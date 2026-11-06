# BioLocky for Android  `2.0.0-rc1`


An encrypted file vault for your phone. Unlock with a **6-digit passcode** or your **fingerprint**.
Files you add are encrypted with AES-256 and moved into the app's private storage.

## Features
- **AES-256-GCM** streaming encryption. Large files are never loaded fully into memory, and tampering is detected.
- **Keys protected by the Android Keystore.** The passcode can't be brute-forced off the device.
- **Fingerprint unlock** (optional), backed by a Keystore key that needs a fingerprint on every use.
- **Move, don't copy.** Originals are deleted once the encrypted copy has been read back and verified.
- **Auto-lock** after leaving the app. Opened files are wiped after a timer, on lock, and on restart.
- **Recovery code** shown once at setup.
- **Face unlock (experimental).** Optional, on-device (ML Kit detection + MobileFaceNet). Read the security notes first.
- **Two-step reveal.** After unlocking, file previews stay blurred until you authenticate again (fingerprint or passcode).
- Real previews for images, videos, PDFs and album art, with type tiles for everything else.
- **Export a copy** (original stays encrypted) or **move out of the vault**.
- Multi-select, delete, sorting (name, date, type, size), search.
- Screenshots and the recent-apps preview are blocked (`FLAG_SECURE`).

## Build
1. Open this folder in **Android Studio** (Koala or newer) and let Gradle sync.
2. Plug in a phone (USB debugging on) and press **Run**, or use **Build ▸ Build APK(s)**.
3. For a release APK: **Build ▸ Generate Signed App Bundle / APK**.

Requires Android 8.0+ (API 26).

## App icon
The included launcher icon is a placeholder. Replace it in Android Studio via
**File ▸ New ▸ Image Asset** (or edit `res/drawable/ic_launcher_foreground.xml`).

## Security notes
- **Face unlock is experimental.** The face check is software, not a hardware-enforced biometric like the fingerprint, and
  there is no liveness detection, so a photo or video of you may fool it. It pauses after 3 misses and stops working
  72 hours after your last passcode/fingerprint unlock. Keep a strong passcode and treat fingerprint as the safer shortcut.
  The match threshold (0.62) was tuned on a very small sample; adjust `FaceFlow.THRESHOLD` after testing on real devices.
- The bundled face model (`assets/face_model.tflite`, MobileFaceNet) comes from a public GitHub repository. Check its
  licence before you publish or distribute the app.
- No passcode and no recovery code means no recovery. There is no backdoor, and cloud backup is disabled.
- The passcode is 6 digits. Attempts are throttled (lockout grows after 5 wrong tries), and the key wrapping is bound to this device.
- Adding a fingerprint to the phone invalidates fingerprint unlock on purpose. Re-enable it in Settings.
- "Move on import" can only delete originals where Android allows it (e.g. Downloads, internal storage). Some providers, such as Google Photos, refuse, and the app tells you.
- Opening a file hands a decrypted temporary copy to another app. It is wiped after the re-lock time.
- Secure-delete is best-effort on flash storage.

## Layout
```
Crypto.kt     AES-GCM streaming, PBKDF2, key derivation
Keys.kt       Android Keystore keys (device-bound + fingerprint-bound)
Vault.kt      keyring, sealed index, import/export, temp-file cleanup
MainVM.kt     state, locking, import/export/open flows
Ui.kt         theme, passcode keypad, dialogs
Screens.kt    lock, files, settings
MainActivity  pickers, biometric prompt
```
