package com.prince.eyenav

import android.Manifest
import android.content.Intent
import android.content.pm.PackageManager
import android.graphics.Color
import android.os.Bundle
import android.provider.Settings
import android.speech.RecognitionListener
import android.speech.SpeechRecognizer
import android.view.Gravity
import android.widget.Button
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

    private val audioPermission = registerForActivityResult(ActivityResultContracts.RequestPermission()) { granted ->
        if (granted) listen() else status.text = "Microphone permission is required for voice commands."
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        doc = DocAssistant(this).also { it.onStatus = { runOnUiThread { status.text = it } } }
        buildUi()
        setupRecognizer()
    }

    private fun buildUi() {
        val root = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            gravity = Gravity.CENTER
            setPadding(34, 40, 34, 34)
            setBackgroundColor(Color.rgb(7, 9, 14))
        }
        root.addView(TextView(this).apply {
            text = "DOC"
            textSize = 48f
            gravity = Gravity.CENTER
            setTextColor(Color.WHITE)
        }, LinearLayout.LayoutParams(-1, 80))
        root.addView(TextView(this).apply {
            text = "Your Android voice command assistant"
            textSize = 16f
            gravity = Gravity.CENTER
            setTextColor(Color.LTGRAY)
        }, LinearLayout.LayoutParams(-1, 48))
        status = TextView(this).apply {
            text = "Standing by."
            textSize = 18f
            gravity = Gravity.CENTER
            setTextColor(Color.WHITE)
            setPadding(8, 20, 8, 20)
        }
        root.addView(status, LinearLayout.LayoutParams(-1, 110))
        transcript = TextView(this).apply {
            text = "Say: open WhatsApp • read screen • go home • scroll down"
            textSize = 14f
            gravity = Gravity.CENTER
            setTextColor(Color.GRAY)
        }
        root.addView(transcript, LinearLayout.LayoutParams(-1, 90))
        root.addView(button("🎙  Talk to Doc") { requestAndListen() })
        root.addView(button("♿  Enable Doc Accessibility") {
            startActivity(Intent(Settings.ACTION_ACCESSIBILITY_SETTINGS))
        })
        root.addView(button("Test voice") { doc.speak("Systems online. Doc is ready.") })
        root.addView(TextView(this).apply {
            text = "Accessibility lets Doc read visible UI text and perform navigation gestures. Android still requires the system permission and confirmation for sensitive actions."
            textSize = 12f
            gravity = Gravity.CENTER
            setTextColor(Color.DKGRAY)
            setPadding(4, 24, 4, 4)
        }, LinearLayout.LayoutParams(-1, 90))
        setContentView(root)
    }

    private fun button(label: String, action: () -> Unit) = Button(this).apply {
        text = label; isAllCaps = false; textSize = 15f; setOnClickListener { action() }
    }

    private fun setupRecognizer() {
        if (!SpeechRecognizer.isRecognitionAvailable(this)) {
            status.text = "Speech recognition is unavailable on this device."
            return
        }
        recognizer = SpeechRecognizer.createSpeechRecognizer(this).also { r ->
            r.setRecognitionListener(object : RecognitionListener {
                override fun onReadyForSpeech(params: Bundle?) { status.text = "Listening…" }
                override fun onBeginningOfSpeech() { status.text = "I’m listening." }
                override fun onEndOfSpeech() { status.text = "Processing…" }
                override fun onError(error: Int) { status.text = "I didn't catch that. Tap Talk to try again." }
                override fun onResults(results: Bundle?) {
                    val text = results?.getStringArrayList(SpeechRecognizer.RESULTS_RECOGNITION)?.firstOrNull().orEmpty()
                    transcript.text = if (text.isBlank()) "No command detected." else "You: $text"
                    if (text.isNotBlank()) doc.execute(text)
                }
                override fun onPartialResults(partialResults: Bundle?) = Unit
                override fun onBufferReceived(buffer: ByteArray?) = Unit
                override fun onEvent(eventType: Int, params: Bundle?) = Unit
                override fun onRmsChanged(rmsdB: Float) = Unit
                override fun onError(error: Int, message: String?) = Unit
            })
        }
    }

    private fun requestAndListen() {
        if (ContextCompat.checkSelfPermission(this, Manifest.permission.RECORD_AUDIO) != PackageManager.PERMISSION_GRANTED) {
            audioPermission.launch(Manifest.permission.RECORD_AUDIO)
        } else listen()
    }

    private fun listen() { recognizer?.startListening(doc.recognizerIntent()) }

    override fun onDestroy() {
        recognizer?.destroy(); recognizer = null
        doc.destroy()
        super.onDestroy()
    }
}
