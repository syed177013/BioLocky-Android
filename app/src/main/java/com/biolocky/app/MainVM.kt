package com.biolocky.app

import android.app.Application
import android.content.ActivityNotFoundException
import android.content.ContentResolver
import android.content.Context
import android.content.Intent
import android.net.Uri
import android.provider.DocumentsContract
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.core.content.FileProvider
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.File
import java.io.IOException
import javax.crypto.Cipher

sealed interface Dlg {
    class Verify(val title: String, val message: String, val then: () -> Unit) : Dlg
    object ChangePin : Dlg
    object ResetPin : Dlg
    class Recovery(val code: String) : Dlg
    class Confirm(val title: String, val message: String, val label: String, val danger: Boolean, val then: () -> Unit) : Dlg
    class Info(val title: String, val message: String) : Dlg
    class Choice(val title: String, val options: List<Pair<String, Int>>, val current: Int, val onPick: (Int) -> Unit) : Dlg
}

fun sortList(l: List<FileEntry>, key: String, desc: Boolean): List<FileEntry> {
    val base = l.sortedBy { it.name.lowercase() }
    val s = when (key) {
        "added" -> base.sortedBy { it.added }
        "modified" -> base.sortedBy { it.modified }
        "size" -> base.sortedBy { it.size }
        "type" -> base.sortedBy { it.name.substringAfterLast('.', "").lowercase() }
        else -> base
    }
    return if (desc) s.reversed() else s
}

class MainVM(app: Application) : AndroidViewModel(app) {
    val vault = Vault(app)
    private val prefs = Prefs(app)
    val faceEngine = FaceEngine(app)

    var exists by mutableStateOf(vault.exists)
    var locked by mutableStateOf(true)
    var bioOn by mutableStateOf(vault.hasBio)
    var files by mutableStateOf(emptyList<FileEntry>())
    var query by mutableStateOf("")
    var sortKey by mutableStateOf(prefs.sortKey)
    var sortDesc by mutableStateOf(prefs.sortDesc)
    var selection by mutableStateOf(emptySet<String>())
    var busy by mutableStateOf<String?>(null)
    var progress by mutableStateOf<Float?>(null)
    var toast by mutableStateOf<String?>(null)
    var dialog by mutableStateOf<Dlg?>(null)
    var dialogError by mutableStateOf<String?>(null)
    var dialogBusy by mutableStateOf(false)
    var pinError by mutableStateOf<String?>(null)
    var pinBusy by mutableStateOf(false)
    var pinReset by mutableIntStateOf(0)
    var faceOn by mutableStateOf(vault.hasFace)
    var faceReady by mutableStateOf(false)        // enabled, passcode used within 72 h, not paused after misses
    var faceMode by mutableStateOf<FaceMode?>(null)
    var faceTemplates: List<FloatArray> = emptyList()
    private var faceStrikes = 0
    var revealed by mutableStateOf(false)      // previews stay blurred until a second authentication
    var revealAsked by mutableStateOf(false)
    var fgTick by mutableIntStateOf(0)
    var tab by mutableIntStateOf(0)
    var autolockSec by mutableIntStateOf(prefs.autolockSec)
    var relockSec by mutableIntStateOf(prefs.relockSec)
    var moveOnImport by mutableStateOf(prefs.moveOnImport)

    var suppressLock = false          // set while a system picker is in front
    var pendingExport: List<String> = emptyList()
    var pendingMove = false           // true = export then remove from vault
    private var lockJob: Job? = null

    init { refreshFace() }

    fun refreshFace() {
        faceOn = vault.hasFace
        faceReady = faceOn && faceStrikes < 3 && vault.faceAllowed() && faceEngine.available
    }

    private fun msg(e: Throwable) = when (e) {
        is BadSecret, is Throttled -> e.message ?: "Error"
        else -> "Something went wrong: ${e.message}"
    }

    /** Runs [block] off the UI thread and delivers the result on it. */
    private fun <T> io(block: () -> T, ok: (T) -> Unit = {}, err: (Throwable) -> Unit = { toast = msg(it) }) {
        viewModelScope.launch {
            val r = withContext(Dispatchers.Default) { runCatching(block) }
            r.fold(ok, err)
        }
    }

    // ------------------------------------------------------------ lock / unlock
    private fun unlocked() {
        locked = false; pinError = null; tab = 0; revealed = false; revealAsked = false; faceStrikes = 0; bioOn = vault.hasBio; refresh(); refreshFace()
    }

    fun refresh() { if (!locked) files = vault.list() }

