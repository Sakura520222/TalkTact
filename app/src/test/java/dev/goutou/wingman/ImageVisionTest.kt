package dev.goutou.wingman

import dev.goutou.wingman.config.IMAGE_MODE_OCR
import dev.goutou.wingman.config.IMAGE_MODE_OCR_THEN_VISION
import dev.goutou.wingman.config.IMAGE_MODE_VISION
import dev.goutou.wingman.llm.Json
import dev.goutou.wingman.llm.JsonValue
import dev.goutou.wingman.llm.anthropicContent
import dev.goutou.wingman.llm.base64Jpeg
import dev.goutou.wingman.llm.jpegDataUrl
import dev.goutou.wingman.llm.pickImageForVision
import dev.goutou.wingman.llm.responsesInput
import dev.goutou.wingman.llm.userContent
import dev.goutou.wingman.wechat.ChatMsg
import dev.goutou.wingman.wechat.IMAGE_PREFIX
import dev.goutou.wingman.wechat.ImageCandidateSpec
import dev.goutou.wingman.wechat.isStickerAttachment
import dev.goutou.wingman.wechat.selectImageCandidate
import dev.goutou.wingman.wechat.shouldSendImage
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * issue #7②③「图片这条路」的纯逻辑。
 *
 * 这几件事都**没法在真机上反复试**（要发版，还要等聊天里正好出现对应的图），
 * 而它们的错法又特别隐蔽 —— 「发了图但模型看不见」和「该发的时候没发」在界面上长得一模一样。
 * 所以规则全部钉在这里。
 */
/** data URL 前缀。抽成常量：Kotlin 里它自己就含冒号，写在断言里容易看错。 */
private const val DATA_URL_PREFIX = "data:image/jpeg;base64,"

class ImageVisionTest {

    // ---------------- 表情包那一类占位 ----------------

    @Test
    fun `表情包占位被认出来`() {
        assertTrue(isStickerAttachment("[表情]"))
        assertTrue(isStickerAttachment("[动画表情]"))
        assertTrue(isStickerAttachment("  [表情]  "))
    }

    @Test
    fun `图片和普通文字不是表情包`() {
        assertFalse(isStickerAttachment("[图片]"))
        assertFalse(isStickerAttachment("[照片]"))
        assertFalse(isStickerAttachment("你好"))
        assertFalse(isStickerAttachment(""))
    }

    // ---------------- 三档策略 ----------------

    @Test
    fun `只认字档永远不直传`() {
        // 一个字的识别结果都没有也不发 —— 这一档的承诺就是「图不出手机」
        assertFalse(shouldSendImage(IMAGE_MODE_OCR, null))
        assertFalse(shouldSendImage(IMAGE_MODE_OCR, ""))
        assertFalse(shouldSendImage(IMAGE_MODE_OCR, "一大段认出来的字"))
    }

    @Test
    fun `认不出才发图档按识别结果判`() {
        assertTrue(shouldSendImage(IMAGE_MODE_OCR_THEN_VISION, null))
        assertTrue(shouldSendImage(IMAGE_MODE_OCR_THEN_VISION, ""))
        assertTrue(shouldSendImage(IMAGE_MODE_OCR_THEN_VISION, "   "))
        // 门槛是 12 个字：11 个字算「没读出什么」，12 个字算「够用了」
        assertTrue(shouldSendImage(IMAGE_MODE_OCR_THEN_VISION, "一".repeat(11)))
        assertFalse(shouldSendImage(IMAGE_MODE_OCR_THEN_VISION, "一".repeat(12)))
    }

    @Test
    fun `总是发图档不看识别结果`() {
        assertTrue(shouldSendImage(IMAGE_MODE_VISION, null))
        assertTrue(shouldSendImage(IMAGE_MODE_VISION, "一大段认出来的字"))
    }

    // ---------------- 这次请求带哪张图 ----------------

    private fun msg(text: String, image: ByteArray? = null): ChatMsg =
        ChatMsg(false, text).also { it.image = image }

    @Test
    fun `只认字档一张图都不带`() {
        assertNull(pickImageForVision(IMAGE_MODE_OCR, listOf(msg("[图片] ", byteArrayOf(1)))))
    }

