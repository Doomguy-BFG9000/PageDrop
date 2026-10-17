package app.pagedrop.ui.connection

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material3.Button
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import androidx.hilt.lifecycle.viewmodel.compose.hiltViewModel
import app.pagedrop.ui.shell.ResultBanner
import app.pagedrop.ui.theme.ButtonShape

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun ConnectionScreen(
    modifier: Modifier = Modifier,
    onBack: () -> Unit = {},
    viewModel: ConnectionViewModel = hiltViewModel()
) {
    val state by viewModel.state.collectAsState()

    Scaffold(
        modifier = modifier,
        topBar = {
            TopAppBar(
                title = { Text("Kindle connection") },
                navigationIcon = {
                    IconButton(onClick = onBack) {
                        Icon(Icons.AutoMirrored.Filled.ArrowBack, "Back")
                    }
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
            modifier = Modifier
                .fillMaxSize()
                .padding(innerPadding)
                .verticalScroll(rememberScrollState())
                .padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(16.dp)
        ) {
            Text(
                text = "How PageDrop reaches your jailbroken Kindle over Wi-Fi. Changes are saved as you type.",
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )

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

            Button(
                onClick = viewModel::testConnection,
                enabled = !state.isTesting && state.host.isNotBlank(),
                modifier = Modifier.fillMaxWidth(),
                shape = ButtonShape
            ) {
                if (state.isTesting) {
                    CircularProgressIndicator(
                        modifier = Modifier.size(18.dp),
                        strokeWidth = 2.dp,
                        color = MaterialTheme.colorScheme.onPrimary
                    )
                    Spacer(Modifier.width(8.dp))
                    Text("Testing...")
                } else {
                    Text("Test connection")
                }
            }

            state.testResult?.let { result ->
                if (result.isSuccess) {
                    ResultBanner(true, "Connected", "PageDrop can reach your Kindle.")
                } else {
                    val hint = ConnectionHints.explain(result.exceptionOrNull(), state.testedPort)
                    ResultBanner(false, hint.title, hint.fix)
                }
            }
        }
    }
}
