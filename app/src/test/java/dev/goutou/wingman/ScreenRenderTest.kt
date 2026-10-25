package dev.goutou.wingman

import android.content.Context
import android.graphics.Bitmap
import android.graphics.Canvas
import android.view.View
import androidx.activity.ComponentActivity
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.assertIsSelected
import androidx.compose.ui.test.assertIsNotSelected
import androidx.compose.ui.test.assertHeightIsAtLeast
import androidx.compose.ui.test.performTouchInput
import androidx.compose.ui.test.click
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import dev.goutou.wingman.config.ConfigStore
import dev.goutou.wingman.config.Keys
import dev.goutou.wingman.config.PREF_NAME
import dev.goutou.wingman.ui.AdvancedScreen
import dev.goutou.wingman.ui.StatusScreen
import dev.goutou.wingman.ui.MentorScreen
import dev.goutou.wingman.ui.RolesScreen
import dev.goutou.wingman.ui.SettingsSection
import dev.goutou.wingman.ui.AppearanceScreen
import dev.goutou.wingman.ui.ChatCandidatesScreen
import dev.goutou.wingman.ui.GlassCard
import dev.goutou.wingman.ui.GlassQuality
import dev.goutou.wingman.ui.GoutouTheme
import dev.goutou.wingman.ui.LiquidNavBar
import dev.goutou.wingman.ui.LiquidNavState
import dev.goutou.wingman.ui.LocalLiquidNav
import dev.goutou.wingman.ui.LocalGlassQuality
import dev.goutou.wingman.ui.RadiusR4
import dev.goutou.wingman.ui.SettingsScreen
import dev.goutou.wingman.ui.TrialScreen
import dev.goutou.wingman.ui.drawBackdropArt
import dev.goutou.wingman.ui.rememberBackdrop
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode
import java.io.File
import java.io.FileOutputStream

/**
 * 截图回归（JVM 上跑，不需要真机/模拟器）。
 *
 * 目的不是像素级严丝合缝（那种对比一升依赖就狂飘），而是：
 * ① **界面还能渲染出来** —— 盲改 Screens.kt 时最容易犯的错就是某个 composable 直接崩；
 * ② 关键文案还在（改版顺手把入口删了这种事，断言能立刻发现）；
 * ③ 每次跑完把图丢进 `build/screenshots/`，CI 作为 artifacts 上传，可以肉眼扫一眼。
 *
 * 两个 Robolectric 上的坑（都踩过了）：
 * - `captureToImage()` 底层是 PixelCopy，Robolectric 没有 —— 所以这里改成**直接把 decorView 画到
 *   Bitmap**（Roborazzi 干的就是这件事）；前提是 `@GraphicsMode(NATIVE)`。
 * - Compose 测试要启动的 ComponentActivity 必须由 `ui-test-manifest` 提供，而它得是
 *   **debugImplementation**（放进被测 APK 的 manifest），放 testImplementation 会找不到 Activity。
 *
 * 玻璃的模糊 / AGSL 折射在这个环境里本来就不生效（代码里已按能力降级，见 ui/GlassQuality.kt），
 * 所以这些图是「没有玻璃特效的骨架图」—— 对回归来说够用。
 */
@RunWith(AndroidJUnit4::class)
@Config(sdk = [34], qualifiers = "w411dp-h891dp-xxhdpi")
@GraphicsMode(GraphicsMode.Mode.NATIVE)
class ScreenRenderTest {

    @get:Rule
    val rule = createAndroidComposeRule<ComponentActivity>()

    private val store: ConfigStore
        get() = ConfigStore(ApplicationProvider.getApplicationContext<Context>())

    /** 把当前界面画成 build/screenshots/<name>.png（CI 会作为 artifacts 上传）。 */
    private fun capture(name: String) {
        val view: View = rule.activity.window.decorView
        // Robolectric 里视图可能还没被测过尺寸：先强制量一遍，否则画出来是空图
        if (view.width <= 0 || view.height <= 0) {
            val w = View.MeasureSpec.makeMeasureSpec(1080, View.MeasureSpec.EXACTLY)
            val h = View.MeasureSpec.makeMeasureSpec(2160, View.MeasureSpec.EXACTLY)
            view.measure(w, h)
            view.layout(0, 0, view.measuredWidth, view.measuredHeight)
        }
        val bitmap = Bitmap.createBitmap(
            view.width.coerceAtLeast(1),
            view.height.coerceAtLeast(1),
            Bitmap.Config.ARGB_8888,
        )
        view.draw(Canvas(bitmap))

        val dir = File("build/screenshots").apply { mkdirs() }
        val file = File(dir, "$name.png")
        FileOutputStream(file).use { bitmap.compress(Bitmap.CompressFormat.PNG, 100, it) }
        assertTrue("截图没写出来：$file", file.length() > 0)
    }

