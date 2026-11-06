package com.biolocky.app

import kotlin.math.sqrt

enum class FaceMode { Enroll, Unlock, Reveal }

object FaceMath {
    fun normalize(v: FloatArray): FloatArray {
        var s = 0f
        for (x in v) s += x * x
        val n = sqrt(s).coerceAtLeast(1e-9f)
        return FloatArray(v.size) { v[it] / n }
    }

    /** Cosine similarity of two L2-normalised embeddings. */
    fun cos(a: FloatArray, b: FloatArray): Float {
        var s = 0f
        for (i in a.indices) s += a[i] * b[i]
        return s
    }
}

/** tone: 0 = neutral, 1 = good, 2 = bad */
class FaceStep(val status: String, val progress: Float, val tone: Int, val done: Boolean = false)

/** Pure logic of a face session (no Android types), so it can be unit-tested.
 *  Enroll: collects [enrollCount] samples >= 0.7 s apart, rejecting frames that look like a different face.
 *  Verify: needs [needStreak] consecutive frames whose best template similarity >= [threshold].
 *  Threshold 0.62 was chosen from measurements on a small sample (see README); treat as experimental. */
class FaceFlow(private val mode: FaceMode, private val templates: List<FloatArray> = emptyList(),
               private val threshold: Float = THRESHOLD, private val needStreak: Int = 4,
               private val enrollCount: Int = 5) {
    companion object { const val THRESHOLD = 0.62f }

    val samples = mutableListOf<FloatArray>()
    private var streak = 0
    private var lastSample = -10_000L
    private val hints = listOf("Look straight at the camera", "Tilt your head slightly left",
        "Tilt your head slightly right", "Raise your chin a little", "Relax and smile")

    fun step(e: FloatArray?, now: Long): FaceStep =
        if (mode == FaceMode.Enroll) enroll(e, now) else verify(e)

    private fun verify(e: FloatArray?): FaceStep {
        if (e == null || templates.isEmpty()) { streak = 0; return FaceStep("Looking for your face…", 0f, 0) }
        val sim = templates.maxOf { FaceMath.cos(it, e) }
        if (sim >= threshold) {
            streak++
            val done = streak >= needStreak
            return FaceStep(if (done) "Recognised" else "Hold still…", streak.toFloat() / needStreak, 1, done)
        }
        streak = 0
        return FaceStep("Not recognised", 0f, 2)
    }

    private fun enroll(e: FloatArray?, now: Long): FaceStep {
        val n = samples.size
        val p = n.toFloat() / enrollCount
        if (e == null) return FaceStep("Face the camera", p, 0)
        if (now - lastSample < 700) return FaceStep(hints[minOf(n, hints.lastIndex)], p, 1)
        if (n > 0 && FaceMath.cos(samples[0], e) < 0.35f) return FaceStep("Only one face, please", p, 2)
        samples += e
        lastSample = now
        val done = samples.size >= enrollCount
        return FaceStep(if (done) "All set" else hints[minOf(samples.size, hints.lastIndex)],
            samples.size.toFloat() / enrollCount, 1, done)
    }
}
