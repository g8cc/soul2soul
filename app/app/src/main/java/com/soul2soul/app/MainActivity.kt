package com.soul2soul.app

import android.Manifest
import android.app.Activity
import android.content.Context
import android.content.Intent
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.provider.Settings
import android.util.Log
import android.view.View
import android.widget.Button
import android.widget.EditText
import android.widget.TextView
import android.widget.Toast
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AlertDialog
import androidx.appcompat.app.AppCompatActivity
import androidx.lifecycle.lifecycleScope
import com.soul2soul.app.session.Notif
import com.soul2soul.app.session.Presence
import com.soul2soul.app.session.ScreenShareService
import com.soul2soul.app.signaling.SignalBus
import com.soul2soul.app.util.Prefs
import kotlinx.coroutines.launch
import org.json.JSONObject

/**
 * 主页：配对 → 待命 → 发起共享。
 * 呼叫流程（SPEC §3）：先授权屏幕 → 再发邀请 → 对方一接听即刻出画面（无授权间隙）。
 * 双方同时呼叫（撞车）时按设备号裁决：设备号小的邀请优先，大的让路接听（telephony glare 处理）。
 */
class MainActivity : AppCompatActivity() {

    private var peerOnline = false
    private var pendingUpdateInfo: com.soul2soul.app.util.Updater.Info? = null
    private var pendingInstallFile: java.io.File? = null
    private val callTimeout = Handler(Looper.getMainLooper())

    /** 主叫状态标记（PresenceService 撞车裁决也要读，故放伴生对象） */
    companion object {
        private const val TAG = "S2S-Main"
        private const val CALL_TIMEOUT_MS = 30_000L

        @Volatile var outgoingPending = false
        @Volatile var acceptedReceived = false
        var projectionCode = 0
        var projectionData: Intent? = null

        fun cancelOutgoing() {
            outgoingPending = false
            acceptedReceived = false
        }
    }

    private lateinit var tvStatus: TextView
    private lateinit var boxPairing: View
    private lateinit var boxReady: View
    private lateinit var boxSharing: View
    private lateinit var tvMyCode: TextView
    private lateinit var btnCall: Button
    private lateinit var permNotification: View
    private lateinit var permMic: View
    private lateinit var permOverlay: View

    private val projectionLauncher =
        registerForActivityResult(ActivityResultContracts.StartActivityForResult()) { result ->
            val data = result.data
            if (result.resultCode == Activity.RESULT_OK && data != null) {
                // 授权成功 → 此时才发邀请；对方接听后 accepted 到达即启动共享服务
                projectionCode = result.resultCode
                projectionData = data
                Presence.client.send("invite")
                Log.d(TAG, "invite sent")
                callTimeout.postDelayed({
                    if (outgoingPending) {
                        cancelOutgoing()
                        toast(R.string.call_timeout)
                        updateUi()
                    }
                }, CALL_TIMEOUT_MS)
            } else {
                toast(R.string.projection_denied)
                cancelOutgoing()
            }
            updateUi()
        }

    private val micPermission =
        registerForActivityResult(ActivityResultContracts.RequestPermission()) {
            refreshPermissionRows()
        }

