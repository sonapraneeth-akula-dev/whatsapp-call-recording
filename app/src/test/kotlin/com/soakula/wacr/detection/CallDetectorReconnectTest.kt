package com.soakula.wacr.detection

import org.junit.Assert.*
import org.junit.Test

class CallDetectorReconnectTest {
    private fun detector() = CallDetector(
        setOf("com.whatsapp", "com.whatsapp.w4b"), ConnectionRules("call", "CallStyle", 2), 2000, 30_000
    )
    private fun notification(key: String = "call", instance: Long = 100) =
        CallNotification("com.whatsapp", key, "call", true, "CallStyle", 2, true, instance)

    private fun start(detector: CallDetector, notification: CallNotification = notification()): DetectedCall {
        detector.accept(CallSignal.AudioMode(true), 0)
        return detector.accept(CallSignal.Posted(notification), 1).filterIsInstance<CallEvent.Started>().single().call
    }

    @Test fun reconnectBeforeThirtySecondsReusesOriginalSessionNotANewStart() {
        val detector = detector()
        val first = start(detector)
        val suspended = detector.accept(CallSignal.Removed(first.packageName, first.notificationKey), 100)
            .filterIsInstance<CallEvent.Suspended>().single()
        assertEquals(100L, suspended.atMs)
        assertEquals(CallSuspension.RECONNECTING, suspended.reason)
        assertEquals(100L, detector.accept(CallSignal.Tick, 2100).filterIsInstance<CallEvent.Ended>().single().endedAtMs)
        assertEquals(30_100L, detector.nextDeadlineMs)
        val events = detector.accept(CallSignal.Posted(notification()), 30_099)
        val resumed = events.filterIsInstance<CallEvent.Resumed>().single()
        assertEquals(first, resumed.call)
        assertEquals(30_099L, resumed.atMs)
        assertTrue(events.none { it is CallEvent.Started })
        assertNull(detector.nextDeadlineMs)
        assertNull(detector.suspension)
    }

    @Test fun exactDeadlineNewInstanceOrDifferentKnownCallerCannotMerge() {
        val firstName = NameExtractor.extract("Caller A")
        val secondName = NameExtractor.extract("Caller B")
        for ((atMs, next) in listOf(
            30_100L to notification().copy(caller = firstName),
            3000L to notification(instance = 200).copy(caller = firstName),
            3000L to notification().copy(caller = secondName)
        )) {
            val detector = detector()
            start(detector, notification().copy(caller = firstName))
            detector.accept(CallSignal.Removed("com.whatsapp", "call"), 100)
            detector.accept(CallSignal.Tick, 2100)
            val events = detector.accept(CallSignal.Posted(next), atMs)
            assertEquals(2L, events.filterIsInstance<CallEvent.Started>().single().call.sequence)
            assertTrue(events.none { it is CallEvent.Resumed })
        }
    }

    @Test fun explicitHoldCanLastMinutesAndUnknownUpdatesDoNotSilentlyUnholdIt() {
        val detector = detector()
        val first = start(detector)
        val suspended = detector.accept(CallSignal.Posted(notification().copy(onHold = true)), 100)
            .filterIsInstance<CallEvent.Suspended>().single()
        assertEquals(CallSuspension.HOLD, suspended.reason)
        assertNull(detector.nextDeadlineMs)
        assertTrue(detector.accept(CallSignal.Tick, 300_000).isEmpty())
        assertTrue(detector.accept(CallSignal.Posted(notification()), 300_001).isEmpty())
        assertEquals(CallSuspension.HOLD, detector.suspension)
        val resumed = detector.accept(CallSignal.Posted(notification().copy(onHold = false)), 300_002)
            .filterIsInstance<CallEvent.Resumed>().single()
        assertEquals(first, resumed.call)
        assertEquals(CallState.CONNECTED, detector.state)
    }

    @Test fun cellularInterruptionPausesImmediatelyAndNeedsFreshWhatsAppEvidenceToResume() {
        val detector = detector()
        val first = start(detector)
        val suspended = detector.accept(CallSignal.AudioMode(false, cellularCall = true), 100)
            .filterIsInstance<CallEvent.Suspended>().single()
        assertEquals(CallSuspension.HOLD, suspended.reason)
        assertTrue(detector.accept(CallSignal.Tick, 90_000).isEmpty())
        assertTrue(detector.accept(CallSignal.AudioMode(true), 90_001).isEmpty())
        assertTrue(detector.accept(CallSignal.Tick, 100_000).isEmpty())
        assertEquals(CallSuspension.HOLD, detector.suspension)
        val resumed = detector.accept(CallSignal.Posted(notification()), 100_001)
            .filterIsInstance<CallEvent.Resumed>().single()
        assertEquals(first, resumed.call)
    }

