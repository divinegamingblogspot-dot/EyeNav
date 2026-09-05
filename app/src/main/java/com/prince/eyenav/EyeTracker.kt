package com.prince.eyenav

import android.content.Context
import com.google.mediapipe.tasks.core.BaseOptions
import com.google.mediapipe.tasks.vision.core.RunningMode
import com.google.mediapipe.tasks.vision.facelandmarker.FaceLandmarker

class EyeTracker(
    private val context: Context
) {

    private var faceLandmarker: FaceLandmarker? = null

    fun setup() {

        val baseOptions =
            BaseOptions.builder()
                .setModelAssetPath("face_landmarker.task")
                .build()

        val options =
            FaceLandmarker.FaceLandmarkerOptions.builder()
                .setBaseOptions(baseOptions)
                .setRunningMode(RunningMode.LIVE_STREAM)
                .setNumFaces(1)
                .setMinFaceDetectionConfidence(0.5f)
                .setMinFacePresenceConfidence(0.5f)
                .setMinTrackingConfidence(0.5f)
                .setOutputFaceBlendshapes(false)
                .setOutputFacialTransformationMatrixes(false)
                .setResultListener { result, _ ->

                    val faces =
                        result.faceLandmarks()

                    if (faces.isNotEmpty()) {

                        val landmarks =
                            faces[0]

                        val count =
                            landmarks.size

                        EyeNavState.update(
                            true,
                            count
                        )
                    }

                }
                .setErrorListener { error ->

                    EyeNavState.setError(
                        error.message ?: "MediaPipe error"
                    )
                }
                .build()

        faceLandmarker =
            FaceLandmarker.createFromOptions(
                context,
                options
            )
    }

    fun close() {

        faceLandmarker?.close()

        faceLandmarker = null
    }
}
