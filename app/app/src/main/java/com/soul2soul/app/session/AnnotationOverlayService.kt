package com.soul2soul.app.session

import android.app.Service
import android.content.Intent
import android.graphics.PixelFormat
import android.os.Handler
import android.os.IBinder
import android.os.Looper
import android.provider.Settings
import android.view.Gravity
import android.view.WindowManager
import com.soul2soul.app.R
import org.json.JSONObject

/**
 * 共享端悬浮层（懒加载 + 用完即拆）：
 *  - 标注画布：只在收到笔迹/表情/特效时添加，内容淡出后自动移除；
 *    窗口一次挂满全屏、存续期间绝不重排（重排会让旧帧被拉伸 = 笔迹残影）
 *  - 自画模式：摇一摇/悬浮球进出，全屏绘制层 + 自动隐现工具条（同窗口，见下）
 *
 * 窗口存续期要短：MIUI 对全屏 TYPE_APPLICATION_OVERLAY 窗口的触摸穿透有版本相关
 * 怪癖（FLAG_NOT_TOUCHABLE 可能失效变成全屏"玻璃罩"冻死桌面），所以画布在内容
 * 淡出后立刻拆除，不常驻。
 */
class AnnotationOverlayService : Service() {

    private val mainHandler = Handler(Looper.getMainLooper())
    private var canvas: OverlayCanvasView? = null
    private var canvasRemoval: Runnable? = null

    private val wm: WindowManager get() = getSystemService(WINDOW_SERVICE) as WindowManager

    // ---------- 摇一摇进出自画 ----------
    // 摇动零成本入口但要"甩手机"（用户反馈累）；悬浮球是主动点击入口、代价是会入画
    // （闲置淡化到 15% 缓解）。两者并存，任选其一。
    private val shakeDetector = ShakeDetector(clock = { android.os.SystemClock.uptimeMillis() })
    private var sensorManager: android.hardware.SensorManager? = null
    private val shakeListener = object : android.hardware.SensorEventListener {
        override fun onSensorChanged(event: android.hardware.SensorEvent) {
            if (event.sensor.type != android.hardware.Sensor.TYPE_ACCELEROMETER) return
            if (shakeDetector.onSample(event.values[0], event.values[1], event.values[2])) {
                android.util.Log.i("Overlay", "shake fired -> toggleDoodle (doodle=${doodle != null})")
                mainHandler.post {
                    runCatching {
                        (getSystemService(VIBRATOR_SERVICE) as android.os.Vibrator).vibrate(60L)
                    }
                    toggleDoodle()
                }
            }
        }

        override fun onAccuracyChanged(sensor: android.hardware.Sensor?, accuracy: Int) {}
    }

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onCreate() {
        super.onCreate()
        hook = { json -> mainHandler.post { acceptInteraction(json) } }
        sensorManager = getSystemService(SENSOR_SERVICE) as? android.hardware.SensorManager
        val acc = sensorManager?.getDefaultSensor(android.hardware.Sensor.TYPE_ACCELEROMETER)
        android.util.Log.i("Overlay", "shake init: acc=" + (acc?.name ?: "NONE"))
        if (acc != null) {
            runCatching {
                // 摇一摇只需识别 600ms 内的三次换向，不需要游戏级采样；
                // UI 级采样足够稳定，并减少共享期间的传感器唤醒与耗电。
                sensorManager?.registerListener(shakeListener, acc, android.hardware.SensorManager.SENSOR_DELAY_UI)
            }.onFailure { android.util.Log.w("Overlay", "shake register failed", it) }
        }
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        if (!Settings.canDrawOverlays(this)) {
            stopSelf()
            return START_NOT_STICKY
        }
        // 会话期间球常驻（自画模式开启时临时拆除）
        showBall()
        return START_STICKY
    }

    override fun onDestroy() {
        hook = null
        runCatching { sensorManager?.unregisterListener(shakeListener) }
        sensorManager = null
        mainHandler.removeCallbacksAndMessages(null)
        canvas?.let { runCatching { wm.removeView(it) } }
        canvas = null
        exitDoodle()
        hideBall()
        super.onDestroy()
    }

    /** 收到互动内容：应用 → 挂/复用全屏窗口 → 安排淡出后移除 */
    private fun acceptInteraction(json: JSONObject) {
        if (!Settings.canDrawOverlays(this)) return
        val k = json.optString("k")
        val cv = canvas ?: OverlayCanvasView(this).also { canvas = it }
        when (k) {
            "emoji" -> cv.applyEmoji(json)
            "fx" -> cv.applyFx(json)
            else -> cv.applyStroke(json)
        }
        val hasFx = k == "emoji" || k == "fx" || cv.hasEmojiOrFx()
        // 空消息（迟到的清屏/收笔、只有"s"没有笔点）不挂窗
        if (!hasFx && cv.parent == null && !cv.hasVisibleContent()) return
        layoutWindow(cv)
        canvasRemoval?.let { mainHandler.removeCallbacks(it) }
        val removal = Runnable { maybeRemoveCanvas() }
        canvasRemoval = removal
        // 有内容就续期；静默笔迹按"收笔+淡出"给足时间，避免动画中途被拆
        val life = if (cv.hasEmojiOrFx()) CONTENT_LIFE_MS + FX_LIFE_MS else CONTENT_LIFE_MS + STROKE_LIFE_MS
        mainHandler.postDelayed(removal, life)
    }

