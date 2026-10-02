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

    /** 收到互动内容：应用 → 依内容形态布置窗口（懒加载、只涨不缩、表情/特效满屏）→ 安排淡出后移除 */
    private fun acceptInteraction(json: JSONObject) {
        if (!Settings.canDrawOverlays(this)) return
        val k = json.optString("k")
        val cv = canvas ?: OverlayCanvasView(this).also { canvas = it }
        when (k) {
            "emoji" -> cv.applyEmoji(json)
            "fx" -> cv.applyFx(json)
            else -> cv.applyStroke(json)
        }
        layoutWindow(cv, fullWanted = k == "emoji" || k == "fx" || cv.hasEmojiOrFx())
        canvasRemoval?.let { mainHandler.removeCallbacks(it) }
        val removal = Runnable { maybeRemoveCanvas() }
        canvasRemoval = removal
        mainHandler.postDelayed(removal, CONTENT_LIFE_MS + 800)
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
                or WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE,
            PixelFormat.TRANSLUCENT,
        ).apply {
            gravity = Gravity.TOP or Gravity.START
            x = rect.left
            y = rect.top
        }
        cv.setViewport(rect.left, rect.top, screenW, screenH)
        winRect = rect
        if (!attached) {
            runCatching { wm.addView(cv, lp) }.onFailure {
                android.util.Log.e("Overlay", "canvas addView failed", it)
                canvas = null
                winRect = null
                android.widget.Toast.makeText(
                    this, R.string.overlay_permission_missing, android.widget.Toast.LENGTH_LONG
                ).show()
            }
        } else if (!same) {
            runCatching { wm.updateViewLayout(cv, lp) }
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
            canvasRemoval?.let { mainHandler.removeCallbacks(it) }
            mainHandler.postDelayed({ maybeRemoveCanvas() }, 800)
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
