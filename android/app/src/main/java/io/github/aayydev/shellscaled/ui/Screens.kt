package io.github.aayydev.shellscaled.ui

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.AssistChip
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.FilledTonalButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import android.annotation.SuppressLint
import android.content.ClipData
import android.os.Build
import android.os.PersistableBundle
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.ui.platform.ClipEntry
import androidx.compose.ui.platform.LocalClipboard
import kotlinx.coroutines.launch
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.ui.unit.dp
import io.github.aayydev.shellscaled.R
import io.github.aayydev.shellscaled.rpc.Device
import io.github.aayydev.shellscaled.rpc.Status
import java.text.DateFormat
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

@Composable
fun ScrollScreen(content: @Composable () -> Unit) {
    Column(
        Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(ScreenPadding),
        verticalArrangement = Arrangement.spacedBy(16.dp),
    ) { content() }
}

private fun stateTexts(state: String): Pair<Int, Int> = when (state) {
    "Running" -> R.string.w_state_Running_title to R.string.w_state_Running_description
    "Stopped" -> R.string.w_state_Stopped_title to R.string.w_state_Stopped_description
    "NeedsLogin" -> R.string.w_state_NeedsLogin_title to R.string.w_state_NeedsLogin_description
    "NeedsMachineAuth" -> R.string.w_state_NeedsMachineAuth_title to R.string.w_state_NeedsMachineAuth_description
    "ServiceStopped" -> R.string.w_state_ServiceStopped_title to R.string.w_state_ServiceStopped_description
    "ServiceError" -> R.string.w_state_ServiceError_title to R.string.w_state_ServiceError_description
    "Starting" -> R.string.w_state_Starting_title to R.string.w_state_Starting_description
    else -> R.string.w_state_NoState_title to R.string.w_state_NoState_description
}

fun connectionLabel(status: Status?, busy: String?): Int = when {
    busy == "down" -> R.string.w_connection_disconnecting
    busy == "up" -> R.string.w_connection_connecting
    status?.state == "Starting" -> R.string.w_connection_cancel
    status?.state == "Running" -> R.string.w_connection_disconnect
    else -> R.string.w_connection_connect
}

@Composable
fun CopyRow(value: String, label: String? = null, sensitive: Boolean = false) {
    val clipboard = LocalClipboard.current
    val scope = rememberCoroutineScope()
    var copied by remember(value) { mutableStateOf(false) }
    Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
        Column(Modifier.weight(1f)) {
            if (label != null) Text(label, style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
            Mono(value)
        }
        TextButton(
            onClick = {
                val clip = ClipData.newPlainText(label ?: "Shellscaled", value)
                // Keep private values (the sign-in link) out of clipboard previews and keyboard suggestions.
                if (sensitive && Build.VERSION.SDK_INT >= 24) {
                    clip.description.extras = PersistableBundle().apply { putBoolean(EXTRA_IS_SENSITIVE, true) }
                }
                scope.launch { clipboard.setClipEntry(ClipEntry(clip)) }
                copied = true
            },
            modifier = Modifier.focusRing(),
        ) { Text(stringResource(if (copied) R.string.w_copy_copied else R.string.w_copy_action)) }
    }
}

// ---- Overview ---------------------------------------------------------------------------------

