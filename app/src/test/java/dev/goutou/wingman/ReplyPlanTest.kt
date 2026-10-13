package dev.goutou.wingman

import dev.goutou.wingman.llm.DEFAULT_STYLE_IDS
import dev.goutou.wingman.llm.DEFAULT_PROMPT
import dev.goutou.wingman.llm.DEFAULT_REPLY_COUNT
import dev.goutou.wingman.llm.MAX_CUSTOM_STYLES
import dev.goutou.wingman.llm.MAX_REPLIES
import dev.goutou.wingman.llm.MIN_REPLIES
import dev.goutou.wingman.llm.REPLY_STYLES
import dev.goutou.wingman.llm.ReplyPlan
import dev.goutou.wingman.llm.SuggestionParser
import dev.goutou.wingman.llm.FULL_PROMPT
import dev.goutou.wingman.llm.applyReplyPlan
import dev.goutou.wingman.llm.buildSkillPrompt
import dev.goutou.wingman.llm.coreOf
import dev.goutou.wingman.llm.customStyle
import dev.goutou.wingman.llm.decodeCustomStyles
import dev.goutou.wingman.llm.encodeCustomStyles
import dev.goutou.wingman.llm.gradedReplyPrompt
import dev.goutou.wingman.llm.gradedRiskPrompt
import dev.goutou.wingman.llm.outputContract
import dev.goutou.wingman.llm.stripOutputContract
import dev.goutou.wingman.llm.stripReplyInstruction
import dev.goutou.wingman.llm.outputBudget
import dev.goutou.wingman.llm.plannedReplyCount
import dev.goutou.wingman.llm.toggledStyleIds
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 条数 / 风格这个功能的要害只有一句：**用户改完设置，下一次调用的提示词和解析必须真的跟着变。**
 *
 * 所以这组测试钉的是三件事：归一化（越界/不认识/不够怎么办）、提示词（条数和风格真的写进去了）、
 * 解析（保留条数跟着配置走，而不是写死的 take(4)）。
 */
class ReplyPlanTest {

    private fun ids(plan: ReplyPlan) = plan.styles.map { it.id }

    @Test
    fun `默认规格是三条稳妥幽默推进（老行为不变）`() {
        val p = ReplyPlan.default()
        assertEquals(3, p.count)
        assertEquals(listOf("safe", "humor", "advance"), ids(p))
        assertEquals(DEFAULT_STYLE_IDS, ids(p))
    }

    @Test
    fun `条数越界会被夹回合法区间`() {
        assertEquals(MIN_REPLIES, ReplyPlan.of(0, DEFAULT_STYLE_IDS).count)
        assertEquals(MIN_REPLIES, ReplyPlan.of(-5, DEFAULT_STYLE_IDS).count)

        // 上限要拿「刚好够多的风格」来测：能勾到的就是内置 8 种 + 自定义 8 个 = MAX_REPLIES。
        // 这一行顺带钉住那条不变量 —— 上限必须跟着风格清单一起走，不能再各写各的。
        val customs = (1..MAX_CUSTOM_STYLES).map { customStyle("自定$it")!! }
        val allIds = REPLY_STYLES.map { it.id } + customs.map { it.id }
        assertEquals(MAX_REPLIES, allIds.size)
        assertEquals(MAX_REPLIES, ReplyPlan.of(99, allIds, customs).count)

        // 只勾 1 种、却要求上限条数：会按内置清单补齐，但**补齐也受「能勾到的风格数」限制**
        //（内置一共 8 种）—— 所以条数必须跟着收窄到 8，不能给出
        //「要求 16 条、却只列了 8 种风格」这种自相矛盾的提示词。
        val narrow = ReplyPlan.of(MAX_REPLIES, listOf("safe"))
        assertEquals(REPLY_STYLES.size, narrow.styles.size)
        assertEquals("条数必须等于风格数", narrow.styles.size, narrow.count)
    }

