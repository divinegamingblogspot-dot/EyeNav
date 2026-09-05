package com.prince.eyenav

import android.content.Context
import android.graphics.Color
import android.graphics.PixelFormat
import android.graphics.drawable.GradientDrawable
import android.os.Build
import android.view.Gravity
import android.view.View
import android.view.WindowManager
import android.widget.TextView

class EyeNavOverlay(private val context: Context) {

    private val windowManager = context.getSystemService(Context.WINDOW_SERVICE) as WindowManager
    private var cursor: View? = null
    private var dock: TextView? = null

    fun show() {
        if (cursor != null) return

        val cursorView = View(context).apply {
            background = GradientDrawable().apply {
                shape = GradientDrawable.OVAL
                setColor(Color.RED)
                setStroke(dp(3), Color.WHITE)
            }
        }

        val cursorParams = WindowManager.LayoutParams(
            dp(34),
            dp(34),
            overlayType(),
            WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE or
                WindowManager.LayoutParams.FLAG_NOT_TOUCHABLE or
                WindowManager.LayoutParams.FLAG_LAYOUT_NO_LIMITS,
            PixelFormat.TRANSLUCENT
        ).apply {
            gravity = Gravity.TOP or Gravity.START
            x = 0
            y = 0
        }

        windowManager.addView(cursorView, cursorParams)
        cursor = cursorView
    }

    fun moveTo(x: Float, y: Float) {
        val view = cursor ?: return
        val params = view.layoutParams as WindowManager.LayoutParams
        params.x = x.toInt() - view.layoutParams.width / 2
        params.y = y.toInt() - view.layoutParams.height / 2
        try {
            windowManager.updateViewLayout(view, params)
        } catch (_: Exception) {
        }
    }

    fun showDock(onPause: () -> Unit, onRecalibrate: () -> Unit) {
        if (dock != null) return
        val view = TextView(context).apply {
            text = "EyeNav   PAUSE   RECALIBRATE"
            textSize = 12f
            setTextColor(Color.WHITE)
            setPadding(dp(12), dp(8), dp(12), dp(8))
            setBackgroundColor(Color.argb(180, 0, 0, 0))
            setOnClickListener { onPause() }
            setOnLongClickListener {
                onRecalibrate()
                true
            }
        }
        val params = WindowManager.LayoutParams(
            WindowManager.LayoutParams.WRAP_CONTENT,
            WindowManager.LayoutParams.WRAP_CONTENT,
            overlayType(),
            WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE,
            PixelFormat.TRANSLUCENT
        ).apply {
            gravity = Gravity.TOP or Gravity.CENTER_HORIZONTAL
            y = dp(18)
        }
        windowManager.addView(view, params)
        dock = view
    }

    fun remove() {
        cursor?.let { runCatching { windowManager.removeView(it) } }
        dock?.let { runCatching { windowManager.removeView(it) } }
        cursor = null
        dock = null
    }

    private fun overlayType(): Int =
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY
        } else {
            WindowManager.LayoutParams.TYPE_PHONE
        }

    private fun dp(value: Int): Int =
        (value * context.resources.displayMetrics.density).toInt()
}