@OptIn(ExperimentalLayoutApi::class)
@Composable
fun OverviewScreen(state: UiState, vm: MainViewModel, tv: Boolean) {
    val status = state.status
    val primaryFocus = remember { FocusRequester() }
    // On TV, start D-pad focus on the connection button once it is enabled (after the first status).
    val focusable = status != null && state.busy == null
    var focusedOnce by rememberSaveable { mutableStateOf(false) }
    if (tv && focusable && !focusedOnce) {
        LaunchedEffect(Unit) { focusedOnce = runCatching { primaryFocus.requestFocus() }.getOrDefault(false) }
    }
    ScrollScreen {
        SectionCard(stringResource(R.string.w_overview_heading)) {
            val (title, description) = if (status == null) R.string.w_state_reading to R.string.w_state_unread else stateTexts(status.state)
            Text(stringResource(title), style = MaterialTheme.typography.headlineMedium)
            Text(stringResource(description), style = MaterialTheme.typography.bodyLarge)
            if (status?.error != null && status.state == "ServiceError") Message(UiText.Raw(status.error), MessageKind.MUTED)
            Text(
                stringResource(
                    when {
                        status == null -> R.string.w_service_unread
                        status.daemonRunning -> R.string.w_service_running
                        else -> R.string.w_service_stopped
                    },
                ),
                style = MaterialTheme.typography.labelLarge,
            )
            val freshness = when {
                state.statusError != null -> state.statusError
                state.needsVerify -> res(R.string.w_freshness_verify)
                state.updatedAt != null -> res(R.string.w_freshness_updated, "time" to DateFormat.getTimeInstance(DateFormat.SHORT).format(Date(state.updatedAt)))
                else -> res(R.string.w_freshness_waiting)
            }
            Message(freshness, if (state.statusError != null) MessageKind.ERROR else MessageKind.MUTED)
            val working = state.busy == "up" || state.busy == "down"
            FlowRow(horizontalArrangement = Arrangement.spacedBy(12.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                val label = stringResource(connectionLabel(status, state.busy))
                val enabled = status != null && state.busy == null
                if (status?.state == "Running") {
                    FilledTonalButton(vm::toggleConnection, Modifier.focusRequester(primaryFocus).focusRing(), enabled = enabled) { ButtonContent(label, working) }
                } else {
                    Button(vm::toggleConnection, Modifier.focusRequester(primaryFocus).focusRing(), enabled = enabled) { ButtonContent(label, working) }
                }
                OutlinedButton({ vm.refresh(announce = true) }, Modifier.focusRing(), enabled = state.busy == null) {
                    Text(stringResource(R.string.w_overview_refresh))
                }
            }
        }

        if (status?.state == "NeedsLogin") LoginCard(state, vm)

        SectionCard(stringResource(R.string.w_overview_addresses)) {
            when {
                status == null -> Message(res(R.string.w_overview_addressWaiting), MessageKind.MUTED)
                status.ips.isEmpty() -> Message(res(R.string.w_overview_addressEmpty), MessageKind.MUTED)
                else -> status.ips.forEach { CopyRow(it, stringResource(R.string.w_overview_addressType)) }
            }
        }

        SectionCard(null) {
            FlowRow(horizontalArrangement = Arrangement.spacedBy(12.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                OutlinedButton(vm::openConsole, Modifier.focusRing(), enabled = state.busy == null) {
                    Text(stringResource(if (state.busy == "console") R.string.w_console_opening else R.string.w_console_open))
                }
                OutlinedButton(vm::openAbout, Modifier.focusRing(), enabled = state.busy == null) {
                    Text(stringResource(if (state.busy == "about") R.string.w_about_opening else R.string.w_about_title))
                }
            }
            status?.version?.takeIf { it.isNotBlank() }?.let {
                Message(res(R.string.module_version, "version" to it), MessageKind.MUTED)
            }
        }
    }
}

@Composable
private fun ButtonContent(label: String, working: Boolean) {
    if (working) {
        CircularProgressIndicator(Modifier.size(18.dp), strokeWidth = 2.dp, color = ButtonDefaults.buttonColors().contentColor)
        Text("  ")
    }
    Text(label)
}

@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun LoginCard(state: UiState, vm: MainViewModel) {
    val url = state.status?.loginUrl
    SectionCard(stringResource(R.string.w_login_heading)) {
        Text(stringResource(R.string.w_login_description))
        if (url != null) CopyRow(url, stringResource(R.string.w_login_url), sensitive = true) else Message(res(R.string.w_login_linkPending), MessageKind.MUTED)
        FlowRow(horizontalArrangement = Arrangement.spacedBy(12.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            Button(vm::login, Modifier.focusRing(), enabled = state.busy == null) { Text(stringResource(R.string.w_login_action)) }
            if (url != null) OutlinedButton(vm::showLoginQr, Modifier.focusRing()) { Text(stringResource(R.string.qr_show)) }
        }
        Message(state.loginMessage)
    }
}

// ---- Devices ----------------------------------------------------------------------------------

@Composable
fun DevicesScreen(state: UiState, vm: MainViewModel) {
    LaunchedEffect(Unit) { if (state.devices == null) vm.loadDevices() }
    var query by rememberSaveable { mutableStateOf("") }
    val devices = state.devices.orEmpty()
    val filtered = if (query.isBlank()) devices else devices.filter { d ->
        val q = query.trim().lowercase()
        d.name.lowercase().contains(q) || d.dnsName.lowercase().contains(q) || d.ips.any { it.contains(q) }
    }
    LazyColumn(Modifier.fillMaxSize(), contentPadding = ScreenPadding, verticalArrangement = Arrangement.spacedBy(12.dp)) {
        item {
            Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                Text(stringResource(R.string.w_devices_heading), style = MaterialTheme.typography.headlineSmall)
                Text(stringResource(R.string.w_devices_description), color = MaterialTheme.colorScheme.onSurfaceVariant)
                Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                    OutlinedTextField(
                        query, { query = it }, Modifier.weight(1f).focusRing(), singleLine = true,
                        label = { Text(stringResource(R.string.w_devices_search)) },
                    )
                    OutlinedButton(vm::loadDevices, Modifier.focusRing(), enabled = !state.devicesLoading) {
                        Text(stringResource(R.string.w_devices_refresh))
                    }
                }
                when {
                    state.devicesLoading -> Message(res(R.string.w_devices_loading), MessageKind.MUTED)
                    state.devicesError != null -> Message(
                        UiText.Joined(res(R.string.w_devices_loadFailed), if (state.devices != null) res(R.string.w_devices_previousData) else state.devicesError),
                        MessageKind.ERROR,
                    )
                    state.devices == null -> Message(res(R.string.w_devices_notLoaded), MessageKind.MUTED)
                    devices.isEmpty() -> Message(res(R.string.w_devices_empty), MessageKind.MUTED)
                    query.isNotBlank() && filtered.isEmpty() -> Message(res(R.string.w_devices_noResults), MessageKind.MUTED)
                    query.isNotBlank() -> Message(res(R.string.w_devices_filteredSummary, "count" to filtered.size.toString()), MessageKind.MUTED)
                    else -> Message(
                        res(R.string.w_devices_summary, "total" to devices.size.toString(), "online" to devices.count { it.online }.toString()),
                        MessageKind.MUTED,
                    )
                }
            }
        }
        items(filtered, key = { it.id + it.dnsName }) { DeviceCard(it) }
    }
}

@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun DeviceCard(device: Device) {
    SectionCard(null, Modifier.focusRing()) {
        Text(device.displayName.ifBlank { stringResource(R.string.w_devices_unnamed) }, style = MaterialTheme.typography.titleMedium)
        FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            AssistChip({}, { Text(stringResource(if (device.online) R.string.w_devices_online else R.string.w_devices_offline)) })
            if (device.self) AssistChip({}, { Text(stringResource(R.string.w_devices_self)) })
            if (device.exitNode) AssistChip({}, { Text(stringResource(R.string.devices_exit_node)) })
            else if (device.exitNodeOption) AssistChip({}, { Text(stringResource(R.string.devices_exit_option)) })
            if (device.os.isNotBlank()) AssistChip({}, { Text(device.os) })
        }
        if (device.dnsName.isNotBlank()) CopyRow(device.dnsName, stringResource(R.string.w_devices_dns))
        if (device.ips.isEmpty()) Message(res(R.string.w_devices_noAddresses), MessageKind.MUTED)
        device.ips.forEach { ip -> CopyRow(ip, stringResource(if (':' in ip) R.string.w_devices_ipv6 else R.string.w_devices_ipv4)) }
        if (!device.online) formatLastSeen(device.lastSeen)?.let { Message(res(R.string.w_devices_lastSeen, "time" to it), MessageKind.MUTED) }
    }
}

