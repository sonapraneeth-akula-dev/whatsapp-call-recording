package com.soakula.wacr.spike.signals

import android.media.AudioManager
import android.os.Handler
import android.os.Looper
import com.soakula.wacr.spike.log.SpikeLog
import kotlinx.coroutines.flow.MutableStateFlow

/**
 * Spike-grade call detector: in call = audio mode IN_COMMUNICATION and (WhatsApp call notification present
 * or WhatsApp call activity shown). Every input is logged so the real rule can be derived from the logs.
 */
object CallGate {
    private val CALL_CLASS = Regex("(?i)voip|call")
    private val main = Handler(Looper.getMainLooper())
    private val notifKeys = mutableSetOf<String>()
    private var endPending: Runnable? = null

    @Volatile var modeComm = false; private set
    @Volatile var callWindow = false; private set
    @Volatile var inCall = false; private set
    val state = MutableStateFlow("idle")
    var listener: ((start: Boolean) -> Unit)? = null

    fun isCallClass(cls: String) = cls.startsWith("com.whatsapp") && CALL_CLASS.containsMatchIn(cls.substringAfterLast('.'))

    @Synchronized fun notification(key: String, isCall: Boolean, removed: Boolean) {
        if (isCall && !removed) notifKeys += key else notifKeys -= key
        evaluate("notification")
    }

    fun mode(m: Int) { modeComm = m == AudioManager.MODE_IN_COMMUNICATION; evaluate("mode") }

    fun window(cls: String) { callWindow = isCallClass(cls); evaluate("window") }

    fun signals(): Map<String, Any?> = mapOf(
        "notifCall" to synchronized(this) { notifKeys.isNotEmpty() }, "modeInCommunication" to modeComm, "callWindow" to callWindow
    )

    private fun evaluate(trigger: String) = main.post {
        val now = modeComm && (synchronized(this) { notifKeys.isNotEmpty() } || callWindow)
        state.value = if (now) "IN CALL" else "idle" + (if (modeComm) " (mode=IN_COMMUNICATION)" else "")
        when {
            now && !inCall -> {
                endPending?.let(main::removeCallbacks); endPending = null
                inCall = true
                SpikeLog.log("gate", "call_start", signals() + ("trigger" to trigger))
                listener?.invoke(true)
            }
            now -> { endPending?.let(main::removeCallbacks); endPending = null }
            !now && inCall && endPending == null -> {
                val r = Runnable {
                    endPending = null
                    val still = modeComm && (synchronized(this) { notifKeys.isNotEmpty() } || callWindow)
                    if (!still && inCall) {
                        inCall = false
                        SpikeLog.log("gate", "call_end", signals() + ("trigger" to trigger))
                        listener?.invoke(false)
                    }
                }
                endPending = r
                main.postDelayed(r, 2000)
            }
        }
    }
}
