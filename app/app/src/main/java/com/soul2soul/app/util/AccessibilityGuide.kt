package com.soul2soul.app.util

import android.app.Activity
import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.content.Intent
import android.os.Build
import android.provider.Settings
import androidx.appcompat.app.AlertDialog
import com.soul2soul.app.R

/**
 * 无障碍授权引导的纯逻辑（JVM 可单测）：决定跳到哪级设置页、用哪份路径文案。
 * 原则：能直达"最终授权界面"就绝不把用户丢进无障碍大列表自己摸。
 */
object AccessibilityGuide {
    /** Android 11+ 有逐应用无障碍详情页（直达本应用服务开关）；以下只有大列表 */
    const val SDK_DETAILS_PAGE = 30

    fun useDetailsPage(sdkInt: Int): Boolean = sdkInt >= SDK_DETAILS_PAGE

    /** 只能落到大列表时的路径指引文案（品牌不同入口不同）；返回 string 资源 id */
    fun pathResId(brandAndManufacturer: String): Int {
        val s = brandAndManufacturer.lowercase()
        return when {
            s.contains("xiaomi") || s.contains("redmi") || s.contains("poco") ->
                R.string.acc_guide_miui
            s.contains("huawei") || s.contains("honor") -> R.string.acc_guide_huawei
            else -> R.string.acc_guide_generic
        }
    }
}

/** 执行跳转的一侧（依赖 Intent/剪贴板，不进单测） */
object AccessibilityLauncher {

    fun open(activity: Activity) {
        if (AccessibilityGuide.useDetailsPage(Build.VERSION.SDK_INT)) {
            // 常量 API 30 才进 android.jar，用字面量兼容低 compileSdk 编译
            val ok = runCatching {
                activity.startActivity(
                    Intent("android.settings.ACCESSIBILITY_DETAILS_SETTINGS")
                        .putExtra(":package", activity.packageName)
                )
            }.isSuccess
            if (ok) return
        }
        // 老系统/ROM 不认详情页 intent：先给路径地图再进大列表，让用户进门就知道往哪走
        val path = activity.getString(
            AccessibilityGuide.pathResId("${Build.BRAND} ${Build.MANUFACTURER}")
        )
        AlertDialog.Builder(activity)
            .setTitle(R.string.acc_guide_title)
            .setMessage(path)
            .setPositiveButton(R.string.acc_guide_go) { _, _ ->
                runCatching {
                    activity.startActivity(Intent(Settings.ACTION_ACCESSIBILITY_SETTINGS))
                }
            }
            .setNeutralButton(R.string.acc_guide_copy) { _, _ ->
                val cm = activity.getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
                cm.setPrimaryClip(ClipData.newPlainText("path", path))
            }
            .show()
    }
}
