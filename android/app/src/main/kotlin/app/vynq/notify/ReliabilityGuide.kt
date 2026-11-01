package app.vynq.notify

import android.annotation.SuppressLint
import android.content.ActivityNotFoundException
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.net.Uri
import android.os.Build
import android.provider.Settings

/**
 * Phone makers that ship aggressive background limits (most phones sold in
 * China, and many elsewhere) need the user to allow vynq to start and run in
 * the background, or messages wait until the app is opened. This opens the
 * right system screen where we know it, and the app's settings page otherwise.
 * We only ever open the screens; the user decides.
 */
object ReliabilityGuide {
    enum class Maker(val label: String, val autostartName: String) {
        Xiaomi("Xiaomi / Redmi / POCO", "Autostart"),
        Oppo("OPPO / realme / OnePlus", "Auto launch"),
        Vivo("vivo / iQOO", "Background startup"),
        Huawei("Huawei / Honor", "App launch"),
        Other("", ""),
    }

    val maker: Maker = when (Build.MANUFACTURER.lowercase()) {
        "xiaomi", "redmi", "poco" -> Maker.Xiaomi
        "oppo", "realme", "oneplus" -> Maker.Oppo
        "vivo", "iqoo" -> Maker.Vivo
        "huawei", "honor" -> Maker.Huawei
        else -> Maker.Other
    }

    /** True where a separate autostart switch exists (and can't be read by apps). */
    val hasAutostart get() = maker != Maker.Other

    fun openAutostart(context: Context): Boolean {
        val candidates = when (maker) {
            Maker.Xiaomi -> listOf(component("com.miui.securitycenter", "com.miui.permcenter.autostart.AutoStartManagementActivity"))
            Maker.Oppo -> listOf(
                component("com.coloros.safecenter", "com.coloros.safecenter.startupapp.StartupAppListActivity"),
                component("com.coloros.safecenter", "com.coloros.safecenter.permission.startup.StartupAppListActivity"),
                component("com.oplus.safecenter", "com.oplus.safecenter.startupapp.StartupAppListActivity"),
                component("com.oppo.safe", "com.oppo.safe.permission.startup.StartupAppListActivity"),
            )
            Maker.Vivo -> listOf(
                component("com.vivo.permissionmanager", "com.vivo.permissionmanager.activity.BgStartUpManagerActivity"),
                component("com.iqoo.secure", "com.iqoo.secure.ui.phoneoptimize.BgStartUpManager"),
            )
            Maker.Huawei -> listOf(
                component("com.huawei.systemmanager", "com.huawei.systemmanager.startupmgr.ui.StartupNormalAppListActivity"),
                component("com.hihonor.systemmanager", "com.hihonor.systemmanager.startupmgr.ui.StartupNormalAppListActivity"),
            )
            Maker.Other -> emptyList()
        }
        return candidates.any { start(context, it) } || openAppDetails(context)
    }

    /** Xiaomi's per-app battery saver ("No restrictions"), else the standard request. */
    fun openBatterySettings(context: Context): Boolean {
        if (maker == Maker.Xiaomi) {
            val miui = Intent("miui.intent.action.HIDDENAPPS_CONFIG_ACTIVITY")
                .putExtra("package_name", context.packageName)
                .putExtra("package_label", "vynq")
            if (start(context, miui)) return true
        }
        return requestIgnoreBatteryOptimizations(context) || openAppDetails(context)
    }

    @SuppressLint("BatteryLife") // a messaging app without Google push is the documented exception
    fun requestIgnoreBatteryOptimizations(context: Context): Boolean =
        start(context, Intent(Settings.ACTION_REQUEST_IGNORE_BATTERY_OPTIMIZATIONS, Uri.parse("package:${context.packageName}")))

    /** Xiaomi: lock-screen display and pop-up permissions live on a separate screen. */
    fun openXiaomiOtherPermissions(context: Context): Boolean =
        start(context, Intent("miui.intent.action.APP_PERM_EDITOR")
            .setClassName("com.miui.securitycenter", "com.miui.permcenter.permissions.PermissionsEditorActivity")
            .putExtra("extra_pkgname", context.packageName)) || openAppDetails(context)

    fun openNotificationSettings(context: Context): Boolean =
        start(context, Intent(Settings.ACTION_APP_NOTIFICATION_SETTINGS).putExtra(Settings.EXTRA_APP_PACKAGE, context.packageName))
            || openAppDetails(context)

    fun openAppDetails(context: Context): Boolean =
        start(context, Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS, Uri.parse("package:${context.packageName}")))

    private fun component(pkg: String, cls: String) = Intent().setComponent(ComponentName(pkg, cls))

    private fun start(context: Context, intent: Intent): Boolean = try {
        context.startActivity(intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
        true
    } catch (e: ActivityNotFoundException) {
        false
    } catch (e: SecurityException) {
        false
    }
}