    @Test
    fun `认不出的风格 id 被丢掉，一个都没剩就回落默认三样`() {
        // 不能给用户一张空白卡 —— 那是「我明明配了怎么没反应」
        val p = ReplyPlan.of(3, listOf("不存在", "also-nope"))
        assertEquals(listOf("safe", "humor", "advance"), ids(p))
        assertEquals(3, p.styles.size)
    }

    @Test
    fun `选了少于是会被按顺序补齐，而不是把条数砍掉`() {
        // 关键行为：用户选了 1 种风格却要 3 条 —— 砍成 1 条会让「条数」这个选择静默失效
        val p = ReplyPlan.of(3, listOf("empathy"))
        assertEquals(3, p.count)
        assertEquals(3, p.styles.size)
        assertEquals("empathy", ids(p)[0])
    }

    @Test
    fun `选了多于条数就按顺序截断`() {
        val p = ReplyPlan.of(2, listOf("humor", "empathy", "direct", "brief"))
        assertEquals(2, p.count)
        assertEquals(listOf("humor", "empathy"), ids(p))
    }

    @Test
    fun `重复的勾选不会产生重复的风格`() {
        val p = ReplyPlan.of(3, listOf("safe", "safe", "humor", "safe"))
        assertEquals(p.styles.size, p.styles.map { it.id }.toSet().size)
        assertEquals(3, p.styles.size)
    }

    @Test
    fun `风格顺序跟着用户勾选的顺序走`() {
        val p = ReplyPlan.of(3, listOf("direct", "safe", "formal"))
        assertEquals(listOf("direct", "safe", "formal"), ids(p))
    }

    @Test
    fun `提示词里真的写进了条数和每个风格`() {
        val p = ReplyPlan.of(5, listOf("empathy", "brief"))
        val text = p.instruction()
        assertTrue("应写明要 5 条，实际：$text", text.contains("5"))
        assertTrue(text.contains("empathy") || text.contains("共情"))
        assertTrue(text.contains("简短"))
        // 单风格时不该再要求「策略不同」
        val one = ReplyPlan.of(1, listOf("safe")).instruction()
        assertTrue(one.contains("不必强求"))
    }

    @Test
    fun `JSON 示例跟着风格变（模型照抄示例的倾向很强）`() {
        val plan = ReplyPlan.of(4, listOf("empathy", "direct"))
        val sample = plan.jsonSample()
        // 示例的形状必须正好等于「这次要几条」—— 少一个模型就少写一个，多一个又会被解析时丢掉
        assertEquals(plan.count, Regex("\"style\":").findAll(sample).count())
        // 每个 style 都要是这次真的会用到的那个（补齐进来的也一样），不能是写死的老三样
        plan.styles.forEach { assertTrue("示例里缺 ${it.label}", sample.contains("\"${it.label}\"")) }
        // 用户勾的前两种必须排在最前面 —— 模型倾向于照抄顺序
        assertTrue(sample.indexOf("\"共情\"") < sample.indexOf("\"直接\""))
    }

    @Test
    fun `默认规格下的契约与老行为一致`() {
        val s = outputContract(ReplyPlan.default())
        assertTrue(s.contains("\"replies\""))
        assertTrue(s.contains("\"best\""))
        assertTrue(s.contains("\"why\""))
    }

    @Test
    fun `换规格重拼提示词：旧的条数要求不会残留`() {
        val plan = ReplyPlan.of(5, listOf("empathy", "direct"))
        val rebuilt = applyReplyPlan(DEFAULT_PROMPT, plan)
        // 关键：默认提示词里写着「恰好给 3 条」，重拼后必须换成 5 条且不留旧的
        assertTrue(rebuilt.contains("恰好给 5 条"))
        assertFalse("不该残留旧条数：$rebuilt", rebuilt.contains("恰好给 3 条"))
        assertTrue(rebuilt.contains("共情"))
    }

    @Test
    fun `重拼提示词是幂等的：连着换两次不会越叠越多`() {
        val a = applyReplyPlan(DEFAULT_PROMPT, ReplyPlan.of(4, listOf("safe")))
        val b = applyReplyPlan(a, ReplyPlan.of(2, listOf("humor")))
        val c = applyReplyPlan(b, ReplyPlan.of(2, listOf("humor")))
        assertEquals(b, c)
        assertEquals(1, Regex("【这次要几条、什么风格】").findAll(b).count())
    }

