package app.pagedrop.ui.book

import android.net.Uri
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.selection.selectable
import androidx.compose.foundation.selection.selectableGroup
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Button
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.RadioButton
import androidx.compose.material3.Text
import androidx.compose.material3.rememberModalBottomSheetState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.unit.dp
import app.pagedrop.converter.BookConverter
import app.pagedrop.data.SendFormatDecision
import app.pagedrop.data.SendFormatSetting
import app.pagedrop.data.SendPlan

/** A file the user picked (or an existing library book to convert) waiting for decisions. */
internal data class PendingAdd(
    val uri: Uri,
    val displayName: String,
    val format: String,
    val initialTitle: String,
    val initialAuthor: String = "",
    /** True when converting a book already in the library: no "keep as is" choice. */
    val convertOnly: Boolean = false,
    /** The library book being converted, so the result can offer to remove it. */
    val originalBookId: Int? = null,
)

/** What the Add book sheet decided. [title]/[author] are null when the user left them alone. */
internal data class AddDecision(
    val title: String?,
    val author: String?,
    val coverUrl: String?,
    val convert: Boolean,
    val serverFormat: String,
    val sendToKindle: Boolean,
    /** The user typed a title/author, confirmed a match or picked a cover; auto-detected details do not count. */
    val userEdited: Boolean = false,
)

/** How a picked file will reach the Kindle, in plain language. */
internal enum class AddPlan { KindleReady, Convert, Choice, Other }

private val kindleReadyFormats = setOf("MOBI", "AZW3", "AZW", "PRC")

internal fun addPlanFor(format: String, serverAvailable: Boolean = false): AddPlan {
    val f = format.uppercase()
    return when {
        f in kindleReadyFormats -> AddPlan.KindleReady
        SendFormatDecision.canConvert(f, serverAvailable) -> AddPlan.Choice
        else -> AddPlan.Other
    }
}

private val serverOutputFormats = app.pagedrop.server.ServerFormats.OFFERED.map { it.uppercase() }

@OptIn(ExperimentalMaterial3Api::class)
@Composable
internal fun AddBookSheet(
    pending: PendingAdd,
    serverConversionAvailable: Boolean,
    serverNoticeAnswered: Boolean = true,
    sendFormatSetting: SendFormatSetting = SendFormatSetting.CONVERT,
    lookupState: BookViewModel.MetadataLookupState,
    selectedCoverUri: Uri?,
    onSearch: (String, String) -> Unit,
    onSuggest: () -> Unit,
    onPickCover: () -> Unit,
    onConfirm: (AddDecision) -> Unit,
    onDismiss: () -> Unit,
) {
    val plan = if (pending.convertOnly) AddPlan.Convert else addPlanFor(pending.format, serverConversionAvailable)
    val form = rememberMetadataFormState(pending, pending.initialTitle, pending.initialAuthor, lookupState)
    // Embedded details are cheap and often better than the file name: always look, never overwrite typing.
    LaunchedEffect(pending) { onSuggest() }
    // null = follow the default (the Send format setting); true/false = the user's pick.
    var keepOverride by remember(pending) { mutableStateOf<Boolean?>(null) }
    var serverOutputFormat by remember(pending) { mutableStateOf("MOBI") }
    var formatMenuExpanded by remember { mutableStateOf(false) }
    val sendPlan = SendFormatDecision.decide(
        sourceFormat = pending.format,
        setting = sendFormatSetting,
        keepOverride = keepOverride,
        serverOutput = serverOutputFormat.takeIf { serverConversionAvailable },
        convertOnly = pending.convertOnly,
    )
    val convert = sendPlan != SendPlan.KeepOriginal
    val keepChosen = !convert

    fun decide(send: Boolean) = onConfirm(
        AddDecision(
            title = form.title.trim().takeIf { it.isNotBlank() && it != pending.initialTitle },
            author = form.author.trim().takeIf { it.isNotBlank() && it != pending.initialAuthor },
            coverUrl = form.coverUrl,
            convert = convert,
            serverFormat = serverOutputFormat,
            sendToKindle = send,
            userEdited = form.userEdited || form.matchChosen || selectedCoverUri != null,
        )
    )

    ModalBottomSheet(
        onDismissRequest = onDismiss,
        sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true),
        containerColor = MaterialTheme.colorScheme.surface,
        shape = MaterialTheme.shapes.small,
    ) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 16.dp)
                .padding(bottom = 24.dp)
                .verticalScroll(rememberScrollState()),
            verticalArrangement = Arrangement.spacedBy(16.dp),
        ) {
            Column {
                Text(
                    text = if (pending.convertOnly) "Convert format" else "Add book",
                    style = MaterialTheme.typography.titleLarge,
                )
                Text(
                    text = "${pending.displayName} · ${pending.format.uppercase()}",
                    style = MaterialTheme.typography.labelMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }

            MetadataFields(
                form = form,
                lookupState = lookupState,
                onFindDetails = { onSearch(form.title, form.author) },
                coverLabel = when {
                    selectedCoverUri != null -> "Custom cover set"
                    form.coverUrl != null -> "Online cover selected"
                    lookupState.embeddedCoverPath != null -> "Cover from file"
                    else -> "Choose cover"
                },
                onPickCover = onPickCover,
            )

            HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant)

            FormatDecision(
                plan = plan,
                format = pending.format,
                serverConversionAvailable = serverConversionAvailable,
                serverNoticeAnswered = serverNoticeAnswered,
                keepChosen = keepChosen,
                onKeepChange = { keepOverride = it },
            )

            if (convert && serverConversionAvailable) {
                Box {
                    OutlinedButton(onClick = { formatMenuExpanded = true }, modifier = Modifier.fillMaxWidth()) {
                        Text("Output format: $serverOutputFormat")
                    }
                    DropdownMenu(expanded = formatMenuExpanded, onDismissRequest = { formatMenuExpanded = false }) {
                        if (plan == AddPlan.Choice) {
                            // The original is the first item: no conversion, no upload. It flips the radio above.
                            val original = pending.format.uppercase()
                            DropdownMenuItem(
                                text = { Text("$original (original)") },
                                onClick = {
                                    keepOverride = true
                                    formatMenuExpanded = false
                                },
                            )
                        }
                        serverOutputFormats.filter { !(plan == AddPlan.Choice && it == pending.format.uppercase()) }.forEach { outputFormat ->
                            DropdownMenuItem(
                                text = { Text(outputFormat) },
                                onClick = {
                                    serverOutputFormat = outputFormat
                                    formatMenuExpanded = false
                                },
                            )
                        }
                    }
                }
            }

            Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
                Button(
                    onClick = { decide(send = true) },
                    modifier = Modifier.fillMaxWidth().heightIn(min = 48.dp),
                ) {
                    Text(if (convert && pending.convertOnly) "Convert & send to Kindle" else "Add & send to Kindle")
                }
                OutlinedButton(
                    onClick = { decide(send = false) },
                    modifier = Modifier.fillMaxWidth().heightIn(min = 48.dp),
                ) {
                    Text(if (convert && pending.convertOnly) "Convert & add to library" else "Add to library")
                }
            }
        }
    }
}

