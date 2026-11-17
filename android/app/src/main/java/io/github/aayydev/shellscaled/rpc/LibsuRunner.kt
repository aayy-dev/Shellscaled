package io.github.aayydev.shellscaled.rpc

import com.topjohnwu.superuser.Shell
import java.util.concurrent.Executors
import kotlin.coroutines.resume
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.TimeoutCancellationException
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeout

/**
 * Root shells through libsu, which handles Magisk, KernelSU and APatch `su` differences.
 * Commands are composed only by [HelperClient] from fixed paths and base64.
 */
class LibsuRunner : RootRunner {
    // libsu delivers results on this executor; shells themselves serialize their own jobs.
    private val executor = Executors.newCachedThreadPool { Thread(it, "shellscaled-root") }

    override suspend fun hasRoot(): Boolean = withContext(Dispatchers.IO) {
        val root = runCatching { Shell.getShell().isRoot }.getOrDefault(false)
        // libsu caches the first shell. Drop a non-root one so a grant made later in the
        // root manager (KernelSU/APatch have no prompt) is picked up on the next check.
        if (!root) runCatching { Shell.getCachedShell()?.close() }
        root
    }

    override suspend fun run(command: String, timeoutMs: Long, isolated: Boolean): RunResult =
        if (isolated) runIsolated(command, timeoutMs) else runShared(command, timeoutMs)

    private suspend fun runShared(command: String, timeoutMs: Long): RunResult {
        val shell = withContext(Dispatchers.IO) { Shell.getShell() }
        return try {
            withTimeout(timeoutMs) { submit(shell, command) }
        } catch (e: TimeoutCancellationException) {
            // A hung quick call would queue every later call behind it; start a fresh shell.
            withContext(Dispatchers.IO) { runCatching { shell.close() } }
            throw e
        }
    }

    private suspend fun runIsolated(command: String, timeoutMs: Long): RunResult {
        val shell = withContext(Dispatchers.IO) { Shell.Builder.create().setTimeout(20).build() }
        try {
            if (!shell.isRoot) return RunResult(126, "")
            return withTimeout(timeoutMs) { submit(shell, command) }
        } finally {
            withContext(Dispatchers.IO) { runCatching { shell.close() } }
        }
    }

    private suspend fun submit(shell: Shell, command: String): RunResult = suspendCancellableCoroutine { continuation ->
        shell.newJob().add(command).to(ArrayList(), null).submit(executor) { result ->
            if (continuation.isActive) continuation.resume(RunResult(result.code, result.out.joinToString("\n")))
        }
    }

    companion object {
        fun configure() {
            Shell.enableVerboseLogging = false
            Shell.setDefaultBuilder(Shell.Builder.create().setTimeout(20))
        }
    }
}
