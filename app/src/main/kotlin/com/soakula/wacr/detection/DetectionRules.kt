package com.soakula.wacr.detection

import android.util.JsonReader
import android.util.JsonToken
import org.json.JSONArray
import org.json.JSONObject
import org.json.JSONTokener
import java.io.InputStream
import java.time.LocalDate

data class ConnectionRules(val category: String, val template: String, val ongoingCallType: Int)

data class DetectionProfile(
    val packageName: String,
    val versionName: String,
    val callWindowClasses: Set<String>,
    val deviceModel: String,
    val sdkInt: Int,
    val capturedOn: LocalDate,
    val verifiedScenarios: Set<String>,
    val limitations: Set<String>
)

class DetectionRules private constructor(
    val version: String,
    val packages: Set<String>,
    val connection: ConnectionRules,
    val endDebounceMs: Long,
    val reconnectWindowMs: Long,
    val profiles: List<DetectionProfile>
) {
    fun profile(packageName: String, versionName: String, deviceModel: String, sdkInt: Int): DetectionProfile? =
        profiles.singleOrNull {
            it.packageName == packageName && it.versionName == versionName &&
                it.deviceModel == deviceModel && it.sdkInt == sdkInt
        }

    companion object {
        private const val MAX_BYTES = 65_536
        private val PACKAGES = setOf("com.whatsapp", "com.whatsapp.w4b")
        private val TOKEN = Regex("[a-zA-Z0-9][a-zA-Z0-9._-]{0,79}")
        private val VERSION = Regex("[0-9]+(\\.[0-9]+){1,4}")
        private val CLASS = Regex("com\\.whatsapp\\.[a-zA-Z0-9_.$]{1,187}")
        private val CODE = Regex("[a-z0-9_]{1,80}")
        private val SCENARIOS = setOf(
            "outgoing_voice", "incoming_voice", "outgoing_video", "incoming_video",
            "group", "missed", "declined", "hold_reconnect"
        )

        fun load(input: InputStream): DetectionRules {
            val bytes = input.readNBytes(MAX_BYTES + 1)
            require(bytes.size <= MAX_BYTES) { "Detection rules exceed size limit" }
            val text = bytes.decodeToString(throwOnInvalidSequence = true)
            validateJson(text)
            val tokens = JSONTokener(text)
            val root = tokens.nextValue()
            require(root is JSONObject && tokens.nextClean() == '\u0000') { "Invalid detection rules document" }
            root.keysExactly("schemaVersion", "version", "packages", "connection", "endDebounceMs", "reconnectWindowMs", "profiles")
            require(root.integer("schemaVersion", 1..1) == 1)
            val version = root.text("version", TOKEN)
            val packages = root.strings("packages", 1..2) { it in PACKAGES }
            val connection = root.getJSONObject("connection")
            connection.keysExactly("category", "template", "ongoingCallType", "requireOngoing", "requireChronometer", "requireCommunicationMode")
            require(connection.opt("category") == "call" && connection.opt("template") == "CallStyle") {
                "Unsupported connection signature"
            }
            connection.integer("ongoingCallType", 2..2)
            for (key in listOf("requireOngoing", "requireChronometer", "requireCommunicationMode")) {
                require(connection.opt(key) == true) { "Unsafe connection requirement" }
            }
            val debounce = root.integer("endDebounceMs", 0..5000).toLong()
            val reconnect = root.integer("reconnectWindowMs", 30_000..30_000).toLong()
            val profileArray = root.getJSONArray("profiles")
            require(profileArray.length() in 1..16) { "Invalid detection profile count" }
            val profiles = (0 until profileArray.length()).map { parseProfile(profileArray.getJSONObject(it), packages) }
            require(profiles.distinctBy { listOf(it.packageName, it.versionName, it.deviceModel, it.sdkInt) }.size == profiles.size) {
                "Ambiguous detection profiles"
            }
            return DetectionRules(
                version, packages,
                ConnectionRules(connection.getString("category"), connection.getString("template"), connection.getInt("ongoingCallType")),
                debounce, reconnect, profiles
            )
        }

        private fun parseProfile(value: JSONObject, packages: Set<String>): DetectionProfile {
            value.keysExactly("packageName", "versionName", "callWindowClasses", "callerField", "evidence")
            val pkg = value.text("packageName", TOKEN)
            require(pkg in packages) { "Unsupported detection package" }
            val version = value.text("versionName", VERSION)
            require(version.length <= 80) { "Invalid detection profile version" }
            val classes = value.strings("callWindowClasses", 1..16) { CLASS.matches(it) && it.length <= 200 }
            require(value.opt("callerField") == "notification_title") { "Unsupported caller field" }
            val evidence = value.getJSONObject("evidence")
            evidence.keysExactly("deviceModel", "sdkInt", "capturedOn", "verifiedScenarios", "limitations")
            val capturedOn = evidence.text("capturedOn", Regex("[0-9]{4}-[0-9]{2}-[0-9]{2}"))
            return DetectionProfile(
                packageName = pkg,
                versionName = version,
                callWindowClasses = classes,
                deviceModel = evidence.text("deviceModel", TOKEN),
                sdkInt = evidence.integer("sdkInt", 33..100),
                capturedOn = LocalDate.parse(capturedOn),
                verifiedScenarios = evidence.strings("verifiedScenarios", 1..8) { it in SCENARIOS },
                limitations = evidence.strings("limitations", 0..16, CODE::matches)
            )
        }

        private fun validateJson(text: String) {
            JsonReader(text.reader()).use { reader ->
                val scopes = mutableListOf<MutableSet<String>?>()
                while (reader.peek() != JsonToken.END_DOCUMENT) {
                    when (reader.peek()) {
                        JsonToken.BEGIN_OBJECT, JsonToken.BEGIN_ARRAY -> {
                            require(scopes.size < 8) { "Detection rules nesting limit exceeded" }
                            if (reader.peek() == JsonToken.BEGIN_OBJECT) {
                                reader.beginObject()
                                scopes.add(mutableSetOf())
                            } else {
                                reader.beginArray()
                                scopes.add(null)
                            }
                        }
                        JsonToken.END_OBJECT -> { reader.endObject(); scopes.removeAt(scopes.lastIndex) }
                        JsonToken.END_ARRAY -> { reader.endArray(); scopes.removeAt(scopes.lastIndex) }
                        JsonToken.NAME -> require(scopes.last()!!.add(reader.nextName())) { "Duplicate detection rule field" }
                        JsonToken.STRING, JsonToken.NUMBER -> reader.nextString()
                        JsonToken.BOOLEAN -> reader.nextBoolean()
                        JsonToken.NULL -> reader.nextNull()
                        else -> error("Invalid detection rules token")
                    }
                }
            }
        }

        private fun JSONObject.keysExactly(vararg expected: String) {
            require(keys().asSequence().toSet() == expected.toSet()) { "Missing or unknown detection rule fields" }
        }

        private fun JSONObject.text(key: String, pattern: Regex): String {
            val value = opt(key)
            require(value is String && value.length <= 200 && pattern.matches(value)) { "Invalid detection rule string" }
            return value
        }

        private fun JSONObject.integer(key: String, range: IntRange): Int {
            val value = opt(key)
            require(value is Int && value in range) { "Invalid detection rule integer" }
            return value
        }

        private fun JSONObject.strings(key: String, count: IntRange, valid: (String) -> Boolean): Set<String> {
            val values = opt(key)
            require(values is JSONArray && values.length() in count) { "Invalid detection rule array" }
            val strings = (0 until values.length()).map {
                val value = values.opt(it)
                require(value is String && valid(value)) { "Invalid detection rule array item" }
                value
            }
            require(strings.distinct().size == strings.size) { "Duplicate detection rule array item" }
            return strings.toSet()
        }
    }
}
