package dev.goutou.wingman.llm

/**
 * 「每次给几条、每条走哪种风格」的配置。
 *
 * 抽成纯函数是为了能写单测：这些值最终会影响两件很难靠肉眼验证的事 ——
 * ① 提示词里要求模型写几条、按什么策略写；
 * ② 解析回来之后保留几条。
 * 以前这两处是写死的 `take(4)` 和提示词里的「3 条 / 稳妥·幽默·推进」，
 * 现在都从这里取，配置改了才真的生效。
 */

/**
 * 一个可选的回复风格。
 *
 * [id] 是存进 SharedPreferences 的稳定标识；[label] 是给用户看的中文名；
 * [hint] 是这个策略到底在干什么（写进提示词，让模型知道两者差别）。
 */
data class ReplyStyle(
    val id: String,
    val label: String,
    /** 给模型看的策略说明。写清楚「什么时候用哪种」，比只给一个名字有用得多。 */
    val hint: String,
) {
    /**
     * 药丸上显示的名字（长名字截断，免得把卡片撑破）。
     *
     * **只在显示时用**，绝不参与 [id]。早先 [customStyle] 是拿截断后的名字拼 id 的，
     * 于是两个前 8 个字相同的长名字会拼出**同一个 id**：第二个加进来直接报「已经加过了」，
     * 而从偏好里解码时又会被 `distinctBy { it.id }` 静默丢掉一条 —— 用户看到的是
     * 「我明明加了两个，只剩一个」。
     */
    val displayLabel: String get() = shortStyleLabel(label)
}

/**
 * 内置的风格清单。
 *
 * 刻意给到 8 个而不是 3 个：真正有用的组合大多落在「几条里挑几种」上 ——
 * 只要「稳妥 + 幽默」两条的人很多，只要「直接 + 推进」的人也很多。
 * 但**上限仍然是 [MAX_REPLIES]**，超过就不给了（见 [ReplyPlan.of]），理由见那里。
 */
val REPLY_STYLES: List<ReplyStyle> = listOf(
    ReplyStyle("safe", "稳妥", "稳、留余地，不替用户做承诺；对方情绪不对时先接住"),
    ReplyStyle("humor", "幽默", "轻松调侃缓解气氛，要用大家都懂的梗、不炫技"),
    ReplyStyle("advance", "推进", "主动往前推一小步，给具体选项而不是开放式提问"),
    ReplyStyle("empathy", "共情", "先接住对方的情绪再谈事；对方在气头上时只安抚不谈道理"),
    ReplyStyle("direct", "直接", "不绕弯子，把关键问题直接问出来；不铺垫、不客套"),
    ReplyStyle("brief", "简短", "极短，一句话说完；适合对方在忙或明显不想聊"),
    ReplyStyle("formal", "正式", "措辞更书面、更礼貌；用于同事、甲方、领导这类关系"),
    ReplyStyle("probe", "追问", "以问句为主，把信息不足的地方问清楚；适合信息不够时"),
)

/** 默认的三种：和三个内置 skill 的提示词原文一致，老用户升级后行为不变。 */
val DEFAULT_STYLE_IDS: List<String> = listOf("safe", "humor", "advance")

/** 自定义风格的 id 前缀：`custom:名称`。存进偏好的就是这个稳定标识。 */
const val CUSTOM_STYLE_ID_PREFIX = "custom:"

/**
 * 最多允许几个自定义风格。
 *
 * 给 8 个：风格区是 4 列换行铺开的，8 个正好两行；再多会把设置页撑得很长，
 * 而且**条数上限是 6**，风格比条数多出一倍以上就没有意义了。
 */
const val MAX_CUSTOM_STYLES = 8

/** 自定义风格名允许输入的最大字数（存之前会被 [shortStyleLabel] 再截一次）。 */
const val MAX_CUSTOM_LABEL_LEN = 16

/** 编码里字段之间的分隔符。用控制字符：用户敲不出来，所以永远不会和内容撞车。 */
private const val CUSTOM_FIELD_SEP = '\u0001'

/** 编码里每条之间的分隔符。名称里的空白已经在 [shortStyleLabel] 里归一过，不会有换行。 */
private const val CUSTOM_ENTRY_SEP = '\n'

