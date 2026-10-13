package dev.goutou.wingman

import dev.goutou.wingman.config.Backup
import dev.goutou.wingman.config.ConfigData
import dev.goutou.wingman.config.Role
import dev.goutou.wingman.config.Roles
import dev.goutou.wingman.llm.MAX_REPLIES
import dev.goutou.wingman.llm.customStyle
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class BackupTest {

    private val t0 = 1_700_000_000_000L

    private fun sampleCfg() = ConfigData(
        baseUrl = "https://api.example.com/v1",
        apiKey = "sk-secret",
        model = "gpt-4o-mini",
        prompt = "你是军师",
        ctx = 12,
        temperature = 0.5,
        maxTokens = 600,
        minIntervalSec = 30,
        allowSensitive = true,
        glassAlpha = 0.8f,
        glassBlur = 30f,
        bgDim = 0.5f,
        skillId = "full",
        mentorAdvanced = true,
    )

    private fun sampleRoles() = Roles.setProfile(
        Roles.merge(emptyList(), listOf("张三" to dev.goutou.wingman.config.RoleMsg(false, "在吗", t0))),
        "张三", "同事", "同一个组的后端",
    )

    @Test
    fun `导出再导入能把配置和角色搬回来`() {
        val text = Backup.export(sampleCfg(), sampleRoles(), includeApiKey = true)
        val r = Backup.import(text, ConfigData(), emptyList())!!
        assertEquals(sampleCfg(), r.config)
        assertEquals(sampleRoles(), r.roles)
        assertEquals(1, r.roleCount)
    }

    @Test
    fun `不带 API Key 导出时不会冲掉已填的 Key`() {
        val text = Backup.export(sampleCfg(), sampleRoles(), includeApiKey = false)
        assertTrue(!text.contains("sk-secret"))
        val r = Backup.import(text, ConfigData(apiKey = "sk-已经在用了"), emptyList())!!
        assertEquals("sk-已经在用了", r.config.apiKey)
    }

    @Test
    fun `导入是合并角色不是替换`() {
        val existing = listOf(Role(key = "李四", name = "李四", relation = "朋友"))
        val text = Backup.export(sampleCfg(), sampleRoles(), includeApiKey = true)
        val r = Backup.import(text, ConfigData(), existing)!!
        assertEquals(2, r.roles.size)
        assertTrue(r.roles.any { it.name == "李四" })
        assertTrue(r.roles.any { it.name == "张三" && it.relation == "同事" })
    }

    @Test
    fun `坏数据不覆盖现有内容`() {
        assertNull(Backup.import("", ConfigData(), emptyList()))
        assertNull(Backup.import("不是 json", ConfigData(), emptyList()))
        assertNull(Backup.import("""{"app":"TalkTact"}""", ConfigData(), emptyList()))
    }

    /**
     * 条数与风格必须跟着备份走。
     *
     * 漏了这两个字段的后果很具体：用户导出→换机→导入，装回来的是默认三样，
     * 而界面上看不出任何「没导进来」的痕迹。
     */
    @Test
    fun `条数与风格能跟着备份往返`() {
        val cfg = sampleCfg().copy(replyCount = 5, replyStyleIds = listOf("empathy", "brief"))
        val r = Backup.import(Backup.export(cfg, sampleRoles(), includeApiKey = true), ConfigData(), emptyList())!!
        assertEquals(5, r.config.replyCount)
        // 用 List 比：**顺序也要一样** —— 顺序决定「第 1 条走哪个风格」，丢了等于没导对
        assertEquals(listOf("empathy", "brief"), r.config.replyStyleIds)
    }

    @Test
    fun `默认设置不往备份里塞多余键 老备份缺这两项时保持默认`() {
        // 默认值不该写进文件 —— 否则老备份一导入就凭空多出两个键
        val text = Backup.export(sampleCfg(), sampleRoles(), includeApiKey = true)
        assertFalse(text.contains("replyCount"))
        assertFalse(text.contains("replyStyles"))
        // 老备份里没有这两个键 → 导入后仍是默认（3 条 / 无指定 = 老三样）
        val legacy = """{"app":"TalkTact","schema":1,"config":{"ctx":8}}"""
        val r = Backup.import(legacy, ConfigData(), emptyList())!!
        assertEquals(3, r.config.replyCount)
        assertTrue(r.config.replyStyleIds.isEmpty())
    }

    @Test
    fun `备份里越界的条数会被夹回合法区间`() {
        val legacy = """{"app":"TalkTact","schema":1,"config":{"replyCount":999}}"""
        val r = Backup.import(legacy, ConfigData(), emptyList())!!
        assertEquals(MAX_REPLIES, r.config.replyCount)
    }

    /**
     * 自定义风格是整个列表里**唯一没法凭内置清单重建**的东西 —— 别的字段丢了都有默认值兜着，
     * 它丢了就是彻底没了（用户得一条条重敲）。所以往返必须逐字对上，顺序也要对上。
     */
    @Test
    fun `自定义风格能跟着备份往返`() {
        val styles = listOf(customStyle("撒娇", "带点抱怨")!!, customStyle("冷处理")!!)
        val cfg = sampleCfg().copy(replyCustomStyles = styles)
        val r = Backup.import(Backup.export(cfg, sampleRoles(), includeApiKey = true), ConfigData(), emptyList())!!
        assertEquals(styles, r.config.replyCustomStyles)
    }

    @Test
    fun `没有自定义风格时不往备份里塞这个键`() {
        val text = Backup.export(sampleCfg(), sampleRoles(), includeApiKey = true)
        assertFalse("默认配置不该多出这个键：$text", text.contains("replyCustomStyles"))
        // 老备份缺这个键 → 不动当前配置里已有的自定义风格
        val cur = ConfigData(replyCustomStyles = listOf(customStyle("撒娇", "带点抱怨")!!))
        val legacy = """{"app":"TalkTact","schema":1,"config":{"ctx":8}}"""
        val r = Backup.import(legacy, cur, emptyList())!!
        assertEquals(cur.replyCustomStyles, r.config.replyCustomStyles)
    }
}
