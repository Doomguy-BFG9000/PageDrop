package app.pagedrop.ui.onboarding

import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.safeDrawingPadding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.unit.dp
import androidx.hilt.lifecycle.viewmodel.compose.hiltViewModel
import app.pagedrop.R
import app.pagedrop.transfer.discovery.DiscoveredDevice
import app.pagedrop.ui.connection.ConnectionFields
import app.pagedrop.ui.shell.HairlineCard
import app.pagedrop.ui.shell.InkButton
import app.pagedrop.ui.shell.OutlineButton
import app.pagedrop.ui.shell.ResultBanner

/**
 * "Connect your Kindle". Shown on first run (with "Set up later") and reused from the
 * Library's "Kindle not connected" banner (with a back arrow, [canGoBack] = true).
 */
@Composable
fun SetupScreen(
    modifier: Modifier = Modifier,
    canGoBack: Boolean = false,
    onBack: () -> Unit = {},
    onDone: () -> Unit,
    onSkip: () -> Unit,
    viewModel: SetupViewModel = hiltViewModel()
) {
    val state by viewModel.state.collectAsState()

    Box(modifier.fillMaxSize().background(Color.Black)) {
        // Same halftone print as the Library, dimmed so the paper card reads clearly.
        Image(
            painter = painterResource(R.drawable.bezos_bg),
            contentDescription = null,
            contentScale = ContentScale.Crop,
            modifier = Modifier.fillMaxSize().alpha(0.55f)
        )
        Column(
            Modifier
                .fillMaxSize()
                .safeDrawingPadding()
                .verticalScroll(rememberScrollState())
                .padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(16.dp)
        ) {
            if (canGoBack) {
                IconButton(onClick = onBack) {
                    Icon(Icons.AutoMirrored.Filled.ArrowBack, "Back", tint = Color.White)
                }
            } else {
                Spacer(Modifier.height(24.dp))
            }
            Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
                Text("PageDrop", style = MaterialTheme.typography.headlineLarge, color = Color.White)
                Text(
                    "Connect your Kindle",
                    style = MaterialTheme.typography.titleLarge,
                    color = Color.White.copy(alpha = 0.85f)
                )
                Text(
                    "Your Kindle needs KOReader with its SSH server on, and must share this phone's Wi-Fi.",
                    style = MaterialTheme.typography.bodyMedium,
                    color = Color.White.copy(alpha = 0.7f)
                )
            }

            HairlineCard(Modifier.fillMaxWidth()) {
                Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
                    Text("Step 1 · Find your Kindle", style = MaterialTheme.typography.titleMedium)
                    Text(
                        "PageDrop checks your Wi-Fi network for a Kindle running KOReader.",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                    if (state.isScanning) {
                        OutlineButton("Stop scanning", viewModel::stopScan, Modifier.fillMaxWidth())
                        LinearProgressIndicator(
                            progress = { state.scanProgress },
                            modifier = Modifier.fillMaxWidth(),
                            trackColor = MaterialTheme.colorScheme.outlineVariant
                        )
                    } else {
                        InkButton("Find my Kindle", viewModel::findMyKindle, Modifier.fillMaxWidth())
                    }
                    state.scanError?.let {
                        Text(it, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.error)
                    }
                    state.devices.forEach { device ->
                        DeviceRow(device, selected = device.host == state.host && device.port.toString() == state.port) {
                            viewModel.pick(device)
                        }
                    }
                    if (state.scanFinished && state.devices.isEmpty()) {
                        Text(
                            "Nothing found. Check that KOReader's SSH server is on (Tools > More tools > SSH server) and the Kindle is awake, or enter the address below.",
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                    }
                }
            }

            HairlineCard(Modifier.fillMaxWidth()) {
                Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
                    Text("Step 2 · Check the details", style = MaterialTheme.typography.titleMedium)
                    ConnectionFields(
                        host = state.host,
                        port = state.port,
                        username = state.username,
                        password = state.password,
                        onHostChange = viewModel::updateHost,
                        onPortChange = viewModel::updatePort,
                        onUsernameChange = viewModel::updateUsername,
                        onPasswordChange = viewModel::updatePassword
                    )
                    OutlineButton(
                        if (state.isTesting) "Testing..." else "Test connection",
                        viewModel::testConnection,
                        Modifier.fillMaxWidth(),
                        enabled = !state.isTesting && state.canSave
                    )
                    when (state.testSuccess) {
                        true -> ResultBanner(true, "Connected", "PageDrop can reach your Kindle. Tap Save to continue.")
                        false -> ResultBanner(false, state.testHint?.title ?: "Couldn't connect", state.testHint?.fix)
                        null -> {}
                    }
                }
            }

            InkButton(
                "Save",
                onClick = { viewModel.save(); onDone() },
                modifier = Modifier.fillMaxWidth(),
                enabled = state.canSave
            )
            if (!canGoBack) {
                TextButton(
                    onClick = { viewModel.skip(); onSkip() },
                    modifier = Modifier.align(Alignment.CenterHorizontally)
                ) { Text("Set up later", color = Color.White) }
            }
        }
    }
}

@Composable
private fun DeviceRow(device: DiscoveredDevice, selected: Boolean, onClick: () -> Unit) {
    HairlineCard(
        Modifier.fillMaxWidth(),
        onClick = onClick,
        containerColor = if (selected) MaterialTheme.colorScheme.surfaceVariant else MaterialTheme.colorScheme.surface
    ) {
        Row(Modifier.padding(12.dp), verticalAlignment = Alignment.CenterVertically) {
            Column(Modifier.weight(1f)) {
                Text("${device.host}:${device.port}", style = MaterialTheme.typography.titleSmall)
                Text(
                    if (device.looksLikeKindle) "Looks like a Kindle" else device.banner.ifBlank { "SSH server" },
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }
            Text(if (selected) "Selected" else "Use this", style = MaterialTheme.typography.labelMedium)
        }
    }
}
