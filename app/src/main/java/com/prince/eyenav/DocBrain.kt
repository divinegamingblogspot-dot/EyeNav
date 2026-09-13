package com.prince.eyenav

import android.content.Context
import java.util.Locale
import java.util.regex.Pattern

/** Offline command brain: no API key, cloud account, or daily quota. */
class DocBrain(private val context: Context) {
    fun hasKey(): Boolean = true
    fun setKey(key: String) = Unit

    fun think(command: String, screen: String, callback: (List<Action>, String?, String?) -> Unit) {
        callback(parse(command, screen), null, null)
    }

    private fun parse(raw: String, screen: String): List<Action> {
        val c = raw.trim()
        val l = c.lowercase(Locale.getDefault())
        val out = mutableListOf<Action>()
        when {
            l.matches(Regex("(go )?home|home screen|take me home")) -> out += Action("home")
            l.matches(Regex("(go )?back|back screen|previous screen")) -> out += Action("back")
            l.contains("recent apps") || l == "recents" || l.contains("show recent") -> out += Action("recents")
            l.contains("scroll down") || l.contains("move down") -> out += Action("scroll", direction = "down")
            l.contains("scroll up") || l.contains("move up") -> out += Action("scroll", direction = "up")
            l.contains("scroll left") -> out += Action("scroll", direction = "left")
            l.contains("scroll right") -> out += Action("scroll", direction = "right")
            l.contains("read screen") || l.contains("what is on screen") || l.contains("what's on screen") || l.contains("read this") -> out += Action("read_screen")
            l.startsWith("open ") -> out += Action("open_app", name = c.substring(5).trim())
            l.startsWith("launch ") -> out += Action("open_app", name = c.substring(7).trim())
            l.startsWith("start ") && l.length > 6 -> out += Action("open_app", name = c.substring(6).trim())
            l.startsWith("click ") || l.startsWith("tap ") || l.startsWith("press ") -> out += Action("click_text", text = c.substringAfter(' ').trim())
            l.startsWith("long press ") || l.startsWith("long click ") -> out += Action("long_click_text", text = c.substringAfter(" ").substringAfter(" ").trim())
            l.startsWith("type ") || l.startsWith("write ") || l.startsWith("enter ") -> out += Action("type_text", text = c.substringAfter(' ').trim())
            l.contains("whatsapp") && (l.contains("message") || l.contains("text ") || l.contains("send ")) -> {
                val m = parseWhatsApp(c)
                if (m != null) out += Action("whatsapp_message", name = m.first, text = m.second) else out += Action("open_app", name = "WhatsApp")
            }
            l.contains("pause") && (l.contains("game") || l.contains("playing")) -> out += Action("click_text", text = "pause")
            l.contains("play") && (l.contains("game") || l.contains("playing")) -> out += Action("click_text", text = "play")
            else -> {
                val cleaned = l.replace(Regex("^(please|doc|can you|could you)\\s+"), "").trim()
                if (cleaned.isNotBlank() && screen.lowercase(Locale.getDefault()).contains(cleaned)) out += Action("click_text", text = cleaned)
                else out += Action("speak", text = "I need a clearer command for that.")
            }
        }
        return out
    }

    private fun parseWhatsApp(c: String): Pair<String, String>? {
        val p = Pattern.compile("(?i)(?:message|send)(?: whatsapp)?(?: to)?\\s+(.+?)\\s+(?:saying|that says|with message)\\s+(.+)")
        val m = p.matcher(c)
        return if (m.find()) m.group(1).trim() to m.group(2).trim() else null
    }

    data class Action(val type: String, val name: String = "", val text: String = "", val direction: String = "", val x: Double = -1.0, val y: Double = -1.0)
    fun shutdown() = Unit
}
