package dev.goutou.wingman.llm

/**
 * 输出契约：任何 skill（内置的、从 GitHub 导入的）都必须带上这一段，
 * 否则模型不会返回卡片能解析的 JSON，界面就出不来候选。
 * 这是「提示词」页和导入功能里唯一不能删的东西。
 *
 * 注意这里是**不带 replies 示例**的版本 —— 用户可以在设置页自选条数与风格，
 * 示例由 [ReplyPlan.jsonSample] 按配置生成，见 [outputContract]。
 * 走这个版本的入口是 [outputContract]。
 */
const val JSON_CONTRACT_HEAD = """只输出下面这个 JSON，不要 markdown、不要任何多余文字：
"""

const val JSON_CONTRACT_TAIL = """

best = 你最推荐的那条在 replies 里的下标（从 0 开始）；why = 一句话说明为什么推荐它。
这两个字段必须有，值可以是空字符串/0。"""

/**
 * 按当前配置拼出输出契约：条数与风格都在 [plan] 里。
 *
 * 为什么不留一个写死三条的常量了：模型对**照抄示例**的倾向很强。
 * 用户选了 5 条却还给它看三条的示例，它大概率照样给三条 ——
 * 而且提示词正文里还写着「给 3 条」，两处都在教它给三条。
 * 所以正文与示例必须一起跟着配置走。
 */
fun outputContract(plan: ReplyPlan): String =
    JSON_CONTRACT_HEAD + plan.jsonSample() + JSON_CONTRACT_TAIL

/** 默认规格下的契约（3 条稳妥/幽默/推进）—— 和以前那段一字不差，老用户行为不变。 */
val JSON_CONTRACT: String = outputContract(ReplyPlan.default())


/**
 * 「原版狗头军师」的核心部分（输出契约在上面单独放）。
 * 三条硬要求别删：① 只输出 JSON ② 3 条策略要真的不同 ③ 不替用户做承诺。
 */
private const val CLASSIC_CORE = """你是「狗头军师」，用户的微信聊天副驾。你会收到最近的聊天记录：「我」是用户，「对方」是聊天对象（群聊里会带昵称，「[图片/表情/语音]」表示内容未知；以「[图片] 」开头的是从图片里识别出来的文字，可能有错别字）。

按顺序想，但只输出最后的 JSON：
1. 对方最后一句的真实意图和情绪是什么；
2. 哪些是事实、哪些只是推测、哪些还不知道 —— 不替对方脑补；
3. 有没有风险（钱、承诺、时间地点、情绪冲突）；
4. 再给回复。

回复要求：
- 像真人发微信：口语、短句，一条不超过 40 个字；不要客服腔，少用「当然可以」、感叹号和表情。
- 按下面「这次要几条、什么风格」那一段的要求给候选，策略要真的不同，不要互相改写。
- 标出最推荐的那条：best 填它的下标（从 0 开始），why 用一句话说清为什么它最合适。
- 不替用户做承诺（钱、时间、见面地点、替人办事）。遇到转账、红包、借钱、付款码、验证码、账号密码，在 note 里提醒谨慎，回复里不要答应。
- 对方情绪低落或有冲突：先接住情绪，再谈事。
- 最后一句跟用户无关时（群聊里别人互聊），在 note 里说明，replies 给适合插话的短句，允许其中一条是「先不回」。
- 信息不够就直说，并让其中一条回复用来追问。"""

/** 「程序员搭子」：技术沟通场景。 */
private const val CODER_CORE = """你是「程序员搭子」，用户的技术沟通军师。聊天记录里「我」是用户，「对方」可能是同事、leader、HR、甲方或候选人。

按顺序想，但只输出最后的 JSON：
1. 对方真实的诉求是什么（要东西？要承诺？要你背锅？还是只是问进度）；
2. 哪些是事实、哪些是推测、哪些还不知道 —— 不替对方脑补；
3. 风险：排期承诺、责任归属、对外口径、评价他人、薪酬与 offer 细节；
4. 再给回复。

回复要求：
- 像真人发微信：口语、短句，一条不超过 45 个字；不要官腔，不要「收到，我这边同步一下」这类正确的废话。
- 按下面「这次要几条、什么风格」那一段的要求给候选，策略要真的不同，不要互相改写。
- 标出最推荐的那条：best 填它的下标（从 0 开始），why 用一句话说清为什么它最合适。
- 不替用户承诺排期，不认锅也不甩锅：没定的事就说「我确认一下再回你」。
- 涉及薪酬、offer、绩效、离职、线上事故、对外口径时，在 note 里提醒谨慎，回复里不要给确定数字或结论。
- 需要技术判断时，在 note 里列出该确认的关键点（复现步骤、日志、影响面），一句话说清，别长篇大论。
- 信息不够就直说，并让其中一条回复用来追问。"""

