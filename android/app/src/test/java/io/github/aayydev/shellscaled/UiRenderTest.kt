package io.github.aayydev.shellscaled

import androidx.compose.runtime.LaunchedEffect
import androidx.compose.ui.input.InputMode
import androidx.compose.ui.platform.LocalInputModeManager
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.assertIsFocused
import androidx.compose.ui.test.hasText
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import io.github.aayydev.shellscaled.rpc.HelperClient
import io.github.aayydev.shellscaled.ui.MainViewModel
import io.github.aayydev.shellscaled.ui.ShellscaledApp
import io.github.aayydev.shellscaled.ui.ShellscaledTheme
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode

/** Renders the real UI against the synthetic helper on several device classes. No device or account data. */
@RunWith(RobolectricTestRunner::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
class UiRenderTest {
    @get:Rule val compose = createComposeRule()

    private fun show(
        runner: FakeRunner = FakeRunner().apply {
            responses["status"] = { 0 to FakeRunner.status() }
            responses["devices"] = { 0 to FakeRunner.DEVICES }
        },
        dpad: Boolean = false,
    ) {
        val vm = MainViewModel(HelperClient(runner) { "system" })
        compose.setContent {
            // A TV remote puts Compose in keyboard input mode, where buttons are focusable.
            if (dpad) {
                val inputMode = LocalInputModeManager.current
                LaunchedEffect(Unit) { inputMode.requestInputMode(InputMode.Keyboard) }
            }
            ShellscaledTheme { ShellscaledApp(vm) }
        }
        compose.waitUntil(5_000) { vm.state.value.status != null }
    }

    @Test @Config(qualifiers = "w411dp-h891dp-port")
    fun phoneShowsStateAndBottomNavigation() {
        show()
        compose.onNodeWithText("Connected").assertIsDisplayed()
        compose.onNodeWithText("Disconnect").assertIsDisplayed()
        compose.onNodeWithText("100.64.0.1").assertIsDisplayed()
        compose.onNodeWithText("Devices").performClick()
        compose.waitUntil(5_000) { compose.onAllNodes(hasText("synthetic-laptop")).fetchSemanticsNodes().isNotEmpty() }
        compose.onNodeWithText("Devices: 2 · Online: 1").assertIsDisplayed()
    }

    @Test @Config(qualifiers = "w960dp-h540dp-land-television")
    fun televisionFocusesTheConnectionButton() {
        show(dpad = true)
        compose.waitForIdle()
        compose.onNodeWithText("Disconnect").assertIsFocused()
        compose.onNodeWithText("Settings").assertIsDisplayed()
    }

    @Test @Config(qualifiers = "w1280dp-h800dp-land")
    fun tabletUsesRail() {
        show()
        compose.onNodeWithText("Commands").performClick()
        compose.onNodeWithText("tailscale status").assertIsDisplayed()
    }

    @Test @Config(qualifiers = "zh-rTW-w411dp-h891dp-port")
    fun traditionalChinese() {
        show()
        compose.onNodeWithText("已連線").assertIsDisplayed()
    }

    @Test @Config(qualifiers = "w411dp-h891dp-port")
    fun signInShowsLinkAndQrOption() {
        val runner = FakeRunner().apply {
            responses["status"] = { 0 to FakeRunner.status(state = "NeedsLogin", loginUrl = "https://login.tailscale.com/a/synthetic") }
        }
        show(runner)
        compose.onNodeWithText("https://login.tailscale.com/a/synthetic").assertIsDisplayed()
        compose.onNodeWithText("Show QR code").performClick()
        compose.onNodeWithText("Scan to open").assertIsDisplayed()
    }

    @Test @Config(qualifiers = "w411dp-h891dp-port")
    fun rejectedCommandShowsStatusAndReasonSeparately() {
        // Seen on the play emulator: the two parts used to run together as "Not run Unsupported…".
        val runner = FakeRunner().apply {
            responses["status"] = { 0 to FakeRunner.status() }
            responses["command"] = { 1 to """{"ok":false,"error":"Unsupported command format."}""" }
        }
        show(runner)
        compose.onNodeWithText("Commands").performClick()
        compose.onNodeWithText("tailscale version").performClick()
        compose.onNodeWithText("Run command").performClick()
        compose.waitUntil(5_000) { compose.onAllNodes(hasText("Unsupported command format.")).fetchSemanticsNodes().isNotEmpty() }
        compose.onNodeWithText("Not run").assertExists()
        compose.onNodeWithText("Unsupported command format.").assertExists()
    }

    @Test @Config(qualifiers = "w411dp-h891dp-port")
    fun noRootExplainsHowToGrant() {
        val vm = MainViewModel(HelperClient(FakeRunner(root = false)) { "system" })
        compose.setContent { ShellscaledTheme { ShellscaledApp(vm) } }
        compose.waitUntil(5_000) { vm.state.value.access == io.github.aayydev.shellscaled.rpc.Access.NO_ROOT }
        compose.onNodeWithText("Root access needed").assertIsDisplayed()
        compose.onNodeWithText("Retry").assertIsDisplayed()
    }

    @Test @Config(sdk = [23], qualifiers = "zh-rTW-w411dp-h891dp-port")
    fun oldestSupportedAndroidRendersTraditionalChinese() {
        show()
        compose.onNodeWithText("已連線").assertIsDisplayed()
    }

    @Test @Config(sdk = [23], qualifiers = "w960dp-h540dp-land-television")
    fun oldestSupportedAndroidTv() {
        show(dpad = true)
        compose.waitForIdle()
        compose.onNodeWithText("Disconnect").assertIsFocused()
    }
}
