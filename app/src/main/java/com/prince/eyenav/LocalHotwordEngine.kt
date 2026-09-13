package com.prince.eyenav

import android.Manifest
import android.content.Context
import android.content.pm.PackageManager
import android.media.AudioFormat
import android.media.AudioRecord
import android.media.MediaRecorder
import android.os.Handler
import android.os.Looper
import androidx.core.content.ContextCompat
import org.vosk.Model
import org.vosk.Recognizer
import java.io.BufferedInputStream
import java.io.File
import java.io.FileOutputStream
import java.net.HttpURLConnection
import java.net.URL
import java.util.zip.ZipInputStream
import kotlin.concurrent.thread

class LocalHotwordEngine(
    private val context: Context,
    private val onWake: (String) -> Unit,
    private val onStatus: (String) -> Unit
) {
    companion object {
        private const val MODEL_URL = "https://alphacephei.com/vosk/models/vosk-model-small-en-us-0.15.zip"
        private const val MODEL_DIR = "vosk-model-small-en-us-0.15"
        private const val SAMPLE_RATE = 16000
    }
    private val main = Handler(Looper.getMainLooper())
    @Volatile private var running = false
    private var recorder: AudioRecord? = null
    private var model: Model? = null
    private var recognizer: Recognizer? = null
    private var worker: Thread? = null

    fun start() {
        if (running) return
        if (ContextCompat.checkSelfPermission(context, Manifest.permission.RECORD_AUDIO) != PackageManager.PERMISSION_GRANTED) { onStatus("DOC • MICROPHONE PERMISSION REQUIRED"); return }
        running = true
        worker = thread(name = "DocHotword", start = true) {
            try {
                val path = ensureModel()
                if (!running) return@thread
                onStatus("DOC • LOADING LOCAL HOTWORD")
                model = Model(path)
                recognizer = Recognizer(model, SAMPLE_RATE.toFloat())
                listenLoop()
            } catch (t: Throwable) { onStatus("DOC • HOTWORD ERROR • ${t.javaClass.simpleName}"); running = false }
        }
    }
    fun stop() {
        running = false
        try { recorder?.stop() } catch (_: Throwable) { }
        try { recorder?.release() } catch (_: Throwable) { }
        recorder = null
        try { recognizer?.close() } catch (_: Throwable) { }
        recognizer = null
        try { model?.close() } catch (_: Throwable) { }
        model = null
        worker = null
    }
    private fun ensureModel(): String {
        val base = File(context.filesDir, "vosk")
        val modelDir = File(base, MODEL_DIR)
        if (File(modelDir, "am/final.mdl").exists()) return modelDir.absolutePath
        base.mkdirs()
        val zipFile = File(base, "model.zip")
        onStatus("DOC • DOWNLOADING LOCAL VOICE MODEL")
        val conn = (URL(MODEL_URL).openConnection() as HttpURLConnection).apply { connectTimeout = 20000; readTimeout = 60000; requestMethod = "GET"; connect() }
        if (conn.responseCode !in 200..299) throw IllegalStateException("model download ${conn.responseCode}")
        conn.inputStream.use { input -> BufferedInputStream(input).use { buffered -> FileOutputStream(zipFile).use { output ->
            val buffer = ByteArray(64 * 1024); var total = 0L
            while (running) { val n = buffered.read(buffer); if (n <= 0) break; output.write(buffer, 0, n); total += n; if (total % (2L * 1024L * 1024L) < n) onStatus("DOC • DOWNLOADING VOICE MODEL • ${total / 1024 / 1024} MB") }
        } } }
        conn.disconnect()
        if (!running) throw InterruptedException("stopped")
        onStatus("DOC • INSTALLING LOCAL VOICE MODEL")
        ZipInputStream(zipFile.inputStream().buffered()).use { zip ->
            var entry = zip.nextEntry
            while (entry != null) {
                val out = File(base, entry.name)
                if (!out.canonicalPath.startsWith(base.canonicalPath + File.separator)) throw SecurityException("bad zip")
                if (entry.isDirectory) out.mkdirs() else { out.parentFile?.mkdirs(); FileOutputStream(out).use { output -> zip.copyTo(output) } }
                zip.closeEntry(); entry = zip.nextEntry
            }
        }
        zipFile.delete()
        if (!File(modelDir, "am/final.mdl").exists()) throw IllegalStateException("model install failed")
        return modelDir.absolutePath
    }
    private fun listenLoop() {
        val min = AudioRecord.getMinBufferSize(SAMPLE_RATE, AudioFormat.CHANNEL_IN_MONO, AudioFormat.ENCODING_PCM_16BIT)
        val size = maxOf(min * 2, 4096)
        val r = AudioRecord(MediaRecorder.AudioSource.VOICE_RECOGNITION, SAMPLE_RATE, AudioFormat.CHANNEL_IN_MONO, AudioFormat.ENCODING_PCM_16BIT, size)
        recorder = r; r.startRecording(); onStatus("DOC • STANDBY • SAY HEY DOC")
        val buffer = ByteArray(size)
        while (running) {
            val n = r.read(buffer, 0, buffer.size); if (n <= 0) continue
            val rec = recognizer ?: continue
            if (rec.acceptWaveForm(buffer, n)) {
                val json = rec.result()
                val text = Regex("\\\"text\\\"\\s*:\\s*\\\"([^\\\"]*)\\\"").find(json)?.groupValues?.getOrNull(1)?.trim().orEmpty()
                val wake = extractWake(text)
                if (wake != null) { main.post { onWake(wake) }; rec.reset() }
            }
        }
    }
    private fun extractWake(text: String): String? {
        val normalized = text.lowercase().replace(Regex("[^a-z0-9 ]"), " ").replace(Regex("\\s+"), " ").trim()
        if (normalized.isBlank()) return null
        val match = Regex("^(?:hey |okay |ok )?doc(?: (.*))?$").find(normalized) ?: return null
        return match.groupValues.getOrNull(1)?.trim().orEmpty()
    }
}