    @Test fun waitingCallStaysSeparateAndBothHeldSessionsCanResumeWithoutNewSessionIds() {
        val detector = detector()
        val first = start(detector)
        assertTrue(detector.accept(CallSignal.Posted(notification("second", 200).copy(chronometer = false)), 10).isEmpty())
        detector.accept(CallSignal.Posted(notification().copy(onHold = true)), 20)
        val secondEvents = detector.accept(CallSignal.Posted(notification("second", 200)), 30)
        val second = secondEvents.filterIsInstance<CallEvent.Started>().single().call
        assertEquals(2L, second.sequence)
        assertTrue(secondEvents.none { it is CallEvent.Ended })
        assertTrue(detector.accept(CallSignal.Tick, 300_000).isEmpty())
        detector.accept(CallSignal.Posted(notification("second", 200).copy(onHold = true)), 300_001)
        val firstResumed = detector.accept(CallSignal.Posted(notification().copy(onHold = false)), 300_002)
            .filterIsInstance<CallEvent.Resumed>().single()
        assertEquals(first, firstResumed.call)
        detector.accept(CallSignal.Posted(notification().copy(onHold = true)), 300_003)
        val secondResumed = detector.accept(CallSignal.Posted(notification("second", 200).copy(onHold = false)), 300_004)
            .filterIsInstance<CallEvent.Resumed>().single()
        assertEquals(second, secondResumed.call)
        assertEquals(first, detector.accept(CallSignal.Removed("com.whatsapp", "call"), 300_005)
            .filterIsInstance<CallEvent.Ended>().single().call)
        assertEquals(second, detector.activeCall)
    }

    @Test fun sourceLossEndsCurrentAndParkedCallsAndNeverReusesRevokedState() {
        val detector = detector()
        val first = start(detector)
        detector.accept(CallSignal.Posted(notification().copy(onHold = true)), 10)
        val second = detector.accept(CallSignal.Posted(notification("second", 200)), 20)
            .filterIsInstance<CallEvent.Started>().single().call
        val ended = detector.accept(CallSignal.Unavailable, 30).filterIsInstance<CallEvent.Ended>()
        assertEquals(setOf(first.sequence, second.sequence), ended.map { it.call.sequence }.toSet())
        assertTrue(ended.all { it.endedAtMs == 30L })
        assertNull(detector.nextDeadlineMs)
        detector.accept(CallSignal.AudioMode(true), 31)
        val next = detector.accept(CallSignal.Posted(notification()), 32)
        assertEquals(3L, next.filterIsInstance<CallEvent.Started>().single().call.sequence)
        assertTrue(next.none { it is CallEvent.Resumed })
    }

    @Test fun notificationKeyReuseDuringCallWaitingDoesNotLoseTheHeldSession() {
        val detector = detector()
        val first = start(detector)
        detector.accept(CallSignal.Posted(notification().copy(onHold = true)), 10)
        detector.accept(CallSignal.Posted(notification(instance = 150).copy(chronometer = false)), 20)
        assertTrue(detector.accept(CallSignal.Tick, 40_000).isEmpty())
        assertNull(detector.nextDeadlineMs)
        val secondEvents = detector.accept(CallSignal.Posted(notification(instance = 200)), 40_001)
        val second = secondEvents.filterIsInstance<CallEvent.Started>().single().call
        assertTrue(secondEvents.none { it is CallEvent.Ended })
        assertTrue(detector.accept(CallSignal.Tick, 300_000).isEmpty())
        detector.accept(CallSignal.Posted(notification(instance = 200).copy(onHold = true)), 300_001)
        val resumed = detector.accept(CallSignal.Posted(notification().copy(onHold = false)), 300_002)
            .filterIsInstance<CallEvent.Resumed>().single().call
        assertEquals(first, resumed)
        val ended = detector.accept(CallSignal.Unavailable, 300_003).filterIsInstance<CallEvent.Ended>()
        assertEquals(setOf(first.sequence, second.sequence), ended.map { it.call.sequence }.toSet())
    }

    @Test fun conflictingKnownCallerCannotConsumeHeldSessionWithoutAnEndEvent() {
        val detector = detector()
        val first = start(detector, notification().copy(caller = NameExtractor.extract("Caller A")))
        detector.accept(CallSignal.Posted(notification().copy(
            caller = first.caller, onHold = true
        )), 10)
        val events = detector.accept(CallSignal.Posted(notification().copy(caller = NameExtractor.extract("Caller B"))), 20)
        assertEquals(first, events.filterIsInstance<CallEvent.Ended>().single().call)
        assertEquals(2L, events.filterIsInstance<CallEvent.Started>().single().call.sequence)
        assertTrue(events.none { it is CallEvent.Resumed })
    }

