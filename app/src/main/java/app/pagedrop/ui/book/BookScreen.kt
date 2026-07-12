/*
 * Copyright (C) 2022 The Android Open Source Project
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *      http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

package app.pagedrop.ui.book

import android.net.Uri
import android.text.format.Formatter
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.animateColorAsState
import androidx.compose.animation.core.Spring
import androidx.compose.animation.core.spring
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.slideInVertically
import androidx.compose.animation.slideOutVertically
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.Build
import androidx.compose.material.icons.filled.Send
import androidx.compose.material.icons.outlined.MenuBook
import androidx.compose.material.icons.rounded.Delete
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.Checkbox
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilledTonalButton
import androidx.compose.material3.FloatingActionButton
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.MediumTopAppBar
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Scaffold
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.material3.rememberModalBottomSheetState
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.input.nestedscroll.nestedScroll
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.painterResource
import app.pagedrop.R
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.hilt.lifecycle.viewmodel.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import app.pagedrop.data.DefaultBookRepository
import app.pagedrop.converter.BookConverter
import app.pagedrop.data.local.database.Book
import kotlinx.coroutines.launch

// ─────────────────────────────────────────────────────────────
// Public entry — wired to the ViewModel via Hilt
// ─────────────────────────────────────────────────────────────

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun BookScreen(
    modifier: Modifier = Modifier,
    onNavigateToTools: () -> Unit = {},
    viewModel: BookViewModel = hiltViewModel(),
) {
    val uiState by viewModel.uiState.collectAsStateWithLifecycle()
    val transferQueue by viewModel.transferQueue.collectAsStateWithLifecycle()
    val isConverting by viewModel.isConverting.collectAsStateWithLifecycle()
    val conversionError by viewModel.conversionError.collectAsStateWithLifecycle()
    
    val kindleIp by viewModel.kindleIp.collectAsStateWithLifecycle()
    val kindlePort by viewModel.kindlePort.collectAsStateWithLifecycle()
    val kindleUsername by viewModel.kindleUsername.collectAsStateWithLifecycle()
    val kindlePassword by viewModel.kindlePassword.collectAsStateWithLifecycle()
    val kindleDirectories by viewModel.kindleDirectories.collectAsStateWithLifecycle()
    val triggerRescan by viewModel.triggerRescan.collectAsStateWithLifecycle()

    val isTestingConnection by viewModel.isTestingConnection.collectAsStateWithLifecycle()
    val connectionTestResult by viewModel.connectionTestResult.collectAsStateWithLifecycle()
    val transferState by viewModel.transferState.collectAsStateWithLifecycle()

    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    val snackbarHostState = remember { SnackbarHostState() }

    // Formats the Kindle browser can download directly (no conversion needed)
    val kindleNativeFormats = listOf("AZW3", "MOBI", "PRC")

    // State for the conversion bottom sheet (for convertible formats)
    var pendingConvertUri by remember { mutableStateOf<Uri?>(null) }
    var pendingConvertFormat by remember { mutableStateOf("") }
    val convertSheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true)

    // State for the transfer bottom sheet
    var showTransferSheet by remember { mutableStateOf(false) }
    val transferSheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true)

    val filePickerLauncher = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.OpenDocument()
    ) { uri: Uri? ->
        uri?.let {
            // Detect format from the URI filename
            val displayName = context.contentResolver.query(it, null, null, null, null)?.use { cursor ->
                if (cursor.moveToFirst()) {
                    val nameIndex = cursor.getColumnIndex(android.provider.OpenableColumns.DISPLAY_NAME)
                    if (nameIndex >= 0) cursor.getString(nameIndex) else null
                } else null
            }
            val format = DefaultBookRepository.detectFormat(displayName ?: "")

            if (format.uppercase() in kindleNativeFormats) {
                // Kindle-native format: add directly, no dialog
                viewModel.addBook(context, it)
            } else if (BookConverter.canConvert(format)) {
                // We can convert this format (EPUB, PDF, TXT) → show bottom sheet
                pendingConvertUri = it
                pendingConvertFormat = format.uppercase()
            } else {
                // Unknown format: add as-is
                viewModel.addBook(context, it)
            }
        }
    }

    // Show snackbar for conversion errors
    LaunchedEffect(conversionError) {
        conversionError?.let { error ->
            snackbarHostState.showSnackbar("Conversion error: $error")
            viewModel.clearConversionError()
        }
    }

    // Format options bottom sheet for convertible formats (EPUB, PDF, TXT)
    if (pendingConvertUri != null) {
        FormatOptionsBottomSheet(
            format = pendingConvertFormat,
            sheetState = convertSheetState,
            serverConversionAvailable = viewModel.isServerConversionAvailable,
            onConvertToMobi = {
                pendingConvertUri?.let { uri ->
                    viewModel.addBookWithConversion(context, uri)
                }
                pendingConvertUri = null
                pendingConvertFormat = ""
                scope.launch { convertSheetState.hide() }
            },
            onConvertOnServer = {
                pendingConvertUri?.let { uri ->
                    viewModel.addBookWithServerConversion(context, uri)
                }
                pendingConvertUri = null
                pendingConvertFormat = ""
                scope.launch { convertSheetState.hide() }
            },
            onSendAsIs = {
                pendingConvertUri?.let { uri ->
                    viewModel.addBook(context, uri)
                }
                pendingConvertUri = null
                pendingConvertFormat = ""
                scope.launch { convertSheetState.hide() }
            },
            onDismiss = {
                pendingConvertUri = null
                pendingConvertFormat = ""
            },
        )
    }

    // Transfer bottom sheet
    if (showTransferSheet) {
        TransferBottomSheet(
            sheetState = transferSheetState,
            kindleIp = kindleIp,
            kindlePort = kindlePort,
            kindleUsername = kindleUsername,
            kindlePassword = kindlePassword,
            kindleDirectories = kindleDirectories,
            triggerRescan = triggerRescan,
            isTestingConnection = isTestingConnection,
            connectionTestResult = connectionTestResult,
            transferState = transferState,
            selectedBooks = transferQueue,
            onIpChange = viewModel::updateKindleIp,
            onPortChange = viewModel::updateKindlePort,
            onUsernameChange = viewModel::updateKindleUsername,
            onPasswordChange = viewModel::updateKindlePassword,
            onDirectoriesChange = viewModel::updateKindleDirectories,
            onTriggerRescanChange = viewModel::updateTriggerRescan,
            onTestConnection = viewModel::testConnection,
            onClearTestResult = viewModel::clearConnectionTestResult,
            onStartTransfer = viewModel::transferBooksToKindle,
            onResetTransferState = viewModel::resetTransferState,
            onDismiss = { 
                showTransferSheet = false
                viewModel.resetTransferState()
                viewModel.clearConnectionTestResult()
            },
        )
    }

    LibraryScreenContent(
        uiState = uiState,
        transferQueue = transferQueue,
        isConverting = isConverting,
        snackbarHostState = snackbarHostState,
        onAddBookClick = {
            filePickerLauncher.launch(
                arrayOf(
                    "application/pdf",
                    "text/plain",
                    "application/x-mobipocket-ebook",   // .mobi
                    "application/epub+zip",              // .epub
                    "application/octet-stream",          // .azw3 fallback
                    "*/*"                                // fallback for Kindle formats
                )
            )
        },
        onToggleQueued = viewModel::toggleQueued,
        onDeleteBook = viewModel::deleteBook,
        onOpenTransferSheet = {
            showTransferSheet = true
        },
        onNavigateToTools = onNavigateToTools,
        modifier = modifier,
    )
}

