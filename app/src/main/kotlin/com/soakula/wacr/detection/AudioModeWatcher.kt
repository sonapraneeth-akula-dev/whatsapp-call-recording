package com.soakula.wacr.detection

import android.content.Context
import android.media.AudioManager

internal class AudioModeWatcher(context: Context, changed: (Int) -> Unit) : AutoCloseable {
    private val manager = checkNotNull(context.getSystemService(AudioManager::class.java))
    private val listener = AudioManager.OnModeChangedListener(changed)
    private var registered = false

    fun start(context: Context): Int {
        check(!registered) { "Audio watcher is already registered" }
        manager.addOnModeChangedListener(context.mainExecutor, listener)
        registered = true
        return manager.mode
    }

    override fun close() {
        if (registered) {
            manager.removeOnModeChangedListener(listener)
            registered = false
        }
    }
}
