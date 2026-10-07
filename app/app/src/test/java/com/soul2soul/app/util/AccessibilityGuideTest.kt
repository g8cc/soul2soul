package com.soul2soul.app.util

import com.soul2soul.app.R
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/** 授权引导决策纯逻辑：直达最终页的版本分界 + 品牌路径文案选择 */
class AccessibilityGuideTest {

    @Test
    fun detailsPage_boundary29_30() {
        assertFalse(AccessibilityGuide.useDetailsPage(29)) // 无障碍 10：没有逐应用详情页
        assertTrue(AccessibilityGuide.useDetailsPage(30))
        assertTrue(AccessibilityGuide.useDetailsPage(35))
    }

    @Test
    fun pathResId_brandMapping_caseInsensitive() {
        assertEquals(R.string.acc_guide_miui, AccessibilityGuide.pathResId("Xiaomi MI 5"))
        assertEquals(R.string.acc_guide_miui, AccessibilityGuide.pathResId("Redmi K60 xiaomi"))
        assertEquals(R.string.acc_guide_miui, AccessibilityGuide.pathResId("POCO F3 poco"))
        assertEquals(R.string.acc_guide_huawei, AccessibilityGuide.pathResId("HUAWEI P60"))
        assertEquals(R.string.acc_guide_huawei, AccessibilityGuide.pathResId("Honor 9 honor"))
        assertEquals(R.string.acc_guide_generic, AccessibilityGuide.pathResId("samsung S23 samsung"))
        assertEquals(R.string.acc_guide_generic, AccessibilityGuide.pathResId("Google Pixel"))
    }
}
