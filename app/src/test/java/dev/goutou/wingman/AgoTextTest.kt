package dev.goutou.wingman

import dev.goutou.wingman.ui.agoText
import org.junit.Assert.assertEquals
import org.junit.Test

/**
 * 「多久以前」那句话（设置页的代理保活诊断用）。
 *
 * 它要回答的是「服务是刚起来的、还是一直活着」—— 粒度到秒没有意义，
 * 反而是「刚刚 / 2 分钟前 / 1 小时前」这种一眼能读。分档边界钉在这里。
 */
class AgoTextTest {

    private val now = 1_700_000_000_000L

    @Test
    fun `从来没有过就是这四个字`() {
        assertEquals("从来没有过", agoText(0L, now))
        assertEquals("从来没有过", agoText(-1L, now))
    }

    @Test
    fun `按秒 分 时 天分档`() {
        assertEquals("刚刚", agoText(now - 2_000L, now))
        assertEquals("30 秒前", agoText(now - 30_000L, now))
        assertEquals("2 分钟前", agoText(now - 120_000L, now))
        assertEquals("3 小时前", agoText(now - 3L * 3_600_000L, now))
        assertEquals("2 天前", agoText(now - 2L * 86_400_000L, now))
    }

    @Test
    fun `时钟回拨也不会说出负数`() {
        assertEquals("刚刚", agoText(now + 60_000L, now))
    }
}