    fun createVault(pin: String) {
        pinBusy = true
        io({ vault.create(pin) }, { code ->
            pinBusy = false; exists = true; unlocked(); dialog = Dlg.Recovery(code)
        }, { pinBusy = false; pinError = msg(it); pinReset++ })
    }

    fun unlockPin(pin: String) {
        pinBusy = true
        io({ vault.unlockPin(pin) }, { pinBusy = false; unlocked() },
            { pinBusy = false; pinError = msg(it); pinReset++ })
    }

    fun finishBioUnlock(c: Cipher) = io({ vault.bioUnlock(c) }, { unlocked() })
    fun finishBioEnable(c: Cipher) = io({ vault.bioStore(c) }, { bioOn = true; toast = "Fingerprint unlock enabled" })
    fun disableBio() = io({ vault.bioRemove() }, { bioOn = false })
    fun invalidateBio() = io({ vault.bioRemove() }, {
        bioOn = false; pinError = "Fingerprints changed. Use your passcode, then re-enable fingerprint."
    })

    fun lock() {
        lockJob?.cancel()
        vault.lock()
        selection = emptySet(); query = ""; files = emptyList(); dialog = null; busy = null; progress = null
        locked = true; revealed = false; revealAsked = false; faceMode = null
        refreshFace()
        io({ vault.wipeTemps(true) })
    }

    fun onBackground() {
        if (locked || suppressLock) return
        lockJob = viewModelScope.launch { delay(autolockSec * 1000L); lock() }
    }

    fun onForeground() {
        suppressLock = false; lockJob?.cancel(); fgTick++; refreshFace()
    }

    fun askReveal() {
        dialog = Dlg.Verify("Reveal previews", "Enter your passcode to show file previews.") { revealed = true }
    }

    fun startFace(mode: FaceMode) {
        if (faceMode != null) return
        if (mode != FaceMode.Enroll) {
            faceTemplates = try { vault.faceTemplates() } catch (e: Exception) { emptyList() }
            if (faceTemplates.isEmpty()) { toast = "Face data unavailable. Use your passcode."; if (mode == FaceMode.Reveal) askReveal(); return }
        }
        faceMode = mode
    }

    fun faceCancel(mode: FaceMode) { faceMode = null; if (mode == FaceMode.Reveal) askReveal() }

    fun faceFinished(mode: FaceMode, ok: Boolean, samples: List<FloatArray>) {
        faceMode = null
        if (ok) {
            when (mode) {
                FaceMode.Enroll -> io({ vault.faceEnable(samples) }, { refreshFace(); toast = "Face unlock enabled (experimental)" })
                FaceMode.Unlock -> io({ vault.faceUnlock() }, { unlocked() }, { toast = msg(it) })
                FaceMode.Reveal -> revealed = true
            }
        } else if (mode == FaceMode.Enroll) {
            toast = "Couldn’t capture your face. Try again in good light."
        } else {
            faceStrikes++; refreshFace()
            toast = if (faceStrikes >= 3) "Face unlock paused. Use your passcode." else "Face not recognised"
            if (mode == FaceMode.Reveal) askReveal()
        }
    }

    fun disableFace() = io({ vault.faceRemove() }, { refreshFace() })

    fun cancelExport() { pendingExport = emptyList(); pendingMove = false }

    // ------------------------------------------------------------ dialogs
    fun closeDialog() { dialog = null; dialogError = null; dialogBusy = false }
    private fun pinOk(p: String) = p.length == 6 && p.all { it.isDigit() }

    fun verify(pin: String, then: () -> Unit) {
        dialogBusy = true
        io({ vault.verify(pin) }, { closeDialog(); then() }, { dialogBusy = false; dialogError = msg(it) })
    }

    fun changePin(old: String, new: String, conf: String) {
        if (!pinOk(new)) { dialogError = "New passcode must be 6 digits"; return }
        if (new != conf) { dialogError = "Passcodes don’t match"; return }
        dialogBusy = true
        io({ vault.changePin(old, new) }, { closeDialog(); toast = "Passcode updated" },
            { dialogBusy = false; dialogError = msg(it) })
    }

    fun resetPin(code: String, new: String, conf: String) {
        if (!pinOk(new)) { dialogError = "New passcode must be 6 digits"; return }
        if (new != conf) { dialogError = "Passcodes don’t match"; return }
        dialogBusy = true
        io({ vault.resetWithRecovery(code, new) }, { closeDialog(); unlocked() },
            { dialogBusy = false; dialogError = msg(it) })
    }

