package app.pagedrop.tools.sync

import android.util.Log
import com.jcraft.jsch.ChannelSftp
import com.jcraft.jsch.Session
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.BufferedReader
import java.io.InputStreamReader
import app.pagedrop.transfer.ssh.KindleSsh

object KoreaderSyncBroker {
    private const val TAG = "KoreaderSync"
    private const val DOCUMENTS_DIR = "/mnt/us/documents"

    suspend fun scanSdrFolders(
        host: String,
        port: Int,
        user: String,
        pass: String,
        basePath: String = DOCUMENTS_DIR
    ): Result<List<KoreaderSyncResult>> = withContext(Dispatchers.IO) {
        var session: Session? = null
        var sftp: ChannelSftp? = null
        try {
            session = KindleSsh.connect(host, port, user, pass, 10_000)

            sftp = session.openChannel("sftp") as ChannelSftp
            sftp.connect(10_000)

            val sdrDirs = findSdrDirs(sftp, basePath)
            val results = mutableListOf<KoreaderSyncResult>()

            for (sdrPath in sdrDirs) {
                try {
                    val metadata = readMetadata(sftp, sdrPath)
                    val highlights = readHighlights(sftp, sdrPath)
                    results.add(
                        KoreaderSyncResult(
                            bookPath = sdrPath.removeSuffix(".sdr"),
                            metadata = metadata,
                            highlights = highlights
                        )
                    )
                } catch (e: Exception) {
                    Log.w(TAG, "Failed to read an SDR file: ${e.javaClass.simpleName}")
                    results.add(
                        KoreaderSyncResult(
                            bookPath = sdrPath.removeSuffix(".sdr"),
                            metadata = null,
                            highlights = emptyList(),
                            hasError = true
                        )
                    )
                }
            }

            Result.success(results)
        } catch (e: Exception) {
            Log.e(TAG, "SDR scan failed: ${e.message}", e)
            Result.failure(e)
        } finally {
            sftp?.disconnect()
            session?.disconnect()
        }
    }

    private fun findSdrDirs(sftp: ChannelSftp, basePath: String): List<String> {
        val result = mutableListOf<String>()
        try {
            val entries = sftp.ls(basePath)
            for (entry in entries) {
                val name = entry.filename
                if (name.endsWith(".sdr") && entry.attrs.isDir) {
                    result.add("$basePath/$name")
                }
            }
        } catch (_: Exception) { }
        return result
    }

    private fun readMetadata(sftp: ChannelSftp, sdrPath: String): KoreaderBookMetadata? {
        val contents = readSftpFile(sftp, "$sdrPath/metadata.lua")
            ?: readSftpFile(sftp, "$sdrPath/docsettings.lua")
            ?: return null
        return parseMetadata(contents)
    }

    private fun readHighlights(sftp: ChannelSftp, sdrPath: String): List<KoreaderHighlight> {
        val contents = readSftpFile(sftp, "$sdrPath/sdr.lua")
            ?: readSftpFile(sftp, "$sdrPath/metadata.lua")
            ?: return emptyList()
        return parseHighlights(contents)
    }

    private fun readSftpFile(sftp: ChannelSftp, path: String): String? {
        return try {
            BufferedReader(InputStreamReader(sftp.get(path))).readText()
        } catch (_: Exception) { null }
    }

    internal fun parseMetadata(contents: String): KoreaderBookMetadata? {
        val map = LuaTableParser.parseFlatTable(contents) ?: return null
        val stats = LuaTableParser.parseFlatTable(contents, "stats")
            ?: LuaTableParser.parseFlatTable(contents, "summary")

        return KoreaderBookMetadata(
            title = map["title"],
            authors = map["authors"],
            language = map["language"],
            series = map["series"],
            description = map["description"],
            lastPosition = stats?.get("pages")?.toIntOrNull()
                ?: stats?.get("current_page")?.toIntOrNull(),
            percentFinished = stats?.get("percent_finished")?.toFloatOrNull(),
            totalPages = stats?.get("pages_total")?.toIntOrNull()
                ?: stats?.get("total_pages")?.toIntOrNull()
        )
    }

    internal fun parseHighlights(contents: String): List<KoreaderHighlight> {
        val results = mutableListOf<KoreaderHighlight>()
        val bookmarkSection = LuaTableParser.extractSection(contents, "bookmarks")
            ?: return results

        val entries = LuaTableParser.splitLuaEntries(bookmarkSection)
        for (entry in entries) {
            val map = LuaTableParser.parseFlatTable(entry) ?: continue
            val text = map["text"] ?: map["notes"] ?: continue
            if (text.isBlank()) continue

            results.add(
                KoreaderHighlight(
                    text = text,
                    chapter = map["chapter"] ?: map["title"],
                    position = map["page"]?.toIntOrNull()
                        ?: map["pos0"]?.toIntOrNull(),
                    timestamp = map["datetime"]?.toLongOrNull()
                        ?: map["time"]?.toLongOrNull(),
                    note = map["note"]?.takeIf { it.isNotBlank() },
                    chapterProgress = map["percent"]?.toFloatOrNull()
                )
            )
        }
        return results
    }
}
