package app.pagedrop.ui.settings

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.outlined.Delete
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.unit.dp
import androidx.hilt.lifecycle.viewmodel.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import app.pagedrop.discover.OpdsCatalog
import app.pagedrop.discover.SourceKind
import app.pagedrop.ui.shell.HairlineCard
import app.pagedrop.ui.shell.OutlineButton
import app.pagedrop.ui.shell.SectionLabel

private fun SourceKind.title(): String = when (this) {
    SourceKind.GUTENBERG -> "Project Gutenberg"
    SourceKind.STANDARD_EBOOKS -> "Standard Ebooks"
    SourceKind.INTERNET_ARCHIVE -> "Internet Archive"
    SourceKind.DIGITAL_LIBRARY_INDIA -> "Digital Library of India"
    SourceKind.OPEN_LIBRARY -> "Open Library"
    SourceKind.HINDI_WIKISOURCE -> "Hindi Wikisource"
    SourceKind.HINDWI -> "Hindwi"
    SourceKind.REKHTA -> "Rekhta"
    SourceKind.ENGLISH_WIKISOURCE -> "English Wikisource"
    SourceKind.FADED_PAGE -> "Faded Page"
    SourceKind.MANYBOOKS -> "ManyBooks"
    SourceKind.OPDS -> "Your catalogs"
    SourceKind.WEBSITE -> "Website downloads"
}

private fun SourceKind.blurb(): String = when (this) {
    SourceKind.GUTENBERG -> "Public-domain classics, many already in Kindle format"
    SourceKind.STANDARD_EBOOKS -> "Carefully formatted public-domain editions"
    SourceKind.INTERNET_ARCHIVE -> "Freely downloadable books and scans"
    SourceKind.DIGITAL_LIBRARY_INDIA -> "Indian-language books and scans from Internet Archive"
    SourceKind.OPEN_LIBRARY -> "Open editions and borrowing through its website"
    SourceKind.HINDI_WIKISOURCE -> "Classic Hindi texts; read and check exports on the website"
    SourceKind.HINDWI -> "Hindi literature; opens in Hindwi’s web reader"
    SourceKind.REKHTA -> "Urdu and related literature; opens in Rekhta’s web reader"
    SourceKind.ENGLISH_WIKISOURCE -> "Classic English texts; read and check exports on the website"
    SourceKind.FADED_PAGE -> "English title search with EPUB and Kindle downloads"
    SourceKind.MANYBOOKS -> "English ebooks; downloads on the website may require an account"
    SourceKind.OPDS, SourceKind.WEBSITE -> ""
}

