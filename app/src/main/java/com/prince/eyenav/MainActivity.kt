package com.prince.eyenav

import android.Manifest
import android.content.Intent
import android.content.pm.PackageManager
import android.graphics.Color
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

/** DOC // voice-first command deck. Eye tracking has been removed. */
class MainActivity : ComponentActivity() {
    private lateinit var doc: DocAssistant
    private var recognizer: SpeechRecognizer? = null
    private lateinit var status: TextView
    private lateinit var transcript: TextView
    private lateinit var orb: TextView
    private lateinit var voiceButton: TextView
    private lateinit var accessibilityButton: TextView
    private lateinit var batteryButton: TextView
    private lateinit var micChip: TextView
    private lateinit var accessChip: TextView
    private lateinit var powerChip: TextView
    private var continuous = false

    private val cyan = Color.rgb(90, 226, 255)
    private val bg = Color.rgb(2, 6, 11)
    private val panel = Color.rgb(7, 17, 27)
    private val panelStrong = Color.rgb(8, 27, 39)
    private val muted = Color.rgb(103, 139, 158)
    private val green = Color.rgb(105, 255, 178)

    private val audioPermission = registerForActivityResult(ActivityResultContracts.RequestPermission()) { granted ->
        if (granted) startVoiceCore() else setStatus("MIC PERMISSION REQUIRED")
    }
    private val notificationPermission = registerForActivityResult(ActivityResultContracts.RequestPermission()) { }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        WindowCompat.setDecorFitsSystemWindows(window, false)
        window.statusBarColor = Color.TRANSPARENT
        window.navigationBarColor = Color.TRANSPARENT

        doc = DocAssistant(this).also { assistant ->
            assistant.onStatus = { text -> runOnUiThread { setStatus(text) } }
        }
        buildUi()
        setupRecognizer()
        refreshStates()

        if (android.os.Build.VERSION.SDK_INT >= 33 && ContextCompat.checkSelfPermission(this, Manifest.permission.POST_NOTIFICATIONS) != PackageManager.PERMISSION_GRANTED) {
            notificationPermission.launch(Manifest.permission.POST_NOTIFICATIONS)
        }

