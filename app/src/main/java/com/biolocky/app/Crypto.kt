package com.biolocky.app

import java.io.InputStream
import java.io.OutputStream
import java.security.MessageDigest
import java.security.SecureRandom
import javax.crypto.Cipher
import javax.crypto.Mac
import javax.crypto.SecretKeyFactory
import javax.crypto.spec.GCMParameterSpec
import javax.crypto.spec.PBEKeySpec
import javax.crypto.spec.SecretKeySpec

class Enc(val sha: ByteArray, val length: Long)

/** AES-256-GCM primitives. File format "BLK1": magic | 8-byte nonce prefix | chunks.
 *  Every chunk is sealed with nonce = prefix||counter and AAD = file-id||final-flag, so
 *  reordering, truncation and cross-file swaps are all detected. */
object Crypto {
    const val CHUNK = 1 shl 20
    private const val TAG = 16
    private val MAGIC = "BLK1".toByteArray()
    private val rng = SecureRandom()

    fun rnd(n: Int) = ByteArray(n).also { rng.nextBytes(it) }
    fun hex(b: ByteArray) = b.joinToString("") { "%02x".format(it) }

    fun pbkdf2(secret: String, salt: ByteArray, iterations: Int = 200_000): ByteArray {
        val spec = PBEKeySpec(secret.toCharArray(), salt, iterations, 256)
        try {
            return SecretKeyFactory.getInstance("PBKDF2WithHmacSHA256").generateSecret(spec).encoded
        } finally {
            spec.clearPassword()
        }
    }

    fun sub(master: ByteArray, label: String): ByteArray {
        val m = Mac.getInstance("HmacSHA256")
        m.init(SecretKeySpec(master, "HmacSHA256"))
        return m.doFinal(("blk-sub:$label").toByteArray())
    }

    private fun cipher(mode: Int, key: ByteArray, nonce: ByteArray, aad: ByteArray): Cipher {
        val c = Cipher.getInstance("AES/GCM/NoPadding")
        c.init(mode, SecretKeySpec(key, "AES"), GCMParameterSpec(128, nonce))
        c.updateAAD(aad)
        return c
    }

    fun seal(key: ByteArray, data: ByteArray, aad: ByteArray = ByteArray(0)): ByteArray {
        val n = rnd(12)
        return n + cipher(Cipher.ENCRYPT_MODE, key, n, aad).doFinal(data)
    }

    fun unseal(key: ByteArray, blob: ByteArray, aad: ByteArray = ByteArray(0)): ByteArray =
        cipher(Cipher.DECRYPT_MODE, key, blob.copyOfRange(0, 12), aad).doFinal(blob, 12, blob.size - 12)

    private fun fill(i: InputStream, b: ByteArray): Int {
        var n = 0
        while (n < b.size) {
            val r = i.read(b, n, b.size - n)
            if (r < 0) break
            n += r
        }
        return n
    }

    private fun nonce(prefix: ByteArray, i: Int) =
        prefix + byteArrayOf((i ushr 24).toByte(), (i ushr 16).toByte(), (i ushr 8).toByte(), i.toByte())

    fun encryptStream(key: ByteArray, input: InputStream, out: OutputStream, aad: ByteArray,
                      progress: (Long) -> Unit = {}): Enc {
        val prefix = rnd(8)
        out.write(MAGIC); out.write(prefix)
        val md = MessageDigest.getInstance("SHA-256")
        var cur = ByteArray(CHUNK); var nxt = ByteArray(CHUNK)
        var curN = fill(input, cur)
        var i = 0; var done = 0L
        while (true) {
            val nxtN = fill(input, nxt)
            val final = nxtN == 0
            val c = cipher(Cipher.ENCRYPT_MODE, key, nonce(prefix, i), aad + byteArrayOf(if (final) 1 else 0))
            out.write(c.doFinal(cur, 0, curN))
            md.update(cur, 0, curN)
            done += curN; i++
            progress(done)
            if (final) return Enc(md.digest(), done)
            val t = cur; cur = nxt; nxt = t; curN = nxtN
        }
    }

    /** Decrypts to [out] (null = verify only). Throws on any tampering or truncation. */
    fun decryptStream(key: ByteArray, input: InputStream, out: OutputStream?, aad: ByteArray): ByteArray {
        val head = ByteArray(12)
        if (fill(input, head) != 12 || !head.copyOfRange(0, 4).contentEquals(MAGIC))
            throw IllegalArgumentException("Not a BioLocky file")
        val prefix = head.copyOfRange(4, 12)
        val md = MessageDigest.getInstance("SHA-256")
        var cur = ByteArray(CHUNK + TAG); var nxt = ByteArray(CHUNK + TAG)
        var curN = fill(input, cur)
        var i = 0
        while (true) {
            val nxtN = fill(input, nxt)
            val final = nxtN == 0
            val c = cipher(Cipher.DECRYPT_MODE, key, nonce(prefix, i), aad + byteArrayOf(if (final) 1 else 0))
            val plain = c.doFinal(cur, 0, curN)
            md.update(plain); out?.write(plain)
            i++
            if (final) return md.digest()
            val t = cur; cur = nxt; nxt = t; curN = nxtN
        }
    }
}
