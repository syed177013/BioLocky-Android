package com.biolocky.app

import androidx.activity.compose.BackHandler
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.shadow
import android.content.Intent
import android.graphics.Bitmap
import android.net.Uri
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.FilterQuality
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import android.graphics.BitmapFactory
import kotlinx.coroutines.delay

class Actions(val pick: () -> Unit, val exportSingle: () -> Unit, val exportTree: () -> Unit,
              val bioUnlock: () -> Unit, val bioEnable: () -> Unit, val reveal: () -> Unit,
              val faceStart: (FaceMode) -> Unit, val faceSupported: Boolean, val bioAvailable: () -> Boolean)

@Composable
fun AppRoot(vm: MainVM, act: Actions) {
    val p = LocalPal.current
    Box(Modifier.fillMaxSize().background(p.bg)) {
        if (vm.locked) LockScreen(vm, act) else Shell(vm, act)
        Dialogs(vm)
        vm.faceMode?.let { FaceScreen(vm, it) }
        vm.toast?.let { t ->
            LaunchedEffect(t) { delay(2600); vm.toast = null }
            Box(Modifier.align(Alignment.BottomCenter).navigationBarsPadding().padding(bottom = 84.dp, start = 24.dp, end = 24.dp)
                .clip(RoundedCornerShape(12.dp)).background(if (p.bg == Color.Black) Color(0xFFEDEDED) else Color(0xFF2B2B2D))
                .padding(horizontal = 16.dp, vertical = 10.dp)) {
                T(t, 14, color = if (p.bg == Color.Black) Color.Black else Color.White, align = TextAlign.Center)
            }
        }
    }
}

// ---------------------------------------------------------------- lock / setup
@Composable
fun LockScreen(vm: MainVM, act: Actions) {
    val p = LocalPal.current
    val creating = !vm.exists
    var stage by remember { mutableIntStateOf(0) }
    var first by remember { mutableStateOf("") }
    var localErr by remember { mutableStateOf<String?>(null) }
    var reset by remember { mutableIntStateOf(0) }
    val bio = !creating && vm.bioOn && act.bioAvailable()
    val face = !creating && act.faceSupported && vm.faceReady
    LaunchedEffect(vm.fgTick, vm.locked) {
        if (bio) { delay(350); act.bioUnlock() }
        else if (face) { delay(350); act.faceStart(FaceMode.Unlock) }   // fingerprint keeps priority when enabled
    }
    Box(Modifier.fillMaxSize().systemBarsPadding()) {
        PinPad(
            title = if (creating) (if (stage == 0) "Create a Passcode" else "Confirm Passcode") else "Enter Passcode",
            sub = if (creating) (if (stage == 0) "Choose 6 digits to protect your vault" else "Enter it once more") else "BioLocky",
            error = localErr ?: vm.pinError, resetKey = reset + vm.pinReset, busy = vm.pinBusy,
            showBio = bio, onBio = act.bioUnlock, showFace = face, onFace = { act.faceStart(FaceMode.Unlock) }) { pin ->
            localErr = null
            if (!creating) vm.unlockPin(pin)
            else if (stage == 0) { first = pin; stage = 1; reset++ }
            else if (pin == first) vm.createVault(pin)
            else { localErr = "Passcodes didn’t match. Start again."; stage = 0; first = ""; reset++ }
        }
        if (!creating) TextButton(onClick = { vm.dialog = Dlg.ResetPin }, modifier = Modifier.align(Alignment.BottomCenter).padding(bottom = 12.dp)) {
            T("Forgot passcode?", 15, color = p.accent)
        }
    }
}

// ---------------------------------------------------------------- shell + tab bar
@Composable
fun Shell(vm: MainVM, act: Actions) {
    val p = LocalPal.current
    Column(Modifier.fillMaxSize().systemBarsPadding()) {
        Box(Modifier.weight(1f)) { if (vm.tab == 0) FilesScreen(vm, act) else SettingsScreen(vm, act) }
        Box(Modifier.fillMaxWidth().height(0.5.dp).background(p.line))
        Row(Modifier.fillMaxWidth().background(p.card).padding(vertical = 6.dp)) {
            NavTab("Files", Icons.Default.List, vm.tab == 0) { vm.tab = 0 }
            NavTab("Settings", Icons.Default.Settings, vm.tab == 1) { vm.tab = 1; vm.selection = emptySet() }
        }
    }
}