/** RFC 3339 from Go (`2026-11-16T10:00:00.123Z` or `+08:00`) without java.time, for Android 6+. */
fun formatLastSeen(value: String?): String? {
    if (value.isNullOrBlank() || value.startsWith("0001-")) return null
    val normalized = value.replace(Regex("\\.\\d+"), "").replace(Regex("Z$"), "+0000").replace(Regex("([+-]\\d{2}):(\\d{2})$"), "$1$2")
    val date = runCatching { SimpleDateFormat("yyyy-MM-dd'T'HH:mm:ssZ", Locale.US).parse(normalized) }.getOrNull() ?: return null
    return DateFormat.getDateTimeInstance(DateFormat.MEDIUM, DateFormat.SHORT).format(date)
}

// ---- Taildrop ---------------------------------------------------------------------------------

// Root-side paths exactly as the helper spells them (helper/main.go), not this app's storage.
private const val SHARED_ROOT = "/storage/emulated/0"
@SuppressLint("SdCardPath")
private val SHARED_ROOTS = setOf(SHARED_ROOT, "/sdcard")
private val PRIVATE_APP_DIRS = Regex("^/storage/emulated/0/Android/(data|obb|sandbox)(/|$)")

/** Mirrors the helper's validTaildropPath: a subdirectory of shared storage outside app-private folders. */
fun choosableTaildropPath(path: String): Boolean =
    path.startsWith("$SHARED_ROOT/") && path !in SHARED_ROOTS && !PRIVATE_APP_DIRS.containsMatchIn(path)

