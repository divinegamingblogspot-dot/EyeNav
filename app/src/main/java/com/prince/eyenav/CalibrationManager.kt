package com.prince.eyenav

import android.content.Context
import kotlin.math.abs

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
    private const val CALIBRATION_VERSION = 3

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

    @Synchronized
    fun load(context: Context) {
        val prefs = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
        val version = prefs.getInt(KEY_VERSION, 0)

        if (version != CALIBRATION_VERSION) {
            points.clear()
            currentTarget = 0
            isCalibrated = false
            prefs.edit().clear().apply()
            return
        }

        val count = prefs.getInt(KEY_COUNT, 0)
        points.clear()
        for (i in 0 until count) {
            val value = prefs.getString(KEY_PREFIX + i, null) ?: continue
            val parts = value.split(",")
            if (parts.size == 4) {
                val gx = parts[0].toFloatOrNull() ?: continue
                val gy = parts[1].toFloatOrNull() ?: continue
                val tx = parts[2].toFloatOrNull() ?: continue
                val ty = parts[3].toFloatOrNull() ?: continue
                points += CalibrationPoint(gx, gy, tx, ty)
            }
        }

        isCalibrated = points.size >= targetPositions.size
        currentTarget = if (isCalibrated) targetPositions.size else points.size
    }

    @Synchronized
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

    @Synchronized
    fun reset(context: Context? = null) {
        points.clear()
        currentTarget = 0
        isCalibrated = false
        context?.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
            ?.edit()?.clear()?.apply()
    }

    fun target(): Pair<Float, Float> = targetPositions[
        currentTarget.coerceIn(0, targetPositions.lastIndex)
    ]

    @Synchronized
    fun addPoint(gazeX: Float, gazeY: Float) {
        if (currentTarget >= targetPositions.size) return
        val target = target()
        points.add(CalibrationPoint(gazeX, gazeY, target.first, target.second))
        currentTarget++
        if (currentTarget >= targetPositions.size) {
            currentTarget = targetPositions.size
            isCalibrated = true
        }
    }

    /**
     * Convert live eye coordinates to screen coordinates.
     * The previous inverse-distance mapper could become sticky around one calibration point.
     * This version builds independent monotonic X/Y mappings from the 3x3 calibration grid,
     * then interpolates between the nearest calibrated gaze anchors. It stays responsive
     * between points and does not require a camera restart.
     */
    @Synchronized
    fun screenPosition(
        gazeX: Float,
        gazeY: Float,
        width: Float,
        height: Float
    ): Pair<Float, Float> {
        if (!isCalibrated || points.size < targetPositions.size || width <= 0f || height <= 0f) {
            return width / 2f to height / 2f
        }

        val xAnchors = axisAnchors(useX = true)
        val yAnchors = axisAnchors(useX = false)

        val normalizedX = interpolateAxis(gazeX.coerceIn(0f, 1f), xAnchors)
        val normalizedY = interpolateAxis(gazeY.coerceIn(0f, 1f), yAnchors)

        return normalizedX.coerceIn(0.02f, 0.98f) * width to
            normalizedY.coerceIn(0.02f, 0.98f) * height
    }

    private fun axisAnchors(useX: Boolean): List<Pair<Float, Float>> {
        val anchors = ArrayList<Pair<Float, Float>>(3)
        val targetLevels = floatArrayOf(0.10f, 0.50f, 0.90f)

        for (level in targetLevels) {
            val selected = points.filter { point ->
                if (useX) abs(point.targetX - level) < 0.01f
                else abs(point.targetY - level) < 0.01f
            }
            if (selected.isNotEmpty()) {
                val gaze = selected.map { if (useX) it.gazeX else it.gazeY }.average().toFloat()
                anchors += gaze to level
            }
        }

        return anchors.sortedBy { it.first }
    }

    private fun interpolateAxis(value: Float, anchors: List<Pair<Float, Float>>): Float {
        if (anchors.isEmpty()) return value
        if (anchors.size == 1) return anchors[0].second

        // If calibration gaze direction is reversed on the device, sorting by gaze still
        // produces the correct monotonic mapping from low gaze coordinate to its calibrated target.
        if (value <= anchors.first().first) {
            return extrapolate(value, anchors[0], anchors[1]).coerceIn(0f, 1f)
        }
        if (value >= anchors.last().first) {
            return extrapolate(value, anchors[anchors.lastIndex - 1], anchors.last()).coerceIn(0f, 1f)
        }

        for (i in 0 until anchors.lastIndex) {
            val a = anchors[i]
            val b = anchors[i + 1]
            if (value <= b.first) return extrapolate(value, a, b).coerceIn(0f, 1f)
        }
        return anchors.last().second
    }

    private fun extrapolate(value: Float, a: Pair<Float, Float>, b: Pair<Float, Float>): Float {
        val dx = b.first - a.first
        if (abs(dx) < 0.00001f) return (a.second + b.second) / 2f
        return a.second + (value - a.first) * (b.second - a.second) / dx
    }
}
