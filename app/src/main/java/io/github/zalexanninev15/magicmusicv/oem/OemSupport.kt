package io.github.zalexanninev15.magicmusicv.oem

import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.net.Uri
import android.os.Build
import android.os.PowerManager
import android.provider.Settings

enum class Vendor { ONEPLUS, REALME, OPPO, OTHER }

/**
* Everything that exists only because ColorOS / OxygenOS / realme UI do not behave like AOSP.
* On the OPLUS family, a foreground service is not a guarantee. The system's battery layer freezes apps that are not on the auto-start whitelist once the screen goes off. This happens without any ANR, log line, or callback — the audio thread simply stops being scheduled, and taps stop. There is no API to detect this, so the only honest thing the app can do is send the user to the two screens that turn it off.
*/
object OemSupport {

    val vendor: Vendor = when (Build.MANUFACTURER.lowercase()) {
        "oneplus" -> Vendor.ONEPLUS
        "realme" -> Vendor.REALME
        "oppo" -> Vendor.OPPO
        else -> when {
            Build.BRAND.equals("realme", true) -> Vendor.REALME
            Build.BRAND.equals("oneplus", true) -> Vendor.ONEPLUS
            else -> Vendor.OTHER
        }
    }

    // The build strings on a rooted phone are not reliable. A device can report itself as a Galaxy while running OxygenOS. However, the presence of the OPLUS framework classes cannot be faked by editing the build.prop file. So, it is checked alongside the manufacturer.
    private val oplusFrameworkPresent: Boolean =
        listOf(
            "com.oplus.os.LinearmotorVibrator",
            "com.oplus.os.WaveformEffect",
            "com.oplus.content.OplusFeatureConfigManager",
        ).any { runCatching { Class.forName(it) }.isSuccess }

    /** OnePlus, realme and OPPO all ship the same OPLUS battery stack. */
    val isOplus: Boolean = vendor != Vendor.OTHER || oplusFrameworkPresent

    val deviceLabel: String = buildString {
        append("${Build.MANUFACTURER} ${Build.MODEL}")
        // Spoofing modules can alter the model name, but they rarely change the fingerprint. Therefore, it is important to display both the model name and fingerprint in cases where they differ. Otherwise, the diagnostic report might incorrectly identify the phone.
        val fpModel = Build.FINGERPRINT.substringAfter('/', "").substringBefore('/')
        if (fpModel.isNotEmpty() && !fpModel.equals(Build.MODEL, true)) append(" ($fpModel)")
    }

    fun isBatteryUnrestricted(context: Context): Boolean =
        context.getSystemService(PowerManager::class.java)
            .isIgnoringBatteryOptimizations(context.packageName)

    fun requestBatteryUnrestricted(context: Context): Boolean {
        val intent = Intent(Settings.ACTION_REQUEST_IGNORE_BATTERY_OPTIMIZATIONS)
            .setData(Uri.parse("package:${context.packageName}"))
            .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        return launch(context, intent)
    }

    /**
     * Opens the ColorOS / OxygenOS / realme UI auto-start list. The component moved with almost every
     * ColorOS release and there is no stable action for it, so this walks a list of the
     * known ones oldest-last and falls back to the app info page.
     */
    fun openAutoStartSettings(context: Context): Boolean {
        for (component in AUTOSTART_COMPONENTS) {
            val intent = Intent()
                .setComponent(component)
                .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            if (launch(context, intent)) return true
        }
        val fallback = Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS)
            .setData(Uri.parse("package:${context.packageName}"))
            .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        return launch(context, fallback)
    }

    private fun launch(context: Context, intent: Intent): Boolean =
        runCatching { context.startActivity(intent); true }.getOrDefault(false)

    private val AUTOSTART_COMPONENTS = listOf(
        // ColorOS 13+ / OxygenOS 13+ / realme UI 4+
        ComponentName("com.oplus.battery", "com.oplus.powermanager.fuelgaue.PowerConsumptionActivity"),
        ComponentName("com.oplus.safecenter", "com.oplus.safecenter.startupapp.StartupAppListActivity"),
        // ColorOS 7-12
        ComponentName("com.coloros.safecenter", "com.coloros.safecenter.startupapp.StartupAppListActivity"),
        ComponentName("com.coloros.safecenter", "com.coloros.safecenter.permission.startup.StartupAppListActivity"),
        ComponentName("com.coloros.oppoguardelf", "com.coloros.powermanager.fuelgaue.PowerUsageModelActivity"),
        // Older OPPO
        ComponentName("com.oppo.safe", "com.oppo.safe.permission.startup.StartupAppListActivity"),
    )
}
