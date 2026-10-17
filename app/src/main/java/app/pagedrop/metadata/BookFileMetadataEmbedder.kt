package app.pagedrop.metadata

import java.io.File

/** Embeds title/author/cover into a stored book file, format by format, always on a copy that replaces the original only if valid. */
object BookFileMetadataEmbedder {
    enum class Status { EMBEDDED, UNSUPPORTED, FAILED }

    data class Outcome(val status: Status, val detail: String? = null) {
        val embedded: Boolean get() = status == Status.EMBEDDED
    }

    val SUPPORTED_FORMATS = setOf("MOBI", "AZW3", "AZW", "PDF")

    fun embed(file: File, format: String, title: String, author: String, cover: ByteArray?): Outcome {
        val f = format.uppercase()
        if (f !in SUPPORTED_FORMATS) return Outcome(Status.UNSUPPORTED, "$f metadata cannot be embedded")
        if (!file.isFile) return Outcome(Status.FAILED, "File missing")
        val tmp = File(file.parentFile, ".${file.name}.meta.tmp")
        val authorToWrite = author.takeUnless { BookNameCleaner.isPlaceholderAuthor(it) }
        return try {
            when (f) {
                "PDF" -> PdfMetadataWriter.write(file, tmp, title, authorToWrite)
                else -> MobiMetadataRewriter.rewrite(file, tmp, title, authorToWrite ?: author, cover)
            }
            if (!tmp.renameTo(file)) {
                tmp.copyTo(file, overwrite = true)
                tmp.delete()
            }
            Outcome(Status.EMBEDDED)
        } catch (e: Exception) {
            tmp.delete()
            Outcome(Status.FAILED, e.message ?: e.javaClass.simpleName)
        } catch (e: NoClassDefFoundError) {
            tmp.delete()
            Outcome(Status.FAILED, e.message)
        }
    }

    /** "Title - Author.ext" with characters Kindle's FAT-style storage dislikes removed. */
    fun fileNameFor(title: String, author: String, extension: String): String {
        fun clean(s: String) = s.replace(Regex("[\\\\/:*?\"<>|\\p{Cntrl}]"), " ").replace(Regex("\\s+"), " ").trim().trim('.')
        val t = clean(title).ifEmpty { "Book" }
        val a = clean(author).takeUnless { BookNameCleaner.isPlaceholderAuthor(it) }
        val base = if (a != null) "$t - $a" else t
        return base.take(120).trim().trim('.') + "." + extension.lowercase()
    }
}
