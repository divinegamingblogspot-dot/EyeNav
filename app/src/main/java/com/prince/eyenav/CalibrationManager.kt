package com.prince.eyenav

data class CalibrationPoint(
    val gazeX: Float,
    val gazeY: Float,
    val screenX: Float,
    val screenY: Float
)

object CalibrationManager {

    private val points = mutableListOf<CalibrationPoint>()

    private val targetPositions =
        listOf(
            0.10f to 0.10f,
            0.50f to 0.10f,
            0.90f to 0.10f,

            0.10f to 0.50f,
            0.50f to 0.50f,
            0.90f to 0.50f,

            0.10f to 0.90f,
            0.50f to 0.90f,
            0.90f to 0.90f
        )

    var currentTarget = 0
        private set

    var isCalibrated = false
        private set

    var minGazeX = 0.3f
        private set

    var maxGazeX = 0.7f
        private set

    var minGazeY = 0.3f
        private set

    var maxGazeY = 0.7f
        private set

    fun reset() {

        points.clear()

        currentTarget = 0

        isCalibrated = false
    }

    fun target(): Pair<Float, Float> {

        return targetPositions[
            currentTarget.coerceIn(
                0,
                targetPositions.lastIndex
            )
        ]
    }

    fun addPoint(
        gazeX: Float,
        gazeY: Float,
        screenWidth: Float,
        screenHeight: Float
    ) {

        val target =
            target()

        points.add(
            CalibrationPoint(
                gazeX,
                gazeY,
                target.first * screenWidth,
                target.second * screenHeight
            )
        )

        currentTarget++

        if (
            currentTarget >=
            targetPositions.size
        ) {

            calculateCalibration()
        }
    }

    private fun calculateCalibration() {

        if (points.size < 9) {
            return
        }

        minGazeX =
            points.minOf {
                it.gazeX
            }

        maxGazeX =
            points.maxOf {
                it.gazeX
            }

        minGazeY =
            points.minOf {
                it.gazeY
            }

        maxGazeY =
            points.maxOf {
                it.gazeY
            }

        isCalibrated = true
    }

    fun screenPosition(
        gazeX: Float,
        gazeY: Float,
        width: Float,
        height: Float
    ): Pair<Float, Float> {

        val rangeX =
            (maxGazeX - minGazeX)
                .coerceAtLeast(0.01f)

        val rangeY =
            (maxGazeY - minGazeY)
                .coerceAtLeast(0.01f)

        val normalizedX =
            ((gazeX - minGazeX) / rangeX)
                .coerceIn(0f, 1f)

        val normalizedY =
            ((gazeY - minGazeY) / rangeY)
                .coerceIn(0f, 1f)

        return Pair(
            normalizedX * width,
            normalizedY * height
        )
    }
}
