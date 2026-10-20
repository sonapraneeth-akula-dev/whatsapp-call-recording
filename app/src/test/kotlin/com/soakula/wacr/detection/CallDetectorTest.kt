package com.soakula.wacr.detection

import com.soakula.wacr.data.CallDirection
import com.soakula.wacr.data.CallType
import org.junit.Assert.*
import org.junit.Test

class CallDetectorTest {
    private fun detector(debounce: Long = 2000) = CallDetector(
        setOf("com.whatsapp", "com.whatsapp.w4b"), ConnectionRules("call", "CallStyle", 2), debounce, 0
    )
    private fun notification(
        timer: Boolean = true,
        key: String = "call",
        instance: Long? = 100,
        pkg: String = "com.whatsapp"
    ) = CallNotification(pkg, key, "call", true, "CallStyle", 2, timer, instance)

    @Test fun outgoingAttemptDoesNotConnectBeforeTimerDespiteAudioAndCallWindow() {
        val detector = detector()
        val events = mutableListOf<CallEvent>()
        events += detector.accept(CallSignal.AudioMode(true), 1)
        events += detector.accept(CallSignal.Window("com.whatsapp", true), 2)
        events += detector.accept(CallSignal.Posted(notification(false).copy(template = "BigTextStyle", styleCallType = null)), 3)
        assertEquals(CallState.RINGING, detector.state)
        events += detector.accept(CallSignal.Posted(notification(false)), 4)
        assertTrue(events.none { it is CallEvent.Started })
        events += detector.accept(CallSignal.Posted(notification()), 5)
        val call = events.filterIsInstance<CallEvent.Started>().single().call
        assertEquals(3L, call.ringingAtMs)
        assertEquals(5L, call.connectedAtMs)
        assertEquals(CallerName("Unknown", null), call.caller)
        assertNull(call.isGroup)
        assertNull(call.callType)
        assertEquals(CallDirection.UNKNOWN, call.direction)
        assertEquals(setOf("com.whatsapp"), detector.callWindowPackages)
    }

    @Test fun notificationCanArriveBeforeAudioAndDuplicateSignalsNeverDuplicateSession() {
        val detector = detector()
        assertTrue(detector.accept(CallSignal.Posted(notification()), 10).isEmpty())
        assertEquals(CallState.IDLE, detector.state)
        val start = detector.accept(CallSignal.AudioMode(true), 11).filterIsInstance<CallEvent.Started>().single()
        assertNull(start.call.ringingAtMs)
        assertTrue(detector.accept(CallSignal.Posted(notification()), 11).isEmpty())
        assertTrue(detector.accept(CallSignal.AudioMode(true), 12).isEmpty())
        assertTrue(detector.accept(CallSignal.Tick, 10_000).isEmpty())
        assertEquals(start.call, detector.activeCall)
    }

    @Test fun missedAndDeclinedAttemptsNeverCreateSessions() {
        for (declined in listOf(false, true)) {
            val detector = detector()
            val events = mutableListOf<CallEvent>()
            events += detector.accept(CallSignal.AudioMode(true), 1)
            events += detector.accept(CallSignal.Posted(notification(false).copy(styleCallType = 1)), 2)
            events += if (declined) detector.accept(CallSignal.Posted(notification(false).copy(ongoing = false)), 3)
            else detector.accept(CallSignal.Removed("com.whatsapp", "call"), 3)
            events += detector.accept(CallSignal.AudioMode(false), 4)
            events += detector.accept(CallSignal.Tick, 5000)
            assertTrue(events.none { it is CallEvent.Started || it is CallEvent.Ended })
            assertEquals(CallState.IDLE, detector.state)
        }
    }

    @Test fun directionSurvivesRingingToConnectedButUnknownGroupAndTypeStayUnknown() {
        val detector = detector()
        detector.accept(CallSignal.AudioMode(true), 0)
        detector.accept(CallSignal.Posted(notification(false).copy(
            styleCallType = 1, direction = CallDirection.INCOMING
        )), 1)
        val started = detector.accept(CallSignal.Posted(notification()), 1)
            .filterIsInstance<CallEvent.Started>().single().call
        assertEquals(CallDirection.INCOMING, started.direction)
        assertEquals(1L, started.ringingAtMs)
        assertNull(started.isGroup)
        assertNull(started.callType)
    }

