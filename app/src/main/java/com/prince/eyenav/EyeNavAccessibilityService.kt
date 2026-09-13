package com.prince.eyenav

import android.accessibilityservice.AccessibilityService
import android.accessibilityservice.GestureDescription
import android.graphics.Path
import android.graphics.Rect
import android.os.Bundle
import android.view.accessibility.AccessibilityEvent
import android.view.accessibility.AccessibilityNodeInfo
import java.util.Locale

/** Device-control bridge. Android grants these powers only after Accessibility is enabled. */
class EyeNavAccessibilityService : AccessibilityService() {
    companion object { @Volatile var instance: EyeNavAccessibilityService? = null }

    override fun onServiceConnected() { super.onServiceConnected(); instance = this }
    override fun onAccessibilityEvent(event: AccessibilityEvent?) = Unit
    override fun onInterrupt() = Unit

    fun readVisibleText(): String {
        val root = rootInActiveWindow ?: return ""
        val out = StringBuilder(); collectText(root, out)
        return out.toString().trim().replace(Regex("\\s+"), " ").take(9000)
    }

    private fun collectText(node: AccessibilityNodeInfo?, out: StringBuilder) {
        if (node == null || out.length >= 9000) return
        node.text?.toString()?.trim()?.takeIf { it.isNotEmpty() }?.let { out.append(it).append(". ") }
        node.contentDescription?.toString()?.trim()?.takeIf { it.isNotEmpty() }?.let { out.append(it).append(". ") }
        for (i in 0 until node.childCount) collectText(node.getChild(i), out)
    }

    fun clickText(target: String, longPress: Boolean = false): Boolean {
        val node = findNode(rootInActiveWindow, target) ?: return false
        if (longPress) { val r = Rect(); node.getBoundsInScreen(r); performLongPress(r.centerX().toFloat(), r.centerY().toFloat()); return true }
        if (node.isClickable && node.performAction(AccessibilityNodeInfo.ACTION_CLICK)) return true
        val parent = node.parent
        if (parent?.isClickable == true && parent.performAction(AccessibilityNodeInfo.ACTION_CLICK)) return true
        val r = Rect(); node.getBoundsInScreen(r); performEyeClick(r.centerX().toFloat(), r.centerY().toFloat()); return true
    }

    private fun findNode(node: AccessibilityNodeInfo?, target: String): AccessibilityNodeInfo? {
        if (node == null) return null
        val needle = target.trim().lowercase(Locale.getDefault())
        val t = node.text?.toString()?.trim()?.lowercase(Locale.getDefault()).orEmpty()
        val d = node.contentDescription?.toString()?.trim()?.lowercase(Locale.getDefault()).orEmpty()
        if (t == needle || d == needle || t.contains(needle) || d.contains(needle)) return node
        for (i in 0 until node.childCount) findNode(node.getChild(i), target)?.let { return it }
        return null
    }

    fun typeText(text: String): Boolean {
        val node = findFocusedEditable(rootInActiveWindow) ?: return false
        val args = Bundle().apply { putCharSequence(AccessibilityNodeInfo.ACTION_ARGUMENT_SET_TEXT_CHARSEQUENCE, text) }
        return node.performAction(AccessibilityNodeInfo.ACTION_SET_TEXT, args)
    }

    private fun findFocusedEditable(node: AccessibilityNodeInfo?): AccessibilityNodeInfo? {
        if (node == null) return null
        if (node.isFocused && node.isEditable) return node
        for (i in 0 until node.childCount) findFocusedEditable(node.getChild(i))?.let { return it }
        return null
    }

    fun performEyeClick(x: Float, y: Float) = gesture(x, y, 60)
    fun performLongPress(x: Float, y: Float) = gesture(x, y, 700)
    private fun gesture(x: Float, y: Float, duration: Long) {
        val path = Path().apply { moveTo(x, y) }
        dispatchGesture(GestureDescription.Builder().addStroke(GestureDescription.StrokeDescription(path, 0, duration)).build(), null, null)
    }

    fun performSwipeUp() = swipe(.50, .78, .50, .28)
    fun performSwipeDown() = swipe(.50, .28, .50, .78)
    fun swipe(direction: String) {
        when (direction.lowercase(Locale.getDefault())) {
            "up" -> performSwipeUp(); "down" -> performSwipeDown(); "left" -> swipe(.80, .50, .20, .50); "right" -> swipe(.20, .50, .80, .50)
        }
    }

    private fun swipe(x1: Double, y1: Double, x2: Double, y2: Double) {
        val w = resources.displayMetrics.widthPixels.toDouble(); val h = resources.displayMetrics.heightPixels.toDouble()
        val path = Path().apply { moveTo((w * x1).toFloat(), (h * y1).toFloat()); lineTo((w * x2).toFloat(), (h * y2).toFloat()) }
        dispatchGesture(GestureDescription.Builder().addStroke(GestureDescription.StrokeDescription(path, 0, 350)).build(), null, null)
    }

    fun goBack() = performGlobalAction(GLOBAL_ACTION_BACK)
    fun goHome() = performGlobalAction(GLOBAL_ACTION_HOME)
    fun openRecents() = performGlobalAction(GLOBAL_ACTION_RECENTS)
    fun openNotifications() = performGlobalAction(GLOBAL_ACTION_NOTIFICATIONS)
    fun openQuickSettings() = performGlobalAction(GLOBAL_ACTION_QUICK_SETTINGS)

    fun takeScreenshot(): Boolean {
        if (android.os.Build.VERSION.SDK_INT < 30) return false
        return try {
            takeScreenshot(0, mainExecutor, object : TakeScreenshotCallback {
                override fun onSuccess(screenshot: ScreenshotResult) { screenshot.hardwareBuffer.close() }
                override fun onFailure(errorCode: Int) = Unit
            })
            true
        } catch (_: Throwable) { false }
    }

    override fun onDestroy() { instance = null; super.onDestroy() }
}
