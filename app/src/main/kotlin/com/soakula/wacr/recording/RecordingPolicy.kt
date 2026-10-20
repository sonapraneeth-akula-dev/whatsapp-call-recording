package com.soakula.wacr.recording

import com.soakula.wacr.data.ContactRuleEntity
import com.soakula.wacr.data.RuleKind
import com.soakula.wacr.detection.CallerName
import com.soakula.wacr.detection.NameExtractor

object RecordingPolicy {
    enum class Decision { AUTO, ASK }

    fun decide(caller: CallerName, isGroup: Boolean, rule: ContactRuleEntity?): Decision {
        val key = caller.matchKey
        return if (!isGroup && key != null && rule?.kind == RuleKind.CONTACT &&
            key == NameExtractor.matchKey(caller.displayName) && key == rule.matchKey &&
            key == NameExtractor.matchKey(rule.displayName)
        ) Decision.AUTO else Decision.ASK
    }
}
