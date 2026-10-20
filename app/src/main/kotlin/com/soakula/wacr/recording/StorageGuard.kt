package com.soakula.wacr.recording

import android.os.StatFs
import android.os.SystemClock
import java.io.File
import java.io.IOException

enum class StorageLevel { READY, WARN, STOP }
data class StorageCheck(val availableBytes: Long, val level: StorageLevel)

class StorageGuard(
    private val freeBytes: () -> Long,
    private val elapsedMs: () -> Long = SystemClock::elapsedRealtime
) {
    private var checkedAt: Long? = null
    private var previous: StorageCheck? = null

    fun beforeStart(): StorageCheck = check(force = true)
    fun poll(): StorageCheck = check(force = false)

    private fun check(force: Boolean): StorageCheck {
        val now = elapsedMs()
        val last = checkedAt
        if (!force && last != null && now >= last && now - last < INTERVAL_MS) {
            return checkNotNull(previous)
        }
        val available = freeBytes()
        if (available < 0) throw IOException("Free-space source returned a negative value")
        val level = when {
            available < STOP_BYTES -> StorageLevel.STOP
            available < WARN_BYTES -> StorageLevel.WARN
            else -> StorageLevel.READY
        }
        return StorageCheck(available, level).also {
            previous = it
            checkedAt = now
        }
    }

    companion object {
        const val INTERVAL_MS = 30_000L
        const val WARN_BYTES = 500_000_000L
        const val STOP_BYTES = 100_000_000L

        fun forDirectory(directory: File): StorageGuard = StorageGuard({ StatFs(directory.path).availableBytes })
    }
}
