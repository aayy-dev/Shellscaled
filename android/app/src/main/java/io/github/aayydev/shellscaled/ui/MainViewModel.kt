package io.github.aayydev.shellscaled.ui

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import io.github.aayydev.shellscaled.R
import io.github.aayydev.shellscaled.rpc.Access
import io.github.aayydev.shellscaled.rpc.BinaryStatus
import io.github.aayydev.shellscaled.rpc.CommandResult
import io.github.aayydev.shellscaled.rpc.Device
import io.github.aayydev.shellscaled.rpc.HelperClient
import io.github.aayydev.shellscaled.rpc.Reply
import io.github.aayydev.shellscaled.rpc.Settings
import io.github.aayydev.shellscaled.rpc.Status
import io.github.aayydev.shellscaled.rpc.TaildropDirectory
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject

/** Fixed destinations shown as QR codes when the device has no browser (Android TV). */
object FixedUrls {
    // Must match the helper's own fixed destinations (helper/main.go consoleCommand/aboutCommand).
    const val CONSOLE = "https://console.tailscale.com/admin/machines"
    const val ABOUT = "https://github.com/KyuubiRan/Shellscaled"
}

data class UiState(
    val access: Access = Access.CHECKING,
    val status: Status? = null,
    val statusError: UiText? = null,
    val updatedAt: Long? = null,
    val needsVerify: Boolean = false,
    /** The single mutating operation in flight, named after its RPC action. */
    val busy: String? = null,
    val notice: UiText? = null,
    val loginMessage: UiText? = null,
    val devices: List<Device>? = null,
    val devicesLoading: Boolean = false,
    val devicesError: UiText? = null,
    val exitNodes: List<Device>? = null,
    val exitNodesLoading: Boolean = false,
    val exitNodesError: UiText? = null,
    /** Optimistic switch values while a single-field change is in flight. */
    val pendingSettings: Map<String, Boolean> = emptyMap(),
    val settingsConfirmed: Boolean = false,
    val settingsError: UiText? = null,
    val networkMessage: UiText? = null,
    val networkError: UiText? = null,
    val binary: BinaryStatus? = null,
    val binaryMessage: UiText? = null,
    val binaryError: UiText? = null,
    val taildropDirs: List<TaildropDirectory>? = null,
    val taildropBrowse: String? = null,
    val taildropTruncated: Boolean = false,
    val taildropMessage: UiText? = null,
    val taildropError: UiText? = null,
    val commandResult: CommandResult? = null,
    val commandError: UiText? = null,
    /** Optimistic IPv6 exit-node preference while its change is in flight. */
    val exitIpv6Pending: Boolean? = null,
    /** A fixed or validated URL to show as a QR code because no browser could open it. */
    val qrUrl: String? = null,
) {
    fun setting(field: String): Boolean = pendingSettings[field] ?: status?.settings?.get(field) ?: false

    /** WebUI binaryUpdateAvailable: a checked, supported update for the selected channel. */
    fun binaryUpdateAvailable(): Boolean {
        val b = binary ?: return false
        return b.checked && b.supported && b.updateAvailable && (b.channel.isEmpty() || b.channel == status?.updateChannel)
    }
}

class MainViewModel(private val client: HelperClient, private val clock: () -> Long = System::currentTimeMillis) : ViewModel() {
    private val _state = MutableStateFlow(UiState())
    val state: StateFlow<UiState> = _state.asStateFlow()
    private var polling: Job? = null
    private var foreground = false

    /** Called when the UI becomes visible. Polls status every 15 s, like the WebUI. */
    fun onForeground() {
        foreground = true
        polling?.cancel()
        polling = viewModelScope.launch {
            checkAccess()
            while (isActive && foreground) {
                if (_state.value.access == Access.READY && _state.value.busy == null) refreshStatus()
                delay(POLL_MS)
            }
        }
    }

    /** Leaving the app wipes device data and command output, as the WebUI does when hidden. */
    fun onBackground() {
        foreground = false
        polling?.cancel()
        _state.update {
            it.copy(devices = null, exitNodes = null, commandResult = null, taildropDirs = null, taildropBrowse = null, qrUrl = null, notice = null)
        }
    }

    fun checkAccess() = viewModelScope.launch {
        val previous = _state.value.access
        // Keep the UI while re-checking an already working connection (e.g. returning to the app).
        if (previous != Access.READY) _state.update { it.copy(access = Access.CHECKING) }
        val checked = try {
            client.access()
        } catch (e: kotlinx.coroutines.CancellationException) {
            throw e
        } catch (_: Exception) {
            null
        }
        // An inconclusive check never claims the module is missing.
        val access = checked ?: if (previous == Access.READY) Access.READY else Access.UNAVAILABLE
        _state.update { it.copy(access = access) }
        if (access == Access.READY) refreshStatus()
    }

