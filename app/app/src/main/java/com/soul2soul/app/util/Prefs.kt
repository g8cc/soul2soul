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

    /** 电池优化豁免只引导一次（国产 ROM 后台杀的通用逃生门） */
    fun askedBattery(ctx: Context): Boolean = sp(ctx).getBoolean("askedBattery", false)

    fun setAskedBattery(ctx: Context, v: Boolean) = sp(ctx).edit().putBoolean("askedBattery", v).apply()

    /** 配对令牌：服务端为这对设备签发的身份凭证，hello 必须携带 */
    fun pairToken(ctx: Context): String? = sp(ctx).getString("pairToken", null)

    fun setPairToken(ctx: Context, v: String?) = sp(ctx).edit().putString("pairToken", v).apply()

    fun romGuideShown(ctx: Context): Boolean = sp(ctx).getBoolean("romGuideShown", false)

    fun setRomGuideShown(ctx: Context, v: Boolean) = sp(ctx).edit().putBoolean("romGuideShown", v).apply()

    /** 无障碍服务历史上开启过：更新安装后国产 ROM 常把它悄悄关掉，
     *  靠这个标记检测"从有到无"，提示一键重开（无法替用户保留授权，但别让用户自己发现） */
    fun accWasEnabled(ctx: Context): Boolean = sp(ctx).getBoolean("accWasEnabled", false)

    fun setAccWasEnabled(ctx: Context, v: Boolean) = sp(ctx).edit().putBoolean("accWasEnabled", v).apply()

    /** 上次作为观看端收到的远端视频尺寸：新会话开场先按缓存套等比布局，
     *  消除首帧尺寸事件到达前 match_parent 被合成层铺满拉伸的裁切闪跳 */
    fun lastVideoSize(ctx: Context): Pair<Int, Int>? {
        val sp = sp(ctx)
        val w = sp.getInt("lastVideoW", 0)
        val h = sp.getInt("lastVideoH", 0)
        return if (w > 0 && h > 0) Pair(w, h) else null
    }

    fun setLastVideoSize(ctx: Context, w: Int, h: Int) =
        sp(ctx).edit().putInt("lastVideoW", w).putInt("lastVideoH", h).apply()
}
