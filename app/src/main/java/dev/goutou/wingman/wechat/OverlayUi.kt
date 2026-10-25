package dev.goutou.wingman.wechat

import android.content.Context
import android.content.res.Configuration

/** 注入侧只使用系统色，不加载 App 的 Compose / Miuix 类。 */
internal data class OverlayPalette(
    val accent: Int, val text: Int, val sub: Int, val reply: Int,
    val stroke: Int, val warn: Int, val bad: Int, val ok: Int,
) {
    companion object {
        fun resolve(context: Context): OverlayPalette {
            val dark = context.resources.configuration.uiMode and Configuration.UI_MODE_NIGHT_MASK == Configuration.UI_MODE_NIGHT_YES
            val accent = runCatching {
                context.getColor(if (dark) android.R.color.system_accent1_200 else android.R.color.system_accent1_600)
            }.getOrDefault(if (dark) 0xFFD0BCFF.toInt() else 0xFF615B88.toInt())
            return OverlayPalette(
                accent, if (dark) 0xFFF2F2F2.toInt() else 0xFF0B0B0F.toInt(),
                if (dark) 0xFFB9B3C0.toInt() else 0xFF625E69.toInt(),
                if (dark) 0x16FFFFFF else 0x18FFFFFF,
                if (dark) 0x33FFFFFF else 0x887E768A.toInt(),
                if (dark) 0xFFF0B95B.toInt() else 0xFF8A5A00.toInt(),
                if (dark) 0xFFFF8A8A.toInt() else 0xFFC93A2E.toInt(),
                if (dark) 0xFF4FD296.toInt() else 0xFF00714A.toInt(),
            )
        }
    }
}

/** 状态卡只保留当前阻碍和下一步；完整现场留在用户主动生成的诊断中。 */
internal fun overlayStatusHint(reason: String): String = when {
    reason.contains("输入框") || reason.contains("按住说话") -> "切换到文字输入后重试。"
    reason.contains("会话名") || reason.contains("聊天对象") -> "暂未识别到会话，重新进入聊天后重试。"
    reason.contains("焦点") || reason.contains("前台") -> "回到聊天页面后重试。"
    reason.contains("切换") -> "正在切换会话，请稍候。"
    else -> reason.lineSequence().firstOrNull { it.isNotBlank() }.orEmpty().take(100)
}

/** 普通列表不足以证明是聊天页，避免在会话列表上弹出错误卡片。 */
internal fun overlayChatEvidence(inputCandidate: Boolean, composer: Boolean, list: Boolean, chatActivity: Boolean): Boolean =
    inputCandidate || (composer && list) || (chatActivity && list)

internal fun overlayManualOnly(qq: Boolean, automatic: Boolean): Boolean = qq || !automatic

/** 一次点击只授权一个屏幕的识别；等 OCR 时可继续，完成或换屏后不能沿用。 */
internal class ManualRecognitionSession {
    var pending: Boolean = false
        private set
    private var screen: String? = null

    fun start() { pending = true; screen = null }
    fun finish() { pending = false; screen = null }
    fun bind(fingerprint: String): Boolean {
        if (!pending) return false
        if (screen == null) screen = fingerprint
        if (screen != fingerprint) { finish(); return false }
        return true
    }
}
