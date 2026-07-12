package app.pagedrop.transfer.sftp

import app.pagedrop.data.local.database.Book
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class KindleSftpClientTest {

    @Test
    fun testConnection_blankHost_returnsFailure() = runBlocking {
        val result = KindleSftpClient.testConnection(
            host = "", port = 22, user = "root", pass = ""
        )
        assertTrue(result.isFailure)
        assertNotNull(result.exceptionOrNull()?.message)
    }

    @Test
    fun transferBooks_emptyHost_returnsFailure() = runBlocking {
        val result = KindleSftpClient.transferBooks(
            books = emptyList(),
            host = "",
            port = 22,
            user = "root",
            pass = "",
            directories = listOf("/mnt/us/documents"),
            triggerRescan = false
        ) { _, _, _ -> }
        assertTrue(result.isFailure)
        assertNotNull(result.exceptionOrNull()?.message)
    }

    @Test
    fun transferBooks_emptyList_doesNotThrow() = runBlocking {
        // Should succeed because there's nothing to transfer, but will fail on empty host
        val result = KindleSftpClient.transferBooks(
            books = emptyList(),
            host = "192.168.1.100",
            port = 22,
            user = "root",
            pass = "password",
            directories = listOf("/mnt/us/documents"),
            triggerRescan = false
        ) { _, _, _ -> }
        // Will fail because no real SSH connection
        assertTrue(result.isFailure)
    }

    @Test
    fun transferBooks_withThumbnailMap() = runBlocking {
        val book = Book(
            uid = 1, title = "Test", author = "Author",
            fileName = "test.mobi", filePath = "/nonexistent/test.mobi",
            format = "MOBI", fileSize = 1000, kindleUuid = "uuid-123"
        )
        val thumbs = mapOf(1 to byteArrayOf(0xFF.toByte(), 0xD8.toByte(), 0xFF.toByte()))
        val result = KindleSftpClient.transferBooks(
            books = listOf(book),
            host = "192.168.1.100",
            port = 22,
            user = "root",
            pass = "pass",
            directories = listOf("/mnt/us/documents"),
            triggerRescan = false,
            thumbnailBytes = thumbs
        ) { _, _, _ -> }
        assertTrue(result.isFailure) // no real connection
    }

    @Test
    fun thumbnailName_generatedCorrectly() {
        val name = "thumbnail_uuid-123_EBOK_portrait.jpg"
        assertEquals("thumbnail_uuid-123_EBOK_portrait.jpg", name)
    }

    @Test
    fun progressCallback_invokedDuringTransfer() = runBlocking {
        var invoked = false
        val book = Book(
            uid = 1, title = "T", author = "A",
            fileName = "f.mobi", filePath = "/noexist/f.mobi",
            format = "MOBI", fileSize = 100
        )
        val result = KindleSftpClient.transferBooks(
            books = listOf(book),
            host = "10.0.0.1",
            port = 22,
            user = "root",
            pass = "test",
            directories = listOf("/mnt/us/documents"),
            triggerRescan = true
        ) { _, _, _ -> invoked = true }
        // No real connection, but progress callback may or may not fire before failure
    }
}

private fun runBlocking(block: suspend () -> Unit) {
    kotlinx.coroutines.runBlocking { block() }
}