        // DOC becomes hands-free immediately after the one-time microphone permission is granted.
        if (ContextCompat.checkSelfPermission(this, Manifest.permission.RECORD_AUDIO) == PackageManager.PERMISSION_GRANTED) {
            startVoiceCore()
        } else {
            audioPermission.launch(Manifest.permission.RECORD_AUDIO)
        }
    }

    private fun dp(value: Int): Int = (value * resources.displayMetrics.density).toInt()

    private fun background(color: Int, radius: Int, stroke: Int = Color.TRANSPARENT): GradientDrawable = GradientDrawable().apply {
        setColor(color)
        cornerRadius = dp(radius).toFloat()
        if (stroke != Color.TRANSPARENT) setStroke(dp(1), stroke)
    }

    private fun buildUi() {
        val root = FrameLayout(this)
        root.setBackgroundColor(bg)

        val scroll = ScrollView(this)
        scroll.isFillViewport = true
        scroll.clipToPadding = false
        scroll.overScrollMode = View.OVER_SCROLL_NEVER

        val body = LinearLayout(this)
        body.orientation = LinearLayout.VERTICAL
        body.gravity = Gravity.CENTER_HORIZONTAL
        body.setPadding(dp(18), dp(12), dp(18), dp(28))
        scroll.addView(body, LinearLayout.LayoutParams(-1, -2))
        root.addView(scroll, FrameLayout.LayoutParams(-1, -1))
        setContentView(root)

        ViewCompat.setOnApplyWindowInsetsListener(root) { _, insets ->
            val bars = insets.getInsets(WindowInsetsCompat.Type.systemBars())
            body.setPadding(dp(18) + bars.left, dp(12) + bars.top, dp(18) + bars.right, dp(28) + bars.bottom)
            insets
        }
        ViewCompat.requestApplyInsets(root)

        body.addView(header(), size(-1, dp(54)))
        body.addView(space(dp(8)))
        body.addView(createOrb(), size(-1, dp(238)))
        body.addView(space(dp(4)))

        val state = LinearLayout(this)
        state.orientation = LinearLayout.VERTICAL
        state.gravity = Gravity.CENTER
        state.setPadding(dp(14), dp(12), dp(14), dp(12))
        state.background = background(panel, 18, Color.rgb(24, 65, 83))
        status = text("VOICE CORE STANDBY", 14f, Color.WHITE).apply {
            gravity = Gravity.CENTER
            typeface = Typeface.create(Typeface.MONOSPACE, Typeface.BOLD)
        }
        transcript = text("Wake word active • say “Doc” or “Hey Doc”", 11f, muted).apply { gravity = Gravity.CENTER }
        state.addView(status, size(-1, -2))
        val transcriptParams = size(-1, -2)
        transcriptParams.topMargin = dp(6)
        state.addView(transcript, transcriptParams)
        body.addView(state, size(-1, dp(74)))
        body.addView(space(dp(10)))

        val telemetry = LinearLayout(this)
        telemetry.orientation = LinearLayout.HORIZONTAL
        telemetry.gravity = Gravity.CENTER
        micChip = chip("MIC  READY")
        accessChip = chip("ACCESS  OFF")
        powerChip = chip("POWER  CHECK")
        telemetry.addView(micChip, weight(1f, dp(34)))
        telemetry.addView(accessChip, weight(1f, dp(34)))
        telemetry.addView(powerChip, weight(1f, dp(34)))
        body.addView(telemetry, size(-1, dp(42)))
        body.addView(space(dp(10)))

        voiceButton = card("VOICE CORE ONLINE", "Wake-word listening • say Doc, then your command") { toggleVoiceCore() }
        body.addView(voiceButton, size(-1, dp(66)))
        body.addView(space(dp(8)))

        body.addView(text("QUICK COMMANDS", 9f, Color.rgb(71, 115, 133)).apply {
            typeface = Typeface.create(Typeface.MONOSPACE, Typeface.BOLD)
            letterSpacing = .14f
        }, size(-1, dp(22)))
        body.addView(commandStrip(), size(-1, dp(48)))
        body.addView(space(dp(10)))

        accessibilityButton = card("CONNECT ACCESSIBILITY CORE", "Cross-app taps • typing • swipes • screen reading") {
            startActivity(Intent(Settings.ACTION_ACCESSIBILITY_SETTINGS))
        }
        body.addView(accessibilityButton, size(-1, dp(60)))
        body.addView(space(dp(8)))

        batteryButton = card("PROTECT VOICE CORE", "Disable battery sleep for reliable background listening") {
            requestBatteryExemption()
        }
        body.addView(batteryButton, size(-1, dp(60)))
        body.addView(space(dp(8)))

        val bottom = LinearLayout(this)
        bottom.orientation = LinearLayout.HORIZONTAL
        bottom.gravity = Gravity.CENTER
        bottom.addView(smallButton("TALK ONCE") { requestAndListenOnce() }, weight(1f, dp(46)))
        bottom.addView(smallButton("TEST DOC VOICE") { doc.speak("All systems online. Voice channel stable. DOC is ready.") }, weight(1f, dp(46)))
        body.addView(bottom, size(-1, dp(52)))
        body.addView(space(dp(10)))

        body.addView(text("LOCAL COGNITIVE CORE  •  API-KEY FREE\nVOICE FIRST  •  ACCESSIBILITY POWERED\n\nLOCK SCREEN: say “Doc, open WhatsApp”. DOC keeps the wake core in a microphone foreground service; Android still controls what may run while locked.", 9f, Color.rgb(58, 88, 104)).apply {
            gravity = Gravity.CENTER
            typeface = Typeface.MONOSPACE
            setPadding(dp(5), 0, dp(5), 0)
        }, size(-1, dp(78)))
    }

    private fun header(): View {
        val row = LinearLayout(this)
        row.orientation = LinearLayout.HORIZONTAL
        row.gravity = Gravity.CENTER_VERTICAL

        val brand = text("D O C", 22f, cyan).apply {
            typeface = Typeface.create(Typeface.MONOSPACE, Typeface.BOLD)
            letterSpacing = .18f
        }
        val subtitle = text("  // LOCAL INTELLIGENCE", 9f, muted).apply { typeface = Typeface.MONOSPACE }
        val live = text("● LIVE", 8f, green).apply {
            gravity = Gravity.CENTER
            typeface = Typeface.MONOSPACE
            background = background(Color.rgb(5, 25, 20), 12, Color.rgb(28, 91, 69))
        }
        row.addView(brand, weight(1f, -1))
        row.addView(subtitle, weight(1.5f, -1))
        row.addView(live, size(dp(58), dp(28)))
        return row
    }

    private fun createOrb(): View {
        val frame = FrameLayout(this)
        frame.foregroundGravity = Gravity.CENTER

        val outer = TextView(this)
        outer.background = background(Color.rgb(4, 17, 27), 140, Color.rgb(25, 116, 143))
        frame.addView(outer, FrameLayout.LayoutParams(dp(218), dp(218), Gravity.CENTER))

        val middle = TextView(this)
        middle.background = background(Color.rgb(5, 24, 35), 120, Color.rgb(48, 184, 216))
        frame.addView(middle, FrameLayout.LayoutParams(dp(164), dp(164), Gravity.CENTER))

        orb = TextView(this)
        orb.text = "DOC"
        orb.textSize = 22f
        orb.gravity = Gravity.CENTER
        orb.setTextColor(cyan)
        orb.typeface = Typeface.create(Typeface.MONOSPACE, Typeface.BOLD)
        orb.background = background(Color.rgb(2, 11, 18), 90, cyan)
        frame.addView(orb, FrameLayout.LayoutParams(dp(108), dp(108), Gravity.CENTER))

        val core = text("WAKE", 8f, Color.rgb(136, 215, 235)).apply {
            gravity = Gravity.CENTER
            typeface = Typeface.MONOSPACE
            letterSpacing = .2f
        }
        val coreParams = FrameLayout.LayoutParams(dp(90), dp(22), Gravity.CENTER)
        coreParams.topMargin = dp(150)
        frame.addView(core, coreParams)

        return frame
    }

    private fun commandStrip(): View {
        val scroll = HorizontalScrollView(this)
        scroll.isHorizontalScrollBarEnabled = false
        scroll.overScrollMode = View.OVER_SCROLL_NEVER
        val row = LinearLayout(this)
        row.orientation = LinearLayout.HORIZONTAL
        val commands = listOf(
            "WhatsApp" to "Open WhatsApp",
            "Read screen" to "Read screen",
            "Go home" to "Go home",
            "Battery" to "Battery",
            "Search Delhi" to "Search for Delhi",
            "Flashlight" to "Turn flashlight on"
        )
        for ((label, command) in commands) {
            val b = smallButton(label) { doc.execute(command) }
            val p = LinearLayout.LayoutParams(dp(108), dp(42))
            p.setMargins(dp(4), dp(2), dp(4), dp(2))
            row.addView(b, p)
        }
        scroll.addView(row, FrameLayout.LayoutParams(-2, -1))
        return scroll
    }

    private fun card(title: String, subtitle: String, action: () -> Unit): TextView = TextView(this).apply {
        text = "$title\n$subtitle"
        textSize = 10.5f
        setTextColor(Color.WHITE)
        gravity = Gravity.CENTER_VERTICAL
        setPadding(dp(18), 0, dp(18), 0)
        typeface = Typeface.MONOSPACE
        background = background(panelStrong, 17, Color.rgb(24, 77, 96))
        isClickable = true
        setOnClickListener { action() }
    }

    private fun smallButton(label: String, action: () -> Unit): TextView = TextView(this).apply {
        text = label
        textSize = 9.5f
        setTextColor(Color.rgb(204, 230, 239))
        gravity = Gravity.CENTER
        typeface = Typeface.MONOSPACE
        background = background(panel, 13, Color.rgb(24, 55, 70))
        isClickable = true
        setOnClickListener { action() }
    }

    private fun chip(label: String): TextView = text(label, 8f, muted).apply {
        gravity = Gravity.CENTER
        typeface = Typeface.MONOSPACE
        background = background(Color.rgb(5, 13, 20), 11, Color.rgb(18, 42, 54))
    }

    private fun text(value: String, sizeSp: Float, color: Int): TextView = TextView(this).apply {
        text = value
        textSize = sizeSp
        setTextColor(color)
        includeFontPadding = true
    }

    private fun size(width: Int, height: Int): LinearLayout.LayoutParams = LinearLayout.LayoutParams(width, height)

    private fun weight(value: Float, height: Int): LinearLayout.LayoutParams = LinearLayout.LayoutParams(0, height, value).also {
        it.setMargins(dp(3), 0, dp(3), 0)
    }

    private fun space(height: Int): View = View(this).apply {
        layoutParams = LinearLayout.LayoutParams(1, height)
    }

    private fun setStatus(value: String) {
        if (::status.isInitialized) status.text = value.uppercase()
        if (::orb.isInitialized) {
            orb.animate().scaleX(1.04f).scaleY(1.04f).setDuration(120).withEndAction {
                orb.animate().scaleX(1f).scaleY(1f).setDuration(240).start()
            }.start()
        }
    }

    private fun toggleVoiceCore() {
        if (continuous) stopVoiceCore() else requestAndStartVoiceCore()
    }

    private fun requestAndStartVoiceCore() {
        if (ContextCompat.checkSelfPermission(this, Manifest.permission.RECORD_AUDIO) != PackageManager.PERMISSION_GRANTED) {
            audioPermission.launch(Manifest.permission.RECORD_AUDIO)
            return
        }
        startVoiceCore()
    }

    private fun startVoiceCore() {
        try {
            ContextCompat.startForegroundService(this, Intent(this, DocVoiceService::class.java).setAction(DocVoiceService.ACTION_START))
            continuous = true
            voiceButton.text = "VOICE CORE ONLINE\nWake word active • say Doc, then command"
            setStatus("DOC WAKE CORE • ACTIVE")
        } catch (_: Throwable) {
            continuous = false
            setStatus("VOICE CORE COULD NOT START")
        }
    }

    private fun stopVoiceCore() {
        stopService(Intent(this, DocVoiceService::class.java).setAction(DocVoiceService.ACTION_STOP))
        continuous = false
        voiceButton.text = "ACTIVATE VOICE CORE\nWake-word listening • screen-off capable"
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
                override fun onReadyForSpeech(params: Bundle?) { setStatus("TALK ONCE • VOICE CHANNEL OPEN") }
                override fun onBeginningOfSpeech() { setStatus("TALK ONCE • HEARING YOU") }
                override fun onEndOfSpeech() { setStatus("TALK ONCE • PROCESSING") }
                override fun onError(error: Int) { setStatus("VOICE CHANNEL READY") }
                override fun onResults(results: Bundle?) {
                    val spoken = results?.getStringArrayList(SpeechRecognizer.RESULTS_RECOGNITION)?.firstOrNull().orEmpty()
                    transcript.text = if (spoken.isBlank()) "No command detected." else "VOICE INPUT  ›  $spoken"
                    if (spoken.isNotBlank()) doc.execute(spoken)
                }
                override fun onPartialResults(partialResults: Bundle?) {
                    partialResults?.getStringArrayList(SpeechRecognizer.RESULTS_RECOGNITION)?.firstOrNull()?.takeIf { it.isNotBlank() }?.let {
                        transcript.text = "VOICE INPUT  ›  $it"
                    }
                }
                override fun onRmsChanged(rmsdB: Float) {
                    if (rmsdB > 3) {
                        orb.scaleX = 1.02f
                        orb.scaleY = 1.02f
                    }
                }
                override fun onBufferReceived(buffer: ByteArray?) = Unit
                override fun onEvent(eventType: Int, params: Bundle?) = Unit
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
        } catch (_: Throwable) {
            setStatus("VOICE ENGINE BUSY")
        }
    }

    private fun refreshStates() {
        val micReady = ContextCompat.checkSelfPermission(this, Manifest.permission.RECORD_AUDIO) == PackageManager.PERMISSION_GRANTED
        micChip.text = if (micReady) "MIC  READY" else "MIC  LOCKED"
        micChip.setTextColor(if (micReady) green else muted)

        val enabled = Settings.Secure.getString(contentResolver, Settings.Secure.ENABLED_ACCESSIBILITY_SERVICES).orEmpty().contains(packageName, true)
        accessChip.text = if (enabled) "ACCESS  ON" else "ACCESS  OFF"
        accessChip.setTextColor(if (enabled) green else muted)
        accessibilityButton.text = if (enabled) "ACCESSIBILITY CORE • CONNECTED\nCross-app control is ready" else "CONNECT ACCESSIBILITY CORE\nCross-app taps • typing • swipes • screen reading"

        try {
            val pm = getSystemService(POWER_SERVICE) as PowerManager
            val exempt = android.os.Build.VERSION.SDK_INT < 23 || pm.isIgnoringBatteryOptimizations(packageName)
            powerChip.text = if (exempt) "POWER  FREE" else "POWER  LIMITED"
            powerChip.setTextColor(if (exempt) green else muted)
            batteryButton.text = if (exempt) "PROTECT VOICE CORE • EXEMPT\nBattery optimization is already disabled" else "PROTECT VOICE CORE\nDisable battery sleep for reliable background listening"
        } catch (_: Throwable) { }
    }

    override fun onResume() {
        super.onResume()
        refreshStates()
    }

    override fun onDestroy() {
        recognizer?.destroy()
        recognizer = null
        doc.destroy()
        super.onDestroy()
    }
}
