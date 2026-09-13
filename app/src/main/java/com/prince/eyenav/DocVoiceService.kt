package com.prince.eyenav

import android.Manifest
import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Intent
import android.content.pm.PackageManager
import android.os.Build
import android.os.Handler
import android.os.IBinder
import android.os.PowerManager
import android.speech.RecognitionListener
import android.speech.RecognizerIntent
import android.speech.SpeechRecognizer
import androidx.core.app.NotificationCompat
import androidx.core.content.ContextCompat

/** DOC voice core: local Vosk wake gate + Android SpeechRecognizer command capture. */
class DocVoiceService : Service() {
    companion object {
        const val ACTION_START = "com.prince.eyenav.DOC_START"
        const val ACTION_STOP = "com.prince.eyenav.DOC_STOP"
        private const val CHANNEL_ID = "doc_voice_core"
        private const val NOTIFICATION_ID = 901
        private const val COMMAND_TIMEOUT = 9000L
        private const val RESTART_HOTWORD = 2200L
    }

    private val handler = Handler(android.os.Looper.getMainLooper())
    private var assistant: DocAssistant? = null
    private var hotword: LocalHotwordEngine? = null
    private var commandRecognizer: SpeechRecognizer? = null
    private var commandListening = false
    private var listeningEnabled = true
    private var wakeLock: PowerManager.WakeLock? = null

    override fun onCreate() {
        super.onCreate()
        createChannel()
        startForegroundCompat()
        acquireWakeLock()
        assistant = DocAssistant(this).also { a ->
            a.onStatus = { updateNotification(it) }
            a.onListeningState = { enabled ->
                listeningEnabled = enabled
                prefs().edit().putBoolean("enabled", enabled).apply()
                if (enabled) startHotword() else stopVoiceInput()
            }
        }
        setupCommandRecognizer()
        startHotword()
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        when (intent?.action) {
            ACTION_STOP -> {
                prefs().edit().putBoolean("enabled", false).apply()
                stopVoiceInput()
                stopSelf()
                return START_NOT_STICKY
            }
            ACTION_START -> {
                listeningEnabled = true
                prefs().edit().putBoolean("enabled", true).apply()
                startHotword()
            }
        }
        return START_STICKY
    }

    private fun prefs() = getSharedPreferences("doc_voice", MODE_PRIVATE)

    private fun acquireWakeLock() {
        try {
            val pm = getSystemService(POWER_SERVICE) as PowerManager
            wakeLock = pm.newWakeLock(PowerManager.PARTIAL_WAKE_LOCK, "DOC:VoiceCore").apply {
                setReferenceCounted(false)
                acquire()
            }
        } catch (_: Throwable) { }
    }

    private fun startHotword() {
        if (!listeningEnabled) return
        stopCommandRecognizer()
        hotword?.stop()
        hotword = LocalHotwordEngine(this, { phrase ->
            hotword?.stop(); hotword = null
            if (phrase.isBlank()) {
                assistant?.speak("Yes?")
                updateNotification("DOC • AWAKE • LISTENING")
                startCommandRecognizer()
            } else executeCommand(phrase)
        }, { updateNotification(it) })
        hotword?.start()
    }

    private fun setupCommandRecognizer() {
        if (!SpeechRecognizer.isRecognitionAvailable(this)) return
        try {
            commandRecognizer?.destroy()
            commandRecognizer = if (Build.VERSION.SDK_INT >= 31 && SpeechRecognizer.isOnDeviceRecognitionAvailable(this)) {
                SpeechRecognizer.createOnDeviceSpeechRecognizer(this)
            } else SpeechRecognizer.createSpeechRecognizer(this)
            commandRecognizer?.setRecognitionListener(object : RecognitionListener {
                override fun onReadyForSpeech(params: android.os.Bundle?) { updateNotification("DOC • LISTENING FOR COMMAND") }
                override fun onBeginningOfSpeech() { updateNotification("DOC • HEARING COMMAND") }
                override fun onEndOfSpeech() { updateNotification("DOC • PROCESSING COMMAND") }
                override fun onResults(results: android.os.Bundle?) {
                    commandListening = false
                    val text = results?.getStringArrayList(SpeechRecognizer.RESULTS_RECOGNITION)?.firstOrNull()?.trim().orEmpty()
                    if (text.isBlank()) { updateNotification("DOC • NO COMMAND HEARD"); scheduleHotword(700) }
                    else executeCommand(text)
                }
                override fun onError(error: Int) {
                    commandListening = false
                    scheduleHotword(if (error == SpeechRecognizer.ERROR_TOO_MANY_REQUESTS) 2500 else 500)
                }
                override fun onPartialResults(partialResults: android.os.Bundle?) { }
                override fun onBufferReceived(buffer: ByteArray?) { }
                override fun onEvent(eventType: Int, params: android.os.Bundle?) { }
                override fun onRmsChanged(rmsdB: Float) { }
            })
        } catch (_: Throwable) { commandRecognizer = null }
    }

