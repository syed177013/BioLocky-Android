package com.biolocky.app

import android.content.ContentResolver
import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.Color
import android.graphics.pdf.PdfRenderer
import android.media.MediaMetadataRetriever
import android.net.Uri
import android.provider.DocumentsContract
import android.provider.OpenableColumns
import android.util.Base64
import android.webkit.MimeTypeMap
import org.json.JSONArray
import org.json.JSONObject
import java.io.*
import java.nio.ByteBuffer
import java.nio.file.Files
import java.nio.file.StandardCopyOption
import java.util.UUID
import javax.crypto.Cipher

class FileEntry(val id: String, val name: String, val size: Long, val added: Long,
                val modified: Long, val mime: String, val thumb: ByteArray?)

class BadSecret(m: String) : Exception(m)
class Throttled(val wait: Long) : Exception("Too many attempts. Try again in ${wait + 1}s.")

class Prefs(c: Context) {
    private val sp = c.getSharedPreferences("biolocky", Context.MODE_PRIVATE)
    var sortKey: String get() = sp.getString("sortKey", "name")!!; set(v) = sp.edit().putString("sortKey", v).apply()
    var sortDesc: Boolean get() = sp.getBoolean("sortDesc", false); set(v) = sp.edit().putBoolean("sortDesc", v).apply()
    var autolockSec: Int get() = sp.getInt("autolock", 300); set(v) = sp.edit().putInt("autolock", v).apply()
    var relockSec: Int get() = sp.getInt("relock", 300); set(v) = sp.edit().putInt("relock", v).apply()
    var moveOnImport: Boolean get() = sp.getBoolean("move", true); set(v) = sp.edit().putBoolean("move", v).apply()
}

/** Encrypted vault.
 *  A random 256-bit data key (DEK) encrypts everything. It is stored only wrapped:
 *   - by PBKDF2(PIN), then sealed again by a non-exportable Keystore key (no off-device brute force)
 *   - by PBKDF2(recovery code)
 *   - optionally by a Keystore key that needs a fingerprint for each use.
 *  Files live in blobs/<id>.blk, always encrypted. Names/thumbnails live in a sealed index. */
class Vault(private val ctx: Context) {
    private val root = File(ctx.filesDir, "vault").apply { mkdirs() }
    private val blobs = File(root, "blobs").apply { mkdirs() }
    private val krFile = File(root, "keyring.json")
    private val idxFile = File(root, "index.blob")
    private val tmpRoot = File(ctx.cacheDir, "open")
    @Volatile private var dek: ByteArray? = null
    private val files = mutableListOf<FileEntry>()
    private val temps = HashMap<String, Long>()

    init { tmpRoot.listFiles()?.forEach { shred(it) } }   // leftovers from a previous process

    val exists get() = krFile.exists()
    val hasBio get() = exists && kr().has("bio")

    private fun b64(b: ByteArray) = Base64.encodeToString(b, Base64.NO_WRAP)
    private fun unb64(s: String) = Base64.decode(s, Base64.NO_WRAP)
    private fun kr() = JSONObject(krFile.readText())
    private fun write(f: File, b: ByteArray) {
        val t = File(f.path + ".tmp")
        FileOutputStream(t).use { it.write(b); it.fd.sync() }
        Files.move(t.toPath(), f.toPath(), StandardCopyOption.REPLACE_EXISTING)
    }
    private fun saveKr(j: JSONObject) = write(krFile, j.toString().toByteArray())
    private fun need() = dek ?: throw IllegalStateException("Vault is locked")

    // ---------------------------------------------------------------- keys
    private fun wrapPin(pin: String, d: ByteArray): JSONObject {
        val salt = Crypto.rnd(16)
        val k = Crypto.pbkdf2(pin, salt)
        return JSONObject().put("salt", b64(salt)).put("blob", b64(Keys.deviceSeal(Crypto.seal(k, d, "pin".toByteArray()))))
    }

    private fun unwrapPin(w: JSONObject, pin: String): ByteArray? = try {
        Crypto.unseal(Crypto.pbkdf2(pin, unb64(w.getString("salt"))), Keys.deviceUnseal(unb64(w.getString("blob"))), "pin".toByteArray())
    } catch (e: Exception) { null }

    private fun wrapRec(code: String, d: ByteArray): JSONObject {
        val salt = Crypto.rnd(16)
        return JSONObject().put("salt", b64(salt)).put("blob", b64(Crypto.seal(Crypto.pbkdf2(code, salt), d, "rec".toByteArray())))
    }

