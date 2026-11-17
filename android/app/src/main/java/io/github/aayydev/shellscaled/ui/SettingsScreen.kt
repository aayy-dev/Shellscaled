package io.github.aayydev.shellscaled.ui

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.material3.Button
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import io.github.aayydev.shellscaled.R
import io.github.aayydev.shellscaled.rpc.Settings
import io.github.aayydev.shellscaled.rpc.Status

@Composable
fun SettingsScreen(state: UiState, vm: MainViewModel) {
    ScrollScreen {
        LanguageCard()
        NetworkCard(state, vm)
        PreferencesCard(state, vm)
        BinaryCard(state, vm)
    }
}

/** Language comes first in Settings (AGENTS.md). */
@Composable
private fun LanguageCard() {
    var current by remember { mutableStateOf(Language.current()) }
    SectionCard(stringResource(R.string.w_language_label)) {
        Language.options.forEach { option ->
            RadioRow(stringResource(Language.label(option)), selected = current == option) {
                current = option
                Language.set(option)
            }
        }
    }
}

@Composable
private fun NetworkCard(state: UiState, vm: MainViewModel) {
    val status = state.status
    // Network roles change only while the daemon's settings are confirmed (WebUI parity).
    val ready = status != null && state.busy == null && state.settingsConfirmed
    var exitEnabledDraft by remember { mutableStateOf<Boolean?>(null) }
    val exitEnabled = exitEnabledDraft ?: (status?.exitNodeConfigured == true)
    LaunchedEffect(status?.exitNodeConfigured) { exitEnabledDraft = null }
    LaunchedEffect(exitEnabled) { if (exitEnabled && state.exitNodes == null) vm.loadExitNodes() }

    SectionCard(stringResource(R.string.w_network_heading)) {
        Text(stringResource(R.string.w_network_description), color = MaterialTheme.colorScheme.onSurfaceVariant)

        SwitchRow(
            stringResource(R.string.w_network_exitNode), stringResource(R.string.w_network_exitNodeDescription),
            checked = exitEnabled, enabled = ready, pending = state.busy == "exit-node",
        ) { on ->
            // Turning on only reveals the picker; turning off clears the exit node immediately.
            if (on) exitEnabledDraft = true else { exitEnabledDraft = false; vm.setExitNode("") }
        }
        Message(exitSummary(status, state), if (status?.exitNodeConfigured == true && status.state == "Running" && status.exitNodeOnline == false) MessageKind.WARNING else MessageKind.MUTED)
        if (exitEnabled) ExitNodePicker(state, vm)

        if (status?.exitNodeConfigured == true) {
            // The saved preference is exitNodeIPv6Takeover; exitNodeIPv6 only reports whether
            // IPv6 is currently routed through the tunnel.
            val takeover = state.exitIpv6Pending ?: status.exitNodeIPv6Takeover
            SwitchRow(
                stringResource(R.string.w_network_exitIpv6), stringResource(R.string.w_network_exitIpv6Description),
                checked = takeover, enabled = ready, pending = state.busy == "exit-node-ipv6",
            ) { if (it != takeover) vm.setExitNodeIPv6(it) }
            ipv6Line(status, takeover)?.let { (text, kind) -> Message(text, kind) }
        }

        HorizontalDivider()
        SwitchRow(
            stringResource(R.string.w_network_magicDNS), stringResource(R.string.w_network_magicDNSDescription),
            checked = status?.magicDnsEnabled == true, enabled = ready, pending = state.busy == "magicdns",
        ) { vm.setMagicDns(it) }
        magicDnsLine(status, state.busy == "magicdns")?.let { (text, kind) -> Message(text, kind) }

        HorizontalDivider()
        AdvertiseSection(state, vm)
        Message(state.networkMessage)
        Message(state.networkError?.let { UiText.Joined(res(R.string.w_network_failed), it) }, MessageKind.ERROR)
    }
}

@Composable
private fun ExitNodePicker(state: UiState, vm: MainViewModel) {
    val status = state.status
    val nodes = state.exitNodes
    when {
        state.exitNodesLoading -> Message(res(R.string.w_network_loading), MessageKind.MUTED)
        state.exitNodesError != null -> {
            Message(UiText.Joined(res(R.string.w_network_loadFailed), state.exitNodesError), MessageKind.ERROR)
            OutlinedButton(vm::loadExitNodes, Modifier.focusRing()) { Text(stringResource(R.string.w_network_retry)) }
        }
        nodes.isNullOrEmpty() -> Message(res(R.string.w_network_empty), MessageKind.MUTED)
        else -> {
            if (nodes.none { it.online }) Message(res(R.string.w_network_allOffline), MessageKind.WARNING)
            val current = status?.exitNodeTarget.orEmpty()
            nodes.forEach { node ->
                val selected = node.id == current || current in node.ips
                RadioRow(
                    node.displayName + if (!node.online) " · " + stringResource(R.string.w_network_offline) else "",
                    selected = selected,
                    enabled = node.online && state.busy == null,
                    supporting = if (selected) stringResource(R.string.w_network_active) else node.dnsName.ifBlank { null },
                ) { if (!selected) vm.setExitNode(node.id) }
            }
            OutlinedButton(vm::loadExitNodes, Modifier.focusRing()) { Text(stringResource(R.string.w_network_refresh)) }
        }
    }
}