    @Test
    fun `认不出才发图档：字够用就不发`() {
        val enough = msg(IMAGE_PREFIX + "这是一段足够长的识别结果，早就超过十二个字了", byteArrayOf(7))
        assertNull(pickImageForVision(IMAGE_MODE_OCR_THEN_VISION, listOf(enough)))
    }

    @Test
    fun `认不出才发图档：字太少就发`() {
        val poor = msg(IMAGE_PREFIX + "你好", byteArrayOf(7))
        assertArrayEquals(byteArrayOf(7), pickImageForVision(IMAGE_MODE_OCR_THEN_VISION, listOf(poor)))
    }

    @Test
    fun `表情包那种占位（没有图片前缀）一律当没认出来`() {
        assertArrayEquals(
            byteArrayOf(9),
            pickImageForVision(IMAGE_MODE_OCR_THEN_VISION, listOf(msg("[表情]", byteArrayOf(9)))),
        )
    }

    @Test
    fun `总是发图档只带最后一张`() {
        val first = msg("第一条", byteArrayOf(1))
        val last = msg("第二条", byteArrayOf(2))
        assertArrayEquals(byteArrayOf(2), pickImageForVision(IMAGE_MODE_VISION, listOf(first, last)))
    }

    @Test
    fun `一张图都没有就是 null`() {
        assertNull(pickImageForVision(IMAGE_MODE_VISION, listOf(msg("你好"), msg("在吗"))))
    }

    // ---------------- 请求体形状 ----------------

    @Test
    fun `没图时 content 仍然是纯字符串`() {
        // 老配置发出去的字节要和以前一模一样 —— 有些中转对 content 数组支持不好
        val v = userContent("你好", null)
        assertEquals("你好", (v as? JsonValue.Str)?.value)
    }

    @Test
    fun `有图时 OpenAI 形态是多模态数组`() {
        val body = Json.encode(userContent("看图", byteArrayOf(1, 2, 3)))
        assertTrue(body, body.startsWith("["))
        assertTrue(body, body.contains("image_url"))
        assertTrue(body, body.contains(DATA_URL_PREFIX + base64Jpeg(byteArrayOf(1, 2, 3))))
        assertTrue(body, body.contains(base64Jpeg(byteArrayOf(1, 2, 3))))
    }

    @Test
    fun `Anthropic 形态把 base64 拆成 media_type 与 data`() {
        val body = Json.encode(anthropicContent("看图", byteArrayOf(1, 2, 3)))
        assertTrue(body, body.contains("media_type"))
        assertTrue(body, body.contains("base64"))
        // Anthropic 走的是「独立 content 块 + media_type/data 两个字段」，不认 data URL 那个写法
        assertFalse("不该出现 data URL: " + body, body.contains(DATA_URL_PREFIX))
    }

    @Test
    fun `Responses 形态没图时仍是一句话`() {
        val v = responsesInput("你好", null)
        assertEquals("你好", (v as? JsonValue.Str)?.value)
    }

    @Test
    fun `data url 前缀正确`() {
        assertTrue(jpegDataUrl(byteArrayOf(1)), jpegDataUrl(byteArrayOf(1)).startsWith(DATA_URL_PREFIX))
    }

    // ---------------- 取图挑选：真位图优先 / 表情包放行 ----------------

    private fun cand(
        w: Int,
        h: Int,
        depth: Int = 1,
        leaf: Boolean = true,
        avatar: Boolean = false,
        square: Boolean = false,
        big: Boolean = false,
        drawn: Boolean = false,
    ) = ImageCandidateSpec(w, h, depth, leaf, avatar, square, big, viaDraw = drawn)

    @Test
    fun `同一档里真位图优先于自绘`() {
        // issue #7②：真机上命中的自绘控件可能画出一整张同色，所以优先拿真位图那个
        assertEquals(
            1,
            selectImageCandidate(
                listOf(cand(640, 480, big = true, drawn = true), cand(500, 400, big = true)),
            ),
        )
    }

    @Test
    fun `只剩自绘候选时也不放弃`() {
        assertEquals(0, selectImageCandidate(listOf(cand(640, 480, big = true, drawn = true))))
    }

    @Test
    fun `允许方形小图时表情包才当候选`() {
        val list = listOf(cand(80, 80, avatar = true, square = true), cand(120, 120, square = true))
        assertEquals(-1, selectImageCandidate(list))
        assertEquals(1, selectImageCandidate(list, allowSquareSmall = true))
    }
}
