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

                    if (faces.isEmpty()) {

                        EyeNavState.update(
                            false,
                            0
                        )

                        return@setResultListener
                    }

                    val landmarks =
                        faces[0]

                    EyeNavState.update(
                        true,
                        landmarks.size
                    )

                    if (landmarks.size >= 478) {

                        /*
                         * MediaPipe iris landmarks:
                         *
                         * Left iris:
                         * 474, 475, 476, 477
                         *
                         * Right iris:
                         * 469, 470, 471, 472
                         */

                        val leftIrisIndices =
                            intArrayOf(
                                474,
                                475,
                                476,
                                477
                            )

                        val rightIrisIndices =
                            intArrayOf(
                                469,
                                470,
                                471,
                                472
                            )

                        var leftX = 0f
                        var leftY = 0f

                        for (index in leftIrisIndices) {

                            leftX +=
                                landmarks[index].x()

                            leftY +=
                                landmarks[index].y()
                        }

                        leftX /=
                            leftIrisIndices.size

                        leftY /=
                            leftIrisIndices.size

                        var rightX = 0f
                        var rightY = 0f

                        for (index in rightIrisIndices) {

                            rightX +=
                                landmarks[index].x()

                            rightY +=
                                landmarks[index].y()
                        }

                        rightX /=
                            rightIrisIndices.size

                        rightY /=
                            rightIrisIndices.size

                        EyeNavState.updateIris(
                            leftX,
                            leftY,
                            rightX,
                            rightY
                        )
                    }
                }
                .setErrorListener { error ->

                    EyeNavState.setError(
                        error.message
                            ?: "MediaPipe error"
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
