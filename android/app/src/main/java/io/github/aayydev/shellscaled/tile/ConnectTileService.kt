package io.github.aayydev.shellscaled.tile

import android.app.PendingIntent
import android.content.Intent
import android.os.Build
import android.service.quicksettings.Tile
import android.service.quicksettings.TileService
import io.github.aayydev.shellscaled.MainActivity
import io.github.aayydev.shellscaled.R
import io.github.aayydev.shellscaled.ShellscaledApplication
import io.github.aayydev.shellscaled.rpc.Access
import io.github.aayydev.shellscaled.rpc.Status
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.launch

@androidx.annotation.RequiresApi(24)
/** Quick Settings connect/disconnect. Anything needing a decision (sign-in, root grant) opens the app. */
class ConnectTileService : TileService() {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)
    private val client get() = (application as ShellscaledApplication).client
    private var last: Status? = null
    private var working = false

    override fun onStartListening() {
        super.onStartListening()
        scope.launch { refresh() }
    }

    override fun onClick() {
        super.onClick()
        val status = last
        if (working) return
        if (status == null || status.state == "NeedsLogin" || status.state == "NeedsMachineAuth") {
            openApp()
            return
        }
        working = true
        render(status, busy = true)
        scope.launch {
            try {
                val reply = client.call(if (status.wantsRunning) "down" else "up")
                last = reply.status() ?: last
                if (last?.state == "NeedsLogin") openApp()
            } catch (_: Exception) {
                // Show the daemon's real state; the app explains failures.
            } finally {
                working = false
                refresh()
            }
        }
    }

    private suspend fun refresh() {
        val status = runCatching {
            if (client.access() != Access.READY) null else client.call("status").status()
        }.getOrNull()
        last = status
        render(status, busy = false)
    }

    private fun render(status: Status?, busy: Boolean) {
        val tile = qsTile ?: return
        tile.label = getString(R.string.tile_label)
        tile.state = when {
            status == null -> Tile.STATE_UNAVAILABLE
            status.wantsRunning || busy -> Tile.STATE_ACTIVE
            else -> Tile.STATE_INACTIVE
        }
        if (Build.VERSION.SDK_INT >= 29) {
            tile.subtitle = when {
                status == null -> getString(R.string.tile_unavailable)
                busy -> getString(if (status.wantsRunning) R.string.w_connection_disconnecting else R.string.w_connection_connecting)
                else -> getString(stateTitle(status.state))
            }
        }
        tile.updateTile()
    }

    private fun stateTitle(state: String) = when (state) {
        "Running" -> R.string.w_state_Running_title
        "Stopped" -> R.string.w_state_Stopped_title
        "NeedsLogin" -> R.string.w_state_NeedsLogin_title
        "NeedsMachineAuth" -> R.string.w_state_NeedsMachineAuth_title
        "Starting" -> R.string.w_state_Starting_title
        "ServiceError" -> R.string.w_state_ServiceError_title
        else -> R.string.w_state_ServiceStopped_title
    }

    private fun openApp() {
        val intent = Intent(this, MainActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        if (Build.VERSION.SDK_INT >= 34) {
            startActivityAndCollapse(PendingIntent.getActivity(this, 0, intent, PendingIntent.FLAG_IMMUTABLE))
        } else {
            startActivityAndCollapseLegacy(intent)
        }
    }

    // Only reached below Android 14, where the Intent overload is the supported API.
    @android.annotation.SuppressLint("StartActivityAndCollapseDeprecated")
    @Suppress("DEPRECATION")
    private fun startActivityAndCollapseLegacy(intent: Intent) {
        startActivityAndCollapse(intent)
    }

    override fun onDestroy() {
        scope.cancel()
        super.onDestroy()
    }
}
