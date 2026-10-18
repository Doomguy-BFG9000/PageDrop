package app.pagedrop.ui.settings

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.DialogProperties
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import app.pagedrop.server.ServerUploadConsent

/**
 * One line under the Settings switch; says the same as [ServerUploadNotice].
 * The retention claim ("deleted 24 hours after the job") is enforced by the server-side cleanup job, so this copy
 * is only accurate once that server change is deployed; keep the two in step.
 */
internal const val SERVER_UPLOAD_SUMMARY =
    "Sends book files, covers and article links to PageDrop’s conversion server, run by the developer. The server deletes uploaded files and results 24 hours after the job."

/**
 * Shown once, before the first book file, cover or article link is uploaded for conversion. It needs an explicit choice, so back and
 * outside taps don't dismiss it; the waiting conversion continues with whichever answer is picked.
 */
@Composable
fun ServerUploadNotice(consent: ServerUploadConsent) {
    val pending by consent.pending.collectAsStateWithLifecycle()
    if (!pending) return
    AlertDialog(
        onDismissRequest = {},
        properties = DialogProperties(dismissOnBackPress = false, dismissOnClickOutside = false),
        shape = MaterialTheme.shapes.small,
        title = { Text("Convert on PageDrop’s server?") },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                Text("To convert this, PageDrop can upload book files, their covers and article links to PageDrop’s conversion server, run by the developer.")
                Text("The server deletes uploaded files and results 24 hours after the job.")
                Text(
                    "Or keep everything on your phone and convert books and articles here instead. You can change this later in Settings.",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        },
        confirmButton = { TextButton(onClick = { consent.answer(true) }) { Text("Use the server") } },
        dismissButton = { TextButton(onClick = { consent.answer(false) }) { Text("Keep everything on my phone") } },
    )
}
