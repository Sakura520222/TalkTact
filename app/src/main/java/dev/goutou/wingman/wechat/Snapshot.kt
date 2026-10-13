package dev.goutou.wingman.wechat

import dev.goutou.wingman.config.IMAGE_MODE_OCR_THEN_VISION
import dev.goutou.wingman.config.IMAGE_MODE_VISION
import dev.goutou.wingman.proxy.ProxyProtocol

/**
 * View 树 -> 纯数据的中间表示。
 *
 * 这一层之所以存在，是因为「读取聊天记录」原来是整个模块最容易坏、又最没法测的地方：
 * 判定逻辑和 View/Canvas 绞在一起，微信一更新就只能装到手机上肉眼试。
 * 现在 ViewReader（依赖 Android）只负责产出下面的快照，ChatParser（纯 Kotlin）负责判定，
 * 判定逻辑因此可以在 JVM 上跑单元测试。
 */

/** 消息方向。UNKNOWN = 判不了，宁可丢掉也不要猜。 */
enum class Side { ME, OTHER, UNKNOWN }

/** 行内文字块的类型（由 ViewReader 标注）。 */
enum class Kind { BUBBLE, NICKNAME, TIMESTAMP, TAG, OTHER }

data class TextNode(
    val text: String,
    val top: Int,
    val kind: Kind = Kind.OTHER,
    val size: Float = 0f,
    /** 控件是否真的可见。微信会把正文放在 INVISIBLE 的占位控件里，所以只当参考权重用。 */
    val visible: Boolean = true,
)

data class AvatarNode(val centerX: Int, val top: Int, val size: Int)

/**
 * 一行的「主气泡」。
 * @param text null 表示这一行是图片/表情/语音之类没有文字的附件
 * @param colorSide 气泡底色给出的那一票（微信自己的绿色 = 我）
 * @param centerRatio 气泡中心 x / 屏宽，用来判左右
 */
data class Bubble(
    val text: String?,
    val colorSide: Side,
    val centerRatio: Double?,
    val top: Int,
)

data class RowSnapshot(
    val bubble: Bubble?,
    val texts: List<TextNode> = emptyList(),
    val avatars: List<AvatarNode> = emptyList(),
    /**
     * 这一行里那张「消息图」（没有图就是 null）。
     *
     * 只带尺寸和一个**按需取字节**的入口：抓图 + 重新编码是有成本的，不该每轮读屏都做一遍；
     * 而取字节要碰 Android API，所以做成回调 —— 快照这一层仍然是纯数据，单测照样能造。
     */
    val image: RowImage? = null,
    /**
     * 这一行「找图」的现场（只在像附件的行上填），例如 `候选 2｜最大 MMImageView 640x480`。
     *
     * 为什么要带着它：真机上「图上有字、却一个字都没认出来」没法调试（改一次要发版）。
     * 把看到的东西带回 App 显示出来，就能一眼分辨是「这行压根没找到图」还是「找到了但认不出字」。
     */
    val imageProbe: String? = null,
)

/**
 * 一行里的那张图（图片消息用）。
 *
 * @param width/height 图在屏幕上的尺寸（诊断里用它说明「取的是哪一张」）
 * @param grab 按「最长边」取 JPEG 字节；null = 这张图不打算送（取不到位图 / 编码失败）
 */
class RowImage(
    val width: Int,
    val height: Int,
    private val grab: (Int) -> ByteArray?,
) {
    /**
     * 上一次取字节失败的原因（null = 没失败过）。
     *
     * 为什么要留着它（issue #7②）：真机上那句「认了：这张图里没字」把两件事混成了一件 ——
     * 「这一行的图根本没取到」和「取到了、但识别器认为没有字」。这两件事的排查方向完全相反
     *（前者要修取图，后者多半是缩略图太小），所以把取图失败的原因单独带出来。
     */
    @Volatile
    var fail: String? = null
        private set

    /** 压成 JPEG。默认最长边走 [ProxyProtocol.OCR_MAX_SIDE]。 */
    fun jpeg(maxSide: Int = ProxyProtocol.OCR_MAX_SIDE): ByteArray? {
        val r = runCatching { grab(maxSide) }
        val bytes = r.getOrNull()
        fail = when {
            r.isFailure ->
                r.exceptionOrNull()?.message
                    ?: "取图异常（${r.exceptionOrNull()?.javaClass?.simpleName}）"
            bytes == null -> "取不到这一行的图（不是普通位图 / 已回收）"
            bytes.isEmpty() -> "压出来的 JPEG 是空的"
            else -> null
        }
        return bytes
    }
}

/** 交给模型的一条消息。 */
data class ChatMsg(
    val fromMe: Boolean,
    val text: String,
    val who: String = "",
    /** true = 占位文本，不是原文（图片/表情/语音） */
    val attachment: Boolean = false,
) {
    /**
     * 这一条消息**对应的图片本体**（JPEG 字节），没有就是 null（issue #7③）。
     *
     * 为什么带着它：OCR 认不出来的图（表情包、字太小的缩略图）在模型眼里等于「内容未知」。
     * 开了「直传图片给多模态模型」之后就把这份字节按 base64 塞进请求，让能看图的模型自己看。
     *
     * 刻意**不放进构造参数**：data class 的 equals/hashCode 会把数组按**引用**比，
     * 而两轮读屏拿到的是两个不同的数组对象 —— 那会让 `screenIdentity`（拿 msgs 算的这一屏指纹）
     * 每轮都变，缓存永远 miss、在飞的请求被反复作废。它只是「随这条消息走的一份素材」，
     * 不参与身份判断。
     */
    var image: ByteArray? = null
}

