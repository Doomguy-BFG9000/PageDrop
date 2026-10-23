package app.pagedrop.server

/** Output formats the app offers for server conversion. The server's reply is only trusted within this list. */
object ServerFormats {
    /** In menu order (the Add book sheet shows them upper-case). */
    val OFFERED: List<String> = listOf(
        "azw3", "epub", "mobi", "docx", "fb2", "htmlz", "kepub", "lit", "lrf", "pdb",
        "pdf", "pmlz", "rb", "rtf", "snb", "tcr", "txt", "txtz", "zip",
    )

    private val offeredSet = OFFERED.toSet()

    /** The lower-case format when [format] is one the app offers, else null. */
    fun normalize(format: String?): String? = format?.trim()?.lowercase()?.takeIf { it in offeredSet }
}
