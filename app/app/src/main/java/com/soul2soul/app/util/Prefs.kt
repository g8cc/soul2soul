package com.soul2soul.app.util

import android.content.Context
import java.util.UUID

object Prefs {
    private const val FILE = "soul2soul"

    private fun sp(ctx: Context) = ctx.getSharedPreferences(FILE, Context.MODE_PRIVATE)

    /** 设备唯一标识：首次启动生成，配对关系绑定在它上面 */
    fun deviceId(ctx: Context): String {
        val sp = sp(ctx)
        return sp.getString("deviceId", null) ?: UUID.randomUUID().toString().also {
            sp.edit().putString("deviceId", it).apply()
        }
    }

    fun paired(ctx: Context): Boolean = sp(ctx).getBoolean("paired", false)

    fun setPaired(ctx: Context, v: Boolean) = sp(ctx).edit().putBoolean("paired", v).apply()

    /** 配对令牌：服务端为这对设备签发的身份凭证，hello 必须携带 */
    fun pairToken(ctx: Context): String? = sp(ctx).getString("pairToken", null)

    fun setPairToken(ctx: Context, v: String?) = sp(ctx).edit().putString("pairToken", v).apply()

    fun romGuideShown(ctx: Context): Boolean = sp(ctx).getBoolean("romGuideShown", false)

    fun setRomGuideShown(ctx: Context, v: Boolean) = sp(ctx).edit().putBoolean("romGuideShown", v).apply()
}
