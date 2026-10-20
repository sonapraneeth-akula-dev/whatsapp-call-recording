package com.soakula.wacr.playback

import android.os.Process
import java.util.UUID

internal fun playbackControllerAllowed(uid: Int, packageName: String, trusted: Boolean, appUid: Int): Boolean =
    uid == appUid || uid == Process.SYSTEM_UID || (trusted && packageName == "com.android.systemui")

internal fun playbackId(value: String): UUID {
    val id = UUID.fromString(value)
    require(value == id.toString()) { "Invalid private playback identity" }
    return id
}