@Composable
private fun exitSummary(status: Status?, state: UiState): UiText {
    if (status == null) return res(R.string.w_network_waiting)
    if (!status.exitNodeConfigured) return res(R.string.w_network_summaryNone)
    val node = state.exitNodes?.firstOrNull { it.id == status.exitNodeTarget || status.exitNodeTarget in it.ips }
    val name = node?.displayName ?: status.exitNodeTarget
    return when {
        status.state != "Running" -> res(R.string.w_network_summaryPending, "name" to name)
        status.exitNodeOnline == false -> res(R.string.w_network_summaryOffline, "name" to name)
        else -> res(R.string.w_network_summaryActive, "name" to name)
    }
}

/** Same rules as the WebUI (module/webroot/app.js, ipv6Line). */
fun ipv6Line(status: Status, takeover: Boolean): Pair<UiText, MessageKind>? = when {
    !status.exitNodeConfigured -> null
    !takeover && status.exitNodeIPv6 == true -> res(R.string.w_network_ipv6Unsupported) to MessageKind.WARNING
    !takeover -> res(R.string.w_network_ipv6Physical) to MessageKind.INFO
    status.exitNodeIPv6 == false -> res(R.string.w_network_ipv6Bypass) to MessageKind.WARNING
    else -> null
}

/** Same precedence as the WebUI's MagicDNS status line (module/webroot/app.js). */
fun magicDnsLine(status: Status?, applying: Boolean): Pair<UiText, MessageKind>? {
    if (status == null || status.state != "Running") return null
    val dns = status.magicDns
    val mode = if (dns?.running == true) dns.mode else "off"
    val stale = dns != null && !dns.running && dns.mode != "off"
    val pending = applying || dns?.pending == true
    val applyFailed = !applying && dns?.applyFailed == true
    val on = status.magicDnsEnabled
    if (!(on || stale || pending || applyFailed)) {
        return if (status.exitNodeConfigured) res(R.string.w_network_dnsOffExit) to MessageKind.WARNING else null
    }
    val line = when {
        applyFailed -> res(R.string.w_network_dnsApplyFailed) to MessageKind.WARNING
        pending -> res(R.string.w_network_dnsPending) to MessageKind.MUTED
        stale -> res(R.string.w_network_dnsRulesFailed) to MessageKind.WARNING
        mode == "off" && dns?.noString == true -> res(R.string.w_network_dnsNoString) to MessageKind.WARNING
        mode == "off" -> res(R.string.w_network_dnsInactive) to MessageKind.INFO
        dns?.privateDns == "hostname" -> res(R.string.w_network_dnsPrivateHostname) to MessageKind.WARNING
        dns?.rules == false || dns?.rules6 == false -> res(R.string.w_network_dnsRulesFailed) to MessageKind.WARNING
        dns?.ipv6Fallback == true -> res(R.string.w_network_dnsIPv4Fallback) to MessageKind.WARNING
        status.exitNodeConfigured && !mode.startsWith("exit") -> res(R.string.w_network_dnsNotViaExit) to MessageKind.WARNING
        mode == "exit-passthrough" -> res(R.string.w_network_dnsExitPassthrough) to MessageKind.WARNING
        mode.startsWith("exit") -> res(R.string.w_network_dnsViaExit) to MessageKind.INFO
        dns == null || dns.suffix.isEmpty() -> res(R.string.w_network_dnsActiveNoSuffix) to MessageKind.INFO
        else -> res(R.string.w_network_dnsActive, "suffix" to dns.suffix) to MessageKind.INFO
    }
    if (line.second == MessageKind.INFO && mode != "off" && dns?.privateDns == "opportunistic") {
        return UiText.Joined(line.first, res(R.string.w_network_dnsPrivateAuto)) to MessageKind.INFO
    }
    return line
}

