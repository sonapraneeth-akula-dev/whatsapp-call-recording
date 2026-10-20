package com.soakula.wacr.capability

import androidx.room.withTransaction
import com.soakula.wacr.data.AppDatabase
import com.soakula.wacr.data.AppPreferences
import com.soakula.wacr.data.CapabilityStatus
import com.soakula.wacr.logging.Json
import com.soakula.wacr.recording.CaptureProfile
import com.soakula.wacr.recording.CaptureSource

class CapabilityProfiles(private val database: AppDatabase, private val preferences: AppPreferences) {
    suspend fun load(device: CapabilityDevice): CaptureProfile {
        val latest = database.capabilities().latest(device.model, device.osBuild, device.waPackage,
            device.waVersion, device.waVersionCode)
        val confirmed = latest.filter {
            it.status == CapabilityStatus.COMPLETE && it.ownOk == true && it.remoteOk == true && it.clientSilenced != true
        }
        val profile = CaptureProfile(routeSources = confirmed.groupBy { it.route }.mapValues { (_, rows) ->
            rows.map { it.source }.sortedBy { CaptureSource.entries.indexOf(it) }
        })
        // SQLCipher remains authoritative; this technical cache is never trusted over fresh verdicts.
        preferences.cacheCaptureProfile(Json.encode(mapOf(
            "schema_version" to 1, "device_model" to device.model, "os_build" to device.osBuild,
            "wa_package" to device.waPackage, "wa_version" to device.waVersion,
            "wa_version_code" to device.waVersionCode, "result_ids" to confirmed.map { it.id },
            "routes" to profile.routeSources.mapKeys { it.key.name }.mapValues { entry -> entry.value.map { it.name } }
        )))
        return profile
    }

    suspend fun review(id: Long, currentDevice: CapabilityDevice, own: Boolean, remote: Boolean) {
        require(id > 0) { "Capability result ID must be positive" }
        database.withTransaction {
            val result = checkNotNull(database.capabilities().result(id)) { "Capability result no longer exists" }
            check(result.device == currentDevice) { "Device or WhatsApp build changed; retest before reviewing" }
            check(result.clientSilenced != true || (!own && !remote)) { "A silenced source cannot acquire a positive verdict" }
            check(database.capabilities().review(id, own, remote) == 1) { "Complete playable test clip is required" }
        }
        load(currentDevice)
    }
}
