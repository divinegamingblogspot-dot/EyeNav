package com.prince.eyenav

import android.content.Context
import com.google.mediapipe.framework.image.MPImage
import com.google.mediapipe.tasks.core.BaseOptions
import com.google.mediapipe.tasks.vision.core.RunningMode
import com.google.mediapipe.tasks.vision.facelandmarker.FaceLandmarker

class EyeTracker(
    private val context: Context
) {

    private var faceLandmarker: FaceLandmarker? = null

    fun setup() {
        val baseOptions = BaseOptions.builder()
            .setModelAssetPath("face_landmarker.task")
            .build()

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
                val faces = result.faceLandmarks()
                if (faces.isEmpty()) {
                    EyeNavState.update(false, 0)
                    return@setResultListener
                }

                val landmarks = faces[0]
                EyeNavState.update(true, landmarks.size)

                if (landmarks.size >= 478) {
                    // Use iris position relative to each eye's own corners/lids.
                    // This removes most head-position/face-size movement from the gaze signal.
                    val leftIrisX = averageX(landmarks, intArrayOf(474, 475, 476, 477))
                    val leftIrisY = averageY(landmarks, intArrayOf(474, 475, 476, 477))
                    val rightIrisX = averageX(landmarks, intArrayOf(469, 470, 471, 472))
                    val rightIrisY = averageY(landmarks, intArrayOf(469, 470, 471, 472))

                    val leftX = normalizeBetween(leftIrisX, landmarks[33].x(), landmarks[133].x())
                    val rightX = normalizeBetween(rightIrisX, landmarks[362].x(), landmarks[263].x())
                    val leftY = normalizeBetween(leftIrisY, landmarks[159].y(), landmarks[145].y())
                    val rightY = normalizeBetween(rightIrisY, landmarks[386].y(), landmarks[374].y())

                    EyeNavState.updateIris(leftX, leftY, rightX, rightY)
                }
            }
            .setErrorListener { error ->
                EyeNavState.setError(error.message ?: "MediaPipe error")
            }
            .build()

        faceLandmarker = FaceLandmarker.createFromOptions(context, options)
    }

    private fun averageX(landmarks: List<com.google.mediapipe.tasks.components.containers.NormalizedLandmark>, indices: IntArray): Float =
        indices.map { landmarks[it].x() }.average().toFloat()

    private fun averageY(landmarks: List<com.google.mediapipe.tasks.components.containers.NormalizedLandmark>, indices: IntArray): Float =
        indices.map { landmarks[it].y() }.average().toFloat()

    private fun normalizeBetween(value: Float, a: Float, b: Float): Float {
        val denominator = b - a
        if (kotlin.math.abs(denominator) < 0.00001f) return 0.5f
        return ((value - a) / denominator).coerceIn(0f, 1f)
    }

    fun processFrame(image: MPImage, timestampMs: Long) {
        faceLandmarker?.detectAsync(image, timestampMs)
    }

    fun close() {
        faceLandmarker?.close()
        faceLandmarker = null
    }
}