    /** 旋转后 displayMetrics 变了：按新屏幕重排窗口与视口 */
    override fun onConfigurationChanged(newConfig: android.content.res.Configuration) {
        super.onConfigurationChanged(newConfig)
        // post 一拍：部分机型回调内 Resources 指标尚未刷新完
        mainHandler.post {
            // 自画层/工具条按旧屏幕尺寸挂的，旋转后直接退出（笔迹随窗口一起清掉）
            if (doodle != null) {
                exitDoodle()
                showBall()
            } else {
                // 球按旧屏幕坐标挂着，旋转后回默认位（丢一次拖异位可接受）
                hideBall()
                showBall()
            }
            val cv = canvas ?: return@post
            if (cv.parent == null) return@post // 未挂窗就别借旋转之机挂上去
            if (!cv.hasVisibleContent()) {
                // 无可绘内容（如仅剩隐形空笔迹）：直接拆窗，别把全屏层空立在旋转后的桌面上
                runCatching { wm.removeView(cv) }
                canvas = null
                return@post
            }
            layoutWindow(cv)
        }
    }

    /**
     * 标注画布窗口：始终铺满整屏、挂上后绝不重排。
     * 为什么不再收缩到笔迹包围盒（v0.2.27 曾这么做以缩小遮挡面）：
     * 包围盒会随每个笔点变化，每次 updateViewLayout 都让 SurfaceFlinger 把上一帧
     * buffer 拉伸到新尺寸 —— 细线条被重采样成"漂移、晃动、拖出多条残影"（真机实锤）。
     * 本窗口带 FLAG_NOT_TOUCHABLE 不吃触摸，重排带来的视觉伤害远大于它声称规避的遮挡风险。
     */
    private fun layoutWindow(cv: OverlayCanvasView) {
        // 真实显示尺寸：悬浮窗坐标是全屏幕，app 视角 dm 会让笔迹整体上偏
        val (screenW, screenH) = com.soul2soul.app.util.ScreenSize.real(this)
        if (cv.parent != null) {
            val lp = cv.layoutParams as? WindowManager.LayoutParams
            val alreadyFull = lp != null && lp.width == screenW && lp.height == screenH && lp.x == 0 && lp.y == 0
            if (alreadyFull) {
                // 尺寸未变也要把视口对齐到新屏幕（旋转后 displayMetrics 变了）
                cv.setViewport(0, 0, screenW, screenH)
                return
            }
            val newLp = (lp ?: WindowManager.LayoutParams()).apply {
                width = screenW; height = screenH; x = 0; y = 0
            }
            runCatching { wm.updateViewLayout(cv, newLp) }
                .onSuccess {
                    cv.setViewport(0, 0, screenW, screenH)
                }
                .onFailure { android.util.Log.w("Overlay", "updateViewLayout failed", it) }
            return
        }
        val lp = WindowManager.LayoutParams(
            screenW,
            screenH,
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
            x = 0; y = 0
        }
        cv.setViewport(0, 0, screenW, screenH)
        runCatching { wm.addView(cv, lp) }.onFailure {
            android.util.Log.e("Overlay", "canvas addView failed", it)
            canvas = null
            android.widget.Toast.makeText(
                this, R.string.overlay_permission_missing, android.widget.Toast.LENGTH_LONG
            ).show()
        }
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
    }

    // ---------- 共享端自画：摇一摇/悬浮球进出 + 全屏绘制层 + 自动隐现工具条 ----------

    private var doodle: SharerDoodleView? = null
    private var doodleRoot: android.widget.FrameLayout? = null
    private var doodleBar: android.widget.LinearLayout? = null
    private var barHide: Runnable? = null

    private fun dp(v: Float): Int = (v * resources.displayMetrics.density).toInt()

