package com.prince.eyenav

import android.content.Context
import android.content.Intent
import android.media.AudioManager
import android.media.session.MediaSession
import android.net.Uri
import android.os.BatteryManager
import android.os.Handler
import android.os.Looper
import android.provider.AlarmClock
import android.provider.Settings
import android.speech.RecognizerIntent
import android.speech.tts.TextToSpeech
import android.speech.tts.Voice
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import kotlin.math.max

/** DOC // execution engine. Offline-first, API-key-free, voice-controlled Android actions. */
class DocAssistant(private val context: Context) : TextToSpeech.OnInitListener {
    private var tts: TextToSpeech? = TextToSpeech(context, this)
    private val brain = DocBrain(context)
    private val handler = Handler(Looper.getMainLooper())
    private var ready = false
    private var pendingWhatsApp = false
    private var torchOn = false
    private var lastCommand = ""
    var onStatus: ((String) -> Unit)? = null
    var onListeningState: ((Boolean) -> Unit)? = null

    override fun onInit(status: Int) {
        ready = status == TextToSpeech.SUCCESS
        if (!ready) return
        val t = tts ?: return
        try {
            t.language = Locale.US
            t.setSpeechRate(0.84f)
            t.setPitch(0.72f)
            t.voices?.firstOrNull { it.locale.language == "en" && it.name.contains("en-us", true) }?.let { t.voice = it }
        } catch (_: Throwable) { }
    }

    fun speak(text: String) {
        val clean = text.trim().replace(Regex("\\s+"), " ")
        if (clean.isBlank()) return
        onStatus?.invoke(clean)
        if (ready) tts?.speak(clean, TextToSpeech.QUEUE_FLUSH, null, "DOC_${System.nanoTime()}")
    }

    fun recognizerIntent() = Intent(RecognizerIntent.ACTION_RECOGNIZE_SPEECH).apply {
        putExtra(RecognizerIntent.EXTRA_LANGUAGE_MODEL, RecognizerIntent.LANGUAGE_MODEL_FREE_FORM)
        putExtra(RecognizerIntent.EXTRA_LANGUAGE, Locale.getDefault())
        putExtra(RecognizerIntent.EXTRA_LANGUAGE_PREFERENCE, Locale.getDefault().toLanguageTag())
        putExtra(RecognizerIntent.EXTRA_MAX_RESULTS, 5)
        putExtra(RecognizerIntent.EXTRA_PARTIAL_RESULTS, true)
        putExtra(RecognizerIntent.EXTRA_CALLING_PACKAGE, context.packageName)
        putExtra(RecognizerIntent.EXTRA_PROMPT, "Listening for Doc")
    }

    fun hasAiKey() = true
    fun setAiKey(key: String) = Unit

    fun execute(raw: String) {
        val c = raw.trim()
        if (c.isBlank()) return
        lastCommand = c
        val service = EyeNavAccessibilityService.instance
        brain.think(c, service?.readVisibleText().orEmpty()) { actions, _, error ->
            handler.post {
                if (error != null) { speak(error); return@post }
                if (actions.isEmpty()) { speak("No command detected."); return@post }
                actions.forEach { runAction(it) }
            }
        }
    }