// ─────────────────────────────────────────────────────────────
// Format Options Bottom Sheet (for non-Kindle-native formats)
// ─────────────────────────────────────────────────────────────

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun FormatOptionsBottomSheet(
    format: String,
    sheetState: androidx.compose.material3.SheetState,
    serverConversionAvailable: Boolean,
    onConvertToMobi: () -> Unit,
    onConvertOnServer: () -> Unit,
    onSendAsIs: () -> Unit,
    onDismiss: () -> Unit,
) {
    val canConvert = BookConverter.canConvert(format)
    val description = when (format) {
        "EPUB" -> "EPUBs aren\u2019t natively supported on older Kindles. Convert to MOBI for compatibility."
        "PDF" -> "Extract text and convert to MOBI. Layout and images will not be preserved."
        "TXT" -> "Convert to MOBI for better formatting on Kindle, or send the text file directly."
        else -> "$format files may not be compatible with Kindle."
    }

    ModalBottomSheet(
        onDismissRequest = onDismiss,
        sheetState = sheetState,
        containerColor = MaterialTheme.colorScheme.surface,
    ) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 24.dp)
                .padding(bottom = 36.dp),
        ) {
            Text(
                text = "$format File",
                style = MaterialTheme.typography.titleLarge,
                color = MaterialTheme.colorScheme.onSurface,
            )

            Spacer(Modifier.height(8.dp))

            Text(
                text = description,
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )

            Spacer(Modifier.height(24.dp))

            // Primary action: Convert to MOBI
            if (canConvert) {
                if (serverConversionAvailable) {
                    Button(
                        onClick = onConvertOnServer,
                        modifier = Modifier
                            .fillMaxWidth()
                            .height(48.dp),
                        shape = RoundedCornerShape(12.dp),
                    ) {
                        Text("Convert with PageDrop server")
                    }

                    Spacer(Modifier.height(12.dp))
                } else {
                    OutlinedButton(
                        onClick = {},
                        enabled = false,
                        modifier = Modifier
                            .fillMaxWidth()
                            .height(48.dp),
                        shape = RoundedCornerShape(12.dp),
                    ) {
                        Text("Convert with PageDrop server")
                    }

                    Spacer(Modifier.height(4.dp))
                    Text(
                        text = "PageDrop server conversion is not connected in this build.",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )

                    Spacer(Modifier.height(12.dp))
                }

                Button(
                    onClick = onConvertToMobi,
                    modifier = Modifier
                        .fillMaxWidth()
                        .height(48.dp),
                    shape = RoundedCornerShape(12.dp),
                ) {
                    Text("Convert to MOBI")
                }

                Spacer(Modifier.height(12.dp))
            }

            // Secondary action: Send as-is
            OutlinedButton(
                onClick = onSendAsIs,
                modifier = Modifier
                    .fillMaxWidth()
                    .height(48.dp),
                shape = RoundedCornerShape(12.dp),
            ) {
                Text(if (canConvert) "Add as-is" else "Add to library")
            }
        }
    }
}

