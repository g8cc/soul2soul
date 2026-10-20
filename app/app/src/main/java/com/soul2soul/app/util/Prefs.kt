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

    /** 电池优化豁免引导时间戳：旧版"只问一次"拒绝后永不再问=缺口，改为按时间节流可再引导 */
    fun batteryPromptAt(ctx: Context): Long = sp(ctx).getLong("batteryPromptAt", 0L)

    fun setBatteryPromptAt(ctx: Context, v: Long) = sp(ctx).edit().putLong("batteryPromptAt", v).apply()

    /** 配对令牌：服务端为这对设备签发的身份凭证，hello 必须携带 */
    fun pairToken(ctx: Context): String? = sp(ctx).getString("pairToken", null)

    fun setPairToken(ctx: Context, v: String?) = sp(ctx).edit().putString("pairToken", v).apply()

    fun romGuideShown(ctx: Context): Boolean = sp(ctx).getBoolean("romGuideShown", false)

    fun setRomGuideShown(ctx: Context, v: Boolean) = sp(ctx).edit().putBoolean("romGuideShown", v).apply()

    /** 无障碍服务历史上开启过：更新安装后国产 ROM 常把它悄悄关掉，
     *  靠这个标记检测"从有到无"，提示一键重开（无法替用户保留授权，但别让用户自己发现） */
    fun accWasEnabled(ctx: Context): Boolean = sp(ctx).getBoolean("accWasEnabled", false)

    fun setAccWasEnabled(ctx: Context, v: Boolean) = sp(ctx).edit().putBoolean("accWasEnabled", v).apply()

    /** 麦克风历史上授权过：覆盖安装后国产 ROM 常把运行时权限重置回"询问"
     *  （无障碍却不受影响），靠这个标记检测"从有到无"，先弹人话解释再走系统弹窗 */
    fun micWasGranted(ctx: Context): Boolean = sp(ctx).getBoolean("micWasGranted", false)

    fun setMicWasGranted(ctx: Context, v: Boolean) = sp(ctx).edit().putBoolean("micWasGranted", v).apply()

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

    /**
     * 共享端入口悬浮球的拖动位（像素，TOP|START 原点）。
     * 0.2.65 起球闲置即隐形（不再"淡化到 15%"，因为那 15% 会被采集进视频=第二个铅笔），
     * 命中区还在但看不见——位置若每次回到默认右边中段，用户只能靠猜。
     * 返回 null＝从没拖过。
     */
    fun ballSpot(ctx: Context): Pair<Int, Int>? {
        val sp = sp(ctx)
        val x = sp.getInt("ballX", -1)
        val y = sp.getInt("ballY", -1)
        return if (x >= 0 && y >= 0) Pair(x, y) else null
    }

    fun setBallSpot(ctx: Context, x: Int, y: Int) =
        sp(ctx).edit().putInt("ballX", x).putInt("ballY", y).apply()

    /** 隐形命中区的人话提示只说一次（第一次共享时）；之后每次共享都打扰就成噪声 */
    fun overlayEntryHintShown(ctx: Context): Boolean = sp(ctx).getBoolean("overlayEntryHintShown", false)

    fun setOverlayEntryHintShown(ctx: Context, v: Boolean) =
        sp(ctx).edit().putBoolean("overlayEntryHintShown", v).apply()
}
