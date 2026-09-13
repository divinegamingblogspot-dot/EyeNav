package com.prince.eyenav

import android.accessibilityservice.AccessibilityService
import android.accessibilityservice.GestureDescription
import android.graphics.Path
import android.view.accessibility.AccessibilityNodeInfo
import android.view.accessibility.AccessibilityEvent

class EyeNavAccessibilityService : AccessibilityService() {
    companion object { var instance: EyeNavAccessibilityService? = null }

    override fun onServiceConnected() { super.onServiceConnected(); instance = this }
    override fun onAccessibilityEvent(event: AccessibilityEvent?) = Unit
    override fun onInterrupt() = Unit

    fun readVisibleText(): String {
        val root = rootInActiveWindow ?: return ""
        val out = StringBuilder()
        collectText(root, out)
        return out.toString().trim().replace(Regex("\\s+"), " ").take(3500)
    }

    private fun collectText(node: AccessibilityNodeInfo?, out: StringBuilder) {
        if (node == null || out.length >= 3500) return
        node.text?.toString()?.trim()?.takeIf { it.isNotEmpty() }?.let { out.append(it).append(". ") }
        node.contentDescription?.toString()?.trim()?.takeIf { it.isNotEmpty() }?.let { out.append(it).append(". ") }
        for (i in 0 until node.childCount) collectText(node.getChild(i), out)
    }

    fun performEyeClick(x: Float, y: Float) = gesture(x, y, 60)
    fun performLongPress(x: Float, y: Float) = gesture(x, y, 700)
    private fun gesture(x: Float, y: Float, duration: Long) {
        val path = Path().apply { moveTo(x, y) }
        dispatchGesture(GestureDescription.Builder().addStroke(GestureDescription.StrokeDescription(path, 0, duration)).build(), null, null)
    }
    fun performSwipeUp() = swipe(.50f, .78f, .50f, .28f)
    fun performSwipeDown() = swipe(.50f, .28f, .50f, .78f)
    private fun swipe(x1: Float, y1: Float, x2: Float, y2: Float) {
        val w = resources.displayMetrics.widthPixels.toFloat(); val h = resources.displayMetrics.heightPixels.toFloat()
        val path = Path().apply { moveTo(w*x1,h*y1); lineTo(w*x2,h*y2) }
        dispatchGesture(GestureDescription.Builder().addStroke(GestureDescription.StrokeDescription(path,0,350)).build(), null, null)
    }
    fun goBack() = performGlobalAction(GLOBAL_ACTION_BACK)
    fun goHome() = performGlobalAction(GLOBAL_ACTION_HOME)
    fun openRecents() = performGlobalAction(GLOBAL_ACTION_RECENTS)
    override fun onDestroy() { instance = null; super.onDestroy() }
}
