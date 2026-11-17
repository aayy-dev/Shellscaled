package io.github.aayydev.shellscaled.ui

import androidx.annotation.StringRes
import androidx.appcompat.app.AppCompatDelegate
import androidx.compose.runtime.Composable
import androidx.compose.ui.res.stringResource
import androidx.core.os.LocaleListCompat
import io.github.aayydev.shellscaled.R
import io.github.aayydev.shellscaled.rpc.ClientError
import io.github.aayydev.shellscaled.rpc.HelperException

/** Text for the user: a resource with {placeholders}, or a message already localized by the helper. */
sealed interface UiText {
    data class Res(@param:StringRes val id: Int, val args: Map<String, String> = emptyMap()) : UiText
    data class Raw(val text: String) : UiText
    /** A heading plus the helper's detail, shown as one message. */
    data class Joined(val first: UiText, val second: UiText) : UiText
}

fun res(@StringRes id: Int, vararg args: Pair<String, String>) = UiText.Res(id, args.toMap())

@Composable
fun UiText.resolve(): String = when (this) {
    is UiText.Raw -> text
    is UiText.Res -> fill(stringResource(id), args)
    is UiText.Joined -> first.resolve() + " " + second.resolve()
}

fun fill(template: String, args: Map<String, String>): String =
    args.entries.fold(template) { acc, (key, value) -> acc.replace("{$key}", value) }

fun Throwable.toUiText(): UiText {
    val e = this as? HelperException ?: return res(R.string.w_error_unknown)
    if (!e.helperMessage.isNullOrBlank()) return UiText.Raw(e.helperMessage)
    return when (e.clientKey) {
        ClientError.ENCODE -> res(R.string.w_error_encode)
        ClientError.TIMEOUT -> res(R.string.w_error_timeout)
        ClientError.RESPONSE -> res(R.string.w_error_response)
        ClientError.UNAVAILABLE -> res(R.string.w_error_unavailable)
        ClientError.UNEXPECTED_EXIT -> res(R.string.w_error_unexpectedExit)
        ClientError.NO_ROOT -> res(R.string.access_no_root_title)
        null -> res(R.string.w_error_operation)
    }
}

/** Per-app language: follows the system unless the user picks one (Android 13 per-app locales, AppCompat below). */
object Language {
    val options = listOf("system", "en", "zh-CN", "zh-TW")

    fun current(): String {
        val tags = AppCompatDelegate.getApplicationLocales().toLanguageTags()
        return when {
            tags.isEmpty() -> "system"
            tags.startsWith("zh-TW") || tags.startsWith("zh-HK") || tags.contains("Hant") -> "zh-TW"
            tags.startsWith("zh") -> "zh-CN"
            tags.startsWith("en") -> "en"
            else -> "system"
        }
    }

    fun set(value: String) {
        AppCompatDelegate.setApplicationLocales(
            if (value == "system") LocaleListCompat.getEmptyLocaleList() else LocaleListCompat.forLanguageTags(value),
        )
    }

    @StringRes
    fun label(value: String) = when (value) {
        "en" -> R.string.w_language_en
        "zh-CN" -> R.string.w_language_zhCN
        "zh-TW" -> R.string.w_language_zhTW
        else -> R.string.w_language_system
    }
}
