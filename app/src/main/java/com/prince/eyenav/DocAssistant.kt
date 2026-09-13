package com.prince.eyenav

import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.speech.RecognizerIntent
import android.speech.tts.TextToSpeech
import android.os.Handler
import android.os.Looper
import java.util.Locale

class DocAssistant(private val context: Context) : TextToSpeech.OnInitListener {
    private var tts: TextToSpeech? = TextToSpeech(context, this)
    private val brain = DocBrain(context)
    private val handler = Handler(Looper.getMainLooper())
    private var ready = false
    var onStatus: ((String) -> Unit)? = null

    override fun onInit(status: Int) {
        ready = status == TextToSpeech.SUCCESS
        if (ready) {
            tts?.language = Locale.US
            tts?.setSpeechRate(.94f)
            tts?.setPitch(.82f)
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

    fun hasAiKey() = true
    fun setAiKey(key: String) = Unit

    fun execute(raw: String) {
        val c = raw.trim()
        if (c.isBlank()) return
        val s = EyeNavAccessibilityService.instance
        speak("Working on it.")
        brain.think(c, s?.readVisibleText().orEmpty()) { actions, _, error ->
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
            "click_text" -> { if (!(s?.clickText(a.text) ?: false)) speak("I could not find ${a.text}.") }
            "long_click_text" -> { if (!(s?.clickText(a.text, true) ?: false)) speak("I could not find ${a.text}.") }
            "type_text" -> { if (!(s?.typeText(a.text) ?: false)) speak("There is no active text field.") }
            "click" -> if (a.x >= 0 && a.y >= 0) s?.performEyeClick((a.x * context.resources.displayMetrics.widthPixels).toFloat(), (a.y * context.resources.displayMetrics.heightPixels).toFloat())
            "long_click" -> if (a.x >= 0 && a.y >= 0) s?.performLongPress((a.x * context.resources.displayMetrics.widthPixels).toFloat(), (a.y * context.resources.displayMetrics.heightPixels).toFloat())
            "swipe" -> s?.swipe(a.direction)
            "read_screen" -> { val t = s?.readVisibleText().orEmpty(); speak(if (t.isBlank()) "I cannot find readable text on this screen." else t) }
            "speak" -> speak(a.text)
            "whatsapp_message" -> sendWhatsAppMessage(a.name, a.text)
        }
    }

    private fun sendWhatsAppMessage(contact: String, message: String) {
        val pm = context.packageManager
        val launch = pm.getLaunchIntentForPackage("com.whatsapp")
        if (launch == null) { speak("WhatsApp is not installed."); return }
        context.startActivity(launch.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
        speak("Opening WhatsApp for $contact.")
        handler.postDelayed({
            val s = EyeNavAccessibilityService.instance ?: return@postDelayed
            if (!s.clickText("Search")) s.clickText("search")
            handler.postDelayed({
                if (!s.typeText(contact)) { speak("I could not enter the contact name."); return@postDelayed }
                handler.postDelayed({
                    if (!s.clickText(contact)) {
                        // WhatsApp search results can include extra text around the contact name.
                        if (!s.clickText(contact.split(" ").firstOrNull().orEmpty())) { speak("I could not find that WhatsApp contact."); return@postDelayed }
                    }
                    handler.postDelayed({
                        if (!s.typeText(message)) { speak("I could not enter the message."); return@postDelayed }
                        handler.postDelayed({
                            // Final Send is deliberately explicit: Doc prepares the message but does not
                            // silently send to the wrong contact. User can say "send" or tap Send.
                            speak("Message is ready for $contact. Say send when you want me to send it.")
                        }, 500)
                    }, 700)
                }, 900)
            }, 600)
        }, 1400)
    }

    private fun openApp(name: String) {
        val pm = context.packageManager
        val n = name.lowercase(Locale.getDefault()).trim()
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
        val known = mapOf(
            "whatsapp" to "com.whatsapp",
            "youtube" to "com.google.android.youtube",
            "chrome" to "com.android.chrome",
            "settings" to "com.android.settings"
        )
        val launch = known[n]?.let { pm.getLaunchIntentForPackage(it) }
        if (launch != null) {
            context.startActivity(launch.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
            speak("Opening $name.")
        } else speak("I could not find $name on this phone.")
    }

    fun destroy() { brain.shutdown(); tts?.stop(); tts?.shutdown(); tts = null }
}
