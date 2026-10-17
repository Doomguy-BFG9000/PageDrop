package app.pagedrop.ui.tools

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.FontDownload
import androidx.compose.material.icons.filled.MenuBook
import androidx.compose.material.icons.filled.PhotoLibrary
import androidx.compose.material.icons.filled.RssFeed
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import app.pagedrop.ui.shell.NavRow

/** Extras tab: content add-ons for the Kindle (device tools live in the Kindle tab). */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun ExtrasScreen(
    modifier: Modifier = Modifier,
    onNavigateToScreensaver: () -> Unit = {},
    onNavigateToFonts: () -> Unit = {},
    onNavigateToDictionaries: () -> Unit = {},
    onNavigateToArticles: () -> Unit = {}
) {
    Scaffold(
        modifier = modifier,
        topBar = {
            TopAppBar(
                title = { Text("Extras") },
                colors = TopAppBarDefaults.topAppBarColors(
                    containerColor = MaterialTheme.colorScheme.background,
                    titleContentColor = MaterialTheme.colorScheme.onSurface
                )
            )
        },
        containerColor = MaterialTheme.colorScheme.background
    ) { innerPadding ->
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(innerPadding)
                .verticalScroll(rememberScrollState())
                .padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp)
        ) {
            Text(
                "Add more than books to your Kindle.",
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
            NavRow(
                title = "Screensavers",
                subtitle = "Crop, resize and grayscale pictures for your lock screen",
                icon = Icons.Default.PhotoLibrary,
                onClick = onNavigateToScreensaver
            )
            NavRow(
                title = "Fonts",
                subtitle = "Install reading fonts tuned for e-ink",
                icon = Icons.Default.FontDownload,
                onClick = onNavigateToFonts
            )
            NavRow(
                title = "Dictionaries",
                subtitle = "Install StarDict dictionaries for KOReader",
                icon = Icons.Default.MenuBook,
                onClick = onNavigateToDictionaries
            )
            NavRow(
                title = "Send article",
                subtitle = "Paste a web link and read it on your Kindle",
                icon = Icons.Default.RssFeed,
                onClick = onNavigateToArticles
            )
        }
    }
}
