package com.prince.eyenav

import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.media.AudioManager
import android.os.BatteryManager
import android.os.Handler
import android.os.Looper
import android.speech.RecognizerIntent
import android.speech.tts.TextToSpeech
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/** Executes Doc's local actions and gives him a polished cinematic voice. */
class DocAssistant(private val context: Context) : TextToSpeech.OnInitListener {
    private var tts: TextToSpeech? = TextToSpeech(context, this)
    private val brain = DocBrain(context)
    private val handler = Handler(Looper.getMainLooper())
    private var ready = false
    private var pendingWhatsApp = false
    var onStatus: ((String) -> Unit)? = null

    override fun onInit(status: Int) {
        ready = status == TextToSpeech.SUCCESS
        if (ready) {
            tts?.language = Locale.US
            tts?.setSpeechRate(0.91f)
            tts?.setPitch(0.78f)
        }
    }

    fun speak(text: String) {
        onStatus?.invoke(text)
        if (ready) tts?.speak(text, TextToSpeech.QUEUE_FLUSH, null, "DOC")
    }

    fun recognizerIntent() = Intent(RecognizerIntent.ACTION_RECOGNIZE_SPEECH).apply {
        putExtra(RecognizerIntent.EXTRA_LANGUAGE_MODEL, RecognizerIntent.LANGUAGE_MODEL_FREE_FORM)
        putExtra(RecognizerIntent.EXTRA_LANGUAGE, Locale.getDefault())
        putExtra(RecognizerIntent.EXTRA_MAX_RESULTS, 3)
        putExtra(RecognizerIntent.EXTRA_PARTIAL_RESULTS, true)
        putExtra(RecognizerIntent.EXTRA_PROMPT, "Listening for Doc...")
    }

    fun hasAiKey() = true
    fun setAiKey(key: String) = Unit

    fun execute(raw: String) {
        val c = raw.trim()
        if (c.isBlank()) return
        val lower = c.lowercase(Locale.getDefault())
        if (pendingWhatsApp && (lower == "send" || lower == "send it" || lower.contains("send message"))) {
            sendPendingWhatsApp()
            return
        }
        val service = EyeNavAccessibilityService.instance
        speak("On it.")
        brain.think(c, service?.readVisibleText().orEmpty()) { actions, _, error ->
            handler.post {
                if (error != null) { speak(error); return@post }
                if (actions.isEmpty()) { speak("I could not understand that command."); return@post }
                actions.forEach { runAction(it) }
            }
        }
    }

    private fun runAction(a: DocBrain.Action) {
        val s = EyeNavAccessibilityService.instance
        when (a.type.lowercase(Locale.getDefault())) {
            "open_app" -> openApp(a.name)
            "home" -> { s?.goHome(); speak("Home screen.") }
            "back" -> { s?.goBack(); speak("Going back.") }
            "recents" -> { s?.openRecents(); speak("Recent apps.") }
            "scroll" -> s?.swipe(a.direction)
            "click_text" -> if (!(s?.clickText(a.text) ?: false)) speak("I could not find ${a.text}.") else speak("Done.")
            "long_click_text" -> if (!(s?.clickText(a.text, true) ?: false)) speak("I could not find ${a.text}.") else speak("Done.")
            "type_text" -> if (!(s?.typeText(a.text) ?: false)) speak("There is no active text field.") else speak("Typed.")
            "click" -> if (a.x >= 0 && a.y >= 0) s?.performEyeClick((a.x * context.resources.displayMetrics.widthPixels).toFloat(), (a.y * context.resources.displayMetrics.heightPixels).toFloat())
            "long_click" -> if (a.x >= 0 && a.y >= 0) s?.performLongPress((a.x * context.resources.displayMetrics.widthPixels).toFloat(), (a.y * context.resources.displayMetrics.heightPixels).toFloat())
            "swipe" -> s?.swipe(a.direction)
            "read_screen" -> {
                val t = s?.readVisibleText().orEmpty()
                speak(if (t.isBlank()) "There is no readable text exposed on this screen." else t)
            }
            "time" -> speak("The time is ${SimpleDateFormat("h:mm a", Locale.US).format(Date())}.")
            "date" -> speak("Today is ${SimpleDateFormat("EEEE, d MMMM", Locale.US).format(Date())}.")
            "battery" -> {
                val bm = context.getSystemService(Context.BATTERY_SERVICE) as BatteryManager
                val level = bm.getIntProperty(BatteryManager.BATTERY_PROPERTY_CAPACITY)
                speak("Battery is at $level percent.")
            }
            "volume" -> changeVolume(a.direction)
            "speak" -> speak(a.text)
            "whatsapp_message" -> prepareWhatsAppMessage(a.name, a.text)
            "send_pending" -> sendPendingWhatsApp()
            "stop_listening" -> speak("Say stop listening again from the Doc screen to disable continuous mode.")
        }
    }

