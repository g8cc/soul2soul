package com.soul2soul.app.session

import android.content.Intent
import android.os.Bundle
import android.provider.Settings
import android.view.View
import android.widget.Button
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView
import androidx.appcompat.app.AppCompatActivity
import com.soul2soul.app.R

/**
 * 操控授权对话框（共享端）：点共享通知即弹这里。
 * HyperOS/Android13+ 常把 FGS 通知的动作按钮折叠到看不见，授权必须另有入口；
 * 且本窗口出现在被共享的屏幕上——她能亲眼看到你点了「允许」。
 */
class CtlConsentActivity : AppCompatActivity() {

    private lateinit var stateText: TextView
    private lateinit var accHint: TextView
    private lateinit var mainBtn: Button

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(buildUi())
        refresh()
    }

    override fun onResume() {
        super.onResume()
        refresh() // 可能在对话框开着时去了无障碍设置再回来
    }

    private fun buildUi(): View {
        val pad = (20 * resources.displayMetrics.density).toInt()
        val title = TextView(this).apply {
            setText(R.string.ctl_consent_title)
            textSize = 19f
            setTypeface(typeface, android.graphics.Typeface.BOLD)
            setPadding(0, pad, 0, pad / 2)
        }
        stateText = TextView(this).apply { textSize = 15f; setPadding(0, 0, 0, pad / 2) }
        accHint = TextView(this).apply {
            setText(R.string.ctl_consent_need_acc)
            textSize = 13f
            setTextColor(android.graphics.Color.parseColor("#C7800A"))
            setPadding(0, 0, 0, pad / 2)
        }
        mainBtn = Button(this).apply {
            setOnClickListener {
                when {
                    !ScreenShareService.isRunning() -> finish()
                    !RemoteControlService.isReady() -> {
                        // 无障碍没开：直达最终授权页（详情页 intent 不可用时给路径指引再进列表），
                        // 回来再点允许（服务侧也会拒绝，双保险）
                        com.soul2soul.app.util.AccessibilityLauncher.open(this@CtlConsentActivity)
                    }
                    ScreenShareService.ctlAllowed -> setCtl(false)
                    else -> setCtl(true)
                }
                refresh()
            }
        }
        val close = Button(this).apply {
            setText(R.string.ctl_consent_close)
            setOnClickListener { finish() }
        }
        val col = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(pad, 0, pad, pad / 2)
            addView(title)
            addView(stateText)
            addView(accHint)
            addView(mainBtn)
            addView(close)
        }
        return ScrollView(this).apply { addView(col) }
    }

    private fun setCtl(on: Boolean) {
        if (!ScreenShareService.isRunning()) {
            finish() // 会话已结束：对话框别残留成假授权入口
            return
        }
        // 同进程共享 companion 状态：先落状态让界面立刻正确，
        // 服务收到 ACTION_SET_CTL 后做同一件事（幂等）并刷新通知栏
        ScreenShareService.ctlAllowed = on
        startService(
            Intent(this, ScreenShareService::class.java)
                .setAction(ScreenShareService.ACTION_SET_CTL)
                .putExtra(ScreenShareService.EXTRA_CTL_ON, on)
        )
    }

    private fun refresh() {
        val accReady = RemoteControlService.isReady()
        val live = ScreenShareService.isRunning()
        val allowed = ScreenShareService.ctlAllowed
        accHint.visibility = if (accReady) View.GONE else View.VISIBLE
        stateText.text = when {
            !live -> getString(R.string.ctl_consent_no_session)
            allowed -> getString(R.string.ctl_consent_on)
            else -> getString(R.string.ctl_consent_off)
        }
        mainBtn.isEnabled = live
        mainBtn.setText(
            when {
                !accReady -> R.string.ctl_consent_open_acc
                !live -> R.string.ctl_consent_close
                allowed -> R.string.ctl_consent_on_disable
                else -> R.string.ctl_consent_on_enable
            }
        )
    }
}
