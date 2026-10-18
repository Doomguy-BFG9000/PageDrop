package app.pagedrop.tools.dashboard

import android.util.Log
import com.jcraft.jsch.ChannelExec
import com.jcraft.jsch.Session
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.BufferedReader
import java.io.InputStreamReader
import app.pagedrop.transfer.ssh.KindleSsh

object DeviceCommandRunner {
    private const val TAG = "DeviceCommandRunner"
    private const val EXEC_TIMEOUT_MS = 5000

    data class CommandResult(
        val exitCode: Int,
        val stdout: String,
        val stderr: String
    )

    data class DeviceStatus(
        val firmwareVersion: String?,
        val serialNumber: String?,
        val freeSpace: String?,
        val uptime: String?,
        val batteryLevel: String?,
        val whispernetBlocked: Boolean?,
        val otaBlocked: Boolean?,
        val koreaderInstalled: Boolean
    )

    suspend fun runCommand(
        host: String,
        port: Int,
        user: String,
        pass: String,
        command: String,
        timeoutMs: Int = EXEC_TIMEOUT_MS
    ): CommandResult = withContext(Dispatchers.IO) {
        var session: Session? = null
        var execChannel: ChannelExec? = null
        try {
            session = KindleSsh.connect(host, port, user, pass, 10_000)

            execChannel = session.openChannel("exec") as ChannelExec
            execChannel.setCommand(command)
            execChannel.connect()

            val stdout = BufferedReader(InputStreamReader(execChannel.inputStream)).readText()
            val stderr = BufferedReader(InputStreamReader(execChannel.errStream)).readText()

            var elapsed = 0
            while (!execChannel.isClosed && elapsed < timeoutMs) {
                Thread.sleep(100)
                elapsed += 100
            }

            CommandResult(
                exitCode = execChannel.exitStatus,
                stdout = stdout.trim(),
                stderr = stderr.trim()
            )
        } catch (e: Exception) {
            Log.e(TAG, "Command failed: $command → ${e.message}")
            CommandResult(exitCode = -1, stdout = "", stderr = e.message ?: "Unknown error")
        } finally {
            execChannel?.disconnect()
            session?.disconnect()
        }
    }

    suspend fun getDeviceStatus(
        host: String,
        port: Int,
        user: String,
        pass: String
    ): DeviceStatus = withContext(Dispatchers.IO) {
        val firmware = runCommand(host, port, user, pass, "cat /etc/prettyversion.txt 2>/dev/null || cat /etc/version.txt 2>/dev/null")
        val serial = runCommand(host, port, user, pass, "cat /proc/usid 2>/dev/null || cat /var/local/java/prefs/com.amazon.ebook.framework/prefs 2>/dev/null | grep -i serial || echo ''")
        val disk = runCommand(host, port, user, pass, "df -h /mnt/us 2>/dev/null | tail -1")
        val uptimeCmd = runCommand(host, port, user, pass, "cat /proc/uptime 2>/dev/null | awk '{print int(\$1)}'")
        val battery = runCommand(host, port, user, pass, "gasgauge-info -s 2>/dev/null || cat /sys/class/power_supply/bq27510/capacity 2>/dev/null || echo ''")
        val whispernet = runCommand(host, port, user, pass, "test -f /etc/upstart/whispernet.conf && echo 'active' || echo 'blocked'")
        val ota = runCommand(host, port, user, pass, "test -d /etc/uks && echo 'blocked' || echo 'active'")
        val koreader = runCommand(host, port, user, pass, "test -d /mnt/us/koreader && echo 'yes' || echo ''")

        fun parseSpace(dfLine: String): String? {
            if (dfLine.isBlank()) return null
            val parts = dfLine.split(Regex("\\s+"))
            return if (parts.size >= 4) "${parts[3]}/${parts[1]}" else null
        }

        DeviceStatus(
            firmwareVersion = firmware.stdout.ifBlank { null },
            serialNumber = serial.stdout.ifBlank { null },
            freeSpace = parseSpace(disk.stdout),
            uptime = uptimeCmd.stdout.toLongOrNull()?.let { formatUptime(it) },
            batteryLevel = battery.stdout.trim().ifBlank { null }?.let {
                it.replace("%", "").toIntOrNull()?.let { l -> "$l%" } ?: it
            },
            whispernetBlocked = whispernet.stdout.contains("blocked"),
            otaBlocked = ota.stdout.contains("blocked"),
            koreaderInstalled = koreader.stdout.contains("yes")
        )
    }

