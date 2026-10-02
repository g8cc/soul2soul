package com.soul2soul.app.session

import android.content.Context
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.PointF
import android.graphics.RectF
import android.os.SystemClock
import android.util.AttributeSet
import android.view.View
import org.json.JSONObject
import kotlin.math.cos
import kotlin.math.min
import kotlin.math.sin

/** 笔迹颜色（观看端/共享端共用） */
object StrokeColors {
    val COLORS = intArrayOf(
        Color.parseColor("#FF3B5C"), // 粉红
        Color.parseColor("#34C759"), // 绿
        Color.parseColor("#1E90FF"), // 蓝
    )
    const val FADE_MS = 2000L
}

/**
 * 悬浮标注/特效层。该窗口 FLAG_NOT_TOUCHABLE，不干扰本端任何操作；
 * 内容会被屏幕采集进视频流，发送方由此看到自己的笔迹/特效在对方屏幕上生效。
 *
 * 坐标系约定：所有内容以归一化屏幕坐标（0..1）存储，绘制时经视口几何
 * （geoLeft/geoTop/geoScreenW/geoScreenH）换算到视图像素。这样同一份内容
 * 既能在全屏窗口里渲染，也能被 AnnotationOverlayService 收缩进笔迹包围盒
 * 的小窗口里渲染（MIUI 全屏悬浮窗挡触摸的规避手段）。
 */
