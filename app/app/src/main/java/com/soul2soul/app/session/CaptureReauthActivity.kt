package com.soul2soul.app.session

import android.content.Intent
import android.os.Bundle
import android.widget.Button
import android.widget.LinearLayout
import android.widget.TextView
import android.widget.Toast
import androidx.activity.result.ActivityResultLauncher
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AppCompatActivity
import com.soul2soul.app.R

/**
 * 录屏授权被系统撤销后的重新授权入口（共享端）：点那条"画面已停"的通知即弹这里。
 *
 * 为什么必须是一个 Activity 而不是通知按钮：MediaProjection 的授权对话框只能由 Activity
 * 用 [android.media.projection.MediaProjectionManager.createScreenCaptureIntent] 发起，
 * 服务侧没有合法的起对话框路径；而 HyperOS 会把通知动作按钮折叠到看不见（同 [CtlConsentActivity]）。
 *
 * 这里**不**碰 PeerConnection：拿到新 token 就交回服务续流，音频与数据通道全程无感。
 */
class CaptureReauthActivity : AppCompatActivity() {

    private lateinit var launcher: ActivityResultLauncher<Intent>

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        if (!ScreenShareService.isRunning()) {
            // 通知是"至少一次"投递的：通话早已结束时用户才点开，别再要一份没用的录屏授权
            Toast.makeText(this, R.string.reauth_missing_service, Toast.LENGTH_SHORT).show()
            finish()
            return
        }
        launcher = registerForActivityResult(ActivityResultContracts.StartActivityForResult()) { r ->
            val data = r.data
            if (r.resultCode == RESULT_OK && data != null) {
                ScreenShareService.reopenCapture(this, data)
            }
            // 拒绝或系统取消：什么都不做——会话本来就活着，通知会一直停在"画面已停"，可以再点
            finish()
        }
        setContentView(buildUi())
    }

    private fun buildUi(): android.view.View = LinearLayout(this).apply {
        orientation = LinearLayout.VERTICAL
        val pad = (20 * resources.displayMetrics.density).toInt()
        setPadding(pad, pad, pad, pad)
        addView(TextView(this@CaptureReauthActivity).apply {
            setText(R.string.reauth_title)
            textSize = 19f
            setTypeface(typeface, android.graphics.Typeface.BOLD)
            setPadding(0, 0, 0, pad / 2)
        })
        addView(TextView(this@CaptureReauthActivity).apply {
            setText(R.string.reauth_body)
            textSize = 15f
            setPadding(0, 0, 0, pad)
        })
        addView(Button(this@CaptureReauthActivity).apply {
            setText(R.string.reauth_go)
            setOnClickListener { askForProjection() }
        })
        addView(Button(this@CaptureReauthActivity).apply {
            setText(R.string.reauth_later)
            setOnClickListener { finish() }
        })
    }

    private fun askForProjection() {
        val mpm = getSystemService(
            android.content.Context.MEDIA_PROJECTION_SERVICE
        ) as android.media.projection.MediaProjectionManager
        launcher.launch(mpm.createScreenCaptureIntent())
    }
}
