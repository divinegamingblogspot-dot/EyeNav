package com.prince.eyenav

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

    private var smoothX = 0f
    private var smoothY = 0f

    private var initialized = false

    fun update(
        detected: Boolean,
        count: Int
    ) {

        faceDetected = detected
        landmarkCount = count
        errorMessage = null

        if (!detected) {
            initialized = false
        }
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

        val rawX =
            (leftX + rightX) / 2f

        val rawY =
            (leftY + rightY) / 2f

        /*
         * Exponential moving average.
         *
         * Smaller number = smoother but slower.
         * Larger number = faster but more jitter.
         */

        val smoothing = 0.20f

        if (!initialized) {

            smoothX = rawX
            smoothY = rawY

            initialized = true

        } else {

            smoothX =
                smoothX +
                smoothing * (rawX - smoothX)

            smoothY =
                smoothY +
                smoothing * (rawY - smoothY)
        }

        gazeX = smoothX
        gazeY = smoothY

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
