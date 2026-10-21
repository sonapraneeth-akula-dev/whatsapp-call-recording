package com.soakula.wacr.ui.library

import com.soakula.wacr.ui.preview.libraryBenchmarkCases
import com.soakula.wacr.ui.preview.libraryBenchmarkEntries
import java.time.ZoneId
import org.junit.Assert.*
import org.junit.Test

class LibraryBenchmarkTest {
    @Test fun thousandSyntheticRecordingsHaveUniqueIdsAndCorrectResultsForEveryBenchmarkCase() {
        val zone = ZoneId.of("Asia/Kolkata")
        val entries = libraryBenchmarkEntries(zone)
        val groups = groupRecordings(entries)
        assertEquals(1000, entries.size)
        assertEquals(1000, entries.map { it.recording.id }.toSet().size)
        assertEquals(100, groups.size)
        assertTrue(entries.all { it.recording.audioPath == null && it.recording.videoPath == null })
        for (case in libraryBenchmarkCases()) {
            val result = filterRecordingGroups(groups, case.route, zone)
            assertEquals(case.count, result.sumOf { it.entries.size })
        }
    }
}
