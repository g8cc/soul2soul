package com.soul2soul.app.util

import org.junit.Assert.assertEquals
import org.junit.Test

/**
 * v0.2.36 真机踩坑锁定：MIUI 升级安装后无障碍"注册串在、服务被解绑"，
 * 旧判定每次都误报"权限被重置"。两种坏法要分开处置，且每次冷启动最多弹一个。
 */
class AccGatePolicyTest {
    

    @Test fun `服务正常绑定时不打扰`() {
        assertEquals(AccGatePolicy.AccPrompt.NONE, AccGatePolicy.prompt(everEnabled = true, registered = true, bound = true, prompted = false))
    }

    @Test fun `从没开过无障碍的新手不弹重置`() {
        assertEquals(AccGatePolicy.AccPrompt.NONE, AccGatePolicy.prompt(everEnabled = false, registered = false, bound = false, prompted = false))
    }

    @Test fun `已弹过一次就不再骚扰`() {
        assertEquals(AccGatePolicy.AccPrompt.NONE, AccGatePolicy.prompt(everEnabled = true, registered = false, bound = false, prompted = true))
        assertEquals(AccGatePolicy.AccPrompt.NONE, AccGatePolicy.prompt(everEnabled = true, registered = true, bound = false, prompted = true))
    }

    @Test fun `开关真被关掉时提示一键重开`() {
        assertEquals(AccGatePolicy.AccPrompt.RESET, AccGatePolicy.prompt(everEnabled = true, registered = false, bound = false, prompted = false))
    }

    @Test fun `开关还在但服务被冻时只教关开到一次`() {
        assertEquals(AccGatePolicy.AccPrompt.FROZEN, AccGatePolicy.prompt(everEnabled = true, registered = true, bound = false, prompted = false))
    }

    @Test fun `被冻优先于误判绑定 只要没绑上且串还在就是FROZEN`() {
        // registered=true + bound=false 是 v0.2.36 Mi10 实测态（settings 有、services:{}）
        assertEquals(AccGatePolicy.AccPrompt.FROZEN, AccGatePolicy.prompt(everEnabled = true, registered = true, bound = false, prompted = false))
    }
}
