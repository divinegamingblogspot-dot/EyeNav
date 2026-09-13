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

/** DOC // persistent wake-word voice core. Audio stays in the Android speech pipeline; no credentials are stored. */
class DocVoiceService : Service() {
    companion object {
        const val ACTION_START = "com.prince.eyenav.DOC_START"
        const val ACTION_STOP = "com.prince.eyenav.DOC_STOP"
        private const val CHANNEL_ID = "doc_voice_core"
        private const val NOTIFICATION_ID = 901
        private const val UNLOCK_POLL_MS = 650L
        private const val COMMAND_WINDOW_MS = 7500L
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
    private var lastWakeAt = 0L

    override fun onCreate() {
        super.onCreate()
        createChannel()
        startForegroundCompat()
        acquireCpuLock()
        assistant = DocAssistant(this).also { a ->
            a.onStatus = { updateNotification(it) }
            a.onListeningState = { enabled ->
                listening = enabled
                if (enabled) scheduleListen(150) else stopRecognizer()
            }
        }
        setupRecognizer()
        scheduleListen(300)
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
        checkPendingAfterResume()
        return START_STICKY
    }

    override fun onTaskRemoved(rootIntent: Intent?) {
        // Ask Android to keep the user-enabled voice core alive when the launcher task is swiped away.
        if (listening) {
            try {
                ContextCompat.startForegroundService(this, Intent(this, DocVoiceService::class.java).setAction(ACTION_START))
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
                    updateNotification(if (waitingForCommand) "DOC • HEARING COMMAND" else "DOC • CHECKING WAKE WORD")
                }

                override fun onEndOfSpeech() {
                    updateNotification(if (waitingForCommand) "DOC • PROCESSING COMMAND" else "DOC • WAKE STANDBY")
                }

                override fun onError(error: Int) {
                    // Recognition sessions are intentionally short. Restart quietly so one failed session cannot kill wake mode.
                    starting = false
                    val delay = when (error) {
                        SpeechRecognizer.ERROR_RECOGNIZER_BUSY -> 900L
                        SpeechRecognizer.ERROR_INSUFFICIENT_PERMISSIONS -> 2500L
                        else -> 180L
                    }
                    scheduleListen(delay)
                }

                override fun onResults(results: android.os.Bundle?) {
                    starting = false
                    val heard = results?.getStringArrayList(SpeechRecognizer.RESULTS_RECOGNITION)
                        ?.firstOrNull().orEmpty().trim()
                    handleSpeechResult(heard)
                }

                override fun onPartialResults(partialResults: android.os.Bundle?) {
                    val heard = partialResults?.getStringArrayList(SpeechRecognizer.RESULTS_RECOGNITION)
                        ?.firstOrNull().orEmpty().trim()
                    if (heard.isBlank()) return
                    if (isWakePhrase(heard)) {
                        lastWakeAt = System.currentTimeMillis()
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
            scheduleListen(1200)
        }
    }

    private fun handleSpeechResult(raw: String) {
        val text = raw.replace(Regex("[^\\p{L}\\p{N}\\s]"), " ")
            .replace(Regex("\\s+"), " ").trim()
        if (text.isBlank()) {
            scheduleListen(120)
            return
        }

        val commandFromWake = wakeCommand(text)
        if (commandFromWake != null) {
            val command = commandFromWake
            lastWakeAt = System.currentTimeMillis()
            if (command.isBlank()) {
                // User said only “Doc”. Open a short command window instead of speaking over the microphone.
                waitingForCommand = true
                commandWindowUntil = System.currentTimeMillis() + COMMAND_WINDOW_MS
                updateNotification("DOC • AWAKE • SAY YOUR COMMAND")
                scheduleListen(100)
                return
            }
            runCommand(command)
            return
        }

        // After “Doc”, accept the next utterance without requiring the wake word again.
        if (waitingForCommand && System.currentTimeMillis() <= commandWindowUntil) {
            waitingForCommand = false
            commandWindowUntil = 0L
            runCommand(text)
            return
        }

        // Ignore ordinary speech while in wake-word standby.
        waitingForCommand = false
        commandWindowUntil = 0L
        updateNotification("DOC • STANDBY • SAY DOC")
        scheduleListen(120)
    }

    private fun runCommand(command: String) {
        waitingForCommand = false
        commandWindowUntil = 0L
        stopRecognizerForCommand()
        if (isDeviceLocked()) {
            queueUntilUnlocked(command)
        } else {
            updateNotification("DOC • EXECUTING • ${command.take(70)}")
            wakeDisplayBriefly()
            assistant?.execute(command)
            // Give TTS/actions time to finish before opening the next recognition session.
            scheduleListen(1700)
        }
    }

    private fun isWakePhrase(text: String): Boolean = wakeCommand(text) != null

    /** Accepts Doc / Hey Doc / Okay Doc / Ok Doc, with punctuation removed before matching. */
    private fun wakeCommand(raw: String): String? {
        val text = raw.trim()
        if (text.isBlank()) return null
        val match = Regex("^(?:hey\\s+|okay\\s+|ok\\s+)?doc\\b\\s*(.*)$", RegexOption.IGNORE_CASE).find(text)
            ?: return null
        return match.groupValues.getOrNull(1)?.trim().orEmpty()
    }

    private fun isDeviceLocked(): Boolean =
        getSystemService(KeyguardManager::class.java)?.isKeyguardLocked == true

    private fun queueUntilUnlocked(command: String) {
        pendingLockedCommand = command
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
                wakeDisplayBriefly()
                updateNotification("DOC • UNLOCKED • RESUMING")
                assistant?.execute(pending)
                scheduleListen(1700)
                return
            }
            handler.postDelayed(this, UNLOCK_POLL_MS)
        }
    }

    private fun checkPendingAfterResume() {
        if (pendingLockedCommand != null) watchForUnlock()
    }

    private fun scheduleListen(delay: Long) {
        if (!listening) return
        if (recognizer == null) setupRecognizerIfNeeded()
        if (recognizer == null) return
        handler.postDelayed({
            if (!listening || starting) return@postDelayed
            if (ContextCompat.checkSelfPermission(this, Manifest.permission.RECORD_AUDIO) != PackageManager.PERMISSION_GRANTED) {
                updateNotification("DOC • MICROPHONE PERMISSION REQUIRED")
                return@postDelayed
            }
            try {
                starting = true
                recognizer?.cancel()
                val intent = Intent(RecognizerIntent.ACTION_RECOGNIZE_SPEECH).apply {
                    putExtra(RecognizerIntent.EXTRA_LANGUAGE_MODEL, RecognizerIntent.LANGUAGE_MODEL_FREE_FORM)
                    putExtra(RecognizerIntent.EXTRA_LANGUAGE, Locale.getDefault())
                    putExtra(RecognizerIntent.EXTRA_MAX_RESULTS, 5)
                    putExtra(RecognizerIntent.EXTRA_PARTIAL_RESULTS, true)
                    putExtra(RecognizerIntent.EXTRA_PREFER_OFFLINE, true)
                    // No prompt: avoids recognition UI/audio feedback on devices that implement it.
                    putExtra(RecognizerIntent.EXTRA_PROMPT, "")
                    putExtra(RecognizerIntent.EXTRA_SPEECH_INPUT_COMPLETE_SILENCE_LENGTH_MILLIS, 650L)
                    putExtra(RecognizerIntent.EXTRA_SPEECH_INPUT_POSSIBLY_COMPLETE_SILENCE_LENGTH_MILLIS, 450L)
                    putExtra(RecognizerIntent.EXTRA_SPEECH_INPUT_MINIMUM_LENGTH_MILLIS, 250L)
                }
                recognizer?.startListening(intent)
            } catch (_: Throwable) {
                starting = false
                scheduleListen(700)
            }
        }, delay)
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
            val channel = NotificationChannel(CHANNEL_ID, "Doc voice core", NotificationManager.IMPORTANCE_LOW)
                .apply { description = "Persistent Doc wake-word voice control" }
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
        getSystemService(NotificationManager::class.java).notify(NOTIFICATION_ID, notification(text))
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
