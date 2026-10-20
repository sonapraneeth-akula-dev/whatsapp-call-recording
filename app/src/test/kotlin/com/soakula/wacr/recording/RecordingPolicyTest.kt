package com.soakula.wacr.recording

import com.soakula.wacr.data.ContactRuleEntity
import com.soakula.wacr.data.RuleKind
import com.soakula.wacr.detection.CallerName
import com.soakula.wacr.detection.NameExtractor
import org.junit.Assert.assertEquals
import org.junit.Test

class RecordingPolicyTest {
    private val rule = ContactRuleEntity("Demo Contact", "demo contact", 1000, id = 1)

    @Test fun autoRecordsOnlyMatchingNormalizedContacts() {
        for (name in listOf("Demo Contact", "DEMO  CONTACT", "Demo Contact \uD83D\uDE00", "\uFF24\uFF45\uFF4D\uFF4F Contact")) {
            assertEquals(RecordingPolicy.Decision.AUTO, RecordingPolicy.decide(NameExtractor.extract(name), false, rule))
        }
    }

    @Test fun absentRulesUnknownNamesAndOtherContactsRequireConfirmation() {
        val known = NameExtractor.extract("Demo Contact")
        assertEquals(RecordingPolicy.Decision.ASK, RecordingPolicy.decide(known, false, null))
        for (name in listOf(null, "", "  ", "\uD83D\uDE00", "Other contact", "+91 000 000 0000")) {
            assertEquals(RecordingPolicy.Decision.ASK, RecordingPolicy.decide(NameExtractor.extract(name), false, rule))
        }
        val unknownRule = rule.copy(displayName = "Unknown", matchKey = "unknown")
        assertEquals(RecordingPolicy.Decision.ASK, RecordingPolicy.decide(NameExtractor.extract(null), false, unknownRule))
    }

    @Test fun groupsRemainManualEvenWithMatchingContactOrGroupRules() {
        val caller = NameExtractor.extract("Demo Contact")
        assertEquals(RecordingPolicy.Decision.ASK, RecordingPolicy.decide(caller, true, rule))
        assertEquals(RecordingPolicy.Decision.ASK, RecordingPolicy.decide(caller, true, rule.copy(kind = RuleKind.GROUP)))
        assertEquals(RecordingPolicy.Decision.ASK, RecordingPolicy.decide(caller, false, rule.copy(kind = RuleKind.GROUP)))
        assertEquals(RecordingPolicy.Decision.ASK, RecordingPolicy.decide(caller, null, rule))
    }

    @Test fun inconsistentKeysCannotTriggerAutomaticRecording() {
        assertEquals(RecordingPolicy.Decision.ASK,
            RecordingPolicy.decide(CallerName("Other contact", rule.matchKey), false, rule))
        assertEquals(RecordingPolicy.Decision.ASK,
            RecordingPolicy.decide(NameExtractor.extract("Demo Contact"), false, rule.copy(displayName = "Other contact")))
        assertEquals(RecordingPolicy.Decision.ASK,
            RecordingPolicy.decide(NameExtractor.extract("Demo Contact"), false, rule.copy(matchKey = "other contact")))
    }
}
