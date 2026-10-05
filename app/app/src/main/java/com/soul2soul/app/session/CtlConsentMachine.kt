package com.soul2soul.app.session

import com.soul2soul.app.R

/**
 * 「允许TA操控」授权状态机的纯逻辑层（假钟注入，JVM 可单测）。
 * 自 ScreenShareService 逐字提取：toggle/set、锁屏收回、乐观写回滚、
 * 3s toast / 4s ctl_denied 节流、入队竞态双判、新会话归零。
 * 状态机只产出 Effect 列表，Toast/发送/通知栏刷新由 Service 执行。
 */
class CtlConsentMachine(private val clock: () -> Long) {

    sealed class Effect {
        data class Toast(val res: Int) : Effect()
        object DeniedNotice : Effect()        // ctl_denied 回灌观看端（已过 4s 节流判定）
        object DeniedScreenOff : Effect()     // 锁屏收回专用：带 reason=screenoff，不节流
        object RefreshNotification : Effect()
    }

    sealed class Decision {
        object Allow : Decision()
        data class Deny(val effects: List<Effect>) : Decision()
    }

    @Volatile var allowed = false
        private set

    @Volatile private var lastWarnAt = 0L
    @Volatile private var lastDenyAt = 0L

    /** 通知栏「允许/不允许TA操控」按钮 */
    fun toggle(accReady: Boolean): List<Effect> {
        if (!allowed && !accReady) return toastEffects(R.string.ctl_need_acc)
        allowed = !allowed
        val t = toastEffects(if (allowed) R.string.ctl_enabled_toast else R.string.ctl_disabled_toast)
        val d = if (!allowed) deniedEffects() else emptyList()
        return t + d + listOf(Effect.RefreshNotification)
    }

    /** 授权对话框的确定性开关（比 toggle 更适合"当前状态→目标状态"） */
    fun setTarget(on: Boolean, accReady: Boolean): List<Effect> {
        if (on && !accReady) {
            allowed = false // 对话框乐观置了 true：服务拒绝就必须落回，否则界面显示"已允许"而实际不能注入
            return toastEffects(R.string.ctl_need_acc)
        }
        allowed = on
        val t = toastEffects(if (on) R.string.ctl_enabled_toast else R.string.ctl_disabled_toast)
        val d = if (!on) deniedEffects() else emptyList()
        return t + d + listOf(Effect.RefreshNotification)
    }

    /** 锁屏收回：清醒时给的授权不跨锁屏存续，解锁后需重新授权 */
    fun revokeOnScreenOff(): List<Effect> {
        if (!allowed) return emptyList() // 本来就未授权：不重复发 ctl_denied
        allowed = false
        return listOf(Effect.RefreshNotification, Effect.DeniedScreenOff)
    }

    /** 手势第一道判（WebRTC signaling 线程）：静默丢弃会让观看端误以为"操控坏了"，必须回一条被拒事件 */
    fun heldCheck(): Decision =
        if (allowed) Decision.Allow else Decision.Deny(deniedEffects())

    /** 注入前第二道判（主线程）：收回授权可能发生在入队之后，必须以最新状态再判一次 */
    fun preInjectCheck(accReady: Boolean): Decision {
        if (!allowed) return Decision.Deny(deniedEffects())
        if (!accReady) {
            allowed = false
            return Decision.Deny(toastEffects(R.string.ctl_need_acc) + deniedEffects())
        }
        return Decision.Allow
    }

    /** 对话框同进程乐观写（界面立刻正确，服务侧 setTarget 做同一件事幂等） */
    fun optimisticSet(on: Boolean) {
        allowed = on
    }

    /** 会话级归零：每次新会话从零开始收授权，上一通话的授权绝不能带进这一通 */
    fun resetForNewSession() {
        allowed = false
    }

    /** 被拒事件回灌观看端（4s 节流）：ctl 可靠通道，观看端在操控横幅上给出原因 */
    private fun deniedEffects(): List<Effect> {
        val now = clock()
        if (now - lastDenyAt < DENY_THROTTLE_MS) return emptyList()
        lastDenyAt = now
        return listOf(Effect.DeniedNotice)
    }

    private fun toastEffects(res: Int): List<Effect> {
        val now = clock()
        if (now - lastWarnAt < TOAST_THROTTLE_MS) return emptyList() // 手势连发时别把屏幕糊满 toast
        lastWarnAt = now
        return listOf(Effect.Toast(res))
    }

    companion object {
        const val TOAST_THROTTLE_MS = 3000L
        const val DENY_THROTTLE_MS = 4000L
    }
}
