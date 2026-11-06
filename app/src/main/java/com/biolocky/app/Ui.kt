package com.biolocky.app

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.selection.selectable
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.platform.LocalClipboardManager
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.text.input.VisualTransformation
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp

/** Flat, solid, iOS-grouped look: no gradients, no glass. */
class Pal(val bg: Color, val card: Color, val line: Color, val text: Color, val muted: Color,
          val accent: Color, val danger: Color, val field: Color)

val LightPal = Pal(Color(0xFFF2F2F7), Color.White, Color(0xFFD1D1D6), Color.Black, Color(0xFF8E8E93),
    Color(0xFF007AFF), Color(0xFFFF3B30), Color(0xFFE9E9EE))
val DarkPal = Pal(Color.Black, Color(0xFF1C1C1E), Color(0xFF38383A), Color.White, Color(0xFF8E8E93),
    Color(0xFF0A84FF), Color(0xFFFF453A), Color(0xFF2C2C2E))
val LocalPal = staticCompositionLocalOf { LightPal }

@Composable
fun BlTheme(content: @Composable () -> Unit) {
    val dark = isSystemInDarkTheme()
    val p = if (dark) DarkPal else LightPal
    val base = if (dark) darkColorScheme() else lightColorScheme()
    val cs = base.copy(primary = p.accent, background = p.bg, surface = p.card, onSurface = p.text,
        onBackground = p.text, surfaceVariant = p.field, onSurfaceVariant = p.muted, outline = p.line)
    CompositionLocalProvider(LocalPal provides p) { MaterialTheme(colorScheme = cs, content = content) }
}

@Composable
fun T(s: String, size: Int = 16, w: FontWeight = FontWeight.Normal, color: Color = LocalPal.current.text,
      modifier: Modifier = Modifier, align: TextAlign? = null, max: Int = Int.MAX_VALUE, mono: Boolean = false) =
    Text(text = s, modifier = modifier, color = color, fontSize = size.sp, fontWeight = w, textAlign = align,
        maxLines = max, overflow = TextOverflow.Ellipsis, fontFamily = if (mono) FontFamily.Monospace else FontFamily.Default)

@Composable
fun Badge(text: String, color: Color) {
    Text(text = text, color = Color.White, fontSize = 10.sp, fontWeight = FontWeight.Bold,
        modifier = Modifier.clip(RoundedCornerShape(5.dp)).background(color).padding(horizontal = 6.dp, vertical = 2.dp))
}

@Composable
fun FingerprintIcon(color: Color, size: Dp = 34.dp) {
    Canvas(Modifier.size(size)) {
        val w = this.size.minDimension
        val sw = w * 0.07f
        val c = Offset(w / 2, w / 2 + w * 0.04f)
        for (r in listOf(0.13f, 0.25f, 0.37f, 0.48f)) {
            drawArc(color, -205f, 230f, false, Offset(c.x - w * r, c.y - w * r), Size(2 * w * r, 2 * w * r),
                style = Stroke(width = sw, cap = StrokeCap.Round))
        }
        drawCircle(color, sw * 0.8f, c)
    }
}

