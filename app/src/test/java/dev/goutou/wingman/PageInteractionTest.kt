package dev.goutou.wingman

import android.content.Context
import androidx.activity.ComponentActivity
import androidx.compose.runtime.Composable
import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.test.SemanticsMatcher
import androidx.compose.ui.test.assert
import androidx.compose.ui.test.assertIsEnabled
import androidx.compose.ui.test.assertIsNotEnabled
import androidx.compose.ui.test.hasSetTextAction
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.test.onAllNodesWithText
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performScrollTo
import androidx.compose.ui.test.performTextReplacement
import androidx.compose.ui.text.AnnotatedString
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import dev.goutou.wingman.config.ConfigData
import dev.goutou.wingman.config.ConfigStore
import dev.goutou.wingman.config.PREF_NAME
import dev.goutou.wingman.config.Role
import dev.goutou.wingman.config.SELF_ROLE_KEY
import dev.goutou.wingman.llm.BUILT_IN_SKILLS
import dev.goutou.wingman.llm.DEFAULT_PROMPT
import dev.goutou.wingman.ui.GoutouTheme
import dev.goutou.wingman.ui.MentorScreen
import dev.goutou.wingman.ui.RolesScreen
import dev.goutou.wingman.ui.TrialScreen
import dev.goutou.wingman.ui.trialMessagesOf
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode

/** 新页面的真实点击与保存回归；只使用合成配置，不访问模型服务。 */
@RunWith(AndroidJUnit4::class)
@Config(sdk = [34], qualifiers = "w411dp-h891dp-xxhdpi")
@GraphicsMode(GraphicsMode.Mode.NATIVE)
class PageInteractionTest {
    @get:Rule
    val rule = createAndroidComposeRule<ComponentActivity>()

    private lateinit var store: ConfigStore

    @Before
    fun resetSyntheticStore() {
        val context = ApplicationProvider.getApplicationContext<Context>()
        context.getSharedPreferences(PREF_NAME, Context.MODE_PRIVATE).edit().clear().commit()
        store = ConfigStore(context)
        store.save(ConfigData())
    }

    private fun page(content: @Composable () -> Unit) {
        rule.setContent { GoutouTheme(dark = false, content = content) }
    }

    private fun assertEditorText(expected: String) {
        rule.onNode(hasSetTextAction()).assert(
            SemanticsMatcher.expectValue(SemanticsProperties.EditableText, AnnotatedString(expected)),
        )
    }

    @Test
    fun `内置军师与导入直接可达 选择预设立即生效`() {
        store.save(store.load().copy(maxTokens = 512))
        var saved = 0
        page { MentorScreen(store, 0.92f) { saved++ } }

        BUILT_IN_SKILLS.forEach { preset -> rule.onAllNodesWithText(preset.name)[0].assertExists() }
        rule.onNodeWithText("从 GitHub 导入").assertExists()
        rule.onNodeWithText("新手设置").assertDoesNotExist()
        rule.onNodeWithText("程序员搭子").performScrollTo().performClick()

        rule.runOnIdle {
            val config = store.load()
            assertEquals("coder", config.skillId)
            assertEquals(BUILT_IN_SKILLS.first { it.id == "coder" }.prompt, config.prompt)
            assertEquals(0, config.maxTokens)
            assertEquals(1, saved)
        }
    }

    @Test
    fun `编辑提示词保存补齐输出格式并保留其他设置`() {
        val endpoint = "https://model.example.invalid/v1"
        store.save(store.load().copy(baseUrl = endpoint, maxTokens = 768, minIntervalSec = 23))
        val configuredLimit = store.load().maxTokens
        page { MentorScreen(store, 0.92f) { } }

        rule.onNodeWithText("编辑提示词").performClick()
        rule.onNode(hasSetTextAction()).performTextReplacement("请使用简短、自然的语气回复。")
        rule.onNodeWithText("保存提示词").performScrollTo().performClick()

        rule.onNodeWithText("提示词已保存").assertExists()
        rule.runOnIdle {
            val config = store.load()
            assertEquals("custom", config.skillId)
            assertTrue(config.prompt.startsWith("请使用简短、自然的语气回复。"))
            assertTrue(config.prompt.contains("replies"))
            assertTrue(config.prompt.contains("intent"))
            assertEquals(endpoint, config.baseUrl)
            assertEquals(configuredLimit, config.maxTokens)
            assertEquals(23, config.minIntervalSec)
        }
    }

