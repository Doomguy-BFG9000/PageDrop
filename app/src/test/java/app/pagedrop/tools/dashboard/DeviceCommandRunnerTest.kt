package app.pagedrop.tools.dashboard

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test

class DeviceCommandRunnerTest {

    @Test
    fun repairRecipes_containsAllExpected() {
        val ids = DeviceCommandRunner.repairRecipes.map { it.id }
        assertTrue(ids.contains("fix_stuck_1pct"))
        assertTrue(ids.contains("fix_corrupt_thumbnails"))
        assertTrue(ids.contains("restart_framework"))
        assertTrue(ids.contains("fix_whispernet_sync"))
        assertTrue(ids.contains("fix_jailbreak"))
    }

    @Test
    fun allRepairRecipes_haveCommands() {
        DeviceCommandRunner.repairRecipes.forEach { recipe ->
            assertTrue("${recipe.id}: empty command", recipe.command.isNotBlank())
            assertTrue("${recipe.id}: empty name", recipe.name.isNotBlank())
            assertTrue("${recipe.id}: empty description", recipe.description.isNotBlank())
        }
    }

    @Test
    fun restartFramework_isDangerous() {
        val restart = DeviceCommandRunner.repairRecipes.find { it.id == "restart_framework" }
        assertNotNull(restart)
        assertTrue(restart!!.dangerous)
    }

    @Test
    fun fixStuck1Pct_isSafe() {
        val fix = DeviceCommandRunner.repairRecipes.find { it.id == "fix_stuck_1pct" }
        assertNotNull(fix)
        assertFalse(fix!!.dangerous)
    }

    @Test
    fun fixCorruptThumbnails_containsRescan() {
        val fix = DeviceCommandRunner.repairRecipes.find { it.id == "fix_corrupt_thumbnails" }
        assertNotNull(fix)
        assertTrue(fix!!.command.contains("reScan"))
    }

    @Test
    fun commandResult_parsesCorrectly() {
        val result = DeviceCommandRunner.CommandResult(
            exitCode = 0,
            stdout = "OK",
            stderr = ""
        )
        assertEquals(0, result.exitCode)
        assertEquals("OK", result.stdout)
    }

    @Test
    fun commandResult_errorState() {
        val result = DeviceCommandRunner.CommandResult(
            exitCode = 1,
            stdout = "",
            stderr = "Permission denied"
        )
        assertEquals(1, result.exitCode)
        assertEquals("Permission denied", result.stderr)
    }

    @Test
    fun deviceStatus_defaultState() {
        val status = DeviceCommandRunner.DeviceStatus(
            firmwareVersion = null,
            serialNumber = null,
            freeSpace = null,
            uptime = null,
            batteryLevel = null,
            whispernetBlocked = null,
            otaBlocked = null,
            koreaderInstalled = false
        )
        assertEquals(null, status.firmwareVersion)
        assertEquals(false, status.koreaderInstalled)
    }

    @Test
    fun deviceStatus_withValues() {
        val status = DeviceCommandRunner.DeviceStatus(
            firmwareVersion = "5.14.2",
            serialNumber = "B0A1B2C3D4E5F6G7",
            freeSpace = "2.3G/3.0G",
            uptime = "12d 3h",
            batteryLevel = "67%",
            whispernetBlocked = true,
            otaBlocked = false,
            koreaderInstalled = true
        )
        assertEquals("5.14.2", status.firmwareVersion)
        assertTrue(status.koreaderInstalled)
    }

    @Test
    fun repairRecipe_hasSafeCommands() {
        DeviceCommandRunner.repairRecipes.forEach { recipe ->
            assertFalse("${recipe.id}: dangerous root rm", recipe.command.contains("rm -rf / ") || recipe.command.contains("rm -rf /;"))
        }
    }
}

class LibraryScanParsingTest {
    @Test
    fun parsesFullOutput() {
        val out = """
            lipc-set-prop: lipcErrNoSuchSource (exit 3)
            RESCAN|resume-signal
            KOREADER|1
            FILE|1234|/mnt/us/documents/The Hunger Games.mobi
            FILE|99|/mnt/us/Books/a|b.pdf
            DONE
        """.trimIndent()
        val scan = DeviceCommandRunner.parseLibraryScan(out)!!
        assertEquals("resume-signal", scan.rescanMethod)
        assertTrue(scan.koreaderRunning)
        assertEquals(2, scan.files.size)
        assertEquals("/mnt/us/documents", scan.files[0].folder)
        assertEquals("The Hunger Games.mobi", scan.files[0].name)
        assertEquals(1234L, scan.files[0].sizeBytes)
        assertEquals("a|b.pdf", scan.files[1].name)
    }

    @Test
    fun noRescanMethodIsNotAnError() {
        val scan = DeviceCommandRunner.parseLibraryScan("RESCAN|none\nKOREADER|0\nDONE")!!
        assertEquals(null, scan.rescanMethod)
        assertFalse(scan.koreaderRunning)
        assertTrue(scan.files.isEmpty())
    }

    @Test
    fun incompleteOutputIsNull() {
        assertEquals(null, DeviceCommandRunner.parseLibraryScan("RESCAN|none\nKOREADER|0"))
    }

    @Test
    fun kualAndClippingsAreNotBooks() {
        val scan = DeviceCommandRunner.parseLibraryScan(
            "KOREADER|0\nFILE|5|/mnt/us/documents/KUAL-KDK-2.0.azw2\nFILE|5|/mnt/us/documents/My Clippings.txt\nDONE"
        )!!
        assertTrue(scan.files.none { it.isBook })
    }

    @Test
    fun sharedRescanScriptUsedByTransferAndCheck() {
        assertTrue(DeviceCommandRunner.LIBRARY_CHECK_SCRIPT.contains(DeviceCommandRunner.RESCAN_FALLBACK_SCRIPT))
        assertTrue(DeviceCommandRunner.RESCAN_FALLBACK_SCRIPT.contains("powerd.resuming"))
    }
}
