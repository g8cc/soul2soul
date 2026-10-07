package com.soul2soul.app.session

import android.content.Context
import android.media.AudioDeviceInfo
import android.media.AudioManager
import android.os.Build
import android.util.Log

/**
 * 管理一场 WebRTC 会话占用的系统音频路由。
 *
 * 路由必须在创建 AudioDeviceModule 之前稳定下来，否则从听筒切到扬声器时，
 * 播放延迟和 AEC 参考信号会同时突变，部分 MIUI 设备会产生短促爆音/回声尾音。
 * 结束时反向执行：先由调用方销毁 WebRTC 音轨，再恢复进入会话前的系统状态。
 */
class AudioRouteController(context: Context) {
    private val audioManager =
        context.applicationContext.getSystemService(Context.AUDIO_SERVICE) as AudioManager

    private var active = false
    private var previousMode = AudioManager.MODE_NORMAL
    private var previousSpeakerphone = false

    fun start() {
        if (active) return
        active = true
        previousMode = audioManager.mode
        @Suppress("DEPRECATION")
        previousSpeakerphone = audioManager.isSpeakerphoneOn

        runCatching {
            audioManager.mode = AudioManager.MODE_IN_COMMUNICATION
            val routedByDeviceApi = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
                val speaker = audioManager.availableCommunicationDevices.firstOrNull {
                    it.type == AudioDeviceInfo.TYPE_BUILTIN_SPEAKER ||
                        it.type == AudioDeviceInfo.TYPE_BUILTIN_SPEAKER_SAFE
                }
                speaker != null && audioManager.setCommunicationDevice(speaker)
            } else {
                false
            }
            if (!routedByDeviceApi) {
                @Suppress("DEPRECATION")
                audioManager.isSpeakerphoneOn = true
            }
            Log.i(
                TAG,
                "start sdk=${Build.VERSION.SDK_INT} mode=${audioManager.mode} " +
                    "speaker=${isSpeakerphoneOn()} deviceApi=$routedByDeviceApi",
            )
        }.onFailure { Log.w(TAG, "start failed", it) }
    }

    /** 仅在 WebRTC 音轨、AudioDeviceModule 都已释放后调用。 */
    fun stop() {
        if (!active) return
        active = false
        runCatching {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
                audioManager.clearCommunicationDevice()
            }
            @Suppress("DEPRECATION")
            audioManager.isSpeakerphoneOn = previousSpeakerphone
            audioManager.mode = previousMode
            Log.i(
                TAG,
                "stop restoreMode=$previousMode restoreSpeaker=$previousSpeakerphone",
            )
        }.onFailure { Log.w(TAG, "stop failed", it) }
    }

    @Suppress("DEPRECATION")
    private fun isSpeakerphoneOn(): Boolean = audioManager.isSpeakerphoneOn

    private companion object {
        const val TAG = "S2S-AudioRoute"
    }
}