    private fun runAction(a: DocBrain.Action) {
        val s = EyeNavAccessibilityService.instance
        when (a.type.lowercase(Locale.getDefault())) {
            "home" -> { s?.goHome(); speak("Home screen.") }
            "back" -> { s?.goBack(); speak("Going back.") }
            "recents" -> { s?.openRecents(); speak("Recent apps.") }
            "notifications" -> { s?.openNotifications(); speak("Notifications.") }
            "quick_settings" -> { s?.openQuickSettings(); speak("Quick settings.") }
            "scroll" -> { if (s == null) speak("Accessibility core is offline.") else s.swipe(a.direction) }
            "click_text" -> if (!(s?.clickText(a.text) ?: false)) speak("I could not locate ${a.text}.") else speak("Done.")
            "long_click_text" -> if (!(s?.clickText(a.text, true) ?: false)) speak("I could not locate ${a.text}.") else speak("Long press complete.")
            "type_text" -> if (!(s?.typeText(a.text) ?: false)) speak("There is no active editable field.") else speak("Entered.")
            "open_app" -> openApp(a.name)
            "read_screen" -> {
                val t = s?.readVisibleText().orEmpty()
                speak(if (t.isBlank()) "The current app exposes no readable text." else t.take(2200))
            }
            "time" -> speak("The time is ${SimpleDateFormat("h:mm a", Locale.US).format(Date())}.")
            "date" -> speak("Today is ${SimpleDateFormat("EEEE, d MMMM yyyy", Locale.US).format(Date())}.")
            "battery" -> {
                val bm = context.getSystemService(Context.BATTERY_SERVICE) as BatteryManager
                val level = bm.getIntProperty(BatteryManager.BATTERY_PROPERTY_CAPACITY)
                speak("Battery is at $level percent.")
            }
            "volume" -> changeVolume(a.direction)
            "flashlight" -> toggleTorch(a.direction)
            "brightness" -> changeBrightness(a.direction)
            "wifi" -> openSettings(Settings.ACTION_WIFI_SETTINGS, "Wi-Fi controls")
            "bluetooth" -> openSettings(Settings.ACTION_BLUETOOTH_SETTINGS, "Bluetooth controls")
            "settings" -> openSettings(Settings.ACTION_SETTINGS, "Settings")
            "accessibility_settings" -> openSettings(Settings.ACTION_ACCESSIBILITY_SETTINGS, "Accessibility settings")
            "web_search" -> searchWeb(a.text)
            "maps" -> navigate(a.text)
            "call" -> dial(a.text)
            "sms" -> sms(a.text)
            "timer" -> setTimer(a.text)
            "alarm" -> setAlarm(a.text)
            "screenshot" -> if (s?.takeScreenshot() == true) speak("Screen capture requested.") else speak("Screen capture is unavailable on this Android version or without Accessibility.")
            "media" -> media(a.direction)
            "whatsapp_message" -> prepareWhatsAppMessage(a.name, a.text)
            "send_pending" -> sendPendingWhatsApp()
            "remember" -> remember(a.text)
            "stop_listening" -> { onListeningState?.invoke(false); speak("Going quiet. Say wake up Doc from the Doc screen to resume.") }
            "resume_listening" -> { onListeningState?.invoke(true); speak("Voice channel restored.") }
            "speak" -> speak(a.text)
        }
    }

