package app.pagedrop.ui.book

import app.pagedrop.data.local.database.Book
import app.pagedrop.ui.components.StatusTone
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/** Where a library book stands in the current send-to-Kindle batch. */
internal enum class BookTransfer { Idle, Sending, Failed }

internal data class BookStatusText(val text: String, val tone: StatusTone)

/** Library filter chips: All, Not on Kindle, On Kindle. */
internal enum class LibraryFilter(val label: String) {
    All("All"),
    NotOnKindle("Not on Kindle"),
    OnKindle("On Kindle");

    fun matches(book: Book): Boolean = when (this) {
        All -> true
        NotOnKindle -> book.lastTransferred == null
        OnKindle -> book.lastTransferred != null
    }
}

internal enum class LibraryViewMode { List, Grid }

internal fun bookStatus(book: Book, transfer: BookTransfer): BookStatusText = when {
    transfer == BookTransfer.Sending -> BookStatusText("Sending…", StatusTone.Active)
    transfer == BookTransfer.Failed -> BookStatusText("Couldn’t send · try again", StatusTone.Error)
    book.lastTransferred != null ->
        BookStatusText("On Kindle · ${formatShortDate(book.lastTransferred)}", StatusTone.Normal)
    else -> BookStatusText("Not sent yet", StatusTone.Normal)
}

internal fun formatShortDate(millis: Long, locale: Locale = Locale.getDefault()): String =
    SimpleDateFormat("d MMM", locale).format(Date(millis))
