package app.pagedrop.ui.shell

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.consumeWindowInsets
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.navigationBars
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Extension
import androidx.compose.material.icons.filled.LibraryBooks
import androidx.compose.material.icons.filled.TabletAndroid
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.NavigationBar
import androidx.compose.material3.NavigationBarItem
import androidx.compose.material3.NavigationBarItemDefaults
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.unit.dp
import androidx.hilt.lifecycle.viewmodel.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import app.pagedrop.ui.book.BookScreen
import app.pagedrop.ui.kindle.KindleScreen
import app.pagedrop.ui.tools.ExtrasScreen

private enum class Tab(val label: String, val icon: ImageVector) {
    Library("Library", Icons.Default.LibraryBooks),
    Kindle("Kindle", Icons.Default.TabletAndroid),
    Extras("Extras", Icons.Default.Extension)
}

/** Library / Kindle / Extras tabs with a monochrome bottom bar. Sub-screens are pushed on top of this. */
@Composable
fun MainShell(
    onOpenSettings: () -> Unit,
    onOpenSetup: () -> Unit,
    onOpenConnection: () -> Unit,
    onOpenKindleReady: () -> Unit = {},
    onOpenSync: () -> Unit,
    onOpenArticle: () -> Unit,
    onOpenScreensaver: () -> Unit,
    onOpenFonts: () -> Unit,
    onOpenDictionaries: () -> Unit,
    onOpenBookSources: () -> Unit = {},
    onOpenCatalog: (catalogId: String, feedUrl: String?) -> Unit = { _, _ -> },
    shellViewModel: ShellViewModel = hiltViewModel(),
) {
    var tabIndex by rememberSaveable { mutableIntStateOf(0) }
    val sharedUri by shellViewModel.pendingSharedUri.collectAsStateWithLifecycle()
    LaunchedEffect(sharedUri) { if (sharedUri != null) tabIndex = Tab.Library.ordinal }
    val tab = Tab.entries[tabIndex]

    Column(Modifier.fillMaxSize()) {
        // Each tab has its own Scaffold; the bar below handles the navigation-bar inset itself.
        Box(Modifier.weight(1f).consumeWindowInsets(WindowInsets.navigationBars)) {
            when (tab) {
                Tab.Library -> BookScreen(
                    modifier = Modifier,
                    onOpenKindle = { tabIndex = Tab.Kindle.ordinal },
                    onOpenSettings = onOpenSettings,
                    onOpenConnectionSetup = onOpenSetup,
                    onOpenArticle = onOpenArticle,
                    onOpenBookSources = onOpenBookSources,
                    onOpenCatalog = onOpenCatalog
                )
                Tab.Kindle -> KindleScreen(
                    onOpenSettings = onOpenSettings,
                    onOpenSync = onOpenSync,
                    onOpenConnection = onOpenConnection,
                    onOpenSetup = onOpenSetup,
                    onOpenKindleReady = onOpenKindleReady,
                    onOpenInLibrary = { tabIndex = Tab.Library.ordinal }
                )
                Tab.Extras -> ExtrasScreen(
                    onNavigateToScreensaver = onOpenScreensaver,
                    onNavigateToFonts = onOpenFonts,
                    onNavigateToDictionaries = onOpenDictionaries,
                    onNavigateToArticles = onOpenArticle
                )
            }
        }
        HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant)
        NavigationBar(
            containerColor = MaterialTheme.colorScheme.surface,
            tonalElevation = 0.dp
        ) {
            Tab.entries.forEach { t ->
                NavigationBarItem(
                    selected = t == tab,
                    onClick = { tabIndex = t.ordinal },
                    icon = { Icon(t.icon, contentDescription = null) },
                    label = { Text(t.label) },
                    colors = NavigationBarItemDefaults.colors(
                        selectedIconColor = MaterialTheme.colorScheme.onPrimary,
                        selectedTextColor = MaterialTheme.colorScheme.onSurface,
                        indicatorColor = MaterialTheme.colorScheme.primary,
                        unselectedIconColor = MaterialTheme.colorScheme.onSurfaceVariant,
                        unselectedTextColor = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                )
            }
        }
    }
}
