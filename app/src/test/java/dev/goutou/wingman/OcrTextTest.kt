package dev.goutou.wingman

import dev.goutou.wingman.ocr.ocrText
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 图片识别结果的清洗（纯函数）。
 *
 * 输入形状照着 ML Kit 还回来的「一行行文字」来 —— 这段逻辑在真机上没法调
 * （改一次要发版，还得等聊天里正好出现一张图），所以规则必须在这里钉住。
 */
class OcrTextTest {

    @Test
    fun `去掉空行和每行首尾空白`() {
        assertEquals("你好\n在吗", ocrText(listOf("  你好 ", "", "   ", "在吗  ")))
    }

    @Test
    fun `行间保留换行`() {
        // 长图 / 聊天截图的排版信息对模型有用（谁跟谁说话），压成一坨反而看不懂
        assertEquals("第一条\n第二条", ocrText(listOf("第一条", "第二条")))
    }

    @Test
    fun `什么都没认出来就是空串`() {
        assertEquals("", ocrText(emptyList()))
        assertEquals("", ocrText(listOf("", "   ")))
    }

    @Test
    fun `超长时首尾都留，不是只留开头`() {
        // issue #7②：聊天截图里最新那条在底部，只截开头会把最该给模型看的内容丢掉。
        // 所以截断改成「开头 1/3 + 省略说明 + 结尾 2/3」。
        val body = "头".repeat(30) + "尾".repeat(30)
        val out = ocrText(listOf(body), max = 12)
        assertTrue("要保留开头（给上下文）: $out", out.startsWith("头"))
        assertTrue("要保留结尾（最新内容）: $out", out.endsWith("尾"))
        assertTrue("要写清中间省略了多少: $out", out.contains("省略"))
    }

    @Test
    fun `内容没超上限就原样返回，不加省略说明`() {
        assertEquals("啊啊啊", ocrText(listOf("啊啊啊"), max = 10))
    }

    @Test
    fun `max 传 0 表示不截断`() {
        val long = "啊".repeat(500)
        assertEquals(long, ocrText(listOf(long), max = 0))
    }
}
