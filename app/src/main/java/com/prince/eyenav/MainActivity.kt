package com.prince.eyenav

import android.Manifest
import android.content.Intent
import android.content.pm.PackageManager
import android.graphics.Color
import android.graphics.drawable.GradientDrawable
import android.net.Uri
import android.os.Bundle
import android.os.PowerManager
import android.provider.Settings
import android.speech.RecognitionListener
import android.speech.SpeechRecognizer
import android.view.Gravity
import android.widget.Button
import android.widget.HorizontalScrollView
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView
import androidx.activity.ComponentActivity
import androidx.activity.result.contract.ActivityResultContracts
import androidx.core.content.ContextCompat

/** DOC // command deck. Voice Core keeps running after app switching and while the display is off. */
class MainActivity : ComponentActivity() {
    private lateinit var doc: DocAssistant
    private var recognizer: SpeechRecognizer? = null
    private lateinit var status: TextView
    private lateinit var transcript: TextView
    private lateinit var orb: TextView
    private lateinit var voiceButton: Button
    private lateinit var accessibilityButton: Button
    private lateinit var batteryButton: Button
    private var continuous = false
    private val pulse = android.os.Handler(android.os.Looper.getMainLooper())

    private val audioPermission = registerForActivityResult(ActivityResultContracts.RequestPermission()) { granted ->
        if (granted) listenOnce() else status.text = "MICROPHONE ACCESS REQUIRED"
    }
    private val notificationPermission = registerForActivityResult(ActivityResultContracts.RequestPermission()) { }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        window.setStatusBarColor(Color.rgb(2, 5, 10)); window.setNavigationBarColor(Color.rgb(2, 5, 10))
        doc = DocAssistant(this).also { it.onStatus = { text -> runOnUiThread { status.text = text; pulseOrb() } } }
        buildUi(); setupRecognizer(); pulseOrb(); refreshAccessibilityState(); refreshBatteryState()
        if (android.os.Build.VERSION.SDK_INT >= 33 && ContextCompat.checkSelfPermission(this, Manifest.permission.POST_NOTIFICATIONS) != PackageManager.PERMISSION_GRANTED) notificationPermission.launch(Manifest.permission.POST_NOTIFICATIONS)
    }

    private fun bg(color: Int, radius: Float = 28f, stroke: Int = 0, strokeColor: Int = Color.TRANSPARENT) = GradientDrawable().apply {
        setColor(color); cornerRadius = radius
        if (stroke > 0) setStroke(stroke, strokeColor)
    }

    private fun buildUi() {
        val root = ScrollView(this).apply { setBackgroundColor(Color.rgb(2, 5, 10)) }
        val body = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL; gravity = Gravity.CENTER_HORIZONTAL; setPadding(24, 28, 24, 28) }

        body.addView(TextView(this).apply {
            text = "D  O  C"; textSize = 32f; letterSpacing = .30f; gravity = Gravity.CENTER; setTextColor(Color.rgb(112, 224, 255))
        }, LinearLayout.LayoutParams(-1, 60))
        body.addView(TextView(this).apply {
            text = "PERSONAL INTELLIGENCE  //  LOCAL COGNITIVE CORE"; textSize = 9f; letterSpacing = .11f; gravity = Gravity.CENTER; setTextColor(Color.rgb(90, 115, 135))
        }, LinearLayout.LayoutParams(-1, 32))

        orb = TextView(this).apply {
            text = "◉"; textSize = 78f; gravity = Gravity.CENTER; setTextColor(Color.rgb(115, 235, 255)); background = bg(Color.rgb(5, 22, 33), 120f, 2, Color.rgb(50, 170, 205))
        }
        val orbParams = LinearLayout.LayoutParams(210, 210); orbParams.setMargins(0, 20, 0, 18); body.addView(orb, orbParams)

        status = TextView(this).apply {
            text = "SYSTEMS NOMINAL"; textSize = 15f; gravity = Gravity.CENTER; setTextColor(Color.WHITE); setPadding(12, 10, 12, 10); background = bg(Color.rgb(8, 16, 25), 20f, 1, Color.rgb(30, 62, 82))
        }
        body.addView(status, LinearLayout.LayoutParams(-1, 58))
        transcript = TextView(this).apply { text = "Voice channel standing by…"; textSize = 13f; gravity = Gravity.CENTER; setTextColor(Color.rgb(145, 170, 188)); setPadding(10, 8, 10, 8) }
        body.addView(transcript, LinearLayout.LayoutParams(-1, 55))

        voiceButton = button("◉   ACTIVATE JARVIS VOICE CORE") { toggleVoiceCore() }
        voiceButton.background = bg(Color.rgb(11, 72, 94), 22f, 1, Color.rgb(70, 205, 235)); body.addView(voiceButton, LinearLayout.LayoutParams(-1, 62))
        body.addView(button("◌   TALK ONCE") { requestAndListenOnce() }, LinearLayout.LayoutParams(-1, 56))
        accessibilityButton = button("♿   CONNECT ACCESSIBILITY CORE") { startActivity(Intent(Settings.ACTION_ACCESSIBILITY_SETTINGS)) }
        body.addView(accessibilityButton, LinearLayout.LayoutParams(-1, 56))
        batteryButton = button("⚡   PROTECT VOICE CORE FROM BATTERY SLEEP") { requestBatteryExemption() }
        body.addView(batteryButton, LinearLayout.LayoutParams(-1, 56))

        val chips = HorizontalScrollView(this).apply { isHorizontalScrollBarEnabled = false }
        val row = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL }
        listOf("Open WhatsApp", "Read screen", "Go home", "Battery", "Search for Delhi", "Turn flashlight on").forEach { command ->
            val b = Button(this).apply { text = command; isAllCaps = false; textSize = 11f; setTextColor(Color.LTGRAY); background = bg(Color.rgb(8, 16, 24), 18f, 1, Color.rgb(28, 53, 68)); setOnClickListener { doc.execute(command) } }
            val p = LinearLayout.LayoutParams(-2, 48); p.setMargins(4, 4, 4, 4); row.addView(b, p)
        }
        chips.addView(row); body.addView(chips, LinearLayout.LayoutParams(-1, 58))

        body.addView(button("🔊   TEST CINEMATIC DOC VOICE") { doc.speak("All systems online. Voice channel stable. Local intelligence core ready.") }, LinearLayout.LayoutParams(-1, 54))
        body.addView(TextView(this).apply {
            text = "LOCK-SCREEN MODE  •  SAY: DOC, OPEN WHATSAPP\n\nVoice Core uses a microphone foreground service, CPU wake lock and brief display wake on commands. Disable battery optimization for DOC for the most reliable screen-off operation. Accessibility unlocks cross-app taps, typing, swipes and screen reading."
            textSize = 9f; gravity = Gravity.CENTER; setTextColor(Color.rgb(75, 100, 118)); setPadding(6, 18, 6, 12)
        }, LinearLayout.LayoutParams(-1, 110))

        root.addView(body); setContentView(root)
    }

    private fun button(label: String, action: () -> Unit) = Button(this).apply {
        text = label; isAllCaps = false; textSize = 12f; setTextColor(Color.LTGRAY); background = bg(Color.rgb(7, 14, 22), 20f, 1, Color.rgb(25, 48, 62)); setOnClickListener { action() }
    }

    private fun toggleVoiceCore() {
        if (continuous) stopVoiceCore() else requestAndStartVoiceCore()
    }

    private fun requestAndStartVoiceCore() {
        if (ContextCompat.checkSelfPermission(this, Manifest.permission.RECORD_AUDIO) != PackageManager.PERMISSION_GRANTED) { audioPermission.launch(Manifest.permission.RECORD_AUDIO); return }
        startVoiceCore()
    }

    private fun startVoiceCore() {
        val intent = Intent(this, DocVoiceService::class.java).setAction(DocVoiceService.ACTION_START)
        ContextCompat.startForegroundService(this, intent)
        continuous = true; voiceButton.text = "◉   VOICE CORE ONLINE  //  TAP TO SLEEP"; status.text = "JARVIS VOICE CORE • ACTIVE • LOCK-SCREEN READY"; pulseOrb()
    }

    private fun stopVoiceCore() {
        stopService(Intent(this, DocVoiceService::class.java).setAction(DocVoiceService.ACTION_STOP))
        continuous = false; voiceButton.text = "◉   ACTIVATE JARVIS VOICE CORE"; status.text = "VOICE CORE STANDBY"; pulseOrb()
    }

    private fun requestBatteryExemption() {
        try {
            val pm = getSystemService(POWER_SERVICE) as PowerManager
            if (android.os.Build.VERSION.SDK_INT >= 23 && !pm.isIgnoringBatteryOptimizations(packageName)) {
                startActivity(Intent(Settings.ACTION_REQUEST_IGNORE_BATTERY_OPTIMIZATIONS, Uri.parse("package:$packageName")))
            } else {
                status.text = "BATTERY OPTIMIZATION ALREADY DISABLED FOR DOC"
            }
        } catch (_: Throwable) {
            try { startActivity(Intent(Settings.ACTION_IGNORE_BATTERY_OPTIMIZATION_SETTINGS)) } catch (_: Throwable) { status.text = "OPEN BATTERY SETTINGS MANUALLY" }
        }
    }

    private fun refreshBatteryState() {
        try {
            val pm = getSystemService(POWER_SERVICE) as PowerManager
            batteryButton.text = if (android.os.Build.VERSION.SDK_INT >= 23 && pm.isIgnoringBatteryOptimizations(packageName)) "⚡   BATTERY PROTECTION • DOC EXEMPT" else "⚡   PROTECT VOICE CORE FROM BATTERY SLEEP"
        } catch (_: Throwable) { }
    }

    private fun setupRecognizer() {
        if (!SpeechRecognizer.isRecognitionAvailable(this)) return
        recognizer = SpeechRecognizer.createSpeechRecognizer(this).also { r ->
            r.setRecognitionListener(object : RecognitionListener {
                override fun onReadyForSpeech(params: Bundle?) { status.text = "LISTENING • VOICE CHANNEL OPEN"; pulseOrb() }
                override fun onBeginningOfSpeech() { status.text = "HEARING YOU"; pulseOrb() }
                override fun onEndOfSpeech() { status.text = "PROCESSING" }
                override fun onError(error: Int) { status.text = "VOICE CHANNEL READY" }
                override fun onResults(results: Bundle?) {
                    val text = results?.getStringArrayList(SpeechRecognizer.RESULTS_RECOGNITION)?.firstOrNull().orEmpty()
                    transcript.text = if (text.isBlank()) "No command detected." else "VOICE INPUT  ›  $text"
                    if (text.isNotBlank()) doc.execute(text)
                }
                override fun onPartialResults(partialResults: Bundle?) { partialResults?.getStringArrayList(SpeechRecognizer.RESULTS_RECOGNITION)?.firstOrNull()?.takeIf { it.isNotBlank() }?.let { transcript.text = "VOICE INPUT  ›  $it" } }
                override fun onBufferReceived(buffer: ByteArray?) = Unit
                override fun onEvent(eventType: Int, params: Bundle?) = Unit
                override fun onRmsChanged(rmsdB: Float) { if (rmsdB > 4) pulseOrb() }
            })
        }
    }

    private fun requestAndListenOnce() {
        if (ContextCompat.checkSelfPermission(this, Manifest.permission.RECORD_AUDIO) != PackageManager.PERMISSION_GRANTED) audioPermission.launch(Manifest.permission.RECORD_AUDIO) else listenOnce()
    }

    private fun listenOnce() { try { recognizer?.cancel(); recognizer?.startListening(doc.recognizerIntent()) } catch (_: Throwable) { status.text = "VOICE ENGINE BUSY" } }

    private fun pulseOrb() {
        if (!::orb.isInitialized) return
        orb.animate().scaleX(1.055f).scaleY(1.055f).setDuration(220).withEndAction { orb.animate().scaleX(1f).scaleY(1f).setDuration(380).start() }.start()
    }

    private fun refreshAccessibilityState() {
        val enabled = Settings.Secure.getString(contentResolver, Settings.Secure.ENABLED_ACCESSIBILITY_SERVICES).orEmpty().contains(packageName, true)
        accessibilityButton.text = if (enabled) "♿   ACCESSIBILITY CORE • CONNECTED" else "♿   CONNECT ACCESSIBILITY CORE"
    }

    override fun onResume() { super.onResume(); refreshAccessibilityState(); refreshBatteryState() }

    override fun onDestroy() {
        pulse.removeCallbacksAndMessages(null); recognizer?.destroy(); recognizer = null; doc.destroy(); super.onDestroy()
    }
}
