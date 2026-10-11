package dev.goutou.wingman

import dev.goutou.wingman.wechat.TitleCandidateSpec
import dev.goutou.wingman.wechat.chatTitleScore
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Test

/**
 * 会话标题识别（issue #6）。
 *
 * 这一条钉的是一个**已经上线过的 bug**：备注是「单个字」的会话（张 / 王 / 李…），
 * 它的标题控件只有一个字宽，被宽度下限判掉 → 标题永远读不出来 →
 * 上层只好沿用上一个会话的名字 → 用户看到的是「换了窗口还在读上一个窗口的内容」。
 */
class ChatTitleScoreTest {

    private val screenW = 1080
    private val bandTop = 0
    private val bandBottom = 300

    /** 一个居中、一个字宽、正常字号的标题控件（density 2.75 下约 47px 宽）。 */
    private fun singleChar(width: Int = 47, density: Float = 2.75f) = chatTitleScore(
        TitleCandidateSpec(
            text = "张",
            width = width,
            height = 56,
            x = (screenW - width) / 2,
            y = 100,
            textSize = 46f,
        ),
        screenW, density, bandTop, bandBottom,
    )

    @Test
    fun `单字备注必须能被选出来`() {
        // 旧规则是 dp(24)：2.75 密度下要 66px，而单字只有约 47px —— 于是永远选不出标题
        assertNotNull(singleChar())
    }

    @Test
    fun `高密度机型上的单字也要能选出来`() {
        assertNotNull(singleChar(width = 60, density = 3.5f))
    }

    @Test
    fun `真正的图标还是不要`() {
        // dp(14) 在 2.75 密度下是 38px —— 比它还窄的不可能是标题
        assertNull(singleChar(width = 30))
    }

    @Test
    fun `不居中的不算标题`() {
        val left = chatTitleScore(
            TitleCandidateSpec("张", 47, 56, x = 40, y = 100, textSize = 46f),
            screenW, 2.75f, bandTop, bandBottom,
        )
        assertNull(left)
    }

    @Test
    fun `纯 UI 文案与超长文本照旧被拒`() {
        val ui = chatTitleScore(
            TitleCandidateSpec("返回", 200, 56, x = 440, y = 100, textSize = 46f),
            screenW, 2.75f, bandTop, bandBottom,
        )
        assertNull(ui)
        val long = chatTitleScore(
            TitleCandidateSpec("啊".repeat(33), 900, 56, x = 90, y = 100, textSize = 46f),
            screenW, 2.75f, bandTop, bandBottom,
        )
        assertNull(long)
    }

    @Test
    fun `被点名要避开的文本不算标题`() {
        val avoided = chatTitleScore(
            TitleCandidateSpec("张", 47, 56, x = (screenW - 47) / 2, y = 100, textSize = 46f),
            screenW, 2.75f, bandTop, bandBottom, avoidTexts = setOf("张"),
        )
        assertNull(avoided)
    }
}
