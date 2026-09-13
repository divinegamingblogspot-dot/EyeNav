package com.prince.eyenav

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.os.Build
import androidx.core.content.ContextCompat

/** Keeps DOC's user-enabled voice core recoverable after reboot/app update when Android permits FGS start. */
class DocBootReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent?) {
        val action = intent?.action ?: return
        if (action != Intent.ACTION_BOOT_COMPLETED &&
            action != Intent.ACTION_LOCKED_BOOT_COMPLETED &&
            action != Intent.ACTION_MY_PACKAGE_REPLACED) return

        val prefs = context.getSharedPreferences("doc_voice", Context.MODE_PRIVATE)
        if (!prefs.getBoolean("enabled", true)) return

        val start = Intent(context, DocVoiceService::class.java).setAction(DocVoiceService.ACTION_START)
        try {
            if (Build.VERSION.SDK_INT >= 26) ContextCompat.startForegroundService(context, start)
            else context.startService(start)
        } catch (_: Throwable) {
            // Android may prohibit microphone FGS startup directly from boot on newer releases.
            // The service will be restored the next time the user opens DOC.
        }
    }
}
