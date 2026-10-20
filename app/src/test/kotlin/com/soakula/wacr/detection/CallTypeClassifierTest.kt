package com.soakula.wacr.detection

import com.soakula.wacr.data.CallType
import org.junit.Assert.*
import org.junit.Test

class CallTypeClassifierTest {
    @Test fun explicitVideoPresenceClassifiesButMissingIsNotVoice() {
        assertEquals(CallType.VIDEO, CallTypeClassifier.classify(true))
        assertEquals(CallType.VOICE, CallTypeClassifier.classify(false))
        assertNull(CallTypeClassifier.classify(null))
    }

    @Test fun unknownClassificationAndRepeatedSnapshotsDoNotInventTypeSwitches() {
        for (previous in listOf(null, CallType.UNKNOWN, CallType.VOICE, CallType.VIDEO)) {
            assertNull(CallTypeClassifier.change(previous, null, 100))
            assertNull(CallTypeClassifier.change(previous, CallType.UNKNOWN, 100))
            if (previous != null) assertNull(CallTypeClassifier.change(previous, previous, 100))
        }
    }

    @Test fun voiceVideoAndReverseSwitchesPreserveExactObservedTime() {
        val synthetic = listOf(false to 100L, true to 200L, true to 201L, null to 202L, false to 300L)
        var known: CallType? = null
        val changes = synthetic.mapNotNull { (video, time) ->
            val observed = CallTypeClassifier.classify(video)
            CallTypeClassifier.change(known, observed, time)?.also { known = it.to }
        }
        assertEquals(listOf(
            CallTypeChange(CallType.UNKNOWN, CallType.VOICE, 100),
            CallTypeChange(CallType.VOICE, CallType.VIDEO, 200),
            CallTypeChange(CallType.VIDEO, CallType.VOICE, 300)
        ), changes)
    }

    @Test fun invalidObservationTimeIsRejectedEvenForOtherwiseUnchangedType() {
        assertThrows(IllegalArgumentException::class.java) {
            CallTypeClassifier.change(CallType.VOICE, CallType.VOICE, -1)
        }
    }
}
