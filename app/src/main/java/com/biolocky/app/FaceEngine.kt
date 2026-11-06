package com.biolocky.app

import android.content.Context
import android.graphics.Bitmap
import com.google.android.gms.tasks.Tasks
import com.google.mlkit.vision.common.InputImage
import com.google.mlkit.vision.face.FaceDetection
import com.google.mlkit.vision.face.FaceDetectorOptions
import org.tensorflow.lite.Interpreter
import java.io.FileInputStream
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.nio.channels.FileChannel

/** On-device face embedding: ML Kit finds the face, MobileFaceNet (TFLite, 112x112 -> 192 floats) describes it.
 *  Heavy parts are created lazily on first use and run on the caller's (background) thread. */
class FaceEngine(private val ctx: Context) {
    companion object { const val MODEL = "face_model.tflite"; const val SIZE = 112 }

    val available: Boolean
        get() = try { ctx.assets.list("")?.contains(MODEL) == true } catch (e: Exception) { false }

    private val detector by lazy {
        FaceDetection.getClient(FaceDetectorOptions.Builder()
            .setPerformanceMode(FaceDetectorOptions.PERFORMANCE_MODE_FAST).setMinFaceSize(0.25f).build())
    }
    private val interp by lazy {
        val fd = ctx.assets.openFd(MODEL)
        val buf = FileInputStream(fd.fileDescriptor).channel.map(FileChannel.MapMode.READ_ONLY, fd.startOffset, fd.declaredLength)
        Interpreter(buf, Interpreter.Options().setNumThreads(2))
    }
    private val dim by lazy { interp.getOutputTensor(0).shape().last() }
    private val input = ByteBuffer.allocateDirect(SIZE * SIZE * 3 * 4).order(ByteOrder.nativeOrder())
    private val pixels = IntArray(SIZE * SIZE)

    /** Returns a normalised embedding of the largest face in an upright frame, or null if no face. */
    @Synchronized
    fun embed(frame: Bitmap): FloatArray? {
        val faces = Tasks.await(detector.process(InputImage.fromBitmap(frame, 0)))
        val f = faces.maxByOrNull { it.boundingBox.width() * it.boundingBox.height() } ?: return null
        val b = f.boundingBox
        val half = maxOf(b.width(), b.height()) * 1.08f / 2f
        val x0 = (b.exactCenterX() - half).toInt().coerceIn(0, frame.width - 1)
        val y0 = (b.exactCenterY() - half).toInt().coerceIn(0, frame.height - 1)
        val x1 = (b.exactCenterX() + half).toInt().coerceIn(x0 + 1, frame.width)
        val y1 = (b.exactCenterY() + half).toInt().coerceIn(y0 + 1, frame.height)
        val crop = Bitmap.createBitmap(frame, x0, y0, x1 - x0, y1 - y0)
        Bitmap.createScaledBitmap(crop, SIZE, SIZE, true).getPixels(pixels, 0, SIZE, 0, 0, SIZE, SIZE)
        input.rewind()
        for (p in pixels) {
            input.putFloat((((p shr 16) and 0xFF) - 128f) / 128f)   // R
            input.putFloat((((p shr 8) and 0xFF) - 128f) / 128f)    // G
            input.putFloat(((p and 0xFF) - 128f) / 128f)            // B
        }
        val out = Array(1) { FloatArray(dim) }
        interp.run(input, out)
        return FaceMath.normalize(out[0])
    }
}
