package dev.goutou.wingman.wechat

import kotlin.math.abs

/** Pure view classification rules. Android-specific code only supplies these measurements. */
data class ImageCandidateSpec(
    val width: Int,
    val height: Int,
    val depth: Int,
    val isLeaf: Boolean,
    val isAvatarLike: Boolean,
    val isSquareSmall: Boolean,
    val isBig: Boolean,
    /**
     * true = 这个控件**没有可直接拿的位图**，只能靠 `view.draw()` 画出来。
     *
     * 为什么让它参与挑选（issue #7②）：真机上「图上有字却认不出字」排查下来，
     * 命中的往往正是这种自绘控件 —— `view.draw()` 对用了硬件层 / SurfaceView 的控件
     * 会画出一整张同色位图，然后就得到一句「这张图里没字」。同一档里只要还有一个
     * 真位图候选（ImageView + BitmapDrawable），就该优先拿它。
     */
    val viaDraw: Boolean = false,
)

/**
 * Selects the image candidate without depending on View, Bitmap, or Canvas.
 *
 * @param allowSquareSmall 允许方形小图当候选。默认 false（头像 / 表情包本来就不该拿去认字），
 *   只有「表情包直传给多模态模型」那条路才开 —— 那时候图**本身就是内容**，
 *   而表情包恰恰长得又方又小（见 wechat/Overlay 里的 wantStickerImage）。
 */
fun selectImageCandidate(
    candidates: List<ImageCandidateSpec>,
    rowIndex: Int = -1,
    allowSquareSmall: Boolean = false,
): Int {
    // 同一档里**真位图优先于自绘**：自绘那条路可能画出一整张同色（见 [ImageCandidateSpec.viaDraw]）。
    // 只剩自绘候选时也不放弃 —— 画得出内容的控件比「这一行没有图」强。
    fun pick(indices: List<Int>): Int? {
        if (indices.isEmpty()) return null
        val real = indices.filter { !candidates[it].viaDraw }
        return (real.ifEmpty { indices }).maxByOrNull { candidates[it].width * candidates[it].height }
    }

    pick(candidates.indices.filter { candidates[it].isBig && candidates[it].isLeaf })?.let { return it }

    // 「大容器」这一档刻意**不**按 viaDraw 排序：它选的是「装图的那个框」而不是图本身，
    // 尺寸与深度才是这一档的判据，掺进取像素的方式只会让结果变得难解释。
    val bigContainer = candidates.indices.filter { candidates[it].isBig && it != rowIndex }
    bigContainer.maxWithOrNull(compareBy<Int> { candidates[it].depth * 10_000_000 + candidates[it].width * candidates[it].height })
        ?.let { return it }

    return pick(
        candidates.indices.filter {
            candidates[it].isLeaf && !candidates[it].isAvatarLike &&
                (allowSquareSmall || !candidates[it].isSquareSmall)
        },
    ) ?: -1
}

/** Measurements of a title node; visibility and ancestry stay at the Android boundary. */
data class TitleCandidateSpec(
    val text: String,
    val width: Int,
    val height: Int,
    val x: Int,
    val y: Int,
    val textSize: Float = 0f,
)

/** Null means rejected; callers keep the first candidate on equal scores and prefer the narrow band. */
fun chatTitleScore(
    candidate: TitleCandidateSpec,
    screenWidth: Int,
    density: Float,
    bandTop: Int,
    bandBottom: Int,
    avoidTexts: Set<String> = emptySet(),
): Int? {
    fun dp(value: Int) = (value * density).toInt()
    val t = candidate.text
    if (t.isEmpty() || t.length > 32 || t in UI_WORDS || looksLikeViewDump(t)) return null
    if (t in avoidTexts || TITLE_NOISE.any { t.startsWith(it) }) return null
    // ⚠️ 宽度下限**不能太大**：备注是「单个字」的会话（张 / 王 / 李…）标题控件就只有一个字宽 ——
    //    实测在常见密度下约 47px，而旧的 dp(24) 在 2.75 密度下要 66px，于是**单字备注的会话永远选不出标题**。
    //    后果不是「读不到名字」这么简单：上层看到标题是空的，会沿用上一个会话的名字等下一轮 ——
    //    一直等下去，就变成「换了窗口还在读上一个窗口的内容」（issue #6）。
    //    dp(14) 仍然宽过一个图标，而且下面还有「居中 ±16%」和 UI 词表两道闸。
    if (candidate.width < dp(14) || candidate.height < dp(14)) return null
    if (candidate.y < bandTop || candidate.y > bandBottom) return null
    val off = kotlin.math.abs((candidate.x + candidate.width / 2f) - screenWidth / 2f) / screenWidth.toFloat()
    if (off > 0.16f) return null
    return ((1f - off) * 1000).toInt() + candidate.width.coerceAtMost(screenWidth) / 20 +
        (candidate.textSize.coerceAtMost(dp(40).toFloat()) / 4f).toInt()
}

