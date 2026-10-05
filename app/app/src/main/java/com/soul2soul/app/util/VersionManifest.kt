package com.soul2soul.app.util

import org.json.JSONObject

/**
 * 版本清单解析 + 更新判定的纯逻辑（无 Android 依赖，可单测）。
 * 解析规则与 Updater.check 原有行为逐字一致：任何异常/非 JSON 一律视为"无信息"。
 */
object VersionManifest {

    /** 清单 JSON → Info；解析失败返回 null（静默，不打扰用户） */
    fun parse(raw: String): Updater.Info? = runCatching {
        val json = JSONObject(raw)
        Updater.Info(
            versionCode = json.optInt("versionCode", 0),
            versionName = json.optString("versionName", ""),
            url = json.optString("url", ""),
        )
    }.getOrNull()

    /**
     * 是否有新版本。判定必须用运行时真实 local（Updater.localVersionCode），
     * 绝不能用 BuildConfig.VERSION_CODE——Kotlin 内联 static-final 常量曾把
     * hasUpdate 焊死成 `remote > 8`（0.2.12 线上事故）。versionCode 缺失=0 同样不触发。
     */
    fun hasUpdate(local: Int, info: Updater.Info?): Boolean =
        info != null && info.versionCode > local
}
