package dev.goutou.wingman

import dev.goutou.wingman.ui.DampedSpring
import dev.goutou.wingman.ui.GlassMotionFrame
import dev.goutou.wingman.ui.LiquidGlassMotion
import dev.goutou.wingman.ui.glassTransformOf
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import kotlin.math.abs

/**
 * 液态玻璃的弹簧与形变公式（Salt Player m50 / vq1 / C0071 case 8 的移植）。
 * 都是纯函数 / 无 Android 依赖，直接对数值断言。
 */
class LiquidGlassMotionTest {

    @Test
    fun `弹簧收敛到目标且停得住`() {
        val s = DampedSpring()
        s.target = 1f
        repeat(120) { s.step(1f / 60f, 24f, 0.52f) }
        assertEquals(1f, s.value, 1e-3f)
        assertEquals(0f, s.velocity, 0.01f)
    }

    @Test
    fun `松手后明显欠阻尼 会过冲`() {
        val s = DampedSpring()
        s.target = 1f
        repeat(30) { s.step(1f / 60f, 42f, 0.82f) }   // 按住：接近 1
        s.target = 0f
        var overshoot = false
        repeat(120) {
            s.step(1f / 60f, 24f, 0.52f)               // 松手参数
            if (s.value < 0f) overshoot = true
        }
        assertTrue("ζ=0.52 的松手回弹应当过冲到负值", overshoot)
        assertEquals(0f, s.value, 1e-3f)
    }

    @Test
    fun `轻触松手踢一脚速度 弹得更`() {
        // 同样的按压时长：release(dragged=false) 应当让 press 弹簧速度 +7（vq1.m7901）。
        // 42Hz 的按压弹簧一帧（60fps）就能到 ~0.167，恰好越过 Salt 的 0.15 阈值 ——
        // 所以用半帧步长模拟「极快的轻点」，press 停在阈值之下
        val plain = LiquidGlassMotion()
        val flick = LiquidGlassMotion()
        plain.down(0.5f, 0.5f); flick.down(0.5f, 0.5f)
        plain.advance(1f / 120f); flick.advance(1f / 120f)
        plain.release(dragged = true)
        flick.release(dragged = false)
        val a = plain.advance(1f / 60f)
        val b = flick.advance(1f / 60f)
        // vq1.m7901 的 +7 是正速度：松手第一帧先朝按压方向冲一下，回弹因此更有劲
        assertTrue("踢过速度的那一路第一帧应该冲得更高", b.press > a.press)
    }

    @Test
    fun `形变公式与 C0071 数值一致`() {
        // ox=1, press=1, w=400, h=200：
        // scaleX = 1 + (0.025*1 + 0.075*1)*200/400 = 1.05
        // scaleY = 1 + 0.06*1 - 0.012*1        = 1.048（横拖：横胖竖瘦）
        // translationX = 1*200*0.13            = 26
        val t = glassTransformOf(GlassMotionFrame(press = 1f, offsetX = 1f), w = 400f, h = 200f)
        assertEquals(1.05f, t.scaleX, 1e-4f)
        assertEquals(1.048f, t.scaleY, 1e-4f)
        assertEquals(26f, t.translationX, 1e-4f)
        assertEquals(0f, t.translationY, 1e-4f)
        // 锚点 = 触点（默认 0.5/0.5 → 面板中心）
        assertEquals(200f, t.pivotX, 1e-4f)
        assertEquals(100f, t.pivotY, 1e-4f)
    }

    @Test
    fun `map 是围绕锚点的缩放加平移`() {
        val t = dev.goutou.wingman.ui.GlassTransform(
            pivotX = 100f, pivotY = 50f, scaleX = 2f, scaleY = 2f,
            translationX = 10f, translationY = 5f,
        )
        // 锚点不动：map(100,50) = (100,50) + (10,5)
        val pivot = t.map(100f, 50f)
        assertEquals(110f, pivot.x, 1e-4f)
        assertEquals(55f, pivot.y, 1e-4f)
        // 距锚点 (50,0) 的点：缩放后 (100,0) 再加平移
        val other = t.map(150f, 50f)
        assertEquals(210f, other.x, 1e-4f)
        assertEquals(55f, other.y, 1e-4f)
    }

    @Test
    fun `拖动量被限制在正负一`() {
        val m = LiquidGlassMotion()
        m.down(0.5f, 0.5f)
        m.move(0.5f, 0.5f, dragX = 9f, dragY = -9f)
        repeat(600) { m.advance(1f / 60f) }
        val frame = m.advance(1f / 60f)
        assertTrue(frame.offsetX <= 1f && frame.offsetX >= -1f)
        assertTrue(abs(frame.offsetY) <= 1f)
    }
}
