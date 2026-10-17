package app.pagedrop.ui.kindle

import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Box
import androidx.compose.ui.graphics.Color
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.foundation.background
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Settings
import androidx.compose.material.icons.filled.Sync
import androidx.compose.material.icons.filled.Wifi
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.unit.dp
import androidx.hilt.lifecycle.viewmodel.compose.hiltViewModel
import app.pagedrop.tools.dashboard.DeviceCommandRunner
import app.pagedrop.ui.connection.ConnectionHints
import app.pagedrop.ui.shell.HairlineCard
import app.pagedrop.ui.shell.InkButton
import app.pagedrop.ui.shell.NavRow
import app.pagedrop.ui.shell.OutlineButton
import app.pagedrop.ui.shell.ResultBanner
import app.pagedrop.ui.shell.SectionLabel
import app.pagedrop.ui.tools.dashboard.DashboardViewModel

/** Kindle tab: connection status, device info, actions, repair recipes, and sync/connection entries. */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun KindleScreen(
    modifier: Modifier = Modifier,
    onOpenSettings: () -> Unit,
    onOpenSync: () -> Unit,
    onOpenConnection: () -> Unit,
    onOpenSetup: () -> Unit,
    viewModel: DashboardViewModel = hiltViewModel()
) {
    val state by viewModel.state.collectAsState()
    var confirmReboot by remember { mutableStateOf(false) }
    var confirmRepair by remember { mutableStateOf<DeviceCommandRunner.RepairRecipe?>(null) }

    LaunchedEffect(Unit) { viewModel.refresh() }

    Scaffold(
        modifier = modifier,
        topBar = {
            TopAppBar(
                title = { Text("Kindle") },
                actions = {
                    IconButton(onClick = onOpenSettings) { Icon(Icons.Default.Settings, "Settings") }
                },
                colors = TopAppBarDefaults.topAppBarColors(
                    containerColor = MaterialTheme.colorScheme.background,
                    titleContentColor = MaterialTheme.colorScheme.onSurface
                )
            )
        },
        containerColor = MaterialTheme.colorScheme.background
    ) { innerPadding ->
        Column(
            Modifier
                .fillMaxSize()
                .padding(innerPadding)
                .verticalScroll(rememberScrollState())
                .padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp)
        ) {
            StatusCard(state, viewModel.host, viewModel.port, onRefresh = viewModel::refresh, onSetup = onOpenSetup)

            if (state.isConfigured) {
                state.lastResult?.let { result ->
                    val ok = result.exitCode == 0
                    ResultBanner(
                        success = ok,
                        title = if (ok) "Done" else "That didn't work",
                        detail = (if (ok) result.stdout else result.stderr).ifBlank { null }
                    )
                }

                SectionLabel("Actions")
                Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                    OutlineButton("Rescan library", viewModel::triggerRescan, Modifier.weight(1f), enabled = state.reachable == true)
                    OutlineButton("Restart Kindle", { confirmReboot = true }, Modifier.weight(1f), enabled = state.reachable == true)
                }
            }

            SectionLabel("More")
            NavRow("KOReader sync", "Import highlights and reading progress", Icons.Default.Sync, onOpenSync)
            NavRow("Connection settings", "Address, port and login", Icons.Default.Wifi, onOpenConnection)

            if (state.isConfigured && state.reachable == true) {
                SectionLabel("Repair")
                viewModel.repairRecipes.forEach { recipe ->
                    HairlineCard(Modifier.fillMaxWidth()) {
                        Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                            Text(recipe.name, style = MaterialTheme.typography.titleSmall)
                            Text(
                                recipe.description,
                                style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant
                            )
                            TextButton(onClick = { confirmRepair = recipe }) {
                                Text(if (recipe.dangerous) "Run (advanced)" else "Run", color = MaterialTheme.colorScheme.onSurface)
                            }
                        }
                    }
                }
            }
        }
    }

    if (confirmReboot) {
        AlertDialog(
            onDismissRequest = { confirmReboot = false },
            title = { Text("Restart Kindle?") },
            text = { Text("The Kindle will restart and the connection drops for a minute or two.") },
            confirmButton = {
                TextButton(onClick = { viewModel.reboot(); confirmReboot = false }) { Text("Restart") }
            },
            dismissButton = { TextButton(onClick = { confirmReboot = false }) { Text("Cancel") } }
        )
    }
    confirmRepair?.let { recipe ->
        AlertDialog(
            onDismissRequest = { confirmRepair = null },
            title = { Text(recipe.name) },
            text = {
                Text(recipe.description + if (recipe.dangerous) "\n\nThis can change system files on the Kindle." else "")
            },
            confirmButton = {
                TextButton(onClick = { viewModel.runRepair(recipe); confirmRepair = null }) { Text("Run") }
            },
            dismissButton = { TextButton(onClick = { confirmRepair = null }) { Text("Cancel") } }
        )
    }
}

@Composable
private fun StatusCard(
    state: DashboardViewModel.DashboardState,
    host: String,
    port: Int,
    onRefresh: () -> Unit,
    onSetup: () -> Unit
) {
    HairlineCard(Modifier.fillMaxWidth()) {
        Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            if (!state.isConfigured) {
                Text("Not set up", style = MaterialTheme.typography.titleMedium)
                Text(
                    "Connect your Kindle to send books and manage it from here.",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
                InkButton("Connect your Kindle", onSetup, Modifier.fillMaxWidth())
                return@Column
            }
            val (label, filled) = when {
                state.isLoading -> "Checking..." to false
                state.reachable == true -> "Kindle connected" to true
                state.reachable == false -> "Kindle offline" to false
                else -> "Checking..." to false
            }
            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                val ink = MaterialTheme.colorScheme.onSurface
                Box(
                    Modifier
                        .size(10.dp)
                        .clip(CircleShape)
                        .border(1.5.dp, ink, CircleShape)
                        .background(if (filled) ink else Color.Transparent)
                )
                Text(label, style = MaterialTheme.typography.titleMedium, modifier = Modifier.weight(1f))
                TextButton(onClick = onRefresh, enabled = !state.isLoading) { Text("Check again") }
            }
            Text(
                "$host:$port",
                style = MaterialTheme.typography.bodySmall,
                fontFamily = FontFamily.Monospace,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
            if (state.isLoading) {
                LinearProgressIndicator(Modifier.fillMaxWidth(), trackColor = MaterialTheme.colorScheme.outlineVariant)
            }
            state.deviceStatus?.let { s ->
                InfoRow("Battery", s.batteryLevel)
                InfoRow("Free space", s.freeSpace)
                InfoRow("Firmware", s.firmwareVersion)
                InfoRow("Uptime", s.uptime)
                InfoRow("KOReader", if (s.koreaderInstalled) "Installed" else "Not found")
            }
            if (state.reachable == false && !state.isLoading) {
                val hint = ConnectionHints.explain(state.probeError ?: RuntimeException("timed out"), port)
                Text(hint.fix, style = MaterialTheme.typography.bodySmall)
            } else if (state.error != null && state.reachable == true && !state.isLoading) {
                Text(state.error!!, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.error)
            }
        }
    }
}

@Composable
private fun InfoRow(label: String, value: String?) {
    if (value.isNullOrBlank()) return
    Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
        Text(label, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
        Text(value, style = MaterialTheme.typography.labelMedium)
    }
}
