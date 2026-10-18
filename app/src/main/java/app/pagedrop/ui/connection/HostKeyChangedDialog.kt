package app.pagedrop.ui.connection

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.unit.dp
import app.pagedrop.transfer.ssh.KindleSsh

/**
 * Shown over any screen when a Kindle's SSH host key no longer matches the one PageDrop pinned.
 * Whatever action hit it has already failed; after trusting the key the user retries it.
 */
@Composable
fun HostKeyChangedDialog() {
    val change by KindleSsh.hostKeyChange.collectAsState()
    val current = change ?: return
    AlertDialog(
        onDismissRequest = KindleSsh::dismissHostKeyChange,
        title = { Text("Is this still your Kindle?") },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                Text("The Kindle at ${current.host} identified itself with a different key than last time, so PageDrop stopped before sending anything.")
                Text("That's expected after reinstalling KOReader or resetting the Kindle. If you didn't, another device on this network may be pretending to be your Kindle: don't trust it.")
                Text("Before: ${current.pinnedFingerprint}\nNow: ${current.presentedFingerprint}", style = MaterialTheme.typography.bodySmall, fontFamily = FontFamily.Monospace)
            }
        },
        confirmButton = {
            TextButton(onClick = { KindleSsh.trustNewKey(current) }) { Text("This is my Kindle, trust the new key") }
        },
        dismissButton = {
            TextButton(onClick = KindleSsh::dismissHostKeyChange) { Text("Don't connect") }
        },
    )
}
