package com.soakula.wacr.data

import java.io.File
import java.util.UUID

class RecordingFiles(filesDir: File) {
    private val root = File(filesDir.canonicalFile, "rec")

    fun file(recordingId: UUID, name: String): File {
        require(NAME.matches(name)) { "Unsupported recording artifact name" }
        val file = File(File(root, recordingId.toString()), name)
        require(file.canonicalFile == file.absoluteFile) { "Recording paths must not contain symlinks" }
        return file
    }

    companion object {
        private val NAME = Regex("(audio\\.aac|audio\\.pending|video\\.mp4|seg_[0-9]{5,10})\\.enc")
    }
}