@Composable
private fun RowScope.NavTab(label: String, icon: androidx.compose.ui.graphics.vector.ImageVector, on: Boolean, click: () -> Unit) {
    val p = LocalPal.current
    val c = if (on) p.accent else p.muted
    Column(Modifier.weight(1f).clickable(onClick = click).padding(vertical = 4.dp), horizontalAlignment = Alignment.CenterHorizontally) {
        Icon(icon, label, tint = c, modifier = Modifier.size(24.dp))
        T(label, 11, FontWeight.Medium, c)
    }
}

// ---------------------------------------------------------------- files
private val SORTS = listOf("Name" to "name", "Date added" to "added", "Date modified" to "modified", "Type" to "type", "Size" to "size")

private fun fmt(n: Long): String {
    var v = n.toDouble(); val u = listOf("B", "KB", "MB", "GB"); var i = 0
    while (v >= 1024 && i < 3) { v /= 1024; i++ }
    return if (i == 0) "$n B" else "%.1f %s".format(v, u[i])
}

@OptIn(ExperimentalFoundationApi::class)
@Composable
fun FilesScreen(vm: MainVM, act: Actions) {
    val p = LocalPal.current
    val ctx = LocalContext.current
    val shown = remember(vm.files, vm.query, vm.sortKey, vm.sortDesc) {
        sortList(vm.files.filter { vm.query.isBlank() || it.name.contains(vm.query, true) }, vm.sortKey, vm.sortDesc)
    }
    val selecting = vm.selection.isNotEmpty()
    BackHandler(selecting) { vm.selection = emptySet() }
    // Second authentication: asked once per unlock, as soon as there is something to protect.
    LaunchedEffect(vm.files.isNotEmpty()) {
        if (!vm.revealed && !vm.revealAsked && vm.files.isNotEmpty()) { vm.revealAsked = true; delay(450); act.reveal() }
    }
    Column(Modifier.fillMaxSize()) {
        Row(Modifier.fillMaxWidth().padding(start = 20.dp, end = 12.dp, top = 14.dp, bottom = 4.dp), verticalAlignment = Alignment.CenterVertically) {
            if (selecting) {
                T("${vm.selection.size} Selected", 26, FontWeight.Bold, modifier = Modifier.weight(1f))
                TextButton({ vm.selection = shown.map { it.id }.toSet() }) { T("Select All", 16, color = p.accent) }
                TextButton({ vm.selection = emptySet() }) { T("Done", 16, FontWeight.SemiBold, p.accent) }
            } else {
                T("Files", 34, FontWeight.Bold, modifier = Modifier.weight(1f))
            }
        }
        if (!selecting) {
            Row(Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 6.dp), verticalAlignment = Alignment.CenterVertically) {
                Row(Modifier.weight(1f).height(38.dp).clip(RoundedCornerShape(10.dp)).background(p.field).padding(horizontal = 10.dp),
                    verticalAlignment = Alignment.CenterVertically) {
                    Icon(Icons.Default.Search, null, tint = p.muted, modifier = Modifier.size(18.dp))
                    Spacer(Modifier.width(8.dp))
                    Box(Modifier.weight(1f)) {
                        if (vm.query.isEmpty()) T("Search", 16, color = p.muted)
                        BasicTextField(vm.query, { vm.query = it }, singleLine = true, cursorBrush = SolidColor(p.accent),
                            textStyle = TextStyle(color = p.text, fontSize = 16.sp), modifier = Modifier.fillMaxWidth())
                    }
                    if (vm.query.isNotEmpty()) Icon(Icons.Default.Clear, "Clear", tint = p.muted,
                        modifier = Modifier.size(18.dp).clickable { vm.query = "" })
                }
                var open by remember { mutableStateOf(false) }
                Box {
                    TextButton({ open = true }) {
                        T(SORTS.first { it.second == vm.sortKey }.first + if (vm.sortDesc) " ↓" else " ↑", 15, color = p.accent)
                    }
                    DropdownMenu(open, { open = false }, containerColor = p.card) {
                        SORTS.forEach { (label, key) ->
                            DropdownMenuItem(text = { T(label + if (key == vm.sortKey) "  ✓" else "", 16) }, onClick = { vm.setSort(key); open = false })
                        }
                        HorizontalDivider(color = p.line)
                        DropdownMenuItem(text = { T(if (vm.sortDesc) "Ascending" else "Descending", 16, color = p.accent) },
                            onClick = { vm.toggleDir(); open = false })
                    }
                }
            }
        }
        if (!vm.revealed && vm.files.isNotEmpty() && !selecting) {
            Row(Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 4.dp).clip(RoundedCornerShape(10.dp))
                .background(p.field).clickable { act.reveal() }.padding(horizontal = 12.dp, vertical = 10.dp),
                verticalAlignment = Alignment.CenterVertically) {
                Icon(Icons.Default.Lock, null, tint = p.muted, modifier = Modifier.size(16.dp))
                Spacer(Modifier.width(8.dp))
                T("Previews hidden", 14, FontWeight.Medium, modifier = Modifier.weight(1f))
                T("Tap to reveal", 14, FontWeight.SemiBold, p.accent)
            }
        }
        AnimatedVisibility(vm.busy != null) {
            Column(Modifier.fillMaxWidth().padding(horizontal = 20.dp, vertical = 6.dp)) {
                T(vm.busy ?: "", 13, color = p.muted)
                Spacer(Modifier.height(4.dp))
                val pr = vm.progress
                if (pr == null) LinearProgressIndicator(Modifier.fillMaxWidth(), color = p.accent, trackColor = p.line)
                else LinearProgressIndicator({ pr }, Modifier.fillMaxWidth(), color = p.accent, trackColor = p.line)
            }
        }
        Box(Modifier.weight(1f)) {
            if (shown.isEmpty()) {
                Column(Modifier.align(Alignment.Center), horizontalAlignment = Alignment.CenterHorizontally) {
                    Icon(Icons.Default.Lock, null, tint = p.line, modifier = Modifier.size(56.dp))
                    Spacer(Modifier.height(12.dp))
                    T(if (vm.files.isEmpty()) "Your vault is empty" else "No matches", 20, FontWeight.SemiBold)
                    T("Tap + to encrypt files into the vault", 14, color = p.muted)
                }
            }
            LazyVerticalGrid(GridCells.Adaptive(104.dp), Modifier.fillMaxSize(), contentPadding = PaddingValues(start = 16.dp, top = 16.dp, end = 16.dp, bottom = 96.dp),
                horizontalArrangement = Arrangement.spacedBy(12.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
                items(shown, key = { it.id }) { e ->
                    val sel = e.id in vm.selection
                    FileCard(e, sel, selecting, vm.revealed,
                        onClick = { if (!vm.revealed) act.reveal() else if (selecting) vm.selection = if (sel) vm.selection - e.id else vm.selection + e.id else vm.openFile(e.id, ctx) },
                        onLong = { if (!vm.revealed) act.reveal() else vm.selection = vm.selection + e.id })
                }
            }
            if (!selecting) Box(Modifier.align(Alignment.BottomEnd).padding(20.dp).shadow(6.dp, CircleShape).size(58.dp)
                .clip(CircleShape).background(p.accent).clickable { vm.suppressLock = true; act.pick() }, contentAlignment = Alignment.Center) {
                Icon(Icons.Default.Add, "Add files", tint = Color.White, modifier = Modifier.size(30.dp))
            }
        }
        if (selecting) {
            Box(Modifier.fillMaxWidth().height(0.5.dp).background(p.line))
            Row(Modifier.fillMaxWidth().background(p.card).padding(vertical = 4.dp)) {
                TextButton({
                    val ids = vm.selection.toList()
                    vm.dialog = Dlg.Choice("Export", listOf("Export a copy" to 0, "Move out of vault" to 1), -1) { mode ->
                        val move = mode == 1
                        vm.dialog = Dlg.Verify(if (move) "Move out of vault" else "Export a copy",
                            if (move) "The decrypted files will be saved and then removed from the vault. Enter your passcode."
                            else "A decrypted copy will be saved. The original stays encrypted in the vault. Enter your passcode.") {
                            vm.pendingExport = ids; vm.pendingMove = move; vm.suppressLock = true
                            if (ids.size == 1) act.exportSingle() else act.exportTree()
                        }
                    }
                }, Modifier.weight(1f)) { Icon(Icons.Default.Share, null, tint = p.accent); Spacer(Modifier.width(6.dp)); T("Export", 16, color = p.accent) }
                TextButton({ vm.deleteSelected() }, Modifier.weight(1f)) { Icon(Icons.Default.Delete, null, tint = p.danger); Spacer(Modifier.width(6.dp)); T("Delete", 16, color = p.danger) }
            }
        }
    }
}

