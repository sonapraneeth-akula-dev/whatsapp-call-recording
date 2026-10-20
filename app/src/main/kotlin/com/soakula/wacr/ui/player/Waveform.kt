package com.soakula.wacr.ui.player

import android.database.SQLException
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.layout.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.dp
import com.soakula.wacr.R
import com.soakula.wacr.recording.WaveformPeaks
import java.io.IOException
import java.security.GeneralSecurityException
import java.security.ProviderException
import java.util.UUID
import kotlinx.coroutines.CancellationException

private enum class WaveformLoad { LOADING, READY, MISSING, FAILED }

@Composable
internal fun WaveformPanel(
    id: UUID, progress: Float, load: suspend () -> WaveformPeaks?, reportFailure: (Throwable) -> Unit
) {
    var state by remember(id) { mutableStateOf(WaveformLoad.LOADING) }
    var peaks by remember(id) { mutableStateOf<WaveformPeaks?>(null) }
    var retry by remember(id) { mutableIntStateOf(0) }
    fun failed(error: Throwable) { reportFailure(error); state = WaveformLoad.FAILED }
    LaunchedEffect(id, retry) {
        state = WaveformLoad.LOADING
        try {
            peaks = load()
            state = if (peaks == null) WaveformLoad.MISSING else WaveformLoad.READY
        } catch (error: CancellationException) { throw error
        } catch (error: IOException) { failed(error)
        } catch (error: GeneralSecurityException) { failed(error)
        } catch (error: ProviderException) { failed(error)
        } catch (error: SQLException) { failed(error)
        } catch (error: IllegalArgumentException) { failed(error)
        } catch (error: IllegalStateException) { failed(error)
        } catch (error: SecurityException) { failed(error)
        } catch (error: UnsatisfiedLinkError) { failed(error) }
    }
    when (state) {
        WaveformLoad.LOADING -> LinearProgressIndicator(Modifier.fillMaxWidth())
        WaveformLoad.MISSING -> Text(stringResource(R.string.player_waveform_missing))
        WaveformLoad.READY -> Waveform(checkNotNull(peaks), progress)
        WaveformLoad.FAILED -> Column {
            Text(stringResource(R.string.player_waveform_error), color = MaterialTheme.colorScheme.error)
            TextButton(onClick = { retry++ }) { Text(stringResource(R.string.library_retry)) }
        }
    }
}

@Composable
internal fun Waveform(peaks: WaveformPeaks, progress: Float) {
    require(progress.isFinite()) { "Invalid waveform position" }
    val label = stringResource(R.string.player_waveform)
    val played = MaterialTheme.colorScheme.primary
    val remaining = MaterialTheme.colorScheme.onSurfaceVariant
    BoxWithConstraints(Modifier.fillMaxWidth().height(64.dp).semantics { contentDescription = label }) {
        val bars = remember(peaks, maxWidth) { peaks.bars((maxWidth.value / 3).toInt().coerceAtLeast(1)) }
        Canvas(Modifier.fillMaxSize()) {
            val step = size.width / bars.size
            for (index in bars.indices) {
                val height = maxOf(1.dp.toPx(), bars[index] * size.height)
                val x = (index + .5f) * step
                drawLine(if (index < bars.size * progress.coerceIn(0f, 1f)) played else remaining,
                    Offset(x, (size.height - height) / 2), Offset(x, (size.height + height) / 2),
                    strokeWidth = minOf(2.dp.toPx(), step), cap = StrokeCap.Round)
            }
        }
    }
}
