package com.prince.eyenav

object EyeNavState {

    @Volatile var faceDetected = false
        private set

    @Volatile var landmarkCount = 0
        private set

    @Volatile var leftIrisX = 0f
        private set
    @Volatile var leftIrisY = 0f
        private set
    @Volatile var rightIrisX = 0f
        private set
    @Volatile var rightIrisY = 0f
        private set

    @Volatile var gazeX = 0f
        private set
    @Volatile var gazeY = 0f
        private set
    @Volatile var gazeHorizontal = 0f
        private set
    @Volatile var gazeVertical = 0f
        private set

    @Volatile var errorMessage: String? = null
        private set

    // Increments only when a new valid iris sample is produced.
    // The calibration UI uses this instead of sampling the same stale result repeatedly.
    @Volatile var sampleVersion: Long = 0L
        private set

    private var smoothX = 0f
    private var smoothY = 0f
    private var initialized = false

    @Synchronized
    fun reset() {
        faceDetected = false
        landmarkCount = 0
        leftIrisX = 0f
        leftIrisY = 0f
        rightIrisX = 0f
        rightIrisY = 0f
        gazeX = 0f
        gazeY = 0f
        gazeHorizontal = 0f
        gazeVertical = 0f
        errorMessage = null
        smoothX = 0f
        smoothY = 0f
        initialized = false
        // Advance the version so a newly started calibration cannot consume an old sample.
        sampleVersion++
    }

    @Synchronized
    fun update(detected: Boolean, count: Int) {
        faceDetected = detected
        landmarkCount = count
        errorMessage = null
        if (!detected) initialized = false
    }

    @Synchronized
    fun updateIris(leftX: Float, leftY: Float, rightX: Float, rightY: Float) {
        leftIrisX = leftX
        leftIrisY = leftY
        rightIrisX = rightX
        rightIrisY = rightY

        val rawX = (leftX + rightX) / 2f
        val rawY = (leftY + rightY) / 2f
        val smoothing = 0.20f

        if (!initialized) {
            smoothX = rawX
            smoothY = rawY
            initialized = true
        } else {
            smoothX += smoothing * (rawX - smoothX)
            smoothY += smoothing * (rawY - smoothY)
        }

        gazeX = smoothX.coerceIn(0f, 1f)
        gazeY = smoothY.coerceIn(0f, 1f)
        gazeHorizontal = ((gazeX - 0.5f) * 2f).coerceIn(-1f, 1f)
        gazeVertical = ((gazeY - 0.5f) * 2f).coerceIn(-1f, 1f)
        faceDetected = true
        errorMessage = null
        sampleVersion++
    }

    fun setError(message: String) {
        errorMessage = message
    }
}