/**
 * 用户自己加的一个风格。
 *
 * 名字用 [normalizeLabel] 收拾过（只压空白 + 限长，**不截断**），**收拾完是空的就返回 null** ——
 * 让调用方去提示「名字不能为空」，而不是造一个没有名字的风格出来
 *（那种风格在界面上就是一个空药丸，点也不是、删也不是）。
 *
 * [hint] 是给模型看的策略说明，空着也能加，但**不能什么都不写**：
 * 提示词里每条风格都带一句「这个策略在干什么」，只给一个名字，模型会把它当成标签照抄
 *（三个候选措辞一模一样，只有 `style` 字段不同）。所以空 hint 会兜一句通用说明。
 */
fun customStyle(label: String, hint: String = ""): ReplyStyle? {
    // 用**未截断**的整名去拼 id（只归一空白），显示时才截（见 [ReplyStyle.displayLabel]）。
    val name = normalizeLabel(label)
    if (name.isEmpty()) return null
    val cleaned = hint.trim().replace(Regex("\\s+"), " ")
    return ReplyStyle(
        id = CUSTOM_STYLE_ID_PREFIX + name,
        label = name,
        hint = cleaned.ifBlank { "按「$name」这种语气和策略来写，要和别的候选明显不一样" },
    )
}

/** 输入框里先按字数上限收一下，免得用户粘一大段进来。 */
private fun limitLabel(raw: String): String {
    val t = raw.trim()
    return if (t.length <= MAX_CUSTOM_LABEL_LEN) t else t.take(MAX_CUSTOM_LABEL_LEN)
}

/**
 * 存进 [ReplyStyle.label] 的那份名字：压掉首尾与连续的空白，按上限截到
 * [MAX_CUSTOM_LABEL_LEN] 字，**不再往下截**。
 *
 * 为什么不在这里直接截成药丸那么短：截出来的名字要拿来拼 [ReplyStyle.id]，
 * 一截就会有两个长名字撞成同一个 id（见 [ReplyStyle.displayLabel] 的说明）。
 * 显示那份截断交给 [shortStyleLabel]，两边职责分开。
 */
private fun normalizeLabel(raw: String): String =
    limitLabel(raw).replace(Regex("\\s+"), " ")

/**
 * 自定义风格 ↔ 偏好里的字符串（一行一条：`名称\u0001说明`）。
 *
 * 为什么不用 `putStringSet`：**Set 会把顺序丢掉**，而风格列表的顺序就是界面上显示的顺序、
 * 也是「勾不够条数时按顺序补齐」的依据。丢顺序之后用户每次重启看到的顺序都在变。
 */
fun encodeCustomStyles(styles: List<ReplyStyle>): String =
    styles.joinToString(CUSTOM_ENTRY_SEP.toString()) { it.label + CUSTOM_FIELD_SEP + it.hint }

/**
 * 解回来。任何一条解不出来（空行、只有说明没有名字）就**跳过那一条**，不整体作废 ——
 * 坏一条就丢光用户的全部自定义风格，代价太大。
 */
fun decodeCustomStyles(raw: String): List<ReplyStyle> =
    raw.split(CUSTOM_ENTRY_SEP)
        .mapNotNull { line ->
            val s = line.trim()
            if (s.isEmpty()) return@mapNotNull null
            val at = s.indexOf(CUSTOM_FIELD_SEP)
            if (at < 0) customStyle(s) else customStyle(s.substring(0, at), s.substring(at + 1))
        }
        .distinctBy { it.id }
        .take(MAX_CUSTOM_STYLES)

/**
 * 条数下限 / 上限。
 *
 * 下限 1：一条也要给 —— 只想要一条最稳妥的就直说，没有比「什么都没给」更糟的结果。
 * 上限 6：再往上候选之间会开始重复（模型没有那么多真正不同的策略），
 * 而且卡片在微信里会被撑得很长，反而没人愿意看完。给到 6 已经偏多。
 */
const val MIN_REPLIES = 1
const val MAX_REPLIES = 6

/** 默认 3 条，和以前一样。 */
const val DEFAULT_REPLY_COUNT = 3

/** 一条回复的 style 标签最多显示几个字（超长的自定义风格名会被截，避免把卡片撑破）。 */
private const val STYLE_LABEL_MAX = 8

/**
 * 归一化后的「回复规格」。
 *
 * 构造即有效：[of] 已经把越界条数夹紧、把认不出的风格 id 丢掉、把重复的补齐过，
 * 所以下游（提示词、解析、界面）拿到的一定是能直接用的。
 */
