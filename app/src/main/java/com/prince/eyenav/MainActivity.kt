package com.prince.eyenav

import android.Manifest
import android.animation.ValueAnimator
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.Typeface
import android.graphics.drawable.GradientDrawable
import android.net.Uri
import android.os.Bundle
import android.os.PowerManager
import android.provider.Settings
import android.speech.RecognitionListener
import android.speech.SpeechRecognizer
import android.view.Gravity
import android.view.View
import android.view.ViewGroup
import android.widget.Button
import android.widget.FrameLayout
import android.widget.HorizontalScrollView
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView
import androidx.activity.ComponentActivity
import androidx.activity.result.contract.ActivityResultContracts
import androidx.core.content.ContextCompat
import androidx.core.view.ViewCompat
import androidx.core.view.WindowCompat
import androidx.core.view.WindowInsetsCompat
import androidx.core.view.updatePadding
import kotlin.math.cos
import kotlin.math.sin
import kotlin.random.Random

/** DOC // futuristic voice command deck. No camera/eye tracking. */
class MainActivity : ComponentActivity() {
    private lateinit var doc: DocAssistant
    private var recognizer: SpeechRecognizer? = null
    private lateinit var status: TextView
    private lateinit var transcript: TextView
    private lateinit var orb: HudOrbView
    private lateinit var voiceButton: TextView
    private lateinit var accessibilityButton: TextView
    private lateinit var batteryButton: TextView
    private lateinit var micChip: TextView
    private lateinit var accessChip: TextView
    private lateinit var batteryChip: TextView
    private var continuous = false

    private val cyan = Color.rgb(82, 226, 255)
    private val cyanSoft = Color.rgb(35, 128, 158)
    private val bg = Color.rgb(2, 7, 13)
    private val panel = Color.rgb(7, 17, 27)
    private val panel2 = Color.rgb(10, 25, 37)
    private val muted = Color.rgb(112, 145, 163)

