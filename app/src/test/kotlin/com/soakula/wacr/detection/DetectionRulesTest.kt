package com.soakula.wacr.detection

import android.app.Application
import org.json.JSONArray
import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config
import java.io.ByteArrayInputStream
import java.time.LocalDate

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [33], application = Application::class)
class DetectionRulesTest {
    private fun asset(): String = RuntimeEnvironment.getApplication().assets
        .open("detection_rules.json").bufferedReader().use { it.readText() }

    private fun load(text: String = asset()) = DetectionRules.load(text.byteInputStream())

    @Test fun packagedRulesHaveExplicitPartialCapturedProvenanceAndSafeTiming() {
        val rules = load()
        assertEquals("2026-10-20-a73-v1", rules.version)
        assertEquals(setOf("com.whatsapp", "com.whatsapp.w4b"), rules.packages)
        assertEquals(2000L, rules.endDebounceMs)
        assertEquals(30_000L, rules.reconnectWindowMs)
        val profile = rules.profile("com.whatsapp", "2.26.40.76", "SM-A736B", 36)!!
        assertEquals(setOf("com.whatsapp.calling.ui.VoipActivityV2"), profile.callWindowClasses)
        assertEquals(LocalDate.of(2026, 10, 19), profile.capturedOn)
        assertEquals(setOf("outgoing_voice"), profile.verifiedScenarios)
        assertTrue("group_classification_unverified" in profile.limitations)
        assertTrue("video_extra_presence_not_captured" in profile.limitations)
    }

    @Test fun unknownBuildDeviceOsOrBusinessNeverInheritVerifiedCallerProfile() {
        val rules = load()
        assertNull(rules.profile("com.whatsapp", "2.26.40.77", "SM-A736B", 36))
        assertNull(rules.profile("com.whatsapp", "2.26.40.76-beta", "SM-A736B", 36))
        assertNull(rules.profile("com.whatsapp", "2.26.40.76", "SM-E426B", 33))
        assertNull(rules.profile("com.whatsapp", "2.26.40.76", "SM-A736B", 35))
        assertNull(rules.profile("com.whatsapp.w4b", "2.26.40.76", "SM-A736B", 36))
    }

    @Test fun invalidDocumentsThrowInsteadOfUsingDefaultRules() {
        for (document in listOf("", "{", "[]", "null", "true", asset() + " {}", asset().replace("\"schemaVersion\": 1", "\"schemaVersion\": 2"))) {
            rejects(document)
        }
        assertThrows(IllegalArgumentException::class.java) { load(" ".repeat(65_537)) }
        assertThrows(Exception::class.java) { DetectionRules.load(ByteArrayInputStream(byteArrayOf(0xC3.toByte(), 0x28))) }
    }

    @Test fun lenientJsonExtensionsDuplicateKeysAndExcessiveNestingAreRejected() {
        for (document in listOf(
            asset().replace("\"schemaVersion\"", "'schemaVersion'"),
            asset().replace("\"schemaVersion\"", "schemaVersion"),
            asset().replace("\"schemaVersion\": 1", "\"schemaVersion\": 1, \"schemaVersion\": 1"),
            asset().replace("\"schemaVersion\": 1", "\"schemaVersion\": 01"),
            asset().replace("\"schemaVersion\": 1", "\"schemaVersion\": 1 /* comment */"),
            asset().replace("\"schemaVersion\": 1", "\"schemaVersion\" = 1"),
            asset().replace("\"schemaVersion\": 1", "\"schemaVersion\": 1;"),
            "[".repeat(100) + "]".repeat(100)
        )) rejects(document)
    }

    @Test fun missingUnknownCoercedAndUnsafeFieldsAreRejected() {
        val mutations: List<(JSONObject) -> Unit> = listOf(
            { it.remove("version"); Unit },
            { it.put("unexpected", true); Unit },
            { it.put("schemaVersion", "1"); Unit },
            { it.put("endDebounceMs", -1); Unit },
            { it.put("endDebounceMs", 5001); Unit },
            { it.put("endDebounceMs", 1.5); Unit },
            { it.put("reconnectWindowMs", 29_999); Unit },
            { it.put("packages", JSONArray(listOf("com.whatsapp", "other.app"))); Unit },
            { it.put("packages", JSONArray(listOf("com.whatsapp", "com.whatsapp"))); Unit },
            { it.put("profiles", JSONArray()); Unit },
            { it.getJSONObject("connection").put("requireChronometer", false); Unit },
            { it.getJSONObject("connection").put("requireCommunicationMode", false); Unit },
            { it.getJSONObject("connection").put("requireOngoing", "true"); Unit },
            { it.getJSONObject("connection").put("ongoingCallType", "2"); Unit },
            { it.getJSONObject("connection").put("category", "msg"); Unit },
            { it.getJSONObject("connection").put("template", "BigTextStyle"); Unit }
        )
        mutations.forEach { mutate -> rejects(JSONObject(asset()).also(mutate).toString()) }
    }

    @Test fun malformedAndAmbiguousProfilesAreRejected() {
        val mutations: List<(JSONObject) -> Unit> = listOf(
            { it.put("packageName", "other.app"); Unit },
            { it.put("callerField", "arbitrary_node_text"); Unit },
            { it.put("versionName", ".*"); Unit },
            { it.put("versionName", "1".repeat(80) + ".1"); Unit },
            { it.put("callWindowClasses", JSONArray(listOf(".*Voip.*"))); Unit },
            { it.put("callWindowClasses", JSONArray(listOf("com.other.VoipActivity"))); Unit },
            { it.getJSONObject("evidence").put("sdkInt", "36"); Unit },
            { it.getJSONObject("evidence").put("sdkInt", 32); Unit },
            { it.getJSONObject("evidence").put("capturedOn", "2026-02-30"); Unit },
            { it.getJSONObject("evidence").put("verifiedScenarios", JSONArray(listOf("invented"))); Unit },
            { it.getJSONObject("evidence").put("limitations", JSONArray(listOf(JSONObject()))); Unit },
            { it.getJSONObject("evidence").put("rawCallerName", "Private test caller"); Unit }
        )
        mutations.forEach { mutate ->
            val root = JSONObject(asset())
            mutate(root.getJSONArray("profiles").getJSONObject(0))
            rejects(root.toString())
        }
        val root = JSONObject(asset())
        root.getJSONArray("profiles").put(root.getJSONArray("profiles").getJSONObject(0))
        rejects(root.toString())
    }

    @Test fun schemaIsPackagedWithRequiredKeysAndConnectionSafetyConstants() {
        val schema = RuntimeEnvironment.getApplication().assets.open("detection_rules.schema.json")
            .bufferedReader().use { JSONObject(it.readText()) }
        val root = JSONObject(asset())
        val required = schema.getJSONArray("required")
        assertEquals(root.keys().asSequence().toSet(), (0 until required.length()).map(required::getString).toSet())
        assertFalse(schema.getBoolean("additionalProperties"))
        val connection = schema.getJSONObject("properties").getJSONObject("connection")
        val properties = connection.getJSONObject("properties")
        val values = root.getJSONObject("connection")
        properties.keys().forEach { key ->
            assertEquals(properties.getJSONObject(key).get("const"), values.get(key))
        }
    }

    private fun rejects(document: String) {
        assertThrows(Exception::class.java) { load(document) }
    }
}