    @Test
    fun `分级模式风险那一路不带上条数要求（它根本不写回复）`() {
        val risk = gradedRiskPrompt(DEFAULT_PROMPT)
        assertTrue(risk.contains("风险评估"))
        assertFalse("风险那一路不该要求条数：$risk", risk.contains("恰好给"))
    }

    @Test
    fun `分级模式写回复那一路按配置来`() {
        val plan = ReplyPlan.of(5, listOf("empathy", "brief"))
        val reply = gradedReplyPrompt(DEFAULT_PROMPT, plan)
        assertTrue(reply.contains("只写回复"))
        assertTrue(reply.contains("恰好给 5 条"))
        assertTrue(reply.contains("简短"))
        assertEquals(1, Regex("【这次要几条、什么风格】").findAll(reply).count())
    }

    @Test
    fun `摘规格要求后不会留下没有头的列表项`() {
        val stripped = stripReplyInstruction(DEFAULT_PROMPT)
        // 整段（标题 + 列表项 + 说明）都要走，不能只砍标题那行、留下一堆没头的 "- 第1条走…"
        assertFalse(stripped.contains("恰好给"))
        assertFalse(stripped.contains("第1条走"))
        assertFalse(stripped.contains("真正不同的策略"))
        // 但正文必须还在 —— 摘这一段是「换规格」，不是「把 skill 洗掉」
        assertTrue(stripped.contains("狗头军师"))
    }

    @Test
    fun `解析保留的条数跟着配置走，而不是写死四条`() {
        val raw = (1..6).joinToString(",") { """{"style":"s$it","text":"t$it"}""" }
        val full = """{"intent":"i","risk":"低","note":"","replies":[$raw]}"""
        assertEquals(4, SuggestionParser.parse(full).replies.size)
        assertEquals(6, SuggestionParser.parse(full, maxReplies = 6).replies.size)
        assertEquals(2, SuggestionParser.parse(full, maxReplies = 2).replies.size)
    }

    @Test
    fun `best 下标按截断后的列表算（多给一条也不会错位）`() {
        val raw = """{"intent":"i","risk":"低","note":"","best":4,"replies":[{"style":"a","text":"1"},{"style":"b","text":"2"},{"style":"c","text":"3"},{"style":"d","text":"4"},{"style":"e","text":"5"}]}"""
        // 保留 5 条，best=4 仍然指向最后一条
        val s = SuggestionParser.parse(raw, maxReplies = 5)
        assertEquals(5, s.replies.size)
        // ⚠️ 刻意不写 assertEquals(4, s.best)：best 是 Int?，
        // 那样写会落到 assertEquals(Object, Object) 上 —— 既在某些 Kotlin 版本上重载歧义，
        // best 为 null 时又是拿Integer 和 null 比，报错信息还完全指不到真正的原因。
        assertEquals(4, s.best ?: -1)
        // 越界的 best 不该被当成 0（那会静默指向第一条，用户以为模型推荐了它）
        val over = SuggestionParser.parse(
            raw.replace("\"best\":4", "\"best\":9"),
            maxReplies = 5,
        )
        assertEquals(-1, over.best ?: -1)
    }

    @Test
    fun `风格清单本身的 id 不能重复（配错会静默丢一项）`() {
        assertEquals(REPLY_STYLES.size, REPLY_STYLES.map { it.id }.toSet().size)
        // 每个都要有可用说明，否则模型收到的要求是空的
        REPLY_STYLES.forEach { assertTrue("${it.id} 缺说明", it.hint.isNotBlank()) }
    }

    // ---------------- 满血版 few-shot 示例：两个真踩过的坑 ----------------

