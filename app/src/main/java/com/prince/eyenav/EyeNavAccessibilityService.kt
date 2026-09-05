package com.prince.eyenav

import android.accessibilityservice.AccessibilityService
import android.accessibilityservice.GestureDescription
import android.graphics.Path
import android.view.accessibility.AccessibilityEvent

class EyeNavAccessibilityService : AccessibilityService() {

    companion object {
        var instance: EyeNavAccessibilityService? = null
    }

    override fun onServiceConnected() {
        super.onServiceConnected()
        instance = this
    }

    override fun onAccessibilityEvent(event: AccessibilityEvent?) {
    }

    override fun onInterrupt() {
    }

    fun performEyeClick(x: Float, y: Float) {

        val path = Path()
        path.moveTo(x, y)

        val gesture = GestureDescription.Builder()
            .addStroke(
                GestureDescription.StrokeDescription(
                    path,
                    0,
                    50
                )
            )
            .build()

        dispatchGesture(
            gesture,
            null,
            null
        )
    }

    override fun onDestroy() {
        instance = null
        super.onDestroy()
    }
}
