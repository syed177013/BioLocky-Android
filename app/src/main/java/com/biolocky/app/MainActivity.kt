package com.biolocky.app

import android.app.KeyguardManager
import android.content.Context
import android.Manifest
import android.content.Intent
import android.content.pm.PackageManager
import android.net.Uri
import android.os.Bundle
import android.security.keystore.KeyPermanentlyInvalidatedException
import android.view.WindowManager
import androidx.activity.ComponentActivity
import androidx.activity.SystemBarStyle
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.activity.result.contract.ActivityResultContract
import androidx.activity.result.contract.ActivityResultContracts
import androidx.activity.viewModels
import androidx.biometric.BiometricManager
import androidx.biometric.BiometricPrompt
import androidx.core.content.ContextCompat
import androidx.fragment.app.FragmentActivity
import javax.crypto.Cipher

class PickFiles : ActivityResultContract<Unit, List<Uri>>() {
    override fun createIntent(context: Context, input: Unit) = Intent(Intent.ACTION_OPEN_DOCUMENT)
        .addCategory(Intent.CATEGORY_OPENABLE).setType("*/*").putExtra(Intent.EXTRA_ALLOW_MULTIPLE, true)
        .addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION or Intent.FLAG_GRANT_WRITE_URI_PERMISSION)

    override fun parseResult(resultCode: Int, intent: Intent?): List<Uri> {
        if (resultCode != android.app.Activity.RESULT_OK || intent == null) return emptyList()
        val cd = intent.clipData
        return if (cd != null) (0 until cd.itemCount).map { cd.getItemAt(it).uri } else listOfNotNull(intent.data)
    }
}

class MainActivity : FragmentActivity() {
    private val vm: MainVM by viewModels()

    private val pickLauncher = registerForActivityResult(PickFiles()) { vm.importUris(it, contentResolver) }
    private val saveLauncher = registerForActivityResult(ActivityResultContracts.CreateDocument("*/*")) { u ->
        if (u != null) vm.exportSingle(u, contentResolver) else vm.cancelExport()
    }
    private val treeLauncher = registerForActivityResult(ActivityResultContracts.OpenDocumentTree()) { u ->
        if (u != null) vm.exportTree(u, contentResolver) else vm.cancelExport()
    }

    private var pendingFace: FaceMode? = null
    private val camPermission = registerForActivityResult(ActivityResultContracts.RequestPermission()) { ok ->
        val m = pendingFace; pendingFace = null
        if (ok && m != null) vm.startFace(m)
        else { vm.toast = "Camera permission is needed for face unlock"; if (m == FaceMode.Reveal) vm.askReveal() }
    }

    private fun faceStart(mode: FaceMode) {
        if (!vm.faceEngine.available) { vm.toast = "Face model missing from this build"; return }
        if (ContextCompat.checkSelfPermission(this, Manifest.permission.CAMERA) == PackageManager.PERMISSION_GRANTED) vm.startFace(mode)
        else { pendingFace = mode; vm.suppressLock = true; camPermission.launch(Manifest.permission.CAMERA) }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        // Hides content in screenshots and the recent-apps preview.
        window.setFlags(WindowManager.LayoutParams.FLAG_SECURE, WindowManager.LayoutParams.FLAG_SECURE)
        //window.clearFlags(WindowManager.LayoutParams.FLAG_SECURE)
        enableEdgeToEdge()
        val actions = Actions(
            pick = { pickLauncher.launch(Unit) },
            exportSingle = { saveLauncher.launch(vm.vault.entry(vm.pendingExport.first()).name) },
            exportTree = { treeLauncher.launch(null) },
            bioUnlock = this::bioUnlock, bioEnable = this::bioEnable, reveal = this::reveal, faceStart = this::faceStart,
            faceSupported = vm.faceEngine.available && packageManager.hasSystemFeature(PackageManager.FEATURE_CAMERA_FRONT), bioAvailable = this::bioAvailable)
        setContent { BlTheme { AppRoot(vm, actions) } }
    }

    override fun onStart() { super.onStart(); vm.onForeground() }
    override fun onStop() { super.onStop(); vm.onBackground() }

    private fun bioAvailable() = BiometricManager.from(this)
        .canAuthenticate(BiometricManager.Authenticators.BIOMETRIC_STRONG) == BiometricManager.BIOMETRIC_SUCCESS

    private fun prompt(title: String, cipher: Cipher, onNegative: (() -> Unit)? = null, onOk: (Cipher) -> Unit) {
        val info = BiometricPrompt.PromptInfo.Builder().setTitle(title).setNegativeButtonText("Use Passcode")
            .setAllowedAuthenticators(BiometricManager.Authenticators.BIOMETRIC_STRONG).build()
        val bp = BiometricPrompt(this, ContextCompat.getMainExecutor(this), object : BiometricPrompt.AuthenticationCallback() {
            override fun onAuthenticationSucceeded(result: BiometricPrompt.AuthenticationResult) {
                result.cryptoObject?.cipher?.let(onOk)
            }

            override fun onAuthenticationError(errorCode: Int, errString: CharSequence) {
                if (errorCode == BiometricPrompt.ERROR_NEGATIVE_BUTTON) onNegative?.invoke()
            }
        })
        bp.authenticate(info, BiometricPrompt.CryptoObject(cipher))
    }

    private fun bioUnlock() {
        if (!vm.bioOn || !vm.locked || !bioAvailable()) return
        val c = try { Keys.bioCipher(false, vm.vault.bioIv()) }
        catch (e: KeyPermanentlyInvalidatedException) { vm.invalidateBio(); return }
        catch (e: Exception) { vm.invalidateBio(); return }
        prompt("Unlock BioLocky", c) { vm.finishBioUnlock(it) }
    }

    /** Second authentication that un-blurs file previews: fingerprint if enabled, otherwise passcode. */
    private fun reveal() {
        if (vm.revealed) return
        if (vm.bioOn && bioAvailable()) {
            val c = try { Keys.bioCipher(false, vm.vault.bioIv()) } catch (e: Exception) { revealFallback(); return }
            prompt("Reveal previews", c, { revealFallback() }) { vm.revealed = true }
        } else revealFallback()
    }

    /** No fingerprint: try the (experimental) face check if it is usable, otherwise ask for the passcode. */
    private fun revealFallback() {
        if (vm.faceReady && packageManager.hasSystemFeature(PackageManager.FEATURE_CAMERA_FRONT)) faceStart(FaceMode.Reveal)
        else vm.askReveal()
    }

    private fun bioEnable() {
        try { prompt("Enable fingerprint unlock", Keys.bioCipher(true)) { vm.finishBioEnable(it) } }
        catch (e: Exception) { vm.toast = "Couldn’t enable fingerprint: ${e.message}" }
    }
}