private fun typeStyle(name: String): Pair<Color, String> {
    val ext = name.substringAfterLast('.', "").lowercase()
    val c = when (ext) {
        "pdf" -> Color(0xFFFF3B30)
        "doc", "docx", "txt", "rtf", "odt", "md" -> Color(0xFF007AFF)
        "xls", "xlsx", "csv", "ods" -> Color(0xFF34C759)
        "ppt", "pptx", "key" -> Color(0xFFFF9500)
        "zip", "rar", "7z", "tar", "gz" -> Color(0xFF8E6B3E)
        "mp3", "wav", "m4a", "flac", "ogg", "aac" -> Color(0xFFAF52DE)
        "mp4", "mkv", "mov", "avi", "webm", "3gp" -> Color(0xFFFF2D55)
        "jpg", "jpeg", "png", "gif", "webp", "bmp", "heic" -> Color(0xFF30B0C7)
        else -> Color(0xFF8E8E93)
    }
    return c to (ext.take(4).uppercase().ifEmpty { "FILE" })
}

@OptIn(ExperimentalFoundationApi::class)
@Composable
private fun FileCard(e: FileEntry, sel: Boolean, selecting: Boolean, revealed: Boolean, onClick: () -> Unit, onLong: () -> Unit) {
    val p = LocalPal.current
    val glyph = when { e.mime.startsWith("video/") -> "▶"; e.mime.startsWith("audio/") -> "♪"; else -> null }
    Column(Modifier.clip(RoundedCornerShape(14.dp)).background(p.card)
        .border(if (sel) 2.dp else 0.5.dp, if (sel) p.accent else p.line, RoundedCornerShape(14.dp))
        .combinedClickable(onClick = onClick, onLongClick = onLong).padding(8.dp)) {
        Box(Modifier.fillMaxWidth().aspectRatio(1f).clip(RoundedCornerShape(10.dp))) {
            // The stored preview is sharp; until the second authentication we only ever draw a ~10px version, upscaled = blur.
            val bmp = remember(e.id, revealed) {
                e.thumb?.let { BitmapFactory.decodeByteArray(it, 0, it.size) }?.let { b ->
                    if (revealed) b else Bitmap.createScaledBitmap(b, maxOf(1, b.width / 12), maxOf(1, b.height / 12), true)
                }?.asImageBitmap()
            }
            if (bmp != null) {
                Image(bmp, null, Modifier.fillMaxSize(), contentScale = ContentScale.Crop, filterQuality = FilterQuality.Low)
                if (glyph != null) Box(Modifier.align(Alignment.Center).size(34.dp).clip(CircleShape).background(Color.Black.copy(alpha = 0.45f)),
                    contentAlignment = Alignment.Center) { T(glyph, 16, color = Color.White) }
            } else {
                val (c, label) = typeStyle(e.name)
                Box(Modifier.fillMaxSize().background(c), contentAlignment = Alignment.Center) {
                    Column(horizontalAlignment = Alignment.CenterHorizontally) {
                        if (glyph != null) T(glyph, 24, color = Color.White)
                        T(label, 20, FontWeight.Bold, Color.White)
                    }
                }
            }
            if (selecting) Box(Modifier.align(Alignment.TopEnd).padding(6.dp).size(22.dp).clip(CircleShape)
                .background(if (sel) p.accent else Color.Black.copy(alpha = 0.25f))
                .border(1.5.dp, Color.White, CircleShape), contentAlignment = Alignment.Center) {
                if (sel) Icon(Icons.Default.Check, null, tint = Color.White, modifier = Modifier.size(14.dp))
            }
        }
        Spacer(Modifier.height(8.dp))
        T(e.name, 13, FontWeight.Medium, max = 2, modifier = Modifier.heightIn(min = 34.dp))
        T(fmt(e.size), 11, color = p.muted)
    }
}

