package io.github.aayydev.shellscaled

import io.github.aayydev.shellscaled.rpc.Access
import io.github.aayydev.shellscaled.rpc.ClientError
import io.github.aayydev.shellscaled.rpc.HelperClient
import io.github.aayydev.shellscaled.rpc.HelperException
import io.github.aayydev.shellscaled.rpc.LoginUrl
import io.github.aayydev.shellscaled.rpc.ModulePaths
import io.github.aayydev.shellscaled.ui.formatLastSeen
import kotlinx.coroutines.test.runTest
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.jsonPrimitive
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Test

class HelperClientTest {
    private val runner = FakeRunner()
    private val client = HelperClient(runner) { "zh-TW" }

    @Test fun requestIsOneBase64ArgumentToTheFixedHelper() = runTest {
        runner.responses["settings"] = { 0 to FakeRunner.status(shieldsUp = true) }
        client.call("settings", mapOf("settings" to buildJsonObject { put("shieldsUp", JsonPrimitive(true)) }))
        val command = runner.commands.single()
        assertTrue(command.matches(Regex("^${Regex.escape(ModulePaths.HELPER)} rpc [A-Za-z0-9+/]+={0,2}$")))
        val request = runner.requests.single()
        assertEquals("settings", request["action"]!!.jsonPrimitive.content)
        assertEquals("zh-TW", request["locale"]!!.jsonPrimitive.content)
        assertEquals(setOf("action", "locale", "settings"), request.keys)
    }

    @Test fun shellMetacharactersNeverReachTheShell() = runTest {
        runner.responses["command"] = { 0 to """{"ok":true,"exitCode":0,"stdout":"x","settings":{},"daemonRunning":true,"exitNodeConfigured":false,"exitNodeIPv6Takeover":false,"magicDNSEnabled":false,"advertiseExitNode":false}""" }
        client.call("command", mapOf("command" to JsonPrimitive("tailscale status; rm -rf / \$(id) `id` | cat")))
        val argument = runner.commands.single().substringAfter(" rpc ")
        assertFalse(argument.any { it in ";|&$`'\" \n" })
        assertEquals("tailscale status; rm -rf / \$(id) `id` | cat", runner.requests.single()["command"]!!.jsonPrimitive.content)
    }

    @Test fun parsesStatus() = runTest {
        runner.responses["status"] = { 0 to FakeRunner.status(acceptRoutes = true, exitNode = "n1") }
        val status = client.call("status").status()!!
        assertEquals("Running", status.state)
        assertTrue(status.settings.acceptRoutes)
        assertTrue(status.exitNodeConfigured)
        assertEquals(true, status.exitNodeOnline)
        assertEquals(listOf("100.64.0.1", "fd7a:115c:a1e0::1"), status.ips)
    }

    @Test fun helperFailureCarriesItsLocalizedMessage() = runTest {
        runner.responses["up"] = { 1 to """{"ok":false,"error":"模組已停用","settings":{},"daemonRunning":false,"exitNodeConfigured":false,"exitNodeIPv6Takeover":false,"magicDNSEnabled":false,"exitCode":0,"advertiseExitNode":false}""" }
        val e = expect { client.call("up") }
        assertEquals("模組已停用", e.helperMessage)
    }

    @Test fun malformedOutputIsAResponseError() = runTest {
        runner.responses["status"] = { 0 to "not json" }
        assertEquals(ClientError.RESPONSE, expect { client.call("status") }.clientKey)
    }

    @Test fun okWithNonZeroExitIsRejected() = runTest {
        runner.responses["status"] = { 3 to FakeRunner.status() }
        assertEquals(ClientError.UNEXPECTED_EXIT, expect { client.call("status") }.clientKey)
    }

    @Test fun noOutputFromAFailedProcessMeansUnavailable() = runTest {
        runner.responses["status"] = { 127 to "" }
        assertEquals(ClientError.UNAVAILABLE, expect { client.call("status") }.clientKey)
    }

    @Test fun accessDistinguishesRootAndModule() = runTest {
        assertEquals(Access.READY, client.access())
        runner.moduleInstalled = false
        assertEquals(Access.MODULE_MISSING, client.access())
        runner.root = false
        assertEquals(Access.NO_ROOT, client.access())
        assertEquals(ClientError.NO_ROOT, expect { client.call("status") }.clientKey)
    }

    @Test fun oversizedRequestsAreRefusedLocally() = runTest {
        val e = expect { client.call("command", mapOf("command" to JsonPrimitive("tailscale " + "a".repeat(7000)))) }
        assertEquals(ClientError.ENCODE, e.clientKey)
        assertTrue(runner.commands.isEmpty())
    }

    @Test fun loginUrlRuleMatchesTheHelper() {
        assertTrue(LoginUrl.allowed("https://login.tailscale.com/a/abc123_-X"))
        assertTrue(LoginUrl.allowed("https://controlplane.tailscale.com/a/abc"))
        assertTrue(LoginUrl.allowed("https://login.tailscale.com:443/a/abc"))
        listOf(
            "http://login.tailscale.com/a/abc", "https://evil.example/a/abc", "https://login.tailscale.com/a/abc?x=1",
            "https://login.tailscale.com/a/abc#f", "https://user@login.tailscale.com/a/abc", "https://login.tailscale.com/b/abc",
            "https://login.tailscale.com:8443/a/abc", " https://login.tailscale.com/a/abc", "https://login.tailscale.com/a/../x",
        ).forEach { assertFalse(it, LoginUrl.allowed(it)) }
    }

    @Test fun statusDropsAnUnsafeLoginUrl() = runTest {
        runner.responses["status"] = { 0 to FakeRunner.status(state = "NeedsLogin", loginUrl = "https://evil.example/a/abc") }
        assertNull(client.call("status").status()!!.loginUrl)
        runner.responses["status"] = { 0 to FakeRunner.status(state = "NeedsLogin", loginUrl = "https://login.tailscale.com/a/abc") }
        assertNotNull(client.call("status").status()!!.loginUrl)
    }

    @Test fun lastSeenParsesGoTimestamps() {
        assertNotNull(formatLastSeen("2026-11-16T10:00:00.123456789Z"))
        assertNotNull(formatLastSeen("2026-11-16T10:00:00+08:00"))
        assertNull(formatLastSeen("0001-01-01T00:00:00Z"))
        assertNull(formatLastSeen("garbage"))
    }

    private suspend fun expect(block: suspend () -> Unit): HelperException {
        try {
            block()
        } catch (e: HelperException) {
            return e
        }
        fail("expected HelperException")
        throw AssertionError()
    }
}