    private fun unwrapRec(w: JSONObject, code: String): ByteArray? = try {
        Crypto.unseal(Crypto.pbkdf2(code, unb64(w.getString("salt"))), unb64(w.getString("blob")), "rec".toByteArray())
    } catch (e: Exception) { null }

    @Synchronized private fun attempt(recovery: Boolean, secret: String): ByteArray {
        val k = kr()
        val wait = k.optLong("lockedUntil") - System.currentTimeMillis()
        if (wait > 0) throw Throttled(wait / 1000)
        val d = if (recovery) unwrapRec(k.getJSONObject("rec"), secret) else unwrapPin(k.getJSONObject("pin"), secret)
        if (d == null) {
            val f = k.optInt("fails") + 1
            k.put("fails", f)
            if (f >= 5) k.put("lockedUntil", System.currentTimeMillis() + minOf(900, 15 * (1 shl minOf(f - 5, 6))) * 1000L)
            saveKr(k)
            throw BadSecret(if (recovery) "Incorrect recovery code" else "Incorrect passcode")
        }
        k.put("fails", 0).put("lockedUntil", 0).put("strong", System.currentTimeMillis())
        saveKr(k)
        return d
    }

    private fun normCode(c: String) = c.replace("-", "").replace(" ", "").uppercase()

    fun create(pin: String): String {
        check(!exists) { "Vault already exists" }
        val d = Crypto.rnd(32)
        val code = Crypto.hex(Crypto.rnd(10)).uppercase().chunked(5).joinToString("-")
        saveKr(JSONObject().put("fails", 0).put("lockedUntil", 0)
            .put("pin", wrapPin(pin, d)).put("rec", wrapRec(normCode(code), d)))
        synchronized(this) { dek = d; files.clear(); saveIndex() }
        return code
    }

    fun unlockPin(pin: String) { val d = attempt(false, pin); synchronized(this) { dek = d; loadIndex() } }
    fun verify(pin: String) { attempt(false, pin) }

    fun changePin(old: String, new: String) {
        val d = attempt(false, old)
        saveKr(kr().put("pin", wrapPin(new, d)))
    }

    fun resetWithRecovery(code: String, new: String) {
        val d = attempt(true, normCode(code))
        saveKr(kr().put("pin", wrapPin(new, d)))
        synchronized(this) { dek = d; loadIndex() }
    }

    fun bioIv(): ByteArray = unb64(kr().getJSONObject("bio").getString("iv"))
    fun bioStore(c: Cipher) {
        val blob = c.doFinal(need())
        saveKr(kr().put("bio", JSONObject().put("iv", b64(c.iv)).put("blob", b64(blob))))
    }
    fun bioUnlock(c: Cipher) {
        val d = c.doFinal(unb64(kr().getJSONObject("bio").getString("blob")))
        synchronized(this) { dek = d; loadIndex() }
        saveKr(kr().put("strong", System.currentTimeMillis()))
    }
    fun bioRemove() {
        saveKr(kr().apply { remove("bio") })
        Keys.deleteBio()
    }

    // ---- EXPERIMENTAL face unlock.
    // The face check itself is software, so it is weaker than the fingerprint: the data key is sealed by a
    // device-bound (non-exportable) Keystore key and released after a face match. As a safeguard the face
    // path stops working 72 h after the last passcode/fingerprint unlock.
    val hasFace get() = exists && kr().has("face")

    fun faceAllowed(): Boolean = exists && System.currentTimeMillis() - kr().optLong("strong") < 72L * 3600 * 1000

    fun faceEnable(samples: List<FloatArray>) {
        val bb = ByteBuffer.allocate(samples.size * samples[0].size * 4)
        samples.forEach { s -> s.forEach { bb.putFloat(it) } }
        val tpl = JSONObject().put("dim", samples[0].size).put("n", samples.size)
            .put("data", b64(Keys.deviceSeal(bb.array())))
        saveKr(kr().put("face", JSONObject().put("wrap", b64(Keys.deviceSeal(need()))).put("tpl", tpl))
            .put("strong", System.currentTimeMillis()))
    }

    fun faceTemplates(): List<FloatArray> {
        val t = kr().getJSONObject("face").getJSONObject("tpl")
        val bb = ByteBuffer.wrap(Keys.deviceUnseal(unb64(t.getString("data"))))
        val dim = t.getInt("dim")
        return List(t.getInt("n")) { FloatArray(dim) { bb.getFloat() } }
    }

    fun faceUnlock() {
        check(faceAllowed()) { "Passcode required" }
        val d = Keys.deviceUnseal(unb64(kr().getJSONObject("face").getString("wrap")))
        synchronized(this) { dek = d; loadIndex() }
    }

    fun faceRemove() { saveKr(kr().apply { remove("face") }) }

