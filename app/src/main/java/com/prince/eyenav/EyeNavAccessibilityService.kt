package com.prince.eyenav

import android.accessibilityservice.AccessibilityService
import android.accessibilityservice.AccessibilityServiceInfo
import android.accessibilityservice.GestureDescription
import android.graphics.Path
import android.graphics.Rect
import android.os.Bundle
import android.view.accessibility.AccessibilityEvent
import android.view.accessibility.AccessibilityNodeInfo
import java.util.Locale

/** Cross-app device-control bridge. Requires the user to explicitly enable Accessibility. */
class EyeNavAccessibilityService : AccessibilityService() {
    companion object { @Volatile var instance: EyeNavAccessibilityService? = null }

    @Volatile private var activePackage: String = ""

    override fun onServiceConnected() {
        super.onServiceConnected()
        instance = this
        try {
            serviceInfo = serviceInfo.apply {
                flags = flags or AccessibilityServiceInfo.FLAG_REPORT_VIEW_IDS or AccessibilityServiceInfo.FLAG_INCLUDE_NOT_IMPORTANT_VIEWS
            }
        } catch (_: Throwable) { }
    }

    override fun onAccessibilityEvent(event: AccessibilityEvent?) {
        if (event?.packageName != null) activePackage = event.packageName.toString()
    }

    override fun onInterrupt() = Unit

    fun foregroundPackage(): String = activePackage

    fun readVisibleText(): String {
        val roots = linkedSetOf<AccessibilityNodeInfo>()
        rootInActiveWindow?.let { roots += it }
        try { windows?.forEach { it.root?.let { r -> roots += r } } } catch (_: Throwable) { }
        val out = StringBuilder()
        roots.forEach { collectText(it, out) }
        return out.toString().trim().replace(Regex("\\s+"), " ").take(9000)
    }

    private fun collectText(node: AccessibilityNodeInfo?, out: StringBuilder) {
        if (node == null || out.length >= 9000) return
        val t = node.text?.toString()?.trim().orEmpty()
        val d = node.contentDescription?.toString()?.trim().orEmpty()
        if (t.isNotEmpty()) out.append(t).append(". ")
        if (d.isNotEmpty() && !d.equals(t, true)) out.append(d).append(". ")
        for (i in 0 until node.childCount) collectText(node.getChild(i), out)
    }

    fun clickText(target: String, longPress: Boolean = false): Boolean {
        val node = findNode(target) ?: return false
        return clickNode(node, longPress)
    }

    private fun clickNode(node: AccessibilityNodeInfo, longPress: Boolean): Boolean {
        if (longPress) {
            val r = Rect()
            node.getBoundsInScreen(r)
            if (!r.isEmpty) { performLongPress(r.centerX().toFloat(), r.centerY().toFloat()); return true }
        }
        var current: AccessibilityNodeInfo? = node
        repeat(6) {
            if (current == null) return@repeat
            if (current!!.isClickable && current!!.performAction(AccessibilityNodeInfo.ACTION_CLICK)) return true
            current = current!!.parent
        }
        val r = Rect()
        node.getBoundsInScreen(r)
        if (!r.isEmpty) { performEyeClick(r.centerX().toFloat(), r.centerY().toFloat()); return true }
        return false
    }

    private fun findNode(target: String): AccessibilityNodeInfo? {
        val needle = target.trim().lowercase(Locale.getDefault())
        if (needle.isBlank()) return null
        val roots = linkedSetOf<AccessibilityNodeInfo>()
        rootInActiveWindow?.let { roots += it }
        try { windows?.forEach { it.root?.let { r -> roots += r } } } catch (_: Throwable) { }
        roots.forEach { root -> findNodeRecursive(root, needle)?.let { return it } }
        return null
    }

    private fun findNodeRecursive(node: AccessibilityNodeInfo?, needle: String): AccessibilityNodeInfo? {
        if (node == null) return null
        val text = node.text?.toString()?.trim()?.lowercase(Locale.getDefault()).orEmpty()
        val desc = node.contentDescription?.toString()?.trim()?.lowercase(Locale.getDefault()).orEmpty()
        val id = node.viewIdResourceName?.lowercase(Locale.getDefault()).orEmpty()
        val exact = text == needle || desc == needle || id.endsWith(needle)
        val partial = needle.length >= 3 && (text.contains(needle) || desc.contains(needle) || id.contains(needle))
        if (exact || partial) return node
        for (i in 0 until node.childCount) findNodeRecursive(node.getChild(i), needle)?.let { return it }
        return null
    }

    fun typeText(text: String): Boolean {
        val node = findFocusedEditable(rootInActiveWindow) ?: findEditable(rootInActiveWindow) ?: return false
        val args = Bundle().apply { putCharSequence(AccessibilityNodeInfo.ACTION_ARGUMENT_SET_TEXT_CHARSEQUENCE, text) }
        return node.performAction(AccessibilityNodeInfo.ACTION_SET_TEXT, args)
    }

    private fun findFocusedEditable(node: AccessibilityNodeInfo?): AccessibilityNodeInfo? {
        if (node == null) return null
        if (node.isFocused && node.isEditable) return node
        for (i in 0 until node.childCount) findFocusedEditable(node.getChild(i))?.let { return it }
        return null
    }

    private fun findEditable(node: AccessibilityNodeInfo?): AccessibilityNodeInfo? {
        if (node == null) return null
        if (node.isEditable && node.isEnabled) return node
        for (i in 0 until node.childCount) findEditable(node.getChild(i))?.let { return it }
        return null
    }

    fun performEyeClick(x: Float, y: Float) = gesture(x, y, 60)
    fun performLongPress(x: Float, y: Float) = gesture(x, y, 700)

    private fun gesture(x: Float, y: Float, duration: Long) {
        if (x < 0 || y < 0) return
        val path = Path().apply { moveTo(x, y) }
        try { dispatchGesture(GestureDescription.Builder().addStroke(GestureDescription.StrokeDescription(path, 0, duration)).build(), null, null) } catch (_: Throwable) { }
    }

    fun performSwipeUp() = swipe(.50, .80, .50, .25)
    fun performSwipeDown() = swipe(.50, .25, .50, .80)
    fun swipe(direction: String) {
        when (direction.lowercase(Locale.getDefault())) {
            "up" -> performSwipeUp()
            "down" -> performSwipeDown()
            "left" -> swipe(.82, .50, .18, .50)
            "right" -> swipe(.18, .50, .82, .50)
        }
    }

    private fun swipe(x1: Double, y1: Double, x2: Double, y2: Double) {
        val w = resources.displayMetrics.widthPixels.toDouble()
        val h = resources.displayMetrics.heightPixels.toDouble()
        val path = Path().apply { moveTo((w * x1).toFloat(), (h * y1).toFloat()); lineTo((w * x2).toFloat(), (h * y2).toFloat()) }
        try { dispatchGesture(GestureDescription.Builder().addStroke(GestureDescription.StrokeDescription(path, 0, 400)).build(), null, null) } catch (_: Throwable) { }
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

    override fun onDestroy() { instance = null; activePackage = ""; super.onDestroy() }
}