    fun refresh(announce: Boolean = false) = viewModelScope.launch {
        refreshStatus()
        if (announce && _state.value.statusError == null) notice(res(R.string.w_notice_updated))
    }

    private suspend fun refreshStatus(): Boolean = try {
        applyStatus(client.call("status"))
        true
    } catch (e: Throwable) {
        if (e is kotlinx.coroutines.CancellationException) throw e
        _state.update { it.copy(statusError = e.toUiText(), settingsConfirmed = false) }
        false
    }

    private fun applyStatus(reply: Reply) {
        val status = reply.status() ?: return
        _state.update {
            // A different update channel invalidates any earlier check result (WebUI syncUpdateChannel).
            val channelChanged = it.status?.updateChannel != null && it.status.updateChannel != status.updateChannel
            it.copy(
                status = status, statusError = null, updatedAt = clock(), needsVerify = false,
                settingsConfirmed = status.daemonRunning, loginMessage = if (status.state == "NeedsLogin") it.loginMessage else null,
                binary = if (channelChanged) null else it.binary,
                binaryMessage = if (channelChanged) null else it.binaryMessage,
            )
        }
    }

    fun dismissNotice() = _state.update { it.copy(notice = null) }
    fun dismissQr() = _state.update { it.copy(qrUrl = null) }
    private fun notice(text: UiText) = _state.update { it.copy(notice = text) }

    /** Runs one mutating action; refuses to start while another is in flight. */
    private fun op(name: String, block: suspend () -> Unit) {
        if (_state.value.busy != null || _state.value.access != Access.READY) return
        _state.update { it.copy(busy = name) }
        viewModelScope.launch {
            try {
                block()
            } finally {
                _state.update { it.copy(busy = null) }
            }
        }
    }

    // ---- Overview -------------------------------------------------------------------------

    fun toggleConnection() {
        val status = _state.value.status ?: return
        val action = if (status.wantsRunning) "down" else "up"
        op(action) {
            try {
                applyStatus(client.call(action))
            } catch (e: Throwable) {
                if (e is kotlinx.coroutines.CancellationException) throw e
                notice(e.toUiText())
                _state.update { it.copy(needsVerify = true) }
                refreshStatus()
            }
        }
    }

    fun login() = op("login") {
        _state.update { it.copy(loginMessage = res(R.string.w_login_opening)) }
        try {
            applyStatus(client.call("login"))
            _state.update { it.copy(loginMessage = res(R.string.w_login_opened)) }
        } catch (e: Throwable) {
            if (e is kotlinx.coroutines.CancellationException) throw e
            // No browser (common on TV). The helper may have just created the link, so re-read
            // status before offering it as text and QR.
            refreshStatus()
            _state.update { it.copy(loginMessage = e.toUiText(), qrUrl = it.status?.loginUrl) }
        }
    }

    fun showLoginQr() = _state.update { it.copy(qrUrl = it.status?.loginUrl) }

    fun openConsole() = openFixed("console", FixedUrls.CONSOLE)
    fun openAbout() = openFixed("about", FixedUrls.ABOUT)

    private fun openFixed(action: String, fallback: String) = op(action) {
        try {
            client.call(action)
        } catch (e: Throwable) {
            if (e is kotlinx.coroutines.CancellationException) throw e
            _state.update { it.copy(notice = e.toUiText(), qrUrl = fallback) }
        }
    }

    // ---- Devices --------------------------------------------------------------------------

    fun loadDevices() {
        if (_state.value.devicesLoading || _state.value.access != Access.READY) return
        _state.update { it.copy(devicesLoading = true, devicesError = null) }
        viewModelScope.launch {
            try {
                val devices = client.call("devices").devices().orEmpty().take(MAX_DEVICES)
                _state.update { it.copy(devices = devices, devicesLoading = false) }
            } catch (e: Throwable) {
                if (e is kotlinx.coroutines.CancellationException) throw e
                _state.update { it.copy(devicesError = e.toUiText(), devicesLoading = false) }
            }
        }
    }

    fun loadExitNodes() {
        if (_state.value.exitNodesLoading || _state.value.access != Access.READY) return
        _state.update { it.copy(exitNodesLoading = true, exitNodesError = null) }
        viewModelScope.launch {
            try {
                val nodes = client.call("exit-nodes").devices().orEmpty().filter { it.exitNodeOption && !it.self }
                _state.update { it.copy(exitNodes = nodes, exitNodesLoading = false) }
            } catch (e: Throwable) {
                if (e is kotlinx.coroutines.CancellationException) throw e
                _state.update { it.copy(exitNodesError = e.toUiText(), exitNodesLoading = false) }
            }
        }
    }

    // ---- Settings -------------------------------------------------------------------------