    private val notifPermission =
        registerForActivityResult(ActivityResultContracts.RequestPermission()) {
            refreshPermissionRows()
        }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_main)

        Notif.ensureChannels(this)
        Presence.ensureStarted(this)
        requestNotificationPermissionIfNeeded()

        tvStatus = findViewById(R.id.tvStatus)
        boxPairing = findViewById(R.id.boxPairing)
        boxReady = findViewById(R.id.boxReady)
        boxSharing = findViewById(R.id.boxSharing)
        tvMyCode = findViewById(R.id.tvMyCode)
        btnCall = findViewById(R.id.btnCall)
        permNotification = findViewById(R.id.permNotification)
        permMic = findViewById(R.id.permMic)
        permOverlay = findViewById(R.id.permOverlay)

        findViewById<View>(R.id.btnShowCode).setOnClickListener {
            if (Presence.client.isConnected) {
                Presence.client.send("pair.request")
            } else {
                toast(R.string.connecting_server)
            }
        }
        findViewById<View>(R.id.btnEnterCode).setOnClickListener { showEnterCodeDialog() }
        findViewById<View>(R.id.btnEndSharing).setOnClickListener {
            ScreenShareService.stopFromOverlay(this)
            boxSharing.postDelayed({ updateUi() }, 500)
        }
        btnCall.setOnClickListener { onCallClicked() }
        findViewById<View>(R.id.btnUnpair).setOnClickListener { showUnpairDialog() }
        findViewById<View>(R.id.btnUpdate).setOnClickListener { showUpdateDialog() }
        checkForUpdate()
        permNotification.setOnClickListener { requestNotificationPermissionIfNeeded(force = true) }
        permMic.setOnClickListener {
            micPermission.launch(Manifest.permission.RECORD_AUDIO)
        }
        permOverlay.setOnClickListener {
            startActivity(
                Intent(
                    Settings.ACTION_MANAGE_OVERLAY_PERMISSION,
                    Uri.parse("package:$packageName"),
                )
            )
        }

        lifecycleScope.launch {
            SignalBus.events.collect { handleSignal(it) }
        }
    }

    private fun handleSignal(json: JSONObject) {
        Log.d(TAG, "signal: ${json.optString("type")}")
        when (json.optString("type")) {
            "pair.code" -> {
                tvMyCode.text = json.optString("code")
                tvMyCode.visibility = View.VISIBLE
            }
            "paired" -> {
                Prefs.setPaired(this, true)
                json.optString("token").takeIf { it.isNotEmpty() }?.let {
                    Prefs.setPairToken(this, it)
                    Presence.client.setPairToken(it)
                }
                peerOnline = json.optBoolean("peerOnline", false)
                toast(R.string.paired_done)
                maybeShowRomGuide() // 配对完成才是引导保活的合理时机（首启连弹两窗太吵）
                updateUi()
            }
            "pair.failed" -> toast(R.string.pair_failed)
            "registered" -> {
                Prefs.setPaired(this, json.optBoolean("paired", false))
                peerOnline = json.optBoolean("peerOnline", false)
                updateUi()
            }
            "peer.online" -> {
                peerOnline = true
                updateUi()
            }
            "peer.gone" -> {
                peerOnline = false
                updateUi()
            }
            "accepted" -> {
                // 对方接听：屏幕授权已在邀请前完成，此刻直接启动共享服务（offer 即刻发出）
                callTimeout.removeCallbacksAndMessages(null)
                acceptedReceived = true
                Presence.iceServersJson = json.optJSONArray("iceServers")
                projectionData?.let {
                    ScreenShareService.start(this, projectionCode, it)
                }
            }
            "declined" -> {
                callTimeout.removeCallbacksAndMessages(null)
                cancelOutgoing()
                toast(R.string.peer_declined)
                updateUi()
            }
            "call.canceled" -> {
                callTimeout.removeCallbacksAndMessages(null)
                cancelOutgoing()
                toast(R.string.call_canceled)
                updateUi()
            }
            "peer.offline" -> {
                callTimeout.removeCallbacksAndMessages(null)
                cancelOutgoing()
                toast(R.string.peer_offline)
                updateUi()
            }
            "unpaired" -> {
                // 被对端解除配对（或自己发起后服务端确认）
                callTimeout.removeCallbacksAndMessages(null)
                cancelOutgoing()
                Prefs.setPaired(this, false)
                Prefs.setPairToken(this, null)
                Presence.client.setPairToken(null)
                peerOnline = false
                toast(R.string.unpaired_done)
                updateUi()
            }
            "local.sessionEnded" -> {
                // 会话因任何原因结束时，共享服务广播此本地事件：复位主叫"呼叫中"状态
                callTimeout.removeCallbacksAndMessages(null)
                cancelOutgoing()
                updateUi()
            }
        }
    }

    private fun onCallClicked() {
        if (outgoingPending) {
            // 二次点击 = 取消呼叫（对方已接听则发 bye，让 TA 别干等）
            callTimeout.removeCallbacksAndMessages(null)
            if (acceptedReceived) Presence.client.send("bye") else Presence.client.send("cancel")
            cancelOutgoing()
            updateUi()
            return
        }
        if (!Prefs.paired(this)) {
            toast(R.string.status_unpaired)
            return
        }
        if (!Settings.canDrawOverlays(this)) {
            startActivity(
                Intent(
                    Settings.ACTION_MANAGE_OVERLAY_PERMISSION,
                    Uri.parse("package:$packageName"),
                )
            )
            return
        }
        if (!Presence.client.isConnected) {
            toast(R.string.connecting_server)
            return
        }
        maybeShowRomGuide()
        outgoingPending = true
        updateUi()
        // 先授权屏幕、后邀请：对方一接听立刻出画面，中间无授权间隙
        requestScreenCapture()
    }

    /** 解除配对：换绑设备/测试切换用。解绑后双方都回到未配对状态，需重新走配对码 */
    private fun showUnpairDialog() {
        AlertDialog.Builder(this)
            .setTitle(R.string.unpair_title)
            .setMessage(R.string.unpair_message)
            .setPositiveButton(R.string.unpair_confirm) { _, _ ->
                Presence.client.send("unpair")
            }
            .setNegativeButton(R.string.cancel, null)
            .show()
    }

    // ---------- 自动更新 ----------

    private fun checkForUpdate() {
        lifecycleScope.launch {
            val info = kotlinx.coroutines.withContext(kotlinx.coroutines.Dispatchers.IO) {
                com.soul2soul.app.util.Updater.checkAsync()
            } ?: return@launch
            if (com.soul2soul.app.util.Updater.hasUpdate(info)) {
                pendingUpdateInfo = info
                findViewById<View>(R.id.btnUpdate).visibility = View.VISIBLE
                findViewById<TextView>(R.id.btnUpdate).text =
                    getString(R.string.update_available_short) + " v" + info.versionName
            }
        }
    }

    private fun showUpdateDialog() {
        val info = pendingUpdateInfo ?: return
        AlertDialog.Builder(this)
            .setTitle(getString(R.string.update_confirm_title, info.versionName))
            .setMessage(R.string.update_confirm_msg)
            .setPositiveButton(R.string.ok) { _, _ -> downloadAndInstall(info) }
            .setNegativeButton(R.string.cancel, null)
            .show()
    }

    private fun downloadAndInstall(info: com.soul2soul.app.util.Updater.Info) {
        val container = android.widget.LinearLayout(this).apply {
            orientation = android.widget.LinearLayout.VERTICAL
            setPadding(48, 24, 48, 24)
        }
        val bar = android.widget.ProgressBar(this, null, android.R.attr.progressBarStyleHorizontal).apply {
            max = 100; progress = 0
        }
        val pct = TextView(this).apply { text = "0%"; gravity = android.view.Gravity.CENTER }
        container.addView(bar)
        container.addView(pct)
        val dialog = AlertDialog.Builder(this)
            .setTitle(getString(R.string.update_confirm_title, info.versionName))
            .setView(container)
            .setCancelable(false)
            .create()
        dialog.show()
        lifecycleScope.launch(kotlinx.coroutines.Dispatchers.IO) {
            val file = com.soul2soul.app.util.Updater.download(
                info.url,
                java.io.File(getExternalFilesDir("apk"), "update-${info.versionCode}.apk"),
            ) { p ->
                runOnUiThread {
                    bar.progress = p
                    pct.text = "$p%"
                }
            }
            runOnUiThread {
                dialog.dismiss()
                if (file != null) {
                    toast(R.string.update_starting)
                    installApk(file)
                } else {
                    toast(R.string.update_download_failed)
                }
            }
        }
    }

    private fun installApk(file: java.io.File) {
        if (Build.VERSION.SDK_INT >= 26 && !packageManager.canRequestPackageInstalls()) {
            pendingInstallFile = file
            startActivity(
                Intent(
                    Settings.ACTION_MANAGE_UNKNOWN_APP_SOURCES,
                    Uri.parse("package:$packageName")
                )
            )
            return
        }
        doInstall(file)
    }

    private fun doInstall(file: java.io.File) {
        val uri = androidx.core.content.FileProvider.getUriForFile(
            this, "$packageName.fileprovider", file
        )
        val i = Intent(android.content.Intent.ACTION_INSTALL_PACKAGE).apply {
            setDataAndType(uri, "application/vnd.android.package-archive")
            addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
        }
        runCatching { startActivity(i) }
            .onFailure { Log.w(TAG, "install start failed", it) }
    }

    private fun requestScreenCapture() {
        val mpm = getSystemService(Context.MEDIA_PROJECTION_SERVICE) as android.media.projection.MediaProjectionManager
        projectionLauncher.launch(mpm.createScreenCaptureIntent())
    }

    private fun showEnterCodeDialog() {
        val input = EditText(this).apply {
            hint = getString(R.string.enter_code_hint)
            inputType = android.text.InputType.TYPE_CLASS_NUMBER
            filters = arrayOf(android.text.InputFilter.LengthFilter(6))
        }
        AlertDialog.Builder(this)
            .setTitle(R.string.enter_code)
            .setView(input)
            .setPositiveButton(R.string.ok) { _, _ ->
                val code = input.text.toString().trim()
                if (code.length == 6) {
                    Presence.client.send("pair.enter") { put("code", code) }
                } else {
                    toast(R.string.enter_code_hint)
                }
            }
            .setNegativeButton(R.string.cancel, null)
            .show()
    }

    private fun requestNotificationPermissionIfNeeded(force: Boolean = false) {
        if (Build.VERSION.SDK_INT >= 33 &&
            (force || checkSelfPermission(Manifest.permission.POST_NOTIFICATIONS) !=
            android.content.pm.PackageManager.PERMISSION_GRANTED)
        ) {
            notifPermission.launch(Manifest.permission.POST_NOTIFICATIONS)
        }
    }

    private fun updateUi() {
        val paired = Prefs.paired(this)
        val sharing = ScreenShareService.isRunning()
        boxPairing.visibility = if (paired) View.GONE else View.VISIBLE
        boxReady.visibility = if (paired && !sharing) View.VISIBLE else View.GONE
        boxSharing.visibility = if (sharing) View.VISIBLE else View.GONE
        btnCall.text = if (outgoingPending) getString(R.string.btn_cancel_call) else getString(R.string.btn_call)
        tvStatus.setText(
            when {
                !paired -> R.string.status_unpaired
                outgoingPending -> R.string.calling
                peerOnline -> R.string.status_paired_online
                else -> R.string.status_paired_offline
            }
        )
        refreshPermissionRows()
    }

    private fun refreshPermissionRows() {
        val notifOk = androidx.core.app.NotificationManagerCompat.from(this).areNotificationsEnabled()
        val micOk = checkSelfPermission(Manifest.permission.RECORD_AUDIO) ==
            android.content.pm.PackageManager.PERMISSION_GRANTED
        val overlayOk = Settings.canDrawOverlays(this)
        bindPermRow(permNotification, R.id.permStateNotif, notifOk)
        bindPermRow(permMic, R.id.permStateMic, micOk)
        bindPermRow(permOverlay, R.id.permStateOverlay, overlayOk)
    }

    private fun bindPermRow(row: View, stateId: Int, ok: Boolean) {
        row.findViewById<TextView>(stateId).setText(
            if (ok) R.string.perm_ok else R.string.perm_todo
        )
        row.alpha = if (ok) 0.55f else 1f
    }

    /** 首次配对完成后按品牌弹出后台保活引导（国内 ROM 杀后台是呼叫可靠性的最大敌人） */
    private fun maybeShowRomGuide() {
        if (Prefs.romGuideShown(this)) return
        Prefs.setRomGuideShown(this, true)
        val msg = when (Build.MANUFACTURER.lowercase()) {
            "xiaomi" -> R.string.guide_xiaomi
            "huawei" -> R.string.guide_huawei
            else -> R.string.guide_generic
        }
        AlertDialog.Builder(this)
            .setTitle(R.string.guide_title)
            .setMessage(msg)
            .setPositiveButton(R.string.ok, null)
            .show()
    }

    override fun onResume() {
        super.onResume()
        // 从"安装未知应用"授权页返回后，继续刚才搁置的安装
        pendingInstallFile?.let { file ->
            if (Build.VERSION.SDK_INT < 26 || packageManager.canRequestPackageInstalls()) {
                pendingInstallFile = null
                doInstall(file)
            }
        }
        updateUi()
    }

    private fun toast(resId: Int) {
        Toast.makeText(this, resId, Toast.LENGTH_SHORT).show()
    }

}