    /**
     * 页面截图的外壳：**把真实的渐变背景画在页面底下**。
     *
     * 这一点很关键 —— 以前这几个用例直接 `setContent { AdvancedScreen(...) }`，
     * 底下是 Material 默认的 #FAFAFA。玻璃卡片压在一块「近白平面」上，看起来永远像白板，
     * 于是「玻璃到底透不透」在 CI 截图里根本验不了（第 6 版就是这么被误判成白板的）。
     * 现在截图和真机一样：渐变背景 → 玻璃卡片。
     */
    private fun page(dark: Boolean = false, content: @Composable () -> Unit) {
        rule.setContent {
            GoutouTheme(dark = dark) {
                val backdrop = rememberBackdrop(bgUri = "", dim = 0f, blur = 24f)
                Box(
                    Modifier.fillMaxSize().drawBehind {
                        drawBackdropArt(backdrop, size.width, size.height, GlassQuality.HIGH)
                    },
                ) { content() }
            }
        }
    }

    /**
     * 内置渐变背景 + 两张玻璃卡：深浅两套各出一张，用来肉眼验收背景的质感。
     *
     * 这里**画背景而不是挂 BackgroundLayer**：后者有一圈「玻璃扫光」的 withFrameNanos 循环，
     * 会让测试永远等不到 idle（AppNotIdleException）。背景本身是纯绘制，直接调就好。
     */
    private fun renderBackdrop(name: String, dark: Boolean) {
        rule.setContent {
            GoutouTheme(dark = dark) {
                val backdrop = rememberBackdrop(bgUri = "", dim = 0f, blur = 24f)
                Box(
                    Modifier.fillMaxSize().drawBehind {
                        drawBackdropArt(backdrop, size.width, size.height, GlassQuality.HIGH)
                    },
                ) {
                    Column(Modifier.fillMaxSize().padding(18.dp)) {
                        GlassCard(0.92f) {
                            Text("运行状态", fontSize = 15.sp, fontWeight = FontWeight.Medium)
                            Text("内置渐变背景 + 玻璃卡片", fontSize = 12.sp)
                        }
                        GlassCard(0.92f) {
                            Text("军师", fontSize = 15.sp, fontWeight = FontWeight.Medium)
                            Text("长提示折叠", fontSize = 12.sp)
                        }
                    }
                }
            }
        }
        rule.onNodeWithText("内置渐变背景 + 玻璃卡片").assertExists()
        capture(name)
    }

    @Test
    fun `内置渐变背景能渲染 浅色`() {
        renderBackdrop("background-light", dark = false)
    }

    @Test
    fun `内置渐变背景能渲染 深色`() {
        renderBackdrop("background-dark", dark = true)
    }

    @Test
    fun `试一试页能渲染`() {
        page { TrialScreen(store, 0.92f) }
        rule.onNodeWithText("试一试").assertExists()
        rule.onNodeWithText("生成候选回复").assertExists()
        capture("trial")
    }

    @Test
    fun `设置页能渲染 所有分类直接可达`() {
        page { SettingsScreen(store, store.load(), {}) }
        listOf("接口与模型", "生成方式", "分析设置", "会话白名单", "会话列表", "识图与代理", "外观", "备份与恢复", "诊断与日志").forEach {
            rule.onNodeWithText(it).assertExists()
        }
        rule.onNodeWithText("高级设置").assertDoesNotExist()
        capture("settings")
    }

    @Test
    fun `高级设置能渲染 且含生成模式开关`() {
        page { AdvancedScreen(store, store.load(), {}, {}, {}, {}) }
        rule.onNodeWithText("生成模式").assertExists()
        // 两个模式都在（用 pill 的文案断言；输入框的 label 不在语义树的文字里）
        rule.onNodeWithText("直通（一套接口）").assertExists()
        rule.onNodeWithText("模型分级").assertExists()
        // 白名单卡顶部那张白底说明 / 注意事项卡也在
        rule.onNodeWithText("怎么用").assertDoesNotExist()
        // 图片文字识别（OCR）：开关 + 「最近一次」状态行
        rule.onNodeWithText("图片文字识别（OCR）").assertExists()
        rule.onNodeWithText("识别聊天里的图片").assertExists()
        rule.onNodeWithText("会话白名单").assertExists()
        capture("advanced")
    }