    /** One masked single-field change. Shows the new value while in flight, then reconciles. */
    fun setSetting(field: String, value: Boolean) {
        val s = _state.value
        if (!s.settingsConfirmed || s.busy != null) return
        if (field == Settings.EXIT_LAN && s.status?.exitNodeConfigured != true) return
        _state.update { it.copy(pendingSettings = it.pendingSettings + (field to value), settingsError = null) }
        op("settings") {
            try {
                val reply = client.call("settings", mapOf("settings" to buildJsonObject { put(field, JsonPrimitive(value)) }))
                applyStatus(reply)
                if (reply.status() == null) refreshStatus()
            } catch (e: Throwable) {
                if (e is kotlinx.coroutines.CancellationException) throw e
                _state.update { it.copy(settingsError = res(R.string.w_settings_failed)) }
                // Reconcile with the daemon before allowing further changes.
                if (!refreshStatus()) _state.update { it.copy(settingsConfirmed = false) }
            } finally {
                _state.update { it.copy(pendingSettings = it.pendingSettings - field) }
            }
        }
    }

    fun setExitNode(target: String) = networkOp("exit-node", res(R.string.w_network_saved)) {
        client.call("exit-node", mapOf("exitNode" to JsonPrimitive(target)))
    }

    fun setExitNodeIPv6(enabled: Boolean) = networkOp("exit-node-ipv6", null, onStart = { _state.update { it.copy(exitIpv6Pending = enabled) } }) {
        client.call("exit-node-ipv6", mapOf("exitNodeIPv6" to JsonPrimitive(enabled)))
    }

    fun setMagicDns(enabled: Boolean) = networkOp("magicdns", null) {
        client.call("magicdns", mapOf("magicDNSEnabled" to JsonPrimitive(enabled)))
    }

    fun saveAdvertise(routes: String, exitNode: Boolean) {
        val list = routes.split(',').map(String::trim).filter(String::isNotEmpty)
        when {
            list.size > MAX_ROUTES -> _state.update { it.copy(networkError = res(R.string.w_network_routesTooMany)) }
            // Same limits as the helper: no default routes here (that is the exit-node switch), 1024 chars.
            routes.length > MAX_ROUTES_TEXT || list.any { !CIDR.matches(it) || it.endsWith("/0") } ->
                _state.update { it.copy(networkError = res(R.string.w_network_routesInvalid)) }
            exitNode && _state.value.status?.let { it.exitNodeConfigured && !it.advertiseExitNode } == true ->
                _state.update { it.copy(networkError = res(R.string.w_network_exitAdvertisementBlocked)) }
            else -> networkOp("advertise", res(R.string.w_network_advertiseSaved)) {
                client.call("advertise", mapOf("advertiseRoutes" to JsonPrimitive(list.joinToString(",")), "advertiseExitNode" to JsonPrimitive(exitNode)))
            }
        }
    }

    private fun networkOp(action: String, success: UiText?, onStart: () -> Unit = {}, call: suspend () -> Reply) {
        // Like the WebUI, network roles change only while the daemon's settings are confirmed.
        if (!_state.value.settingsConfirmed || _state.value.busy != null || _state.value.access != Access.READY) return
        onStart()
        _state.update { it.copy(networkError = null, networkMessage = null) }
        op(action) {
            try {
                val reply = call()
                if (reply.status() != null) applyStatus(reply) else refreshStatus()
                _state.update { it.copy(networkMessage = success) }
            } catch (e: Throwable) {
                if (e is kotlinx.coroutines.CancellationException) throw e
                _state.update { it.copy(networkError = e.toUiText()) }
                // The preference may be saved even if applying failed; re-read it.
                refreshStatus()
            } finally {
                _state.update { it.copy(exitIpv6Pending = null) }
            }
        }
    }

    // ---- Binary updates -------------------------------------------------------------------

    fun binaryCheck() = binaryOp("binary-check", res(R.string.w_binary_currentStatus))

    fun binaryUpdate() {
        if (!_state.value.binaryUpdateAvailable()) return
        binaryOp("binary-update", res(R.string.w_binary_completed))
    }

    private fun binaryOp(action: String, done: UiText) = op(action) {
        _state.update { it.copy(binaryError = null, binaryMessage = null) }
        try {
            val timeout = if (action == "binary-update") HelperClient.UPDATE_TIMEOUT else HelperClient.DEFAULT_TIMEOUT
            val binary = client.call(action, timeoutMs = timeout).binary()
            val message = when {
                binary == null -> res(R.string.w_error_response)
                action == "binary-update" -> done
                !binary.supported -> res(R.string.w_binary_unsupported)
                binary.updateAvailable -> res(R.string.w_binary_available)
                else -> done
            }
            _state.update { it.copy(binary = binary, binaryMessage = message) }
            if (action == "binary-update") refreshStatus()
        } catch (e: Throwable) {
            if (e is kotlinx.coroutines.CancellationException) throw e
            val prefix = if (action == "binary-update") R.string.w_binary_updateFailed else R.string.w_binary_checkFailed
            _state.update { it.copy(binaryError = joinTexts(res(prefix), e.toUiText())) }
        }
    }

