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
    private val brain = DocBrain(context)
    private var ready = false
    var onStatus: ((String) -> Unit)? = null
    override fun onInit(status: Int) { ready=status==TextToSpeech.SUCCESS; if(ready){tts?.language=Locale.US;tts?.setSpeechRate(.94f);tts?.setPitch(.82f)} }
    fun speak(text:String){onStatus?.invoke(text);if(ready)tts?.speak(text,TextToSpeech.QUEUE_FLUSH,null,"DOC")}
    fun recognizerIntent()=Intent(RecognizerIntent.ACTION_RECOGNIZE_SPEECH).apply{putExtra(RecognizerIntent.EXTRA_LANGUAGE_MODEL,RecognizerIntent.LANGUAGE_MODEL_FREE_FORM);putExtra(RecognizerIntent.EXTRA_LANGUAGE,Locale.getDefault());putExtra(RecognizerIntent.EXTRA_PROMPT,"Listening for Doc...")}
    fun hasAiKey()=brain.hasKey()
    fun setAiKey(key:String)=brain.setKey(key)

    fun execute(raw:String){
        val c=raw.trim();if(c.isBlank())return
        val l=c.lowercase(Locale.getDefault());val s=EyeNavAccessibilityService.instance
        when{
            l=="home"||l=="go home"->{s?.goHome();speak("Home screen.")}
            l=="back"||l=="go back"->{s?.goBack();speak("Going back.")}
            l.contains("recent apps")||l=="recents"->{s?.openRecents();speak("Recent apps.")}
            l.contains("scroll down")->{s?.performSwipeDown();speak("Scrolling down.")}
            l.contains("scroll up")->{s?.performSwipeUp();speak("Scrolling up.")}
            l.contains("read screen")||l.contains("what is on screen")||l.contains("what's on screen")->{val t=s?.readVisibleText().orEmpty();speak(if(t.isBlank())"I cannot find readable text on this screen." else t)}
            l.startsWith("open ")->openApp(c.substringAfter("open ").trim())
            l.startsWith("launch ")->openApp(c.substringAfter("launch ").trim())
            else->{
                if(!brain.hasKey()){speak("My AI brain is not configured. Add the Gemini API key in Doc.");return}
                speak("Working on it.")
                brain.think(c,s?.readVisibleText().orEmpty()){actions,reply,error->android.os.Handler(context.mainLooper).post{
                    if(error!=null){speak(error);return@post};actions.forEach{runAction(it)};if(!reply.isNullOrBlank())speak(reply)
                }}
            }
        }
    }
    private fun runAction(a:DocBrain.Action){
        val s=EyeNavAccessibilityService.instance
        when(a.type.lowercase()){
            "open_app"->openApp(a.name);"home"->s?.goHome();"back"->s?.goBack();"recents"->s?.openRecents();"scroll"->s?.swipe(a.direction)
            "click_text"->s?.clickText(a.text);"long_click_text"->s?.clickText(a.text,true);"type_text"->s?.typeText(a.text)
            "click"->if(a.x>=0&&a.y>=0)s?.performEyeClick((a.x*context.resources.displayMetrics.widthPixels).toFloat(),(a.y*context.resources.displayMetrics.heightPixels).toFloat())
            "long_click"->if(a.x>=0&&a.y>=0)s?.performLongPress((a.x*context.resources.displayMetrics.widthPixels).toFloat(),(a.y*context.resources.displayMetrics.heightPixels).toFloat())
            "swipe"->s?.swipe(a.direction);"read_screen"->{val t=s?.readVisibleText().orEmpty();if(t.isNotBlank())speak(t)};"speak"->speak(a.text)
        }
    }
    private fun openApp(name:String){
        val pm=context.packageManager;val n=name.lowercase(Locale.getDefault())
        val app=pm.getInstalledApplications(PackageManager.GET_META_DATA).firstOrNull{pm.getApplicationLabel(it).toString().lowercase(Locale.getDefault()).contains(n)}
        if(app!=null){pm.getLaunchIntentForPackage(app.packageName)?.let{context.startActivity(it.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK));speak("Opening ${pm.getApplicationLabel(app)}.");return}}
        val known=mapOf("whatsapp" to "com.whatsapp","youtube" to "com.google.android.youtube","chrome" to "com.android.chrome","settings" to "com.android.settings")
        val launch=known[n]?.let{pm.getLaunchIntentForPackage(it)}
        if(launch!=null){context.startActivity(launch.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK));speak("Opening $name.")}else speak("I could not find $name on this phone.")
    }
    fun destroy(){brain.shutdown();tts?.stop();tts?.shutdown();tts=null}
}
