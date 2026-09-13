package com.prince.eyenav

import android.content.Context
import java.util.Locale
import java.util.regex.Pattern

/** DOC // local cognitive router: deterministic, offline-first command execution. */
class DocBrain(private val context: Context) {
    fun hasKey(): Boolean = true
    fun setKey(key: String) = Unit

    fun think(command: String, screen: String, callback: (List<Action>, String?, String?) -> Unit) {
        callback(parse(command, screen), null, null)
    }

    private fun parse(raw: String, screen: String): List<Action> {
        val original = raw.trim()
        if (original.isBlank()) return emptyList()
        val clean = original.replace(Regex("^(hey|ok|okay|yo)?\\s*doc[,:]?\\s*", RegexOption.IGNORE_CASE), "").trim()
        val l = clean.lowercase(Locale.getDefault())
        val out = mutableListOf<Action>()
        when {
            l.matches(Regex("(go )?home|home screen|take me home|return home|go to home")) -> out += Action("home")
            l.matches(Regex("(go )?back|back screen|previous screen|go previous")) -> out += Action("back")
            l.contains("recent apps") || l == "recents" || l.contains("show recent") -> out += Action("recents")
            l.contains("notifications") || l.contains("notification panel") -> out += Action("notifications")
            l.contains("quick settings") || l.contains("control center") -> out += Action("quick_settings")
            l.contains("scroll down") || l.contains("move down") || l == "down" -> out += Action("scroll", direction = "down")
            l.contains("scroll up") || l.contains("move up") || l == "up" -> out += Action("scroll", direction = "up")
            l.contains("scroll left") || l == "left" -> out += Action("scroll", direction = "left")
            l.contains("scroll right") || l == "right" -> out += Action("scroll", direction = "right")
            l.contains("read screen") || l.contains("read this screen") || l.contains("what is on screen") || l.contains("what's on screen") || l.contains("what do you see") || l.contains("scan screen") -> out += Action("read_screen")
            l.contains("time") && (l.contains("what") || l.contains("current") || l == "time") -> out += Action("time")
            l.contains("date") || l.contains("what day is it") || l.contains("today's day") -> out += Action("date")
            l.contains("battery") || l.contains("charge") -> out += Action("battery")
            l.contains("volume up") || l.contains("increase volume") || l.contains("make it louder") || l == "louder" -> out += Action("volume", direction = "up")
            l.contains("volume down") || l.contains("decrease volume") || l.contains("make it quieter") || l == "quieter" -> out += Action("volume", direction = "down")
            l == "mute" || l.contains("mute phone") || l.contains("silence phone") -> out += Action("volume", direction = "mute")
            l.contains("unmute") -> out += Action("volume", direction = "unmute")
            l.contains("flashlight") || l.contains("torch") -> out += Action("flashlight", direction = if (l.contains("off") || l.contains("disable")) "off" else "toggle")
            l.contains("brightness") -> out += Action("brightness", direction = if (l.contains("down") || l.contains("lower") || l.contains("dim")) "down" else if (l.contains("max") || l.contains("full")) "max" else if (l.contains("up") || l.contains("increase") || l.contains("bright")) "up" else "show")
            l.contains("wifi") && (l.contains("settings") || l.contains("turn") || l.contains("connect")) -> out += Action("wifi")
            l.contains("bluetooth") -> out += Action("bluetooth")
            l == "settings" || l.startsWith("open settings") || l.startsWith("show settings") -> out += Action("settings")
            l.contains("accessibility") -> out += Action("accessibility_settings")
            l == "camera" || l.startsWith("open camera") || l.startsWith("launch camera") -> out += Action("open_app", name = "Camera")
            l.startsWith("open ") || l.startsWith("launch ") || l.startsWith("start app ") -> out += Action("open_app", name = if (l.startsWith("start app ")) original.substring(10).trim() else original.substringAfter(' ').trim())
            l.startsWith("close ") || l.startsWith("exit ") -> out += Action("back")
            l.startsWith("tap ") || l.startsWith("click ") || l.startsWith("press ") || l.startsWith("select ") -> out += Action("click_text", text = original.substringAfter(' ').trim())
            l.startsWith("long press ") || l.startsWith("long click ") -> out += Action("long_click_text", text = original.substringAfter(' ').substringAfter(' ').trim())
            l.startsWith("type ") || l.startsWith("write ") || l.startsWith("enter ") || l.startsWith("fill ") -> out += Action("type_text", text = original.substringAfter(' ').trim())
            l.startsWith("search for ") || l.startsWith("google ") || l.startsWith("search ") -> out += Action("web_search", text = original.substringAfter(' ').trim().removePrefix("for ").trim())
            l.startsWith("go to ") || l.startsWith("navigate to ") || l.startsWith("take me to ") -> out += Action("maps", text = original.substringAfter("to ").trim())
            l.startsWith("call ") || l.startsWith("dial ") -> out += Action("call", text = original.substringAfter(' ').trim())
            l.startsWith("text ") || l.startsWith("sms ") -> out += Action("sms", text = original.substringAfter(' ').trim())
            l.contains("set a timer") || l.contains("set timer") -> out += Action("timer", text = original)
            l.contains("set an alarm") || l.contains("set alarm") || l.contains("wake me") -> out += Action("alarm", text = original)
            l.contains("screenshot") || l.contains("capture screen") -> out += Action("screenshot")
            l.contains("play music") || l == "play" || l.contains("resume music") -> out += Action("media", direction = "play")
            l.contains("pause music") || l == "pause" || l.contains("pause playback") -> out += Action("media", direction = "pause")
            l.contains("next song") || l.contains("next track") -> out += Action("media", direction = "next")
            l.contains("previous song") || l.contains("previous track") -> out += Action("media", direction = "previous")
            l.contains("whatsapp") && (l.contains("message") || l.contains("text ") || l.contains("send ")) -> {
                val m = parseWhatsApp(clean)
                if (m != null) out += Action("whatsapp_message", name = m.first, text = m.second) else out += Action("open_app", name = "WhatsApp")
            }
            l == "send" || l == "send it" || l == "send message" -> out += Action("send_pending")
            l.contains("pause") && (l.contains("game") || l.contains("playing")) -> out += Action("click_text", text = "pause")
            l.contains("stop listening") || l.contains("sleep doc") || l.contains("go to sleep") -> out += Action("stop_listening")
            l.contains("wake up doc") || l.contains("resume listening") -> out += Action("resume_listening")
            l.contains("help") || l.contains("what can you do") -> out += Action("speak", text = "I am Doc. I can control navigation, apps, accessibility taps, typing, scrolling, screen reading, calls, messages, maps, search, alarms, timers, media, flashlight, brightness, volume, battery and WhatsApp. I run without an AI API key.")
            l.startsWith("say ") -> out += Action("speak", text = original.substringAfter(' ').trim())
            l.startsWith("remember ") -> out += Action("remember", text = original.substringAfter(' ').trim())
            else -> {
                val normalizedScreen = screen.lowercase(Locale.getDefault())
                if (l.isNotBlank() && normalizedScreen.contains(l)) out += Action("click_text", text = clean)
                else out += Action("speak", text = "I heard you, but I do not have a safe local action for that command yet.")
            }
        }
        return out
    }

    private fun parseWhatsApp(c: String): Pair<String, String>? {
        val patterns = listOf(
            Pattern.compile("(?i)(?:message|send)(?: whatsapp)?(?: to)?\\s+(.+?)\\s+(?:saying|that says|with message)\\s+(.+)"),
            Pattern.compile("(?i)whatsapp\\s+(.+?)\\s*:\\s*(.+)")
        )
        for (p in patterns) {
            val m = p.matcher(c)
            if (m.find()) return m.group(1).trim() to m.group(2).trim()
        }
        return null
    }

    data class Action(val type: String, val name: String = "", val text: String = "", val direction: String = "", val x: Double = -1.0, val y: Double = -1.0)
    fun shutdown() = Unit
}
