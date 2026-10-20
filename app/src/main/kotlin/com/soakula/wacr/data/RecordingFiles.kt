package com.soakula.wacr.data

import com.soakula.wacr.recording.SegmentWriter
import java.io.File
import java.io.IOException
import java.util.UUID

class RecordingFiles(filesDir: File) {
    private val root = File(filesDir.canonicalFile, "rec")

    fun file(recordingId: UUID, name: String): File {
        require(NAME.matches(name)) { "Unsupported recording artifact name" }
        val file = File(File(root, recordingId.toString()), name)
        require(file.canonicalFile == file.absoluteFile) { "Recording paths must not contain symlinks" }
        return file
    }

    fun segmentIndices(recordingId: UUID): List<Int> {
        val directory = file(recordingId, "audio.aac.enc").parentFile!!
        if (!directory.exists()) return emptyList()
        val children = directory.listFiles() ?: throw IOException("Cannot list recording directory")
        return children.mapNotNull { child ->
            val match = SEGMENT.matchEntire(child.name) ?: return@mapNotNull null
            file(recordingId, child.name)
            val index = match.groupValues[1].toIntOrNull()
            if (index == null || index <= 0 || child.name != SegmentWriter.name(index)) {
                throw IOException("Invalid segment filename")
            }
            index
        }.sorted()
    }

    companion object {
        private val NAME = Regex("(audio\\.aac|audio\\.pending|video\\.mp4|seg_[0-9]{5,10})\\.enc")
        private val SEGMENT = Regex("seg_([0-9]{5,10})\\.enc")
    }
}
