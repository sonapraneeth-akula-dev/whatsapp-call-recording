package com.soakula.wacr.recording

import android.media.MediaRecorder.AudioSource
import com.soakula.wacr.data.AudioRoute

enum class CaptureSource(val androidId: Int) {
    VOICE_RECOGNITION(AudioSource.VOICE_RECOGNITION),
    VOICE_COMMUNICATION(AudioSource.VOICE_COMMUNICATION),
    MIC(AudioSource.MIC),
    CAMCORDER(AudioSource.CAMCORDER),
    UNPROCESSED(AudioSource.UNPROCESSED)
}

data class CaptureProfile(
    val sources: List<CaptureSource> = listOf(CaptureSource.VOICE_RECOGNITION),
    val routeSources: Map<AudioRoute, List<CaptureSource>> = emptyMap()
) {
    init {
        (routeSources.values + listOf(sources)).forEach {
            require(it.isNotEmpty() && it.distinct().size == it.size) { "Capture sources must be nonempty and unique" }
        }
    }

    fun sourcesFor(route: AudioRoute?): List<CaptureSource> = (routeSources[route] ?: sources).toList()
}
