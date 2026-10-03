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
        return START_STICKY
    }

    override fun onDestroy() {
        hook = null
        mainHandler.removeCallbacksAndMessages(null)
        canvas?.let { runCatching { wm.removeView(it) } }
        canvas = null
        winRect = null
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
        val screenW = resources.displayMetrics.widthPixels
        val screenH = resources.displayMetrics.heightPixels
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
            WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY,
            WindowManager.LayoutParams.FLAG_NOT_TOUCHABLE
                or WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE
                // 双影修复：观看端笔迹发到共享端后画在悬浮层里，若不隔离会被
                // MediaProjection 录回视频流，观看端会看到同一笔迹的本地版+视频回灌版。
                // FLAG_SECURE 让本窗口内容退出屏幕采集（Android 8+ 悬浮窗有效）。
                or (if (android.os.Build.VERSION.SDK_INT >= 26) WindowManager.LayoutParams.FLAG_SECURE else 0),
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

    companion object {
        private const val CONTENT_LIFE_MS = 2400L

        /** ScreenShareService 收到 DataChannel 消息时的入口（进程内单例转发） */
        @Volatile
        var hook: ((JSONObject) -> Unit)? = null
    }
}
