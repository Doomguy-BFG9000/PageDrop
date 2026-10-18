package app.pagedrop.ui.onboarding

import android.content.ActivityNotFoundException
import android.content.Context
import android.content.Intent
import android.net.Uri
import android.widget.Toast
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
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
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
import androidx.compose.ui.layout.onGloballyPositioned
import androidx.compose.ui.layout.positionInParent
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.unit.dp
import androidx.hilt.lifecycle.viewmodel.compose.hiltViewModel
import app.pagedrop.ui.shell.HairlineCard
import app.pagedrop.ui.shell.InkButton
import app.pagedrop.ui.shell.OutlineButton

const val WIZARD_URL = "https://kindlemodding.org/jailbreak-wizard.html"
const val JAILBREAK_OVERVIEW_URL = "https://kindlemodding.org/jailbreaking/"
const val KOREADER_URL = "https://kindlemodding.org/jailbreaking/whats-next/getting-koreader/"
const val PREVENT_UPDATES_URL = "https://kindlemodding.org/jailbreaking/prevent-auto-update/"

internal fun openLink(context: Context, url: String) {
    try {
        context.startActivity(Intent(Intent.ACTION_VIEW, Uri.parse(url)).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
    } catch (_: ActivityNotFoundException) {
        Toast.makeText(context, "No browser found. Open $url on any device.", Toast.LENGTH_LONG).show()
    }
}

/** "Get your Kindle ready": a checklist for Kindles that are not jailbroken yet. */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun KindleReadyScreen(
    modifier: Modifier = Modifier,
    onBack: () -> Unit,
    onConnect: () -> Unit,
    viewModel: KindleReadyViewModel = hiltViewModel()
) {
    val state by viewModel.state.collectAsState()
    val context = LocalContext.current
    val next = nextUndoneStep(state.done)
    var expanded by remember { mutableStateOf(next) }
    val scroll = rememberScrollState()
    val positions = remember { mutableMapOf<Int, Int>() }

    // Open and scroll to the next undone step when progress changes (and on first show).
    LaunchedEffect(next) {
        expanded = next
        next?.let { positions[it] }?.let { scroll.animateScrollTo(it) }
    }

    Scaffold(
        modifier = modifier,
        topBar = {
            TopAppBar(
                title = { Text("Get your Kindle ready") },
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
    ) { inner ->
        Column(
            Modifier.fillMaxSize().padding(inner).verticalScroll(scroll).padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp)
        ) {
            Text(
                "PageDrop sends books to your Kindle over Wi-Fi. To do that your Kindle needs three things: " +
                    "a jailbreak, KOReader, and KOReader's SSH server switched on. About 20-40 minutes, once.",
                style = MaterialTheme.typography.bodyMedium
            )
            Text(
                "Jailbreaking isn't supported by Amazon and carries a small risk. Follow the wiki exactly.",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
            val doneCount = state.done.size
            Text(
                if (next == null) "All $KINDLE_READY_STEP_COUNT steps done" else "Step ${next + 1} of $KINDLE_READY_STEP_COUNT",
                style = MaterialTheme.typography.labelLarge
            )
            LinearProgressIndicator(
                progress = { doneCount / KINDLE_READY_STEP_COUNT.toFloat() },
                modifier = Modifier.fillMaxWidth(),
                trackColor = MaterialTheme.colorScheme.outlineVariant
            )

            StepCard(0, "Check your Kindle", state.done, expanded, positions, viewModel, { expanded = if (expanded == 0) null else 0 }) {
                Body("Find your model and firmware version: Settings → Device Options → Device Info, or Settings → Menu → Device Info on older models (wording varies slightly).")
                Body("The wizard will ask for these. Writing the firmware version here is optional and only stays on this phone.")
                OutlinedTextField(
                    value = state.firmware,
                    onValueChange = viewModel::setFirmware,
                    label = { Text("Firmware version (optional)") },
                    singleLine = true,
                    modifier = Modifier.fillMaxWidth()
                )
            }
            StepCard(1, "Back up your books", state.done, expanded, positions, viewModel, { expanded = if (expanded == 1) null else 1 }) {
                Body("Some jailbreak methods erase the Kindle. Before you start, connect it to a computer by USB and copy the documents folder somewhere safe.")
            }
            StepCard(2, "Jailbreak with the wiki's wizard", state.done, expanded, positions, viewModel, { expanded = if (expanded == 2) null else 2 }) {
                Body("The Kindle Modding Wiki's wizard tells you the right jailbreak for your Kindle. Follow its instructions exactly, including any airplane-mode or Wi-Fi steps.")
                Body("Kindles update themselves over Wi-Fi, which can close the jailbreak. Keep Wi-Fi off until updates are blocked (step 5).")
                InkButton("Open the jailbreak wizard", { openLink(context, WIZARD_URL) }, Modifier.fillMaxWidth())
                TextButton(onClick = { openLink(context, JAILBREAK_OVERVIEW_URL) }) { Text("What is jailbreaking?") }
            }
            StepCard(3, "Install KOReader", state.done, expanded, positions, viewModel, { expanded = if (expanded == 3) null else 3 }) {
                Body("Follow the wiki's KOReader page. It tells you which installer your jailbreak needs.")
                OutlineButton("Open the KOReader guide", { openLink(context, KOREADER_URL) }, Modifier.fillMaxWidth())
            }
            StepCard(4, "Block automatic updates", state.done, expanded, positions, viewModel, { expanded = if (expanded == 4) null else 4 }) {
                Body("Do this before you turn Wi-Fi back on, so an update can't undo your work.")
                OutlineButton("Open the update-blocking guide", { openLink(context, PREVENT_UPDATES_URL) }, Modifier.fillMaxWidth())
            }
            StepCard(5, "Turn on KOReader's SSH server", state.done, expanded, positions, viewModel, { expanded = if (expanded == 5) null else 5 }) {
                Body("In KOReader, open the Tools menu (cog icon) → Network → SSH server, then tick \"SSH server\". On older versions it may be under Tools → More tools.")
                Body("The port is 2222 and the username is root. SSH only runs while KOReader is open.")
                Body("For the first connection, tick \"Login without password (DANGEROUS)\". While it's on, anyone on your Wi-Fi can get into the Kindle, so only use it on a network you trust.")
                Body("Once PageDrop is connected, open Settings → Kindle connection → Set up key login. PageDrop adds its own key to the Kindle; then turn \"Login without password\" off again.")
            }
            StepCard(6, "Connect PageDrop", state.done, expanded, positions, viewModel, { expanded = if (expanded == 6) null else 6 }) {
                Body("Turn Wi-Fi on, join the same network as this phone, keep KOReader open, and let PageDrop find your Kindle.")
                InkButton("Connect your Kindle", onConnect, Modifier.fillMaxWidth())
            }
        }
    }
}

@Composable
private fun Body(text: String, mono: Boolean = false) {
    Text(
        text,
        style = MaterialTheme.typography.bodyMedium,
        fontFamily = if (mono) FontFamily.Monospace else null
    )
}

@Composable
private fun StepCard(
    index: Int,
    title: String,
    done: Set<Int>,
    expanded: Int?,
    positions: MutableMap<Int, Int>,
    viewModel: KindleReadyViewModel,
    onToggle: () -> Unit,
    content: @Composable () -> Unit
) {
    val isDone = index in done
    HairlineCard(Modifier.fillMaxWidth().onGloballyPositioned { positions[index] = it.positionInParent().y.toInt() }) {
        Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
            Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                TextButton(onClick = onToggle, modifier = Modifier.weight(1f)) {
                    Text(
                        "${index + 1}. $title",
                        style = MaterialTheme.typography.titleMedium,
                        modifier = Modifier.weight(1f)
                    )
                }
                Text(if (isDone) "Done" else "", style = MaterialTheme.typography.labelMedium)
            }
            if (expanded == index) {
                content()
                if (isDone) {
                    OutlineButton("Mark as not done", { viewModel.setDone(index, false) }, Modifier.fillMaxWidth())
                } else {
                    InkButton("Done", { viewModel.setDone(index, true) }, Modifier.fillMaxWidth())
                }
            }
        }
    }
}