    fun setUpdateChannel(channel: String) {
        if (channel != "stable" && channel != "preview" || channel == _state.value.status?.updateChannel) return
        op("binary-channel") {
            _state.update { it.copy(binary = null, binaryError = null, binaryMessage = res(R.string.w_binary_channelSaving)) }
            try {
                val reply = client.call("binary-channel", mapOf("channel" to JsonPrimitive(channel)))
                if (reply.updateChannel != channel) throw IllegalStateException()
                _state.update { it.copy(binaryMessage = res(R.string.w_binary_channelSaved)) }
                refreshStatus()
            } catch (e: Throwable) {
                if (e is kotlinx.coroutines.CancellationException) throw e
                _state.update { it.copy(binaryMessage = null, binaryError = res(R.string.w_binary_channelFailed)) }
            }
        }
    }

    // ---- Taildrop -------------------------------------------------------------------------

    fun taildropBrowse(path: String = "") = op("taildrop-list") {
        _state.update { it.copy(taildropError = null) }
        try {
            val reply = client.call("taildrop-list", if (path.isEmpty()) emptyMap() else mapOf("path" to JsonPrimitive(path)))
            _state.update {
                it.copy(
                    taildropDirs = reply.taildropDirectories().take(MAX_DIRS),
                    // The helper returns the normalized browse location as taildropPath.
                    taildropBrowse = reply.taildropPath ?: path.ifEmpty { it.status?.taildropPath },
                    taildropTruncated = reply.taildropDirectoriesTruncated,
                )
            }
        } catch (e: Throwable) {
            if (e is kotlinx.coroutines.CancellationException) throw e
            _state.update { it.copy(taildropError = e.toUiText()) }
        }
    }

    fun taildropChoose(path: String) = op("taildrop-path") {
        _state.update { it.copy(taildropError = null, taildropMessage = null) }
        try {
            val reply = client.call("taildrop-path", mapOf("path" to JsonPrimitive(path)))
            _state.update { s ->
                s.copy(
                    taildropMessage = res(R.string.w_taildrop_saved), taildropDirs = null, taildropBrowse = null,
                    status = s.status?.copy(taildropPath = reply.taildropPath ?: path),
                )
            }
        } catch (e: Throwable) {
            if (e is kotlinx.coroutines.CancellationException) throw e
            _state.update { it.copy(taildropError = e.toUiText()) }
        }
    }

    fun taildropReceive() = op("taildrop-receive") {
        _state.update { it.copy(taildropError = null, taildropMessage = res(R.string.w_taildrop_receiving)) }
        try {
            client.call("taildrop-receive")
            _state.update { it.copy(taildropMessage = res(R.string.w_taildrop_received)) }
        } catch (e: Throwable) {
            if (e is kotlinx.coroutines.CancellationException) throw e
            _state.update { it.copy(taildropMessage = null, taildropError = e.toUiText()) }
        }
    }

    // ---- Command panel --------------------------------------------------------------------

    fun runCommand(text: String) {
        val command = text.trim()
        if (!command.startsWith("tailscale")) {
            _state.update { it.copy(commandError = res(R.string.w_error_command)) }
            return
        }
        op("command") {
            _state.update { it.copy(commandError = null, commandResult = null) }
            try {
                // A failing command still returns its output and exit code; show them.
                val reply = client.call("command", mapOf("command" to JsonPrimitive(command)), acceptFailure = true)
                val hasOutput = reply.raw.containsKey("stdout") || reply.raw.containsKey("stderr") || reply.ok
                _state.update {
                    it.copy(
                        commandResult = if (hasOutput) reply.command() else null,
                        commandError = reply.error?.takeIf(String::isNotBlank)?.let(UiText::Raw),
                    )
                }
            } catch (e: Throwable) {
                if (e is kotlinx.coroutines.CancellationException) throw e
                _state.update { it.copy(commandError = e.toUiText()) }
            }
            // Commands can change preferences or the connection; re-read status like the WebUI.
            refreshStatus()
        }
    }

    fun clearCommand() = _state.update { it.copy(commandResult = null, commandError = null) }

    companion object {
        const val POLL_MS = 15_000L
        const val MAX_DEVICES = 500
        const val MAX_DIRS = 128
        const val MAX_ROUTES = 16
        const val MAX_ROUTES_TEXT = 1024
        private val CIDR = Regex("^[0-9A-Fa-f:.]+/[0-9]{1,3}$")

        fun joinTexts(a: UiText, b: UiText): UiText = UiText.Joined(a, b)
    }
}
