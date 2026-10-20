package com.soakula.wacr.detection

import com.soakula.wacr.data.CallType
import org.junit.Assert.*
import org.junit.Test

class CallTypeEventsTest {
    private fun detector() = CallDetector(setOf("com.whatsapp"), ConnectionRules("call", "CallStyle", 2), 2000, 30_000)
    private fun signal(type: CallType?, instance: Long = 10, hold: Boolean? = null) = CallSignal.Posted(
        CallNotification("com.whatsapp", "synthetic", "call", true, "CallStyle", 2, true, instance,
            callType = type, onHold = hold)
    )

    @Test fun activeNativeTypeChangesStayInOneSessionAndUnknownSnapshotsDoNotDowngradeIt() {
        val detector = detector()
        detector.accept(CallSignal.AudioMode(true), 0)
        val start = detector.accept(signal(CallType.VOICE), 1).filterIsInstance<CallEvent.Started>().single().call
        val change = detector.accept(signal(CallType.VIDEO), 2).filterIsInstance<CallEvent.TypeChanged>().single()
        assertEquals(start.sequence, change.call.sequence)
        assertEquals(CallTypeChange(CallType.VOICE, CallType.VIDEO, 2), change.change)
        assertEquals(CallType.VIDEO, detector.activeCall?.callType)
        assertTrue(detector.accept(signal(null), 3).isEmpty())
        assertTrue(detector.accept(signal(CallType.UNKNOWN), 4).isEmpty())
        assertEquals(CallType.VIDEO, detector.activeCall?.callType)
        val reverse = detector.accept(signal(CallType.VOICE), 5).filterIsInstance<CallEvent.TypeChanged>().single()
        assertEquals(CallTypeChange(CallType.VIDEO, CallType.VOICE, 5), reverse.change)
        assertEquals(start.connectedAtMs, detector.activeCall?.connectedAtMs)
    }

    @Test fun heldAndProvisionallyEndedCallsKeepTheirOwnChangeEvents() {
        val detector = detector()
        detector.accept(CallSignal.AudioMode(true), 0)
        val first = detector.accept(signal(CallType.VOICE), 1).filterIsInstance<CallEvent.Started>().single().call
        detector.accept(signal(CallType.VOICE, hold = true), 2)
        val second = detector.accept(signal(CallType.VOICE, instance = 20), 3).filterIsInstance<CallEvent.Started>().single().call
        val heldChange = detector.accept(signal(CallType.VIDEO, hold = true), 4)
            .filterIsInstance<CallEvent.TypeChanged>().single()
        assertEquals(first.sequence, heldChange.call.sequence)
        assertEquals(CallType.VOICE, detector.activeCall?.callType)
        detector.accept(signal(CallType.VOICE, hold = false), 5)
        assertEquals(first.sequence, detector.activeCall?.sequence)
        assertNotEquals(second.sequence, detector.activeCall?.sequence)
        detector.accept(CallSignal.Removed("com.whatsapp", "synthetic"), 6)
        detector.accept(CallSignal.Tick, 2006)
        val resumed = detector.accept(signal(CallType.VIDEO, hold = false), 2007)
        assertEquals(first.sequence, resumed.filterIsInstance<CallEvent.Resumed>().single().call.sequence)
        assertEquals(CallTypeChange(CallType.VOICE, CallType.VIDEO, 2007),
            resumed.filterIsInstance<CallEvent.TypeChanged>().single().change)
    }

    @Test fun replacementCallAndRingingCannotChangeThePreviousCallsType() {
        val detector = detector()
        detector.accept(CallSignal.AudioMode(true), 0)
        detector.accept(signal(CallType.VOICE), 1)
        val events = detector.accept(signal(CallType.VIDEO, instance = 20), 2)
        assertTrue(events.none { it is CallEvent.TypeChanged })
        assertEquals(CallType.VOICE, events.filterIsInstance<CallEvent.Ended>().single().call.callType)
        assertEquals(CallType.VIDEO, events.filterIsInstance<CallEvent.Started>().single().call.callType)
        val ringing = signal(CallType.VOICE, instance = 20)
        assertTrue(detector.accept(ringing.copy(notification = ringing.notification.copy(chronometer = false)), 3)
            .none { it is CallEvent.TypeChanged })
    }
}