@Composable
private fun AdvertiseSection(state: UiState, vm: MainViewModel) {
    val status = state.status
    val savedRoutes = status?.advertiseRoutes.orEmpty().joinToString(",")
    var routes by rememberSaveable(savedRoutes) { mutableStateOf(savedRoutes) }
    var advertiseExit by rememberSaveable(status?.advertiseExitNode) { mutableStateOf(status?.advertiseExitNode == true) }
    val blocked = status?.let { it.exitNodeConfigured && !it.advertiseExitNode } == true
    OutlinedTextField(
        routes, { routes = it }, Modifier.fillMaxWidth().focusRing(), singleLine = true,
        label = { Text(stringResource(R.string.w_network_routes)) },
        placeholder = { Text(stringResource(R.string.w_network_routesPlaceholder)) },
        enabled = status != null,
    )
    SwitchRow(
        stringResource(R.string.w_network_exitAdvertisement),
        stringResource(if (blocked) R.string.w_network_exitAdvertisementBlocked else R.string.w_network_exitAdvertisementDescription),
        checked = advertiseExit, enabled = status != null && state.busy == null && state.settingsConfirmed && (!blocked || advertiseExit),
    ) { advertiseExit = it }
    Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
        Button(
            { vm.saveAdvertise(routes, advertiseExit) }, Modifier.focusRing(),
            enabled = status != null && state.busy == null && state.settingsConfirmed &&
                (routes != savedRoutes || advertiseExit != status.advertiseExitNode),
        ) { Text(stringResource(R.string.w_network_save)) }
    }
}

@Composable
private fun PreferencesCard(state: UiState, vm: MainViewModel) {
    val enabled = state.settingsConfirmed && state.busy == null
    val exitConfigured = state.status?.exitNodeConfigured == true
    SectionCard(stringResource(R.string.w_settings_heading)) {
        SwitchRow(
            stringResource(R.string.w_settings_acceptRoutes), stringResource(R.string.w_settings_acceptRoutesDescription),
            state.setting(Settings.ACCEPT_ROUTES), enabled, pending = Settings.ACCEPT_ROUTES in state.pendingSettings,
        ) { vm.setSetting(Settings.ACCEPT_ROUTES, it) }
        SwitchRow(
            stringResource(R.string.w_settings_shieldsUp), stringResource(R.string.w_settings_shieldsUpDescription),
            state.setting(Settings.SHIELDS_UP), enabled, pending = Settings.SHIELDS_UP in state.pendingSettings,
        ) { vm.setSetting(Settings.SHIELDS_UP, it) }
        SwitchRow(
            stringResource(R.string.w_settings_exitLan),
            stringResource(
                when {
                    state.status == null -> R.string.w_settings_exitLanWaiting
                    exitConfigured -> R.string.w_settings_exitLanDescription
                    else -> R.string.w_settings_exitLanUnavailable
                },
            ),
            state.setting(Settings.EXIT_LAN), enabled && exitConfigured, pending = Settings.EXIT_LAN in state.pendingSettings,
        ) { vm.setSetting(Settings.EXIT_LAN, it) }
        Message(
            when {
                state.settingsError != null -> state.settingsError
                state.status == null -> res(R.string.w_settings_unread)
                !state.settingsConfirmed -> res(R.string.w_settings_unconfirmed)
                else -> res(R.string.w_settings_automatic)
            },
            if (state.settingsError != null) MessageKind.ERROR else MessageKind.MUTED,
        )
    }
}

@Composable
private fun BinaryCard(state: UiState, vm: MainViewModel) {
    val channel = state.status?.updateChannel
    val binary = state.binary
    SectionCard(stringResource(R.string.w_binary_heading)) {
        Text(stringResource(R.string.w_binary_description), color = MaterialTheme.colorScheme.onSurfaceVariant)
        Text(stringResource(R.string.w_binary_channel), style = MaterialTheme.typography.labelLarge)
        listOf("stable" to R.string.w_binary_stable, "preview" to R.string.w_binary_preview).forEach { (value, label) ->
            RadioRow(stringResource(label), selected = channel == value, enabled = channel != null && state.busy == null) { vm.setUpdateChannel(value) }
        }
        Text(stringResource(R.string.w_binary_channelHint), style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
        val current = binary?.currentVersion?.ifBlank { null } ?: state.status?.version
        if (!current.isNullOrBlank()) Text(stringResource(R.string.w_binary_current) + ": " + current)
        if (binary?.checked == true && binary.latestVersion.isNotBlank()) Text(stringResource(R.string.w_binary_latest) + ": " + binary.latestVersion)
        val updating = state.busy == "binary-update"
        val canUpdate = state.binaryUpdateAvailable()
        Button(
            { if (canUpdate) vm.binaryUpdate() else vm.binaryCheck() }, Modifier.focusRing(),
            enabled = state.busy == null && state.status != null,
        ) {
            Text(
                stringResource(
                    when {
                        updating -> R.string.w_binary_updating
                        state.busy == "binary-check" -> R.string.w_binary_checking
                        canUpdate -> R.string.w_binary_update
                        else -> R.string.w_binary_check
                    },
                ),
            )
        }
        if (updating) Message(res(R.string.w_binary_busy), MessageKind.MUTED)
        Message(state.binaryMessage)
        Message(state.binaryError, MessageKind.ERROR)
    }
}