    @Test
    fun `未保存返回需确认 外部更新不会被旧草稿覆盖`() {
        page { MentorScreen(store, 0.92f) { } }
        rule.onNodeWithText("编辑提示词").performClick()
        val draft = "本地草稿，不应覆盖另一处刚保存的提示词。"
        rule.onNode(hasSetTextAction()).performTextReplacement(draft)

        rule.runOnIdle { rule.activity.onBackPressedDispatcher.onBackPressed() }
        rule.onNodeWithText("放弃未保存的修改？").assertExists()
        rule.runOnIdle { assertEquals(DEFAULT_PROMPT, store.load().prompt) }
        rule.onNodeWithText("继续编辑").performClick()
        assertEditorText(draft)

        val external = "$DEFAULT_PROMPT\n不要替对方承诺日期。"
        rule.runOnIdle { store.save(store.load().copy(prompt = external, skillId = "custom")) }
        rule.onNodeWithText("保存提示词").performScrollTo().performClick()
        rule.onNodeWithText("当前军师已在别处修改，尚未覆盖你的草稿。").assertExists()
        assertEditorText(draft)
        rule.runOnIdle { assertEquals(external, store.load().prompt) }

        rule.onNodeWithText("载入当前版本").performScrollTo().performClick()
        rule.onNodeWithText("载入当前版本？").assertExists()
        rule.onNodeWithText("载入").performClick()
        assertEditorText(external)
    }

    @Test
    fun `角色搜索支持关系和识别名并正确打开本人入口`() {
        store.saveRoles(
            listOf(
                Role(key = "会话甲", name = "小林", relation = "同事"),
                Role(key = "会话乙", name = "阿青", relation = "朋友"),
            ),
        )
        var opened: String? = null
        page { RolesScreen(store, 0.92f, open = null) { opened = it } }

        rule.onNodeWithText("我的说话风格").assertExists()
        rule.onNode(hasSetTextAction()).performTextReplacement("同事")
        rule.onNodeWithText("小林").assertExists()
        rule.onNodeWithText("阿青").assertDoesNotExist()
        rule.onNodeWithText("小林").performScrollTo().performClick()
        rule.runOnIdle { assertEquals("会话甲", opened) }

        rule.onNode(hasSetTextAction()).performTextReplacement("会话乙")
        rule.onNodeWithText("小林").assertDoesNotExist()
        rule.onNodeWithText("阿青").performScrollTo().performClick()
        rule.runOnIdle { assertEquals("会话乙", opened) }

        rule.onNodeWithContentDescription("清除角色搜索").performScrollTo().performClick()
        rule.onNodeWithText("我的说话风格").performScrollTo().performClick()
        rule.runOnIdle {
            assertEquals(SELF_ROLE_KEY, opened)
            assertEquals(3, store.roles().size)
        }
    }

    @Test
    fun `试聊可清空恢复示例且空白不能生成`() {
        page { TrialScreen(store, 0.92f) }
        rule.onNodeWithText("生成候选回复").assertIsEnabled()
        rule.onNodeWithText("清空").performScrollTo().performClick()
        assertEditorText("")
        rule.onNodeWithText("生成候选回复").assertIsNotEnabled()
        rule.onNodeWithText("清空").assertIsNotEnabled()

        rule.onNodeWithText("示例").performClick()
        rule.onNodeWithText("生成候选回复").assertIsEnabled()
        assertEditorText("对方: 在吗\n我: 在\n对方: 周末有空吗，想约你吃个饭")
        rule.onNode(hasSetTextAction()).performTextReplacement("  \n \t ")
        rule.onNodeWithText("生成候选回复").assertIsNotEnabled()
        rule.runOnIdle { assertEquals(0 to 0, store.usage()) }
    }
}

class TrialMessageParsingTest {
    @Test
    fun `去掉说话人前缀但保留网址时间和普通冒号正文`() {
        val messages = trialMessagesOf(
            "我: 会议改到 10:30\n对方：https://example.invalid:8443/a:b\n备注: 不要拆开这一行\n",
        )
        assertEquals(3, messages.size)
        assertTrue(messages[0].fromMe)
        assertEquals("会议改到 10:30", messages[0].text)
        assertFalse(messages[1].fromMe)
        assertEquals("https://example.invalid:8443/a:b", messages[1].text)
        assertFalse(messages[2].fromMe)
        assertEquals("备注: 不要拆开这一行", messages[2].text)
    }

    @Test
    fun `空行被忽略且带空白的全角说话人标记仍能识别`() {
        val messages = trialMessagesOf(" \n 我 ： 可以 \n\n对方 : 好 \n")
        assertEquals(2, messages.size)
        assertTrue(messages[0].fromMe)
        assertEquals("可以", messages[0].text)
        assertFalse(messages[1].fromMe)
        assertEquals("好", messages[1].text)
    }
}
