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
        private const val MEDIA_CONNECT_TIMEOUT_MS = 45_000L

        @Volatile var outgoingPending = false
        @Volatile var acceptedReceived = false
        var projectionData: Intent? = null

        fun cancelOutgoing() {
            outgoingPending = false
            acceptedReceived = false
            projectionData = null // 呼叫撤销/超时后旧授权一并作废
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
    private lateinit var permAccessibility: View

    private val projectionLauncher =
        registerForActivityResult(ActivityResultContracts.StartActivityForResult()) { result ->
            val data = result.data
            if (result.resultCode == Activity.RESULT_OK && data != null) {
                // 用户可能在系统授权页期间点了取消，或主叫等待超时；不要让迟到的授权结果重新发起邀请。
                if (!outgoingPending) {
                    projectionData = null
                    updateUi()
                } else {
                    // 授权成功 → 此时才发邀请；对方接听后 accepted 到达即启动共享服务
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
                }
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
        permAccessibility = findViewById(R.id.permAccessibility)
        findViewById<TextView>(R.id.tvAppVersion).text =
            "v${BuildConfig.VERSION_NAME} (${com.soul2soul.app.util.Updater.localVersionCode(this)})"

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
        // 更新检查统一在 onResume（冷启动也会走到）
        permNotification.setOnClickListener { requestNotificationPermissionIfNeeded(force = true) }
        permMic.setOnClickListener {
            micPermission.launch(Manifest.permission.RECORD_AUDIO)
        }
        permOverlay.setOnClickListener { openOverlayPermissionPage() }
        permAccessibility.setOnClickListener {
            runCatching {
                startActivity(Intent(Settings.ACTION_ACCESSIBILITY_SETTINGS))
            }.onFailure {
                toast(R.string.ctl_need_acc)
            }
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
                if (!outgoingPending) return
                // 对方接听：屏幕授权已在邀请前完成，此刻直接启动共享服务（offer 即刻发出）
                callTimeout.removeCallbacksAndMessages(null)
                acceptedReceived = true
                Presence.iceServersJson = json.optJSONArray("iceServers")
                // 录屏授权 token 即用即清：不留驻静态字段（异常时序也不复用旧授权）
                projectionData?.let {
                    ScreenShareService.start(this, it)
                    projectionData = null
                }
                // accepted 只代表对方点击了接听；媒体仍可能卡在授权、SDP 或 ICE。
                // 给主叫端也留一个总出口，避免共享服务异常时主页永久显示“呼叫中”。
                callTimeout.postDelayed({
                    if (outgoingPending && acceptedReceived) {
                        if (ScreenShareService.isLive()) {
                            // 主页可能在后台错过 local.sessionLive；服务状态才是权威来源。
                            cancelOutgoing()
                            updateUi()
                        } else {
                            Presence.client.send("bye")
                            cancelOutgoing()
                            toast(R.string.connect_timeout)
                            updateUi()
                        }
                    }
                }, MEDIA_CONNECT_TIMEOUT_MS)
            }
            "declined" -> {
                callTimeout.removeCallbacksAndMessages(null)
                cancelOutgoing()
                toast(R.string.peer_declined)
                updateUi()
            }
            "bye" -> {
                // 对端在未接听前就结束（旧版本秒挂/异常退出兜底）：主叫端立即复位，
                // 否则界面停留在"呼叫中"，只能干等 45s 超时
                if (outgoingPending && !acceptedReceived) {
                    callTimeout.removeCallbacksAndMessages(null)
                    cancelOutgoing()
                    toast(R.string.peer_ended_call)
                    updateUi()
                }
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
            "local.sessionLive" -> {
                // ICE 真正连通后取消媒体建立兜底；会话状态仍由共享服务持有。
                callTimeout.removeCallbacksAndMessages(null)
                updateUi()
            }
        }
    }

    /** 悬浮窗权限页：MIUI/华为有专属逐应用权限页（直达开关），其他品牌走标准页 */
    private fun openOverlayPermissionPage() {
        val brand = (Build.BRAND + " " + Build.MANUFACTURER).lowercase()
        val miui = Intent().setClassName(
            "com.miui.securitycenter",
            "com.miui.permcenter.permissions.PermissionsEditorActivity"
        ).putExtra("extra_pkgname", packageName)
        val huawei = Intent().setClassName(
            "com.huawei.systemmanager",
            "com.huawei.permissionmanager.ui.SinglePermissionActivity"
        ).putExtra("permission", "android.permission.SYSTEM_ALERT_WINDOW")
        when {
            brand.contains("xiaomi") || brand.contains("redmi") || brand.contains("poco") ->
                runCatching { startActivity(miui) }.onFailure { fallBackOverlay() }
            brand.contains("huawei") || brand.contains("honor") ->
                runCatching { startActivity(huawei) }.onFailure { fallBackOverlay() }
            else -> fallBackOverlay()
        }
    }

    private fun fallBackOverlay() {
        startActivity(
            Intent(
                Settings.ACTION_MANAGE_OVERLAY_PERMISSION,
                Uri.parse("package:$packageName")
            )
        )
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
            openOverlayPermissionPage()
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
            val local = com.soul2soul.app.util.Updater.localVersionCode(this@MainActivity)
            Log.d(TAG, "update check: remote=${info.versionCode} local=$local")
            reportVersionDiag(info, local)
            val btn = findViewById<TextView>(R.id.btnUpdate)
            if (info.versionCode > local) {
                pendingUpdateInfo = info
                btn.visibility = View.VISIBLE
                btn.text = getString(R.string.update_available_short) + " v" + info.versionName +
                    "（本机 $local）"
            } else {
                // 无更新必须撤掉提示：否则安装成功后旧进程/后续场景里按钮永久残留
                pendingUpdateInfo = null
                btn.visibility = View.GONE
            }
        }
    }

    /**
     * 更新判定自检上报信令服务器（服务端 [diag.version] 日志落盘）。
     * 用于诊断"系统设置版本 / 运行进程版本 / 服务器清单版本"三者错位——设备侧无需肉眼读数。
     */
    private fun reportVersionDiag(info: com.soul2soul.app.util.Updater.Info, local: Int) {
        val send = Runnable {
            if (Presence.client.isConnected) {
                Presence.client.send("app.version") {
                    put("localCode", local)
                    put("localName", BuildConfig.VERSION_NAME)
                    put("remoteCode", info.versionCode)
                    put("remoteName", info.versionName)
                    put("hasUpdate", info.versionCode > local)
                }
            }
        }
        if (Presence.client.isConnected) send.run()
        else findViewById<View>(R.id.tvAppVersion).postDelayed(send, 8000)
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
        val accOk = isAppAccessibilityEnabled()
        bindPermRow(permNotification, R.id.permStateNotif, notifOk)
        bindPermRow(permMic, R.id.permStateMic, micOk)
        bindPermRow(permOverlay, R.id.permStateOverlay, overlayOk)
        bindPermRow(permAccessibility, R.id.permStateAcc, accOk)
    }

    /** 无障碍服务是否已被用户手动开启（无法程序化授权，只能引导） */
    private fun isAppAccessibilityEnabled(): Boolean {
        val am = getSystemService(Context.ACCESSIBILITY_SERVICE) as
            android.view.accessibility.AccessibilityManager
        return am.getEnabledAccessibilityServiceList(
            android.accessibilityservice.AccessibilityServiceInfo.FEEDBACK_ALL_MASK
        ).any { it.resolveInfo.serviceInfo.packageName == packageName }
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

    private var lastUpdateCheckAt = 0L

    override fun onResume() {
        super.onResume()
        // 从"安装未知应用"授权页返回后，继续刚才搁置的安装
        pendingInstallFile?.let { file ->
            if (Build.VERSION.SDK_INT < 26 || packageManager.canRequestPackageInstalls()) {
                pendingInstallFile = null
                doInstall(file)
            }
        }
        // 进程常驻时 onCreate 不再触发：回前台补查更新（10 分钟节流，避免频繁打服务器）
        if (android.os.SystemClock.elapsedRealtime() - lastUpdateCheckAt > 10 * 60 * 1000L) {
            lastUpdateCheckAt = android.os.SystemClock.elapsedRealtime()
            checkForUpdate()
        }
        updateUi()
    }

    private fun toast(resId: Int) {
        Toast.makeText(this, resId, Toast.LENGTH_SHORT).show()
    }

}
