package com.soul2soul.app.session

import android.app.Service
import android.content.Intent
import android.graphics.PixelFormat
import android.graphics.Rect
import android.graphics.RectF
import android.os.Handler
import android.os.IBinder
import android.os.Looper
import android.provider.Settings
import android.view.Gravity
import android.view.WindowManager
import com.soul2soul.app.R
import org.json.JSONObject
import kotlin.math.hypot

/**
 * 共享端悬浮层（懒加载 + 按需最小化）：
 *  - 标注画布：只在收到笔迹/表情/特效时添加，内容淡出后自动移除
 *  - 窗口尺寸：纯笔迹时收缩到笔迹包围盒，全屏悬浮窗只在表情/特效播放期间出现
 *
 * 为什么收缩：MIUI 对全屏 TYPE_APPLICATION_OVERLAY 窗口的触摸穿透有版本相关怪癖
 * （FLAG_NOT_TOUCHABLE 可能失效变成全屏"玻璃罩"冻死桌面）。对方画一笔、你正搓玻璃，
 * 游戏直接废掉。包围盒窗口把"可能的遮挡面"压到笔迹那一小块，且随内容消失即拆；
 * 表情/特效必须满屏演，但它们只活 ~1.6 秒，遮挡窗口极小。
 */
class AnnotationOverlayService : Service() {

    private val mainHandler = Handler(Looper.getMainLooper())
    private var canvas: OverlayCanvasView? = null
    private var canvasRemoval: Runnable? = null

    /** 当前窗口矩形（像素）。null = 窗口未挂或上次为全屏，用于跳过重复 updateViewLayout */
    private var winRect: Rect? = null

    private val wm: WindowManager get() = getSystemService(WINDOW_SERVICE) as WindowManager

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onCreate() {
        super.onCreate()
        hook = { json -> mainHandler.post { acceptInteraction(json) } }
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        if (!Settings.canDrawOverlays(this)) {
            stopSelf()
            return START_NOT_STICKY
        }
        addBall()
        return START_STICKY
    }

    override fun onDestroy() {
        hook = null
        mainHandler.removeCallbacksAndMessages(null)
        canvas?.let { runCatching { wm.removeView(it) } }
        canvas = null
        winRect = null
        exitDoodle()
        ball?.let { runCatching { wm.removeView(it) } }
        ball = null
        super.onDestroy()
    }

    /** 收到互动内容：应用 → 依内容形态布置窗口（懒加载、按需收缩）→ 安排淡出后移除 */
    private fun acceptInteraction(json: JSONObject) {
        if (!Settings.canDrawOverlays(this)) return
        val k = json.optString("k")
        val cv = canvas ?: OverlayCanvasView(this).also { canvas = it }
        when (k) {
            "emoji" -> cv.applyEmoji(json)
            "fx" -> cv.applyFx(json)
            else -> cv.applyStroke(json)
        }
        val fullWanted = k == "emoji" || k == "fx" || cv.hasEmojiOrFx()
        // 空消息（迟到的清屏/收笔、只有"s"没有笔点）不挂窗——连一帧全屏暴露都不给
        if (!fullWanted && cv.parent == null && !cv.hasVisibleContent()) return
        layoutWindow(cv, fullWanted)
        canvasRemoval?.let { mainHandler.removeCallbacks(it) }
        val removal = Runnable { maybeRemoveCanvas() }
        canvasRemoval = removal
        mainHandler.postDelayed(removal, CONTENT_LIFE_MS + 800)
    }

    /** 旋转后 displayMetrics 变了：按新屏幕重排窗口与视口 */
    override fun onConfigurationChanged(newConfig: android.content.res.Configuration) {
        super.onConfigurationChanged(newConfig)
        // post 一拍：部分机型回调内 Resources 指标尚未刷新完
        mainHandler.post {
            // 自画层/工具条按旧屏幕尺寸挂的，旋转后直接退出（笔迹随窗口一起清掉）
            if (doodle != null) exitDoodle()
            ball?.let { b ->
                val (sw, sh) = com.soul2soul.app.util.ScreenSize.real(this)
                val lp = b.layoutParams as WindowManager.LayoutParams
                lp.x = lp.x.coerceIn(0, (sw - b.width).coerceAtLeast(0))
                lp.y = lp.y.coerceIn(0, (sh - b.height).coerceAtLeast(0))
                ballX = lp.x; ballY = lp.y
                runCatching { wm.updateViewLayout(b, lp) }
            }
            val cv = canvas ?: return@post
            if (cv.parent == null) return@post // 未挂窗就别借旋转之机挂上去
            if (!cv.hasVisibleContent()) {
                // 无可绘内容（如仅剩隐形空笔迹）：直接拆窗，别走全屏兜底把玻璃罩又立起来
                runCatching { wm.removeView(cv) }
                canvas = null
                winRect = null
                return@post
            }
            winRect = null
            layoutWindow(cv, fullWanted = cv.hasEmojiOrFx())
        }
    }