    /**
     * Shell fragment shared with the post-transfer rescan. Tries each rescan method in order and
     * prints `RESCAN|<method>` for the first that works (`RESCAN|none` otherwise). A method that
     * does not exist on this firmware (lipc exit 3 "lipcErrNoSuchSource") is simply skipped.
     */
    const val RESCAN_FALLBACK_SCRIPT: String =
        "if lipc-set-prop com.lab126.amznAssetsMgrService reScan 1 >/dev/null 2>&1; then echo 'RESCAN|assets-manager'; " +
            "elif lipc-set-prop com.lab126.scanner doFullScan 1 >/dev/null 2>&1; then echo 'RESCAN|scanner'; " +
            "elif dbus-send --system /default com.lab126.powerd.resuming int32:1 >/dev/null 2>&1; then echo 'RESCAN|resume-signal'; " +
            "else echo 'RESCAN|none'; fi"

    private const val BOOK_FOLDERS = "/mnt/us/documents /mnt/us/Books"

    /** Full script run by [refreshKindleLibrary]: rescan, KOReader check, then a sized file listing. */
    val LIBRARY_CHECK_SCRIPT: String = listOf(
        RESCAN_FALLBACK_SCRIPT,
        "n=\$(ps | grep -c '[r]eader.lua' 2>/dev/null)",
        "if [ \"\${n:-0}\" -gt 0 ] 2>/dev/null; then echo 'KOREADER|1'; else echo 'KOREADER|0'; fi",
        "for d in $BOOK_FOLDERS; do",
        "  [ -d \"\$d\" ] || continue",
        "  find \"\$d\" -maxdepth 2 -type f \\( -iname '*.azw*' -o -iname '*.mobi' -o -iname '*.pdf' " +
            "-o -iname '*.epub' -o -iname '*.txt' -o -iname '*.prc' \\) 2>/dev/null | while read -r f; do",
        "    case \"\$f\" in *.sdr/*) continue;; esac",
        "    echo \"FILE|\$(wc -c < \"\$f\" | tr -d ' ')|\$f\"",
        "  done",
        "done",
        "echo 'DONE'"
    ).joinToString("\n")

    data class KindleFile(val path: String, val folder: String, val name: String, val sizeBytes: Long) {
        /** KUAL's .azw2 launcher and the Kindle's clippings file are not books. */
        val isBook: Boolean
            get() = !name.endsWith(".azw2", ignoreCase = true) && !name.equals("My Clippings.txt", ignoreCase = true)
    }

    data class KindleLibraryScan(
        /** Which rescan method worked, or null if none did (not an error). */
        val rescanMethod: String?,
        val koreaderRunning: Boolean,
        val files: List<KindleFile>
    )

    /** Parses [LIBRARY_CHECK_SCRIPT] output; null when the script did not run to completion. */
    fun parseLibraryScan(stdout: String): KindleLibraryScan? {
        var method: String? = null
        var koreader = false
        var done = false
        val files = mutableListOf<KindleFile>()
        for (raw in stdout.lines()) {
            val line = raw.trimEnd('\r')
            when {
                line.startsWith("RESCAN|") ->
                    method = line.removePrefix("RESCAN|").trim().takeIf { it.isNotEmpty() && it != "none" }
                line.startsWith("KOREADER|") -> koreader = line.removePrefix("KOREADER|").trim() == "1"
                line.startsWith("FILE|") -> {
                    val parts = line.split('|', limit = 3)
                    if (parts.size == 3 && parts[2].isNotBlank()) {
                        val path = parts[2]
                        files += KindleFile(
                            path = path,
                            folder = path.substringBeforeLast('/', ""),
                            name = path.substringAfterLast('/'),
                            sizeBytes = parts[1].trim().toLongOrNull() ?: 0L
                        )
                    }
                }
                line.trim() == "DONE" -> done = true
                // Anything else (e.g. a lipc "lipcErrNoSuchSource" line) is noise, not an error.
            }
        }
        return if (done) KindleLibraryScan(method, koreader, files.distinctBy { it.path }) else null
    }

    /** One SSH session: asks the Kindle to rescan, checks KOReader, lists books. Fails only on real connection problems. */
    suspend fun refreshKindleLibrary(
        host: String, port: Int, user: String, pass: String
    ): Result<KindleLibraryScan> {
        val result = runCommand(host, port, user, pass, LIBRARY_CHECK_SCRIPT, timeoutMs = 30_000)
        val scan = parseLibraryScan(result.stdout)
        return when {
            scan != null -> Result.success(scan)
            else -> Result.failure(
                IllegalStateException(result.stderr.ifBlank { "The Kindle didn't answer the library check." })
            )
        }
    }