// ─────────────────────────────────────────────────────────────
// Transfer / Settings Bottom Sheet
// ─────────────────────────────────────────────────────────────

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun TransferBottomSheet(
    sheetState: androidx.compose.material3.SheetState,
    kindleIp: String,
    kindlePort: String,
    kindleUsername: String,
    kindlePassword: String,
    kindleDirectories: List<String>,
    triggerRescan: Boolean,
    isTestingConnection: Boolean,
    connectionTestResult: Result<Unit>?,
    transferState: BookViewModel.TransferState,
    selectedBooks: List<Book>,
    onIpChange: (String) -> Unit,
    onPortChange: (String) -> Unit,
    onUsernameChange: (String) -> Unit,
    onPasswordChange: (String) -> Unit,
    onDirectoriesChange: (List<String>) -> Unit,
    onTriggerRescanChange: (Boolean) -> Unit,
    onTestConnection: () -> Unit,
    onClearTestResult: () -> Unit,
    onStartTransfer: () -> Unit,
    onResetTransferState: () -> Unit,
    onDismiss: () -> Unit,
) {
    ModalBottomSheet(
        onDismissRequest = onDismiss,
        sheetState = sheetState,
        containerColor = MaterialTheme.colorScheme.surface,
    ) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 24.dp)
                .padding(bottom = 36.dp)
                .verticalScroll(rememberScrollState()),
            verticalArrangement = Arrangement.spacedBy(16.dp)
        ) {
            Text(
                text = "Kindle Connection Settings",
                style = MaterialTheme.typography.titleLarge,
                fontWeight = FontWeight.Bold,
                color = MaterialTheme.colorScheme.onSurface
            )

            // IP & Port
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(12.dp)
            ) {
                OutlinedTextField(
                    value = kindleIp,
                    onValueChange = onIpChange,
                    label = { Text("Kindle IP Address") },
                    placeholder = { Text("e.g. 192.168.1.100") },
                    singleLine = true,
                    modifier = Modifier.weight(2f)
                )

                OutlinedTextField(
                    value = kindlePort,
                    onValueChange = onPortChange,
                    label = { Text("Port") },
                    singleLine = true,
                    modifier = Modifier.weight(1f)
                )
            }

            // Username & Password
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(12.dp)
            ) {
                OutlinedTextField(
                    value = kindleUsername,
                    onValueChange = onUsernameChange,
                    label = { Text("Username") },
                    singleLine = true,
                    modifier = Modifier.weight(1f)
                )

                OutlinedTextField(
                    value = kindlePassword,
                    onValueChange = onPasswordChange,
                    label = { Text("Password") },
                    visualTransformation = PasswordVisualTransformation(),
                    singleLine = true,
                    modifier = Modifier.weight(1f)
                )
            }

            Text(
                text = "Send to paths",
                style = MaterialTheme.typography.titleSmall,
                fontWeight = FontWeight.SemiBold,
                color = MaterialTheme.colorScheme.onSurface
            )
            kindleDirectories.forEachIndexed { index, path ->
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(8.dp)
                ) {
                    OutlinedTextField(
                        value = path,
                        onValueChange = { value ->
                            onDirectoriesChange(kindleDirectories.toMutableList().also { it[index] = value })
                        },
                        label = { Text(if (index == 0) "Kindle Documents Directory" else "Additional Kindle Path") },
                        singleLine = true,
                        modifier = Modifier.weight(1f)
                    )
                    if (kindleDirectories.size > 1) {
                        IconButton(onClick = {
                            onDirectoriesChange(kindleDirectories.filterIndexed { itemIndex, _ -> itemIndex != index })
                        }) {
                            Icon(Icons.Rounded.Delete, contentDescription = "Remove path")
                        }
                    }
                }
            }
            OutlinedButton(
                onClick = { onDirectoriesChange(kindleDirectories + "/mnt/us/documents") },
                modifier = Modifier.fillMaxWidth()
            ) {
                Icon(Icons.Default.Add, contentDescription = null, modifier = Modifier.size(18.dp))
                Spacer(Modifier.width(8.dp))
                Text("Add path")
            }

            // Trigger Library Rescan Checkbox
            Row(
                modifier = Modifier.fillMaxWidth(),
                verticalAlignment = Alignment.CenterVertically
            ) {
                Checkbox(
                    checked = triggerRescan,
                    onCheckedChange = onTriggerRescanChange
                )
                Spacer(Modifier.width(8.dp))
                Text(
                    text = "Trigger Library Rescan after transfer",
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }

            // Test Connection button and status
            Column(modifier = Modifier.fillMaxWidth()) {
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    OutlinedButton(
                        onClick = onTestConnection,
                        enabled = !isTestingConnection && kindleIp.isNotBlank(),
                        modifier = Modifier.weight(1f)
                    ) {
                        if (isTestingConnection) {
                            CircularProgressIndicator(
                                modifier = Modifier.size(18.dp),
                                strokeWidth = 2.dp,
                                color = MaterialTheme.colorScheme.primary
                            )
                            Spacer(Modifier.width(8.dp))
                            Text("Testing...")
                        } else {
                            Text("Test Connection")
                        }
                    }
                }

                connectionTestResult?.let { result ->
                    Spacer(Modifier.height(8.dp))
                    if (result.isSuccess) {
                        Text(
                            text = "✓ Connection Successful!",
                            style = MaterialTheme.typography.bodyMedium,
                            color = MaterialTheme.colorScheme.primary,
                            fontWeight = FontWeight.Medium
                        )
                    } else {
                        val errMsg = result.exceptionOrNull()?.localizedMessage ?: "Unknown error"
                        Text(
                            text = "✗ Failed to connect: $errMsg",
                            style = MaterialTheme.typography.bodyMedium,
                            color = MaterialTheme.colorScheme.error,
                            fontWeight = FontWeight.Medium
                        )
                    }
                }
            }

            HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant)

            // Book Selection / Summary
            if (selectedBooks.isNotEmpty()) {
                Column(modifier = Modifier.fillMaxWidth()) {
                    Text(
                        text = "Books to Send (${selectedBooks.size})",
                        style = MaterialTheme.typography.titleMedium,
                        fontWeight = FontWeight.Bold,
                        color = MaterialTheme.colorScheme.onSurface
                    )
                    Spacer(Modifier.height(8.dp))
                    selectedBooks.forEach { book ->
                        Text(
                            text = book.title,
                            style = MaterialTheme.typography.bodyMedium,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis,
                            modifier = Modifier.padding(vertical = 2.dp)
                        )
                    }
                }
            }

            // Transfer action and status
            Column(modifier = Modifier.fillMaxWidth()) {
                when (transferState) {
                    is BookViewModel.TransferState.Idle -> {
                        Button(
                            onClick = onStartTransfer,
                            enabled = kindleIp.isNotBlank() && selectedBooks.isNotEmpty(),
                            modifier = Modifier
                                .fillMaxWidth()
                                .height(48.dp),
                            shape = RoundedCornerShape(12.dp)
                        ) {
                            Text("Send to Kindle")
                        }
                    }

                    is BookViewModel.TransferState.Progress -> {
                        Column(
                            modifier = Modifier.fillMaxWidth(),
                            horizontalAlignment = Alignment.CenterHorizontally
                        ) {
                            LinearProgressIndicator(
                                progress = { if (transferState.total > 0) transferState.current.toFloat() / transferState.total else 0f },
                                modifier = Modifier.fillMaxWidth(),
                                color = MaterialTheme.colorScheme.primary,
                                trackColor = MaterialTheme.colorScheme.surfaceVariant
                            )
                            Spacer(Modifier.height(8.dp))
                            Text(
                                text = transferState.message,
                                style = MaterialTheme.typography.bodyMedium,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                                textAlign = TextAlign.Center
                            )
                        }
                    }

                    is BookViewModel.TransferState.Success -> {
                        Column(
                            modifier = Modifier.fillMaxWidth(),
                            horizontalAlignment = Alignment.CenterHorizontally
                        ) {
                            Text(
                                text = "✓ Sent Successfully!",
                                style = MaterialTheme.typography.titleMedium,
                                color = MaterialTheme.colorScheme.primary,
                                fontWeight = FontWeight.Bold
                            )
                            Spacer(Modifier.height(8.dp))
                            Text(
                                text = "Books have been transferred and indexed on your Kindle.",
                                style = MaterialTheme.typography.bodyMedium,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                                textAlign = TextAlign.Center
                            )
                            Spacer(Modifier.height(16.dp))
                            Button(
                                onClick = onDismiss,
                                modifier = Modifier.fillMaxWidth()
                            ) {
                                Text("Close")
                            }
                        }
                    }

                    is BookViewModel.TransferState.Error -> {
                        Column(
                            modifier = Modifier.fillMaxWidth(),
                            horizontalAlignment = Alignment.CenterHorizontally
                        ) {
                            Text(
                                text = "✗ Transfer Failed",
                                style = MaterialTheme.typography.titleMedium,
                                color = MaterialTheme.colorScheme.error,
                                fontWeight = FontWeight.Bold
                            )
                            Spacer(Modifier.height(8.dp))
                            Text(
                                text = transferState.message,
                                style = MaterialTheme.typography.bodyMedium,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                                textAlign = TextAlign.Center
                            )
                            Spacer(Modifier.height(16.dp))
                            Row(
                                modifier = Modifier.fillMaxWidth(),
                                horizontalArrangement = Arrangement.spacedBy(12.dp)
                            ) {
                                OutlinedButton(
                                    onClick = onResetTransferState,
                                    modifier = Modifier.weight(1f)
                                ) {
                                    Text("Back")
                                }
                                Button(
                                    onClick = onStartTransfer,
                                    modifier = Modifier.weight(1f)
                                ) {
                                    Text("Retry")
                                }
                            }
                        }
                    }
                }
            }
        }
    }
}

