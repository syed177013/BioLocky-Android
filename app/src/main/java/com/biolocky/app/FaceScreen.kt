package com.biolocky.app

import android.graphics.Bitmap
import android.graphics.Matrix
import android.os.Handler
import android.os.Looper
import android.os.SystemClock
import androidx.activity.compose.BackHandler
import androidx.camera.core.CameraSelector
import androidx.camera.core.ImageAnalysis
import androidx.camera.core.Preview
import androidx.camera.lifecycle.ProcessCameraProvider
import androidx.camera.view.PreviewView
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.TextButton
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.viewinterop.AndroidView
import androidx.core.content.ContextCompat
import androidx.lifecycle.LifecycleOwner
import kotlinx.coroutines.delay
import java.util.concurrent.Executors

/** Full-screen camera overlay for enrolling or verifying a face (EXPERIMENTAL). */
@Composable
fun FaceScreen(vm: MainVM, mode: FaceMode) {
    val p = LocalPal.current
    val ctx = LocalContext.current
    val owner = ctx as LifecycleOwner
    val flow = remember(mode) { FaceFlow(mode, if (mode == FaceMode.Enroll) emptyList() else vm.faceTemplates) }
    var step by remember { mutableStateOf(FaceStep("Starting camera…", 0f, 0)) }
    var finished by remember { mutableStateOf(false) }
    val main = remember { Handler(Looper.getMainLooper()) }
    val executor = remember { Executors.newSingleThreadExecutor() }

    fun end(ok: Boolean) {
        if (finished) return
        finished = true
        vm.faceFinished(mode, ok, flow.samples.toList())
    }

    DisposableEffect(Unit) {
        onDispose {
            executor.shutdown()
            try { ProcessCameraProvider.getInstance(ctx).get().unbindAll() } catch (_: Exception) {}
        }
    }
    BackHandler { if (!finished) { finished = true; vm.faceCancel(mode) } }
    LaunchedEffect(Unit) { delay(if (mode == FaceMode.Enroll) 60_000 else 20_000); end(false) }

    val ring = when (step.tone) { 1 -> p.accent; 2 -> p.danger; else -> p.line }
    Column(Modifier.fillMaxSize().background(p.bg).clickable(enabled = false) {}.systemBarsPadding().padding(24.dp),
        horizontalAlignment = Alignment.CenterHorizontally) {
        Spacer(Modifier.weight(1f))
        T(if (mode == FaceMode.Enroll) "Set Up Face Unlock" else "Face Unlock", 24, FontWeight.SemiBold)
        Spacer(Modifier.height(8.dp))
        Badge("EXPERIMENTAL", p.danger)
        Spacer(Modifier.height(28.dp))
        Box(Modifier.size(280.dp).border(4.dp, ring, CircleShape).padding(6.dp).clip(CircleShape).background(p.field)) {
            AndroidView(modifier = Modifier.fillMaxSize(), factory = { c ->
                val pv = PreviewView(c).apply {
                    scaleType = PreviewView.ScaleType.FILL_CENTER
                    implementationMode = PreviewView.ImplementationMode.COMPATIBLE   // needed so the circle clip works
                }
                val future = ProcessCameraProvider.getInstance(c)
                future.addListener({
                    try {
                        val provider = future.get()
                        val preview = Preview.Builder().build().also { it.setSurfaceProvider(pv.surfaceProvider) }
                        val analysis = ImageAnalysis.Builder()
                            .setBackpressureStrategy(ImageAnalysis.STRATEGY_KEEP_ONLY_LATEST).build()
                        var last = 0L
                        analysis.setAnalyzer(executor) { proxy ->
                            try {
                                val now = SystemClock.elapsedRealtime()
                                if (!finished && now - last >= 200) {
                                    last = now
                                    val raw = proxy.toBitmap()
                                    val rot = proxy.imageInfo.rotationDegrees
                                    val upright = if (rot == 0) raw else Bitmap.createBitmap(
                                        raw, 0, 0, raw.width, raw.height, Matrix().apply { postRotate(rot.toFloat()) }, true)
                                    val s = flow.step(vm.faceEngine.embed(upright), now)
                                    main.post { if (!finished) { step = s; if (s.done) end(true) } }
                                }
                            } catch (t: Throwable) {
                                main.post { step = FaceStep("Camera error: ${t.message}", 0f, 2) }
                            } finally { proxy.close() }
                        }
                        provider.unbindAll()
                        provider.bindToLifecycle(owner, CameraSelector.DEFAULT_FRONT_CAMERA, preview, analysis)
                    } catch (t: Throwable) {
                        step = FaceStep("Couldn’t start the camera", 0f, 2)
                    }
                }, ContextCompat.getMainExecutor(c))
                pv
            })
        }
        Spacer(Modifier.height(24.dp))
        T(step.status, 18, FontWeight.Medium, align = TextAlign.Center, modifier = Modifier.height(28.dp))
        Spacer(Modifier.height(12.dp))
        LinearProgressIndicator({ step.progress }, Modifier.width(220.dp), color = p.accent, trackColor = p.line)
        Spacer(Modifier.weight(1f))
        TextButton(onClick = { if (!finished) { finished = true; vm.faceCancel(mode) } }) {
            T(if (mode == FaceMode.Enroll) "Cancel" else "Use Passcode", 16, color = p.accent)
        }
        if (mode == FaceMode.Enroll) T("Use even lighting. A photo of you may fool this feature.", 12, color = p.muted, align = TextAlign.Center)
    }
}