const val ATTACHMENT_TEXT = "[图片/表情/语音]"

/**
 * 微信给「没有文字的附件」用的**通用占位**（无障碍描述里也会出现这几个）。
 *
 * 刻意**不含**具体表情名（`[微笑]` 这种）：那种行本来就是有内容的文字消息，
 * 不该被当成「图片消息」重写一遍。
 */
private val GENERIC_ATTACHMENT = setOf(
    "[图片]", "[照片]", "[表情]", "[动画表情]", "[语音]", "[视频]", "[文件]", "[链接]", "[位置]", ATTACHMENT_TEXT,
)

/** 这一行是不是「通用附件占位」——凡是这种行，都可以拿图里的字把它换掉。 */
fun isGenericAttachment(t: String): Boolean = t.trim() in GENERIC_ATTACHMENT

/**
 * 「值得去找图认字」的行 —— 比 [GENERIC_ATTACHMENT] 窄得多。
 *
 * 只有图片 / 照片两类占位、以及模块自己那个通用占位才去找图。语音 / 视频 / 文件 / 位置
 * 这些行里没有能认的文字，`[表情]` / `[动画表情]` 同理 —— 以前对着它们也要把控件画下来送去
 * OCR，结果永远是「认了：这张图里没字」，白占一次后台识别，还让状态行一屏一屏地刷。
 *
 * ⚠️ 判定的是**微信给的占位**；正文为空（`null`，内容未知）那一支由调用方处理，仍会去找图。
 */
private val IMAGE_LIKE = setOf("[图片]", "[照片]", ATTACHMENT_TEXT)

/** 这一行值不值得去找图认字（见 [IMAGE_LIKE]）。 */
fun isImageLikeAttachment(t: String): Boolean = t.trim() in IMAGE_LIKE

/**
 * 表情包那一类占位（issue #7③）。
 *
 * 和 IMAGE_LIKE 刻意分开：它们**没有字可认**，找图只为了把图直传给多模态模型。
 * 混进 IMAGE_LIKE 的后果是一屏表情包会被一个接一个画下来送去 OCR，全是「这张图里没字」——
 * 老代码里那段注释就是为了防这件事写的。
 */
private val STICKER_LIKE = setOf("[表情]", "[动画表情]")

/** 这一行是不是表情包（见 [STICKER_LIKE]）。 */
fun isStickerAttachment(t: String): Boolean = t.trim() in STICKER_LIKE

/**
 * 这一条消息的图要不要**直传**给多模态模型（issue #7③）。
 *
 * 三档的语义见 config/Config.kt 里 IMAGE_MODE_* 那几个常量：
 * - `ocr`：永不直传（默认，图不出手机）；
 * - `ocr_then_vision`：**认不出字、或认出来的字太少**时才直传 —— 这正是 issue #7 报的两种情况
 *  （含字截图被认成「没字」、表情包压根没有内容）；
 * - `vision`：总是直传（模型看原图最准，代价是每次都多花那份图的钱）。
 *
 * @param ocr 这一行认出来的文字（null / 空 = 没认出来）
 */
fun shouldSendImage(mode: String, ocr: String?): Boolean = when (mode) {
    IMAGE_MODE_VISION -> true
    IMAGE_MODE_OCR_THEN_VISION -> ocr.orEmpty().trim().length < VISION_FALLBACK_MIN_CHARS
    else -> false
}

/**
 * `ocr_then_vision` 里「认出来的字太少」的门槛。
 *
 * 12 个字：短于这个长度的识别结果基本都是「扫到一两个词 / 几个数字」这种没什么用的残片
 *（真正有价值的一段话远不止 12 个字），那时候让模型看原图比拿着一地碎词强。
 */
const val VISION_FALLBACK_MIN_CHARS = 12

/** 认出来的图片文字写进上下文时的前缀（模型靠它知道「这是从图里认出来的，可能有错字」）。 */
const val IMAGE_PREFIX = "[图片] "

/**
 * 图片那一行最终写进上下文的样子。
 *
 * 认出字 → `[图片] 认出来的文字`（告诉模型这是从图里认出来的，可能有错别字 ——
 * 但总比「内容未知，不要猜」强）；没认出来 / 没开识图 / 认字失败 → 退回老占位 [ATTACHMENT_TEXT]。
 *
 * 截断而不是丢弃：普通文字消息超过 [ChatParser] 的上限会被整条丢掉，
 * 但一张图的文字往往只是一部分有用 —— 留前面这些字比什么都不留强。
 */
fun imageMessageText(ocr: String?, max: Int = 400): String {
    val t = ocr?.trim().orEmpty()
    if (t.isEmpty()) return ATTACHMENT_TEXT
    val body = if (max > 0 && t.length > max) t.take(max) + "…" else t
    return IMAGE_PREFIX + body
}
