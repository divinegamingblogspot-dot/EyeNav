package com.prince.eyenav

import kotlin.math.max
import kotlin.math.min

object EyeNavState {

    var faceDetected = false
        private set

    var landmarkCount = 0
        private set

    var leftIrisX = 0f
        private set

    var leftIrisY = 0f
        private set

    var rightIrisX = 0f
        private set

    var rightIrisY = 0f
        private set

    var gazeX = 0f
        private set

    var gazeY = 0f
        private set

    var gazeHorizontal = 0f
        private set

    var gazeVertical = 0f
        private set

    var errorMessage: String? = null
        private set

    fun update(
        detected: Boolean,
        count: Int
    ) {
        faceDetected = detected
        landmarkCount = count
        errorMessage = null
    }

    fun updateIris(
        leftX: Float,
        leftY: Float,
        rightX: Float,
        rightY: Float
    ) {

        leftIrisX = leftX
        leftIrisY = leftY

        rightIrisX = rightX
        rightIrisY = rightY

        gazeX = (leftX + rightX) / 2f
        gazeY = (leftY + rightY) / 2f

        /*
         * Convert MediaPipe coordinates
         * into a simple -1 to +1 range.
         *
         * 0.5 is approximately the centre.
         */

        gazeHorizontal =
            ((gazeX - 0.5f) * 2f)
                .coerceIn(-1f, 1f)

        gazeVertical =
            ((gazeY - 0.5f) * 2f)
                .coerceIn(-1f, 1f)
    }

    fun setError(
        message: String
    ) {
        errorMessage = message
    }
}
