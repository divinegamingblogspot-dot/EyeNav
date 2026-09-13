package com.prince.eyenav

import android.Manifest
import android.content.Intent
import android.content.pm.PackageManager
import android.graphics.Color
import android.os.Bundle
import android.provider.Settings
import android.speech.RecognitionListener
import android.speech.SpeechRecognizer
import android.view.Gravity
import android.widget.Button
import android.widget.EditText
import android.widget.LinearLayout
import android.widget.TextView
import androidx.activity.ComponentActivity
import androidx.activity.result.contract.ActivityResultContracts
import androidx.core.content.ContextCompat

class MainActivity : ComponentActivity() {
    private lateinit var doc: DocAssistant
    private var recognizer: SpeechRecognizer? = null
    private lateinit var status: TextView
    private lateinit var transcript: TextView
    private var continuous = false
    private val audioPermission = registerForActivityResult(ActivityResultContracts.RequestPermission()) { granted -> if (granted) listen() else status.text="Microphone permission is required." }
    override fun onCreate(savedInstanceState: Bundle?) { super.onCreate(savedInstanceState); doc=DocAssistant(this).also{it.onStatus={runOnUiThread{status.text=it}}}; buildUi(); setupRecognizer() }
    private fun buildUi(){
        val root=LinearLayout(this).apply{orientation=LinearLayout.VERTICAL;gravity=Gravity.CENTER;setPadding(30,30,30,24);setBackgroundColor(Color.rgb(7,9,14))}
        root.addView(TextView(this).apply{text="DOC";textSize=48f;gravity=Gravity.CENTER;setTextColor(Color.WHITE)},LinearLayout.LayoutParams(-1,75))
        root.addView(TextView(this).apply{text="AI voice agent";textSize=16f;gravity=Gravity.CENTER;setTextColor(Color.LTGRAY)},LinearLayout.LayoutParams(-1,42))
        status=TextView(this).apply{text="Standing by.";textSize=18f;gravity=Gravity.CENTER;setTextColor(Color.WHITE);setPadding(8,16,8,16)};root.addView(status,LinearLayout.LayoutParams(-1,90))
        transcript=TextView(this).apply{text="Say anything to Doc.";textSize=14f;gravity=Gravity.CENTER;setTextColor(Color.GRAY)};root.addView(transcript,LinearLayout.LayoutParams(-1,70))
        root.addView(button("🎙 Talk to Doc"){requestAndListen()})
        root.addView(button("♾ Always listen"){continuous=!continuous;status.text=if(continuous)"Always listening is ON." else "Always listening is OFF.";if(continuous)requestAndListen()})
        root.addView(button("♿ Enable Accessibility"){startActivity(Intent(Settings.ACTION_ACCESSIBILITY_SETTINGS))})
        val key=EditText(this).apply{hint="Gemini API key (stored on this phone)";setTextColor(Color.WHITE);setHintTextColor(Color.GRAY);singleLine=true;inputType=0x00000081}
        root.addView(key,LinearLayout.LayoutParams(-1,58))
        root.addView(button("Save AI brain key"){doc.setAiKey(key.text.toString());status.text=if(doc.hasAiKey())"AI brain connected." else "No API key saved."})
        root.addView(button("Test voice"){doc.speak("Systems online. Doc is ready.")})
        root.addView(TextView(this).apply{text="Doc can reason over the current Accessibility screen and turn natural-language commands into app launches, navigation, taps, text entry, swipes and screen-reading actions.";textSize=11f;gravity=Gravity.CENTER;setTextColor(Color.GRAY);setPadding(4,16,4,4)},LinearLayout.LayoutParams(-1,85))
        setContentView(root)
    }
    private fun button(label:String,action:()->Unit)=Button(this).apply{text=label;isAllCaps=false;textSize=15f;setOnClickListener{action()}}
    private fun setupRecognizer(){
        if(!SpeechRecognizer.isRecognitionAvailable(this)){status.text="Speech recognition unavailable.";return}
        recognizer=SpeechRecognizer.createSpeechRecognizer(this).also{r->r.setRecognitionListener(object:RecognitionListener{
            override fun onReadyForSpeech(params:Bundle?){status.text="Listening…"}
            override fun onBeginningOfSpeech(){status.text="I’m listening."}
            override fun onEndOfSpeech(){status.text="Thinking…"}
            override fun onError(error:Int){status.text="I didn't catch that.";if(continuous)window.decorView.postDelayed({listen()},700)}
            override fun onResults(results:Bundle?){val text=results?.getStringArrayList(SpeechRecognizer.RESULTS_RECOGNITION)?.firstOrNull().orEmpty();transcript.text=if(text.isBlank())"No command detected." else "You: $text";if(text.isNotBlank())doc.execute(text);if(continuous)window.decorView.postDelayed({listen()},900)}
            override fun onPartialResults(partialResults:Bundle?)=Unit
            override fun onBufferReceived(buffer:ByteArray?)=Unit
            override fun onEvent(eventType:Int,params:Bundle?)=Unit
            override fun onRmsChanged(rmsdB:Float)=Unit
        })}
    }
    private fun requestAndListen(){if(ContextCompat.checkSelfPermission(this,Manifest.permission.RECORD_AUDIO)!=PackageManager.PERMISSION_GRANTED)audioPermission.launch(Manifest.permission.RECORD_AUDIO) else listen()}
    private fun listen(){if(!isFinishing)recognizer?.startListening(doc.recognizerIntent())}
    override fun onDestroy(){recognizer?.destroy();recognizer=null;doc.destroy();super.onDestroy()}
}