    private fun layoutWindow(cv: OverlayCanvasView, fullWanted: Boolean) {
        // 真实显示尺寸：悬浮窗坐标是全屏幕，app 视角 dm 会让笔迹整体上偏
        val (screenW, screenH) = com.soul2soul.app.util.ScreenSize.real(this)
        val attached = cv.parent != null
        val rect: Rect = if (fullWanted || !attached && cv.strokesBboxN() == null) {
            Rect(0, 0, screenW, screenH)
        } else {
            bboxRect(cv, screenW, screenH) ?: winRect ?: Rect(0, 0, screenW, screenH)
        }
        val same = winRect != null && winRect!!.left == rect.left && winRect!!.top == rect.top &&
            winRect!!.width() == rect.width() && winRect!!.height() == rect.height()
        val lp = WindowManager.LayoutParams(
            rect.width(),
            rect.height(),
            overlayWindowType(),
            WindowManager.LayoutParams.FLAG_NOT_TOUCHABLE
                or WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE
                // rect/x/y are expressed in the physical display coordinate space. Without
                // this flag WindowManager may inset an overlay below the status bar, so a
                // perfectly normalized point is rendered one inset above/below its target.
                or WindowManager.LayoutParams.FLAG_LAYOUT_IN_SCREEN,
            PixelFormat.TRANSLUCENT,
        ).apply {
            gravity = Gravity.TOP or Gravity.START
            x = rect.left
            y = rect.top
        }
        if (!attached) {
            cv.setViewport(rect.left, rect.top, screenW, screenH)
            winRect = rect
            runCatching { wm.addView(cv, lp) }.onFailure {
                android.util.Log.e("Overlay", "canvas addView failed", it)
                canvas = null
                winRect = null
                android.widget.Toast.makeText(
                    this, R.string.overlay_permission_missing, android.widget.Toast.LENGTH_LONG
                ).show()
            }
        } else if (!same) {
            // 失败时视口/缓存留在旧矩形（窗口实际没动）：内容不错位，下次消息自然重试
            runCatching { wm.updateViewLayout(cv, lp) }
                .onSuccess {
                    cv.setViewport(rect.left, rect.top, screenW, screenH)
                    winRect = rect
                }
                .onFailure { android.util.Log.w("Overlay", "updateViewLayout failed", it) }
        }
    }

    /** 笔迹包围盒 → 屏幕像素矩形（外扩 24dp、钳到屏内、设最小边长防零尺寸窗口） */
    private fun bboxRect(cv: OverlayCanvasView, screenW: Int, screenH: Int): Rect? {
        val bbox: RectF = cv.strokesBboxN() ?: return null
        val pad = (24 * resources.displayMetrics.density)
        val minSide = (96 * resources.displayMetrics.density)
        var l = (bbox.left * screenW - pad).toInt().coerceAtLeast(0)
        var t = (bbox.top * screenH - pad).toInt().coerceAtLeast(0)
        var r = (bbox.right * screenW + pad).toInt().coerceAtMost(screenW)
        var b = (bbox.bottom * screenH + pad).toInt().coerceAtMost(screenH)
        if (r - l < minSide) {
            r = (l + minSide.toInt()).coerceAtMost(screenW)
            l = (r - minSide.toInt()).coerceAtLeast(0)
        }
        if (b - t < minSide) {
            b = (t + minSide.toInt()).coerceAtMost(screenH)
            t = (b - minSide.toInt()).coerceAtLeast(0)
        }
        return Rect(l, t, r, b)
    }

