package com.prince.eyenav

import android.content.Context
import java.util.Locale
import java.util.regex.Pattern

/**
 * Doc's zero-quota local command brain. Fast paths are deliberately offline:
 * navigation, apps, accessibility, device status and common conversational commands.
 */
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
        val clean = l.replace(Regex("^(hey )?doc[,:]?\\s*"), "").trim()
        when {
            clean.matches(Regex("(go )?home|home screen|take me home|return home")) -> out += Action("home")
            clean.matches(Regex("(go )?back|back screen|previous screen|go back")) -> out += Action("back")
            clean.contains("recent apps") || clean == "recents" || clean.contains("show recent") -> out += Action("recents")
            clean.contains("scroll down") || clean.contains("move down") || clean == "down" -> out += Action("scroll", direction = "down")
            clean.contains("scroll up") || clean.contains("move up") || clean == "up" -> out += Action("scroll", direction = "up")
            clean.contains("scroll left") || clean == "left" -> out += Action("scroll", direction = "left")
            clean.contains("scroll right") || clean == "right" -> out += Action("scroll", direction = "right")
            clean.contains("read screen") || clean.contains("what is on screen") || clean.contains("what's on screen") || clean.contains("read this") || clean.contains("what do you see") -> out += Action("read_screen")
            clean.contains("what time") || clean == "time" || clean.contains("current time") -> out += Action("time")
            clean.contains("what date") || clean == "date" || clean.contains("today's date") || clean.contains("todays date") -> out += Action("date")
            clean.contains("battery") || clean.contains("battery level") -> out += Action("battery")
            clean.contains("volume up") || clean.contains("increase volume") || clean.contains("louder") -> out += Action("volume", direction = "up")
            clean.contains("volume down") || clean.contains("decrease volume") || clean.contains("quieter") -> out += Action("volume", direction = "down")
            clean == "mute" || clean.contains("mute phone") -> out += Action("volume", direction = "mute")
            clean.startsWith("open ") -> out += Action("open_app", name = c.substringAfter(" ").trim())
            clean.startsWith("launch ") -> out += Action("open_app", name = c.substringAfter(" ").trim())
            clean.startsWith("start ") && clean.length > 6 -> out += Action("open_app", name = c.substringAfter(" ").trim())
            clean.startsWith("close ") -> out += Action("back")
            clean.startsWith("click ") || clean.startsWith("tap ") || clean.startsWith("press ") -> out += Action("click_text", text = c.substringAfter(' ').trim())
            clean.startsWith("long press ") || clean.startsWith("long click ") -> out += Action("long_click_text", text = clean.substringAfter(" ").substringAfter(" ").trim())
            clean.startsWith("type ") || clean.startsWith("write ") || clean.startsWith("enter ") -> out += Action("type_text", text = c.substringAfter(' ').trim())
            clean.contains("whatsapp") && (clean.contains("message") || clean.contains("text ") || clean.contains("send ")) -> {
                val m = parseWhatsApp(c)
                if (m != null) out += Action("whatsapp_message", name = m.first, text = m.second) else out += Action("open_app", name = "WhatsApp")
            }
            clean == "send" || clean == "send it" || clean == "send message" -> out += Action("send_pending")
            clean.contains("pause") && (clean.contains("game") || clean.contains("playing") || clean == "pause") -> out += Action("click_text", text = "pause")
            clean.contains("play") && (clean.contains("game") || clean.contains("playing") || clean == "play") -> out += Action("click_text", text = "play")
            clean.contains("stop listening") -> out += Action("stop_listening")
            clean.contains("help") || clean.contains("what can you do") -> out += Action("speak", text = "I can open apps, navigate, tap, type, scroll, read accessible screens, control volume, check battery and prepare WhatsApp messages. Say Hey Doc before a command if you like.")
            clean.startsWith("say ") -> out += Action("speak", text = c.substringAfter(' ').trim())
            else -> {
                val normalizedScreen = screen.lowercase(Locale.getDefault())
                if (clean.isNotBlank() && normalizedScreen.contains(clean)) out += Action("click_text", text = clean)
                else out += Action("speak", text = "I am online and ready. Try saying open WhatsApp, read the screen, go home, or tap a button.")
            }
        }
        return out
    }

    private fun parseWhatsApp(c: String): Pair<String, String>? {
        val p = Pattern.compile("(?i)(?:message|send)(?: whatsapp)?(?: to)?\\s+(.+?)\\s+(?:saying|that says|with message)\\s+(.+)")
        val m = p.matcher(c)
        return if (m.find()) m.group(1).trim() to m.group(2).trim() else null
    }

    data class Action(
        val type: String,
        val name: String = "",
        val text: String = "",
        val direction: String = "",
        val x: Double = -1.0,
        val y: Double = -1.0
    )

    fun shutdown() = Unit
}
