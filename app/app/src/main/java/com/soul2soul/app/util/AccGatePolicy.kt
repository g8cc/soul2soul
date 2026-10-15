package com.soul2soul.app.util

/**
 * 无障碍"被重置"提示决策（纯逻辑，JVM 锁定 v0.2.36 踩坑）：
 * 旧判定只看系统绑定的服务列表，MIUI 升级安装后开关登记还在、服务被解绑，
 * 导致每次打开 App 都误报"权限被重置"。注册串与绑定态要分开处理。
 */
internal object AccGatePolicy {
    enum class AccPrompt { NONE, RESET, FROZEN }

    /**
     * @param everEnabled 历史上开启过无障碍（用户不是新手，不该打扰）
     * @param registered  Settings.Secure 开关注册串里还有我们
     * @param bound       系统当前真的绑定了服务（功能可用）
     * @param prompted    本次冷启动是否已经弹过（两种弹窗共享一次额度，不骚扰）
     */
    fun prompt(
        everEnabled: Boolean,
        registered: Boolean,
        bound: Boolean,
        prompted: Boolean,
    ): AccPrompt = when {
        bound || !everEnabled || prompted -> AccPrompt.NONE
        !registered -> AccPrompt.RESET   // 开关真被关了：一键重开
        else -> AccPrompt.FROZEN         // 开关还开着但服务被冻：教用户关→开一次
    }
}