    // ------------------------------------------------------------ settings
    fun setSort(key: String) {
        if (key != sortKey) {
            sortKey = key
            sortDesc = key in setOf("added", "modified", "size")   // Explorer-style defaults
        }
        prefs.sortKey = sortKey; prefs.sortDesc = sortDesc
    }
    fun toggleDir() { sortDesc = !sortDesc; prefs.sortDesc = sortDesc }
    fun setAutolock(s: Int) { autolockSec = s; prefs.autolockSec = s }
    fun setRelock(s: Int) { relockSec = s; prefs.relockSec = s }
    fun setMove(b: Boolean) { moveOnImport = b; prefs.moveOnImport = b }

    // ------------------------------------------------------------ files
    fun importUris(uris: List<Uri>, cr: ContentResolver) {
        if (uris.isEmpty() || locked) return
        val move = moveOnImport
        busy = "Encrypting…"; progress = 0f
        viewModelScope.launch {
            val r = withContext(Dispatchers.Default) {
                var ok = 0; var kept = 0; val fail = mutableListOf<String>()
                uris.forEachIndexed { i, u ->
                    try {
                        val (_, deleted) = vault.import(cr, u, move) { f ->
                            progress = (i + f) / uris.size; busy = "Encrypting ${i + 1} of ${uris.size}"
                        }
                        ok++; if (move && !deleted) kept++
                    } catch (e: Exception) { fail += (e.message ?: "Unknown error") }
                }
                Triple(ok, kept, fail)
            }
            busy = null; progress = null; refresh()
            val (ok, kept, fail) = r
            toast = "$ok file${if (ok != 1) "s" else ""} secured" + if (kept > 0) " · $kept original${if (kept != 1) "s" else ""} couldn’t be removed by Android" else ""
            if (fail.isNotEmpty()) dialog = Dlg.Info("Some files couldn’t be added", fail.take(3).joinToString("\n"))
        }
    }

    fun openFile(id: String, ctx: Context) {
        busy = "Decrypting…"; progress = null
        io({ vault.openTemp(id, relockSec) }, { f ->
            busy = null
            val e = files.firstOrNull { it.id == id }
            val uri = FileProvider.getUriForFile(ctx, "${ctx.packageName}.files", f)
            val i = Intent(Intent.ACTION_VIEW).setDataAndType(uri, e?.mime ?: "*/*")
                .addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
            try { ctx.startActivity(i) } catch (x: ActivityNotFoundException) { toast = "No app installed can open this file" }
            viewModelScope.launch { delay(relockSec * 1000L + 500); withContext(Dispatchers.IO) { vault.wipeTemps(false) } }
        }, { busy = null; toast = msg(it) })
    }

    fun exportSingle(uri: Uri, cr: ContentResolver) {
        val id = pendingExport.firstOrNull() ?: return
        val move = pendingMove
        cancelExport()
        busy = "Exporting…"
        io({
            vault.export(id, cr.openOutputStream(uri, "w") ?: throw IOException("Can’t write there"))
            if (move) vault.deleteMany(setOf(id))     // only after the copy was written successfully
        }, { busy = null; selection = emptySet(); refresh(); toast = if (move) "Moved out of the vault" else "Exported a copy" },
            { busy = null; toast = msg(it) })
    }

    fun exportTree(tree: Uri, cr: ContentResolver) {
        val ids = pendingExport
        val move = pendingMove
        cancelExport()
        busy = "Exporting…"; progress = 0f
        io({
            val parent = DocumentsContract.buildDocumentUriUsingTree(tree, DocumentsContract.getTreeDocumentId(tree))
            ids.forEachIndexed { i, id ->
                val e = vault.entry(id)
                val dest = DocumentsContract.createDocument(cr, parent, e.mime, e.name) ?: throw IOException("Can’t create ${e.name}")
                vault.export(id, cr.openOutputStream(dest, "w") ?: throw IOException("Can’t write ${e.name}"))
                progress = (i + 1f) / ids.size
            }
            if (move) vault.deleteMany(ids.toSet())   // nothing is removed unless every copy succeeded
            ids.size
        }, { busy = null; progress = null; selection = emptySet(); refresh()
            toast = (if (move) "Moved " else "Exported ") + "$it file${if (it != 1) "s" else ""}" + (if (move) " out of the vault" else " as copies") },
            { busy = null; progress = null; toast = msg(it) })
    }

    fun deleteSelected() {
        val ids = selection
        if (ids.isEmpty()) return
        val what = if (ids.size == 1) "“${files.first { it.id == ids.first() }.name}”" else "${ids.size} files"
        dialog = Dlg.Confirm("Delete permanently?", "$what will be removed from the vault.", "Delete", true) {
            io({ vault.deleteMany(ids) }, { selection = emptySet(); refresh() })
        }
    }
}