    private fun startCommandRecognizer() {
        if (!listeningEnabled) return
        if (commandRecognizer == null) setupCommandRecognizer()
        val r = commandRecognizer ?: run { updateNotification("DOC • SPEECH RECOGNIZER UNAVAILABLE"); scheduleHotword(1000); return }
        if (ContextCompat.checkSelfPermission(this, Manifest.permission.RECORD_AUDIO) != PackageManager.PERMISSION_GRANTED) {
            updateNotification("DOC • MICROPHONE PERMISSION REQUIRED"); return
        }
        try {
            commandListening = true
            r.cancel()
            val intent = Intent(RecognizerIntent.ACTION_RECOGNIZE_SPEECH).apply {
                putExtra(RecognizerIntent.EXTRA_LANGUAGE_MODEL, RecognizerIntent.LANGUAGE_MODEL_FREE_FORM)
                putExtra(RecognizerIntent.EXTRA_LANGUAGE, "en-IN")
                putExtra(RecognizerIntent.EXTRA_MAX_RESULTS, 5)
                putExtra(RecognizerIntent.EXTRA_PARTIAL_RESULTS, false)
                putExtra(RecognizerIntent.EXTRA_PREFER_OFFLINE, true)
                putExtra(RecognizerIntent.EXTRA_SPEECH_INPUT_COMPLETE_SILENCE_LENGTH_MILLIS, 1400L)
                putExtra(RecognizerIntent.EXTRA_SPEECH_INPUT_POSSIBLY_COMPLETE_SILENCE_LENGTH_MILLIS, 1000L)
                putExtra(RecognizerIntent.EXTRA_SPEECH_INPUT_MINIMUM_LENGTH_MILLIS, 500L)
            }
            r.startListening(intent)
            handler.postDelayed({ if (commandListening) { stopCommandRecognizer(); scheduleHotword(300) } }, COMMAND_TIMEOUT)
        } catch (_: Throwable) { commandListening = false; scheduleHotword(900) }
    }

    private fun executeCommand(command: String) {
        commandListening = false
        stopCommandRecognizer()
        updateNotification("DOC • EXECUTING • ${command.take(80)}")
        assistant?.execute(command)
        scheduleHotword(RESTART_HOTWORD)
    }

    private fun scheduleHotword(delay: Long) {
        if (!listeningEnabled) return
        handler.removeCallbacksAndMessages(null)
        handler.postDelayed({ if (listeningEnabled) startHotword() }, delay)
    }

    private fun stopCommandRecognizer() {
        commandListening = false
        try { commandRecognizer?.cancel() } catch (_: Throwable) { }
    }

    private fun stopVoiceInput() {
        listeningEnabled = false
        handler.removeCallbacksAndMessages(null)
        hotword?.stop(); hotword = null
        stopCommandRecognizer()
    }

    private fun createChannel() {
        if (Build.VERSION.SDK_INT >= 26) getSystemService(NotificationManager::class.java).createNotificationChannel(
            NotificationChannel(CHANNEL_ID, "Doc voice core", NotificationManager.IMPORTANCE_LOW)
        )
    }

    private fun notification(text: String): Notification {
        val openIntent = Intent(this, MainActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_SINGLE_TOP)
        val pi = PendingIntent.getActivity(this, 1, openIntent, PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE)
        return NotificationCompat.Builder(this, CHANNEL_ID)
            .setSmallIcon(android.R.drawable.ic_btn_speak_now)
            .setContentTitle("DOC // PERSONAL INTELLIGENCE")
            .setContentText(text.take(120))
            .setContentIntent(pi)
            .setOngoing(true)
            .setSilent(true)
            .setCategory(NotificationCompat.CATEGORY_SERVICE)
            .setPriority(NotificationCompat.PRIORITY_LOW)
            .build()
    }

    private fun startForegroundCompat() {
        if (Build.VERSION.SDK_INT >= 30) startForeground(NOTIFICATION_ID, notification("VOICE CORE STARTING"), android.content.pm.ServiceInfo.FOREGROUND_SERVICE_TYPE_MICROPHONE)
        else startForeground(NOTIFICATION_ID, notification("VOICE CORE STARTING"))
    }

    private fun updateNotification(text: String) {
        getSystemService(NotificationManager::class.java).notify(NOTIFICATION_ID, notification(text))
    }

    override fun onTaskRemoved(rootIntent: Intent?) {
        if (listeningEnabled) {
            try {
                val restart = Intent(applicationContext, DocVoiceService::class.java).setAction(ACTION_START)
                if (Build.VERSION.SDK_INT >= 26) ContextCompat.startForegroundService(applicationContext, restart)
                else applicationContext.startService(restart)
            } catch (_: Throwable) { }
        }
        super.onTaskRemoved(rootIntent)
    }

    override fun onDestroy() {
        stopVoiceInput()
        try { commandRecognizer?.destroy() } catch (_: Throwable) { }
        commandRecognizer = null
        assistant?.destroy(); assistant = null
        try { wakeLock?.release() } catch (_: Throwable) { }
        wakeLock = null
        super.onDestroy()
    }

    override fun onBind(intent: Intent?): IBinder? = null
}
