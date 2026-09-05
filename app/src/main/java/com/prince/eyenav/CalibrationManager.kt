package com.prince.eyenav

data class CalibrationPoint(
    val gazeX: Float,
    val gazeY: Float,
    val screenX: Float,
    val screenY: Float
)

object CalibrationManager {

    private val points =
        mutableListOf<CalibrationPoint>()

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

                gazeX = gazeX,

                gazeY = gazeY,

                screenX =
                    target.first *
                    screenWidth,

                screenY =
                    target.second *
                    screenHeight
            )
        )

        currentTarget++

        if (
            currentTarget >=
            targetPositions.size
        ) {

            isCalibrated = true
        }
    }

    fun screenPosition(
        gazeX: Float,
        gazeY: Float,
        width: Float,
        height: Float
    ): Pair<Float, Float> {

        if (points.size < 9) {

            return Pair(
                width / 2f,
                height / 2f
            )
        }

        val sortedPoints =
            points.sortedBy {

                val dx =
                    it.gazeX - gazeX

                val dy =
                    it.gazeY - gazeY

                dx * dx + dy * dy
            }

        val nearest =
            sortedPoints.take(4)

        var totalWeight = 0f

        var weightedX = 0f

        var weightedY = 0f

        for (point in nearest) {

            val dx =
                point.gazeX - gazeX

            val dy =
                point.gazeY - gazeY

            val distance =
                dx * dx +
                dy * dy

            val weight =
                1f /
                (distance + 0.0001f)

            weightedX +=
                point.screenX *
                weight

            weightedY +=
                point.screenY *
                weight

            totalWeight +=
                weight
        }

        var resultX =
            weightedX /
            totalWeight

        var resultY =
            weightedY /
            totalWeight

        resultX =
            resultX.coerceIn(
                0f,
                width
            )

        resultY =
            resultY.coerceIn(
                0f,
                height
            )

        return Pair(
            resultX,
            resultY
        )
    }
}