    /** TYPE_APPLICATION_OVERLAY 是 API 26+ 类型，Android 7/8 以下挂它会被 WindowManager 直接拒绝（Mi5 悬浮窗"无效"根因） */
    @Suppress("DEPRECATION")
    private fun overlayWindowType(): Int =
        if (android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.O)
            WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY
        else
            WindowManager.LayoutParams.TYPE_PHONE

    private fun maybeRemoveCanvas() {
        val cv = canvas ?: return
        if (cv.hasVisibleContent()) {
            // 复查也要登记：新消息（如落笔"s"还没来笔点）才能取消这一发，
            // 否则会在"上一笔淡出→新一笔首点"的空隙里把窗口从正在画的人手里拆掉
            val r = Runnable { maybeRemoveCanvas() }
            canvasRemoval = r
            mainHandler.postDelayed(r, 800)
            return
        }
        runCatching { wm.removeView(cv) }
        canvas = null
        winRect = null
    }

    // ---------- 共享端自画：悬浮画笔球 + 全屏绘制层 + 工具条 ----------

    private var ball: android.widget.TextView? = null
    private var ballX = -1
    private var ballY = -1
    private var doodle: SharerDoodleView? = null
    private var doodleBar: android.widget.LinearLayout? = null

    private fun dp(v: Float): Int = (v * resources.displayMetrics.density).toInt()

    /** 画笔球：可拖动，轻点进出自画模式。自画期间隐藏（球会被全屏绘制层压住） */
    private fun addBall() {
        if (ball != null || !Settings.canDrawOverlays(this)) return
        val (sw, sh) = com.soul2soul.app.util.ScreenSize.real(this)
        val size = dp(56f)
        if (ballX < 0) {
            ballX = sw - size - dp(10f)
            ballY = sh * 2 / 3
        }
        val tv = android.widget.TextView(this).apply {
            text = "✏️"
            textSize = 22f
            gravity = Gravity.CENTER
            background = androidx.core.content.ContextCompat.getDrawable(this@AnnotationOverlayService, R.drawable.bg_bubble)
            setTextColor(android.graphics.Color.WHITE)
        }
        val slop = android.view.ViewConfiguration.get(this).scaledTouchSlop
        var startX = 0f; var startY = 0f; var ox = 0; var oy = 0; var dragged = false
        tv.setOnTouchListener { v, ev ->
            when (ev.actionMasked) {
                android.view.MotionEvent.ACTION_DOWN -> {
                    startX = ev.rawX; startY = ev.rawY; ox = ballX; oy = ballY; dragged = false; true
                }
                android.view.MotionEvent.ACTION_MOVE -> {
                    val dx = ev.rawX - startX; val dy = ev.rawY - startY
                    if (!dragged && hypot(dx, dy) > slop) dragged = true
                    if (dragged) {
                        val (w2, h2) = com.soul2soul.app.util.ScreenSize.real(this@AnnotationOverlayService)
                        ballX = (ox + dx.toInt()).coerceIn(0, (w2 - v.width).coerceAtLeast(0))
                        ballY = (oy + dy.toInt()).coerceIn(0, (h2 - v.height).coerceAtLeast(0))
                        val lp = v.layoutParams as WindowManager.LayoutParams
                        lp.x = ballX; lp.y = ballY
                        runCatching { wm.updateViewLayout(v, lp) }
                    }
                    true
                }
                android.view.MotionEvent.ACTION_UP -> {
                    if (!dragged) toggleDoodle()
                    true
                }
                else -> false
            }
        }
        val lp = WindowManager.LayoutParams(
            size, size, overlayWindowType(),
            WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE
                or WindowManager.LayoutParams.FLAG_LAYOUT_IN_SCREEN,
            PixelFormat.TRANSLUCENT,
        ).apply {
            gravity = Gravity.TOP or Gravity.START
            x = ballX; y = ballY
        }
        runCatching { wm.addView(tv, lp) }
            .onSuccess { ball = tv }
            .onFailure { android.util.Log.e("Overlay", "ball addView failed", it) }
    }