    private fun toggleDoodle() {
        if (doodle != null) {
            exitDoodle()
            showBall()
            return
        }
        hideBall()
        if (!Settings.canDrawOverlays(this)) return
        val dv = SharerDoodleView(this)
        dv.onActivity = { showBar() }
        // 工具栏必须和自画层同窗口：Android 7/MIUI 上两个同类型悬浮窗
        // 视觉上工具栏在上，但触摸仍被先添加的全屏层吃掉 →「清屏」点不动（v0.2.27 真机实锤）。
        // 合成一个窗口后走常规 View 树命中测试，按钮区域优先分发给工具栏。
        val root = android.widget.FrameLayout(this)
        root.addView(dv, android.widget.FrameLayout.LayoutParams(
            android.widget.FrameLayout.LayoutParams.MATCH_PARENT,
            android.widget.FrameLayout.LayoutParams.MATCH_PARENT,
        ))
        val bar = buildDoodleBar(dv)
        root.addView(
            bar,
            android.widget.FrameLayout.LayoutParams(
                android.widget.FrameLayout.LayoutParams.WRAP_CONTENT,
                android.widget.FrameLayout.LayoutParams.WRAP_CONTENT,
                Gravity.BOTTOM or Gravity.CENTER_HORIZONTAL,
            ).apply { bottomMargin = dp(20f) },
        )
        val layerLp = WindowManager.LayoutParams(
            WindowManager.LayoutParams.MATCH_PARENT,
            WindowManager.LayoutParams.MATCH_PARENT,
            overlayWindowType(),
            WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE
                or WindowManager.LayoutParams.FLAG_LAYOUT_IN_SCREEN,
            PixelFormat.TRANSLUCENT,
        )
        // 自画层吃掉全部触摸：这是"模式"，画到桌面图标上不该触发图标
        runCatching { wm.addView(root, layerLp) }.onFailure {
            android.util.Log.e("Overlay", "doodle layer addView failed", it)
            return
        }
        doodle = dv
        doodleRoot = root
        doodleBar = bar
        showBar()
        android.widget.Toast.makeText(this, R.string.doodle_enter_hint, android.widget.Toast.LENGTH_SHORT).show()
    }

    /** 工具条唤回：本机任何一笔触摸都会触发；闲置 BAR_SHOW_MS 后淡出并退出命中区 */
    private fun showBar() {
        val bar = doodleBar ?: return
        barHide?.let { mainHandler.removeCallbacks(it) }
        bar.animate().cancel()
        bar.visibility = android.view.View.VISIBLE
        bar.alpha = 1f
        val hide = Runnable {
            bar.animate().alpha(0f).setDuration(350L)
                .withEndAction { bar.visibility = android.view.View.INVISIBLE }
                .start()
        }
        barHide = hide
        mainHandler.postDelayed(hide, BAR_SHOW_MS)
    }