data class ReplyPlan(
    /** 最终给几条（1..[MAX_REPLIES]） */
    val count: Int,
    /** 要用的风格，顺序即展示顺序，长度 = [count] */
    val styles: List<ReplyStyle>,
) {
    /** 风格的中文名，提示词和界面的摘要都用它。 */
    val labels: List<String> get() = styles.map { it.label }

    /**
     * 界面上那行摘要：「3 条 · 稳妥 / 幽默 / 推进」。
     *
     * 用 [ReplyStyle.displayLabel]（截断过的）而不是全名：自定义风格名最长可以到
     * [MAX_CUSTOM_LABEL_LEN] 字，几个凑一起会把这一行撑成一整屏。
     */
    val summary: String get() = "$count 条 · " + styles.joinToString(" / ") { it.displayLabel }

    /**
     * 拼给模型的要求段落。
     *
     * 放在 skill 正文**之后、输出契约之前** —— 位置是有讲究的：
     * 契约必须仍然是最后一段（`stripOutputContract` 认的是「最后一个 `{"intent"` 那一行」），
     * 所以这条要求既不能跑到契约后面（会被当成契约的一部分），也不能塞进 skill 正文里
     * （那样用户改提示词时会把它一起删掉）。
     */
    fun instruction(): String = buildString {
        append("【这次要几条、什么风格】\n")
        append("- 恰好给 ").append(count).append(" 条候选回复（replies 数组里就是 ").append(count).append(" 个元素）。\n")
        styles.forEachIndexed { i, s ->
            append("- 第").append(i + 1).append("条走「").append(s.label).append("」：").append(s.hint).append('\n')
        }
        // 注意这条**不能带 "- " 前缀**：它是收尾的补充说明，不是和上面并列的一个要点。
        // 带上就会多出一个空的列表项（模型那边渲染成悬空的破折号，读着莫名其妙）。
        if (styles.size == 1) {
            append("只有一条风格可用，所以不必强求「策略不同」，但一定要给最合适的那一版。\n")
        } else {
            append(styles.size).append(" 条必须是真正不同的策略，不能只是互相换词。\n")
        }
        append("- 每条都要能直接复制粘贴发出去；best 仍然是这 ").append(count).append(" 条里最推荐的一条的下标（从 0 开始）。")
    }

    /**
     * 按这张表动态生成 JSON 示例。
     *
     * 原来契约里的示例是写死三条的（稳妥/幽默/推进）。模型对**照抄示例**的倾向很强，
     * 用户选了「共情 + 简短」却还给它看三条稳妥/幽默/推进的示例，它大概率照样给三条 ——
     * 所以示例必须跟着配置一起变。
     */
    fun jsonSample(): String = buildString {
        // 用双引号包：整段是 JSON 文本，单引号在 Kotlin 里是 Char 字面量，多字符会编译不过。
        append("{\"intent\":\"一句话\",\"risk\":\"低/中/高\",\"note\":\"一句话提醒\",\"best\":0,\"why\":\"为什么推荐它（一句话）\",\"replies\":[")
        styles.forEachIndexed { i, s ->
            if (i > 0) append(',')
            append("{\"style\":\"").append(s.label).append("\",\"text\":\"...\"}")
        }
        append("]}")
    }

    companion object {
        /**
         * 从配置算出真正要用的规格。
         *
         * 四个动作，顺序有讲究：
         * ① 条数夹进 [MIN_REPLIES]..[MAX_REPLIES]；
         * ② 风格按 [selectedIds] 的顺序认 —— 在**内置 + [customStyles]** 里找，认不出的丢掉；
         *    一个都没认出来就用 [DEFAULT_STYLE_IDS]；
         * ③ **认出来的风格不够条数时，从内置清单里按固定顺序补齐**；仍然不够就按条数截断；
         * ④ 只取前 [count] 个。
         *
         * ③ 只从**内置**清单补齐，不拿自定义的凑数：自定义是用户一条条手写进来的，
         * 自动「复制」几条出来填位置，等于替他编了他没写过的风格。
         *
         * ③ 本身是「选了 1 条风格却想要 3 条」这个场景的关键：用户的意思多半是
         * 「至少要有『共情』这一条」，而不是「就只要一条、不给别的」。
         * 直接砍成 1 条会让界面上的条数选择**静默失效** —— 那是最容易招来投诉的做法。
         */
        fun of(
            count: Int,
            selectedIds: List<String>,
            /** 用户在设置页自己加的风格（见 [customStyle]）。默认空 = 只有内置的 8 种。 */
            customStyles: List<ReplyStyle> = emptyList(),
        ): ReplyPlan {
            val n = count.coerceIn(MIN_REPLIES, MAX_REPLIES)
            val byId = (REPLY_STYLES + customStyles).distinctBy { it.id }.associateBy { it.id }
            val picked = LinkedHashSet<ReplyStyle>()
            // 先按用户选的顺序收
            selectedIds.forEach { id -> byId[id]?.let { picked.add(it) } }
            // 一个都没认出来（老配置 / 选了已下线的 id）→ 回默认三样，别给空白卡
            if (picked.isEmpty()) DEFAULT_STYLE_IDS.forEach { id -> byId[id]?.let { picked.add(it) } }
            // 不够条数就从内置清单补齐（跳过已选的），保证长度真的等于 n
            if (picked.size < n) for (s in REPLY_STYLES) {
                if (picked.size >= n) break
                picked.add(s)
            }
            return ReplyPlan(n, picked.take(n))
        }

        /** 老配置 / 默认值：3 条、稳妥+幽默+推进。 */
        fun default(): ReplyPlan = of(DEFAULT_REPLY_COUNT, DEFAULT_STYLE_IDS)
    }
}