    @Test fun unrelatedPackagesChatNotificationsAudioAndWindowsCannotCreateSession() {
        val detector = detector()
        val events = mutableListOf<CallEvent>()
        events += detector.accept(CallSignal.AudioMode(true), 0)
        events += detector.accept(CallSignal.Window("other.app", true), 1)
        events += detector.accept(CallSignal.Window("com.whatsapp", true), 2)
        events += detector.accept(CallSignal.Posted(notification(pkg = "other.app")), 3)
        events += detector.accept(CallSignal.Posted(notification().copy(category = "msg")), 4)
        events += detector.accept(CallSignal.Posted(notification().copy(ongoing = false)), 5)
        events += detector.accept(CallSignal.Tick, 60_000)
        assertTrue(events.isEmpty())
        assertNull(detector.activeCall)
        detector.accept(CallSignal.Window("com.whatsapp", false), 60_001)
        assertTrue(detector.callWindowPackages.isEmpty())
    }

    @Test fun removalEndsExactlyOnceAfterDebounceWithOriginalDisconnectTimestamp() {
        val detector = detector()
        detector.accept(CallSignal.AudioMode(true), 0)
        detector.accept(CallSignal.Posted(notification()), 100)
        assertTrue(detector.accept(CallSignal.Removed("com.whatsapp", "other"), 150).isEmpty())
        assertTrue(detector.accept(CallSignal.Removed("other.app", "call"), 160).isEmpty())
        assertEquals(CallSuspension.RECONNECTING, detector.accept(CallSignal.Removed("com.whatsapp", "call"), 200)
            .filterIsInstance<CallEvent.Suspended>().single().reason)
        assertEquals(2200L, detector.nextDeadlineMs)
        assertTrue(detector.accept(CallSignal.Tick, 2199).isEmpty())
        val end = detector.accept(CallSignal.Tick, 2200).filterIsInstance<CallEvent.Ended>().single()
        assertEquals(200L, end.endedAtMs)
        assertEquals(CallState.ENDED, detector.state)
        assertNull(detector.activeCall)
        assertNull(detector.nextDeadlineMs)
        assertTrue(detector.accept(CallSignal.Tick, 3000).isEmpty())
    }

    @Test fun briefCommunicationLossDoesNotSplitSessionAndExpiredEvidenceCannotRestartIt() {
        val detector = detector()
        detector.accept(CallSignal.AudioMode(true), 0)
        val call = detector.accept(CallSignal.Posted(notification()), 1).filterIsInstance<CallEvent.Started>().single().call
        detector.accept(CallSignal.AudioMode(false), 100)
        assertEquals(2100L, detector.nextDeadlineMs)
        assertEquals(call, detector.accept(CallSignal.AudioMode(true), 2099).filterIsInstance<CallEvent.Resumed>().single().call)
        assertNull(detector.nextDeadlineMs)
        assertEquals(call, detector.activeCall)
        detector.accept(CallSignal.AudioMode(false), 2200)
        assertEquals(1, detector.accept(CallSignal.Tick, 4200).filterIsInstance<CallEvent.Ended>().size)
        assertTrue(detector.accept(CallSignal.AudioMode(true), 4300).isEmpty())
        assertNull(detector.activeCall)
    }

    @Test fun replacementIdentityNeverMergesTwoConnectedCallsEvenWithReusedKeyAndCaller() {
        for (next in listOf(notification(instance = 200), notification(key = "second"), notification(pkg = "com.whatsapp.w4b"))) {
            val detector = detector()
            detector.accept(CallSignal.AudioMode(true), 0)
            val first = detector.accept(CallSignal.Posted(notification()), 1).filterIsInstance<CallEvent.Started>().single().call
            val events = detector.accept(CallSignal.Posted(next), 20)
            val ended = events.filterIsInstance<CallEvent.Ended>().single()
            val started = events.filterIsInstance<CallEvent.Started>().single()
            assertEquals(first, ended.call)
            assertEquals(20L, ended.endedAtMs)
            assertEquals(2L, started.call.sequence)
            assertEquals(20L, started.call.connectedAtMs)
            assertNull(started.call.ringingAtMs)
            assertTrue(events.indexOf(ended) < events.indexOf(started))
        }
    }

