package dev.goutou.wingman

import dev.goutou.wingman.llm.DEFAULT_STYLE_IDS
import dev.goutou.wingman.llm.DEFAULT_PROMPT
import dev.goutou.wingman.llm.MAX_REPLIES
import dev.goutou.wingman.llm.MIN_REPLIES
import dev.goutou.wingman.llm.REPLY_STYLES
import dev.goutou.wingman.llm.ReplyPlan
import dev.goutou.wingman.llm.SuggestionParser
import dev.goutou.wingman.llm.FULL_PROMPT
import dev.goutou.wingman.llm.applyReplyPlan
import dev.goutou.wingman.llm.buildSkillPrompt
import dev.goutou.wingman.llm.coreOf
import dev.goutou.wingman.llm.gradedReplyPrompt
import dev.goutou.wingman.llm.gradedRiskPrompt
import dev.goutou.wingman.llm.outputContract
import dev.goutou.wingman.llm.stripOutputContract
import dev.goutou.wingman.llm.stripReplyInstruction
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
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
        assertEquals(MAX_REPLIES, ReplyPlan.of(99, DEFAULT_STYLE_IDS).count)
        // 上限 6 但只有 8 个风格，所以补得齐
        assertEquals(MAX_REPLIES, ReplyPlan.of(MAX_REPLIES, listOf("safe")).styles.size)
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
}