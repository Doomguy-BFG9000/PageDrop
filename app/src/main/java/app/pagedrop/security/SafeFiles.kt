package app.pagedrop.security

import java.io.File

/**
 * Turns untrusted names (a share provider's display name, a server-returned title or format, an archive entry)
 * into files that can only ever land inside the directory they were meant for.
 */
object SafeFiles {
    private const val MAX_BASE_LENGTH = 120
    private val UNSAFE_CHARS = Regex("[\\p{Cntrl}\\\\/:*?\"<>|]")
    private val SPACES = Regex("\\s+")

    /** True when [file], resolved canonically (following symlinks and `..`), is strictly inside [dir]. */
    fun isInside(dir: File, file: File): Boolean {
        val root = dir.canonicalFile.toPath()
        val target = file.canonicalFile.toPath()
        return target != root && target.startsWith(root)
    }

    /**
     * `dir/name` after checking that [name] is one plain path component and the result stays inside [dir].
     * Throws [SecurityException] otherwise.
     */
    fun childOf(dir: File, name: String): File {
        if (name.isEmpty() || name == "." || name == ".." || '/' in name || '\\' in name || '\u0000' in name) {
            throw SecurityException("Unsafe file name")
        }
        val file = File(dir, name)
        // A dangling symlink isn't resolved by canonicalFile, so refuse any existing link outright.
        if (java.nio.file.Files.isSymbolicLink(file.toPath()) || !isInside(dir, file)) {
            throw SecurityException("File would leave its directory")
        }
        return file
    }

    /** A plain base name: no directories, separators, control characters or leading dots, at most 120 chars. */
    fun safeBaseName(raw: String?, fallback: String = "book"): String {
        val lastSegment = raw.orEmpty().substringAfterLast('/').substringAfterLast('\\')
        val cleaned = lastSegment
            .replace(UNSAFE_CHARS, " ")
            .replace(SPACES, " ")
            .trim()
            .trimStart('.', ' ')
            .take(MAX_BASE_LENGTH)
            .trimEnd('.', ' ')
        return cleaned.ifEmpty { fallback }
    }

    /**
     * "base.ext" built from an untrusted [displayName]: the base is [safeBaseName] and the extension is kept only when
     * it (lowercased) is one of [allowedExtensions]. Any other extension is dropped together with its dot.
     */
    fun safeFileName(displayName: String?, allowedExtensions: Set<String>, fallbackBase: String = "book"): String {
        val last = displayName.orEmpty().substringAfterLast('/').substringAfterLast('\\').trim()
        val ext = last.substringAfterLast('.', "").lowercase()
        val base = safeBaseName(if ('.' in last) last.substringBeforeLast('.') else last, fallbackBase)
        return if (ext.isNotEmpty() && ext in allowedExtensions) "$base.$ext" else base
    }

    /** A file named like [name] inside [dir] that does not exist yet ("name_1.ext", "name_2.ext", ...). */
    fun uniqueChild(dir: File, name: String): File {
        var file = childOf(dir, name)
        if (!file.exists()) return file
        val dot = name.lastIndexOf('.')
        val baseName = if (dot > 0) name.substring(0, dot) else name
        val extension = if (dot > 0) name.substring(dot + 1) else ""
        var counter = 1
        while (file.exists()) {
            val newName = if (extension.isNotEmpty()) "${baseName}_$counter.$extension" else "${baseName}_$counter"
            file = childOf(dir, newName)
            counter++
        }
        return file
    }
}