    /**
     * 满血版正文里的【例子】不能在重拼时被砍掉。
     *
     * 以前 [coreOf] 用「最后一个 `{"intent"`」定位输出契约，而满血版正文里那条
     * few-shot 示例**也**含 `{"intent"`，于是每次重拼都把【例子】连同它上面那句
     * 「【例子】只看结构…」一起丢掉了 —— 而那是满血版给模型的结构锚点。
     *
     * 现在契约改用引导句「只输出下面这个 JSON」定位。
     */
    @Test
    fun `重拼不能砍掉满血版的 few-shot 示例`() {
        val rebuilt = applyReplyPlan(FULL_PROMPT, ReplyPlan.of(4, listOf("empathy", "direct")))
        assertTrue("【例子】那段必须还在：$rebuilt", rebuilt.contains("【例子】"))
        assertTrue("示例的输入行必须还在", rebuilt.contains("输入：对方: 周末有空吗"))
        assertTrue("示例里 intent 字段必须还在（只该换 replies 数组）", rebuilt.contains("想约你见面"))
    }

    /**
     * 满血版正文里有一句 `按「这次要几条、什么风格」那一段指定的策略来写`，
     * 标题是**嵌在句子中间**的。识别规格段时如果只看「有没有这个词」，
     * 就会误判那一句是规格段，往回整段砍 —— 把这句和它后面的【例子】一起丢掉。
     * 所以判据必须是「标题在行首」。
     */
    @Test
    fun `正文里引用规格段标题的那句话不能被当成规格段砍掉`() {
        val quote = "每条按「这次要几条、什么风格」那一段指定的策略来写"
        assertTrue("前提：满血版正文确实有这句引用", FULL_PROMPT.contains(quote))
        val rebuilt = applyReplyPlan(FULL_PROMPT, ReplyPlan.of(4, listOf("empathy", "direct")))
        assertTrue("这句引用必须原样保留：$rebuilt", rebuilt.contains(quote))
        // 真正的规格段只有一份
        assertEquals(1, Regex("【这次要几条、什么风格】").findAll(rebuilt).count())
    }

    /**
     * 反复重拼后 few-shot 示例始终跟着当前规格。
     *
     * 只针对满血版 —— 只有它的正文里有【例子】这段（另外两个内置 skill 没有）。
     *
     * 记一个坑：这条断言第一次写的时候用了 `applyReplyPlan(FULL_PROMPT, …)` 起步，
     * 但真正出问题的是**第二次**之后 —— 占位符在第一次就被消耗掉了，
     * 于是示例冻结在 3 条。所以这里必须真的循环调，不能只断言一次。
     */
    @Test
    fun `反复重拼后 few-shot 示例始终跟着当前规格`() {
        var p = FULL_PROMPT
        // 条数 2→3→4→5→6，每步都从上一份重拼（模拟用户反复改设置）
        for (n in 2..6) {
            p = applyReplyPlan(p, ReplyPlan.of(n, listOf("safe", "humor", "advance")))
            val example = p.substringAfter("【例子】").substringBefore("只输出下面这个 JSON")
            assertEquals(
                "重拼到 $n 条时，正文示例也该是 $n 条",
                n,
                Regex("\"style\":").findAll(example).count(),
            )
            assertTrue("重拼到 $n 条时规格段要写 $n 条", p.contains("恰好给 $n 条"))
            // 示例那一整段 JSON 还得是合法的（曾经坏成 `"why":"…",__PH__}`）
            assertTrue("示例的 replies 键名不能被吃掉", example.contains("\"replies\":"))
        }
        // 换风格也一样
        p = applyReplyPlan(p, ReplyPlan.of(3, listOf("empathy", "direct", "brief")))
        val ex3 = p.substringAfter("【例子】").substringBefore("只输出下面这个 JSON")
        assertEquals(3, Regex("\"style\":").findAll(ex3).count())
        assertTrue("示例里要有新选的风格：$ex3", ex3.contains("共情"))
    }

