package com.prince.eyenav

import android.accessibilityservice.AccessibilityService
import android.accessibilityservice.GestureDescription
import android.graphics.Path
import android.view.accessibility.AccessibilityEvent

class EyeNavAccessibilityService : AccessibilityService() {

    companion object {
        var instance: EyeNavAccessibilityService? = null
            private set
    }

    override fun onServiceConnected() {
        super.onServiceConnected()
        instance = this
    }

    override fun onAccessibilityEvent(
        event: AccessibilityEvent?
    ) {
    }

    override fun onInterrupt() {
    }

    override fun onDestroy() {
        instance = null
        super.onDestroy()
    }

    fun tap(x: Float, y: Float) {

        val path = Path()

        path.moveTo(x, y)

        val stroke =
            GestureDescription.StrokeDescription(
                path,
                0,
                80
            )

        val gesture =
            GestureDescription.Builder()
                .addStroke(stroke)
                .build()

        dispatchGesture(
            gesture,
            null,
            null
        )
    }

    fun swipe(
        startX: Float,
        startY: Float,
        endX: Float,
        endY: Float
    ) {

        val path = Path()

        path.moveTo(startX, startY)
        path.lineTo(endX, endY)

        val stroke =
            GestureDescription.StrokeDescription(
                path,
                0,
                500
            )

        val gesture =
            GestureDescription.Builder()
                .addStroke(stroke)
                .build()

        dispatchGesture(
            gesture,
            null,
            null
        )
    }

    fun goBack() {
        performGlobalAction(
            GLOBAL_ACTION_BACK
        )
    }

    fun goHome() {
        performGlobalAction(
            GLOBAL_ACTION_HOME
        )
    }
}