fun isConversationListContainer(shown: Boolean, childCount: Int, listLike: Boolean, height: Int, top: Int, screenHeight: Int): Boolean =
    shown && childCount >= 3 && listLike && height > 0 && top + height > 0 &&
        top < screenHeight && height >= screenHeight * 0.25f

fun isConversationAvatar(width: Int, height: Int, left: Int, screenWidth: Int, density: Float): Boolean {
    fun dp(value: Int) = (value * density).toInt()
    return width in dp(24)..dp(84) && height > 0 && kotlin.math.abs(width - height) <= dp(4) && left < screenWidth * 0.30f
}

fun classifyBubbleColor(px: Int): Side {
    if (((px ushr 24) and 0xFF) < 24) return Side.UNKNOWN
    val r = (px shr 16) and 0xFF
    val g = (px shr 8) and 0xFF
    val b = px and 0xFF
    return if (g - r > 16 && g - b > 10) Side.ME else Side.OTHER
}

fun colorDistance(a: Int, b: Int): Int {
    val dr = abs(((a shr 16) and 0xFF) - ((b shr 16) and 0xFF))
    val dg = abs(((a shr 8) and 0xFF) - ((b shr 8) and 0xFF))
    val db = abs((a and 0xFF) - (b and 0xFF))
    return dr + dg + db
}

fun looksLikeViewDump(s: String): Boolean {
    val t = s.trim()
    if (t.isEmpty()) return false
    if (FQN_RE.matches(t)) return true
    // "android.widget.LinearLayout{...}" 这种：去掉 {...} 之后再看一眼前半段
    val head = t.substringBefore('{').trim()
    if (head.length < t.length && head.length >= 8 && FQN_RE.matches(head)) return true
    // 兜底：框架包名开头的一律不是聊天内容
    return FRAMEWORK_PREFIXES.any { t.startsWith(it) }
}

/** 会出现在同一带的临时/状态文案，不是会话名。 */
private val TITLE_NOISE = listOf(
    "对方正在输入", "正在输入", "网络连接不可用", "未连接", "点击重连", "连接中",
    "语音通话中", "视频通话中", "邀请你", "按住说话", "松开 结束",
)

/**
 * `android.widget.TextView` 这种：全是点分标识符、末段首字母大写。
 * 刻意不把 `$` 写进字符类 —— 在 Kotlin 字符串里它是模板起始符，容易出幺蛾子，
 * 而带 `$` 的内部类名字符串还有 FRAMEWORK_PREFIXES 那条兜底。
 */
// matches() 本身就是整串匹配，不用 ^ / $ 锚点（也避开 Kotlin 字符串里的 $ 模板歧义）
private val FQN_RE = Regex("([A-Za-z_][A-Za-z0-9_]*\\.)+[A-Z][A-Za-z0-9_]*")

/** 这些包名开头的一律不是聊天内容。 */
private val FRAMEWORK_PREFIXES = listOf(
    "android.", "androidx.", "java.", "javax.", "kotlin.", "dalvik.",
    "com.tencent.", "com.android.", "com.google.android.",
)

/** 纯 UI 文案的无障碍描述，不当消息正文。 */
internal val UI_WORDS = setOf(
    "头像", "表情", "更多功能", "更多", "返回", "发送", "语音输入", "加号",
    "图片", "视频", "按住 说话", "切换键盘", "菜单", "关闭", "搜索", "聊天信息",
)