    /** Drops keys and the in-memory index immediately. Call wipeTemps(true) afterwards off the UI thread. */
    @Synchronized fun lock() { dek = null; files.clear() }

    // ---------------------------------------------------------------- index
    private fun loadIndex() {
        files.clear()
        if (!idxFile.exists()) return
        val j = JSONObject(String(Crypto.unseal(Crypto.sub(need(), "index"), idxFile.readBytes())))
        val a = j.getJSONArray("files")
        for (i in 0 until a.length()) {
            val o = a.getJSONObject(i)
            files += FileEntry(o.getString("id"), o.getString("name"), o.getLong("size"), o.getLong("added"),
                o.optLong("modified", o.getLong("added")), o.optString("mime", "application/octet-stream"),
                o.optString("thumb").takeIf { it.isNotEmpty() }?.let(::unb64))
        }
    }

    private fun saveIndex() {
        val a = JSONArray()
        files.forEach {
            a.put(JSONObject().put("id", it.id).put("name", it.name).put("size", it.size).put("added", it.added)
                .put("modified", it.modified).put("mime", it.mime).put("thumb", it.thumb?.let(::b64) ?: ""))
        }
        write(idxFile, Crypto.seal(Crypto.sub(need(), "index"), JSONObject().put("files", a).toString().toByteArray()))
    }

    @Synchronized fun list(): List<FileEntry> { need(); return files.toList() }
    fun entry(id: String) = list().first { it.id == id }

    // ---------------------------------------------------------------- files
    private fun guessMime(name: String) = MimeTypeMap.getSingleton()
        .getMimeTypeFromExtension(name.substringAfterLast('.', "").lowercase()) ?: "application/octet-stream"

    private fun safe(n: String) = n.replace(Regex("[\\\\/:*?\"<>|\\u0000-\\u001f]"), "_").trim(' ', '.').ifEmpty { "file" }

    private fun decodeImage(cr: ContentResolver, uri: Uri): Bitmap? {
        val o = BitmapFactory.Options().apply { inJustDecodeBounds = true }
        cr.openInputStream(uri)?.use { BitmapFactory.decodeStream(it, null, o) }
        var s = 1
        while (o.outWidth / s > 320 || o.outHeight / s > 320) s *= 2
        return cr.openInputStream(uri)?.use {
            BitmapFactory.decodeStream(it, null, BitmapFactory.Options().apply { inSampleSize = s })
        }
    }

    private fun videoFrame(uri: Uri): Bitmap? {
        val r = MediaMetadataRetriever()
        try {
            r.setDataSource(ctx, uri)
            return r.getFrameAtTime(1_000_000, MediaMetadataRetriever.OPTION_CLOSEST_SYNC) ?: r.frameAtTime
        } finally { r.release() }
    }

    private fun albumArt(uri: Uri): Bitmap? {
        val r = MediaMetadataRetriever()
        try {
            r.setDataSource(ctx, uri)
            return r.embeddedPicture?.let { BitmapFactory.decodeByteArray(it, 0, it.size) }
        } finally { r.release() }
    }

    private fun pdfPage(cr: ContentResolver, uri: Uri): Bitmap? {
        val pfd = cr.openFileDescriptor(uri, "r") ?: return null
        pfd.use {
            PdfRenderer(it).use { rd ->
                if (rd.pageCount == 0) return null
                rd.openPage(0).use { pg ->
                    val w = 320
                    val h = (pg.height * w / pg.width.toFloat()).toInt().coerceAtLeast(1)
                    val b = Bitmap.createBitmap(w, h, Bitmap.Config.ARGB_8888)
                    b.eraseColor(Color.WHITE)
                    pg.render(b, null, null, PdfRenderer.Page.RENDER_MODE_FOR_DISPLAY)
                    return b
                }
            }
        }
    }

    /** Small SHARP preview (128px JPEG) for images, video frames, PDF first pages and album art.
     *  The UI blurs it until the user re-authenticates; other file types get a type tile instead. */
    private fun makeThumb(cr: ContentResolver, uri: Uri, mime: String): ByteArray? {
        try {
            val bm = when {
                mime.startsWith("image/") -> decodeImage(cr, uri)
                mime.startsWith("video/") -> videoFrame(uri)
                mime.startsWith("audio/") -> albumArt(uri)
                mime == "application/pdf" -> pdfPage(cr, uri)
                else -> null
            } ?: return null
            val k = 128f / maxOf(bm.width, bm.height)
            val t = if (k < 1f) Bitmap.createScaledBitmap(bm, maxOf(1, (bm.width * k).toInt()), maxOf(1, (bm.height * k).toInt()), true) else bm
            val out = ByteArrayOutputStream()
            t.compress(Bitmap.CompressFormat.JPEG, 68, out)
            return out.toByteArray()
        } catch (e: Throwable) { return null }
    }

