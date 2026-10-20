package com.soakula.wacr.ui.library

import com.soakula.wacr.data.CallSessionEntity
import com.soakula.wacr.data.RecordingEntity
import com.soakula.wacr.data.RecordingWithSession
import org.junit.Assert.*
import org.junit.Test
import java.util.UUID

class RecordingGroupsTest {
    private fun entry(id: Long, at: Long, session: CallSessionEntity?) =
        RecordingWithSession(RecordingEntity(UUID(0, id), at, 1, "MIC"), session)

    @Test fun normalizedContactsGroupWithCountsAndNewestFirstDeterministicOrdering() {
        val old = entry(1, 100, CallSessionEntity(waDisplayName = "Demo Contact", matchKey = "demo contact", isGroupKnown = true))
        val latest = entry(2, 300, CallSessionEntity(waDisplayName = "DEMO CONTACT", matchKey = "demo contact", isGroupKnown = true))
        val other = entry(3, 200, CallSessionEntity(waDisplayName = "Other Person", isGroupKnown = true))
        val groups = groupRecordings(listOf(old, other, latest))
        assertEquals(2, groups.size)
        assertEquals("DEMO CONTACT", groups.first().name)
        assertEquals(LibraryContactKind.CONTACT, groups.first().kind)
        assertEquals(listOf(latest, old), groups.first().entries)
        assertEquals(listOf(other), groups.last().entries)
        assertEquals(groups, groupRecordings(listOf(latest, old, other)))
    }

    @Test fun manualUnknownUnverifiedAndPositiveGroupNeverMergeByAnIdenticalName() {
        val contact = CallSessionEntity(waDisplayName = "Demo", matchKey = "demo", isGroupKnown = true)
        val records = listOf(
            entry(1, 1, null),
            entry(2, 2, CallSessionEntity()),
            entry(3, 3, contact),
            entry(4, 4, contact.copy(isGroupKnown = false)),
            entry(5, 5, contact.copy(isGroup = true, groupName = "Demo group"))
        )
        val groups = groupRecordings(records)
        assertEquals(5, groups.size)
        assertEquals(5, groups.map { it.key }.toSet().size)
        assertEquals(LibraryContactKind.GROUP, groups.first().kind)
        assertEquals("Demo group", groups.first().name)
        assertEquals(LibraryContactKind.MANUAL, groups.last().kind)
        assertTrue(groupRecordings(emptyList()).isEmpty())
    }
}
