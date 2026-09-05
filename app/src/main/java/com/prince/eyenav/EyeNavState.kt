package com.prince.eyenav

object EyeNavState {

    var faceDetected = false
        private set

    var landmarkCount = 0
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

    fun setError(
        message: String
    ) {

        errorMessage = message
    }
}
