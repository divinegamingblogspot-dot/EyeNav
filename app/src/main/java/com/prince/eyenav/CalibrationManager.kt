package com.prince.eyenav

import android.content.Context

data class CalibrationPoint(
    val gazeX: Float,
    val gazeY: Float,
    val targetX: Float,
    val targetY: Float
)

object CalibrationManager {

    private const val PREFS = "eyenav_calibration"
    private const val KEY_COUNT = "count"
    private const val KEY_PREFIX = "p_"
    private const val KEY_VERSION = "version"
    private const val CALIBRATION_VERSION = 2

    private val points = mutableListOf<CalibrationPoint>()

    private val targetPositions = listOf(
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

    fun load(context: Context) {
        val prefs = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
        val version = prefs.getInt(KEY_VERSION, 0)

        // v1 stored raw iris coordinates. v2 stores iris position relative to each eye,
        // so the old calibration must not be used. Other app permissions/settings remain intact.
        if (version != CALIBRATION_VERSION) {
            points.clear()
            currentTarget = 0
            isCalibrated = false
            prefs.edit()
                .remove(KEY_COUNT)
                .remove(KEY_VERSION)
                .apply()
            return
        }

        val count = prefs.getInt(KEY_COUNT, 0)
        points.clear()
        for (i in 0 until count) {
            val value = prefs.getString(KEY_PREFIX + i, null) ?: continue
            val parts = value.split(",")
            if (parts.size == 4) {
                points += CalibrationPoint(
                    parts[0].toFloatOrNull() ?: continue,
                    parts[1].toFloatOrNull() ?: continue,
                    parts[2].toFloatOrNull() ?: continue,
                    parts[3].toFloatOrNull() ?: continue
                )
            }
        }

        isCalibrated = points.size >= targetPositions.size
        currentTarget = if (isCalibrated) targetPositions.size else points.size
    }

    fun save(context: Context) {
        val editor = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE).edit()
        editor.clear()
        editor.putInt(KEY_VERSION, CALIBRATION_VERSION)
        editor.putInt(KEY_COUNT, points.size)
        points.forEachIndexed { index, point ->
            editor.putString(
                KEY_PREFIX + index,
                "${point.gazeX},${point.gazeY},${point.targetX},${point.targetY}"
            )
        }
        editor.apply()
    }

    fun reset(context: Context? = null) {
        points.clear()
        currentTarget = 0
        isCalibrated = false
        context?.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
            ?.edit()
            ?.clear()
            ?.apply()
    }

    fun target(): Pair<Float, Float> = targetPositions[
        currentTarget.coerceIn(0, targetPositions.lastIndex)
    ]

    fun addPoint(gazeX: Float, gazeY: Float) {
        val target = target()
        points.add(CalibrationPoint(gazeX, gazeY, target.first, target.second))
        currentTarget++
        if (currentTarget >= targetPositions.size) {
            currentTarget = targetPositions.size
            isCalibrated = true
        }
    }

    fun screenPosition(
        gazeX: Float,
        gazeY: Float,
        width: Float,
        height: Float
    ): Pair<Float, Float> {
        if (points.size < targetPositions.size) {
            return width / 2f to height / 2f
        }

        // Inverse-distance interpolation across the closest calibration samples.
        // Exact/near-exact calibration hits are handled directly to avoid numerical spikes.
        val distances = points.map { point ->
            val dx = point.gazeX - gazeX
            val dy = point.gazeY - gazeY
            point to (dx * dx + dy * dy)
        }.sortedBy { it.second }

        val exact = distances.firstOrNull()?.takeIf { it.second < 0.00002f }
        if (exact != null) {
            return exact.first.targetX * width to exact.first.targetY * height
        }

        val nearest = distances.take(6)
        var totalWeight = 0f
        var weightedX = 0f
        var weightedY = 0f

        for ((point, distance) in nearest) {
            val weight = 1f / (distance + 0.0002f)
            weightedX += point.targetX * weight
            weightedY += point.targetY * weight
            totalWeight += weight
        }

        val normalizedX = (weightedX / totalWeight).coerceIn(0.02f, 0.98f)
        val normalizedY = (weightedY / totalWeight).coerceIn(0.02f, 0.98f)
        return normalizedX * width to normalizedY * height
    }
}