    /**
     * 占位符还原必须只动 replies 数组，外面那半截 JSON 一个字都不能少。
     *
     * 也锁住「还原之后能不能再填回去」—— 只还原不填，等于下一次重拼还是错的。
     */
    @Test
    fun `还原占位符只替换 replies 数组`() {
        val withPlaceholder = coreOf(FULL_PROMPT)
        assertTrue("正文里应该留着占位符：$withPlaceholder", withPlaceholder.contains("__FULL_EXAMPLE_REPLIES__"))
        // 还原一次再填一次，应该完全回到原样
        assertEquals(FULL_PROMPT, buildSkillPrompt(withPlaceholder, ReplyPlan.default()))
    }

    /**
     * 输出契约被**摘掉**时不能顺手删掉正文 —— 满血版的 few-shot 示例也含 `{"intent"`，
     * 只按「最后一个 `{"intent"`」砍会把它当成契约误删。
     *
     * 兜底判据：契约后面必有那句 `best = …下标（从 0 开始）`，示例后面没有。
     */
    @Test
    fun `没有引导句时也不能把正文里的示例当契约删掉`() {
        // 模拟用户把契约的引导句删了（提示词页可以自由编辑）
        val noHead = stripOutputContract(FULL_PROMPT.replace("只输出下面这个 JSON，不要 markdown、不要任何多余文字：", "按下面的格式给："))
        assertTrue("示例段落必须还在", noHead.contains("【例子】"))
        assertTrue("示例里的 intent 字段必须还在", noHead.contains("想约你见面"))
        assertTrue("契约确实被摘掉了", !noHead.contains("下标（从 0 开始）；why"))
    }

    // ── 自定义风格（用户在设置页自己输入的那种） ────────────────────────────────

    /**
     * 自定义风格不是「给候选贴个标签」，它必须走完整条链路：
     * 认得出 id → 进得了规格段 → JSON 示例里的 style 也跟着换。
     * 少任何一环，用户看到的就是「加了但模型不按它写」。
     */
    @Test
    fun `自定义风格能被选中，并且真的写进提示词`() {
        val st = customStyle("撒娇", "用撒娇的语气，带点抱怨")!!
        val plan = ReplyPlan.of(1, listOf(st.id), listOf(st))
        assertEquals(listOf("撒娇"), plan.labels)

        val p = applyReplyPlan(DEFAULT_PROMPT, plan)
        assertTrue("规格段要写上自定义风格的名字：$p", p.contains("撒娇"))
        assertTrue("用户写的说明也要进提示词：$p", p.contains("用撒娇的语气"))
        assertTrue("JSON 示例里的 style 也要换成它（模型照抄示例）：$p", p.contains("\"style\":\"撒娇\""))
    }

    @Test
    fun `只写了名字的自定义风格会兜一句说明`() {
        val st = customStyle("冷处理")!!
        assertTrue("必须带一句策略说明，只给名字模型会把它当标签照抄：${st.hint}", st.hint.contains("冷处理"))
    }

    @Test
    fun `空名字加不进来（返回 null 让界面去提示）`() {
        assertEquals(null, customStyle("   "))
        assertEquals(null, customStyle(""))
    }

    /**
     * 长名字**只截显示，不截 id**。
     *
     * 早先 `id` 是拿截断后的名字拼的（`custom:` + 药丸上那个短名），于是两个前 8 个字相同的
     * 长名字会拼出**同一个 id**：第二个加进来直接报「已经加过了」，从偏好里解码时还会被
     * `distinctBy { it.id }` 静默丢掉一条。用户看到的是「我明明加了两个，只剩一个」。
     */
    @Test
    fun `长名字只在药丸上截断，id 用的仍是完整名字`() {
        val a = customStyle("先用撒娇的语气抱怨他")!!
        val b = customStyle("先用撒娇的语气抱怨你")!!
        assertTrue("药丸上要短：${a.displayLabel}", a.displayLabel.length <= 9)
        assertEquals("全名要留着（进提示词用）", "先用撒娇的语气抱怨他", a.label)
        assertNotEquals("前 8 个字相同的两个名字不能撞成同一个 id", a.id, b.id)
        // 而且编码往返之后 id 不能变 —— 变了就等于「重启后勾选丢了」
        assertEquals(listOf(a, b), decodeCustomStyles(encodeCustomStyles(listOf(a, b))))
    }