/**
 * 点一下风格药丸之后，要**存下去**的那组 id（用户显式勾选的，保序）。
 *
 * 为什么规则从原来的 `ReplyPlan.toggledIds` 搬出来、并且只看「显式勾选」：
 * 老规则是拿 [ReplyPlan.styles]（**生效**的那张表）算的，而那张表里掺了自动补齐出来的风格，
 * 于是有两处必然出问题 ——
 * ① 点一个「被补齐进来、界面上正亮着」的风格 → 从集合里减掉 → 下一轮又被补齐回来
 *    → 表现是**点了没反应**；
 * ② 点一个新风格时被挤掉的是「生效表」的最后一个，那个可能用户**从来没勾过**
 *    → 表现是「点了个新的，掉的却是别的那个」。
 * 现在勾选集是唯一的事实来源，补齐只发生在提示词那一侧（见 [ReplyPlan.of]），
 * 界面上永远只亮着真正勾了的那几个。
 *
 * 两条规则：
 * - **新点的一定留下**：排在**最前**，位置不够时让位的是末尾那个；
 * - **至少留一种**：点掉最后一个时保持原样（等于「取消 = 保持现状」），
 *   否则选择会变成空的，而模型侧又会回落默认三样，表现还是「点了没反应」。
 *
 * @param current 现在显式勾着的 id（**保序** —— 顺序决定「第 1 条走哪个风格」）
 * @param known 现在真实存在的风格 id（内置 + 自定义），用来清掉已删除风格的残留
 * @param count 条数：勾超过条数没有意义，只留前 [count] 个
 */
fun toggledStyleIds(
    current: List<String>,
    id: String,
    count: Int,
    known: Set<String>,
): List<String> {
    val n = count.coerceIn(MIN_REPLIES, MAX_REPLIES)
    // 先清掉「已经删掉的风格」残留，再判重 —— 顺序在这里必须留着
    val cur = current.map { it.trim() }.filter { it.isNotBlank() && it in known }.distinct()
    if (id !in known) return cur
    if (id in cur) {
        val next = cur - id
        return if (next.isEmpty()) cur.take(1) else next
    }
    return (listOf(id) + cur).distinct().take(n)
}

/** 按配置算规格的便捷入口（给 [dev.goutou.wingman.config.ConfigData] 用，避免那边 import 整个 llm 包）。 */
fun replyPlanOf(
    count: Int,
    ids: List<String>,
    customStyles: List<ReplyStyle> = emptyList(),
): ReplyPlan = ReplyPlan.of(count, ids, customStyles)

/** 短风格名（存进偏好 / 卡片上的标签）。长名字截断，免得把微信里的气泡撑成一整行。 */
internal fun shortStyleLabel(raw: String): String {
    val t = raw.trim().replace(Regex("\\s+"), " ")
    if (t.isEmpty()) return ""
    return if (t.length <= STYLE_LABEL_MAX) t else t.take(STYLE_LABEL_MAX) + "…"
}