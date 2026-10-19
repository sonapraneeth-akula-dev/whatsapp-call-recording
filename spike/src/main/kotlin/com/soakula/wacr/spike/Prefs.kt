package com.soakula.wacr.spike

import android.content.ComponentName
import android.content.Context
import android.content.SharedPreferences
import android.provider.Settings
import androidx.core.app.NotificationManagerCompat
import androidx.core.content.edit

object Prefs {
    private lateinit var sp: SharedPreferences
    fun init(ctx: Context) { sp = ctx.getSharedPreferences("spike", Context.MODE_PRIVATE) }

    /** How the recorder is started when a call is detected: see [Host]. */
    var host: String
        get() = sp.getString("host", Host.ON_DETECT)!!
        set(v) = sp.edit { putString("host", v) }
    var autoSweep: Boolean
        get() = sp.getBoolean("autoSweep", true)
        set(v) = sp.edit { putBoolean("autoSweep", v) }
    var sideSeconds: Int
        get() = sp.getInt("sideSeconds", 10)
        set(v) = sp.edit { putInt("sideSeconds", v) }
    var treeDump: Boolean
        get() = sp.getBoolean("treeDump", true)
        set(v) = sp.edit { putBoolean("treeDump", v) }
    var screenshots: Boolean
        get() = sp.getBoolean("screenshots", false)
        set(v) = sp.edit { putBoolean("screenshots", v) }
    var redact: Boolean
        get() = sp.getBoolean("redact", true)
        set(v) = sp.edit { putBoolean("redact", v) }
    var includeAudio: Boolean
        get() = sp.getBoolean("includeAudio", false)
        set(v) = sp.edit { putBoolean("includeAudio", v) }
    var includeShots: Boolean
        get() = sp.getBoolean("includeShots", false)
        set(v) = sp.edit { putBoolean("includeShots", v) }

    /** WhatsApp notification titles (caller / group names), used only for redaction at export. */
    val knownNames: Set<String> get() = sp.getStringSet("knownNames", emptySet())!!
    fun addName(n: String?) {
        if (n.isNullOrBlank() || n in knownNames) return
        sp.edit { putStringSet("knownNames", knownNames + n) }
    }
}

object Host {
    /** Accessibility service starts the microphone FGS when the call is detected (design AD-012 primary). */
    const val ON_DETECT = "fgs_on_detect"
    /** FGS started beforehand from the app (shows "Ready" notification); call detection only starts capture (AD-012 fallback). */
    const val READY = "fgs_ready"
    /** No FGS: capture runs inside the accessibility service process directly. */
    const val DIRECT = "a11y_direct"
}

object Access {
    fun a11yEnabled(ctx: Context): Boolean {
        val me = ComponentName(ctx, SpikeAccessibilityService::class.java).flattenToString()
        val on = Settings.Secure.getString(ctx.contentResolver, Settings.Secure.ENABLED_ACCESSIBILITY_SERVICES) ?: return false
        return on.split(':').any { it.equals(me, ignoreCase = true) }
    }

    fun notificationAccess(ctx: Context) = ctx.packageName in NotificationManagerCompat.getEnabledListenerPackages(ctx)
}
