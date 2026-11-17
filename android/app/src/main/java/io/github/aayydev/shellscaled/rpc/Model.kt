package io.github.aayydev.shellscaled.rpc

import java.net.URI
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.booleanOrNull
import kotlinx.serialization.json.intOrNull

internal fun JsonObject.str(key: String): String? = (this[key] as? JsonPrimitive)?.takeIf { it.isString }?.content
internal fun JsonObject.bool(key: String): Boolean? = (this[key] as? JsonPrimitive)?.booleanOrNull
internal fun JsonObject.int(key: String): Int? = (this[key] as? JsonPrimitive)?.intOrNull
internal fun JsonObject.obj(key: String): JsonObject? = this[key] as? JsonObject
internal fun JsonObject.array(key: String): JsonArray? = this[key] as? JsonArray
internal fun JsonObject.strings(key: String): List<String> = array(key).orEmpty().mapNotNull { (it as? JsonPrimitive)?.takeIf { p -> p.isString }?.content }

data class Settings(val acceptRoutes: Boolean, val shieldsUp: Boolean, val exitNodeAllowLanAccess: Boolean) {
    fun with(field: String, value: Boolean) = when (field) {
        ACCEPT_ROUTES -> copy(acceptRoutes = value)
        SHIELDS_UP -> copy(shieldsUp = value)
        EXIT_LAN -> copy(exitNodeAllowLanAccess = value)
        else -> this
    }

    fun get(field: String) = when (field) {
        ACCEPT_ROUTES -> acceptRoutes
        SHIELDS_UP -> shieldsUp
        else -> exitNodeAllowLanAccess
    }

    companion object {
        const val ACCEPT_ROUTES = "acceptRoutes"
        const val SHIELDS_UP = "shieldsUp"
        const val EXIT_LAN = "exitNodeAllowLanAccess"
        fun from(o: JsonObject?) = Settings(o?.bool(ACCEPT_ROUTES) == true, o?.bool(SHIELDS_UP) == true, o?.bool(EXIT_LAN) == true)
    }
}

data class MagicDns(
    val running: Boolean, val mode: String, val enabled: Boolean, val noString: Boolean,
    val rules: Boolean, val rules6: Boolean, val ipv6Fallback: Boolean, val privateDns: String,
    val pending: Boolean, val applyFailed: Boolean, val suffix: String,
) {
    companion object {
        fun from(o: JsonObject) = MagicDns(
            running = o.bool("running") == true, mode = o.str("mode") ?: "off", enabled = o.bool("enabled") == true,
            noString = o.bool("noString") == true, rules = o.bool("rules") != false, rules6 = o.bool("rules6") != false,
            ipv6Fallback = o.bool("ipv6Fallback") == true, privateDns = o.str("privateDns").orEmpty(),
            pending = o.bool("pending") == true, applyFailed = o.bool("applyFailed") == true,
            suffix = o.strings("suffixes").firstOrNull().orEmpty(),
        )
    }
}

data class Status(
    val state: String,
    val version: String,
    val ips: List<String>,
    val settings: Settings,
    val daemonRunning: Boolean,
    val exitNodeConfigured: Boolean,
    val exitNodeOnline: Boolean?,
    val exitNodeIPv6: Boolean?,
    val exitNodeIPv6Takeover: Boolean,
    val magicDnsEnabled: Boolean,
    val loginUrl: String?,
    val exitNodeTarget: String,
    val advertiseExitNode: Boolean,
    val advertiseRoutes: List<String>,
    val updateChannel: String?,
    val magicDns: MagicDns?,
    val taildropPath: String,
    val error: String?,
) {
    /** Connected or connecting: the single connection button disconnects. */
    val wantsRunning get() = state == "Running" || state == "Starting"

    companion object {
        fun from(o: JsonObject) = Status(
            state = o.str("state") ?: "NoState",
            version = o.str("version").orEmpty(),
            ips = o.strings("ips").take(4),
            settings = Settings.from(o.obj("settings")),
            daemonRunning = o.bool("daemonRunning") == true,
            exitNodeConfigured = o.bool("exitNodeConfigured") == true,
            exitNodeOnline = o.bool("exitNodeOnline"),
            exitNodeIPv6 = o.bool("exitNodeIPv6"),
            exitNodeIPv6Takeover = o.bool("exitNodeIPv6Takeover") == true,
            magicDnsEnabled = o.bool("magicDNSEnabled") == true,
            loginUrl = o.str("loginURL")?.takeIf(LoginUrl::allowed),
            exitNodeTarget = o.str("exitNodeTarget").orEmpty(),
            advertiseExitNode = o.bool("advertiseExitNode") == true,
            advertiseRoutes = o.strings("advertiseRoutes"),
            updateChannel = o.str("updateChannel"),
            magicDns = o.obj("magicDNS")?.let(MagicDns::from),
            taildropPath = o.str("taildropPath").orEmpty(),
            error = o.str("error"),
        )
    }
}

data class Device(
    val id: String, val name: String, val dnsName: String, val ips: List<String>, val online: Boolean,
    val os: String, val self: Boolean, val lastSeen: String?, val exitNode: Boolean, val exitNodeOption: Boolean,
) {
    val displayName get() = name.ifBlank { dnsName.substringBefore('.') }

    companion object {
        fun from(o: JsonObject) = Device(
            id = o.str("id").orEmpty(), name = o.str("name").orEmpty(), dnsName = o.str("dnsName").orEmpty().removeSuffix("."),
            ips = o.strings("ips"), online = o.bool("online") == true, os = o.str("os").orEmpty(), self = o.bool("self") == true,
            lastSeen = o.str("lastSeen"), exitNode = o.bool("exitNode") == true, exitNodeOption = o.bool("exitNodeOption") == true,
        )
    }
}

data class BinaryStatus(
    val currentVersion: String, val latestVersion: String, val updateAvailable: Boolean,
    val checked: Boolean, val supported: Boolean, val channel: String,
) {
    companion object {
        fun from(o: JsonObject) = BinaryStatus(
            currentVersion = o.str("currentVersion").orEmpty(), latestVersion = o.str("latestVersion").orEmpty(),
            updateAvailable = o.bool("updateAvailable") == true, checked = o.bool("checked") == true,
            supported = o.bool("supported") == true, channel = o.str("channel").orEmpty(),
        )
    }
}

data class CommandResult(val exitCode: Int, val stdout: String, val stderr: String, val truncated: Boolean)

data class TaildropDirectory(val name: String, val path: String)

/**
 * Client-side mirror of the helper's interactive-login URL rule (helper/main.go allowedLoginURL).
 * The helper still validates again before opening anything; the app uses this only to decide
 * whether the link may be shown as text or as a QR code.
 */
object LoginUrl {
    private val tokenPath = Regex("^/a/[A-Za-z0-9_-]{1,512}$")

    fun allowed(value: String): Boolean {
        if (value.isEmpty() || value.length > 2048 || value.trim() != value || '#' in value) return false
        val uri = runCatching { URI(value) }.getOrNull() ?: return false
        if (uri.scheme != "https" || uri.rawUserInfo != null || uri.rawQuery != null || uri.rawFragment != null || uri.isOpaque) return false
        if (uri.port != -1 && uri.port != 443) return false
        val path = uri.rawPath ?: return false
        if (!tokenPath.matches(path)) return false
        return when (uri.host?.lowercase()) {
            "login.tailscale.com", "controlplane.tailscale.com" -> true
            else -> false
        }
    }
}
