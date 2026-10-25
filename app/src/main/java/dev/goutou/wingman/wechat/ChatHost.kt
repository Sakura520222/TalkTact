package dev.goutou.wingman.wechat

import dev.goutou.wingman.config.Roles

/** 比合法原始标题的 32 字符上限更长，不能与旧微信标题混淆；只作内部 key。 */
internal const val QQ_KEY_PREFIX = "talktact:host:com.tencent.mobileqq:"
private const val HOST_KEY_NAMESPACE = "talktact:host:"
private const val MAX_RAW_TITLE_LENGTH = 32

/** 注入宿主只允许这两个包的主进程；同名会话不能跨宿主复用档案与放行状态。 */
internal enum class ChatHost(val packageName: String, val label: String) {
    WECHAT("com.tencent.mm", "微信"),
    QQ("com.tencent.mobileqq", "QQ");

    /** 参数是宿主刚识别出的原始标题，只在进入会话身份边界时调用一次。 */
    fun conversationKey(name: String): String {
        val normalized = Roles.normalizeKey(name)
        if (!isValidRawConversationName(normalized)) return ""
        return if (this == WECHAT) normalized else QQ_KEY_PREFIX + normalized
    }

    companion object {
        fun fromPackage(packageName: String): ChatHost? = entries.firstOrNull { it.packageName == packageName }

        fun matchesMainProcess(packageName: String, processName: String): Boolean {
            val host = fromPackage(packageName) ?: return false
            return processName == host.packageName
        }
    }
}

/** 展示与内部身份分离；人类可读的「QQ · 」不再用于权限或档案 key。 */
internal fun conversationDisplayName(value: String): String =
    if (value.startsWith(QQ_KEY_PREFIX)) "QQ · " + conversationRawName(value) else value

internal fun conversationRawName(value: String): String = value.removePrefix(QQ_KEY_PREFIX)

/** 回传候选名的共同边界：普通微信 key 或带完整 QQ 命名空间的原始标题。 */
internal fun isValidConversationKey(value: String): Boolean = when {
    value.startsWith(QQ_KEY_PREFIX) -> isValidRawConversationName(conversationRawName(value))
    value.startsWith(HOST_KEY_NAMESPACE) -> false
    else -> isValidRawConversationName(value)
}

private fun isValidRawConversationName(value: String): Boolean {
    if (value.length !in 1..MAX_RAW_TITLE_LENGTH || Roles.normalizeKey(value).isBlank() ||
        value.startsWith(HOST_KEY_NAMESPACE)) return false
    // SharedPreferences / 备份中的身份必须是合法 XML / Unicode，正常 emoji 对保持可用。
    var index = 0
    while (index < value.length) {
        val character = value[index]
        if (character < ' ' && character != '\t' && character != '\n' && character != '\r' ||
            character == '\uFFFE' || character == '\uFFFF') return false
        if (Character.isHighSurrogate(character)) {
            if (index + 1 >= value.length || !Character.isLowSurrogate(value[index + 1])) return false
            index += 2
        } else {
            if (Character.isLowSurrogate(character)) return false
            index++
        }
    }
    return true
}
