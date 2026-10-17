package app.pagedrop.ui.book

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.Button
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp

/** Bottom action bar of selection mode: "Send N to Kindle" and Delete. */
@Composable
internal fun SelectionBar(
    selectedCount: Int,
    sending: Boolean,
    deleteBlocked: Boolean = false,
    onSend: () -> Unit,
    onDelete: () -> Unit,
    modifier: Modifier = Modifier,
) {
    Surface(
        modifier = modifier.fillMaxWidth(),
        color = MaterialTheme.colorScheme.surface,
        border = BorderStroke(1.dp, MaterialTheme.colorScheme.outlineVariant),
        tonalElevation = 0.dp,
        shadowElevation = 0.dp,
    ) {
        Row(
            modifier = Modifier.padding(horizontal = 16.dp, vertical = 12.dp),
            horizontalArrangement = Arrangement.spacedBy(12.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Button(
                onClick = onSend,
                enabled = selectedCount > 0 && !sending,
                modifier = Modifier.weight(1f).heightIn(min = 48.dp),
            ) {
                Text(if (selectedCount == 1) "Send 1 to Kindle" else "Send $selectedCount to Kindle")
            }
            OutlinedButton(
                onClick = onDelete,
                enabled = selectedCount > 0 && !deleteBlocked,
                modifier = Modifier.heightIn(min = 48.dp),
            ) {
                Text("Delete")
            }
            if (deleteBlocked) {
                Text(
                    text = "Some selected books are being sent",
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        }
    }
}