    @Test fun waitingAttemptDoesNotReplaceActiveSessionAndSourceLossEndsImmediately() {
        val detector = detector()
        detector.accept(CallSignal.AudioMode(true), 0)
        val current = detector.accept(CallSignal.Posted(notification()), 1).filterIsInstance<CallEvent.Started>().single().call
        assertTrue(detector.accept(CallSignal.Posted(notification(false, key = "waiting")), 2).isEmpty())
        assertEquals(current, detector.activeCall)
        val ended = detector.accept(CallSignal.Unavailable, 3).filterIsInstance<CallEvent.Ended>().single()
        assertEquals(3L, ended.endedAtMs)
        assertNull(detector.activeCall)
        assertTrue(detector.accept(CallSignal.AudioMode(true), 4).isEmpty())
    }

    @Test fun connectedSnapshotRetainsOnlyExplicitMetadataAndBusinessIsScopedSeparately() {
        val detector = detector(0)
        val caller = NameExtractor.extract("Example caller")
        detector.accept(CallSignal.AudioMode(true), 0)
        val notification = notification(pkg = "com.whatsapp.w4b").copy(
            caller = caller, direction = CallDirection.INCOMING, callType = CallType.VIDEO, isGroup = true
        )
        val started = detector.accept(CallSignal.Posted(notification), 1).filterIsInstance<CallEvent.Started>().single()
        assertEquals(caller, started.call.caller)
        assertEquals(CallDirection.INCOMING, started.call.direction)
        assertEquals(CallType.VIDEO, started.call.callType)
        assertEquals(true, started.call.isGroup)
        assertNull(started.call.ringingAtMs)
        assertEquals(1, detector.accept(CallSignal.Removed(notification.packageName, notification.key), 2)
            .filterIsInstance<CallEvent.Ended>().size)
    }

    @Test fun invalidSignalAtDeadlineCannotConsumeOrLosePendingSessionEnd() {
        val detector = detector()
        detector.accept(CallSignal.AudioMode(true), 0)
        detector.accept(CallSignal.Posted(notification()), 1)
        detector.accept(CallSignal.Removed("com.whatsapp", "call"), 2)
        assertThrows(IllegalArgumentException::class.java) {
            detector.accept(CallSignal.Posted(notification(key = "")), 2002)
        }
        assertNotNull(detector.activeCall)
        assertEquals(2002L, detector.nextDeadlineMs)
        assertEquals(2L, detector.accept(CallSignal.Tick, 2002)
            .filterIsInstance<CallEvent.Ended>().single().endedAtMs)
    }

    @Test fun invalidConnectionEvidenceNeverStartsAndMalformedBoundariesFailExplicitly() {
        for (notification in listOf(
            notification(instance = null), notification(instance = 0),
            notification().copy(template = "BigTextStyle"), notification().copy(styleCallType = 1)
        )) {
            val detector = detector()
            detector.accept(CallSignal.AudioMode(true), 0)
            assertTrue(detector.accept(CallSignal.Posted(notification), 1).none { it is CallEvent.Started })
        }
        val detector = detector()
        assertThrows(IllegalArgumentException::class.java) { detector.accept(CallSignal.Tick, -1) }
        detector.accept(CallSignal.Tick, 10)
        assertThrows(IllegalArgumentException::class.java) { detector.accept(CallSignal.Tick, 9) }
        assertThrows(IllegalArgumentException::class.java) {
            detector.accept(CallSignal.Posted(notification(key = "")), 11)
        }
        assertThrows(IllegalArgumentException::class.java) {
            detector.accept(CallSignal.Posted(notification(key = "x".repeat(513))), 12)
        }
        assertThrows(IllegalArgumentException::class.java) { detector.accept(CallSignal.Tick, Long.MAX_VALUE) }
        assertThrows(IllegalArgumentException::class.java) { detector(-1) }
        assertThrows(IllegalArgumentException::class.java) {
            CallDetector(setOf("other.app"), ConnectionRules("call", "CallStyle", 2), 2000, 0)
        }
        assertThrows(IllegalArgumentException::class.java) {
            CallDetector(setOf("com.whatsapp"), ConnectionRules("msg", "CallStyle", 2), 2000, 0)
        }
        repeat(8) { detector.accept(CallSignal.Posted(notification(false, key = "$it")), 20L + it) }
        assertThrows(IllegalArgumentException::class.java) {
            detector.accept(CallSignal.Posted(notification(false, key = "overflow")), 30)
        }
    }
}
