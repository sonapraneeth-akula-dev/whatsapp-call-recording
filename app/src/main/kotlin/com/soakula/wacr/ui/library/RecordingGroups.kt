package com.soakula.wacr.ui.library

import com.soakula.wacr.data.RecordingWithSession
import com.soakula.wacr.detection.NameExtractor

internal enum class LibraryContactKind { CONTACT, GROUP, UNVERIFIED, UNKNOWN, MANUAL }

internal data class RecordingGroup(
    val key: String,
    val kind: LibraryContactKind,
    val name: String?,
    val entries: List<RecordingWithSession>
)

internal fun groupRecordings(entries: List<RecordingWithSession>): List<RecordingGroup> {
    data class Contact(val kind: LibraryContactKind, val key: String?)

    fun contact(entry: RecordingWithSession): Contact {
        val session = entry.session ?: return Contact(LibraryContactKind.MANUAL, null)
        val name = if (session.isGroupKnown && session.isGroup) session.groupName ?: session.waDisplayName else session.waDisplayName
        if (name.isNullOrBlank()) return Contact(LibraryContactKind.UNKNOWN, null)
        val key = session.matchKey ?: NameExtractor.matchKey(name)
        val kind = when {
            key == null -> LibraryContactKind.UNKNOWN
            !session.isGroupKnown -> LibraryContactKind.UNVERIFIED
            session.isGroup -> LibraryContactKind.GROUP
            else -> LibraryContactKind.CONTACT
        }
        return Contact(kind, key)
    }

    return entries.sortedWith(compareByDescending<RecordingWithSession> { it.recording.startedAt }
        .thenBy { it.recording.id.toString() }).groupBy(::contact).map { (contact, recordings) ->
        val session = recordings.first().session
        val name = if (contact.kind == LibraryContactKind.GROUP) session?.groupName ?: session?.waDisplayName
            else session?.waDisplayName
        RecordingGroup("${contact.kind}:${contact.key.orEmpty()}", contact.kind, name, recordings)
    }
}
