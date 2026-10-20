package com.soakula.wacr.detection

import com.soakula.wacr.data.CallType

data class CallTypeChange(val from: CallType, val to: CallType, val atMs: Long)

object CallTypeClassifier {
    fun classify(isVideo: Boolean?): CallType? = isVideo?.let { if (it) CallType.VIDEO else CallType.VOICE }

    fun change(previous: CallType?, observed: CallType?, atMs: Long): CallTypeChange? {
        require(atMs >= 0) { "Invalid call-type observation time" }
        if (observed == null || observed == CallType.UNKNOWN || observed == previous) return null
        return CallTypeChange(previous ?: CallType.UNKNOWN, observed, atMs)
    }
}