    /** Encrypts [uri] into the vault, verifies it by reading it back, then (optionally) deletes the original.
     *  Returns the entry and whether the original was actually removed. */
    fun import(cr: ContentResolver, uri: Uri, move: Boolean, progress: (Float) -> Unit): Pair<FileEntry, Boolean> {
        val d = need()
        var name = "file"; var size = 0L; var mod = System.currentTimeMillis()
        cr.query(uri, null, null, null, null)?.use { c ->
            if (c.moveToFirst()) {
                c.getColumnIndex(OpenableColumns.DISPLAY_NAME).takeIf { it >= 0 }?.let { name = c.getString(it) ?: name }
                c.getColumnIndex(OpenableColumns.SIZE).takeIf { it >= 0 }?.let { if (!c.isNull(it)) size = c.getLong(it) }
                c.getColumnIndex(DocumentsContract.Document.COLUMN_LAST_MODIFIED).takeIf { it >= 0 }
                    ?.let { if (!c.isNull(it)) mod = c.getLong(it) }
            }
        }
        val mime = cr.getType(uri) ?: guessMime(name)
        val id = UUID.randomUUID().toString().replace("-", "")
        val key = Crypto.sub(d, "file:$id")
        val part = File(blobs, "$id.part"); val fin = File(blobs, "$id.blk")
        val enc: Enc = try {
            val e = cr.openInputStream(uri)!!.use { inp ->
                FileOutputStream(part).use { out ->
                    val r = Crypto.encryptStream(key, inp, out, id.toByteArray()) { done ->
                        if (size > 0) progress((done.toFloat() / size).coerceAtMost(1f))
                    }
                    out.flush(); out.fd.sync(); r
                }
            }
            val again = FileInputStream(part).use { Crypto.decryptStream(key, it, null, id.toByteArray()) }
            if (!again.contentEquals(e.sha)) throw IOException("Verification failed")
            Files.move(part.toPath(), fin.toPath(), StandardCopyOption.REPLACE_EXISTING)
            e
        } catch (t: Throwable) {
            part.delete(); throw t
        }
        val thumb = makeThumb(cr, uri, mime)
        val e = FileEntry(id, safe(name), enc.length, System.currentTimeMillis(), mod, mime, thumb)
        synchronized(this) { files += e; saveIndex() }
        val deleted = move && try { DocumentsContract.deleteDocument(cr, uri) } catch (x: Exception) { false }
        return e to deleted
    }

    private fun decryptTo(id: String, out: OutputStream) {
        val key = Crypto.sub(need(), "file:$id")
        FileInputStream(File(blobs, "$id.blk")).use { i -> out.use { Crypto.decryptStream(key, i, it, id.toByteArray()) } }
    }

    fun export(id: String, out: OutputStream) { entry(id); decryptTo(id, out) }

    fun openTemp(id: String, relockSec: Int): File {
        val e = entry(id)
        val dir = File(tmpRoot, Crypto.hex(Crypto.rnd(6))).apply { mkdirs() }
        val f = File(dir, safe(e.name))
        try { decryptTo(id, FileOutputStream(f)) } catch (t: Throwable) { shred(dir); throw t }
        synchronized(temps) { temps[dir.path] = System.currentTimeMillis() + relockSec * 1000L }
        return f
    }

    fun wipeTemps(force: Boolean) {
        val now = System.currentTimeMillis()
        synchronized(temps) {
            temps.entries.removeAll { (p, exp) -> if (force || exp <= now) { shred(File(p)); true } else false }
        }
        if (force) tmpRoot.listFiles()?.forEach { shred(it) }
    }

    @Synchronized fun deleteMany(ids: Set<String>) {
        need()
        files.removeAll { it.id in ids }
        saveIndex()
        ids.forEach { File(blobs, "$it.blk").delete() }
    }

    companion object {
        /** Best-effort overwrite then delete (flash storage can't fully guarantee erasure). */
        fun shred(f: File) {
            try {
                if (f.isDirectory) { f.listFiles()?.forEach { shred(it) }; f.delete(); return }
                if (f.isFile) {
                    RandomAccessFile(f, "rw").use { r ->
                        val z = ByteArray(1 shl 16); var left = r.length()
                        while (left > 0) { r.write(z, 0, minOf(left, z.size.toLong()).toInt()); left -= z.size }
                        r.fd.sync()
                    }
                }
            } catch (_: Exception) {}
            f.delete()
        }
    }
}
