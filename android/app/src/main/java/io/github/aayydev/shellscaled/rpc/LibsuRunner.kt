package io.github.aayydev.shellscaled.rpc

import com.topjohnwu.superuser.Shell
import java.util.concurrent.Executors
import kotlin.coroutines.resume
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeout

/**
 * One persistent root shell through libsu, which handles Magisk, KernelSU and APatch `su`
 * differences. Commands are composed only by [HelperClient] from fixed paths and base64.
 */
class LibsuRunner : RootRunner {
    // libsu shells are not reentrant; serialize every command on one thread.
    private val executor = Executors.newSingleThreadExecutor { Thread(it, "shellscaled-root") }

    override suspend fun hasRoot(): Boolean = withContext(Dispatchers.IO) {
        val root = runCatching { Shell.getShell().isRoot }.getOrDefault(false)
        // libsu caches the first shell. Drop a non-root one so a grant made later in the
        // root manager (KernelSU/APatch have no prompt) is picked up on the next check.
        if (!root) runCatching { Shell.getCachedShell()?.close() }
        root
    }

    override suspend fun run(command: String, timeoutMs: Long): RunResult = withTimeout(timeoutMs) {
        val shell = withContext(Dispatchers.IO) { Shell.getShell() }
        suspendCancellableCoroutine { continuation ->
            shell.newJob().add(command).to(ArrayList(), null).submit(executor) { result ->
                if (continuation.isActive) continuation.resume(RunResult(result.code, result.out.joinToString("\n")))
            }
        }
    }

    companion object {
        fun configure() {
            Shell.enableVerboseLogging = false
            Shell.setDefaultBuilder(Shell.Builder.create().setTimeout(20))
        }
    }
}
