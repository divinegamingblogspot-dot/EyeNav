package com.prince.eyenav

import android.accessibilityservice.AccessibilityService
import android.accessibilityservice.GestureDescription
import android.graphics.Path
import android.view.accessibility.AccessibilityEvent
import android.view.accessibility.AccessibilityNodeInfo
import android.os.Bundle

class EyeNavAccessibilityService : AccessibilityService() {
    companion object { var instance: EyeNavAccessibilityService? = null }

    override fun onServiceConnected() { super.onServiceConnected(); instance = this }
    override fun onAccessibilityEvent(event: AccessibilityEvent?) = Unit
    override fun onInterrupt() = Unit

    fun readVisibleText(): String {
        val root = rootInActiveWindow ?: return ""
        val out = StringBuilder(); collectText(root, out)
        return out.toString().trim().replace(Regex("\\s+"), " ").take(7000)
    }
    private fun collectText(node: AccessibilityNodeInfo?, out: StringBuilder) {
        if (node == null || out.length >= 7000) return
        node.text?.toString()?.trim()?.takeIf { it.isNotEmpty() }?.let { out.append(it).append(". ") }
        node.contentDescription?.toString()?.trim()?.takeIf { it.isNotEmpty() }?.let { out.append(it).append(". ") }
        for (i in 0 until node.childCount) collectText(node.getChild(i), out)
    }

    fun clickText(target: String, longPress: Boolean = false): Boolean {
        val node = findNode(rootInActiveWindow, target) ?: return false
        if (longPress) {
            val r = android.graphics.Rect(); node.getBoundsInScreen(r); performLongPress(r.centerX().toFloat(), r.centerY().toFloat())
            return true
        }
        if (node.isClickable && node.performAction(AccessibilityNodeInfo.ACTION_CLICK)) return true
        val parent = node.parent
        if (parent?.isClickable == true && parent.performAction(AccessibilityNodeInfo.ACTION_CLICK)) return true
        val r = android.graphics.Rect(); node.getBoundsInScreen(r); performEyeClick(r.centerX().toFloat(), r.centerY().toFloat()); return true
    }

    private fun findNode(node: AccessibilityNodeInfo?, target: String): AccessibilityNodeInfo? {
        if (node == null) return null
        val needle = target.trim().lowercase()
        val t = node.text?.toString()?.trim()?.lowercase().orEmpty()
        val d = node.contentDescription?.toString()?.trim()?.lowercase().orEmpty()
        if (t == needle || d == needle || t.contains(needle) || d.contains(needle)) return node
        for (i in 0 until node.childCount) findNode(node.getChild(i), target)?.let { return it }
        return null
    }

    fun typeText(text: String): Boolean {
        val root = rootInActiveWindow ?: return false
        val node = findFocusedEditable(root) ?: return false
        val args = Bundle(); args.putCharSequence(AccessibilityNodeInfo.ACTION_ARGUMENT_SET_TEXT_CHARSEQUENCE, text)
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
    fun performSwipeUp() = swipe(.50f, .78f, .50f, .28f)
    fun performSwipeDown() = swipe(.50f, .28f, .50f, .78f)
    fun swipe(direction: String) {
        when (direction.lowercase()) {
            "up" -> performSwipeUp()
            "down" -> performSwipeDown()
            "left" -> swipe(.80f,.50f,.20f,.50f)
            "right" -> swipe(.20f,.50f,.80f,.50f)
        }
    }
    private fun swipe(x1: Double, y1: Double, x2: Double, y2: Double) {
        val w=resources.displayMetrics.widthPixels.toFloat(); val h=resources.displayMetrics.heightPixels.toFloat()
        val path=Path().apply { moveTo((w*x1).toFloat(),(h*y1).toFloat()); lineTo((w*x2).toFloat(),(h*y2).toFloat()) }
        dispatchGesture(GestureDescription.Builder().addStroke(GestureDescription.StrokeDescription(path,0,350)).build(),null,null)
    }
    fun goBack() = performGlobalAction(GLOBAL_ACTION_BACK)
    fun goHome() = performGlobalAction(GLOBAL_ACTION_HOME)
    fun openRecents() = performGlobalAction(GLOBAL_ACTION_RECENTS)
    override fun onDestroy() { instance=null; super.onDestroy() }
}