    /**
     * Deletes books (and optionally their `.sdr` reading-progress folders) in one SSH exec. Every path is validated
     * on the phone first and must be in [allowedPaths] (the latest listing). Fails with a plain-English message
     * when nothing could be sent; partial failures come back in [KindleDelete.Outcome].
     */
    suspend fun deleteFromKindle(
        host: String, port: Int, user: String, pass: String,
        paths: List<String>,
        deleteSidecars: Boolean,
        allowedPaths: Set<String>,
        thumbnailIds: List<String> = emptyList()
    ): Result<KindleDelete.Outcome> {
        val script = try {
            KindleDelete.buildScript(paths, deleteSidecars, allowedPaths, thumbnailIds)
        } catch (e: IllegalArgumentException) {
            return Result.failure(e)
        }
        val result = runCommand(host, port, user, pass, script, timeoutMs = 30_000)
        if (result.exitCode == -1 && result.stdout.isBlank()) {
            return Result.failure(IllegalStateException(result.stderr.ifBlank { "The Kindle didn't answer." }))
        }
        return Result.success(KindleDelete.parseOutput(result.stdout, paths))
    }

    suspend fun rebootDevice(
        host: String, port: Int, user: String, pass: String
    ): CommandResult = runCommand(
        host, port, user, pass, "reboot"
    )

    suspend fun toggleWhispernetBlock(
        host: String, port: Int, user: String, pass: String
    ): CommandResult {
        val current = runCommand(host, port, user, pass, "test -f /etc/upstart/whispernet.conf && echo 'on' || echo 'off'")
        return if (current.stdout.contains("on")) {
            runCommand(host, port, user, pass, "mv /etc/upstart/whispernet.conf /etc/upstart/whispernet.conf.disabled")
        } else {
            runCommand(host, port, user, pass, "mv /etc/upstart/whispernet.conf.disabled /etc/upstart/whispernet.conf")
        }
    }

    suspend fun toggleOtaBlock(
        host: String, port: Int, user: String, pass: String
    ): CommandResult {
        val current = runCommand(host, port, user, pass, "test -d /etc/uks && echo 'blocked' || echo 'active'")
        return if (current.stdout.contains("blocked")) {
            runCommand(host, port, user, pass, "mv /etc/uks.disabled /etc/uks 2>/dev/null; echo 'restored'")
        } else {
            runCommand(host, port, user, pass, "mv /etc/uks /etc/uks.disabled 2>/dev/null; echo 'blocked'")
        }
    }

    // ── Repair recipes ──

    data class RepairRecipe(
        val id: String,
        val name: String,
        val description: String,
        val command: String,
        val dangerous: Boolean = false
    )

    val repairRecipes = listOf(
        RepairRecipe(
            id = "fix_stuck_1pct",
            name = "Fix 'Stuck at 1%' downloads",
            description = "Clears corrupted download cache and restarts the download manager.",
            command = "rm -rf /mnt/us/.active-content-data /var/local/cc.db 2>/dev/null; " +
                    "restart framework 2>/dev/null || lipc-set-prop com.lab126.appmgrd start app://com.lab126.booklet.downloader 2>/dev/null"
        ),
        RepairRecipe(
            id = "fix_corrupt_thumbnails",
            name = "Rebuild Thumbnail Cache",
            description = "Clears corrupt thumbnail cache so Kindle regenerates cover art.",
            command = "rm -rf /mnt/us/system/thumbnails/* 2>/dev/null; " +
                    "lipc-set-prop com.lab126.amznAssetsMgrService reScan 1"
        ),
        RepairRecipe(
            id = "restart_framework",
            name = "Restart Kindle Framework",
            description = "Restarts the Kindle UI / Java framework. Screen will flash briefly.",
            command = "restart framework",
            dangerous = true
        ),
        RepairRecipe(
            id = "fix_whispernet_sync",
            name = "Fix WhisperSync/Sync Errors",
            description = "Clears sync cache and restarts communication services.",
            command = "rm -rf /var/local/keystore /var/local/cc.db 2>/dev/null; " +
                    "restart todo 2>/dev/null; " +
                    "lipc-set-prop com.lab126.whispernet enable 1 2>/dev/null"
        ),
        RepairRecipe(
            id = "fix_jailbreak",
            name = "Reinstall Hotfix",
            description = "Re-applies the jailbreak hotfix bridge. Safe to run repeatedly.",
            command = "test -f /mnt/us/MRJ-thf.bin && " +
                    "lipc-set-prop com.lab126.ota startUpdate 1 2>/dev/null || " +
                    "echo 'Hotfix bin not found in /mnt/us'"
        )
    )

    suspend fun runRepair(
        recipe: RepairRecipe,
        host: String, port: Int, user: String, pass: String
    ): CommandResult = runCommand(host, port, user, pass, recipe.command)
}

private fun formatUptime(seconds: Long): String {
    val days = seconds / 86400
    val hours = (seconds % 86400) / 3600
    val mins = (seconds % 3600) / 60
    return when {
        days > 0 -> "${days}d ${hours}h"
        hours > 0 -> "${hours}h ${mins}m"
        else -> "${mins}m"
    }
}