// ─────────────────────────────────────────────────────────────
// Pure UI content (preview-friendly)
// ─────────────────────────────────────────────────────────────

@OptIn(ExperimentalMaterial3Api::class)
@Composable
internal fun LibraryScreenContent(
    uiState: LibraryUiState,
    transferQueue: List<Book>,
    isConverting: Boolean,
    snackbarHostState: SnackbarHostState,
    onAddBookClick: () -> Unit,
    onToggleQueued: (Book) -> Unit,
    onDeleteBook: (Book) -> Unit,
    onOpenTransferSheet: () -> Unit,
    onNavigateToTools: () -> Unit = {},
    modifier: Modifier = Modifier,
) {
    val scrollBehavior = TopAppBarDefaults.exitUntilCollapsedScrollBehavior()

    Box(modifier = modifier.fillMaxSize()) {
        // Background image — full-bleed, Kindle aesthetic
        Image(
            painter = painterResource(id = R.drawable.bezos_bg),
            contentDescription = null,
            modifier = Modifier.fillMaxSize(),
            contentScale = ContentScale.Crop,
            alpha = 0.85f
        )

        // Gradient overlay — fades from transparent at top to surface at bottom
        Box(
            modifier = Modifier
                .fillMaxSize()
                .background(
                    Brush.verticalGradient(
                        colors = listOf(
                            Color.Transparent,
                            Color.Transparent,
                            MaterialTheme.colorScheme.surface.copy(alpha = 0.50f),
                            MaterialTheme.colorScheme.surface.copy(alpha = 0.85f),
                            MaterialTheme.colorScheme.surface
                        ),
                        startY = 0f,
                        endY = Float.POSITIVE_INFINITY
                    )
                )
        )

        Scaffold(
            modifier = Modifier.fillMaxSize(),
            topBar = {
                MediumTopAppBar(
                    title = {
                        Text(
                            text = "PageDrop",
                            style = MaterialTheme.typography.titleLarge,
                        )
                    },
                    actions = {
                        IconButton(onClick = onNavigateToTools) {
                            Icon(
                                Icons.Default.Build,
                                contentDescription = "Tools",
                            )
                        }
                        IconButton(onClick = onAddBookClick) {
                            Icon(
                                Icons.Default.Add,
                                contentDescription = "Add book",
                            )
                        }
                    },
                    colors = TopAppBarDefaults.mediumTopAppBarColors(
                        containerColor = Color.Transparent,
                        scrolledContainerColor = MaterialTheme.colorScheme.surface.copy(alpha = 0.92f),
                        titleContentColor = MaterialTheme.colorScheme.onSurface,
                    ),
                    scrollBehavior = scrollBehavior,
                )
            },
            floatingActionButton = {
                AnimatedVisibility(
                    visible = transferQueue.isNotEmpty(),
                    enter = fadeIn() + slideInVertically { it },
                    exit = fadeOut() + slideOutVertically { it },
                ) {
                    FloatingActionButton(
                        onClick = onOpenTransferSheet,
                        containerColor = MaterialTheme.colorScheme.primaryContainer.copy(alpha = 0.92f),
                        contentColor = MaterialTheme.colorScheme.onPrimaryContainer,
                    ) {
                        Icon(Icons.Default.Send, contentDescription = "Transfer")
                    }
                }
            },
            snackbarHost = { SnackbarHost(snackbarHostState) },
            containerColor = Color.Transparent,
        ) { innerPadding ->

            if (isConverting) {
                LinearProgressIndicator(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(top = innerPadding.calculateTopPadding()),
                    color = MaterialTheme.colorScheme.primary,
                    trackColor = MaterialTheme.colorScheme.surfaceVariant,
                )
            }

            when (uiState) {
                is LibraryUiState.Loading -> {
                    Box(
                        Modifier
                            .fillMaxSize()
                            .padding(innerPadding),
                        contentAlignment = Alignment.Center,
                    ) {
                        CircularProgressIndicator(color = MaterialTheme.colorScheme.primary)
                    }
                }

                is LibraryUiState.Error -> {
                    Box(
                        Modifier
                            .fillMaxSize()
                            .padding(innerPadding),
                        contentAlignment = Alignment.Center,
                    ) {
                        Text(
                            text = "Something went wrong.\n${uiState.throwable.localizedMessage}",
                            color = MaterialTheme.colorScheme.error,
                            style = MaterialTheme.typography.bodyLarge,
                        )
                    }
                }

                is LibraryUiState.Success -> {
                    if (uiState.data.isEmpty()) {
                        EmptyLibraryState(
                            modifier = Modifier
                                .fillMaxSize()
                                .padding(innerPadding),
                        )
                    } else {
                        BookList(
                            books = uiState.data,
                            transferQueue = transferQueue,
                            onToggleQueued = onToggleQueued,
                            onDelete = onDeleteBook,
                            contentPadding = innerPadding,
                        )
                    }
                }
            }
        }
    }
}