@OptIn(ExperimentalLayoutApi::class)
@Composable
fun TaildropScreen(state: UiState, vm: MainViewModel) {
    ScrollScreen {
        SectionCard(stringResource(R.string.w_taildrop_heading)) {
            Text(stringResource(R.string.w_taildrop_description))
            Text(stringResource(R.string.w_taildrop_pathLabel), style = MaterialTheme.typography.labelLarge)
            Mono(state.status?.taildropPath?.ifBlank { null } ?: stringResource(R.string.w_taildrop_pathPlaceholder))
            FlowRow(horizontalArrangement = Arrangement.spacedBy(12.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                OutlinedButton({ vm.taildropBrowse(state.status?.taildropPath.orEmpty()) }, Modifier.focusRing(), enabled = state.busy == null) {
                    Text(stringResource(R.string.w_taildrop_browse))
                }
                Button(vm::taildropReceive, Modifier.focusRing(), enabled = state.busy == null) {
                    Text(stringResource(if (state.busy == "taildrop-receive") R.string.w_taildrop_receiving else R.string.w_taildrop_receive))
                }
            }
            Message(state.taildropMessage)
            Message(state.taildropError, MessageKind.ERROR)
        }
        val dirs = state.taildropDirs
        if (dirs != null || state.busy == "taildrop-list") {
            SectionCard(stringResource(R.string.w_taildrop_directoryList)) {
                val current = state.taildropBrowse.orEmpty()
                if (current.isNotBlank()) Mono(current)
                if (state.busy == "taildrop-list") Message(res(R.string.w_taildrop_browsing), MessageKind.MUTED)
                FlowRow(horizontalArrangement = Arrangement.spacedBy(12.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    val parent = current.substringBeforeLast('/', "")
                    if (current.length > SHARED_ROOT.length && parent.startsWith(SHARED_ROOT)) {
                        OutlinedButton({ vm.taildropBrowse(parent) }, Modifier.focusRing(), enabled = state.busy == null) {
                            Text(stringResource(R.string.w_taildrop_up))
                        }
                    }
                    if (choosableTaildropPath(current)) {
                        Button({ vm.taildropChoose(current) }, Modifier.focusRing(), enabled = state.busy == null) {
                            Text(stringResource(if (state.busy == "taildrop-path") R.string.w_taildrop_saving else R.string.w_taildrop_choose))
                        }
                    }
                }
                if (dirs != null && dirs.isEmpty()) Message(res(R.string.w_taildrop_empty), MessageKind.MUTED)
                dirs.orEmpty().forEach { dir ->
                    TextButton({ vm.taildropBrowse(dir.path) }, Modifier.fillMaxWidth().focusRing(), enabled = state.busy == null) {
                        Text(dir.name + "  ›", Modifier.fillMaxWidth())
                    }
                }
                if (state.taildropTruncated) Message(res(R.string.w_taildrop_more), MessageKind.MUTED)
            }
        }
    }
}

// ---- Command panel ----------------------------------------------------------------------------

private val EXAMPLES = listOf("tailscale status", "tailscale version", "tailscale netcheck", "tailscaled --version")

@OptIn(ExperimentalLayoutApi::class)
@Composable
fun CommandScreen(state: UiState, vm: MainViewModel) {
    var input by rememberSaveable { mutableStateOf("") }
    ScrollScreen {
        SectionCard(stringResource(R.string.w_command_heading)) {
            Text(stringResource(R.string.w_command_helpCommands), color = MaterialTheme.colorScheme.onSurfaceVariant)
            Text(stringResource(R.string.w_command_helpArguments), color = MaterialTheme.colorScheme.onSurfaceVariant)
            OutlinedTextField(
                input, { input = it }, Modifier.fillMaxWidth().focusRing(),
                label = { Text(stringResource(R.string.w_command_label)) },
                placeholder = { Text(stringResource(R.string.w_command_placeholder), fontFamily = FontFamily.Monospace) },
                singleLine = true, textStyle = MaterialTheme.typography.bodyLarge.copy(fontFamily = FontFamily.Monospace),
                keyboardOptions = KeyboardOptions(imeAction = ImeAction.Go, autoCorrectEnabled = false),
                keyboardActions = KeyboardActions(onGo = { vm.runCommand(input) }),
            )
            Text(stringResource(R.string.w_command_examples), style = MaterialTheme.typography.labelLarge)
            FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                // Literal command syntax in every language.
                EXAMPLES.forEach { example ->
                    AssistChip({ input = example }, { Text(example, fontFamily = FontFamily.Monospace) }, Modifier.focusRing())
                }
            }
            FlowRow(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                Button({ vm.runCommand(input) }, Modifier.focusRing(), enabled = state.busy == null && input.isNotBlank()) {
                    Text(stringResource(if (state.busy == "command") R.string.w_command_running else R.string.w_command_run))
                }
                OutlinedButton({ input = ""; vm.clearCommand() }, Modifier.focusRing()) { Text(stringResource(R.string.w_command_clear)) }
            }
        }
        SectionCard(stringResource(R.string.w_command_result)) {
            val result = state.commandResult
            when {
                result == null && state.commandError != null -> Message(UiText.Joined(res(R.string.w_command_rejected), state.commandError), MessageKind.ERROR)
                result == null -> Message(res(R.string.w_command_waiting), MessageKind.MUTED)
                else -> {
                    // A command that ran but failed (non-zero exit, timeout) keeps its output.
                    Message(state.commandError, MessageKind.ERROR)
                    Message(
                        if (result.exitCode == 0) res(R.string.w_command_complete) else res(R.string.w_command_exitCode, "code" to result.exitCode.toString()),
                        if (result.exitCode == 0) MessageKind.INFO else MessageKind.WARNING,
                    )
                    if (result.stdout.isBlank() && result.stderr.isBlank()) Message(res(R.string.w_command_empty), MessageKind.MUTED)
                    if (result.stdout.isNotBlank()) Mono(result.stdout.take(MAX_OUTPUT))
                    if (result.stderr.isNotBlank()) {
                        Text(stringResource(R.string.w_command_errorOutput), style = MaterialTheme.typography.labelLarge)
                        Mono(result.stderr.take(MAX_OUTPUT))
                    }
                    if (result.truncated || result.stdout.length > MAX_OUTPUT) Message(res(R.string.w_command_truncated), MessageKind.MUTED)
                }
            }
        }
    }
}

private const val MAX_OUTPUT = 60_000

/** ClipDescription.EXTRA_IS_SENSITIVE (API 33); older versions ignore the extra. */
private const val EXTRA_IS_SENSITIVE = "android.content.extra.IS_SENSITIVE"
