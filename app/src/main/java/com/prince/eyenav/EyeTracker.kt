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

                        EyeNavState.update(
                            true,
                            landmarks.size
                        )

                    } else {

                        EyeNavState.update(
                            false,
                            0
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

    fun processFrame(
        image: MPImage,
        timestampMs: Long
    ) {

        faceLandmarker?.detectAsync(
            image,
            timestampMs
        )
    }

    fun close() {

        faceLandmarker?.close()

        faceLandmarker = null
    }
}
