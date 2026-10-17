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
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.activity.compose.BackHandler
import app.pagedrop.ui.book.BookViewModel
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
import app.pagedrop.transfer.sftp.KindleSftpClient
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
    onOpenKindleReady: () -> Unit = {},
    onOpenInLibrary: () -> Unit = {},
    viewModel: DashboardViewModel = hiltViewModel(),
    bookViewModel: BookViewModel = hiltViewModel()
) {
    val state by viewModel.state.collectAsState()
    var confirmReboot by remember { mutableStateOf(false) }
    var confirmRepair by remember { mutableStateOf<DeviceCommandRunner.RepairRecipe?>(null) }

    val sendingIds by bookViewModel.sendingIds.collectAsState()
    val transferState by bookViewModel.transferState.collectAsState()
    val transferBusy = sendingIds.isNotEmpty() || transferState is BookViewModel.TransferState.Progress
    val busyHint: String? = when {
        state.isChecking -> "Wait for the Kindle check to finish."
        state.isDeleting -> "Deleting from your Kindle\u2026"
        state.savingKey != null -> "Saving a book to your library\u2026"
        transferBusy -> "A book is being sent to your Kindle. Try again when it's done."
        else -> null
    }

    val snackbarHostState = remember { SnackbarHostState() }
    var sheetKey by remember { mutableStateOf<String?>(null) }
    var selectedKeys by remember { mutableStateOf(emptySet<String>()) }
    var deleteKeys by remember { mutableStateOf<List<String>?>(null) }
    val groups = remember(state.libraryReport) {
        state.libraryReport?.let { KindleLibraryMatcher.group(it) }.orEmpty()
    }
    val selectionMode = selectedKeys.isNotEmpty()
    BackHandler(enabled = selectionMode) { selectedKeys = emptySet() }

    LaunchedEffect(Unit) { viewModel.refresh() }
    LaunchedEffect(viewModel) { viewModel.messages.collect { snackbarHostState.showSnackbar(it) } }
    // Selections vanish with the books they point at (after a delete and re-check).
    LaunchedEffect(groups) {
        val keys = groups.map { it.key }.toSet()
        selectedKeys = selectedKeys.filter { it in keys }.toSet()
    }

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
        snackbarHost = { SnackbarHost(snackbarHostState) },
        bottomBar = {
            if (selectionMode) {
                KindleSelectionBar(
                    count = selectedKeys.size,
                    deleteEnabled = busyHint == null,
                    busyHint = busyHint,
                    onDelete = { deleteKeys = selectedKeys.toList() },
                    onCancel = { selectedKeys = emptySet() }
                )
            }
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
            StatusCard(state, viewModel.host, viewModel.port, onRefresh = viewModel::refresh, onSetup = onOpenSetup, onGetReady = onOpenKindleReady)

            if (state.isConfigured) {
                LibraryCheckCard(
                    state = state,
                    port = viewModel.port,
                    groups = groups,
                    selectionMode = selectionMode,
                    selectedKeys = selectedKeys,
                    onRowClick = { key ->
                        if (selectionMode) selectedKeys = if (key in selectedKeys) selectedKeys - key else selectedKeys + key
                        else sheetKey = key
                    },
                    onRowLongClick = { key -> if (busyHint == null) selectedKeys = selectedKeys + key }
                )

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
                    OutlineButton(
                        if (state.isChecking) "Checking..." else "Check Kindle library",
                        viewModel::triggerRescan,
                        Modifier.weight(1f),
                        enabled = state.reachable == true && !state.isBusy && !transferBusy
                    )
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

    val sheetGroup = groups.firstOrNull { it.key == sheetKey }
    if (sheetGroup != null) {
        KindleBookSheet(
            group = sheetGroup,
            inLibrary = sheetGroup.inLibrary || sheetGroup.key in state.savedKeys,
            saving = state.savingKey == sheetGroup.key,
            busyHint = busyHint.takeIf { state.savingKey != sheetGroup.key },
            onSave = { viewModel.saveToLibrary(sheetGroup) },
            onOpenInLibrary = { sheetKey = null; onOpenInLibrary() },
            onDelete = { sheetKey = null; deleteKeys = listOf(sheetGroup.key) },
            onDismiss = { sheetKey = null }
        )
    }
    deleteKeys?.let { keys ->
        val targets = groups.filter { it.key in keys }
        if (targets.isEmpty()) {
            deleteKeys = null
        } else {
            KindleDeleteDialog(
                groups = targets,
                onConfirm = { home, ko, sidecars ->
                    deleteKeys = null
                    selectedKeys = emptySet()
                    viewModel.deleteFromKindle(targets, home, ko, sidecars)
                },
                onDismiss = { deleteKeys = null }
            )
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
private fun LibraryCheckCard(
    state: DashboardViewModel.DashboardState,
    port: Int,
    groups: List<KindleBookGroup>,
    selectionMode: Boolean,
    selectedKeys: Set<String>,
    onRowClick: (String) -> Unit,
    onRowLongClick: (String) -> Unit
) {
    val report = state.libraryReport
    val error = state.libraryError
    if (!state.isChecking && !state.isDeleting && report == null && error == null) return
    var expanded by remember { mutableStateOf(false) }
    HairlineCard(Modifier.fillMaxWidth()) {
        Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            when {
                state.isChecking || state.isDeleting -> Row(
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(12.dp)
                ) {
                    CircularProgressIndicator(Modifier.size(20.dp), strokeWidth = 2.dp)
                    Text(
                        if (state.isDeleting) "Deleting from your Kindle..." else "Checking what's on your Kindle...",
                        style = MaterialTheme.typography.bodyMedium
                    )
                }
                error != null -> {
                    val hint = ConnectionHints.explain(error, port)
                    Text(hint.title, style = MaterialTheme.typography.titleSmall)
                    Text(hint.fix, style = MaterialTheme.typography.bodySmall)
                }
                report != null -> {
                    val books = if (report.bookCount == 1) "1 book" else "${report.bookCount} books"
                    Text(
                        "$books on your Kindle \u00b7 ${report.pageDropCount} from PageDrop",
                        style = MaterialTheme.typography.titleSmall
                    )
                    if (report.rescanMethod != null) {
                        Text("Asked the Kindle to refresh its home screen.", style = MaterialTheme.typography.bodySmall)
                    }
                    if (report.koreaderRunning) {
                        Text(
                            "Close KOReader to see new books on the Kindle home screen.",
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                    }
                    if (groups.isNotEmpty()) {
                        TextButton(onClick = { expanded = !expanded }) {
                            Text(if (expanded) "Hide \"On your Kindle\"" else "On your Kindle", color = MaterialTheme.colorScheme.onSurface)
                        }
                    }
                    if (expanded || selectionMode) {
                        Text(
                            "Tap a book to manage it. Press and hold to select several.",
                            style = MaterialTheme.typography.labelSmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                        groups.forEach { g ->
                            KindleBookRow(
                                group = g,
                                inLibrary = g.inLibrary || g.key in state.savedKeys,
                                selectionMode = selectionMode,
                                selected = g.key in selectedKeys,
                                onClick = { onRowClick(g.key) },
                                onLongClick = { onRowLongClick(g.key) }
                            )
                        }
                    }
                }
            }
        }
    }
}

internal fun formatSize(bytes: Long): String = when {
    bytes >= 1_048_576 -> String.format(java.util.Locale.US, "%.1f MB", bytes / 1_048_576.0)
    bytes >= 1024 -> "${bytes / 1024} KB"
    else -> "$bytes B"
}

@Composable
private fun StatusCard(
    state: DashboardViewModel.DashboardState,
    host: String,
    port: Int,
    onRefresh: () -> Unit,
    onSetup: () -> Unit,
    onGetReady: () -> Unit = {}
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
                OutlineButton("Get your Kindle ready", onGetReady, Modifier.fillMaxWidth())
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
