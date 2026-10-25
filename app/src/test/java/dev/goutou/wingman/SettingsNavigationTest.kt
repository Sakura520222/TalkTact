package dev.goutou.wingman

import android.content.Context
import androidx.activity.ComponentActivity
import androidx.compose.ui.test.assertIsOff
import androidx.compose.ui.test.assertIsOn
import androidx.compose.ui.test.hasSetTextAction
import androidx.compose.ui.test.hasText
import androidx.compose.ui.test.isToggleable
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performScrollTo
import androidx.compose.ui.test.performTextReplacement
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import dev.goutou.wingman.config.ConfigData
import dev.goutou.wingman.config.ConfigStore
import dev.goutou.wingman.config.PREF_NAME
import dev.goutou.wingman.ui.AdvancedScreen
import dev.goutou.wingman.ui.App
import dev.goutou.wingman.ui.GoutouTheme
import dev.goutou.wingman.ui.SettingsScreen
import dev.goutou.wingman.ui.SettingsSection
import org.junit.Assert.assertEquals
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode

@RunWith(AndroidJUnit4::class)
@Config(sdk = [34], qualifiers = "w411dp-h891dp-xxhdpi")
@GraphicsMode(GraphicsMode.Mode.NATIVE)
class SettingsNavigationTest {
    @get:Rule val rule = createAndroidComposeRule<ComponentActivity>()
    private lateinit var store: ConfigStore

    @Before fun setup() {
        val context = ApplicationProvider.getApplicationContext<Context>()
        context.getSharedPreferences(PREF_NAME, Context.MODE_PRIVATE).edit().clear().commit()
        store = ConfigStore(context)
        store.save(ConfigData(geoEnabled = false, baseUrl = "http://127.0.0.1:1/v1"))
    }

    @Test fun `设置分类从首页直接进入`() {
        var selected: SettingsSection? = null
        rule.setContent { GoutouTheme { SettingsScreen(store, store.load(), {}, onOpenSection = { selected = it }) } }
        listOf("接口与模型" to SettingsSection.CONNECTION, "生成方式" to SettingsSection.GENERATION,
            "分析设置" to SettingsSection.WECHAT, "会话白名单" to SettingsSection.WHITELIST,
            "识图与代理" to SettingsSection.RECOGNITION, "备份与恢复" to SettingsSection.BACKUP).forEach { (label, destination) ->
            rule.onNodeWithText(label).performScrollTo().performClick()
            rule.runOnIdle { assertEquals(destination, selected) }
        }
        rule.onNodeWithText("高级设置").assertDoesNotExist()
    }

    @Test fun `备份默认不包含密钥 由用户显式选择`() {
        rule.setContent { GoutouTheme { AdvancedScreen(store, store.load(), {}, {}, {}, {}, section = SettingsSection.BACKUP) } }
        rule.onAllNodes(isToggleable())[0].assertIsOff().performClick().assertIsOn()
        rule.onNodeWithText("导出配置").assertExists()
    }

    @Test fun `切换页签前可放弃接口草稿 原设置不被覆盖`() {
        val original = store.load().baseUrl
        rule.setContent { App(store) }
        rule.onNodeWithText("设置").performClick()
        rule.onNodeWithText("接口与模型").performScrollTo().performClick()
        rule.onNode(hasSetTextAction() and hasText("接口地址（OpenAI 兼容，写到 /v1）"))
            .performScrollTo().performTextReplacement("http://127.0.0.1:12345/v1")
        rule.onNodeWithText("军师").performClick()
        rule.onNodeWithText("有未保存的设置").assertExists()
        rule.onNodeWithText("放弃修改").performClick()
        rule.runOnIdle { assertEquals(original, store.load().baseUrl) }
        rule.onNodeWithText("内置军师").assertExists()
    }

    @Test fun `接口页保存仅在明确点击时生效`() {
        val original = store.load().baseUrl
        var saved = 0
        rule.setContent { GoutouTheme { AdvancedScreen(store, store.load(), { saved++ }, {}, {}, {}, section = SettingsSection.CONNECTION) } }
        val target = "http://127.0.0.1:12345/v1"
        rule.onNode(hasSetTextAction() and hasText("接口地址（OpenAI 兼容，写到 /v1）"))
            .performScrollTo().performTextReplacement(target)
        rule.runOnIdle { assertEquals(original, store.load().baseUrl) }
        rule.onNodeWithText("保存").performClick()
        rule.runOnIdle { assertEquals(target, store.load().baseUrl); assertEquals(1, saved) }
    }
}
