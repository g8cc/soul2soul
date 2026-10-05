package com.soul2soul.app.session

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class StrokeMappingTest {

    @Test
    fun `同宽高比时视频铺满容器`() {
        val r = StrokeMapping.fitRect(1080, 2340, 1080f, 2340f)
        assertEquals(0f, r[0], 0.01f)
        assertEquals(0f, r[1], 0.01f)
        assertEquals(1080f, r[2], 0.01f)
        assertEquals(2340f, r[3], 0.01f)
    }

    @Test
    fun `竖长视频在方形容器内左右留黑边`() {
        // 视频 9:16（aspect 0.5625），容器 1:1 → 视频铺满高度，左右留边
        val r = StrokeMapping.fitRect(900, 1600, 1000f, 1000f)
        assertEquals(218.75f, r[0], 0.01f)
        assertEquals(0f, r[1], 0.01f)
        assertEquals(781.25f, r[2], 0.01f)
        assertEquals(1000f, r[3], 0.01f)
    }

    @Test
    fun `横屏视频在竖屏容器内上下贴边左右留黑`() {
        // 视频 16:9，容器 9:16
        val r = StrokeMapping.fitRect(1920, 1080, 900f, 1600f)
        // videoAspect(1.78) > viewAspect(0.5625) → 宽铺满，高 = 900/1.78 = 506
        assertEquals(0f, r[0], 0.01f)
        assertEquals((1600f - 506.25f) / 2f, r[1], 0.1f)
        assertEquals(900f, r[2], 0.01f)
        assertEquals((1600f + 506.25f) / 2f, r[3], 0.1f)
    }

    @Test
    fun `零尺寸输入回退为全容器`() {
        val r = StrokeMapping.fitRect(0, 100, 500f, 500f)
        assertTrue(r.contentEquals(floatArrayOf(0f, 0f, 500f, 500f)))
    }

    @Test
    fun `归一化往返一致_两端分辨率无关`() {
        // 观看端 1080x2340 视口看 720x1280 视频；共享端屏幕 1220x2712（华为典型）
        val videoW = 720; val videoH = 1280
        val viewW = 1080f; val viewH = 2340f
        val screenW = 1220f; val screenH = 2712f
        // 观看端点视频正中心
        val touchX = viewW / 2f; val touchY = viewH / 2f
        val (xn, yn) = StrokeMapping.normalize(touchX, touchY, videoW, videoH, viewW, viewH)
        assertEquals(0.5f, xn, 0.001f)
        assertEquals(0.5f, yn, 0.001f)
        val (px, py) = StrokeMapping.denormalize(xn, yn, screenW, screenH)
        assertEquals(screenW / 2f, px, 0.01f)
        assertEquals(screenH / 2f, py, 0.01f)
    }

    @Test
    fun `letterbox 外的点击被钳制到边缘`() {
        // 横屏视频在竖屏容器中，点击顶部黑边区域
        val (xn, yn) = StrokeMapping.normalize(450f, 100f, 1920, 1080, 900f, 1600f)
        assertTrue(yn >= 0f)
        assertTrue(StrokeMapping.withinTolerance(xn to yn, 0.5f to 0f, 0.06f))
    }

    @Test
    fun `角落触点还原到共享端对应角落`() {
        val (xn, yn) = StrokeMapping.normalize(1079f, 2339f, 720, 1280, 1080f, 2340f)
        assertEquals(1f, xn, 0.01f)
        assertEquals(1f, yn, 0.01f)
        val (px, py) = StrokeMapping.denormalize(xn, yn, 1080f, 2340f)
        assertTrue(StrokeMapping.withinTolerance(px / 1080f to py / 2340f, 1f to 1f))
    }

    @Test
    fun `视频高为0同样回退全容器`() {
        val r = StrokeMapping.fitRect(100, 0, 500f, 500f)
        assertTrue(r.contentEquals(floatArrayOf(0f, 0f, 500f, 500f)))
    }

    @Test
    fun `容差判定恰5%为真_超出即假`() {
        // AC-3 验收线：<=0.05 通过、0.051 拒绝（起点取 0 保证单精度减法不引入舍入歧义）
        assertTrue(StrokeMapping.withinTolerance(0f to 0f, 0.05f to 0f))
        assertTrue(!StrokeMapping.withinTolerance(0f to 0f, 0.051f to 0f))
    }
}
