package com.soakula.wacr.spike.log

import android.Manifest
import android.content.Context
import android.content.pm.PackageManager
import android.media.AudioDeviceInfo
import android.media.AudioManager
import android.media.MediaRecorder
import android.os.Build
import android.os.PowerManager
import com.soakula.wacr.spike.Access

/** Human-readable names for Android int constants, built by reflection so nothing is missed. */
object Names {
    private fun consts(cls: Class<*>, prefix: String): Map<Int, String> =
        cls.fields.filter { it.name.startsWith(prefix) && it.type == Int::class.javaPrimitiveType }
            .associate { it.getInt(null) to it.name.removePrefix(prefix) }

    private val deviceTypes by lazy { consts(AudioDeviceInfo::class.java, "TYPE_") }
    private val modes by lazy { consts(AudioManager::class.java, "MODE_") }
    private val sources by lazy { consts(MediaRecorder.AudioSource::class.java, "") }
    private val usages by lazy { consts(android.media.AudioAttributes::class.java, "USAGE_") }

    fun deviceType(t: Int) = deviceTypes[t] ?: "TYPE_$t"
    fun mode(m: Int) = modes[m] ?: "MODE_$m"
    fun source(s: Int) = sources[s] ?: "SOURCE_$s"
    fun usage(u: Int) = usages[u] ?: "USAGE_$u"
    fun device(d: AudioDeviceInfo): Map<String, Any?> =
        linkedMapOf("type" to deviceType(d.type), "id" to d.id, "product" to d.productName?.toString(), "sink" to d.isSink)
}

object DeviceInfo {
    fun collect(ctx: Context): Map<String, Any?> {
        val am = ctx.getSystemService(AudioManager::class.java)
        val pm = ctx.packageManager
        return linkedMapOf(
            "manufacturer" to Build.MANUFACTURER,
            "model" to Build.MODEL,
            "device" to Build.DEVICE,
            "sdkInt" to Build.VERSION.SDK_INT,
            "release" to Build.VERSION.RELEASE,
            "securityPatch" to Build.VERSION.SECURITY_PATCH,
            "buildDisplay" to Build.DISPLAY,
            "oneUi" to oneUi(),
            "spikeVersion" to pm.getPackageInfo(ctx.packageName, 0).versionName,
            "whatsapp" to pkg(pm, "com.whatsapp"),
            "whatsappBusiness" to pkg(pm, "com.whatsapp.w4b"),
            "permissions" to listOf(
                Manifest.permission.RECORD_AUDIO, Manifest.permission.POST_NOTIFICATIONS, Manifest.permission.READ_CONTACTS
            ).associate { it.substringAfterLast('.') to (ctx.checkSelfPermission(it) == PackageManager.PERMISSION_GRANTED) },
            "accessibilityEnabled" to Access.a11yEnabled(ctx),
            "notificationAccess" to Access.notificationAccess(ctx),
            "ignoringBatteryOptimizations" to
                ctx.getSystemService(PowerManager::class.java).isIgnoringBatteryOptimizations(ctx.packageName),
            "audioMode" to Names.mode(am.mode),
            "communicationDevice" to am.communicationDevice?.let(Names::device),
            "inputs" to am.getDevices(AudioManager.GET_DEVICES_INPUTS).map(Names::device),
            "outputs" to am.getDevices(AudioManager.GET_DEVICES_OUTPUTS).map(Names::device),
        )
    }

    private fun pkg(pm: PackageManager, name: String): Any =
        runCatching { pm.getPackageInfo(name, 0).let { mapOf("versionName" to it.versionName, "versionCode" to it.longVersionCode) } }
            .getOrElse { "not installed" }

    /** Samsung exposes One UI as Build.VERSION.SEM_PLATFORM_INT, e.g. 150100 -> 6.1. */
    private fun oneUi(): Any? = runCatching {
        val raw = Build.VERSION::class.java.getField("SEM_PLATFORM_INT").getInt(null)
        val x = raw - 90000
        mapOf("version" to "${x / 10000}.${(x % 10000) / 100}", "raw" to raw)
    }.getOrNull()
}
