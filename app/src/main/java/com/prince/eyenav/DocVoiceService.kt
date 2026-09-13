package com.prince.eyenav

import android.Manifest
import android.app.KeyguardManager
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

/** DOC // persistent wake-word voice core. Uses short recognition sessions because Android SpeechRecognizer is not a continuous-recognition API. */
class DocVoiceService : Service() {
    companion object {
        const val ACTION_START = "com.prince.eyenav.DOC_START"
        const val ACTION_STOP = "com.prince.eyenav.DOC_STOP"
        private const val CHANNEL_ID = "doc_voice_core"
        private const val NOTIFICATION_ID = 901
        private const val UNLOCK_POLL_MS = 650L
        private const val COMMAND_WINDOW_MS = 8000L
        private const val POST_COMMAND_COOLDOWN_MS = 3200L
        private const val WAKE_CONFIDENCE = 0.55f
    }

    private val handler = Handler(Looper.getMainLooper())
    private var recognizer: SpeechRecognizer? = null
    private var assistant: DocAssistant? = null
    private var listening = true
    private var starting = false
    private var wakeLock: PowerManager.WakeLock? = null
    private var displayWakeLock: PowerManager.WakeLock? = null
    private var pendingLockedCommand: String? = null
    private var unlockWatcherActive = false
    private var waitingForCommand = false
    private var commandWindowUntil = 0L
    private var cooldownUntil = 0L

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
        scheduleListen(500)
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
                scheduleListen(200)
            }
        }
        if (pendingLockedCommand != null) watchForUnlock()
        return START_STICKY
    }

    override fun onTaskRemoved(rootIntent: Intent?) {
        if (listening) {
            try {
                ContextCompat.startForegroundService(
                    this,
                    Intent(this, DocVoiceService::class.java).setAction(ACTION_START)
                )
            } catch (_: Throwable) { }
        }
        super.onTaskRemoved(rootIntent)
    }

    private fun setupRecognizerIfNeeded() {
        if (recognizer == null) setupRecognizer()
    }

    private fun setupRecognizer() {
        if (!SpeechRecognizer.isRecognitionAvailable(this)) {
            updateNotification("DOC • VOICE ENGINE UNAVAILABLE")
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
                override fun onReadyForSpeech(params: android.os.Bundle?) {
                    updateNotification(if (waitingForCommand) "DOC • LISTENING FOR COMMAND" else "DOC • STANDBY • SAY DOC")
                }

                override fun onBeginningOfSpeech() {
                    updateNotification(if (waitingForCommand) "DOC • HEARING COMMAND" else "DOC • HEARING WAKE WORD")
                }

                override fun onEndOfSpeech() {
                    updateNotification(if (waitingForCommand) "DOC • PROCESSING COMMAND" else "DOC • CHECKING WAKE WORD")
                }

                override fun onError(error: Int) {
                    starting = false
                    val delay = when (error) {
                        SpeechRecognizer.ERROR_RECOGNIZER_BUSY -> 1000L
                        SpeechRecognizer.ERROR_TOO_MANY_REQUESTS -> 2500L
                        SpeechRecognizer.ERROR_INSUFFICIENT_PERMISSIONS -> 3000L
                        else -> 350L
                    }
                    scheduleListen(delay)
                }

                override fun onResults(results: android.os.Bundle?) {
                    starting = false
                    val heard = results?.getStringArrayList(SpeechRecognizer.RESULTS_RECOGNITION)
                        ?.firstOrNull().orEmpty().trim()
                    val confidence = results?.getFloatArray(SpeechRecognizer.CONFIDENCE_SCORES)?.firstOrNull() ?: -1f
                    handleSpeechResult(heard, confidence)
                }

                override fun onPartialResults(partialResults: android.os.Bundle?) {
                    // Never execute or arm DOC from partial recognition. Partial results are often unstable.
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

    private fun handleSpeechResult(raw: String, confidence: Float) {
        val text = raw
            .replace(Regex("[^\\p{L}\\p{N}\\s]"), " ")
            .replace(Regex("\\s+"), " ")
            .trim()
        if (text.isBlank()) {
            scheduleListen(250)
            return
        }

        // A recognizer that is not confident enough must never wake DOC from background speech/noise.
        if (confidence >= 0f && confidence < WAKE_CONFIDENCE && !waitingForCommand) {
            scheduleListen(250)
            return
        }

        val commandFromWake = wakeCommand(text)
        if (commandFromWake != null) {
            val command = commandFromWake
            if (command.isBlank()) {
                waitingForCommand = true
                commandWindowUntil = System.currentTimeMillis() + COMMAND_WINDOW_MS
                updateNotification("DOC • AWAKE • SAY YOUR COMMAND")
                scheduleListen(150)
            } else {
                runCommand(command)
            }
            return
        }

        // Once DOC has heard its wake word, the next utterance is the command.
        if (waitingForCommand && System.currentTimeMillis() <= commandWindowUntil) {
            waitingForCommand = false
            commandWindowUntil = 0L
            runCommand(text)
            return
        }

        waitingForCommand = false
        commandWindowUntil = 0L
        scheduleListen(250)
    }

    private fun runCommand(command: String) {
        waitingForCommand = false
        commandWindowUntil = 0L
        cooldownUntil = System.currentTimeMillis() + POST_COMMAND_COOLDOWN_MS
        stopRecognizerForCommand()

        if (isDeviceLocked()) {
            queueUntilUnlocked(command)
            return
        }

        updateNotification("DOC • EXECUTING • ${command.take(70)}")
        wakeDisplayBriefly()
        assistant?.execute(command)
        // Do not immediately reopen the microphone. This prevents DOC's own TTS confirmation from becoming a new command.
        scheduleListen(POST_COMMAND_COOLDOWN_MS)
    }

    private fun isWakePhrase(text: String): Boolean = wakeCommand(text) != null

    /** Only these explicit phrases can wake DOC. Ordinary speech is ignored. */
    private fun wakeCommand(raw: String): String? {
        val text = raw.trim().replace(Regex("\\s+"), " ")
        if (text.isBlank()) return null
        val match = Regex(
            "^(?:hey\\s+|okay\\s+|ok\\s+)?doc(?:\\s+(.*))?$",
            RegexOption.IGNORE_CASE
        ).find(text) ?: return null
        return match.groupValues.getOrNull(1)?.trim().orEmpty()
    }

    private fun isDeviceLocked(): Boolean =
        getSystemService(KeyguardManager::class.java)?.isKeyguardLocked == true

    private fun queueUntilUnlocked(command: String) {
        pendingLockedCommand = command
        cooldownUntil = System.currentTimeMillis() + POST_COMMAND_COOLDOWN_MS
        wakeDisplayBriefly()
        updateNotification("DOC • LOCKED • COMMAND QUEUED")
        assistant?.speak("Please unlock your phone. I will continue automatically.")
        watchForUnlock()
    }

    private fun watchForUnlock() {
        if (unlockWatcherActive) return
        unlockWatcherActive = true
        handler.post(unlockPoll)
    }

    private val unlockPoll = object : Runnable {
        override fun run() {
            val pending = pendingLockedCommand
            if (pending == null) {
                unlockWatcherActive = false
                return
            }
            if (!isDeviceLocked()) {
                pendingLockedCommand = null
                unlockWatcherActive = false
                cooldownUntil = System.currentTimeMillis() + POST_COMMAND_COOLDOWN_MS
                wakeDisplayBriefly()
                updateNotification("DOC • UNLOCKED • RESUMING")
                assistant?.execute(pending)
                scheduleListen(POST_COMMAND_COOLDOWN_MS)
                return
            }
            handler.postDelayed(this, UNLOCK_POLL_MS)
        }
    }

    private fun scheduleListen(delay: Long) {
        if (!listening) return
        if (recognizer == null) setupRecognizerIfNeeded()
        if (recognizer == null) return

        val now = System.currentTimeMillis()
        val actualDelay = maxOf(delay, cooldownUntil - now, 120L)
        handler.postDelayed({
            if (!listening || starting) return@postDelayed
            val remaining = cooldownUntil - System.currentTimeMillis()
            if (remaining > 0) {
                scheduleListen(remaining + 100L)
                return@postDelayed
            }
            if (ContextCompat.checkSelfPermission(this, Manifest.permission.RECORD_AUDIO) != PackageManager.PERMISSION_GRANTED) {
                updateNotification("DOC • MICROPHONE PERMISSION REQUIRED")
                return@postDelayed
            }
            try {
                starting = true
                recognizer?.cancel()
                val intent = Intent(RecognizerIntent.ACTION_RECOGNIZE_SPEECH).apply {
                    putExtra(RecognizerIntent.EXTRA_LANGUAGE_MODEL, RecognizerIntent.LANGUAGE_MODEL_FREE_FORM)
                    putExtra(RecognizerIntent.EXTRA_LANGUAGE, "en-IN")
                    putExtra(RecognizerIntent.EXTRA_LANGUAGE_PREFERENCE, "en-IN")
                    putExtra(RecognizerIntent.EXTRA_MAX_RESULTS, 1)
                    putExtra(RecognizerIntent.EXTRA_PARTIAL_RESULTS, false)
                    putExtra(RecognizerIntent.EXTRA_PREFER_OFFLINE, true)
                    putExtra(RecognizerIntent.EXTRA_PROMPT, "")
                    putExtra(RecognizerIntent.EXTRA_SPEECH_INPUT_COMPLETE_SILENCE_LENGTH_MILLIS, 800L)
                    putExtra(RecognizerIntent.EXTRA_SPEECH_INPUT_POSSIBLY_COMPLETE_SILENCE_LENGTH_MILLIS, 550L)
                    putExtra(RecognizerIntent.EXTRA_SPEECH_INPUT_MINIMUM_LENGTH_MILLIS, 350L)
                }
                recognizer?.startListening(intent)
            } catch (_: Throwable) {
                starting = false
                scheduleListen(900)
            }
        }, actualDelay)
    }

    private fun stopRecognizerForCommand() {
        try { recognizer?.cancel() } catch (_: Throwable) { }
        starting = false
    }

    private fun acquireCpuLock() {
        if (wakeLock?.isHeld == true) return
        val pm = getSystemService(POWER_SERVICE) as PowerManager
        wakeLock = pm.newWakeLock(PowerManager.PARTIAL_WAKE_LOCK, "DOC:VoiceCore").apply {
            setReferenceCounted(false)
            acquire()
        }
    }

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
        unlockWatcherActive = false
        starting = false
        waitingForCommand = false
        commandWindowUntil = 0L
    }

    private fun createChannel() {
        if (Build.VERSION.SDK_INT >= 26) {
            val channel = NotificationChannel(
                CHANNEL_ID,
                "Doc voice core",
                NotificationManager.IMPORTANCE_LOW
            ).apply { description = "Persistent Doc wake-word voice control" }
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
                notification("VOICE CORE STARTING • SAY DOC"),
                android.content.pm.ServiceInfo.FOREGROUND_SERVICE_TYPE_MICROPHONE
            )
        } else {
            startForeground(NOTIFICATION_ID, notification("VOICE CORE STARTING • SAY DOC"))
        }
    }

    private fun updateNotification(text: String) {
        getSystemService(NotificationManager::class.java)
            .notify(NOTIFICATION_ID, notification(text))
    }

    override fun onDestroy() {
        stopRecognizer()
        assistant?.destroy()
        assistant = null
        pendingLockedCommand = null
        try { wakeLock?.let { if (it.isHeld) it.release() } } catch (_: Throwable) { }
        try { displayWakeLock?.let { if (it.isHeld) it.release() } } catch (_: Throwable) { }
        wakeLock = null
        displayWakeLock = null
        super.onDestroy()
    }

    override fun onBind(intent: Intent?): IBinder? = null
}