/**
 * 「狗头军师·满血版」：在 CLASSIC_CORE 之上补了四样东西 ——
 * ① 场景模板（每个场景的重心与雷区）② 输出前自检清单 ③ 风险分级的具体判据 ④ few-shot 例子。
 * 代价是 system prompt 从 ~300 字涨到 ~1500 字，每次调用多花一点 token（当前是「无限制」档，影响不大）。
 */
private const val FULL_CORE = """你是「狗头军师·满血版」，用户的微信聊天副驾。
你会收到一段聊天记录：「我」是用户，「对方」是聊天对象（群聊里会带昵称，「[图片/表情/语音]」表示内容未知、不要猜它是什么；以「[图片] 」开头的是从图片里识别出来的文字，可能有错别字）。

先按下面五步想清楚，但**只输出最后的 JSON**：
1. 对方最后一句：字面意思是什么？真实意图是什么？两者一致吗（是客套、试探、催、阴阳，还是就是字面意思）。
2. 情绪：方向和强度（平静 / 期待 / 不满 / 冷淡 / 着急）。
3. 事实与推测分开：哪些是对方明说的，哪些是你在猜，哪些还不知道。**不要替对方脑补动机**。
4. 关系与阶段：刚认识 / 熟人 / 暧昧 / 同事 / 甲方 —— 分寸由它决定。
5. 风险：按下面的分级判据定级。

【输出前自检】逐条过一遍，不通过就重写（这一步不要输出）：
- 按「这次要几条、什么风格」要求的条数给够了吗？策略是**真的不同**吗？只是换个说法的，重写。
- 有没有替用户做承诺（钱、时间、地点、替人办事）？
- 有没有编造对方没说过的信息？
- 是不是客服腔或正确的废话（「收到」「好的呀」「这边同步一下」「辛苦啦」）？
- 有没有蹦出英文单词、类名或行业黑话（ConstraintLayout、RelativeLayout 这种）？有就换成大白话。
- 单条超长了吗？能不能直接复制粘贴就发出去？
- 该提醒的风险，note 里提醒了吗？
- best / why 给了吗？why 是不是一句话就说清了「为什么这条最合适」？

【场景模板】按场景找对应的一节，重心照它来：
- 邀约 / 见面：核心是降低对方的决策成本。给具体时间或给两个选项，不要问「你什么时候有空」。
- 拒绝：先一句缓冲（共情或感谢），再给理由 —— **理由越短越可信**，最后留个口子或给个替代方案。
- 道歉：只道歉，不复盘，不解释动机。把所有「但是」删掉。
- 催进度 / 催款：先对齐目标（我们都想按时），再给具体节点，不指责、不阴阳。
- 暧昧 / 升温：推进要具体但可撤回（提一个小邀约并给对方台阶），一次只推一小步。
- 冲突 / 吵架：先接住情绪（承认对方的感受是真的），再谈事实。气头上不讲道理。
- 群聊插话 / 别人互聊：note 里说明这条与你无关，给能自然插进去的短句，允许其中一条是「先不回」。
- 信息不足：直接问，把追问写成其中一条回复。

【高危区，必须谨慎】涉及借钱 / 转账 / 付款码 / 红包 / 验证码 / 账号密码 / 报名单，或面试、HR、薪酬、offer、离职、绩效、线上事故、对外口径、责任归属时：
note 里必须提醒谨慎，回复里**不要答应、不要给确定数字或结论**，给一条「先核实 / 先了解清楚再回」的话。

【风险分级判据】note 里报的等级按这个定：
- 低：日常寒暄、约已明确的事、无金钱无承诺。
- 中：时间地点未定、需要用户拿主意、对方情绪偏负、需要表态但可撤回。
- 高：钱与账号、任何承诺、替人办事、对外口径与责任归属、薪酬与 offer、情绪冲突可能升级。

【语气硬约束】
- 像真人发微信：口语、短句，可以用语气词，但不要网络烂梗。
- 一条不超过 40 个字，一句话说完。
- 不用感叹号和表情符号；不写「当然可以」「没问题呀」「收到」「这边」「同步一下」这类话。
- 只说人话：回复里不出现英文单词、类名、缩写或技术黑话（ConstraintLayout、RelativeLayout、API、bug 这种都算），
  除非对方原话里就是那个词、且必须回应它。幽默要用大家都懂的梗，不要用行业梗、不要炫技。
- 每条按「这次要几条、什么风格」那一段指定的策略来写，不要都写成同一种。
- 不确定的用「是不是」「要不」这类开放问法，别替对方下结论。

【例子】只看结构，不要照抄内容：
输入：对方: 周末有空吗，想约你吃个饭
输出：{"intent":"想约你见面，在探你的意思","risk":"低","note":"只问了时间，地点和事由都没定","best":2,"why":"给两个具体日期，对方只要挑一个","replies":[__FULL_EXAMPLE_REPLIES__]}"""