class OverlayCanvasView @JvmOverloads constructor(
    context: Context,
    attrs: AttributeSet? = null,
) : View(context, attrs) {

    private class Stroke(
        val id: String,
        val color: Int,
        val bornAt: Long,
    ) {
        val points = mutableListOf<PointF>() // 归一化坐标 0..1

        /** 收笔时刻（0 = 还在书写中）。淡出从收笔才开始计——网络延迟不影响显示 */
        var endAt = 0L

        /** 最后一个笔点的到达时刻：收笔信号丢失时的兜底依据 */
        var lastPointAt = 0L
    }

    private class Emoji(
        val char: String,
        val xN: Float,
        val bornAt: Long,
    )

    private class Particle(
        val x0: Float, val y0: Float,
        val vx: Float, val vy: Float,
        val color: Int,
        val char: String?,
    )

    private class Fx(
        val type: String,
        val bornAt: Long,
        val parts: List<Particle>,
    )

    private val strokes = mutableListOf<Stroke>()
    private val emojis = mutableListOf<Emoji>()
    private val fx = mutableListOf<Fx>()
    private val paint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.STROKE
        strokeCap = Paint.Cap.ROUND
        strokeJoin = Paint.Join.ROUND
    }
    private val fillPaint = Paint(Paint.ANTI_ALIAS_FLAG)
    private val emojiPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        textAlign = Paint.Align.CENTER
    }

    /** 视口几何：本视图可能是屏幕的一个子矩形窗口（bbox 模式），默认全屏 */
    private var geoLeft = 0f
    private var geoTop = 0f
    private var geoScreenW = 0f
    private var geoScreenH = 0f

    fun setViewport(left: Int, top: Int, screenW: Int, screenH: Int) {
        geoLeft = left.toFloat()
        geoTop = top.toFloat()
        geoScreenW = screenW.toFloat()
        geoScreenH = screenH.toFloat()
        invalidate()
    }

    private fun sx(xn: Float): Float =
        xn * (if (geoScreenW > 0f) geoScreenW else width.toFloat()) - geoLeft

    private fun sy(yn: Float): Float =
        yn * (if (geoScreenH > 0f) geoScreenH else height.toFloat()) - geoTop

    private fun screenW(): Float =
        if (geoScreenW > 0f) geoScreenW else width.toFloat()

    private fun screenH(): Float =
        if (geoScreenH > 0f) geoScreenH else height.toFloat()

    fun applyStroke(json: JSONObject) {
        val id = json.optString("id")
        when (json.optString("k")) {
            "clear" -> {
                strokes.clear()
                emojis.clear()
                fx.clear()
            }
            "s" -> {
                // 幂等：同 id 重复落笔（极端重发场景）不产生双笔画
                if (strokes.none { it.id == id }) strokes.add(
                    Stroke(
                        id,
                        StrokeColors.COLORS[json.optInt("c", 0).mod(StrokeColors.COLORS.size)],
                        SystemClock.uptimeMillis(),
                    )
                )
            }
            "p" -> {
                // 自愈：容忍丢包通道丢了落笔"s"时，凭笔点现场建笔，整笔不再凭空消失
                val s = strokes.lastOrNull { it.id == id }
                    ?: Stroke(id, StrokeColors.COLORS[0], SystemClock.uptimeMillis()).also { strokes.add(it) }
                s.points.add(
                    PointF(
                        json.optDouble("x", 0.5).toFloat().coerceIn(0f, 1f),
                        json.optDouble("y", 0.5).toFloat().coerceIn(0f, 1f),
                    )
                )
                s.lastPointAt = SystemClock.uptimeMillis()
            }
            "e" -> strokes.lastOrNull { it.id == id }?.let { if (it.endAt == 0L) it.endAt = SystemClock.uptimeMillis() }
        }
        invalidate()
    }

    fun applyEmoji(json: JSONObject) {
        val e = json.optString("e")
        if (e.isEmpty()) return
        emojis.add(Emoji(e, json.optDouble("x", 0.5).toFloat(), SystemClock.uptimeMillis()))
        invalidate()
    }

    /** 满屏互动特效：bomb 爆炸 / gift 礼物雨 / rocket 火箭 */
    fun applyFx(json: JSONObject) {
        val now = SystemClock.uptimeMillis()
        when (json.optString("t")) {
            "gift" -> {
                val chars = listOf("🎁", "❤️", "✨", "🎉", "🌹", "💖")
                repeat(12) { i ->
                    emojis.add(
                        Emoji(
                            chars.random(),
                            0.1f + Math.random().toFloat() * 0.8f,
                            now + i * 70L,
                        )
                    )
                }
            }
            "rocket" -> fx.add(Fx("rocket", now, emptyList()))
            else -> { // bomb
                val cx = 0.5f
                val cy = 0.42f
                val colors = intArrayOf(
                    Color.parseColor("#FFD24A"),
                    Color.parseColor("#FF6B35"),
                    Color.parseColor("#FF3B5C"),
                    Color.WHITE,
                )
                val parts = (0 until 26).map { i ->
                    val a = (i / 26f) * 2.0 * Math.PI + Math.random() * 0.25
                    val sp = 0.35 + Math.random() * 0.55
                    Particle(
                        cx, cy,
                        (cos(a) * sp).toFloat(), (sin(a) * sp).toFloat(),
                        colors[i % colors.size],
                        if (i % 6 == 0) "💥" else null,
                    )
                }
                fx.add(Fx("bomb", now, parts))
            }
        }
        invalidate()
    }

    /** 笔迹的归一化包围盒（无笔迹返回 null），服务据此收缩悬浮窗尺寸 */
    fun strokesBboxN(): RectF? {
        var l = 2f; var t = 2f; var r = -1f; var b = -1f
        for (s in strokes) for (p in s.points) {
            if (p.x < l) l = p.x
            if (p.x > r) r = p.x
            if (p.y < t) t = p.y
            if (p.y > b) b = p.y
        }
        if (r < 0f) return null
        return RectF(l, t, r, b)
    }

    fun hasEmojiOrFx(): Boolean = emojis.isNotEmpty() || fx.isNotEmpty()

    fun hasVisibleContent(): Boolean =
        strokes.any { it.points.isNotEmpty() } || emojis.isNotEmpty() || fx.isNotEmpty()

    override fun onDraw(canvas: Canvas) {
        super.onDraw(canvas)
        val now = SystemClock.uptimeMillis()
        val sw = screenW()
        val sh = screenH()
        val strokeW = 6f * resources.displayMetrics.density

        // 爆炸震屏：内容整体抖动，营造冲击感
        val bomb = fx.firstOrNull { it.type == "bomb" && now - it.bornAt < 600L }
        val saveCount = canvas.save()
        if (bomb != null) {
            val age = (now - bomb.bornAt).toFloat() / 600f
            val amp = 14f * resources.displayMetrics.density * (1f - age)
            canvas.translate(
                sin(age * 42f) * amp,
                cos(age * 35f) * amp * 0.7f,
            )
        }

        // 笔迹生命周期：
        //  1) 书写中（未收笔）→ 完整显示，永不消失
        //  2) 收笔后 → 2 秒淡出
        //  3) 收笔信号丢失（容忍丢包通道会丢小消息）→ 以"最后笔点后 1.2 秒无新点"判定收笔，兜底淡出
        val iter = strokes.iterator()
        while (iter.hasNext()) {
            val s = iter.next()
            // 病态笔画自愈：只收到"s"而笔点/收笔全被丢包 → 到龄直接清除，
            // 否则窗口会被一笔"隐形"内容永久钉住（全屏玻璃罩回归）
            if (s.points.isEmpty()) {
                if (now - s.bornAt > STROKE_SILENCE_MS) iter.remove()
                continue
            }
            val fadeStart = when {
                s.endAt > 0L -> s.endAt
                s.lastPointAt > 0L && now - s.lastPointAt > STROKE_SILENCE_MS -> s.lastPointAt + STROKE_SILENCE_MS
                else -> 0L // 仍在书写中：完整显示
            }
            if (fadeStart == 0L) {
                paintStroke(canvas, s, 1f, strokeW)
                continue
            }
            val sinceEnd = now - fadeStart
            if (sinceEnd > StrokeColors.FADE_MS) {
                iter.remove()
                continue
            }
            paintStroke(canvas, s, 1f - sinceEnd.toFloat() / StrokeColors.FADE_MS, strokeW)
        }

        // 表情：从下往上飘 + 放大 + 淡出（1.6 秒生命周期）
        val emojiIter = emojis.iterator()
        while (emojiIter.hasNext()) {
            val e = emojiIter.next()
            val age = now - e.bornAt
            if (age < 0L) continue // 礼物雨错峰出生
            if (age > EMOJI_LIFE_MS) {
                emojiIter.remove()
                continue
            }
            val t = age.toFloat() / EMOJI_LIFE_MS
            val size = resources.displayMetrics.density * (56f + 40f * t)
            emojiPaint.textSize = size
            emojiPaint.alpha = (255f * (1f - t * t)).toInt().coerceIn(0, 255)
            canvas.drawText(e.char, sx(e.xN), sh * 0.78f - t * sh * 0.38f - geoTop, emojiPaint)
        }

        // 特效
        val fxIter = fx.iterator()
        while (fxIter.hasNext()) {
            val f = fxIter.next()
            val age = now - f.bornAt
            val life = if (f.type == "rocket") ROCKET_LIFE_MS else BOMB_LIFE_MS
            if (age > life) {
                fxIter.remove()
                continue
            }
            val t = age.toFloat() / life
            if (f.type == "rocket") {
                val x = sx(0.12f + 0.76f * t)
                val y = sh * (0.88f - 0.78f * t) - geoTop
                emojiPaint.textSize = resources.displayMetrics.density * 72f
                // 尾迹：三个渐隐的残影
                for (g in 3 downTo 1) {
                    emojiPaint.alpha = (60f / g).toInt()
                    canvas.drawText(
                        "🚀",
                        sx(0.12f + 0.76f * (t - 0.04f * g)),
                        sh * (0.88f - 0.78f * (t - 0.04f * g)) - geoTop,
                        emojiPaint,
                    )
                }
                emojiPaint.alpha = 255
                canvas.drawText("🚀", x, y, emojiPaint)
            } else {
                // 冲击波环
                paint.color = Color.parseColor("#FFD24A")
                paint.alpha = (255f * (1f - t)).toInt().coerceIn(0, 255)
                paint.strokeWidth = 18f * resources.displayMetrics.density * (1f - t) + 2f
                val cx = sx(0.5f)
                val cy = sh * 0.42f - geoTop
                canvas.drawCircle(cx, cy, t * min(sw, sh) * 0.7f, paint)
                // 径向粒子（含少量 💥）
                val tt = age / 1000f
                for (p in f.parts) {
                    val px = sx(p.x0 + p.vx * tt)
                    val py = sh * (p.y0 + p.vy * tt + 1.4f * tt * tt) - geoTop
                    fillPaint.alpha = (255f * (1f - t)).toInt().coerceIn(0, 255)
                    if (p.char != null) {
                        emojiPaint.textSize = resources.displayMetrics.density * 32f
                        emojiPaint.alpha = fillPaint.alpha
                        canvas.drawText(p.char, px, py, emojiPaint)
                    } else {
                        fillPaint.color = p.color
                        canvas.drawCircle(px, py, 5f * resources.displayMetrics.density, fillPaint)
                    }
                }
            }
        }

        // 爆炸白闪压在所有内容之上
        if (bomb != null) {
            val age = now - bomb.bornAt
            if (age < 160L) {
                fillPaint.color = Color.WHITE
                fillPaint.alpha = (200f * (1f - age / 160f)).toInt().coerceIn(0, 255)
                canvas.drawRect(0f, 0f, width.toFloat(), height.toFloat(), fillPaint)
            }
        }
        canvas.restoreToCount(saveCount)

        if (hasVisibleContent()) postInvalidateOnAnimation()
    }

    private fun paintStroke(canvas: Canvas, s: Stroke, fade: Float, strokeW: Float) {
        paint.color = s.color
        paint.alpha = (255f * fade.coerceIn(0f, 1f)).toInt().coerceIn(0, 255)
        paint.strokeWidth = strokeW
        var prev: PointF? = null
        for (p in s.points) {
            val x = sx(p.x)
            val y = sy(p.y)
            prev?.let { canvas.drawLine(it.x, it.y, x, y, paint) }
            prev = PointF(x, y)
        }
        // 单击也留个点（"就点这里"是最常用的指引）
        if (s.points.size == 1) {
            val only = s.points[0]
            canvas.drawCircle(sx(only.x), sy(only.y), paint.strokeWidth / 2f, paint)
        }
    }

    companion object {
        private const val EMOJI_LIFE_MS = 1600L
        private const val BOMB_LIFE_MS = 1000L
        private const val ROCKET_LIFE_MS = 1200L

        /** 收笔信号丢失的兜底：最后笔点后静默此时长即视为已收笔，开始淡出 */
        const val STROKE_SILENCE_MS = 1200L
    }
}
