package app.pagedrop.ui.connection

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.SegmentedButton
import androidx.compose.material3.SegmentedButtonDefaults
import androidx.compose.material3.SingleChoiceSegmentedButtonRow
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.unit.dp

/** Stateless Kindle address / port / login fields shared by first-run setup and Connection settings. */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun ConnectionFields(
    host: String,
    port: String,
    username: String,
    password: String,
    onHostChange: (String) -> Unit,
    onPortChange: (String) -> Unit,
    onUsernameChange: (String) -> Unit,
    onPasswordChange: (String) -> Unit,
    modifier: Modifier = Modifier
) {
    var customPort by remember { mutableStateOf(port != "2222" && port != "22") }
    val selected = when {
        customPort -> 2
        port == "22" -> 1
        else -> 0
    }
    val options = listOf("KOReader SSH 2222", "USBNetwork 22", "Custom")

    Column(modifier, verticalArrangement = Arrangement.spacedBy(12.dp)) {
        OutlinedTextField(
            value = host,
            onValueChange = onHostChange,
            label = { Text("Kindle IP address") },
            placeholder = { Text("e.g. 192.168.1.42") },
            singleLine = true,
            keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Uri),
            modifier = Modifier.fillMaxWidth()
        )
        Text("Port", style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
        SingleChoiceSegmentedButtonRow(Modifier.fillMaxWidth()) {
            options.forEachIndexed { index, label ->
                SegmentedButton(
                    selected = selected == index,
                    onClick = {
                        when (index) {
                            0 -> { customPort = false; onPortChange("2222") }
                            1 -> { customPort = false; onPortChange("22") }
                            else -> customPort = true
                        }
                    },
                    shape = SegmentedButtonDefaults.itemShape(index, options.size, MaterialTheme.shapes.small),
                    label = { Text(label, maxLines = 1, style = MaterialTheme.typography.labelSmall) }
                )
            }
        }
        if (customPort) {
            OutlinedTextField(
                value = port,
                onValueChange = { onPortChange(it.filter(Char::isDigit).take(5)) },
                label = { Text("Port number") },
                singleLine = true,
                keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number),
                modifier = Modifier.fillMaxWidth()
            )
        }
        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(12.dp)) {
            OutlinedTextField(
                value = username,
                onValueChange = onUsernameChange,
                label = { Text("User") },
                singleLine = true,
                modifier = Modifier.weight(1f)
            )
            OutlinedTextField(
                value = password,
                onValueChange = onPasswordChange,
                label = { Text("Password (optional)") },
                visualTransformation = PasswordVisualTransformation(),
                singleLine = true,
                modifier = Modifier.weight(1f)
            )
        }
        Text(
            "Leave the password blank if KOReader's \"login without password\" is on.",
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant
        )
    }
}
