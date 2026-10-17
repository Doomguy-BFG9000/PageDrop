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
    viewModel: SettingsViewModel = hiltViewModel()
) {
    val state by viewModel.state.collectAsState()
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

            SectionLabel("Find a book")
            NavRow(
                title = "Book sources",
                subtitle = "Free libraries and your own OPDS catalogs",
                icon = Icons.Default.Search,
                onClick = onOpenBookSources
            )

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
                        OutlineButton(
                            if (state.isTestingServer) "Testing..." else "Test PageDrop server",
                            viewModel::testServer,
                            Modifier.fillMaxWidth(),
                            enabled = !state.isTestingServer
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
