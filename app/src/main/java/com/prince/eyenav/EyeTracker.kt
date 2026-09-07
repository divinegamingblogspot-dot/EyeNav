package com.prince.eyenav

import android.content.Context
import com.google.mediapipe.framework.image.MPImage
import com.google.mediapipe.tasks.core.BaseOptions
import com.google.mediapipe.tasks.vision.core.RunningMode
import com.google.mediapipe.tasks.vision.facelandmarker.FaceLandmarker
import java.util.concurrent.atomic.AtomicBoolean

class EyeTracker(private val context: Context) {

    private var faceLandmarker: FaceLandmarker? = null
    private val closed = AtomicBoolean(true)
    private var lastTimestampMs = 0L

    @Synchronized
    fun setup() {
        close()
        closed.set(false)
        lastTimestampMs = 0L

        val baseOptions = BaseOptions.builder()
            .setModelAssetPath("face_landmarker.task")
            .build()

        // LIVE_STREAM is intentionally restored here. This was the responsive pipeline that
        // produced continuous gaze updates. IMAGE mode made the tracking loop effectively
        // synchronous and introduced the current "stuck" behaviour on-device.
        val options = FaceLandmarker.FaceLandmarkerOptions.builder()
            .setBaseOptions(baseOptions)
            .setRunningMode(RunningMode.LIVE_STREAM)
            .setNumFaces(1)
            .setMinFaceDetectionConfidence(0.5f)
            .setMinFacePresenceConfidence(0.5f)
            .setMinTrackingConfidence(0.5f)
            .setOutputFaceBlendshapes(false)
            .setOutputFacialTransformationMatrixes(false)
            .setResultListener { result, _ ->
                if (closed.get()) return@setResultListener

                val faces = result.faceLandmarks()
                if (faces.isEmpty()) {
                    EyeNavState.update(false, 0)
                    return@setResultListener
                }

                val landmarks = faces[0]
                if (landmarks.size < 478) {
                    EyeNavState.update(false, landmarks.size)
                    return@setResultListener
                }

                val leftIrisX = averageX(landmarks, intArrayOf(474, 475, 476, 477))
                val leftIrisY = averageY(landmarks, intArrayOf(474, 475, 476, 477))
                val rightIrisX = averageX(landmarks, intArrayOf(469, 470, 471, 472))
                val rightIrisY = averageY(landmarks, intArrayOf(469, 470, 471, 472))

                val leftX = normalizeBetween(leftIrisX, landmarks[33].x(), landmarks[133].x())
                val rightX = normalizeBetween(rightIrisX, landmarks[362].x(), landmarks[263].x())
                val leftY = normalizeBetween(leftIrisY, landmarks[159].y(), landmarks[145].y())
                val rightY = normalizeBetween(rightIrisY, landmarks[386].y(), landmarks[374].y())

                EyeNavState.update(true, landmarks.size)
                EyeNavState.updateIris(leftX, leftY, rightX, rightY)
            }
            .setErrorListener { error ->
                if (!closed.get()) EyeNavState.setError(error.message ?: "MediaPipe error")
            }
            .build()

        faceLandmarker = FaceLandmarker.createFromOptions(context, options)
    }

    private fun averageX(landmarks: List<com.google.mediapipe.tasks.components.containers.NormalizedLandmark>, indices: IntArray): Float =
        indices.sumOf { landmarks[it].x().toDouble() }.toFloat() / indices.size

    private fun averageY(landmarks: List<com.google.mediapipe.tasks.components.containers.NormalizedLandmark>, indices: IntArray): Float =
        indices.sumOf { landmarks[it].y().toDouble() }.toFloat() / indices.size

    private fun normalizeBetween(value: Float, a: Float, b: Float): Float {
        val denominator = b - a
        if (kotlin.math.abs(denominator) < 0.00001f) return 0.5f
        return ((value - a) / denominator).coerceIn(0f, 1f)
    }

    @Synchronized
    fun processFrame(image: MPImage, timestampMs: Long) {
        if (closed.get()) return
        val safeTimestamp = maxOf(timestampMs, lastTimestampMs + 1L)
        lastTimestampMs = safeTimestamp
        try {
            faceLandmarker?.detectAsync(image, safeTimestamp)
        } catch (e: Exception) {
            if (!closed.get()) EyeNavState.setError(e.message ?: "Face tracking error")
        }
    }

    @Synchronized
    fun close() {
        closed.set(true)
        runCatching { faceLandmarker?.close() }
        faceLandmarker = null
    }
}