    private val audioPermission = registerForActivityResult(ActivityResultContracts.RequestPermission()) { granted ->
        if (granted) listenOnce() else setStatus("MIC PERMISSION REQUIRED")
    }
    private val notificationPermission = registerForActivityResult(ActivityResultContracts.RequestPermission()) { }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        WindowCompat.setDecorFitsSystemWindows(window, false)
        window.statusBarColor = Color.TRANSPARENT
        window.navigationBarColor = Color.TRANSPARENT
        doc = DocAssistant(this).also { it.onStatus = { text -> runOnUiThread { setStatus(text); orb.pulse() } } }
        buildUi()
        setupRecognizer()
        refreshStates()
        if (android.os.Build.VERSION.SDK_INT >= 33 && ContextCompat.checkSelfPermission(this, Manifest.permission.POST_NOTIFICATIONS) != PackageManager.PERMISSION_GRANTED) {
            notificationPermission.launch(Manifest.permission.POST_NOTIFICATIONS)
        }
    }

    private fun dp(value: Int): Int = (value * resources.displayMetrics.density).toInt()

    private fun panelBg(color: Int = panel, radius: Float = 20f, strokeColor: Int = Color.rgb(22, 57, 73)): GradientDrawable = GradientDrawable().apply {
        setColor(color)
        cornerRadius = dp(radius.toInt()).toFloat()
        setStroke(dp(1), strokeColor)
    }

    private fun buildUi() {
        val root = FrameLayout(this).apply { setBackgroundColor(bg) }
        val scroll = ScrollView(this).apply {
            isFillViewport = true
            overScrollMode = View.OVER_SCROLL_NEVER
            clipToPadding = false
        }
        val body = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            gravity = Gravity.CENTER_HORIZONTAL
            setPadding(dp(18), dp(16), dp(18), dp(34))
        }
        scroll.addView(body, FrameLayout.LayoutParams(-1, -2))
        root.addView(scroll, FrameLayout.LayoutParams(-1, -1))
        setContentView(root)

        ViewCompat.setOnApplyWindowInsetsListener(root) { view, insets ->
            val bars = insets.getInsets(WindowInsetsCompat.Type.systemBars() or WindowInsetsCompat.Type.displayCutout())
            view.updatePadding(left = 0, top = 0, right = 0, bottom = 0)
            body.setPadding(dp(18) + bars.left, dp(12) + bars.top, dp(18) + bars.right, dp(28) + bars.bottom)
            insets
        }
        ViewCompat.requestApplyInsets(root)

        body.addView(topBar(), match(-1, dp(58)))
        body.addView(space(8))

        orb = HudOrbView(this)
        body.addView(orb, LinearLayout.LayoutParams(dp(250), dp(250)))
        body.addView(space(8))

        val statePanel = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            gravity = Gravity.CENTER_HORIZONTAL
            setPadding(dp(16), dp(14), dp(16), dp(14))
            background = panelBg(Color.rgb(5, 15, 24), 18f, Color.rgb(25, 71, 89))
        }
        status = label("VOICE CORE STANDBY", 14f, Color.WHITE).apply {
            gravity = Gravity.CENTER
            typeface = Typeface.create(Typeface.MONOSPACE, Typeface.BOLD)
        }
        transcript = label("Awaiting command • say “Doc” to wake the core", 12f, muted).apply { gravity = Gravity.CENTER }
        statePanel.addView(status, match(-1, -2))
        statePanel.addView(transcript, match(-1, -2).also { it.topMargin = dp(7) })
        body.addView(statePanel, match(-1, -2))
        body.addView(space(12))

        val chips = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL; gravity = Gravity.CENTER }
        micChip = chip("MIC  READY")
        accessChip = chip("ACCESS  OFF")
        batteryChip = chip("POWER  CHECK")
        chips.addView(micChip, weight(1f, dp(36), 4))
        chips.addView(accessChip, weight(1f, dp(36), 4))
        chips.addView(batteryChip, weight(1f, dp(36), 4))
        body.addView(chips, match(-1, dp(44)))
        body.addView(space(10))

        voiceButton = actionCard("ACTIVATE VOICE CORE", "Continuous hands-free listening • screen-off capable") { toggleVoiceCore() }
        body.addView(voiceButton, match(-1, dp(68)))
        body.addView(space(8))

        val quick = label("QUICK COMMANDS", 10f, Color.rgb(75, 119, 137)).apply {
            typeface = Typeface.create(Typeface.MONOSPACE, Typeface.BOLD)
            letterSpacing = .16f
        }
        body.addView(quick, match(-1, dp(24)))
        body.addView(commandStrip(), match(-1, dp(50)))
        body.addView(space(10))

        accessibilityButton = actionCard("CONNECT ACCESSIBILITY CORE", "Cross-app taps • typing • swipes • screen reading") {
            startActivity(Intent(Settings.ACTION_ACCESSIBILITY_SETTINGS))
        }
        body.addView(accessibilityButton, match(-1, dp(62)))
        body.addView(space(8))
        batteryButton = actionCard("PROTECT VOICE CORE", "Disable battery sleep for reliable background listening") { requestBatteryExemption() }
        body.addView(batteryButton, match(-1, dp(62)))
        body.addView(space(8))

        val bottom = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER
        }
        val talk = smallAction("TALK ONCE") { requestAndListenOnce() }
        val test = smallAction("TEST DOC VOICE") { doc.speak("All systems online. Voice channel stable. DOC is ready.") }
        bottom.addView(talk, weight(1f, dp(48), 4))
        bottom.addView(test, weight(1f, dp(48), 4))
        body.addView(bottom, match(-1, dp(56)))
        body.addView(space(12))

        val footer = label("LOCAL COGNITIVE CORE  •  API-KEY FREE\nVOICE FIRST  •  ACCESSIBILITY POWERED\n\nLOCK SCREEN: say “Doc, open WhatsApp” — DOC wakes the display and asks Android for normal unlock when required.", 9f, Color.rgb(61, 92, 108)).apply {
            gravity = Gravity.CENTER
            typeface = Typeface.MONOSPACE
            letterSpacing = .06f
            setPadding(dp(8), dp(8), dp(8), 0)
        }
        body.addView(footer, match(-1, dp(82)))
    }

    private fun topBar(): View = LinearLayout(this).apply {
        orientation = LinearLayout.HORIZONTAL
        gravity = Gravity.CENTER_VERTICAL
        val brand = label("D O C", 22f, cyan).apply {
            typeface = Typeface.create(Typeface.MONOSPACE, Typeface.BOLD)
            letterSpacing = .18f
        }
        val sub = label("  //  LOCAL INTELLIGENCE", 9f, muted).apply { typeface = Typeface.MONOSPACE }
        val live = TextView(this@MainActivity).apply {
            text = "● LIVE"
            textSize = 9f
            setTextColor(Color.rgb(105, 255, 178))
            typeface = Typeface.MONOSPACE
            gravity = Gravity.CENTER
            background = panelBg(Color.rgb(5, 25, 20), 14f, Color.rgb(28, 94, 72))
        }
        addView(brand, LinearLayout.LayoutParams(0, -1, 1f))
        addView(sub, LinearLayout.LayoutParams(0, -1, 1.4f))
        addView(live, LinearLayout.LayoutParams(dp(58), dp(30)))
    }

    private fun commandStrip(): View {
        val scroll = HorizontalScrollView(this).apply { isHorizontalScrollBarEnabled = false; overScrollMode = View.OVER_SCROLL_NEVER }
        val row = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL; gravity = Gravity.CENTER_VERTICAL }
        val commands = listOf("WhatsApp", "Read screen", "Go home", "Battery", "Search Delhi", "Flashlight")
        commands.forEach { command ->
            val b = smallAction(command) { doc.execute(command.replace("WhatsApp", "Open WhatsApp").replace("Search Delhi", "Search for Delhi").replace("Flashlight", "Turn flashlight on")) }
            row.addView(b, LinearLayout.LayoutParams(dp(108), dp(44)).also { it.setMargins(dp(4), 0, dp(4), 0) })
        }
        scroll.addView(row, LinearLayout.LayoutParams(-2, -1))
        return scroll
    }

    private fun actionCard(title: String, subtitle: String, action: () -> Unit): TextView = TextView(this).apply {
        text = "$title\n$subtitle"
        textSize = 11f
        setTextColor(Color.WHITE)
        gravity = Gravity.CENTER_VERTICAL
        setPadding(dp(18), 0, dp(18), 0)
        typeface = Typeface.create(Typeface.MONOSPACE, Typeface.NORMAL)
        background = panelBg(panel2, 18f, Color.rgb(25, 77, 96))
        isClickable = true
        setOnClickListener { action() }
    }

    private fun smallAction(title: String, action: () -> Unit): TextView = TextView(this).apply {
        text = title
        textSize = 10f
        setTextColor(Color.rgb(205, 230, 239))
        gravity = Gravity.CENTER
        typeface = Typeface.MONOSPACE
        background = panelBg(panel, 14f, Color.rgb(26, 58, 72))
        isClickable = true
        setOnClickListener { action() }
    }

    private fun chip(title: String): TextView = TextView(this).apply {
        text = title
        textSize = 8f
        setTextColor(muted)
        gravity = Gravity.CENTER
        typeface = Typeface.MONOSPACE
        background = panelBg(Color.rgb(5, 13, 21), 12f, Color.rgb(19, 43, 55))
    }

    private fun label(textValue: String, size: Float, color: Int): TextView = TextView(this).apply {
        text = textValue
        textSize = size
        setTextColor(color)
        includeFontPadding = true
    }

    private fun match(w: Int, h: Int): LinearLayout.LayoutParams = LinearLayout.LayoutParams(w, h)
    private fun weight(weight: Float, height: Int, margin: Int): LinearLayout.LayoutParams = LinearLayout.LayoutParams(0, height, weight).also { it.setMargins(margin, 0, margin, 0) }
    private fun space(height: Int): View = Space(this).apply { minimumHeight = dp(height) }

    private fun setStatus(text: String) {
        if (::status.isInitialized) status.text = text.uppercase()
        if (::orb.isInitialized) orb.pulse()
    }

    private fun toggleVoiceCore() { if (continuous) stopVoiceCore() else requestAndStartVoiceCore() }

    private fun requestAndStartVoiceCore() {
        if (ContextCompat.checkSelfPermission(this, Manifest.permission.RECORD_AUDIO) != PackageManager.PERMISSION_GRANTED) {
            audioPermission.launch(Manifest.permission.RECORD_AUDIO)
            return
        }
        startVoiceCore()
    }

    private fun startVoiceCore() {
        ContextCompat.startForegroundService(this, Intent(this, DocVoiceService::class.java).setAction(DocVoiceService.ACTION_START))
        continuous = true
        voiceButton.text = "VOICE CORE ONLINE\nContinuous listening • tap to sleep"
        setStatus("JARVIS VOICE CORE • ACTIVE")
    }

    private fun stopVoiceCore() {
        stopService(Intent(this, DocVoiceService::class.java).setAction(DocVoiceService.ACTION_STOP))
        continuous = false
        voiceButton.text = "ACTIVATE VOICE CORE\nContinuous hands-free listening • screen-off capable"
        setStatus("VOICE CORE STANDBY")
    }

    private fun requestBatteryExemption() {
        try {
            val pm = getSystemService(POWER_SERVICE) as PowerManager
            if (android.os.Build.VERSION.SDK_INT >= 23 && !pm.isIgnoringBatteryOptimizations(packageName)) {
                startActivity(Intent(Settings.ACTION_REQUEST_IGNORE_BATTERY_OPTIMIZATIONS, Uri.parse("package:$packageName")))
            } else setStatus("BATTERY PROTECTION ALREADY DISABLED")
        } catch (_: Throwable) {
            try { startActivity(Intent(Settings.ACTION_IGNORE_BATTERY_OPTIMIZATION_SETTINGS)) } catch (_: Throwable) { setStatus("OPEN BATTERY SETTINGS MANUALLY") }
        }
    }

    private fun setupRecognizer() {
        if (!SpeechRecognizer.isRecognitionAvailable(this)) return
        recognizer = SpeechRecognizer.createSpeechRecognizer(this).also { r ->
            r.setRecognitionListener(object : RecognitionListener {
                override fun onReadyForSpeech(params: Bundle?) { setStatus("LISTENING • VOICE CHANNEL OPEN") }
                override fun onBeginningOfSpeech() { setStatus("HEARING YOU") }
                override fun onEndOfSpeech() { setStatus("PROCESSING") }
                override fun onError(error: Int) { setStatus("VOICE CHANNEL READY") }
                override fun onResults(results: Bundle?) {
                    val text = results?.getStringArrayList(SpeechRecognizer.RESULTS_RECOGNITION)?.firstOrNull().orEmpty()
                    transcript.text = if (text.isBlank()) "No command detected." else "VOICE INPUT  ›  $text"
                    if (text.isNotBlank()) doc.execute(text)
                }
                override fun onPartialResults(partialResults: Bundle?) {
                    partialResults?.getStringArrayList(SpeechRecognizer.RESULTS_RECOGNITION)?.firstOrNull()?.takeIf { it.isNotBlank() }?.let { transcript.text = "VOICE INPUT  ›  $it" }
                }
                override fun onBufferReceived(buffer: ByteArray?) = Unit
                override fun onEvent(eventType: Int, params: Bundle?) = Unit
                override fun onRmsChanged(rmsdB: Float) { if (rmsdB > 3) orb.pulse() }
            })
        }
    }

    private fun requestAndListenOnce() {
        if (ContextCompat.checkSelfPermission(this, Manifest.permission.RECORD_AUDIO) != PackageManager.PERMISSION_GRANTED) audioPermission.launch(Manifest.permission.RECORD_AUDIO) else listenOnce()
    }

    private fun listenOnce() {
        try {
            recognizer?.cancel()
            recognizer?.startListening(doc.recognizerIntent())
        } catch (_: Throwable) { setStatus("VOICE ENGINE BUSY") }
    }

    private fun refreshStates() {
        val micOk = ContextCompat.checkSelfPermission(this, Manifest.permission.RECORD_AUDIO) == PackageManager.PERMISSION_GRANTED
        micChip.text = if (micOk) "MIC  READY" else "MIC  LOCKED"
        val enabled = Settings.Secure.getString(contentResolver, Settings.Secure.ENABLED_ACCESSIBILITY_SERVICES).orEmpty().contains(packageName, true)
        accessChip.text = if (enabled) "ACCESS  ON" else "ACCESS  OFF"
        accessChip.setTextColor(if (enabled) Color.rgb(105, 255, 178) else muted)
        accessibilityButton.text = if (enabled) "ACCESSIBILITY CORE • CONNECTED\nCross-app control is ready" else "CONNECT ACCESSIBILITY CORE\nCross-app taps • typing • swipes • screen reading"
        try {
            val pm = getSystemService(POWER_SERVICE) as PowerManager
            val exempt = android.os.Build.VERSION.SDK_INT < 23 || pm.isIgnoringBatteryOptimizations(packageName)
            batteryChip.text = if (exempt) "POWER  FREE" else "POWER  LIMITED"
            batteryChip.setTextColor(if (exempt) Color.rgb(105, 255, 178) else muted)
            batteryButton.text = if (exempt) "PROTECT VOICE CORE • EXEMPT\nBattery optimization is already disabled" else "PROTECT VOICE CORE\nDisable battery sleep for reliable background listening"
        } catch (_: Throwable) { }
    }

    override fun onResume() { super.onResume(); refreshStates() }

    override fun onDestroy() {
        recognizer?.destroy(); recognizer = null; doc.destroy(); super.onDestroy()
    }

    private class HudOrbView(context: Context) : View(context) {
        private val paint = Paint(Paint.ANTI_ALIAS_FLAG)
        private var phase = 0f
        private var pulse = 0f
        private val animator = ValueAnimator.ofFloat(0f, 360f).apply {
            duration = 9000L
            repeatCount = ValueAnimator.INFINITE
            addUpdateListener { phase = it.animatedValue as Float; invalidate() }
        }
        init { setLayerType(View.LAYER_TYPE_SOFTWARE, null); animator.start() }

        fun pulse() { pulse = 1f; animate().scaleX(1.025f).scaleY(1.025f).setDuration(120).withEndAction { animate().scaleX(1f).scaleY(1f).setDuration(260).start() }.start() }

        override fun onDraw(canvas: Canvas) {
            super.onDraw(canvas)
            val cx = width / 2f
            val cy = height / 2f
            val r = minOf(width, height) * .33f
            paint.style = Paint.Style.FILL
            paint.color = Color.rgb(4, 18, 28)
            canvas.drawCircle(cx, cy, r * 1.22f, paint)

            paint.style = Paint.Style.STROKE
            paint.strokeWidth = 1.2f
            paint.color = Color.rgb(24, 104, 129)
            canvas.drawCircle(cx, cy, r * 1.55f, paint)
            paint.color = Color.rgb(47, 177, 209)
            paint.strokeWidth = 2f
            canvas.drawCircle(cx, cy, r * 1.28f, paint)
            paint.color = Color.rgb(72, 224, 255)
            paint.strokeWidth = 3f
            val start = phase
            canvas.drawArc(cx - r * 1.55f, cy - r * 1.55f, cx + r * 1.55f, cy + r * 1.55f, start, 78f, false, paint)
            canvas.drawArc(cx - r * 1.28f, cy - r * 1.28f, cx + r * 1.28f, cy + r * 1.28f, -start * .7f, -52f, false, paint)

            paint.style = Paint.Style.FILL
            paint.color = Color.rgb(80, 226, 255)
            canvas.drawCircle(cx, cy, r * .45f, paint)
            paint.color = Color.rgb(3, 13, 21)
            canvas.drawCircle(cx, cy, r * .34f, paint)
            paint.style = Paint.Style.STROKE
            paint.strokeWidth = 2f
            paint.color = Color.rgb(104, 239, 255)
            canvas.drawCircle(cx, cy, r * .20f, paint)

            paint.strokeWidth = 1f
            paint.color = Color.rgb(29, 77, 94)
            for (i in 0 until 12) {
                val a = Math.toRadians((i * 30 + phase * .25).toDouble())
                val inner = r * 1.67f
                val outer = r * 1.75f
                canvas.drawLine((cx + cos(a) * inner).toFloat(), (cy + sin(a) * inner).toFloat(), (cx + cos(a) * outer).toFloat(), (cy + sin(a) * outer).toFloat(), paint)
            }
            paint.style = Paint.Style.FILL
            paint.color = Color.rgb(105, 255, 178)
            canvas.drawCircle(cx + r * 1.55f, cy - r * 1.35f, 4f, paint)
        }

        override fun onDetachedFromWindow() { animator.cancel(); super.onDetachedFromWindow() }
    }
}
