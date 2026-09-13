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
import android.os.PowerManager
import android.speech.RecognitionListener
import android.speech.RecognizerIntent
import android.speech.SpeechRecognizer
import androidx.core.app.NotificationCompat
import androidx.core.content.ContextCompat
import java.util.Locale

/** DOC always-ready voice core. */
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
    private var wakeLock: PowerManager.WakeLock? = null
    private var displayWakeLock: PowerManager.WakeLock? = null

    override fun onCreate() {
        super.onCreate()
        createChannel()
        startForegroundCompat()
        acquireCpuLock()
        assistant = DocAssistant(this).also { a ->
            a.onStatus = { updateNotification(it) }
            a.onListeningState = { enabled ->
                listening = enabled
                if (enabled) scheduleListen(200) else stopRecognizer()
            }
        }
        setupRecognizer()
        scheduleListen(350)
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        when (intent?.action) {
            ACTION_STOP -> {
                stopRecognizer()
                stopSelf()
                return START_NOT_STICKY
            }
            ACTION_START -> {
                listening = true
                acquireCpuLock()
                setupRecognizerIfNeeded()
                scheduleListen(100)
            }
        }
        return START_STICKY
    }

    private fun setupRecognizerIfNeeded() {
        if (recognizer == null) setupRecognizer()
    }

    private fun setupRecognizer() {
        if (!SpeechRecognizer.isRecognitionAvailable(this)) {
            updateNotification("VOICE ENGINE UNAVAILABLE")
            return
        }
        try {
            recognizer?.destroy()
            recognizer = if (Build.VERSION.SDK_INT >= 31 && SpeechRecognizer.isOnDeviceRecognitionAvailable(this)) {
                SpeechRecognizer.createOnDeviceSpeechRecognizer(this)
            } else {
                SpeechRecognizer.createSpeechRecognizer(this)
            }
            recognizer?.setRecognitionListener(object : RecognitionListener {
                override fun onReadyForSpeech(params: android.os.Bundle?) { updateNotification("DOC • SCREEN-OFF READY • SAY DOC") }
                override fun onBeginningOfSpeech() { updateNotification("DOC • HEARING YOU") }
                override fun onEndOfSpeech() { updateNotification("DOC • PROCESSING") }
                override fun onError(error: Int) {
                    scheduleListen(if (error == SpeechRecognizer.ERROR_RECOGNIZER_BUSY) 1200L else 350L)
                }
                override fun onResults(results: android.os.Bundle?) {
                    val heard = results?.getStringArrayList(SpeechRecognizer.RESULTS_RECOGNITION)?.firstOrNull().orEmpty()
                    val command = wakeCommand(heard)
                    if (command != null && listening) {
                        updateNotification("DOC • EXECUTING • ${command.take(70)}")
                        wakeDisplayBriefly()
                        assistant?.execute(command)
                    } else if (heard.isNotBlank()) {
                        updateNotification("DOC • STANDBY • SAY DOC FIRST")
                    }
                    scheduleListen(250)
                }
                override fun onPartialResults(partialResults: android.os.Bundle?) {
                    val heard = partialResults?.getStringArrayList(SpeechRecognizer.RESULTS_RECOGNITION)?.firstOrNull().orEmpty()
                    if (heard.contains(Regex("\\b(doc|hey doc|okay doc|ok doc)\\b", RegexOption.IGNORE_CASE))) {
                        updateNotification("DOC • WAKE WORD DETECTED")
                    }
                }
                override fun onBufferReceived(buffer: ByteArray?) = Unit
                override fun onEvent(eventType: Int, params: android.os.Bundle?) = Unit
                override fun onRmsChanged(rmsdB: Float) = Unit
            })
        } catch (_: Throwable) {
            recognizer = null
            updateNotification("DOC • VOICE ENGINE RETRYING")
            scheduleListen(1500)
        }
    }

    private fun wakeCommand(raw: String): String? {
        val text = raw.trim()
        if (text.isBlank()) return null
        val match = Regex("^(?:hey\\s+|okay\\s+|ok\\s+)?doc\\b\\s*(.*)$", RegexOption.IGNORE_CASE).find(text)
            ?: return null
        val command = match.groupValues.getOrNull(1)?.trim().orEmpty()
        return command.ifBlank { "speak Say the command." }
    }

    private fun scheduleListen(delay: Long) {
        if (!listening || restarting) return
        if (recognizer == null) setupRecognizerIfNeeded()
        if (recognizer == null) return
        restarting = true
        handler.postDelayed({
            restarting = false
            if (!listening) return@postDelayed
            if (ContextCompat.checkSelfPermission(this, Manifest.permission.RECORD_AUDIO) != PackageManager.PERMISSION_GRANTED) {
                updateNotification("DOC • MICROPHONE PERMISSION REQUIRED")
                return@postDelayed
            }
            try {
                recognizer?.cancel()
                recognizer?.startListening(Intent(RecognizerIntent.ACTION_RECOGNIZE_SPEECH).apply {
                    putExtra(RecognizerIntent.EXTRA_LANGUAGE_MODEL, RecognizerIntent.LANGUAGE_MODEL_FREE_FORM)
                    putExtra(RecognizerIntent.EXTRA_LANGUAGE, Locale.getDefault())
                    putExtra(RecognizerIntent.EXTRA_MAX_RESULTS, 5)
                    putExtra(RecognizerIntent.EXTRA_PARTIAL_RESULTS, true)
                    putExtra(RecognizerIntent.EXTRA_PREFER_OFFLINE, true)
                    putExtra(RecognizerIntent.EXTRA_PROMPT, "Say Doc followed by a command")
                })
            } catch (_: Throwable) {
                scheduleListen(1000)
            }
        }, delay)
    }

    private fun acquireCpuLock() {
        if (wakeLock?.isHeld == true) return
        val pm = getSystemService(POWER_SERVICE) as PowerManager
        wakeLock = pm.newWakeLock(PowerManager.PARTIAL_WAKE_LOCK, "DOC:VoiceCore").apply {
            setReferenceCounted(false)
            acquire()
        }
    }

    /** Briefly wakes the display so a command such as "Doc, open WhatsApp" can visibly launch. */
    private fun wakeDisplayBriefly() {
        try {
            val pm = getSystemService(POWER_SERVICE) as PowerManager
            displayWakeLock?.let { if (it.isHeld) it.release() }
            @Suppress("DEPRECATION")
            displayWakeLock = pm.newWakeLock(
                PowerManager.SCREEN_BRIGHT_WAKE_LOCK or PowerManager.ACQUIRE_CAUSES_WAKEUP,
                "DOC:CommandWake"
            ).apply {
                setReferenceCounted(false)
                acquire(3500L)
            }
        } catch (_: Throwable) { }
    }

    private fun stopRecognizer() {
        listening = false
        handler.removeCallbacksAndMessages(null)
        try { recognizer?.cancel(); recognizer?.destroy() } catch (_: Throwable) { }
        recognizer = null
    }

    private fun createChannel() {
        if (Build.VERSION.SDK_INT >= 26) {
            val channel = NotificationChannel(CHANNEL_ID, "Doc voice core", NotificationManager.IMPORTANCE_LOW)
                .apply { description = "Persistent Doc voice control" }
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
        if (Build.VERSION.SDK_INT >= 30) {
            startForeground(
                NOTIFICATION_ID,
                notification("VOICE CORE STARTING • LOCK-SCREEN READY"),
                android.content.pm.ServiceInfo.FOREGROUND_SERVICE_TYPE_MICROPHONE
            )
        } else {
            startForeground(NOTIFICATION_ID, notification("VOICE CORE STARTING • LOCK-SCREEN READY"))
        }
    }

    private fun updateNotification(text: String) {
        getSystemService(NotificationManager::class.java).notify(NOTIFICATION_ID, notification(text))
    }

    override fun onDestroy() {
        stopRecognizer()
        assistant?.destroy()
        assistant = null
        try { wakeLock?.let { if (it.isHeld) it.release() } } catch (_: Throwable) { }
        try { displayWakeLock?.let { if (it.isHeld) it.release() } } catch (_: Throwable) { }
        wakeLock = null
        displayWakeLock = null
        super.onDestroy()
    }

    override fun onBind(intent: Intent?): IBinder? = null
}
