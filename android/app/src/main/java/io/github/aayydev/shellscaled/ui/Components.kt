package io.github.aayydev.shellscaled.ui

import android.content.res.Configuration
import android.os.Build
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.selection.selectable
import androidx.compose.foundation.selection.toggleable
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.selection.SelectionContainer
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ElevatedCard
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.RadioButton
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.dynamicDarkColorScheme
import androidx.compose.material3.dynamicLightColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.composed
import androidx.compose.ui.focus.onFocusChanged
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Shape
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.unit.dp
import com.google.zxing.BarcodeFormat
import com.google.zxing.EncodeHintType
import com.google.zxing.qrcode.QRCodeWriter
import com.google.zxing.qrcode.decoder.ErrorCorrectionLevel
import io.github.aayydev.shellscaled.R

@Composable
fun ShellscaledTheme(content: @Composable () -> Unit) {
    val dark = isSystemInDarkTheme()
    val context = LocalContext.current
    val scheme = when {
        Build.VERSION.SDK_INT >= 31 -> if (dark) dynamicDarkColorScheme(context) else dynamicLightColorScheme(context)
        dark -> darkColorScheme(primary = Color(0xFF8FCBFF), secondary = Color(0xFF5EC2FF))
        else -> lightColorScheme(primary = Color(0xFF1F5FBF), secondary = Color(0xFF13233A))
    }
    MaterialTheme(colorScheme = scheme, content = content)
}

/** True on Android TV / Google TV / Fire TV, where navigation is by D-pad and there may be no browser. */
@Composable
fun isTelevision(): Boolean =
    (LocalConfiguration.current.uiMode and Configuration.UI_MODE_TYPE_MASK) == Configuration.UI_MODE_TYPE_TELEVISION

/** A clearly visible focus outline for D-pad, keyboard and remote navigation. */
fun Modifier.focusRing(shape: Shape = RoundedCornerShape(12.dp)): Modifier = composed {
    var focused by remember { mutableStateOf(false) }
    val color = MaterialTheme.colorScheme.primary
    onFocusChanged { focused = it.isFocused || it.hasFocus }
        .border(if (focused) 3.dp else 0.dp, if (focused) color else Color.Transparent, shape)
}

@Composable
fun SectionCard(title: String?, modifier: Modifier = Modifier, content: @Composable () -> Unit) {
    ElevatedCard(modifier.fillMaxWidth()) {
        Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
            if (title != null) Text(title, style = MaterialTheme.typography.titleMedium)
            content()
        }
    }
}

@Composable
fun SwitchRow(
    title: String,
    description: String?,
    checked: Boolean,
    enabled: Boolean,
    pending: Boolean = false,
    onChange: (Boolean) -> Unit,
) {
    Row(
        Modifier
            .fillMaxWidth()
            .focusRing()
            .toggleable(value = checked, enabled = enabled && !pending, role = Role.Switch, onValueChange = onChange)
            .padding(horizontal = 8.dp, vertical = 8.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Column(Modifier.weight(1f)) {
            Text(title, style = MaterialTheme.typography.bodyLarge)
            val detail = if (pending) stringResource(R.string.w_settings_applying) else description
            if (!detail.isNullOrBlank()) {
                Text(detail, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
        }
        Spacer(Modifier.width(12.dp))
        if (pending) CircularProgressIndicator(Modifier.size(24.dp), strokeWidth = 2.dp)
        else Switch(checked = checked, onCheckedChange = null, enabled = enabled)
    }
}

@Composable
fun RadioRow(label: String, selected: Boolean, enabled: Boolean = true, supporting: String? = null, onClick: () -> Unit) {
    Row(
        Modifier
            .fillMaxWidth()
            .focusRing()
            .selectable(selected = selected, enabled = enabled, role = Role.RadioButton, onClick = onClick)
            .padding(horizontal = 8.dp, vertical = 6.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        RadioButton(selected = selected, onClick = null, enabled = enabled)
        Spacer(Modifier.width(12.dp))
        Column {
            Text(label, style = MaterialTheme.typography.bodyLarge)
            if (supporting != null) Text(supporting, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
    }
}

enum class MessageKind { INFO, WARNING, ERROR, MUTED }

@Composable
fun Message(text: UiText?, kind: MessageKind = MessageKind.INFO) {
    text ?: return
    val color = when (kind) {
        MessageKind.ERROR -> MaterialTheme.colorScheme.error
        MessageKind.WARNING -> MaterialTheme.colorScheme.tertiary
        MessageKind.MUTED -> MaterialTheme.colorScheme.onSurfaceVariant
        MessageKind.INFO -> MaterialTheme.colorScheme.primary
    }
    Text(text.resolve(), style = MaterialTheme.typography.bodyMedium, color = color)
}

@Composable
fun Mono(text: String, modifier: Modifier = Modifier) {
    SelectionContainer { Text(text, modifier, fontFamily = FontFamily.Monospace, style = MaterialTheme.typography.bodyMedium) }
}

/** QR code drawn locally from text; nothing is sent anywhere. Always dark-on-white for scanners. */
@Composable
fun QrCode(text: String, modifier: Modifier = Modifier) {
    val matrix = remember(text) {
        runCatching {
            QRCodeWriter().encode(text, BarcodeFormat.QR_CODE, 0, 0, mapOf(EncodeHintType.MARGIN to 2, EncodeHintType.ERROR_CORRECTION to ErrorCorrectionLevel.M))
        }.getOrNull()
    } ?: return
    Canvas(modifier.background(Color.White)) {
        val cell = size.minDimension / matrix.width
        for (y in 0 until matrix.height) for (x in 0 until matrix.width) {
            if (matrix.get(x, y)) drawRect(Color.Black, Offset(x * cell, y * cell), Size(cell + 0.5f, cell + 0.5f))
        }
    }
}

@Composable
fun QrDialog(url: String, onDismiss: () -> Unit) {
    AlertDialog(
        onDismissRequest = onDismiss,
        confirmButton = { TextButton(onClick = onDismiss, modifier = Modifier.focusRing()) { Text(stringResource(R.string.close)) } },
        title = { Text(stringResource(R.string.qr_title)) },
        text = {
            Column(horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.spacedBy(12.dp)) {
                Text(stringResource(R.string.qr_body))
                QrCode(url, Modifier.size(240.dp))
                Mono(url)
            }
        },
    )
}
