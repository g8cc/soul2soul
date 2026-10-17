package com.soul2soul.app

import android.app.Activity
import android.app.Application
import android.os.Bundle
import android.util.Log
import org.webrtc.EglBase
import org.webrtc.Logging
import org.webrtc.PeerConnectionFactory

class App : Application() {

    companion object {
        @JvmStatic
        lateinit var instance: App
            private set
    }

    /** 当前处于 resumed 态的 Activity 数：诊断上报用，判"会话出问题时用户是否在本 App 界面上" */
    @Volatile
    var resumedActivities = 0
        private set

    /** 全局共享的 EGL 上下文：屏幕采集/编码/渲染必须同源 */
    val eglBase: EglBase by lazy { EglBase.create() }

    override fun onCreate() {
        super.onCreate()
        instance = this
        registerActivityLifecycleCallbacks(object : ActivityLifecycleCallbacks {
            override fun onActivityResumed(activity: Activity) { resumedActivities++ }
            override fun onActivityPaused(activity: Activity) {
                resumedActivities = (resumedActivities - 1).coerceAtLeast(0)
            }

            override fun onActivityCreated(activity: Activity, savedInstanceState: Bundle?) {}
            override fun onActivityStarted(activity: Activity) {}
            override fun onActivityStopped(activity: Activity) {}
            override fun onActivitySaveInstanceState(activity: Activity, outState: Bundle) {}
            override fun onActivityDestroyed(activity: Activity) {}
        })
        PeerConnectionFactory.initialize(
            PeerConnectionFactory.InitializationOptions.builder(this)
                // 原生日志只透出错误级：SDP 解析失败等能直接从 logcat 定位
                .setInjectableLogger({ message, severity, tag ->
                    Log.w("WEBRTC-N", "$severity [$tag] $message")
                }, Logging.Severity.LS_ERROR)
                .setEnableInternalTracer(false)
                .createInitializationOptions()
        )
    }
}