    @Test
    fun `自定义风格的编解码能原样往返，顺序不丢`() {
        val list = listOf(customStyle("撒娇", "带点抱怨")!!, customStyle("冷处理")!!)
        assertEquals(list, decodeCustomStyles(encodeCustomStyles(list)))
    }

    /**
     * 坏一条只丢那一条。整体作废等于「存坏一个字符，用户全部自定义风格没了」——
     * 而这个编码是拼在一行里的，这种事故一点都不罕见。
     */
    @Test
    fun `编码里出现空行或残行时只跳过那一条`() {
        val raw = encodeCustomStyles(listOf(customStyle("撒娇", "带点抱怨")!!)) + "\n\n" + "\u0001只有说明没有名字\n"
        val back = decodeCustomStyles(raw)
        assertEquals(1, back.size)
        assertEquals("撒娇", back[0].label)
    }

    @Test
    fun `补齐条数只从内置清单补，不拿自定义的凑数`() {
        val st = customStyle("撒娇", "带点抱怨")!!
        val plan = ReplyPlan.of(3, listOf(st.id), listOf(st))
        assertEquals(listOf("撒娇", "稳妥", "幽默"), plan.labels)
    }

    @Test
    fun `删掉的自定义风格会被忽略，不会在规格里留个空位`() {
        val st = customStyle("撒娇", "带点抱怨")!!
        // 风格列表里已经没有它了（用户删了），但选中集里还残着它的 id
        val plan = ReplyPlan.of(3, listOf(st.id, "safe", "humor"), emptyList())
        assertEquals(listOf("稳妥", "幽默", "推进"), plan.labels)
    }

    @Test
    fun `加完自定义风格它一定在勾选集里，不会被末尾挤掉`() {
        val st = customStyle("撒娇", "带点抱怨")!!
        val known = REPLY_STYLES.map { it.id }.toSet() + st.id
        // 界面上「加完直接选中」时就是这么算的：新加的排最前，再按条数截断
        val next = toggledStyleIds(DEFAULT_STYLE_IDS, st.id, 3, known)
        assertEquals("新加的必须排最前，让位的是末尾那个", listOf(st.id, "safe", "humor"), next)
        assertEquals("总数仍然等于条数", 3, next.size)
    }

    // ── 条数 = 勾选的个数（设置页上已经没有「条数」这一项了） ──────────────────

    @Test
    fun `条数就是勾选的个数，一种都没勾才回落到默认`() {
        assertEquals(5, plannedReplyCount(listOf("a", "b", "c", "d", "e")))
        assertEquals(DEFAULT_REPLY_COUNT, plannedReplyCount(emptyList()))
        assertEquals("越界要夹紧", MAX_REPLIES, plannedReplyCount(List(99) { "x$it" }))
        assertEquals(MIN_REPLIES, plannedReplyCount(emptyList(), fallback = 0))
    }

    /**
     * 输出预算必须**跟着条数抬高**。那几档额度（200/400/600）在勾到十几条时一定装不下，
     * 装不下的后果是 JSON 被截断 —— 看起来只像「模型偷懒少写了几条」，极难查。
     */
    @Test
    fun `条数多了要把输出预算抬上去，无限制就原样不动`() {
        assertEquals("3 条时 400 够用，不该动它", 400, outputBudget(400, 3))
        assertTrue("6 条已经超过 400，必须抬", outputBudget(400, 6) > 400)
        assertTrue("16 条要抬得更高", outputBudget(400, 16) > outputBudget(400, 6))
        assertEquals("配置成 0 = 无限制，原样传", 0, outputBudget(0, 16))
        assertEquals("配置本来就比需要大，听配置的", 2000, outputBudget(2000, 3))
    }

    // ── 多选（点药丸）的规则 ──────────────────────────────────────────────────

    private val builtInIds = REPLY_STYLES.map { it.id }.toSet()