// ---------------------------------------------------------------- passcode pad
@Composable
fun PinPad(title: String, sub: String, error: String?, resetKey: Int, busy: Boolean, showBio: Boolean,
           onBio: () -> Unit, showFace: Boolean = false, onFace: () -> Unit = {}, onDone: (String) -> Unit) {
    val p = LocalPal.current
    var pin by remember(resetKey) { mutableStateOf("") }
    fun press(d: String) {
        if (busy || pin.length >= 6) return
        pin += d
        if (pin.length == 6) onDone(pin)
    }
    Column(Modifier.fillMaxSize().padding(horizontal = 24.dp), horizontalAlignment = Alignment.CenterHorizontally) {
        Spacer(Modifier.weight(1f))
        T(title, 24, FontWeight.SemiBold)
        Spacer(Modifier.height(6.dp))
        T(error ?: sub, 14, color = if (error != null) p.danger else p.muted, align = TextAlign.Center, modifier = Modifier.height(40.dp))
        Spacer(Modifier.height(10.dp))
        Row(horizontalArrangement = Arrangement.spacedBy(18.dp)) {
            repeat(6) { i ->
                Box(Modifier.size(14.dp).clip(CircleShape).background(if (i < pin.length) p.accent else p.line)) {
                    if (i >= pin.length) Box(Modifier.padding(1.5.dp).fillMaxSize().clip(CircleShape).background(p.bg))
                }
            }
        }
        Spacer(Modifier.height(36.dp))
        val rows = listOf(listOf("1", "2", "3"), listOf("4", "5", "6"), listOf("7", "8", "9"))
        rows.forEach { r ->
            Row(horizontalArrangement = Arrangement.spacedBy(22.dp)) { r.forEach { Key(it) { press(it) } } }
            Spacer(Modifier.height(14.dp))
        }
        Row(horizontalArrangement = Arrangement.spacedBy(22.dp)) {
            Box(Modifier.size(76.dp).clip(CircleShape).clickable(enabled = showBio) { onBio() }, contentAlignment = Alignment.Center) {
                if (showBio) FingerprintIcon(p.accent)
            }
            Key("0") { press("0") }
            Box(Modifier.size(76.dp).clip(CircleShape).clickable { if (!busy && pin.isNotEmpty()) pin = pin.dropLast(1) },
                contentAlignment = Alignment.Center) { T("⌫", 24, color = p.text) }
        }
        if (showFace) {
            Spacer(Modifier.height(10.dp))
            TextButton(onClick = onFace) {
                T("Use Face", 15, color = p.accent); Spacer(Modifier.width(8.dp)); Badge("EXPERIMENTAL", p.danger)
            }
        }
        Spacer(Modifier.weight(1f))
    }
}

@Composable
private fun Key(d: String, onClick: () -> Unit) {
    val p = LocalPal.current
    Box(Modifier.size(76.dp).clip(CircleShape).background(p.field).clickable(onClick = onClick), contentAlignment = Alignment.Center) {
        T(d, 30, FontWeight.Light)
    }
}

// ---------------------------------------------------------------- dialogs
@Composable
private fun FormDialog(vm: MainVM, title: String, message: String?, labels: List<String>, numeric: List<Boolean>,
                       confirm: String, onSubmit: (List<String>) -> Unit) {
    val p = LocalPal.current
    val vals = remember { mutableStateListOf<String>().apply { repeat(labels.size) { add("") } } }
    AlertDialog(
        onDismissRequest = { if (!vm.dialogBusy) vm.closeDialog() },
        containerColor = p.card, shape = RoundedCornerShape(18.dp),
        title = { T(title, 18, FontWeight.SemiBold) },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                message?.let { T(it, 14, color = p.muted) }
                labels.forEachIndexed { i, l ->
                    OutlinedTextField(
                        value = vals[i], singleLine = true, label = { Text(l) }, shape = RoundedCornerShape(10.dp),
                        onValueChange = { v -> vals[i] = if (numeric[i]) v.filter { c -> c.isDigit() }.take(6) else v.take(24) },
                        visualTransformation = if (numeric[i]) PasswordVisualTransformation() else VisualTransformation.None,
                        keyboardOptions = KeyboardOptions(keyboardType = if (numeric[i]) KeyboardType.NumberPassword else KeyboardType.Text))
                }
                vm.dialogError?.let { T(it, 13, color = p.danger) }
            }
        },
        confirmButton = { TextButton(onClick = { onSubmit(vals.toList()) }, enabled = !vm.dialogBusy) { T(confirm, 16, FontWeight.SemiBold, p.accent) } },
        dismissButton = { TextButton(onClick = { vm.closeDialog() }, enabled = !vm.dialogBusy) { T("Cancel", 16, color = p.accent) } })
}