    private fun changeVolume(direction: String) {
        val am = context.getSystemService(Context.AUDIO_SERVICE) as AudioManager
        when (direction) {
            "up" -> am.adjustVolume(AudioManager.ADJUST_RAISE, AudioManager.FLAG_SHOW_UI)
            "down" -> am.adjustVolume(AudioManager.ADJUST_LOWER, AudioManager.FLAG_SHOW_UI)
            "mute" -> am.adjustVolume(AudioManager.ADJUST_MUTE, AudioManager.FLAG_SHOW_UI)
        }
        speak("Volume adjusted.")
    }

    private fun prepareWhatsAppMessage(contact: String, message: String) {
        val pm = context.packageManager
        val launch = pm.getLaunchIntentForPackage("com.whatsapp")
        if (launch == null) { speak("WhatsApp is not installed."); return }
        pendingWhatsApp = false
        context.startActivity(launch.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
        speak("Opening WhatsApp for $contact.")
        handler.postDelayed({
            val s = EyeNavAccessibilityService.instance ?: run { speak("Accessibility service is not available."); return@postDelayed }
            if (!s.clickText("Search")) s.clickText("search")
            handler.postDelayed({
                if (!s.typeText(contact)) { speak("I could not enter the contact name."); return@postDelayed }
                handler.postDelayed({
                    if (!s.clickText(contact) && !s.clickText(contact.split(" ").firstOrNull().orEmpty())) {
                        speak("I could not find that WhatsApp contact."); return@postDelayed
                    }
                    handler.postDelayed({
                        if (!s.typeText(message)) { speak("I could not enter the message."); return@postDelayed }
                        pendingWhatsApp = true
                        handler.postDelayed({ speak("Message prepared for $contact. Say send when you are ready.") }, 400)
                    }, 800)
                }, 1000)
            }, 700)
        }, 1500)
    }

    private fun sendPendingWhatsApp() {
        if (!pendingWhatsApp) { speak("There is no pending message."); return }
        val s = EyeNavAccessibilityService.instance ?: run { speak("Accessibility service is unavailable."); return }
        val sent = s.clickText("Send") || s.clickText("send")
        if (sent) {
            pendingWhatsApp = false
            speak("Message sent.")
        } else speak("I could not find the Send button. The message is still waiting for you.")
    }

    private fun openApp(name: String) {
        val pm = context.packageManager
        val n = name.lowercase(Locale.getDefault()).trim()
        val aliases = mapOf(
            "whatsapp" to "com.whatsapp",
            "youtube" to "com.google.android.youtube",
            "chrome" to "com.android.chrome",
            "settings" to "com.android.settings",
            "play store" to "com.android.vending",
            "instagram" to "com.instagram.android",
            "telegram" to "org.telegram.messenger",
            "spotify" to "com.spotify.music"
        )
        val direct = aliases[n]?.let { pm.getLaunchIntentForPackage(it) }
        if (direct != null) {
            context.startActivity(direct.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
            speak("Opening $name.")
            return
        }
        val apps = pm.getInstalledApplications(PackageManager.GET_META_DATA)
        val app = apps.firstOrNull {
            val label = pm.getApplicationLabel(it).toString().lowercase(Locale.getDefault())
            label == n || label.contains(n) || n.contains(label)
        }
        if (app != null) {
            pm.getLaunchIntentForPackage(app.packageName)?.let {
                context.startActivity(it.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
                speak("Opening ${pm.getApplicationLabel(app)}.")
                return
            }
        }
        speak("I could not find $name on this phone.")
    }

    fun destroy() { brain.shutdown(); tts?.stop(); tts?.shutdown(); tts = null }
}
