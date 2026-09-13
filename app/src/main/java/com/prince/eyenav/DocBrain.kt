package com.prince.eyenav

import android.content.Context
import org.json.JSONArray
import org.json.JSONObject
import java.net.HttpURLConnection
import java.net.URL
import java.util.concurrent.Executors

class DocBrain(private val context: Context) {
    private val prefs = context.getSharedPreferences("doc", Context.MODE_PRIVATE)
    private val executor = Executors.newSingleThreadExecutor()
    private val model = "gemini-3.8-flash"

    fun hasKey(): Boolean = prefs.getString("gemini_key", "").orEmpty().isNotBlank()
    fun setKey(key: String) { prefs.edit().putString("gemini_key", key.trim()).apply() }

    fun think(command: String, screen: String, callback: (List<Action>, String?, String?) -> Unit) {
        val key = prefs.getString("gemini_key", "").orEmpty()
        if (key.isBlank()) { callback(emptyList(), null, "Set a Gemini API key in Doc first."); return }
        executor.execute {
            try {
                val system = """
You are DOC, a capable Android personal assistant. Convert the user's natural-language command into a JSON array of executable actions. Use only these action types: open_app{name}, home, back, recents, scroll{direction}, click_text{text}, long_click_text{text}, type_text{text}, click{x,y}, long_click{x,y}, swipe{direction}, read_screen, speak{text}. Return ONLY valid JSON: {\"actions\":[{\"type\":\"...\", ...}],\"reply\":\"short spoken reply\"}. Coordinate values are normalized 0..1. Use screen text when useful. Never claim an action succeeded unless it can be executed. For messaging, open the messaging app and navigate using visible text; do not invent contact names. Keep actions minimal but sufficient.
""".trimIndent()
                val user = "USER COMMAND:\n$command\n\nCURRENT ACCESSIBILITY SCREEN TEXT:\n${screen.take(7000)}"
                val body = JSONObject().apply {
                    put("system_instruction", JSONObject().put("parts", JSONArray().put(JSONObject().put("text", system))))
                    put("contents", JSONArray().put(JSONObject().put("role", "user").put("parts", JSONArray().put(JSONObject().put("text", user)))))
                    put("generationConfig", JSONObject().put("temperature", 0.1).put("responseMimeType", "application/json"))
                }
                val conn = (URL("https://generativelanguage.googleapis.com/v1beta/models/$model:generateContent").openConnection() as HttpURLConnection).apply {
                    requestMethod = "POST"
                    connectTimeout = 15000
                    readTimeout = 30000
                    doOutput = true
                    setRequestProperty("Content-Type", "application/json")
                    setRequestProperty("x-goog-api-key", key)
                }
                conn.outputStream.use { it.write(body.toString().toByteArray(Charsets.UTF_8)) }
                val stream = if (conn.responseCode in 200..299) conn.inputStream else conn.errorStream
                val response = stream.bufferedReader().use { it.readText() }
                if (conn.responseCode !in 200..299) throw IllegalStateException("Gemini HTTP ${conn.responseCode}")
                val root = JSONObject(response)
                val text = root.getJSONArray("candidates").getJSONObject(0).getJSONObject("content").getJSONArray("parts").getJSONObject(0).getString("text")
                val parsed = JSONObject(text)
                val actions = mutableListOf<Action>()
                val arr = parsed.optJSONArray("actions") ?: JSONArray()
                for (i in 0 until arr.length()) {
                    val a = arr.getJSONObject(i)
                    actions.add(Action(a.optString("type"), a.optString("name"), a.optString("text"), a.optString("direction"), a.optDouble("x", -1.0), a.optDouble("y", -1.0)))
                }
                callback(actions, parsed.optString("reply").ifBlank { null }, null)
            } catch (e: Exception) { callback(emptyList(), null, e.message ?: "Doc brain error") }
        }
    }

    data class Action(val type: String, val name: String = "", val text: String = "", val direction: String = "", val x: Double = -1.0, val y: Double = -1.0)
    fun shutdown() { executor.shutdownNow() }
}
