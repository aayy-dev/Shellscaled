package io.github.aayydev.shellscaled

import io.github.aayydev.shellscaled.rpc.ModulePaths
import io.github.aayydev.shellscaled.rpc.RootRunner
import io.github.aayydev.shellscaled.rpc.RunResult
import kotlin.io.encoding.Base64
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive

/** Synthetic helper: records requests and answers from a table. Never touches a device or account. */
class FakeRunner(var root: Boolean = true, var moduleInstalled: Boolean = true) : RootRunner {
    val requests = mutableListOf<JsonObject>()
    val commands = mutableListOf<String>()
    /** Action name -> whether it ran in an isolated shell. */
    val isolation = mutableMapOf<String, Boolean>()
    var probeFails = false
    var responses: MutableMap<String, (JsonObject) -> Pair<Int, String>> = mutableMapOf()

    override suspend fun hasRoot() = root

    override suspend fun run(command: String, timeoutMs: Long, isolated: Boolean): RunResult {
        commands += command
        if (command == "test -x ${ModulePaths.HELPER}") {
            if (probeFails) throw IllegalStateException("shell unavailable")
            return RunResult(if (moduleInstalled) 0 else 1, "")
        }
        val prefix = "${ModulePaths.HELPER} rpc "
        require(command.startsWith(prefix)) { "unexpected command $command" }
        val request = Json.parseToJsonElement(Base64.decode(command.removePrefix(prefix)).decodeToString()).jsonObject
        requests += request
        val action = request["action"]!!.jsonPrimitive.content
        isolation[action] = isolated
        val (code, out) = responses[action]?.invoke(request) ?: (1 to """{"ok":false,"error":"unsupported $action","settings":{},"daemonRunning":false,"exitNodeConfigured":false,"exitNodeIPv6Takeover":false,"magicDNSEnabled":false,"exitCode":0,"advertiseExitNode":false}""")
        return RunResult(code, out)
    }

    companion object {
        fun status(
            state: String = "Running",
            acceptRoutes: Boolean = false,
            shieldsUp: Boolean = false,
            exitNode: String = "",
            loginUrl: String? = null,
            ipv6Takeover: Boolean = false,
            ipv6Routed: Boolean? = null,
            channel: String = "stable",
        ): String {
            val routed = ipv6Routed?.let { ""","exitNodeIPv6":$it""" } ?: ""
            val login = loginUrl?.let { ""","loginURL":"$it"""" } ?: ""
            val exit = if (exitNode.isNotEmpty()) ""","exitNodeTarget":"$exitNode","exitNodeOnline":true""" else ""
            return """{"ok":true,"state":"$state","version":"1.104.6","ips":["100.64.0.1","fd7a:115c:a1e0::1"],"settings":{"acceptRoutes":$acceptRoutes,"shieldsUp":$shieldsUp,"exitNodeAllowLanAccess":false},"daemonRunning":true,"exitNodeConfigured":${exitNode.isNotEmpty()},"exitNodeIPv6Takeover":$ipv6Takeover,"magicDNSEnabled":false,"exitCode":0,"advertiseExitNode":false,"updateChannel":"$channel","taildropPath":"/storage/emulated/0/Download/Taildrop"$login$exit$routed}"""
        }

        const val DEVICES = """{"ok":true,"settings":{},"daemonRunning":true,"exitNodeConfigured":false,"exitNodeIPv6Takeover":false,"magicDNSEnabled":false,"exitCode":0,"advertiseExitNode":false,
            "devices":[{"id":"n1","name":"synthetic-laptop","dnsName":"synthetic-laptop.example.ts.net.","ips":["100.64.0.2"],"online":true,"os":"linux","self":false,"exitNodeOption":true},
                       {"id":"n2","name":"synthetic-tv","dnsName":"synthetic-tv.example.ts.net.","ips":["100.64.0.3"],"online":false,"os":"android","self":false,"lastSeen":"2026-11-16T10:00:00.123456789Z"}]}"""
    }
}