    @Test fun delayedPositiveHoldEvidenceKeepsOriginalSessionWithoutResumingCapture() {
        val detector = detector()
        val first = start(detector)
        detector.accept(CallSignal.AudioMode(false), 100)
        detector.accept(CallSignal.Tick, 2100)
        val held = detector.accept(CallSignal.Posted(notification().copy(onHold = true, chronometer = false)), 2200)
        assertEquals(first, held.filterIsInstance<CallEvent.Suspended>().single().call)
        assertTrue(held.none { it is CallEvent.Started || it is CallEvent.Resumed })
        assertTrue(detector.accept(CallSignal.Tick, 100_000).isEmpty())
        detector.accept(CallSignal.AudioMode(true), 100_001)
        assertEquals(first, detector.accept(CallSignal.Posted(notification().copy(onHold = false)), 100_002)
            .filterIsInstance<CallEvent.Resumed>().single().call)
    }

    @Test fun holdRemovalAndLateTimeoutCannotCreateAnExpiredReconnectTicket() {
        val detector = detector()
        start(detector)
        detector.accept(CallSignal.Posted(notification().copy(onHold = true)), 100)
        detector.accept(CallSignal.Removed("com.whatsapp", "call"), 100_000)
        assertEquals(CallSuspension.RECONNECTING, detector.suspension)
        assertEquals(102_000L, detector.nextDeadlineMs)
        assertEquals(100_000L, detector.accept(CallSignal.Tick, 140_000).filterIsInstance<CallEvent.Ended>().single().endedAtMs)
        assertNull(detector.nextDeadlineMs)
        assertEquals(2L, detector.accept(CallSignal.Posted(notification()), 140_001)
            .filterIsInstance<CallEvent.Started>().single().call.sequence)
    }

    @Test fun invalidReconnectAndContradictoryAudioInputsFailBeforeConsumingEvents() {
        assertThrows(IllegalArgumentException::class.java) {
            CallDetector(setOf("com.whatsapp"), ConnectionRules("call", "CallStyle", 2), 2000, 29_999)
        }
        val detector = detector()
        start(detector)
        detector.accept(CallSignal.Removed("com.whatsapp", "call"), 10)
        assertThrows(IllegalArgumentException::class.java) {
            detector.accept(CallSignal.AudioMode(true, cellularCall = true), 2010)
        }
        assertNotNull(detector.activeCall)
        assertEquals(10L, detector.accept(CallSignal.Tick, 2010).filterIsInstance<CallEvent.Ended>().single().endedAtMs)
    }

    @Test fun reconnectHistoryEvictsOldestTicketWithoutLosingNewerSessions() {
        val detector = detector()
        detector.accept(CallSignal.AudioMode(true), 0)
        for (index in 1L..9L) {
            val atMs = index * 3000
            detector.accept(CallSignal.Posted(notification(instance = index)), atMs)
            detector.accept(CallSignal.Removed("com.whatsapp", "call"), atMs + 1)
            detector.accept(CallSignal.Tick, atMs + 2001)
        }
        assertEquals(10L, detector.accept(CallSignal.Posted(notification(instance = 1)), 29_002)
            .filterIsInstance<CallEvent.Started>().single().call.sequence)
        assertEquals(9L, detector.accept(CallSignal.Posted(notification(instance = 9)), 29_003)
            .filterIsInstance<CallEvent.Resumed>().single().call.sequence)
    }

    @Test fun heldCapacityRejectsGrowthAtomicallyButAllowsAnExistingHeldCallToResume() {
        val detector = detector()
        val first = start(detector)
        var current = notification()
        var atMs = 2L
        repeat(8) { index ->
            detector.accept(CallSignal.Posted(current.copy(onHold = true)), atMs++)
            current = notification(instance = 200L + index)
            detector.accept(CallSignal.Posted(current), atMs++)
        }
        detector.accept(CallSignal.Posted(current.copy(onHold = true)), atMs++)
        assertThrows(IllegalArgumentException::class.java) {
            detector.accept(CallSignal.Posted(notification(instance = 999)), atMs)
        }
        assertEquals(9L, detector.activeCall?.sequence)
        assertEquals(first, detector.accept(CallSignal.Posted(notification().copy(onHold = false)), atMs++)
            .filterIsInstance<CallEvent.Resumed>().single().call)
        assertEquals(9, detector.accept(CallSignal.Unavailable, atMs)
            .filterIsInstance<CallEvent.Ended>().map { it.call.sequence }.distinct().size)
    }
}