/**
 * 按当前规格把满血版 skill 正文里的 few-shot 例子填出来。
 *
 * 例子必须跟着配置走：模型对示例的抄写倾向很强，给它看三条示例、
 * 却要求五条回复，它多半还是给三条。
 */
private fun fullExampleReplies(plan: ReplyPlan): String =
    plan.styles.map { "{\"style\":\"${it.label}\",\"text\":\"...\"}" }.joinToString(",")

/**
 * 把正文里**已经填好的** few-shot 例子还原回 [EXAMPLE_PLACEHOLDER]。
 *
 * 为什么要还原：占位符一旦被 [buildSkillPrompt] 替换掉，存进配置的提示词里就只剩
 * 「当时那几条」的具体列表了。而配置是被**反复重拼**的 ——
 * 用户在设置里点一次条数就重拼一次，下一次再点，占位符已经找不回来了，
 * 例子就永久冻结在第一次的条数上：规格段写着「恰好给 5 条」、
 * 正文示例却还是三条 reply（或者反过来），**自相矛盾**，而模型两份都会照。
 *
 * 认的方式是那段示例里 `"replies":[` 到 `]` 的那一对括号，
 * 不依赖当时是几条 —— 所以不管重拼过多少次都能认回来。
 */
internal fun restoreExamplePlaceholder(core: String): String {
    if (core.contains(EXAMPLE_PLACEHOLDER)) return core
    val m = EXAMPLE_REPLIES_ARRAY.find(core) ?: return core
    // 用 group 1 的范围，而不是整段匹配的：
    // 整段是 `"replies":[元素,…]`，起点含键名和方括号；把它一起换成占位符的话，
    // 还原之后示例会变成 `"why":"…",__PH__}` —— JSON 直接坏掉，
    // 而且下次 restore 再也匹配不到 `"replies":`，占位符永远回不来，
    // 之后每次重拼都往那个坏位置里塞新的元素列表。踩过两次，别改回去。
    val inner = m.groups[1] ?: return core
    return core.substring(0, inner.range.first) + EXAMPLE_PLACEHOLDER + core.substring(inner.range.last + 1)
}

/**
 * few-shot 示例里的 replies 数组：整段是 `"replies":[元素,元素,…]`，
 * 第 1 组只捕获**元素部分**（不含键名和方括号）。
 *
 * 不用 look-behind 拿内容：`"replies":` 和 `[` 之间的空白长度不固定，
 * 变长的 look-behind 在别的正则引擎里未必支持，用捕获组最省事也最 portable。
 *
 * 调用 [restoreExamplePlaceholder] 时输出契约已经被摘掉了，
 * 所以剩下的 `"replies":` 只可能来自 few-shot 示例。
 */