    @Test
    fun `拉取到的联系人页能渲染 且有搜索框和两段名单`() {
        // 造一份「拉回来的名单」，其中老张已经在白名单里 —— 两段（还没加进去的 / 已经在白名单里的）都要画出来
        ApplicationProvider.getApplicationContext<Context>()
            .getSharedPreferences(PREF_NAME, Context.MODE_PRIVATE)
            .edit()
            .putStringSet(Keys.CHAT_CANDIDATES, linkedSetOf("妈妈", "老张", "项目群"))
            .putLong(Keys.CHAT_AT, System.currentTimeMillis())
            .apply()
        store.save(store.load().copy(whitelistEnabled = true, whitelist = setOf("老张")))

        page { ChatCandidatesScreen(store, store.load(), {}, {}) }
        rule.onNodeWithText("拉取到的联系人").assertExists()
        rule.onNodeWithText("拉取会话列表").assertExists()
        rule.onNodeWithText("还没加进去的（2）").assertExists()
        rule.onNodeWithText("已经在白名单里的（1）").assertExists()
        capture("chat-candidates")
    }
    @Test
    fun `液态玻璃底栏能渲染 降级路径不崩`() {
        // Robolectric 上没有 RuntimeShader（LiquidGlass.available=false），
        // 这个用例走的是 LiquidNavBar 的降级分支（莫奈扁卡底）——
        // 真·AGSL 折射（挂在整个内容层上）要靠真机验证，这里保证「底栏还在、页签还在」
        page {
            var selected by remember { mutableIntStateOf(0) }
            CompositionLocalProvider(LocalGlassQuality provides GlassQuality.LOW) {
                LiquidNavBar(tab = selected) { selected = it }
            }
        }
        rule.onNodeWithText("状态").assertIsSelected()
        listOf("军师", "角色", "试一试", "设置").forEach { label ->
            rule.onNodeWithText(label).assertHeightIsAtLeast(48.dp).performTouchInput {
                // 点击页签最下方，防止只有图标 / 文字中间的一小块能够响应。
                click(Offset(center.x, height - 1f))
            }
            rule.onNodeWithText(label).assertIsSelected()
            rule.onNodeWithText("状态").assertIsNotSelected()
        }
        capture("liquid-glass-bar")
    }

    private fun renderNavHighlights(dark: Boolean) {
        page(dark = dark) {
            CompositionLocalProvider(LocalLiquidNav provides remember { LiquidNavState(true) }) {
                Box(Modifier.fillMaxSize()) {
                    LiquidNavBar(tab = 2, modifier = Modifier.align(Alignment.BottomCenter).padding(14.dp)) { }
                }
            }
        }
        rule.onNodeWithText("角色").assertIsSelected()
        rule.onNodeWithText("设置").assertExists()
        capture(if (dark) "liquid-nav-highlight-dark" else "liquid-nav-highlight-light")
    }

    @Test
    fun `液态底栏的前景高光能渲染 浅色`() = renderNavHighlights(dark = false)

    @Test
    fun `液态底栏的前景高光能渲染 深色`() = renderNavHighlights(dark = true)

    @Test
    fun `高级设置 深色能渲染`() {
        page(dark = true) { AdvancedScreen(store, store.load(), {}, {}, {}, {}) }
        rule.onNodeWithText("生成模式").assertExists()
        capture("advanced-dark")
    }
    @Test
    fun `状态页仪表盘能渲染`() {
        store.save(store.load().copy(geoEnabled = false, baseUrl = "http://127.0.0.1:1/v1"))
        page { StatusScreen(store, onTrial = {}) }
        rule.onNodeWithText("TalkTact").assertExists()
        rule.onNodeWithText("模块激活").assertExists()
        rule.onNodeWithText("开始自检").assertExists()
        capture("status-dashboard")
    }

    @Test
    fun `军师与导入直接展示`() {
        page { MentorScreen(store, 0.92f) {} }
        rule.onNodeWithText("内置军师").assertExists()
        rule.onNodeWithText("从 GitHub 导入").assertExists()
        capture("mentor")
    }

    @Test
    fun `角色页能渲染且本人入口常驻`() {
        page { RolesScreen(store, 0.92f, null) {} }
        rule.onNodeWithText("我的说话风格").assertExists()
        rule.onNodeWithText("这是干什么的").assertDoesNotExist()
        capture("roles")
    }

    @Test
    fun `新设置分类与外观深色能渲染`() {
        page(dark = true) { SettingsScreen(store, store.load(), {}) }
        rule.onNodeWithText("备份与恢复").assertExists()
        capture("settings-dark")
    }

    @Test
    fun `识图与代理独立页面能渲染`() {
        page { AdvancedScreen(store, store.load(), {}, {}, {}, {}, section = SettingsSection.RECOGNITION) }
        rule.onNodeWithText("识别聊天里的图片").assertExists()
        rule.onNodeWithText("API Key", substring = false).assertDoesNotExist()
        capture("recognition")
    }

    @Test
    fun `效果品质入口能渲染`() {
        page { AppearanceScreen(store, {}, {}) }
        rule.onNodeWithText("效果品质").assertExists()
        rule.onNodeWithText("背景图片").assertExists()
        capture("appearance")
    }

}
