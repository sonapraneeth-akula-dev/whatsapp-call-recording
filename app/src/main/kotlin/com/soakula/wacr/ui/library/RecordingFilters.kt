package com.soakula.wacr.ui.library

import java.text.Normalizer
import java.time.ZoneId
import java.util.Locale

internal fun filterRecordingGroups(
    groups: List<RecordingGroup>, route: LibraryRoute, zone: ZoneId = ZoneId.systemDefault()
): List<RecordingGroup> {
    fun searchKey(value: String) = Normalizer.normalize(value, Normalizer.Form.NFKC).lowercase(Locale.ROOT)
    val query = searchKey(route.query.trim())
    val from = route.from?.atStartOfDay(zone)?.toInstant()?.toEpochMilli()
    val until = route.to?.plusDays(1)?.atStartOfDay(zone)?.toInstant()?.toEpochMilli()
    if (route.contact == null && from == null && query.isEmpty()) return groups
    val entries = groups.flatMap { group ->
        if (route.contact != null && group.key != route.contact) return@flatMap emptyList()
        val nameMatches = query.isEmpty() || searchKey(group.name.orEmpty()).contains(query)
        group.entries.filter { entry ->
            (from == null || entry.recording.startedAt >= from) &&
                (until == null || entry.recording.startedAt < until) &&
                (nameMatches || searchKey(entry.recording.title.orEmpty()).contains(query))
        }
    }
    return groupRecordings(entries)
}
