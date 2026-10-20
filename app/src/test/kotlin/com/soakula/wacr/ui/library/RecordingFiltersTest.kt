package com.soakula.wacr.ui.library

import com.soakula.wacr.data.CallSessionEntity
import com.soakula.wacr.data.RecordingEntity
import com.soakula.wacr.data.RecordingWithSession
import java.time.Instant
import java.time.LocalDate
import java.time.ZoneId
import java.util.UUID
import org.junit.Assert.*
import org.junit.Test

class RecordingFiltersTest {
    private val contact = CallSessionEntity(waDisplayName = "Demo Contact", matchKey = "demo contact", isGroupKnown = true)
    private fun entry(id: Long, at: Long, session: CallSessionEntity? = contact, title: String? = null) =
        RecordingWithSession(RecordingEntity(UUID(0, id), at, 1, "MIC", title = title), session)

    @Test fun contactAndNameIntersectWithoutMergingUnverifiedGroups() {
        val groups = groupRecordings(listOf(entry(1, 1), entry(2, 2, contact.copy(isGroupKnown = false)),
            entry(3, 3, contact.copy(waDisplayName = "Other Person", matchKey = "other person"))))
        val filtered = filterRecordingGroups(groups, LibraryRoute("CONTACT:demo contact", query = "dEmO"))
        assertEquals(listOf(UUID(0, 1)), filtered.flatMap { it.entries }.map { it.recording.id })
        assertTrue(filterRecordingGroups(groups, LibraryRoute("CONTACT:demo contact", query = "other")).isEmpty())
        assertTrue(filterRecordingGroups(groups, LibraryRoute("CONTACT:deleted")).isEmpty())
    }

    @Test fun searchMatchesUnicodeContactNamesOrOwnerRecordingTitlesAndPreservesOrder() {
        val groups = groupRecordings(listOf(
            entry(1, 1, title = "Coffee \u2615"),
            entry(2, 2, contact.copy(waDisplayName = "\uFF24\uFF25\uFF2D\uFF2F Contact")),
            entry(3, 3, null, "Personal note")
        ))
        val byContact = filterRecordingGroups(groups, LibraryRoute(query = "  DEMO  "))
        assertEquals(listOf(UUID(0, 2), UUID(0, 1)), byContact.single().entries.map { it.recording.id })
        assertEquals(listOf(UUID(0, 1)), filterRecordingGroups(groups, LibraryRoute(query = "\u2615"))
            .single().entries.map { it.recording.id })
        assertEquals(listOf(UUID(0, 3)), filterRecordingGroups(groups, LibraryRoute(query = "NOTE"))
            .single().entries.map { it.recording.id })
        assertEquals(groups, filterRecordingGroups(groups, LibraryRoute()))
    }

    @Test fun localDateRangeIncludesBothWholeDaysAcrossShortAndLongDstDays() {
        val zone = ZoneId.of("America/New_York")
        for (date in listOf(LocalDate.of(2026, 3, 8), LocalDate.of(2026, 11, 1))) {
            val from = date.atStartOfDay(zone).toInstant().toEpochMilli()
            val until = date.plusDays(1).atStartOfDay(zone).toInstant().toEpochMilli()
            val groups = groupRecordings(listOf(entry(1, from - 1), entry(2, from), entry(3, until - 1), entry(4, until)))
            val result = filterRecordingGroups(groups, LibraryRoute(from = date, to = date), zone)
            assertEquals(listOf(UUID(0, 3), UUID(0, 2)), result.single().entries.map { it.recording.id })
        }
    }

    @Test fun dateAndQueryFiltersComposeAndCountsDescribeOnlyVisibleEntries() {
        val date = LocalDate.of(2026, 1, 15)
        val groups = groupRecordings(listOf(
            entry(1, Instant.parse("2026-01-14T16:00:00Z").toEpochMilli(), title = "Chosen clip"),
            entry(2, Instant.parse("2026-01-15T15:00:00Z").toEpochMilli(), title = "Other title"),
            entry(3, Instant.parse("2026-01-15T16:00:00Z").toEpochMilli(), title = "Chosen clip")
        ))
        val result = filterRecordingGroups(groups, LibraryRoute(from = date, to = date, query = "chosen"),
            ZoneId.of("Asia/Tokyo"))
        assertEquals(listOf(UUID(0, 1)), result.single().entries.map { it.recording.id })
    }

    @Test fun groupsReorderByTheirNewestMatchingRecordingAfterFiltering() {
        val groups = groupRecordings(listOf(
            entry(1, 300, title = "Excluded"), entry(2, 100, title = "Match"),
            entry(3, 200, contact.copy(waDisplayName = "Other", matchKey = "other"), "Match")
        ))
        val result = filterRecordingGroups(groups, LibraryRoute(query = "match"))
        assertEquals(listOf(UUID(0, 3), UUID(0, 2)), result.flatMap { it.entries }.map { it.recording.id })
    }
}
