package app.pagedrop.ui.kindle

import app.pagedrop.data.local.database.Book
import app.pagedrop.tools.dashboard.DeviceCommandRunner.KindleFile
import app.pagedrop.tools.dashboard.DeviceCommandRunner.KindleLibraryScan
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class KindleLibraryMatcherTest {
    private fun f(dir: String, name: String, size: Long = 1000) = KindleFile("$dir/$name", dir, name, size)
    private val docs = "/mnt/us/documents"
    private val books = "/mnt/us/Books"

    private val documents = listOf(
        "Looking for Alaska - John Green.pdf", "The Hunger Games.mobi", "The Catcher In The Rye.azw3",
        "flowersforalgernon.pdf", "flowersforalgernon.mobi", "of-mice-and-men.azw3", "of-mice-and-men.azw",
        "The Great Gatsby.mobi", "Great Gatsby - F. Scott Fitzgerald.pdf", "KUAL-KDK-2.0.azw2", "My Clippings.txt"
    ).map { f(docs, it) }
    private val koBooks = documents.filter { it.isBook }.map { f(books, it.name) }
    private val all = documents + koBooks

    private fun book(uid: Int, fileName: String, title: String, author: String, size: Long = 1000, last: Long? = null) =
        Book(uid = uid, title = title, author = author, fileName = fileName, filePath = "/x/$fileName",
            format = fileName.substringAfterLast('.').uppercase(), fileSize = size, lastTransferred = last)

    @Test
    fun azw3UploadedAsAzw() {
        val m = KindleLibraryMatcher.matchBooks(listOf(book(1, "of-mice-and-men.azw3", "Of Mice and Men", "Steinbeck")), all)
        assertTrue(m[1]!!.any { it.name == "of-mice-and-men.azw" && it.folder == docs })
        assertTrue(m[1]!!.any { it.folder == books })
    }

    @Test
    fun renamedTitleAuthor() {
        val m = KindleLibraryMatcher.matchBooks(
            listOf(book(2, "alaska.pdf", "Looking for Alaska", "John Green")), all
        )
        assertEquals(listOf("Looking for Alaska - John Green.pdf"), m[2]!!.map { it.name }.distinct())
    }

    @Test
    fun sameSizeSimilarName() {
        val m = KindleLibraryMatcher.matchBooks(
            listOf(book(3, "Gatsby.pdf", "Gatsby", "", size = 4242)),
            listOf(f(docs, "Great Gatsby - F. Scott Fitzgerald.pdf", 4242))
        )
        assertTrue(3 in m)
        val noSize = KindleLibraryMatcher.matchBooks(
            listOf(book(3, "Gatsby.pdf", "Gatsby", "", size = 1)),
            listOf(f(docs, "Great Gatsby - F. Scott Fitzgerald.mobi", 4242))
        )
        assertFalse(3 in noSize)
    }

    @Test
    fun unrelatedAndIgnoredFilesDontMatch() {
        val m = KindleLibraryMatcher.matchBooks(
            listOf(book(4, "Dune.epub", "Dune", "Herbert"), book(5, "KUAL-KDK-2.0.azw2", "KUAL", "")), all
        )
        assertTrue(m.isEmpty())
    }

    @Test
    fun reconcileMarksNewKeepsExistingClearsMissing() {
        val lib = listOf(
            book(1, "The Hunger Games.mobi", "The Hunger Games", "Collins"),            // new
            book(2, "flowersforalgernon.mobi", "Flowers", "Keyes", last = 111L),         // keep
            book(3, "Dune.epub", "Dune", "Herbert", last = 222L),                         // gone -> clear
            book(4, "Emma.epub", "Emma", "Austen")                                        // untouched
        )
        val matches = KindleLibraryMatcher.matchBooks(lib, all)
        val c = KindleLibraryMatcher.reconcile(lib, matches, 999L)
        assertEquals(listOf(1), c.markTransferred)
        assertEquals(listOf(3), c.clearTransferred)
        assertEquals(999L, c.timestamp)
    }

    @Test
    fun reportCountsIgnoreKualAndClippings() {
        val lib = listOf(book(1, "The Hunger Games.mobi", "The Hunger Games", "Collins"))
        val matches = KindleLibraryMatcher.matchBooks(lib, all)
        val r = KindleLibraryMatcher.report(KindleLibraryScan("resume-signal", true, all), matches)
        assertEquals(18, r.files.size)
        // Hunger Games, Alaska, Catcher, flowers, of-mice, Gatsby, Great Gatsby - Fitzgerald
        assertEquals(7, r.bookCount)
        assertEquals(1, r.pageDropCount)
        assertEquals(2, r.pageDropPaths.size)
        assertTrue(r.koreaderRunning)
    }
}

class KindleGroupingTest {
    private fun f(path: String) = app.pagedrop.tools.dashboard.DeviceCommandRunner.KindleFile(
        path, path.substringBeforeLast('/'), path.substringAfterLast('/'), 100
    )

    @org.junit.Test
    fun sameBookInBothFoldersShowsOnce() {
        val files = listOf(
            f("/mnt/us/documents/The Hunger Games.azw"),
            f("/mnt/us/Books/The Hunger Games.azw"),
            f("/mnt/us/Books/Dune.epub")
        )
        val report = KindleLibraryReport(null, false, files, setOf(files[1].path), mapOf(files[1].path to 7), 2, 1)
        val groups = KindleLibraryMatcher.group(report)
        org.junit.Assert.assertEquals(2, groups.size)
        val hg = groups.first { it.title.startsWith("The Hunger") }
        org.junit.Assert.assertEquals(1, hg.homeFiles.size)
        org.junit.Assert.assertEquals(1, hg.koreaderFiles.size)
        org.junit.Assert.assertEquals(7, hg.libraryBookId)
        org.junit.Assert.assertFalse(groups.first { it.title == "Dune" }.inLibrary)
    }
}