// ---------------------------------------------------------------- settings
@Composable
private fun Group(title: String, content: @Composable ColumnScope.() -> Unit) {
    val p = LocalPal.current
    T(title.uppercase(), 12, color = p.muted, modifier = Modifier.padding(start = 32.dp, top = 22.dp, bottom = 6.dp))
    Column(Modifier.padding(horizontal = 16.dp).fillMaxWidth().clip(RoundedCornerShape(12.dp)).background(p.card), content = content)
}

@Composable
private fun Item(title: String, sub: String? = null, color: Color = LocalPal.current.text, divider: Boolean = true,
                 onClick: (() -> Unit)? = null, trailing: @Composable () -> Unit = {}) {
    val p = LocalPal.current
    Column {
        Row(Modifier.fillMaxWidth().then(if (onClick != null) Modifier.clickable(onClick = onClick) else Modifier)
            .padding(horizontal = 16.dp, vertical = 12.dp), verticalAlignment = Alignment.CenterVertically) {
            Column(Modifier.weight(1f)) {
                T(title, 16, color = color)
                sub?.let { T(it, 12, color = p.muted) }
            }
            trailing()
        }
        if (divider) Box(Modifier.padding(start = 16.dp).fillMaxWidth().height(0.5.dp).background(p.line))
    }
}

