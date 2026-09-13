package com.prince.eyenav

import android.Manifest
import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.Service
import android.content.Intent
import android.content.pm.PackageManager
import android.os.Build
import android.os.Handler
import android.os.IBinder
import android.os.Looper
import android.speech.RecognitionListener
import android.speech.RecognizerIntent
import android.speech.SpeechRecognizer
import androidx.core.app.NotificationCompat
import androidx.core.content.ContextCompat

/** Persistent voice channel. Started by a visible user action; keeps Doc listening while you switch apps. */
class DocVoiceService : Service() {
    companion object {
        const val ACTION_START = "com.prince.eyenav.DOC_START"
        const val ACTION_STOP = "com.prince.eyenav.DOC_STOP"
        private const val CHANNEL_ID = "doc_voice_core"
        private const val NOTIFICATION_ID = 901
    }

    private val handler = Handler(Looper.getMainLooper())
    private var recognizer: SpeechRecognizer? = null
    private var assistant: DocAssistant? = null
    private var listening = true
    private var restarting = false

    override fun onCreate() {
        super.onCreate()
        createChannel(); startForegroundCompat()
        assistant = DocAssistant(this).also { a ->
            a.onStatus = { updateNotification(it) }
            a.onListeningState = { enabled -> listening = enabled; if (enabled) scheduleListen(200) else stopRecognizer() }
        }
        setupRecognizer()
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        when (intent?.action) {
            ACTION_STOP -> { stopRecognizer(); stopSelf(); return START_NOT_STICKY }
            ACTION_START -> { listening = true; scheduleListen(100) }
        }
        return START_STICKY
    }

    private fun setupRecognizer() {
        if (!SpeechRecognizer.isRecognitionAvailable(this)) return
        recognizer = SpeechRecognizer.createSpeechRecognizer(this)
        recognizer?.setRecognitionListener(object : RecognitionListener {
            override fun onReadyForSpeech(params: android.os.Bundle?) { updateNotification("VOICE CHANNEL • LISTENING") }
            override fun onBeginningOfSpeech() { updateNotification("VOICE CHANNEL • HEARING YOU") }
            override fun onEndOfSpeech() { updateNotification("VOICE CHANNEL • THINKING") }
            override fun onError(error: Int) { scheduleListen(if (error == SpeechRecognizer.ERROR_RECOGNIZER_BUSY) 1200 else 500) }
            override fun onResults(results: android.os.Bundle?) {
                val text = results?.getStringArrayList(SpeechRecognizer.RESULTS_RECOGNITION)?.firstOrNull().orEmpty()
                if (text.isNotBlank() && listening) assistant?.execute(text)
                scheduleListen(500)
            }
            override fun onPartialResults(partialResults: android.os.Bundle?) {
                partialResults?.getStringArrayList(SpeechRecognizer.RESULTS_RECOGNITION)?.firstOrNull()?.takeIf { it.isNotBlank() }?.let { updateNotification("HEARING • $it") }
            }
            override fun onBufferReceived(buffer: ByteArray?) = Unit
            override fun onEvent(eventType: Int, params: android.os.Bundle?) = Unit
            override fun onRmsChanged(rmsdB: Float) = Unit
        })
    }

    private fun scheduleListen(delay: Long) {
        if (!listening || recognizer == null || restarting) return
        restarting = true
        handler.postDelayed({
            restarting = false
            if (!listening) return@postDelayed
            if (ContextCompat.checkSelfPermission(this, Manifest.permission.RECORD_AUDIO) != PackageManager.PERMISSION_GRANTED) {
                updateNotification("MICROPHONE PERMISSION REQUIRED"); return@postDelayed
            }
            try {
                recognizer?.cancel()
                recognizer?.startListening(Intent(RecognizerIntent.ACTION_RECOGNIZE_SPEECH).apply {
                    putExtra(RecognizerIntent.EXTRA_LANGUAGE_MODEL, RecognizerIntent.LANGUAGE_MODEL_FREE_FORM)
                    putExtra(RecognizerIntent.EXTRA_LANGUAGE, java.util.Locale.getDefault())
                    putExtra(RecognizerIntent.EXTRA_MAX_RESULTS, 5)
                    putExtra(RecognizerIntent.EXTRA_PARTIAL_RESULTS, true)
                    putExtra(RecognizerIntent.EXTRA_PROMPT, "Doc listening")
                })
            } catch (_: Throwable) { scheduleListen(1500) }
        }, delay)
    }

    private fun stopRecognizer() {
        listening = false; handler.removeCallbacksAndMessages(null)
        try { recognizer?.cancel(); recognizer?.destroy() } catch (_: Throwable) { }
        recognizer = null
    }

    private fun createChannel() {
        if (Build.VERSION.SDK_INT >= 26) {
            val channel = NotificationChannel(CHANNEL_ID, "Doc voice core", NotificationManager.IMPORTANCE_LOW).apply { description = "Persistent Doc voice control" }
            getSystemService(NotificationManager::class.java).createNotificationChannel(channel)
        }
    }

    private fun notification(text: String): Notification = NotificationCompat.Builder(this, CHANNEL_ID)
        .setSmallIcon(android.R.drawable.ic_btn_speak_now)
        .setContentTitle("DOC // PERSONAL INTELLIGENCE")
        .setContentText(text.take(120))
        .setOngoing(true)
        .setCategory(NotificationCompat.CATEGORY_SERVICE)
        .setSilent(true)
        .build()

    private fun startForegroundCompat() {
        if (Build.VERSION.SDK_INT >= 30) startForeground(NOTIFICATION_ID, notification("VOICE CHANNEL STARTING"), android.content.pm.ServiceInfo.FOREGROUND_SERVICE_TYPE_MICROPHONE)
        else startForeground(NOTIFICATION_ID, notification("VOICE CHANNEL STARTING"))
    }

    private fun updateNotification(text: String) { getSystemService(NotificationManager::class.java).notify(NOTIFICATION_ID, notification(text)) }

    override fun onDestroy() { stopRecognizer(); assistant?.destroy(); assistant = null; super.onDestroy() }
    override fun onBind(intent: Intent?): IBinder? = null
}