@Composable
fun Dialogs(vm: MainVM) {
    val p = LocalPal.current
    when (val d = vm.dialog) {
        null -> {}
        is Dlg.Verify -> FormDialog(vm, d.title, d.message, listOf("Passcode"), listOf(true), "Continue") { v -> vm.verify(v[0], d.then) }
        Dlg.ChangePin -> FormDialog(vm, "Change Passcode", null, listOf("Current passcode", "New passcode", "Confirm new passcode"),
            listOf(true, true, true), "Update") { v -> vm.changePin(v[0], v[1], v[2]) }
        Dlg.ResetPin -> FormDialog(vm, "Reset Passcode", "Enter your recovery code and choose a new 6-digit passcode.",
            listOf("Recovery code", "New passcode", "Confirm new passcode"), listOf(false, true, true), "Reset") { v -> vm.resetPin(v[0], v[1], v[2]) }
        is Dlg.Recovery -> {
            val clip = LocalClipboardManager.current
            AlertDialog(onDismissRequest = {}, containerColor = p.card, shape = RoundedCornerShape(18.dp),
                title = { T("Save your recovery code", 18, FontWeight.SemiBold) },
                text = {
                    Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
                        T("This is the only way back in if you forget your passcode. It is shown once. Write it down and keep it somewhere safe.", 14, color = p.muted)
                        Box(Modifier.fillMaxWidth().clip(RoundedCornerShape(10.dp)).background(p.field).padding(14.dp), contentAlignment = Alignment.Center) {
                            T(d.code, 20, FontWeight.Bold, mono = true)
                        }
                    }
                },
                confirmButton = { TextButton(onClick = { vm.closeDialog() }) { T("I’ve saved it", 16, FontWeight.SemiBold, p.accent) } },
                dismissButton = { TextButton(onClick = { clip.setText(AnnotatedString(d.code)); vm.toast = "Copied" }) { T("Copy", 16, color = p.accent) } })
        }
        is Dlg.Confirm -> AlertDialog(onDismissRequest = { vm.closeDialog() }, containerColor = p.card, shape = RoundedCornerShape(18.dp),
            title = { T(d.title, 18, FontWeight.SemiBold) }, text = { T(d.message, 14, color = p.muted) },
            confirmButton = { TextButton(onClick = { vm.closeDialog(); d.then() }) { T(d.label, 16, FontWeight.SemiBold, if (d.danger) p.danger else p.accent) } },
            dismissButton = { TextButton(onClick = { vm.closeDialog() }) { T("Cancel", 16, color = p.accent) } })
        is Dlg.Info -> AlertDialog(onDismissRequest = { vm.closeDialog() }, containerColor = p.card, shape = RoundedCornerShape(18.dp),
            title = { T(d.title, 18, FontWeight.SemiBold) }, text = { T(d.message, 14, color = p.muted) },
            confirmButton = { TextButton(onClick = { vm.closeDialog() }) { T("OK", 16, FontWeight.SemiBold, p.accent) } })
        is Dlg.Choice -> AlertDialog(onDismissRequest = { vm.closeDialog() }, containerColor = p.card, shape = RoundedCornerShape(18.dp),
            title = { T(d.title, 18, FontWeight.SemiBold) },
            text = {
                Column {
                    d.options.forEach { (label, v) ->
                        Row(Modifier.fillMaxWidth().selectable(selected = v == d.current, onClick = { vm.closeDialog(); d.onPick(v) }).padding(vertical = 10.dp),
                            verticalAlignment = Alignment.CenterVertically) {
                            RadioButton(selected = v == d.current, onClick = null)
                            Spacer(Modifier.width(12.dp)); T(label, 16)
                        }
                    }
                }
            },
            confirmButton = {}, dismissButton = { TextButton(onClick = { vm.closeDialog() }) { T("Cancel", 16, color = p.accent) } })
    }
}