private val AUTOLOCK = listOf("30 seconds" to 30, "1 minute" to 60, "5 minutes" to 300, "15 minutes" to 900)
private val RELOCK = listOf("1 minute" to 60, "2 minutes" to 120, "5 minutes" to 300, "10 minutes" to 600)

@Composable
fun SettingsScreen(vm: MainVM, act: Actions) {
    val p = LocalPal.current
    val sw = SwitchDefaults.colors(checkedTrackColor = Color(0xFF34C759), checkedThumbColor = Color.White,
        uncheckedTrackColor = p.line, uncheckedThumbColor = Color.White, uncheckedBorderColor = p.line, checkedBorderColor = Color(0xFF34C759))
    val ctx = LocalContext.current
    val bioOk = act.bioAvailable()
    Column(Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(bottom = 24.dp)) {
        T("Settings", 34, FontWeight.Bold, modifier = Modifier.padding(start = 20.dp, top = 14.dp, bottom = 4.dp))
        Group("Security") {
            Item("Change Passcode", onClick = { vm.dialog = Dlg.ChangePin }) {
                Icon(Icons.Default.KeyboardArrowRight, null, tint = p.muted)
            }
            Item("Fingerprint Unlock", if (bioOk) "Unlock the vault with your fingerprint" else "No fingerprint enrolled or not supported") {
                Switch(vm.bioOn, { on ->
                    if (!on) vm.disableBio()
                    else vm.dialog = Dlg.Verify("Enable fingerprint", "Enter your passcode, then confirm with your fingerprint.") { act.bioEnable() }
                }, enabled = bioOk, colors = sw)
            }
            Item("Face Unlock", if (act.faceSupported) "Less secure than fingerprint. A photo of you may fool it. Passcode needed every 72 h."
                else "Needs a front camera", divider = false) {
                Badge("EXPERIMENTAL", p.danger); Spacer(Modifier.width(10.dp))
                Switch(vm.faceOn, { on ->
                    if (!on) vm.disableFace()
                    else vm.dialog = Dlg.Verify("Set up Face Unlock", "Experimental feature. Enter your passcode, then follow the on-screen steps.") { act.faceStart(FaceMode.Enroll) }
                }, enabled = act.faceSupported, colors = sw)
            }
        }
        Group("Privacy") {
            Item("Auto-lock", "After leaving the app", onClick = {
                vm.dialog = Dlg.Choice("Auto-lock", AUTOLOCK, vm.autolockSec) { vm.setAutolock(it) }
            }) { T(AUTOLOCK.firstOrNull { it.second == vm.autolockSec }?.first ?: "", 15, color = p.muted) }
            Item("Re-lock opened files", "Decrypted copies are wiped after this long", onClick = {
                vm.dialog = Dlg.Choice("Re-lock opened files", RELOCK, vm.relockSec) { vm.setRelock(it) }
            }) { T(RELOCK.firstOrNull { it.second == vm.relockSec }?.first ?: "", 15, color = p.muted) }
            Item("Move files on import", "Remove the original once the encrypted copy is verified", divider = false) {
                Switch(vm.moveOnImport, { vm.setMove(it) }, colors = sw)
            }
        }
        Group("Vault") {
            Item("Lock Now", color = p.accent, divider = false, onClick = { vm.lock() })
        }
        Group("About") {
            Item("BioLocky ${AppInfo.VERSION}", "${AppInfo.STAGE} · expect rough edges") {
                T("RC1", 12, FontWeight.Bold, Color.White,
                    Modifier.clip(RoundedCornerShape(6.dp)).background(p.accent).padding(horizontal = 8.dp, vertical = 2.dp))
            }
            Item("Source on GitHub", AppInfo.GITHUB, onClick = {
                try { ctx.startActivity(Intent(Intent.ACTION_VIEW, Uri.parse(AppInfo.GITHUB))) } catch (_: Exception) { vm.toast = "No browser found" }
            }) { Icon(Icons.Default.KeyboardArrowRight, null, tint = p.muted) }
            Item("Encryption", "AES-256-GCM · PBKDF2 · Android Keystore", divider = false)
        }
    }
}
