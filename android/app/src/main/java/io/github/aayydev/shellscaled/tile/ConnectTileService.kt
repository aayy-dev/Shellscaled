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
import kotlinx.coroutines.CancellationException
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
        if (working) return
        // Changing the VPN-like connection from the lock screen requires unlocking first.
        if (isLocked) unlockAndRun { toggle() } else toggle()
    }

    private fun toggle() {
        working = true
        render(last, busy = true)
        scope.launch {
            try {
                // Act on the daemon's current state, not on what was read when the panel opened.
                val status = currentStatus()
                if (status == null || status.state == "NeedsLogin" || status.state == "NeedsMachineAuth") {
                    openApp()
                    return@launch
                }
                val reply = client.call(if (status.wantsRunning) "down" else "up")
                last = reply.status() ?: last
                if (last?.state == "NeedsLogin") openApp()
            } catch (e: CancellationException) {
                throw e
            } catch (_: Exception) {
                // Show the daemon's real state below; the app explains failures.
            } finally {
                working = false
                refresh()
            }
        }
    }

    private suspend fun currentStatus(): Status? = try {
        if (client.access() != Access.READY) null else client.call("status").status()
    } catch (e: CancellationException) {
        throw e
    } catch (_: Exception) {
        null
    }

    private suspend fun refresh() {
        val status = currentStatus()
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
