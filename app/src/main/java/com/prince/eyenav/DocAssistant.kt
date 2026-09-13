package com.prince.eyenav

import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.net.Uri
import android.speech.RecognizerIntent
import android.speech.tts.TextToSpeech
import java.util.Locale

class DocAssistant(private val context: Context) : TextToSpeech.OnInitListener {
    private var tts: TextToSpeech? = TextToSpeech(context, this)
    private var ready = false
    var onStatus: ((String) -> Unit)? = null

    override fun onInit(status: Int) {
        ready = status == TextToSpeech.SUCCESS
        if (ready) {
            tts?.language = Locale.US
            tts?.setSpeechRate(0.94f)
            tts?.setPitch(0.86f)
        }
    }

    fun speak(text: String) {
        onStatus?.invoke(text)
        if (ready) tts?.speak(text, TextToSpeech.QUEUE_FLUSH, null, "DOC")
    }

    fun recognizerIntent() = Intent(RecognizerIntent.ACTION_RECOGNIZE_SPEECH).apply {
        putExtra(RecognizerIntent.EXTRA_LANGUAGE_MODEL, RecognizerIntent.LANGUAGE_MODEL_FREE_FORM)
        putExtra(RecognizerIntent.EXTRA_LANGUAGE, Locale.getDefault())
        putExtra(RecognizerIntent.EXTRA_PROMPT, "Listening for Doc...")
    }

    fun execute(raw: String) {
        val c = raw.trim().lowercase(Locale.getDefault())
        if (c.isBlank()) return
        val s = EyeNavAccessibilityService.instance
        when {
            c == "home" || c == "go home" -> { s?.goHome(); speak("Home screen.") }
            c == "back" || c == "go back" -> { s?.goBack(); speak("Going back.") }
            c.contains("recent apps") || c == "recents" -> { s?.openRecents(); speak("Recent apps.") }
            c.contains("scroll down") -> { s?.performSwipeUp(); speak("Scrolling down.") }
            c.contains("scroll up") -> { s?.performSwipeDown(); speak("Scrolling up.") }
            c.contains("read screen") || c.contains("what is on screen") || c.contains("what's on screen") -> {
                speak(s?.readVisibleText()?.ifBlank { "I cannot see readable text here." } ?: "Enable Doc accessibility first.")
            }
            c.startsWith("open ") -> openApp(c.removePrefix("open ").trim())
            c.startsWith("launch ") -> openApp(c.removePrefix("launch ").trim())
            c.contains("whatsapp") && (c.contains("message") || c.contains("text")) -> sendWhatsApp(c)
            c.contains("pause") -> speak("There is no universal Android pause command for every game. I can interact with visible game controls when they are exposed to accessibility.")
            c.contains("start game") || c.contains("play game") -> {
                val n = c.substringAfter("game", "").trim()
                if (n.isBlank()) speak("Tell me the game name.") else openApp(n)
            }
            else -> speak("I am Doc. I can open apps, navigate, read the visible screen and control supported UI actions.")
        }
    }

    private fun openApp(name: String) {
        val pm = context.packageManager
        val app = pm.getInstalledApplications(PackageManager.GET_META_DATA).firstOrNull {
            pm.getApplicationLabel(it).toString().lowercase(Locale.getDefault()).contains(name)
        }
        if (app != null) {
            pm.getLaunchIntentForPackage(app.packageName)?.let {
                context.startActivity(it.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
                speak("Opening ${pm.getApplicationLabel(app)}.")
                return
            }
        }
        val known = mapOf("whatsapp" to "com.whatsapp", "youtube" to "com.google.android.youtube", "chrome" to "com.android.chrome", "settings" to "com.android.settings")
        val pkg = known[name]
        val launch = pkg?.let { pm.getLaunchIntentForPackage(it) }
        if (launch != null) { context.startActivity(launch.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)); speak("Opening $name.") }
        else speak("I could not find $name on this phone.")
    }

    private fun sendWhatsApp(c: String) {
        val m = Regex("message(?: whatsapp)?(?: to)? (.+?) (?:saying|that says|says) (.+)").find(c)
        if (m == null) { speak("Say: message WhatsApp to contact saying your message."); return }
        val contact = m.groupValues[1].trim()
        val message = m.groupValues[2].trim()
        val intent = Intent(Intent.ACTION_VIEW, Uri.parse("https://wa.me/?text=${Uri.encode(message)}")).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        context.startActivity(intent)
        speak("WhatsApp is ready for $contact. I will not silently send a message without your confirmation.")
    }

    fun destroy() { tts?.stop(); tts?.shutdown(); tts = null }
}
