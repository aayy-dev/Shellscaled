package io.github.aayydev.shellscaled

import android.os.Bundle
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.appcompat.app.AppCompatActivity
import androidx.lifecycle.viewmodel.compose.viewModel
import io.github.aayydev.shellscaled.ui.Language
import io.github.aayydev.shellscaled.ui.MainViewModel
import io.github.aayydev.shellscaled.ui.ShellscaledApp
import io.github.aayydev.shellscaled.ui.ShellscaledTheme

/** AppCompatActivity so per-app language also works below Android 13. */
class MainActivity : AppCompatActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        enableEdgeToEdge()
        super.onCreate(savedInstanceState)
        Language.syncFromSystem()
        val client = (application as ShellscaledApplication).client
        setContent {
            val vm: MainViewModel = viewModel { MainViewModel(client) }
            ShellscaledTheme { ShellscaledApp(vm) }
        }
    }
}