    private fun buildDoodleBar(dv: SharerDoodleView): android.widget.LinearLayout {
        val bar = android.widget.LinearLayout(this).apply {
            orientation = android.widget.LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            setBackgroundResource(R.drawable.bg_bubble)
            setPadding(dp(12f), dp(8f), dp(12f), dp(8f))
            // 点空白处也算"本机有动作"：唤回正在淡出的工具条；返回 false 不干扰子按钮点击
            setOnTouchListener { _, ev ->
                if (ev.actionMasked == android.view.MotionEvent.ACTION_DOWN) showBar()
                false
            }
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
        return bar
    }

    private fun exitDoodle() {
        barHide?.let { mainHandler.removeCallbacks(it) }
        barHide = null
        doodleRoot?.let { runCatching { wm.removeView(it) } }
        doodleRoot = null
        doodle = null
        doodleBar = null
    }

    // ---------- 涂鸦入口悬浮球：轻点/长按开画笔，可拖动，闲置淡化 ----------
    // 独立小窗口（不吃全屏触摸、只占球那一块），所以长按/点击都能被我们感知——
    // 全屏任意位置的触摸属于前台 App，后台服务收不到（这是当初做摇一摇的原因）。
    // 入画代价用"闲置 3s 淡化到 15%"缓解；自画模式开启时球整体拆除，避免和工具条打架。
    private var ballView: android.widget.ImageView? = null
    private var ballLp: WindowManager.LayoutParams? = null
    private var ballDim: Runnable? = null
    private var ballDragging = false
    private var ballDragHappened = false
    private var ballDownRawX = 0f
    private var ballDownRawY = 0f
    private var ballBaseX = 0
    private var ballBaseY = 0

    private fun showBall() {
        if (ballView != null || doodle != null) return
        if (!Settings.canDrawOverlays(this)) return
        val size = dp(BALL_SIZE_DP)
        val (sw, sh) = com.soul2soul.app.util.ScreenSize.real(this)
        val lp = WindowManager.LayoutParams(
            size, size,
            overlayWindowType(),
            WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE
                or WindowManager.LayoutParams.FLAG_LAYOUT_IN_SCREEN,
            PixelFormat.TRANSLUCENT,
        ).apply {
            gravity = Gravity.TOP or Gravity.START
            x = sw - size - dp(6f)
            y = (sh * BALL_INITIAL_Y).toInt()
        }
        val slop = android.view.ViewConfiguration.get(this).scaledTouchSlop
        val gd = android.view.GestureDetector(this,
            object : android.view.GestureDetector.SimpleOnGestureListener() {
                override fun onDown(e: android.view.MotionEvent): Boolean { wakeBall(); return true }
                override fun onSingleTapUp(e: android.view.MotionEvent): Boolean {
                    if (!ballDragHappened) ballActivate()
                    return true
                }
                override fun onLongPress(e: android.view.MotionEvent) {
                    if (!ballDragHappened) ballActivate()
                }
            })
        val iv = android.widget.ImageView(this).apply {
            setImageResource(R.drawable.ic_ball_brush)
            setBackgroundResource(R.drawable.bg_ball)
            setPadding(dp(11f), dp(11f), dp(11f), dp(11f))
            contentDescription = getString(R.string.doodle_ball_desc)
            alpha = BALL_ACTIVE_ALPHA
            setOnTouchListener { v, ev ->
                gd.onTouchEvent(ev)
                when (ev.actionMasked) {
                    android.view.MotionEvent.ACTION_DOWN -> {
                        ballDownRawX = ev.rawX; ballDownRawY = ev.rawY
                        ballBaseX = lp.x; ballBaseY = lp.y
                        ballDragging = false; ballDragHappened = false
                    }
                    android.view.MotionEvent.ACTION_MOVE -> {
                        val dx = ev.rawX - ballDownRawX
                        val dy = ev.rawY - ballDownRawY
                        if (!ballDragging && dx * dx + dy * dy > slop * slop) ballDragging = true
                        if (ballDragging) {
                            ballDragHappened = true
                            wakeBall()
                            val (cx, cy) = DoodleBallPolicy.clamp(
                                (ballBaseX + dx).toInt(), (ballBaseY + dy).toInt(), size, sw, sh
                            )
                            lp.x = cx; lp.y = cy
                            runCatching { wm.updateViewLayout(v, lp) }
                                .onFailure { android.util.Log.w("Overlay", "ball move failed", it) }
                        }
                    }
                    android.view.MotionEvent.ACTION_UP,
                    android.view.MotionEvent.ACTION_CANCEL -> {
                        if (ballDragging) { ballDragging = false; scheduleBallDim() }
                    }
                }
                true
            }
        }
        ballLp = lp
        runCatching { wm.addView(iv, lp) }.onFailure {
            android.util.Log.e("Overlay", "ball addView failed", it)
            ballLp = null
            return
        }
        ballView = iv
        scheduleBallDim()
    }

    private fun wakeBall() {
        val v = ballView ?: return
        ballDim?.let { mainHandler.removeCallbacks(it) }
        v.animate().cancel()
        v.alpha = BALL_ACTIVE_ALPHA
        scheduleBallDim()
    }

    private fun scheduleBallDim() {
        val v = ballView ?: return
        ballDim?.let { mainHandler.removeCallbacks(it) }
        val r = Runnable { v.animate().alpha(BALL_DIM_ALPHA).setDuration(400L).start() }
        ballDim = r
        mainHandler.postDelayed(r, BALL_IDLE_MS)
    }

    private fun hideBall() {
        ballDim?.let { mainHandler.removeCallbacks(it) }
        ballDim = null
        ballView?.let { runCatching { wm.removeView(it) } }
        ballView = null
        ballLp = null
        ballDragging = false
        ballDragHappened = false
    }

    private fun ballActivate() {
        runCatching {
            (getSystemService(VIBRATOR_SERVICE) as android.os.Vibrator).vibrate(60L)
        }
        toggleDoodle()
    }

    companion object {
        private const val CONTENT_LIFE_MS = 2400L
        /** 满屏窗口在最后一笔后的存续预算：静默收笔 1200 + 淡出 2000 + 裕量 */
        private const val STROKE_LIFE_MS = 4000L
        /** 表情/特效最长生命周期（emoji 1600 / rocket 1200）+ 淡出裕量 */
        private const val FX_LIFE_MS = 1600L
        /** 工具条在本机最后一次触摸后的可见时长 */
        private const val BAR_SHOW_MS = 2500L

        /** 悬浮球：边长/闲置淡化延迟/两种透明度/初始纵向位置（屏高比例） */
        private const val BALL_SIZE_DP = 48f
        private const val BALL_IDLE_MS = 3000L
        private const val BALL_ACTIVE_ALPHA = 0.85f
        private const val BALL_DIM_ALPHA = 0.15f
        private const val BALL_INITIAL_Y = 0.55f

        /** ScreenShareService 收到 DataChannel 消息时的入口（进程内单例转发） */
        @Volatile
        var hook: ((JSONObject) -> Unit)? = null
    }
}
