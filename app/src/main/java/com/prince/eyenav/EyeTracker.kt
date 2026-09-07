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

    @Synchronized
    fun setup() {
        close()

        val baseOptions = BaseOptions.builder()
            .setModelAssetPath("face_landmarker.task")
            .build()

        val options = FaceLandmarker.FaceLandmarkerOptions.builder()
            .setBaseOptions(baseOptions)
            // IMAGE mode makes every camera frame self-contained. There is no asynchronous
            // MediaPipe callback racing CameraX teardown/recalibration.
            .setRunningMode(RunningMode.IMAGE)
            .setNumFaces(1)
            .setMinFaceDetectionConfidence(0.5f)
            .setMinFacePresenceConfidence(0.5f)
            .setMinTrackingConfidence(0.5f)
            .setOutputFaceBlendshapes(false)
            .setOutputFacialTransformationMatrixes(false)
            .build()

        faceLandmarker = FaceLandmarker.createFromOptions(context, options)
        closed.set(false)
    }

    /** Process one frame on the CameraX analyzer thread. */
    @Synchronized
    fun processFrame(image: MPImage): Boolean {
        if (closed.get()) return false

        return try {
            val result = faceLandmarker?.detect(image) ?: return false
            val faces = result.faceLandmarks()
            if (faces.isEmpty()) {
                EyeNavState.update(false, 0)
                return true
            }

            val landmarks = faces[0]
            EyeNavState.update(true, landmarks.size)

            if (landmarks.size >= 478) {
                val leftIrisX = averageX(landmarks, intArrayOf(474, 475, 476, 477))
                val leftIrisY = averageY(landmarks, intArrayOf(474, 475, 476, 477))
                val rightIrisX = averageX(landmarks, intArrayOf(469, 470, 471, 472))
                val rightIrisY = averageY(landmarks, intArrayOf(469, 470, 471, 472))

                // Both eyes use the nose-to-temple direction consistently: 0 = toward
                // the temple, 1 = toward the nose. This gives one stable horizontal axis.
                val leftX = normalizeBetween(leftIrisX, landmarks[33].x(), landmarks[133].x())
                val rightX = normalizeBetween(rightIrisX, landmarks[362].x(), landmarks[263].x())
                val leftY = normalizeBetween(leftIrisY, landmarks[159].y(), landmarks[145].y())
                val rightY = normalizeBetween(rightIrisY, landmarks[386].y(), landmarks[374].y())

                EyeNavState.updateIris(leftX, leftY, rightX, rightY)
            } else {
                EyeNavState.update(false, landmarks.size)
            }
            true
        } catch (e: Exception) {
            if (!closed.get()) EyeNavState.setError(e.message ?: "Face tracking error")
            false
        }
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
    fun close() {
        if (closed.getAndSet(true)) return
        runCatching { faceLandmarker?.close() }
        faceLandmarker = null
    }
}
