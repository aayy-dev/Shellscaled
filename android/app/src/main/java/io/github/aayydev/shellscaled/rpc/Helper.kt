package io.github.aayydev.shellscaled.rpc

import kotlin.io.encoding.Base64
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.jsonObject

/** Fixed module paths. The app never runs anything else as root. */
object ModulePaths {
    const val MODULE = "/data/adb/modules/shellscaled"
    const val HELPER = "$MODULE/bin/shellscaled-web"
}

data class RunResult(val code: Int, val stdout: String)

/** Executes one fixed command line in a root shell. */
interface RootRunner {
    /** False when the app has no root shell (not granted, or no root manager). */
    suspend fun hasRoot(): Boolean

    /**
     * Runs [command]. Quick calls share one persistent shell; [isolated] calls get their own
     * short-lived shell so a long operation (an update, a command) never blocks status polling.
     */
    suspend fun run(command: String, timeoutMs: Long, isolated: Boolean = false): RunResult
}

/** A failure with a message for the user: either the helper's localized text, or a client key. */
class HelperException(val helperMessage: String?, val clientKey: ClientError? = null) : Exception(helperMessage ?: clientKey?.name)

enum class ClientError { ENCODE, TIMEOUT, RESPONSE, UNAVAILABLE, UNEXPECTED_EXIT, NO_ROOT }

enum class Access { CHECKING, NO_ROOT, MODULE_MISSING, UNAVAILABLE, READY }

/**
 * Same RPC protocol the KernelSU WebUI uses: one JSON request, base64 encoded, passed as a
 * single argument to the module helper, which validates every field against its own allowlist.
 */
class HelperClient(private val runner: RootRunner, private val locale: () -> String) {
    private val json = Json { ignoreUnknownKeys = true }

    /** Null when the check itself could not finish (for example, the root shell timed out). */
    suspend fun access(): Access? {
        if (!runner.hasRoot()) return Access.NO_ROOT
        val probe = try {
            runner.run("test -x ${ModulePaths.HELPER}", 15_000, isolated = true)
        } catch (e: kotlinx.coroutines.CancellationException) {
            if (e is kotlinx.coroutines.TimeoutCancellationException) return null
            throw e
        } catch (_: Exception) {
            return null
        }
        return if (probe.code == 0) Access.READY else Access.MODULE_MISSING
    }

    /**
     * Sends one request. With [acceptFailure], an `ok:false` reply is returned instead of thrown,
     * for actions such as `command` whose failures still carry output for the user.
     */
    suspend fun call(
        action: String,
        fields: Map<String, JsonElement> = emptyMap(),
        timeoutMs: Long = DEFAULT_TIMEOUT,
        acceptFailure: Boolean = false,
    ): Reply {
        val request = buildJsonObject {
            put("action", JsonPrimitive(action))
            put("locale", JsonPrimitive(locale()))
            fields.forEach { (key, value) -> put(key, value) }
        }
        val encoded = Base64.encode(request.toString().encodeToByteArray())
        if (encoded.length > MAX_REQUEST || !BASE64.matches(encoded)) throw HelperException(null, ClientError.ENCODE)
        if (!runner.hasRoot()) throw HelperException(null, ClientError.NO_ROOT)
        val result = try {
            runner.run("${ModulePaths.HELPER} rpc $encoded", timeoutMs, isolated = action in ISOLATED)
        } catch (_: kotlinx.coroutines.TimeoutCancellationException) {
            throw HelperException(null, ClientError.TIMEOUT)
        }
        if (result.stdout.length > MAX_RESPONSE) throw HelperException(null, ClientError.RESPONSE)
        val text = result.stdout.trim()
        if (text.isEmpty()) throw HelperException(null, if (result.code == 0) ClientError.RESPONSE else ClientError.UNAVAILABLE)
        val obj = runCatching { json.parseToJsonElement(text).jsonObject }.getOrNull()
            ?: throw HelperException(null, ClientError.RESPONSE)
        val reply = Reply(obj)
        if (!reply.ok && acceptFailure) return reply
        if (!reply.ok) throw HelperException(reply.error ?: "", if (reply.error.isNullOrBlank()) ClientError.UNEXPECTED_EXIT else null)
        if (result.code != 0) throw HelperException(null, ClientError.UNEXPECTED_EXIT)
        return reply
    }

    companion object {
        const val DEFAULT_TIMEOUT = 60_000L
        const val UPDATE_TIMEOUT = 200_000L
        private const val MAX_REQUEST = 8192
        private const val MAX_RESPONSE = 1_500_000
        private val BASE64 = Regex("^[A-Za-z0-9+/]+={0,2}$")

        /** Slow or user-initiated actions that must not hold the shared shell. */
        val ISOLATED = setOf("up", "down", "login", "console", "about", "binary-check", "binary-update", "command", "taildrop-receive")
    }
}

/** Typed, lenient view over one helper response. Unknown or missing fields fall back to defaults. */
class Reply(val raw: JsonObject) {
    val ok get() = raw.bool("ok") == true
    val error get() = raw.str("error")

    fun status(): Status? = raw.str("state")?.let { Status.from(raw) }
    fun devices(): List<Device>? = raw.array("devices")?.mapNotNull { (it as? JsonObject)?.let(Device::from) }
    fun binary(): BinaryStatus? = raw.obj("binary")?.let(BinaryStatus::from)
    fun command() = CommandResult(
        exitCode = raw.int("exitCode") ?: 0,
        stdout = raw.str("stdout").orEmpty(),
        stderr = raw.str("stderr").orEmpty(),
        truncated = raw.bool("truncated") == true,
    )
    fun taildropDirectories(): List<TaildropDirectory> = raw.array("taildropDirectories").orEmpty().mapNotNull {
        val o = it as? JsonObject ?: return@mapNotNull null
        val name = o.str("name") ?: return@mapNotNull null
        val path = o.str("path") ?: return@mapNotNull null
        TaildropDirectory(name, path)
    }
    val taildropDirectoriesTruncated get() = raw.bool("taildropDirectoriesTruncated") == true
    val taildropPath get() = raw.str("taildropPath")
    val updateChannel get() = raw.str("updateChannel")
}
