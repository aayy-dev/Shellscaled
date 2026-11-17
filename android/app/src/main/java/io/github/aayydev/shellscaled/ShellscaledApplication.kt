package io.github.aayydev.shellscaled

import android.app.Application
import io.github.aayydev.shellscaled.rpc.HelperClient
import io.github.aayydev.shellscaled.rpc.LibsuRunner
import io.github.aayydev.shellscaled.ui.Language

class ShellscaledApplication : Application() {
    /** Shared by the activity and the Quick Settings tile so both use the same root shell. */
    val client: HelperClient by lazy { HelperClient(LibsuRunner(), Language::current) }

    override fun onCreate() {
        super.onCreate()
        LibsuRunner.configure()
    }
}