private val builtIns = SourceKind.entries.filter { it.isBuiltIn }

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun BookSourcesScreen(
    modifier: Modifier = Modifier,
    onBack: () -> Unit = {},
    viewModel: BookSourcesViewModel = hiltViewModel(),
) {
    val enabled by viewModel.enabledBuiltIns.collectAsStateWithLifecycle()
    val catalogs by viewModel.catalogs.collectAsStateWithLifecycle()
    val showShadow by viewModel.showShadowLibraries.collectAsStateWithLifecycle()
    val addState by viewModel.add.collectAsStateWithLifecycle()
    var showAdd by remember { mutableStateOf(false) }
    var removeTarget by remember { mutableStateOf<OpdsCatalog?>(null) }

    Scaffold(
        modifier = modifier,
        topBar = {
            TopAppBar(
                title = { Text("Book sources") },
                navigationIcon = { IconButton(onClick = onBack) { Icon(Icons.AutoMirrored.Filled.ArrowBack, "Back") } },
                colors = TopAppBarDefaults.topAppBarColors(
                    containerColor = MaterialTheme.colorScheme.surface,
                    titleContentColor = MaterialTheme.colorScheme.onSurface,
                ),
            )
        },
        containerColor = MaterialTheme.colorScheme.background,
    ) { inner ->
        Column(
            Modifier.fillMaxSize().padding(inner).verticalScroll(rememberScrollState()).padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            Text(
                "Where “Find a book” looks. Download available files or open a source’s web reader.",
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            SectionLabel("Built-in")
            HairlineCard(Modifier.fillMaxWidth()) {
                Column(Modifier.padding(horizontal = 16.dp, vertical = 8.dp)) {
                    builtIns.forEach { kind ->
                        Row(Modifier.fillMaxWidth().padding(vertical = 8.dp), verticalAlignment = Alignment.CenterVertically) {
                            Column(Modifier.weight(1f)) {
                                Text(kind.title(), style = MaterialTheme.typography.titleMedium)
                                Text(kind.blurb(), style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                            }
                            Switch(checked = kind in enabled, onCheckedChange = { viewModel.setEnabled(kind, it) })
                        }
                    }
                }
            }

            SectionLabel("Website shortcuts")
            Text(
                "Shortcuts for ePustakalay and Global Grey appear below book results. " +
                    "Search inside PageDrop and capture supported book downloads; access varies.",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            HairlineCard(Modifier.fillMaxWidth()) {
                Row(Modifier.padding(horizontal = 16.dp, vertical = 16.dp), verticalAlignment = Alignment.CenterVertically) {
                    Column(Modifier.weight(1f)) {
                        Text("Show shadow-library search shortcuts", style = MaterialTheme.typography.titleMedium)
                        Text(
                            "Adds Anna’s Archive and OceanofPDF. Their availability and legality vary by country.",
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    }
                    Switch(checked = showShadow, onCheckedChange = viewModel::setShowShadowLibraries)
                }
            }

            SectionLabel("OPDS catalogs")
            if (catalogs.isEmpty()) {
                Text(
                    "None added. An OPDS catalog is a book server address, for example from a library or a Calibre-Web install.",
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            } else {
                catalogs.forEach { catalog ->
                    HairlineCard(Modifier.fillMaxWidth()) {
                        Row(Modifier.padding(start = 16.dp, top = 8.dp, bottom = 8.dp, end = 4.dp), verticalAlignment = Alignment.CenterVertically) {
                            Column(Modifier.weight(1f)) {
                                Text(catalog.title, style = MaterialTheme.typography.titleMedium)
                                Text(
                                    catalog.url,
                                    style = MaterialTheme.typography.bodySmall,
                                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                                    maxLines = 1,
                                )
                            }
                            IconButton(onClick = { removeTarget = catalog }) {
                                Icon(Icons.Outlined.Delete, contentDescription = "Remove ${catalog.title}")
                            }
                        }
                    }
                }
            }
            OutlineButton("Add catalog", onClick = { viewModel.clearAddError(); showAdd = true }, Modifier.fillMaxWidth())
            Text(
                "Only add catalogs you’re allowed to download from. Passwords are kept on this phone only.",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
    }

    if (showAdd) {
        AddCatalogDialog(
            adding = addState.adding,
            error = addState.error,
            onAdd = { url, user, pass -> viewModel.addCatalog(url, user, pass) { showAdd = false } },
            onDismiss = { if (!addState.adding) showAdd = false },
        )
    }

    removeTarget?.let { target ->
        AlertDialog(
            onDismissRequest = { removeTarget = null },
            shape = MaterialTheme.shapes.small,
            title = { Text("Remove “${target.title}”?") },
            text = { Text("Books you already added from it stay in your library.") },
            confirmButton = {
                TextButton(onClick = { viewModel.removeCatalog(target.id); removeTarget = null }) {
                    Text("Remove", color = MaterialTheme.colorScheme.error)
                }
            },
            dismissButton = { TextButton(onClick = { removeTarget = null }) { Text("Cancel") } },
        )
    }
}

@Composable
private fun AddCatalogDialog(
    adding: Boolean,
    error: String?,
    onAdd: (String, String, String) -> Unit,
    onDismiss: () -> Unit,
) {
    var url by remember { mutableStateOf("") }
    var user by remember { mutableStateOf("") }
    var pass by remember { mutableStateOf("") }
    AlertDialog(
        onDismissRequest = onDismiss,
        shape = MaterialTheme.shapes.small,
        title = { Text("Add OPDS catalog") },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                OutlinedTextField(
                    value = url, onValueChange = { url = it }, label = { Text("Catalog address") },
                    placeholder = { Text("https://…") }, singleLine = true, enabled = !adding,
                    keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Uri),
                    modifier = Modifier.fillMaxWidth(),
                )
                OutlinedTextField(
                    value = user, onValueChange = { user = it }, label = { Text("Username (optional)") },
                    singleLine = true, enabled = !adding, modifier = Modifier.fillMaxWidth(),
                )
                OutlinedTextField(
                    value = pass, onValueChange = { pass = it }, label = { Text("Password (optional)") },
                    singleLine = true, enabled = !adding, visualTransformation = PasswordVisualTransformation(),
                    keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Password),
                    modifier = Modifier.fillMaxWidth(),
                )
                if (adding) {
                    Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                        CircularProgressIndicator(Modifier.padding(2.dp), strokeWidth = 2.dp)
                        Text("Checking the catalog…", style = MaterialTheme.typography.bodyMedium)
                    }
                }
                error?.let { Text(it, color = MaterialTheme.colorScheme.error, style = MaterialTheme.typography.bodyMedium) }
            }
        },
        confirmButton = { TextButton(onClick = { onAdd(url, user, pass) }, enabled = !adding) { Text("Add") } },
        dismissButton = { TextButton(onClick = onDismiss, enabled = !adding) { Text("Cancel") } },
    )
}
