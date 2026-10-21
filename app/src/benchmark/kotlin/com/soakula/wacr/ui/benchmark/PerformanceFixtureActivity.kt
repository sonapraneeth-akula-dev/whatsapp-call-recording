package com.soakula.wacr.ui.benchmark

import android.os.Bundle
import android.widget.TextView
import androidx.activity.ComponentActivity
import androidx.lifecycle.lifecycleScope
import androidx.room.withTransaction
import com.soakula.wacr.WacrApp
import com.soakula.wacr.data.CallSessionEntity
import com.soakula.wacr.data.CallType
import com.soakula.wacr.data.RecordingEntity
import com.soakula.wacr.data.RecordingStatus
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.util.UUID

class PerformanceFixtureActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        check(packageName == "com.soakula.wacr.benchmark") { "Benchmark package required" }
        val status = TextView(this).apply { text = "Preparing synthetic metadata" }
        setContentView(status)
        lifecycleScope.launch {
            // Errors deliberately fail the activity; never signal ready after partial seeding.
            withContext(Dispatchers.IO) { seed(application as WacrApp) }
            status.text = "Synthetic metadata ready"
            status.contentDescription = "benchmark_fixture_ready"
        }
    }

    internal suspend fun seed(app: WacrApp) {
        check(app.packageName == "com.soakula.wacr.benchmark") { "Benchmark package required" }
        val database = app.database()
        val dao = database.recordings()
        database.withTransaction {
            repeat(100) { index ->
                val session = CallSessionEntity(
                    id = 1_000_000L + index, waDisplayName = "Benchmark sample $index",
                    matchKey = "benchmark sample $index", isGroupKnown = true, callType = CallType.VOICE
                )
                val existing = dao.session(session.id)
                check(existing == null || existing == session) { "Fixture session conflict" }
                if (existing == null) dao.insertSession(session)
            }
            repeat(1_000) { index ->
                val recording = RecordingEntity(
                    id = UUID.nameUUIDFromBytes("wacr-benchmark-$index".toByteArray(Charsets.UTF_8)),
                    startedAt = 1_700_000_000_000L + index * 60_000L, channels = 1, captureSource = "MIC",
                    sessionId = 1_000_000L + index % 100, title = "Synthetic sample $index",
                    status = RecordingStatus.FINALIZED, durationMs = 30_000
                )
                val existing = dao.recording(recording.id)
                check(existing == null || existing == recording) { "Fixture recording conflict" }
                if (existing == null) dao.insertRecording(recording)
            }
        }
        app.preferences.completeOnboarding()
    }
}