private val EXAMPLE_REPLIES_ARRAY = Regex("\"replies\"\\s*:\\s*\\[(.*?)]")

/**
 * 把 skill 正文 + 规格要求 + 输出契约拼成一份完整的 system 提示词。
 *
 * 三段的顺序是刻意的：
 * 1. skill 正文（角色、场景、语气约束）—— 它每次都一样，放在最前面才能吃到服务端的**前缀缓存**；
 * 2. 「这次要几条、什么风格」—— 只有这一段随配置变；
 * 3. 输出契约 —— 永远在最后一段。
 *
 * 第 3 段必须在最后，因为 [stripOutputContract] 靠它把契约整段摘掉、再换成各自那一份。
 */
fun buildSkillPrompt(core: String, plan: ReplyPlan): String =
    core
        .replace(EXAMPLE_PLACEHOLDER, fullExampleReplies(plan))
        .trimEnd() + "\n\n" + plan.instruction() + "\n\n" + outputContract(plan)

/** 满核心里 few-shot 例子那段的占位符（见 [fullExampleReplies]）。 */
private const val EXAMPLE_PLACEHOLDER = "__FULL_EXAMPLE_REPLIES__"

val FULL_PROMPT: String = buildSkillPrompt(FULL_CORE, ReplyPlan.default())

val DEFAULT_PROMPT: String = buildSkillPrompt(CLASSIC_CORE, ReplyPlan.default())

val CODER_PROMPT: String = buildSkillPrompt(CODER_CORE, ReplyPlan.default())

/** 内置 skill。想加新的：把 core 写在上面，再往这个列表里加一条。 */
data class SkillPreset(val id: String, val name: String, val summary: String, val prompt: String)

val BUILT_IN_SKILLS: List<SkillPreset> = listOf(
    SkillPreset(
        id = "classic",
        name = "原版狗头军师",
        summary = "社交 / 暧昧 / 朋友场景：读意图、评风险，按你选的风格给候选回复",
        prompt = DEFAULT_PROMPT,
    ),
    SkillPreset(
        id = "full",
        name = "狗头军师·满血版",
        summary = "场景模板 + 输出前自检 + few-shot 示例 + 风险分级判据；system prompt 更长，token 消耗略高",
        prompt = FULL_PROMPT,
    ),
    SkillPreset(
        id = "coder",
        name = "程序员搭子",
        summary = "技术沟通：需求确认、排期、锅的归属、对外口径、面试与 HR 消息",
        prompt = CODER_PROMPT,
    ),
)

/**
 * 内置 skill 的正文部分（不含规格要求与契约）—— 换个配置重新拼时要用。
 *
 * 末尾还要把 few-shot 示例还原成 [EXAMPLE_PLACEHOLDER]：正文一旦被填过占位符就再也找不回来，
 * 而配置是被反复重拼的，不还原的话示例会永久冻结在**第一次**重拼时的条数上
 * （详细理由见 [restoreExamplePlaceholder]）。
 */
internal fun coreOf(prompt: String): String =
    restoreExamplePlaceholder(stripOutputContract(stripReplyInstruction(prompt)))

/**
 * 把一份**已保存**的 skill 提示词按当前规格重拼一遍。
 *
 * 为什么必须重拼，而不是只在用户改设置时才更新 cfg.prompt：
 * cfg.prompt 是**当初那次配置下**拼好、然后存进偏好里的快照。用户在设置页
 * 把条数从 3 改成 5 之后，这份快照里仍然是「恰好给 3 条」+ 三条的示例。
 * 提示词页还是自由编辑的（用户可能自己动过正文），所以也不能直接用内置 core 覆盖它 ——
 * 只能把「规格要求」和「输出契约」这两段换掉，**正文一个字不动**。
 *
 * 用户自己写的正文里要是有个字面上的「{"intent"」（比如在教模型举例），
 * [stripOutputContract] 可能砍错一段 —— 这属于「提示词自由编辑」这个功能本身的固有限制，
 * 以前也一样，不在这里额外处理。
 */
