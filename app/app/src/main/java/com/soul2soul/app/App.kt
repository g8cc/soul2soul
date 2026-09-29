package com.soul2soul.app

import android.app.Application
import org.webrtc.EglBase
import org.webrtc.PeerConnectionFactory

class App : Application() {

    companion object {
        @JvmStatic
        lateinit var instance: App
            private set
    }

    /** 全局共享的 EGL 上下文：屏幕采集/编码/渲染必须同源 */
    val eglBase: EglBase by lazy { EglBase.create() }

    override fun onCreate() {
        super.onCreate()
        instance = this
        PeerConnectionFactory.initialize(
            PeerConnectionFactory.InitializationOptions.builder(this)
                .setEnableInternalTracer(false)
                .createInitializationOptions()
        )
    }
}