    private fun toggleDoodle() {
        if (doodle != null) {
            exitDoodle()
            return
        }
        if (!Settings.canDrawOverlays(this)) return
        val dv = SharerDoodleView(this)
        val layerLp = WindowManager.LayoutParams(
            WindowManager.LayoutParams.MATCH_PARENT,
            WindowManager.LayoutParams.MATCH_PARENT,
            overlayWindowType(),
            WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE
                or WindowManager.LayoutParams.FLAG_LAYOUT_IN_SCREEN,
            PixelFormat.TRANSLUCENT,
        )
        // 自画层吃掉全部触摸：这是"模式"，画到桌面图标上不该触发图标
        runCatching { wm.addView(dv, layerLp) }.onFailure {
            android.util.Log.e("Overlay", "doodle layer addView failed", it)
            return
        }
        doodle = dv
        ball?.visibility = android.view.View.INVISIBLE
        addDoodleBar(dv)
        android.widget.Toast.makeText(this, R.string.doodle_enter_hint, android.widget.Toast.LENGTH_SHORT).show()
    }

    private fun addDoodleBar(dv: SharerDoodleView) {
        val bar = android.widget.LinearLayout(this).apply {
            orientation = android.widget.LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            setBackgroundResource(R.drawable.bg_bubble)
            setPadding(dp(12f), dp(8f), dp(12f), dp(8f))
        }
        val dots = ArrayList<android.view.View>()
        StrokeColors.COLORS.forEachIndexed { i, color ->
            val dot = android.view.View(this).apply {
                background = androidx.core.content.ContextCompat.getDrawable(this@AnnotationOverlayService, R.drawable.bg_dot)
                backgroundTintList = android.content.res.ColorStateList.valueOf(color)
                alpha = if (i == dv.engine.colorIndex) 1f else 0.4f
            }
            dot.layoutParams = android.widget.LinearLayout.LayoutParams(dp(28f), dp(28f)).apply {
                marginStart = if (i == 0) 0 else dp(8f)
            }
            dot.setOnClickListener {
                dv.setColor(i)
                dots.forEachIndexed { j, d -> d.alpha = if (j == i) 1f else 0.4f }
            }
            dots.add(dot)
            bar.addView(dot)
        }
        val clear = android.widget.TextView(this).apply {
            text = getString(R.string.clear_strokes)
            setTextColor(android.graphics.Color.WHITE)
            textSize = 13f
            setBackgroundResource(R.drawable.bg_chip)
            setPadding(dp(10f), dp(6f), dp(10f), dp(6f))
            layoutParams = android.widget.LinearLayout.LayoutParams(
                android.widget.LinearLayout.LayoutParams.WRAP_CONTENT,
                android.widget.LinearLayout.LayoutParams.WRAP_CONTENT,
            ).apply { marginStart = dp(10f) }
            setOnClickListener { dv.clearAll() }
        }
        val done = android.widget.TextView(this).apply {
            text = getString(R.string.doodle_done)
            setTextColor(android.graphics.Color.WHITE)
            textSize = 13f
            setBackgroundResource(R.drawable.bg_chip)
            setPadding(dp(10f), dp(6f), dp(10f), dp(6f))
            layoutParams = android.widget.LinearLayout.LayoutParams(
                android.widget.LinearLayout.LayoutParams.WRAP_CONTENT,
                android.widget.LinearLayout.LayoutParams.WRAP_CONTENT,
            ).apply { marginStart = dp(6f) }
            setOnClickListener { exitDoodle() }
        }
        bar.addView(clear)
        bar.addView(done)
        val lp = WindowManager.LayoutParams(
            WindowManager.LayoutParams.WRAP_CONTENT,
            WindowManager.LayoutParams.WRAP_CONTENT,
            overlayWindowType(),
            WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE
                or WindowManager.LayoutParams.FLAG_LAYOUT_IN_SCREEN,
            PixelFormat.TRANSLUCENT,
        ).apply {
            gravity = Gravity.BOTTOM or Gravity.CENTER_HORIZONTAL
            y = dp(20f)
        }
        runCatching { wm.addView(bar, lp) }
            .onSuccess { doodleBar = bar }
            .onFailure { android.util.Log.e("Overlay", "doodle bar addView failed", it) }
    }

    private fun exitDoodle() {
        doodleBar?.let { runCatching { wm.removeView(it) } }
        doodleBar = null
        doodle?.let { runCatching { wm.removeView(it) } }
        doodle = null
        ball?.visibility = android.view.View.VISIBLE
    }

    companion object {
        private const val CONTENT_LIFE_MS = 2400L

        /** ScreenShareService 收到 DataChannel 消息时的入口（进程内单例转发） */
        @Volatile
        var hook: ((JSONObject) -> Unit)? = null
    }
}