    @Test
    fun `多选：新点的一定留下，位置不够让末尾的`() {
        assertEquals(
            listOf("empathy", "safe", "humor"),
            toggledStyleIds(listOf("safe", "humor", "advance"), "empathy", 3, builtInIds),
        )
    }

    @Test
    fun `多选：取消一个已勾选的只是少一个`() {
        assertEquals(
            listOf("safe", "advance"),
            toggledStyleIds(listOf("safe", "humor", "advance"), "humor", 3, builtInIds),
        )
    }

    @Test
    fun `多选：点掉最后一个时保持现状`() {
        assertEquals(listOf("safe"), toggledStyleIds(listOf("safe"), "safe", 1, builtInIds))
    }

    /**
     * 这一条钉的是**多选最要命的那个 bug**。
     *
     * 用户只勾了 1 个风格、条数 3 → 生效表会被自动补齐成 [safe, humor, advance]，
     * 界面上**三个都亮着**。他点「幽默」想取消 —— 如果拿「生效表」算，就是「减掉幽默」，
     * 于是只剩 2 个、下一轮又被补齐回幽默，界面上**毫无变化**，用户只会说「点了没反应」。
     *
     * 所以规则只看**显式勾选**那份：勾选集里本来就没有幽默，点它等于**勾上**它。
     */
    @Test
    fun `多选：点一个「自动补齐、界面上亮着」的风格不会白点`() {
        // 生效表里有幽默（补齐进来的），但用户显式只勾了 safe
        val plan = ReplyPlan.of(3, listOf("safe"))
        assertEquals(listOf("稳妥", "幽默", "推进"), plan.labels)

        assertEquals(
            "没真的勾过 → 点它等于勾上它",
            listOf("humor", "safe"),
            toggledStyleIds(listOf("safe"), "humor", 3, builtInIds),
        )
        assertEquals(
            "真的勾过 → 点它就能取消，而且不会被补齐回来占住位置",
            listOf("humor", "advance"),
            toggledStyleIds(listOf("safe", "humor", "advance"), "safe", 3, builtInIds),
        )
    }

    /**
     * 取消勾选之后**不能**把「补齐进来的」当作用户勾过的存下去 ——
     * 存下去的话那个风格会一直占着位置，用户再怎么点也去不掉。
     */
    @Test
    fun `多选：取消勾选后剩下的是用户真的勾过的，不是补齐出来的`() {
        val next = toggledStyleIds(listOf("safe", "humor", "advance"), "advance", 3, builtInIds)
        assertEquals(listOf("safe", "humor"), next)
        // 重算规格：只剩 2 个 → 补齐回 3 个，但**配置里仍然只有 2 个**
        val plan = ReplyPlan.of(3, next)
        assertEquals(3, plan.styles.size)
        assertEquals("稳妥", plan.labels[0])
        assertEquals("幽默", plan.labels[1])
    }

    @Test
    fun `多选：已删掉的自定义风格不会白占一个位置`() {
        val st = customStyle("撒娇")!!
        // 自定义风格已经被删了 → 它不在 known 里
        assertEquals(
            listOf("safe"),
            toggledStyleIds(listOf(st.id, "safe"), "safe", 3, builtInIds),
        )
    }

    /**
     * 勾选的**顺序**就是「第 1 条走哪个风格」的顺序，落进规格要能对上。
     * 这也是配置层从 `Set` 换成保序 `List` 的原因：Set 存不住顺序，
     * 丢了顺序之后用户每次重启看到的第 1/2/3 条风格都在变。
     */
    @Test
    fun `多选：先勾的排在后面，但顺序稳定可预测`() {
        var ids = emptyList<String>()
        ids = toggledStyleIds(ids, "empathy", 3, builtInIds)
        ids = toggledStyleIds(ids, "brief", 3, builtInIds)
        ids = toggledStyleIds(ids, "formal", 3, builtInIds)
        assertEquals(listOf("formal", "brief", "empathy"), ids)
        assertEquals(listOf("正式", "简短", "共情"), ReplyPlan.of(3, ids).labels)
    }
}
