package app.pagedrop.ui.settings

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.Search
import androidx.compose.material.icons.filled.Wifi
import androidx.compose.material3.Checkbox
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.onFocusChanged
import androidx.compose.ui.unit.dp
import androidx.hilt.lifecycle.viewmodel.compose.hiltViewModel
import app.pagedrop.BuildConfig
import app.pagedrop.data.SendFormatSetting
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.selection.selectable
import androidx.compose.foundation.selection.selectableGroup
import androidx.compose.material3.RadioButton
import androidx.compose.ui.semantics.Role
import app.pagedrop.ui.shell.HairlineCard
import app.pagedrop.ui.shell.NavRow
import app.pagedrop.ui.shell.OutlineButton
import app.pagedrop.ui.shell.ResultBanner
import app.pagedrop.ui.shell.SectionLabel

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun SettingsScreen(
    modifier: Modifier = Modifier,
    onBack: () -> Unit = {},
    onOpenConnection: () -> Unit = {},
    onOpenBookSources: () -> Unit = {},
    onOpenKindleReady: () -> Unit = {},
    viewModel: SettingsViewModel = hiltViewModel()
) {
    val state by viewModel.state.collectAsState()
    val useGoogleBooks by viewModel.useGoogleBooks.collectAsState()
    val sendFormat by viewModel.sendFormat.collectAsState()
    LaunchedEffect(Unit) { viewModel.refresh() }

    Scaffold(
        modifier = modifier,
        topBar = {
            TopAppBar(
                title = { Text("Settings") },
                navigationIcon = {
                    IconButton(onClick = onBack) { Icon(Icons.AutoMirrored.Filled.ArrowBack, "Back") }
                },
                colors = TopAppBarDefaults.topAppBarColors(
                    containerColor = MaterialTheme.colorScheme.surface,
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
            SectionLabel("Kindle")
            NavRow(
                title = "Kindle connection",
                subtitle = state.kindleSummary,
                icon = Icons.Default.Wifi,
                onClick = onOpenConnection
            )
            NavRow(
                title = "Get your Kindle ready",
                subtitle = "Jailbreak, KOReader and SSH, step by step",
                icon = Icons.Default.Wifi,
                onClick = onOpenKindleReady
            )

            SectionLabel("Send format")
            HairlineCard(Modifier.fillMaxWidth()) {
                Column(Modifier.padding(vertical = 8.dp, horizontal = 16.dp).selectableGroup()) {
                    SendFormatRow(
                        selected = sendFormat == SendFormatSetting.CONVERT,
                        onClick = { viewModel.updateSendFormat(SendFormatSetting.CONVERT) },
                        title = "Convert to Kindle format (MOBI)",
                        note = "Shows on the Kindle home screen and in KOReader.",
                    )
                    SendFormatRow(
                        selected = sendFormat == SendFormatSetting.KEEP_ORIGINAL,
                        onClick = { viewModel.updateSendFormat(SendFormatSetting.KEEP_ORIGINAL) },
                        title = "Keep the original format",
                        note = "Sent exactly as added. Formats the Kindle’s own reader can’t open go to KOReader only.",
                    )
                }
            }

            SectionLabel("Find a book")
            NavRow(
                title = "Book sources",
                subtitle = "Free libraries and your own OPDS catalogs",
                icon = Icons.Default.Search,
                onClick = onOpenBookSources
            )

            SectionLabel("Book details")
            HairlineCard(Modifier.fillMaxWidth()) {
                Row(Modifier.padding(16.dp), verticalAlignment = Alignment.CenterVertically) {
                    Column(Modifier.weight(1f)) {
                        Text("Use Google Books for book details", style = MaterialTheme.typography.bodyMedium)
                        Text(
                            "Titles, authors and covers come from Open Library. Google Books adds more matches; " +
                                "when on, a book's title and author (or ISBN) are also sent to Google.",
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                    }
                    Switch(checked = useGoogleBooks, onCheckedChange = viewModel::updateUseGoogleBooks)
                }
            }

            SectionLabel("Extra folders (optional)")
            HairlineCard(Modifier.fillMaxWidth()) {
                Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    OutlinedTextField(
                        value = state.folders,
                        onValueChange = viewModel::updateFolders,
                        label = { Text("Extra folders on the Kindle") },
                        supportingText = {
                            Text(
                                "Books always go to your Kindle home screen and KOReader's Books folder. " +
                                    "Add extra folders here, one per line."
                            )
                        },
                        minLines = 2,
                        modifier = Modifier
                            .fillMaxWidth()
                            .onFocusChanged { if (!it.isFocused) viewModel.saveFolders() }
                    )
                    OutlineButton(
                        "Save folders",
                        viewModel::saveFolders,
                        Modifier.fillMaxWidth(),
                        enabled = state.foldersDirty
                    )
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Checkbox(checked = state.triggerRescan, onCheckedChange = viewModel::updateTriggerRescan)
                        Text(
                            "Refresh the Kindle library after sending",
                            style = MaterialTheme.typography.bodyMedium
                        )
                    }
                }
            }

            SectionLabel("Conversion server")
            HairlineCard(Modifier.fillMaxWidth()) {
                Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
                    Text(
                        "Optional. Used for heavy conversions and article formatting; otherwise books are converted on this phone.",
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                    if (state.serverAvailable) {
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            Column(Modifier.weight(1f)) {
                                Text(
                                    "Use PageDrop conversion server",
                                    style = MaterialTheme.typography.bodyMedium
                                )
                                Text(
                                    SERVER_UPLOAD_SUMMARY,
                                    style = MaterialTheme.typography.bodySmall,
                                    color = MaterialTheme.colorScheme.onSurfaceVariant
                                )
                            }
                            Switch(checked = state.useServer, onCheckedChange = viewModel::updateUseServer)
                        }
                        if (!state.useServer) {
                            Text(
                                "Off: everything is converted on this phone and nothing is sent to the server.",
                                style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant
                            )
                        }
                        OutlineButton(
                            if (state.isTestingServer) "Testing..." else "Test PageDrop server",
                            viewModel::testServer,
                            Modifier.fillMaxWidth(),
                            enabled = state.useServer && !state.isTestingServer
                        )
                    } else {
                        Text(
                            "Server conversion is not available in this build.",
                            style = MaterialTheme.typography.bodySmall
                        )
                    }
                    state.serverTestResult?.let { result ->
                        ResultBanner(
                            success = result.isSuccess,
                            title = if (result.isSuccess) "Server reachable" else "Server not reachable",
                            detail = if (result.isFailure) result.exceptionOrNull()?.localizedMessage else null
                        )
                    }
                }
            }

            SectionLabel("About")
            HairlineCard(Modifier.fillMaxWidth()) {
                Column(Modifier.padding(16.dp)) {
                    Text("PageDrop", style = MaterialTheme.typography.titleMedium)
                    Text(
                        "Version ${BuildConfig.VERSION_NAME}",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }
            }
        }
    }
}

@Composable
private fun SendFormatRow(selected: Boolean, onClick: () -> Unit, title: String, note: String) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .heightIn(min = 48.dp)
            .selectable(selected = selected, onClick = onClick, role = Role.RadioButton),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        RadioButton(selected = selected, onClick = null)
        Column(Modifier.padding(start = 12.dp, top = 8.dp, bottom = 8.dp)) {
            Text(title, style = MaterialTheme.typography.bodyMedium)
            Text(
                note,
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
    }
}