// ─────────────────────────────────────────────────────────────
// Empty state
// ─────────────────────────────────────────────────────────────

@Composable
private fun EmptyLibraryState(
    modifier: Modifier = Modifier,
) {
    Column(
        modifier = modifier.padding(horizontal = 32.dp),
        verticalArrangement = Arrangement.Center,
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        Card(
            colors = CardDefaults.cardColors(
                containerColor = MaterialTheme.colorScheme.surface.copy(alpha = 0.75f)
            ),
            shape = RoundedCornerShape(24.dp),
            elevation = CardDefaults.cardElevation(defaultElevation = 4.dp)
        ) {
            Column(
                modifier = Modifier.padding(40.dp),
                horizontalAlignment = Alignment.CenterHorizontally
            ) {
                Icon(
                    imageVector = Icons.Outlined.MenuBook,
                    contentDescription = null,
                    modifier = Modifier.size(64.dp),
                    tint = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                Spacer(Modifier.height(16.dp))
                Text(
                    text = "No books yet",
                    style = MaterialTheme.typography.titleMedium,
                    color = MaterialTheme.colorScheme.onSurface,
                )
                Spacer(Modifier.height(4.dp))
                Text(
                    text = "Tap + to add",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        }
    }
}

// ─────────────────────────────────────────────────────────────
// Book list (no swipe-to-dismiss)
// ─────────────────────────────────────────────────────────────

@OptIn(ExperimentalFoundationApi::class)
@Composable
private fun BookList(
    books: List<Book>,
    transferQueue: List<Book>,
    onToggleQueued: (Book) -> Unit,
    onDelete: (Book) -> Unit,
    contentPadding: PaddingValues,
) {
    LazyColumn(
        modifier = Modifier.fillMaxSize(),
        contentPadding = PaddingValues(
            top = contentPadding.calculateTopPadding() + 8.dp,
            bottom = contentPadding.calculateBottomPadding() + 96.dp,
            start = 16.dp,
            end = 16.dp,
        ),
        verticalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        items(
            items = books,
            key = { it.uid },
        ) { book ->
            val isQueued = transferQueue.any { it.uid == book.uid }

            BookCard(
                book = book,
                isQueued = isQueued,
                onTap = { onToggleQueued(book) },
                onDelete = { onDelete(book) },
                modifier = Modifier.animateItem(
                    fadeInSpec = spring(stiffness = Spring.StiffnessMediumLow),
                    fadeOutSpec = spring(stiffness = Spring.StiffnessMediumLow),
                ),
            )
        }
    }
}

// ─────────────────────────────────────────────────────────────
// Individual book card
// ─────────────────────────────────────────────────────────────

@OptIn(ExperimentalFoundationApi::class)
@Composable
private fun BookCard(
    book: Book,
    isQueued: Boolean,
    onTap: () -> Unit,
    onDelete: () -> Unit,
    modifier: Modifier = Modifier,
) {
    var showMenu by remember { mutableStateOf(false) }
    val context = LocalContext.current

    Card(
        modifier = modifier
            .fillMaxWidth()
            .combinedClickable(
                onClick = onTap,
                onLongClick = { showMenu = true },
            ),
        shape = RoundedCornerShape(12.dp),
        colors = CardDefaults.cardColors(
            containerColor = MaterialTheme.colorScheme.surface.copy(alpha = 0.78f),
        ),
        elevation = CardDefaults.cardElevation(defaultElevation = 2.dp),
    ) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 12.dp, vertical = 12.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            // Selection checkbox
            Checkbox(
                checked = isQueued,
                onCheckedChange = { onTap() },
            )

            Spacer(Modifier.width(8.dp))

            // Book info
            Column(modifier = Modifier.weight(1f)) {
                Text(
                    text = book.title,
                    style = MaterialTheme.typography.titleSmall,
                    fontWeight = FontWeight.Medium,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                    color = MaterialTheme.colorScheme.onSurface,
                )
                Spacer(Modifier.height(2.dp))
                Text(
                    text = book.author,
                    style = MaterialTheme.typography.bodySmall,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }

            Spacer(Modifier.width(8.dp))

            // Format chip (outlined)
            Box(
                modifier = Modifier
                    .border(
                        width = 1.dp,
                        color = MaterialTheme.colorScheme.outline,
                        shape = RoundedCornerShape(6.dp),
                    )
                    .padding(horizontal = 8.dp, vertical = 4.dp),
            ) {
                Text(
                    text = book.format.uppercase(),
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }

            Spacer(Modifier.width(8.dp))

            // File size
            Text(
                text = Formatter.formatShortFileSize(context, book.fileSize),
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )

            // Context menu (long press)
            DropdownMenu(
                expanded = showMenu,
                onDismissRequest = { showMenu = false },
            ) {
                DropdownMenuItem(
                    text = { Text("Delete") },
                    onClick = {
                        showMenu = false
                        onDelete()
                    },
                    leadingIcon = {
                        Icon(
                            Icons.Rounded.Delete,
                            contentDescription = null,
                            tint = MaterialTheme.colorScheme.error,
                        )
                    },
                )
            }
        }
    }
}
