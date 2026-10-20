package com.soakula.wacr.data

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertThrows
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.File
import java.io.IOException
import java.util.UUID

class RecordingFilesTest {
    @get:Rule val temporary = TemporaryFolder()

    @Test fun allArtifactsResolveUnderThePrivateRecordingDirectory() {
        val filesDir = temporary.newFolder()
        val paths = RecordingFiles(filesDir)
        val id = UUID.randomUUID()
        for (name in listOf("audio.aac.enc", "video.mp4.enc", "seg_00001.enc")) {
            assertEquals(File(File(File(filesDir.canonicalFile, "rec"), id.toString()), name), paths.file(id, name))
        }
        assertFalse(File(filesDir, "rec").exists())
    }

    @Test fun traversalAbsoluteNamesAndPlaintextFilesAreRejected() {
        val paths = RecordingFiles(temporary.newFolder())
        val id = UUID.randomUUID()
        for (name in listOf("../audio.aac.enc", "sub/audio.aac.enc", "sub\\audio.aac.enc", "C:\\audio.aac.enc", "/audio.aac.enc", "audio.aac", "seg_00001.enc/..")) {
            assertThrows(IllegalArgumentException::class.java) { paths.file(id, name) }
        }

    }

    @Test fun listingOrdersCanonicalSegmentIndicesAndRejectsAmbiguousNames() {
        val paths = RecordingFiles(temporary.newFolder())
        val id = UUID.randomUUID()
        assertEquals(emptyList<Int>(), paths.segmentIndices(id))
        val first = paths.file(id, "seg_00001.enc")
        check(first.parentFile!!.mkdirs())
        first.writeBytes(byteArrayOf(1))
        paths.file(id, "seg_00010.enc").writeBytes(byteArrayOf(2))
        paths.file(id, "audio.aac.enc").writeBytes(byteArrayOf(3))
        assertEquals(listOf(1, 10), paths.segmentIndices(id))
        paths.file(id, "seg_000001.enc").writeBytes(byteArrayOf(4))
        assertThrows(IOException::class.java) { paths.segmentIndices(id) }
    }
}