fun applyReplyPlan(prompt: String, plan: ReplyPlan): String =
    buildSkillPrompt(coreOf(prompt), plan)

/**
 * 把「这次要几条、什么风格」那一段从提示词里摘掉。
 *
 * 用户可能手动改过提示词（「提示词」页是自由编辑的），所以这段是**每次重新拼**时要替换掉的 ——
 * 不摘掉的话用户选了 5 条、提示词里却留着「恰好给 3 条」，模型会两边打架。
 * 识别用正文里那行固定的标题，它只由 [ReplyPlan.instruction] 产生。
 */
internal fun stripReplyInstruction(prompt: String): String {
    val head = findInstructionHead(prompt)
    if (head < 0) return prompt.trimEnd()
    // 往回到最近的空行整段砍掉 —— 只砍标题那行会留下没头的列表项
    val start = prompt.lastIndexOf("\n\n", head).let { if (it >= 0) it else head }
    return prompt.substring(0, start).trimEnd()
}

/**
 * 找规格段的标题，且**只在行首**才算。
 *
 * 为什么非得加这一层：满血版正文里有一句「每条按「这次要几条、什么风格」那一段指定的策略来写」，
 * 标题是**被引号包着、嵌在句子中间**的。用 `indexOf` 找会命中它，然后往回砍到最近的空行 ——
 * 结果把那句所在的整段连同它后面的【例子】整段一起砍掉。
 *
 * 而真正要替换的规格段，标题永远在行首（[ReplyPlan.instruction] 第一行就是这么写的）。
 * 所以「行首」这个条件能干净地把两者区分开，不用去猜哪一段更像。
 */
private fun findInstructionHead(prompt: String): Int {
    var from = 0
    while (true) {
        val at = prompt.indexOf(REPLY_INSTRUCTION_HEAD, from)
        if (at < 0) return -1
        // 行首 = 前面是行首或换行
        if (at == 0 || prompt[at - 1] == '\n') return at
        from = at + 1
    }
}

/** [ReplyPlan.instruction] 开头那一行，用来定位并整段替换。 */
internal const val REPLY_INSTRUCTION_HEAD = "【这次要几条、什么风格】"

/**
 * 给外部导入的 skill 自动补齐输出契约。
 * 少了「只输出 JSON」这段，卡片就拿不到可解析的 JSON —— 这是导入功能最常踩的坑。
 */
fun ensureJsonContract(text: String, plan: ReplyPlan = ReplyPlan.default()): String =
    if (text.contains("replies") && text.contains("intent")) {
        text
    } else {
        text.trimEnd() + "\n\n" + outputContract(plan)
    }


// ---------------- 模型分级模式：两路各一份契约 ----------------

/**
 * 分级模式里「风险评估」那一路的契约：只要 intent / risk / note。
 * 刻意不要 replies —— 写回复交给另一路（可能是另一个模型）。
 */
const val RISK_CONTRACT = """这次**只做风险评估**，不要写任何回复。
只输出下面这个 JSON，不要 markdown、不要任何多余文字：
{"intent":"对方最后一句的真实意图（一句话）","risk":"低/中/高","note":"一句话提醒"}"""

/**
 * 分级模式里「写回复」那一路的契约：只要 replies / best / why。
 * 前面那段 skill 正文里的「先想意图和风险」仍然有用（那是思考过程），只是不再输出它们。
 *
 * 条数与风格由 [replyContract] 按配置生成 —— 原来这里写死了三条示例。
 */
const val REPLY_CONTRACT_HEAD = """这次**只写回复**，不用复述意图和风险（已经有另一路在做了）。
只输出下面这个 JSON，不要 markdown、不要任何多余文字：
"""

fun replyContract(plan: ReplyPlan): String = buildString {
    append(REPLY_CONTRACT_HEAD)
    append("{\"replies\":[").append(plan.styles.joinToString(",") {
        "{\"style\":\"${it.label}\",\"text\":\"...\"}"
    }).append("],\"best\":0,\"why\":\"为什么推荐它（一句话）\"}")
}

/** 默认规格下那一路（老样子：三条稳妥/幽默/推进）。 */
val REPLY_CONTRACT: String = replyContract(ReplyPlan.default())

