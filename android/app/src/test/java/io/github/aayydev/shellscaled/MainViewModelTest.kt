package io.github.aayydev.shellscaled

import io.github.aayydev.shellscaled.rpc.Access
import io.github.aayydev.shellscaled.rpc.HelperClient
import io.github.aayydev.shellscaled.rpc.Settings
import io.github.aayydev.shellscaled.ui.MainViewModel
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.test.setMain
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner

@OptIn(ExperimentalCoroutinesApi::class)
@RunWith(RobolectricTestRunner::class)
class MainViewModelTest {
    private val dispatcher = StandardTestDispatcher()
    private val runner = FakeRunner()
    private lateinit var vm: MainViewModel

    @Before fun setUp() {
        Dispatchers.setMain(dispatcher)
        runner.responses["status"] = { 0 to FakeRunner.status() }
        vm = MainViewModel(HelperClient(runner) { "en" })
    }

    @After fun tearDown() = Dispatchers.resetMain()

    private fun TestScope.ready() {
        vm.checkAccess()
        advanceUntilIdle()
        assertEquals(Access.READY, vm.state.value.access)
    }

    @Test fun connectionButtonSendsDownWhenRunningAndUpOtherwise() = runTest(dispatcher) {
        ready()
        runner.responses["down"] = { 0 to FakeRunner.status(state = "Stopped") }
        vm.toggleConnection()
        advanceUntilIdle()
        assertEquals("down", runner.requests.last()["action"]!!.jsonPrimitive.content)
        assertEquals("Stopped", vm.state.value.status!!.state)
        runner.responses["up"] = { 0 to FakeRunner.status(state = "Running") }
        vm.toggleConnection()
        advanceUntilIdle()
        assertEquals("up", runner.requests.last()["action"]!!.jsonPrimitive.content)
    }

    @Test fun settingChangesExactlyOneField() = runTest(dispatcher) {
        ready()
        runner.responses["settings"] = { 0 to FakeRunner.status(acceptRoutes = true) }
        vm.setSetting(Settings.ACCEPT_ROUTES, true)
        assertEquals(true, vm.state.value.pendingSettings[Settings.ACCEPT_ROUTES])
        advanceUntilIdle()
        val sent = runner.requests.last()["settings"]!!.jsonObject
        assertEquals(setOf(Settings.ACCEPT_ROUTES), sent.keys)
        assertTrue(vm.state.value.status!!.settings.acceptRoutes)
        assertTrue(vm.state.value.pendingSettings.isEmpty())
    }

    @Test fun failedSettingReconcilesAndLocksWhenStatusIsUnavailable() = runTest(dispatcher) {
        ready()
        runner.responses["settings"] = { 1 to """{"ok":false,"error":"x","settings":{},"daemonRunning":true,"exitNodeConfigured":false,"exitNodeIPv6Takeover":false,"magicDNSEnabled":false,"exitCode":0,"advertiseExitNode":false}""" }
        runner.responses["status"] = { 127 to "" }
        vm.setSetting(Settings.SHIELDS_UP, true)
        advanceUntilIdle()
        assertFalse(vm.state.value.settingsConfirmed)
        assertNotNull(vm.state.value.settingsError)
        // Further changes are refused until the daemon state is confirmed again.
        val before = runner.requests.size
        vm.setSetting(Settings.SHIELDS_UP, true)
        advanceUntilIdle()
        assertEquals(before, runner.requests.size)
    }

    @Test fun lanAccessNeedsAnExitNode() = runTest(dispatcher) {
        ready()
        val before = runner.requests.size
        vm.setSetting(Settings.EXIT_LAN, true)
        advanceUntilIdle()
        assertEquals(before, runner.requests.size)
    }

    @Test fun backgroundWipesDeviceData() = runTest(dispatcher) {
        ready()
        runner.responses["devices"] = { 0 to FakeRunner.DEVICES }
        vm.loadDevices()
        advanceUntilIdle()
        assertEquals(2, vm.state.value.devices!!.size)
        vm.onBackground()
        assertNull(vm.state.value.devices)
        assertNull(vm.state.value.commandResult)
    }

    @Test fun invalidRoutesAreRejectedBeforeAnyRequest() = runTest(dispatcher) {
        ready()
        val before = runner.requests.size
        vm.saveAdvertise("192.168.1.0/24, not-a-route", false)
        vm.saveAdvertise((1..17).joinToString(",") { "10.$it.0.0/16" }, false)
        advanceUntilIdle()
        assertEquals(before, runner.requests.size)
    }

    @Test fun onlyOneMutationAtATime() = runTest(dispatcher) {
        ready()
        runner.responses["down"] = { 0 to FakeRunner.status(state = "Stopped") }
        vm.toggleConnection()
        vm.toggleConnection()
        advanceUntilIdle()
        assertEquals(1, runner.requests.count { it["action"]!!.jsonPrimitive.content == "down" })
    }

    @Test fun commandPanelOnlySendsTailscaleCommands() = runTest(dispatcher) {
        ready()
        val before = runner.requests.size
        vm.runCommand("rm -rf /")
        advanceUntilIdle()
        assertEquals(before, runner.requests.size)
        assertNotNull(vm.state.value.commandError)
    }
}