    private fun openSettings(action: String, label: String) {
        try { context.startActivity(Intent(action).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)); speak("Opening $label.") }
        catch (_: Throwable) { speak("I cannot open $label on this device.") }
    }

    private fun searchWeb(query: String) {
        val q = query.trim(); if (q.isBlank()) { speak("What should I search for?"); return }
        try {
            context.startActivity(Intent(Intent.ACTION_WEB_SEARCH).apply { putExtra("query", q); addFlags(Intent.FLAG_ACTIVITY_NEW_TASK) })
            speak("Searching for $q.")
        } catch (_: Throwable) { context.startActivity(Intent(Intent.ACTION_VIEW, Uri.parse("https://www.google.com/search?q=${Uri.encode(q)}")).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)); speak("Opening search.") }
    }

    private fun navigate(destination: String) {
        val d = destination.trim(); if (d.isBlank()) { speak("Where should we go?"); return }
        val uri = Uri.parse("google.navigation:q=${Uri.encode(d)}")
        try { context.startActivity(Intent(Intent.ACTION_VIEW, uri).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)); speak("Navigating to $d.") }
        catch (_: Throwable) { context.startActivity(Intent(Intent.ACTION_VIEW, Uri.parse("https://www.google.com/maps/search/?api=1&query=${Uri.encode(d)}")).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)); speak("Opening maps.") }
    }

    private fun dial(target: String) {
        val number = target.trim()
        context.startActivity(Intent(Intent.ACTION_DIAL, Uri.parse("tel:${Uri.encode(number)}")).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
        speak("Opening the dialer.")
    }

    private fun sms(raw: String) {
        val parts = raw.split(Regex("\\s+(saying|that says|with message)\\s+"), limit = 2)
        val number = parts.firstOrNull().orEmpty().trim()
        val body = parts.getOrNull(1).orEmpty().trim()
        val intent = Intent(Intent.ACTION_SENDTO, Uri.parse("smsto:${Uri.encode(number)}")).apply { if (body.isNotBlank()) putExtra("sms_body", body); addFlags(Intent.FLAG_ACTIVITY_NEW_TASK) }
        context.startActivity(intent); speak("Opening messages.")
    }

    private fun setTimer(command: String) {
        val m = Regex("(\\d+)\\s*(second|seconds|minute|minutes|hour|hours)", RegexOption.IGNORE_CASE).find(command) ?: run { speak("Tell me the timer duration, for example ten minutes."); return }
        val n = m.groupValues[1].toInt(); val unit = m.groupValues[2].lowercase(Locale.getDefault()); val seconds = when { unit.startsWith("second") -> n; unit.startsWith("hour") -> n * 3600; else -> n * 60 }
        context.startActivity(Intent(AlarmClock.ACTION_SET_TIMER).apply { putExtra(AlarmClock.EXTRA_LENGTH, max(1, seconds)); putExtra(AlarmClock.EXTRA_SKIP_UI, false); addFlags(Intent.FLAG_ACTIVITY_NEW_TASK) })
        speak("Timer set for $n ${unit.removeSuffix("s")}.")
    }

    private fun setAlarm(command: String) {
        val m = Regex("(\\d{1,2})(?::(\\d{2}))?\\s*(am|pm)?", RegexOption.IGNORE_CASE).find(command) ?: run { speak("Tell me a time, for example seven thirty AM."); return }
        var hour = m.groupValues[1].toInt(); val minute = m.groupValues[2].ifBlank { "0" }.toInt(); val ampm = m.groupValues[3]
        if (ampm.equals("pm", true) && hour < 12) hour += 12
        if (ampm.equals("am", true) && hour == 12) hour = 0
        context.startActivity(Intent(AlarmClock.ACTION_SET_ALARM).apply { putExtra(AlarmClock.EXTRA_HOUR, hour); putExtra(AlarmClock.EXTRA_MINUTES, minute); putExtra(AlarmClock.EXTRA_MESSAGE, "Doc alarm"); addFlags(Intent.FLAG_ACTIVITY_NEW_TASK) })
        speak("Alarm prepared for ${String.format(Locale.US, "%02d:%02d", hour, minute)}.")
    }

    private fun media(direction: String) {
        val am = context.getSystemService(Context.AUDIO_SERVICE) as AudioManager
        val key = when (direction) { "play" -> android.view.KeyEvent.KEYCODE_MEDIA_PLAY; "pause" -> android.view.KeyEvent.KEYCODE_MEDIA_PAUSE; "next" -> android.view.KeyEvent.KEYCODE_MEDIA_NEXT; else -> android.view.KeyEvent.KEYCODE_MEDIA_PREVIOUS }
        try { am.dispatchMediaKeyEvent(android.view.KeyEvent(android.view.KeyEvent.ACTION_DOWN, key)); am.dispatchMediaKeyEvent(android.view.KeyEvent(android.view.KeyEvent.ACTION_UP, key)); speak("Media command complete.") }
        catch (_: Throwable) { speak("The active media player rejected that command.") }
    }

    private fun changeVolume(direction: String) {
        val am = context.getSystemService(Context.AUDIO_SERVICE) as AudioManager
        when (direction) { "up" -> am.adjustVolume(AudioManager.ADJUST_RAISE, AudioManager.FLAG_SHOW_UI); "down" -> am.adjustVolume(AudioManager.ADJUST_LOWER, AudioManager.FLAG_SHOW_UI); "mute" -> am.adjustVolume(AudioManager.ADJUST_MUTE, AudioManager.FLAG_SHOW_UI); "unmute" -> am.adjustVolume(AudioManager.ADJUST_UNMUTE, AudioManager.FLAG_SHOW_UI) }
        speak("Volume adjusted.")
    }

    private fun toggleTorch(mode: String) {
        try {
            val cm = context.getSystemService(Context.CAMERA_SERVICE) as android.hardware.camera2.CameraManager
            val id = cm.cameraIdList.firstOrNull { cm.getCameraCharacteristics(it).get(android.hardware.camera2.CameraCharacteristics.FLASH_INFO_AVAILABLE) == true }
            if (id == null) { speak("No flashlight hardware found."); return }
            torchOn = if (mode == "off") false else !torchOn
            cm.setTorchMode(id, torchOn)
            speak(if (torchOn) "Flashlight on." else "Flashlight off.")
        } catch (_: Throwable) { speak("Flashlight control needs camera permission and supported hardware.") }
    }

    private fun changeBrightness(direction: String) {
        if (!Settings.System.canWrite(context)) {
            speak("Brightness control needs one-time system-write permission. Opening it now.")
            context.startActivity(Intent(Settings.ACTION_MANAGE_WRITE_SETTINGS, Uri.parse("package:${context.packageName}")).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)); return
        }
        try {
            val current = Settings.System.getInt(context.contentResolver, Settings.System.SCREEN_BRIGHTNESS, 128)
            val next = when (direction) { "down" -> current - 35; "up" -> current + 35; "max" -> 255; else -> current }
            Settings.System.putInt(context.contentResolver, Settings.System.SCREEN_BRIGHTNESS, next.coerceIn(10, 255))
            speak("Brightness adjusted.")
        } catch (_: Throwable) { speak("Brightness control failed.") }
    }

    private fun remember(text: String) {
        context.getSharedPreferences("doc_memory", Context.MODE_PRIVATE).edit().putString("last_memory", text).apply()
        speak("Stored locally. I will keep that in Doc memory.")
    }

    private fun prepareWhatsAppMessage(contact: String, message: String) {
        val launch = context.packageManager.getLaunchIntentForPackage("com.whatsapp")
        if (launch == null) { speak("WhatsApp is not installed."); return }
        pendingWhatsApp = false
        context.startActivity(launch.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
        speak("Opening WhatsApp for $contact.")
        handler.postDelayed({
            val s = EyeNavAccessibilityService.instance ?: run { speak("Accessibility core is unavailable."); return@postDelayed }
            if (!s.clickText("Search")) s.clickText("search")
            handler.postDelayed({
                if (!s.typeText(contact)) { speak("I could not enter the contact."); return@postDelayed }
                handler.postDelayed({
                    if (!s.clickText(contact) && !s.clickText(contact.split(" ").firstOrNull().orEmpty())) { speak("I could not find that WhatsApp contact."); return@postDelayed }
                    handler.postDelayed({
                        if (!s.typeText(message)) { speak("I could not enter the message."); return@postDelayed }
                        pendingWhatsApp = true
                        speak("Message prepared for $contact. Say send when you are ready.")
                    }, 700)
                }, 900)
            }, 600)
        }, 1400)
    }

    private fun sendPendingWhatsApp() {
        if (!pendingWhatsApp) { speak("There is no pending WhatsApp message."); return }
        val s = EyeNavAccessibilityService.instance ?: run { speak("Accessibility core is unavailable."); return }
        if (s.clickText("Send") || s.clickText("send")) { pendingWhatsApp = false; speak("Message sent.") }
        else speak("I cannot see the Send button yet. The message remains unsent.")
    }

    private fun openApp(name: String) {
        val pm = context.packageManager
        val n = name.lowercase(Locale.getDefault()).trim()
        val aliases = mapOf("whatsapp" to "com.whatsapp", "youtube" to "com.google.android.youtube", "chrome" to "com.android.chrome", "settings" to "com.android.settings", "play store" to "com.android.vending", "instagram" to "com.instagram.android", "telegram" to "org.telegram.messenger", "spotify" to "com.spotify.music")
        val direct = aliases[n]?.let { pm.getLaunchIntentForPackage(it) }
        if (direct != null) { context.startActivity(direct.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)); speak("Opening $name."); return }
        val apps = pm.getInstalledApplications(0)
        val app = apps.firstOrNull { val label = pm.getApplicationLabel(it).toString().lowercase(Locale.getDefault()); label == n || label.contains(n) || n.contains(label) }
        if (app != null) { pm.getLaunchIntentForPackage(app.packageName)?.let { context.startActivity(it.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)); speak("Opening ${pm.getApplicationLabel(app)}."); return } }
        speak("I could not find $name on this phone.")
    }

    fun destroy() { brain.shutdown(); tts?.stop(); tts?.shutdown(); tts = null }
}