/**
 * 把 skill 提示词末尾的「输出契约」摘掉 —— 已确定的契约永远在最后一段，所以从它那段的
 * 起点起整段砍掉（后面的字段说明也一起去掉）。
 *
 * 定位用 [JSON_CONTRACT_HEAD] 里那句固定的引导语「只输出下面这个 JSON」，
 * **不是** `{"intent"`。
 *
 * 以前认的是 `{"intent"`，在满血版上会砍错东西：它正文里的 few-shot 示例
 * （`【例子】只看结构…输入：…输出：{"intent":…}`）也含这个字样，于是每次调用
 * 都在把整段【例子】连同上面的说明一起丢掉 —— 而那正是满血版「示例 + 自检」的核心。
 * 看着像少了一段提示词，实际影响是模型少了「照着这个结构写」的锚点，
 * 而对条数的要求又变了，正好最容易出岔子的地方。
 *
 * 兜底仍然保留 `{"intent"`：用户可以自由编辑提示词，把引导句改掉或删掉
 * （提示词页就是干这个的）。这时退回旧判据，至少还能把契约摘下来。
 */
internal fun stripOutputContract(prompt: String): String {
    val head = prompt.lastIndexOf(CONTRACT_HEAD_MARK)
    if (head >= 0) {
        val paraStart = prompt.lastIndexOf("\n\n", head)
        return prompt.substring(0, if (paraStart >= 0) paraStart else head).trimEnd()
    }
    // 兜底：用户可能把引导句改掉或删了（提示词页就是自由编辑的），那就退而找 `{"intent"`。
    //
    // ⚠️ 但**必须同时有契约的尾巴**才能砍，否则会误删正文 —— 这坑踩过：
    // [coreOf] 是先摘规格段再摘契约，第二次摘的时候满血版正文里那条 few-shot 示例
    // 本身就含 `{"intent"`，兜底一刀下去就把整个【例子】段落删了
    // （而那是满血版给模型的结构锚点）。
    //
    // 契约尾巴（`best = …下标（从 0 开始）`）只在真正的契约后面有，
    // 示例后面没有 —— 用它当第二判据，能把两者干净分开。
    //
    // 认不出来的代价是**契约被留在正文里**（下一段又会补一份新的，最多重复一份），
    // 比误删用户写的内容好得多：重复的契约不影响解析，删掉的内容找不回来。
    val json = prompt.lastIndexOf("{\"intent\"")
    if (json < 0) return prompt.trimEnd()
    if (!prompt.substring(json).contains(CONTRACT_TAIL_MARK)) return prompt.trimEnd()
    val paraStart = prompt.lastIndexOf("\n\n", json)
    return prompt.substring(0, if (paraStart >= 0) paraStart else json).trimEnd()
}

/** 契约引导句的第一行（[JSON_CONTRACT_HEAD] 的开头），用来定位契约段的起点。 */
private const val CONTRACT_HEAD_MARK = "只输出下面这个 JSON"

/** 契约尾巴里的固定片段（[JSON_CONTRACT_TAIL]），用来确认那处 `{"intent"` 确实是契约。 */
private const val CONTRACT_TAIL_MARK = "下标（从 0 开始）"

/**
 * 分级模式：风险一路要发的 system。
 *
 * 只摘契约，**不重拼规格要求**：这一路的契约里根本没有 replies，
 * 留着「要几条、什么风格」只会让模型去猜自己不该管的事（还多烧 token）。
 */
fun gradedRiskPrompt(prompt: String): String =
    stripReplyInstruction(stripOutputContract(prompt)) + "\n\n" + RISK_CONTRACT

/**
 * 分级模式：写回复一路要发的 system。
 *
 * 这一路要写候选，所以规格要求**必须跟着当前配置重拼**：
 * 直通模式下拼好的那段会被摘掉（换成这一路的契约），换成带 [plan] 的那一份。
 */
fun gradedReplyPrompt(prompt: String, plan: ReplyPlan): String =
    stripReplyInstruction(stripOutputContract(prompt)) + "\n\n" + plan.instruction() + "\n\n" + replyContract(plan)
