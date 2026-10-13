package dev.goutou.wingman.ocr

/**
 * 识别结果的「清洗」—— 纯函数，单测在 OcrTextTest。
 *
 * 为什么单独抽一段：ML Kit 还回来的是一棵「块 / 行 / 词」的树，直接拼会带一堆空行和缩进；
 * 而这段逻辑在真机上根本没法验证（改一次要发版，还得等聊天里正好出现一张图）。
 * 抽成纯函数之后，照着真实形状构造输入就能一遍遍跑。
 */

/** 一段识别结果的字符上限：图里的字可能很多，全塞给模型会把上下文挤爆。 */
const val OCR_MAX_CHARS = 300

/**
 * 把识别出来的一行行文字拼成一条消息的内容。
 *
 * - 去掉空行和每行首尾空白（截图里常有一行只有标点或数字的噪声行）
 * - **行间保留换行**：长图 / 聊天截图的排版信息对模型有用，压成一坨反而看不懂谁跟谁说话
 * - 超过 [max] 字就截断并加省略号 —— 宁可少给，也不要让一张图把整个上下文占满
 */
fun ocrText(lines: List<String>, max: Int = OCR_MAX_CHARS): String {
    val joined = lines.asSequence()
        .map { it.trim() }
        .filter { it.isNotEmpty() }
        .joinToString("\n")
    if (max <= 0 || joined.length <= max) return joined
    // 超长时**首尾都留**，而不是只留开头（issue #7②）。
    // 聊天截图里「最新的那条」在**底部** —— 只截开头等于把最该给模型看的内容丢掉；
    // 而文档类长图的信息又常常在开头。所以切两段：开头 1/3 给上下文，结尾 2/3 给最新内容。
    val head = max / 3
    val tail = max - head
    return joined.take(head) +
        "\n…（中间省略 ${joined.length - max} 字）…\n" +
        joined.takeLast(tail)
}
