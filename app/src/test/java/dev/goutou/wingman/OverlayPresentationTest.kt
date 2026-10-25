package dev.goutou.wingman

import dev.goutou.wingman.wechat.overlayChatEvidence
import dev.goutou.wingman.wechat.overlayStatusHint
import dev.goutou.wingman.wechat.overlayManualOnly
import dev.goutou.wingman.wechat.ManualRecognitionSession
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class OverlayPresentationTest {
    @Test fun `QQ与关闭自动分析时都只能手动请求`() {
        assertTrue(overlayManualOnly(true, true))
        assertTrue(overlayManualOnly(true, false))
        assertTrue(overlayManualOnly(false, false))
        assertFalse(overlayManualOnly(false, true))
    }

    @Test fun `一次手动识别完成后新消息不能自动沿用授权`() {
        val session = ManualRecognitionSession()
        session.start()
        assertTrue(session.bind("screen-a"))
        assertTrue(session.bind("screen-a")) // OCR 尚未结束，同一屏可继续。
        session.finish()
        assertFalse(session.bind("screen-b"))
        assertFalse(session.pending)
    }

    @Test fun `等待OCR期间换屏立即作废这次请求`() {
        val session = ManualRecognitionSession()
        session.start()
        assertTrue(session.bind("screen-a"))
        assertFalse(session.bind("screen-b"))
        assertFalse(session.bind("screen-a"))
        session.start()
        assertTrue(session.bind("screen-b"))
    }

    @Test fun `普通列表不会触发聊天错误浮窗`() {
        assertFalse(overlayChatEvidence(false, false, true, false))
        assertFalse(overlayChatEvidence(false, false, false, false))
    }

    @Test fun `语音输入与消息列表可保留手动入口`() {
        assertTrue(overlayChatEvidence(false, true, true, false))
        assertFalse(overlayChatEvidence(false, true, false, false))
        assertTrue(overlayChatEvidence(true, false, false, false))
    }

    @Test fun `已知聊天页面仍需列表证据`() {
        assertTrue(overlayChatEvidence(false, false, true, true))
        assertFalse(overlayChatEvidence(false, false, false, true))
    }

    @Test fun `输入异常只显示下一步而不显示诊断说明书`() {
        assertEquals("切换到文字输入后重试。", overlayStatusHint("这一屏读不到输入框。\n判断依据：有像消息列表的容器。长按生成诊断。"))
        assertEquals("正在切换会话，请稍候。", overlayStatusHint("正在切换会话，请稍候。"))
        assertEquals("暂未识别到会话，重新进入聊天后重试。", overlayStatusHint("无法识别会话名\n请生成诊断发给开发者。"))
    }
}
