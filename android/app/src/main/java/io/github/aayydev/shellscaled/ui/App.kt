package io.github.aayydev.shellscaled.ui

import android.content.Intent
import androidx.core.net.toUri
import androidx.annotation.StringRes
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.consumeWindowInsets
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.safeDrawingPadding
import androidx.compose.foundation.layout.widthIn
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Build
import androidx.compose.material.icons.filled.Home
import androidx.compose.material.icons.automirrored.filled.List
import androidx.compose.material.icons.filled.Settings
import androidx.compose.material.icons.filled.Share
import androidx.compose.material3.Button
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.NavigationBar
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.NavigationBarItem
import androidx.compose.material3.NavigationRail
import androidx.compose.material3.NavigationRailItem
import androidx.compose.material3.Scaffold
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalWindowInfo
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.LifecycleStartEffect
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import io.github.aayydev.shellscaled.R
import io.github.aayydev.shellscaled.rpc.Access

enum class Tab(@param:StringRes val label: Int, val icon: ImageVector) {
    OVERVIEW(R.string.w_tab_overview, Icons.Filled.Home),
    DEVICES(R.string.w_tab_devices, Icons.AutoMirrored.Filled.List),
    TAILDROP(R.string.w_tab_taildrop, Icons.Filled.Share),
    SETTINGS(R.string.w_tab_settings, Icons.Filled.Settings),
    COMMAND(R.string.w_tab_command, Icons.Filled.Build),
}

@Composable
fun ShellscaledApp(vm: MainViewModel) {
    val state by vm.state.collectAsStateWithLifecycle()
    LifecycleStartEffect(Unit) {
        vm.onForeground()
        onStopOrDispose { vm.onBackground() }
    }
    val tv = isTelevision()
    val context = LocalContext.current
    // Compact phones get a bottom bar; tablets, foldables, desktops and TV get a side rail.
    // Window (not screen) width, so split-screen, freeform and desktop windows lay out correctly.
    val windowWidth = with(LocalDensity.current) { LocalWindowInfo.current.containerSize.width.toDp() }
    val wide = tv || windowWidth >= 600.dp
    var tab by rememberSaveable { mutableStateOf(Tab.OVERVIEW) }
    val snackbar = remember { SnackbarHostState() }
    val notice = state.notice?.resolve()
    LaunchedEffect(notice) {
        if (notice != null) {
            snackbar.showSnackbar(notice)
            vm.dismissNotice()
        }
    }
    state.qrUrl?.let { QrDialog(it, vm::dismissQr) }

    Scaffold(
        snackbarHost = { SnackbarHost(snackbar) },
        bottomBar = {
            if (!wide && state.access == Access.READY) {
                NavigationBar {
                    Tab.entries.forEach { item ->
                        NavigationBarItem(
                            selected = tab == item, onClick = { tab = item },
                            icon = { Icon(item.icon, contentDescription = null) },
                            label = { Text(stringResource(item.label), maxLines = 1) },
                        )
                    }
                }
            }
        },
    ) { padding ->
        Row(
            Modifier
                .fillMaxSize()
                .padding(padding)
                .consumeWindowInsets(padding)
                // TV overscan-safe margins (Android TV design guidance: 48dp x 27dp).
                .then(if (tv) Modifier.padding(horizontal = 48.dp, vertical = 27.dp) else Modifier),
        ) {
            if (wide && state.access == Access.READY) {
                NavigationRail(Modifier.safeDrawingPadding()) {
                    Tab.entries.forEach { item ->
                        NavigationRailItem(
                            selected = tab == item, onClick = { tab = item },
                            icon = { Icon(item.icon, contentDescription = null) },
                            label = { Text(stringResource(item.label), maxLines = 1) },
                            modifier = Modifier.focusRing(),
                        )
                    }
                }
            }
            Box(Modifier.weight(1f).fillMaxSize(), contentAlignment = Alignment.TopCenter) {
                // Comfortable reading width on tablets, desktops and TVs.
                Box(Modifier.widthIn(max = 760.dp).fillMaxWidth()) {
                    when (state.access) {
                        Access.CHECKING -> Gate(R.string.access_checking, null, busy = true, onRetry = null)
                        Access.NO_ROOT -> Gate(R.string.access_no_root_title, R.string.access_no_root_body, busy = false) { vm.checkAccess() }
                        Access.MODULE_MISSING -> Gate(
                            R.string.access_module_title, R.string.access_module_body, busy = false,
                            // Root managers remove modules early in boot, when the app may not be removable yet.
                            onUninstall = { context.startActivity(Intent(Intent.ACTION_DELETE, "package:${context.packageName}".toUri())) },
                        ) { vm.checkAccess() }
                        Access.UNAVAILABLE -> Gate(R.string.w_state_ServiceError_title, R.string.w_error_unavailable, busy = false) { vm.checkAccess() }
                        Access.READY -> when (tab) {
                            Tab.OVERVIEW -> OverviewScreen(state, vm, tv)
                            Tab.DEVICES -> DevicesScreen(state, vm)
                            Tab.TAILDROP -> TaildropScreen(state, vm)
                            Tab.SETTINGS -> SettingsScreen(state, vm)
                            Tab.COMMAND -> CommandScreen(state, vm)
                        }
                    }
                }
            }
        }
    }
}

@Composable
private fun Gate(@StringRes title: Int, @StringRes body: Int?, busy: Boolean, onUninstall: (() -> Unit)? = null, onRetry: (() -> Unit)?) {
    Column(
        Modifier.fillMaxSize().padding(24.dp),
        verticalArrangement = Arrangement.spacedBy(16.dp, Alignment.CenterVertically),
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        if (busy) CircularProgressIndicator()
        Text(stringResource(title), style = MaterialTheme.typography.headlineSmall)
        if (body != null) Text(stringResource(body), style = MaterialTheme.typography.bodyLarge)
        if (onRetry != null) Button(onClick = onRetry, modifier = Modifier.focusRing()) { Text(stringResource(R.string.retry)) }
        if (onUninstall != null) OutlinedButton(onClick = onUninstall, modifier = Modifier.focusRing()) { Text(stringResource(R.string.access_uninstall_app)) }
    }
}

val ScreenPadding = PaddingValues(16.dp)
