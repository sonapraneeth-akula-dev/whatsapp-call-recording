package com.soakula.wacr.ui.preview

import com.soakula.wacr.data.CallSessionEntity
import com.soakula.wacr.data.CallType
import com.soakula.wacr.data.RecordingEntity
import com.soakula.wacr.data.RecordingStatus
import com.soakula.wacr.data.RecordingWithSession
import com.soakula.wacr.ui.library.LibraryRoute
import java.time.LocalDate
import java.time.ZoneId
import java.util.Locale
import java.util.UUID

internal fun libraryBenchmarkEntries(zone: ZoneId = ZoneId.systemDefault()): List<RecordingWithSession> =
    (0 until 1000).map { index ->
        val number = String.format(Locale.ROOT, "%03d", index / 10)
        val session = CallSessionEntity(
            id = index + 1L, waDisplayName = "Benchmark Contact $number",
            matchKey = "benchmark contact $number", isGroupKnown = true,
            callType = if (index % 2 == 0) CallType.VOICE else CallType.VIDEO
        )
        RecordingWithSession(RecordingEntity(
            UUID(0, index + 1L),
            LocalDate.of(2026, 10, 1).plusDays((index % 20).toLong()).atTime(12, 0)
                .atZone(zone).toInstant().toEpochMilli() + index,
            1, "SYNTHETIC", sessionId = session.id, status = RecordingStatus.FINALIZED,
            title = String.format(Locale.ROOT, "Synthetic clip %04d", index),
            durationMs = 60_000, sizeBytes = 120_000
        ), session)
    }

internal data class LibraryBenchmarkCase(val route: LibraryRoute, val count: Int)

internal fun libraryBenchmarkCases(): List<LibraryBenchmarkCase> = listOf(
    LibraryBenchmarkCase(LibraryRoute(), 1000),
    LibraryBenchmarkCase(LibraryRoute(query = "CONTACT 001"), 10),
    LibraryBenchmarkCase(LibraryRoute(query = "clip 0001"), 1),
    LibraryBenchmarkCase(LibraryRoute(from = LocalDate.of(2026, 10, 5), to = LocalDate.of(2026, 10, 9)), 250),
    LibraryBenchmarkCase(LibraryRoute("CONTACT:benchmark contact 001",
        LocalDate.of(2026, 10, 15), LocalDate.of(2026, 10, 19)), 5),
    LibraryBenchmarkCase(LibraryRoute(query = "no such recording"), 0)
)
