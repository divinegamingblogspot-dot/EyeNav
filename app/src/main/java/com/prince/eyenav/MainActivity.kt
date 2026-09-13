package com.prince.eyenav

import android.Manifest
import android.content.Intent
import android.content.pm.PackageManager
import android.graphics.Color
import android.graphics.drawable.GradientDrawable
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.provider.Settings
import android.speech.RecognitionListener
import android.speech.SpeechRecognizer
import android.view.Gravity
import android.widget.Button
import android.widget.HorizontalScrollView
import android.widget.LinearLayout
import android.widget.TextView
import androidx.activity.ComponentActivity
import androidx.activity.result.contract.ActivityResultContracts
import androidx.core.content.ContextCompat

class MainActivity : ComponentActivity() {
    private lateinit var doc: DocAssistant
    private var recognizer: SpeechRecognizer? = null
    private lateinit var status: TextView
    private lateinit var transcript: TextView
    private lateinit var orb: TextView
    private var continuous = false
    private val pulse = Handler(Looper.getMainLooper())
    private val audioPermission = registerForActivityResult(ActivityResultContracts.RequestPermission()) { granted ->
        if (granted) listen() else status.text = "Microphone permission is required."
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        doc = DocAssistant(this).also { it.onStatus = { text -> runOnUiThread { status.text = text; pulseOrb() } } }
        buildUi()
        setupRecognizer()
        pulseOrb()
    }

    private fun bg(color: Int, radius: Float = 28f, stroke: Int = 0, strokeColor: Int = Color.TRANSPARENT) = GradientDrawable().apply {
        setColor(color); cornerRadius = radius
        if (stroke > 0) setStroke(stroke, strokeColor)
    }

