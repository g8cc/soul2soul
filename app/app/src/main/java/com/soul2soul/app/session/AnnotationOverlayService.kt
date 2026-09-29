package com.soul2soul.app.session

import android.app.Service
import android.content.Intent
import android.graphics.PixelFormat
import android.os.Handler
import android.os.IBinder
import android.os.Looper
import android.provider.Settings
import android.view.WindowManager
import com.soul2soul.app.R
import org.json.JSONObject

/**
 * 共享端悬浮层（懒加载）：
 *  - 标注画布：只在收到笔迹/表情时添加，内容淡出后自动移除
 *
 * 为什么懒加载：MIUI 对全屏 TYPE_APPLICATION_OVERLAY 窗口的触摸穿透有版本相关怪癖
 * （FLAG_NOT_TOUCHABLE 可能失效变成全屏"玻璃罩"冻死桌面）。按需存在 + 不使用
 * FLAG_LAYOUT_NO_LIMITS 可以把暴露面压到最小；即使真被冻住，通知栏的「结束」
 * 也永远可用（通知不经过桌面窗口层）。
 *
 * 内容两种：笔迹（激光笔）+ 表情互动（从下往上飘的 emoji）。
 */
class AnnotationOverlayService : Service() {

    private val mainHandler = Handler(Looper.getMainLooper())
    private var canvas: OverlayCanvasView? = null
    private var canvasRemoval: Runnable? = null

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
        canvas?.let { runCatching { (getSystemService(WINDOW_SERVICE) as WindowManager).removeView(it) } }
        canvas = null
        OverlayCanvasView.discard()
        super.onDestroy()
    }

    /** 收到互动内容：确保画布窗口存在（懒加载）→ 应用 → 安排淡出后移除窗口 */
    private fun acceptInteraction(json: JSONObject) {
        if (canvas == null) ensureCanvas()
        if (canvas == null) return // 悬浮窗权限缺失：降级为无标注会话
        when (json.optString("k")) {
            "emoji" -> OverlayCanvasView.onEmoji(json)
            else -> OverlayCanvasView.onStroke(json)
        }
        canvasRemoval?.let { mainHandler.removeCallbacks(it) }
        val removal = Runnable { maybeRemoveCanvas() }
        canvasRemoval = removal
        mainHandler.postDelayed(removal, CONTENT_LIFE_MS + 800)
    }

    private fun maybeRemoveCanvas() {
        val cv = canvas ?: return
        if (cv.hasVisibleContent()) {
            canvasRemoval?.let { mainHandler.removeCallbacks(it) }
            mainHandler.postDelayed({ maybeRemoveCanvas() }, 800)
            return
        }
        runCatching { (getSystemService(WINDOW_SERVICE) as WindowManager).removeView(cv) }
        canvas = null
    }

    /** 全屏标注层懒加载（MIUI 触摸穿透怪癖的规避） */
    private fun ensureCanvas() {
        if (canvas != null) return
        if (!Settings.canDrawOverlays(this)) return
        val wm = getSystemService(WINDOW_SERVICE) as WindowManager
        val cv = OverlayCanvasView(this)
        runCatching {
            wm.addView(
                cv,
                WindowManager.LayoutParams(
                    WindowManager.LayoutParams.MATCH_PARENT,
                    WindowManager.LayoutParams.MATCH_PARENT,
                    WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY,
                    WindowManager.LayoutParams.FLAG_NOT_TOUCHABLE
                        or WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE,
                    PixelFormat.TRANSLUCENT,
                ),
            )
            canvas = cv
        }.onFailure {
            android.util.Log.e("Overlay", "canvas addView failed", it)
            android.widget.Toast.makeText(
                this, R.string.overlay_permission_missing, android.widget.Toast.LENGTH_LONG
            ).show()
        }
    }

    companion object {
        private const val CONTENT_LIFE_MS = 2400L

        /** ScreenShareService 收到 DataChannel 消息时的入口（进程内单例转发） */
        @Volatile
        var hook: ((JSONObject) -> Unit)? = null
    }
}
