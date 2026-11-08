package com.replay.recoder.ui

import android.content.Context
import android.content.Intent
import android.net.Uri
import android.os.Build
import android.os.PowerManager
import android.provider.Settings
import android.util.Log

/**
 * 厂商 Intent 跳转表:后台保活/权限设置页。
 *
 * ⚠️ 厂商私有 Intent 随 OS 版本变化且无文档,每个 Intent 必须先
 * packageManager.resolveActivity() 检查能否解析,不能解析时降级为通用设置页。
 * 严禁直接 startActivity——在未覆盖厂商上会崩溃。
 */
object VendorIntents {

    private const val TAG = "VendorIntents"

    /** 当前设备品牌小写(HUAWEI/XIAOMI/OPPO/VIVO/HONOR/SAMSUNG/MEIZU/ONEPLUS/REALME/IQOO ...) */
    private val brand: String get() = Build.BRAND?.lowercase() ?: ""

    /** 当前设备制造商小写 */
    private val manufacturer: String get() = Build.MANUFACTURER?.lowercase() ?: ""

    /** 识别厂商:返回人类可读名称,未识别返回 Build.MANUFACTURER */
    fun friendlyBrand(): String = when {
        brand.contains("huawei") || brand.contains("honor") -> "鸿蒙/荣耀"
        brand.contains("xiaomi") || brand.contains("redmi") -> "小米/Redmi"
        brand.contains("oppo") -> "OPPO"
        brand.contains("realme") -> "Realme"
        brand.contains("vivo") || brand.contains("iqoo") -> "vivo/iQOO"
        brand.contains("samsung") -> "三星"
        brand.contains("oneplus") -> "一加"
        brand.contains("meizu") -> "魅族"
        else -> Build.MANUFACTURER
    }

    /** 是否已加电池优化白名单。全厂商通用,Android 6.0+。
     *  返回 true=未加白(系统可能杀后台);false=已加白。 */
    fun isBatteryOptimized(ctx: Context): Boolean {
        return try {
            val pm = ctx.getSystemService(Context.POWER_SERVICE) as? PowerManager
                ?: return true
            !pm.isIgnoringBatteryOptimizations(ctx.packageName)
        } catch (_: Throwable) { true }
    }

    /** 弹系统请求加电池优化白名单——全厂商通用,无需厂商 Intent。
     *  用户确认后从 Settings 返回,不需要 onActivityResult 回调(系统自动加白)。 */
    fun requestBatteryOptimization(ctx: Context) {
        try {
            val intent = Intent(Settings.ACTION_REQUEST_IGNORE_BATTERY_OPTIMIZATIONS).apply {
                data = Uri.parse("package:${ctx.packageName}")
                addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            }
            if (canResolve(ctx, intent)) {
                ctx.startActivity(intent)
                return
            }
        } catch (t: Throwable) {
            Log.w(TAG, "requestBatteryOptimization 异常", t)
        }
        // 降级:跳到应用详情页让用户手动加白
        openAppDetails(ctx)
    }

    /** 无悬浮窗权限时跳到系统悬浮窗设置页(优先厂商页,降级通用页)。 */
    fun jumpOverlaySettings(ctx: Context) {
        // 厂商专用页优先级更高
        val vendor = when {
            brand.contains("xiaomi") || brand.contains("redmi") -> Intent("miui.intent.action.APP_PERM_EDIT").apply {
                setClassName("com.miui.securitycenter", "com.miui.permcenter.permissions.AppPermissionsEditorActivity")
                putExtra("extra_pkgname", ctx.packageName)
            }
            brand.contains("huawei") || brand.contains("honor") -> Intent("huawei.intent.action.APP_SETTINGS").apply {
                setClassName("com.huawei.systemmanager", "com.huawei.systemmanager.optimize.process.ProtectActivity")
            }
            else -> null
        }
        if (vendor != null && canResolve(ctx, vendor)) { ctx.startActivity(vendor); return }
        // 通用页(Android 6.0+)
        val general = Intent(Settings.ACTION_MANAGE_OVERLAY_PERMISSION, Uri.parse("package:${ctx.packageName}"))
        if (canResolve(ctx, general)) ctx.startActivity(general) else openAppDetails(ctx)
    }

    /** 跳到通知设置页。 */
    fun jumpNotificationSettings(ctx: Context) {
        val intent = Intent(Settings.ACTION_APP_NOTIFICATION_SETTINGS).apply {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                putExtra(Settings.EXTRA_APP_PACKAGE, ctx.packageName)
            } else {
                putExtra("app_package", ctx.packageName)
                putExtra("app_uid", ctx.applicationInfo.uid)
            }
            addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        }
        if (canResolve(ctx, intent)) ctx.startActivity(intent) else openAppDetails(ctx)
    }

    /** 跳到本应用详情设置页——所有厂商都能解析的兜底入口。 */
    fun openAppDetails(ctx: Context) {
        val intent = Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS).apply {
            data = Uri.parse("package:${ctx.packageName}")
            addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        }
        runCatching { ctx.startActivity(intent) }
    }

    /** 尝试跳到厂商自启动/后台活动设置页。找不到则降级 openAppDetails。
     *  此方法 best-effort,不保证所有厂商都有专用页。 */
    fun jumpAutoStartSettings(ctx: Context) {
        val vendors = listOfNotNull(
            // 小米自启动
            if (brand.contains("xiaomi") || brand.contains("redmi")) Intent().apply {
                setClassName("com.miui.securitycenter",
                    "com.miui.permcenter.permissions.PermissionsEditorActivity")
                putExtra("extra_pkgname", ctx.packageName)
            } else null,
            // OPPO/Realme
            if (brand.contains("oppo") || brand.contains("realme")) Intent().apply {
                setClassName("com.coloros.safecenter",
                    "com.coloros.safecenter.startupapp.StartupAppListActivity")
            } else null,
            // vivo/iQOO
            if (brand.contains("vivo") || brand.contains("iqoo")) Intent().apply {
                setClassName("com.vivo.permissionmanager",
                    "com.vivo.permissionmanager.activity.BgStartUpManagerActivity")
            } else null,
            // 华为/荣耀
            if (brand.contains("huawei") || brand.contains("honor")) Intent().apply {
                setClassName("com.huawei.systemmanager",
                    "com.huawei.systemmanager.optimize.process.ProtectActivity")
            } else null,
            // 三星
            if (brand.contains("samsung")) Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS).apply {
                data = Uri.parse("package:${ctx.packageName}")
            } else null,
            // 通用应用详情(兜底)
            Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS).apply {
                data = Uri.parse("package:${ctx.packageName}")
            }
        )
        for (intent in vendors) {
            if (canResolve(ctx, intent)) {
                runCatching { ctx.startActivity(intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)) }
                return
            }
        }
        openAppDetails(ctx)
    }

    private fun canResolve(ctx: Context, intent: Intent): Boolean = runCatching {
        intent.resolveActivity(ctx.packageManager) != null
    }.getOrDefault(false)
}