    private fun buildUi() {
        val root = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            gravity = Gravity.CENTER_HORIZONTAL
            setPadding(24, 22, 24, 22)
            setBackgroundColor(Color.rgb(3, 7, 13))
        }
        root.addView(TextView(this).apply {
            text = "D O C"; textSize = 30f; letterSpacing = .28f; gravity = Gravity.CENTER
            setTextColor(Color.rgb(120, 220, 255))
        }, LinearLayout.LayoutParams(-1, 55))
        root.addView(TextView(this).apply {
            text = "PERSONAL INTELLIGENCE SYSTEM  •  OFFLINE CORE"; textSize = 9f; letterSpacing = .14f; gravity = Gravity.CENTER
            setTextColor(Color.rgb(105, 125, 145))
        }, LinearLayout.LayoutParams(-1, 30))
        orb = TextView(this).apply {
            text = "◉"; textSize = 76f; gravity = Gravity.CENTER
            setTextColor(Color.rgb(90, 215, 255)); background = bg(Color.rgb(6, 20, 30), 100f, 2, Color.rgb(45, 150, 190))
        }
        val orbParams = LinearLayout.LayoutParams(190, 190); orbParams.setMargins(0, 18, 0, 16); root.addView(orb, orbParams)
        status = TextView(this).apply {
            text = "SYSTEMS NOMINAL"; textSize = 17f; gravity = Gravity.CENTER; setTextColor(Color.WHITE)
            background = bg(Color.rgb(9, 17, 27), 20f, 1, Color.rgb(35, 65, 85)); setPadding(12, 12, 12, 12)
        }
        root.addView(status, LinearLayout.LayoutParams(-1, 58))
        transcript = TextView(this).apply {
            text = "Awaiting command…"; textSize = 13f; gravity = Gravity.CENTER_VERTICAL; setTextColor(Color.rgb(160, 180, 195)); setPadding(16, 0, 16, 0)
        }
        root.addView(transcript, LinearLayout.LayoutParams(-1, 52))
        val talk = button("◉   TALK TO DOC") { requestAndListen() }
        talk.background = bg(Color.rgb(12, 64, 84), 22f, 1, Color.rgb(75, 190, 225)); root.addView(talk, LinearLayout.LayoutParams(-1, 58))
        val chips = HorizontalScrollView(this).apply { isHorizontalScrollBarEnabled = false }
        val row = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL; gravity = Gravity.CENTER_VERTICAL }
        listOf("Open WhatsApp", "Read screen", "Battery", "What time is it?", "Go home").forEach { command ->
            val b = Button(this).apply {
                text = command; isAllCaps = false; textSize = 11f; setTextColor(Color.LTGRAY)
                background = bg(Color.rgb(10, 18, 27), 18f, 1, Color.rgb(30, 55, 70)); setOnClickListener { doc.execute(command) }
            }
            val p = LinearLayout.LayoutParams(-2, 48); p.setMargins(4, 4, 4, 4); row.addView(b, p)
        }
        chips.addView(row); root.addView(chips, LinearLayout.LayoutParams(-1, 58))
        root.addView(button("♾   ALWAYS LISTEN") {
            continuous = !continuous; status.text = if (continuous) "ALWAYS LISTENING • ACTIVE" else "ALWAYS LISTENING • OFF"
            if (continuous) requestAndListen()
        })
        root.addView(button("♿   ACCESSIBILITY CORE") { startActivity(Intent(Settings.ACTION_ACCESSIBILITY_SETTINGS)) })
        root.addView(button("🔊   TEST DOC VOICE") { doc.speak("All systems are online. I am Doc. Your local command core is ready.") })
        root.addView(TextView(this).apply {
            text = "LOCAL CORE  •  ZERO API KEY  •  ZERO AI DAILY QUOTA\nAccessibility unlocks app control, navigation, taps, typing, swipes and readable-screen commands."
            textSize = 9f; gravity = Gravity.CENTER; setTextColor(Color.rgb(85, 105, 120)); setPadding(4, 14, 4, 0)
        }, LinearLayout.LayoutParams(-1, 58))
        setContentView(root)
    }

    private fun button(label: String, action: () -> Unit) = Button(this).apply {
        text = label; isAllCaps = false; textSize = 13f; setTextColor(Color.LTGRAY)
        background = bg(Color.rgb(8, 15, 23), 20f, 1, Color.rgb(28, 50, 65)); setOnClickListener { action() }
    }

    private fun pulseOrb() {
        if (!::orb.isInitialized) return
        orb.animate().scaleX(1.05f).scaleY(1.05f).setDuration(260).withEndAction { orb.animate().scaleX(1f).scaleY(1f).setDuration(360).start() }.start()
    }

    private fun setupRecognizer() {
        if (!SpeechRecognizer.isRecognitionAvailable(this)) { status.text = "Speech recognition unavailable."; return }
        recognizer = SpeechRecognizer.createSpeechRecognizer(this).also { r ->
            r.setRecognitionListener(object : RecognitionListener {
                override fun onReadyForSpeech(params: Bundle?) { status.text = "LISTENING • VOICE CHANNEL OPEN"; pulseOrb() }
                override fun onBeginningOfSpeech() { status.text = "I'M LISTENING"; pulseOrb() }
                override fun onEndOfSpeech() { status.text = "PROCESSING COMMAND" }
                override fun onError(error: Int) { status.text = "VOICE CHANNEL READY"; if (continuous) window.decorView.postDelayed({ listen() }, 700) }
                override fun onResults(results: Bundle?) {
                    val text = results?.getStringArrayList(SpeechRecognizer.RESULTS_RECOGNITION)?.firstOrNull().orEmpty()
                    transcript.text = if (text.isBlank()) "No command detected." else "VOICE INPUT  ›  $text"
                    if (text.isNotBlank()) doc.execute(text)
                    if (continuous) window.decorView.postDelayed({ listen() }, 900)
                }
                override fun onPartialResults(partialResults: Bundle?) { partialResults?.getStringArrayList(SpeechRecognizer.RESULTS_RECOGNITION)?.firstOrNull()?.let { if (it.isNotBlank()) transcript.text = "VOICE INPUT  ›  $it" } }
                override fun onBufferReceived(buffer: ByteArray?) = Unit
                override fun onEvent(eventType: Int, params: Bundle?) = Unit
                override fun onRmsChanged(rmsdB: Float) { if (rmsdB > 4) pulseOrb() }
            })
        }
    }

    private fun requestAndListen() {
        if (ContextCompat.checkSelfPermission(this, Manifest.permission.RECORD_AUDIO) != PackageManager.PERMISSION_GRANTED) audioPermission.launch(Manifest.permission.RECORD_AUDIO) else listen()
    }
    private fun listen() { if (!isFinishing) recognizer?.startListening(doc.recognizerIntent()) }
    override fun onDestroy() { pulse.removeCallbacksAndMessages(null); recognizer?.destroy(); recognizer = null; doc.destroy(); super.onDestroy() }
}