@Composable
private fun FormatDecision(
    plan: AddPlan,
    format: String,
    serverConversionAvailable: Boolean,
    serverNoticeAnswered: Boolean,
    keepChosen: Boolean,
    onKeepChange: (Boolean) -> Unit,
) {
    Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
        Text("How it will be added", style = MaterialTheme.typography.titleSmall)
        when (plan) {
            AddPlan.KindleReady -> DecisionText("Kindle-ready — sent as is.")
            AddPlan.Convert -> {
                DecisionText("Will be converted to a Kindle book.")
                DecisionNote(
                    when {
                        !serverConversionAvailable -> "Converted on this phone."
                        !serverNoticeAnswered -> "Converted on this phone or by the PageDrop server — you’ll be asked first."
                        else -> "Converted by your PageDrop server."
                    }
                )
            }
            AddPlan.Other -> DecisionText("This format may not open on your Kindle. It will be sent as is.")
            AddPlan.Choice -> {
                KeepChoiceRadios(format, keepChosen, onKeepChange)
                if (!keepChosen) {
                    DecisionNote(
                        when {
                            !serverConversionAvailable -> "Converted on this phone."
                            !serverNoticeAnswered -> "Converted on this phone or by the PageDrop server — you’ll be asked first."
                            else -> "Converted by your PageDrop server."
                        }
                    )
                }
            }
        }
    }
}

/** One-line reason to keep each convertible format as is. */
internal fun keepNote(format: String): String = when (format.uppercase()) {
    "EPUB" -> "For KOReader. The Kindle’s own reader can’t open EPUB."
    "PDF" -> "Best for scanned or illustrated PDFs."
    "TXT" -> "Plain text; opens on the Kindle and in KOReader."
    else -> "Sent without conversion."
}

/** Keep/convert choice for a convertible file, shared by the Add book and Find a book sheets. */
@Composable
internal fun KeepChoiceRadios(format: String, keep: Boolean, onKeepChange: (Boolean) -> Unit) {
    Column(Modifier.selectableGroup()) {
        RadioRow(
            selected = keep,
            onClick = { onKeepChange(true) },
            title = "Keep as ${format.uppercase()}",
            note = keepNote(format),
        )
        RadioRow(
            selected = !keep,
            onClick = { onKeepChange(false) },
            title = "Convert to Kindle format",
            note = "Opens on the Kindle home screen and in KOReader.",
        )
    }
}

@Composable
private fun DecisionText(text: String) =
    Text(text, style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurface)

@Composable
internal fun DecisionNote(text: String) =
    Text(text, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)

@Composable
internal fun RadioRow(selected: Boolean, onClick: () -> Unit, title: String, note: String) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .heightIn(min = 48.dp)
            .selectable(selected = selected, onClick = onClick, role = Role.RadioButton),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        RadioButton(selected = selected, onClick = null)
        Column(Modifier.padding(start = 12.dp)) {
            Text(title, style = MaterialTheme.typography.bodyMedium)
            DecisionNote(note)
        }
    }
}
