package com.soakula.wacr.recording

import android.annotation.SuppressLint
import android.content.ActivityNotFoundException
import android.os.Build
import android.service.quicksettings.Tile
import android.service.quicksettings.TileService
import android.widget.Toast
import com.soakula.wacr.R
import com.soakula.wacr.WacrApp
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.launch

class RecordTileService : TileService() {
    private val app get() = application as WacrApp
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)
    private var listening: Job? = null

    override fun onStartListening() {
        super.onStartListening()
        listening?.cancel()
        listening = scope.launch { app.recorder.state.collect(::render) }
    }

    override fun onStopListening() {
        listening?.cancel()
        listening = null
        super.onStopListening()
    }

    override fun onClick() {
        super.onClick()
        when (app.recorder.state.value) {
            is RecorderState.Preparing, is RecorderState.Recording -> app.recorder.stop()
            is RecorderState.Saving -> app.log.log("tile", "click_ignored_saving")
            else -> if (isLocked) unlockAndRun(::openRecording) else openRecording()
        }
    }

    @Suppress("DEPRECATION")
    @SuppressLint("StartActivityAndCollapseDeprecated")
    private fun openRecording() {
        try {
            if (Build.VERSION.SDK_INT >= 34) startActivityAndCollapse(RecordingEntry.pendingIntent(this))
            else startActivityAndCollapse(RecordingEntry.intent(this))
        } catch (error: ActivityNotFoundException) {
            failed(error)
        } catch (error: SecurityException) {
            failed(error)
        } catch (error: IllegalStateException) {
            failed(error)
        }
    }

    private fun render(state: RecorderState) {
        val tile = qsTile ?: return
        val active = state is RecorderState.Preparing || state is RecorderState.Recording
        tile.state = when {
            state is RecorderState.Saving -> Tile.STATE_UNAVAILABLE
            active -> Tile.STATE_ACTIVE
            else -> Tile.STATE_INACTIVE
        }
        tile.label = getString(if (active) R.string.tile_stop else R.string.tile_record)
        tile.subtitle = getString(when (state) {
            is RecorderState.Preparing -> R.string.recording_preparing
            is RecorderState.Recording -> R.string.tile_recording
            is RecorderState.Saving -> R.string.recording_saving
            else -> R.string.tile_tap_start
        })
        tile.contentDescription = "${tile.label}. ${tile.subtitle}"
        tile.updateTile()
    }

    private fun failed(error: Exception) {
        app.log.log("tile", "open_failed", mapOf("error_type" to error.javaClass.simpleName))
        Toast.makeText(this, R.string.recording_entry_error, Toast.LENGTH_LONG).show()
    }

    override fun onDestroy() {
        scope.cancel()
        super.onDestroy()
    }
}
