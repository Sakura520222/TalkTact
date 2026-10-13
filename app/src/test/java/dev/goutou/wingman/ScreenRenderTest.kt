package dev.goutou.wingman

import android.content.Context
import android.graphics.Bitmap
import android.graphics.Canvas
import android.view.View
import androidx.activity.ComponentActivity
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import dev.goutou.wingman.config.ConfigStore
import dev.goutou.wingman.llm.MAX_REPLIES
import dev.goutou.wingman.llm.REPLY_STYLES
import dev.goutou.wingman.config.Keys
import dev.goutou.wingman.config.PREF_NAME
import dev.goutou.wingman.llm.FULL_PROMPT
import dev.goutou.wingman.llm.coreOf
import dev.goutou.wingman.ui.AdvancedScreen
import dev.goutou.wingman.ui.ChatCandidatesScreen
import dev.goutou.wingman.ui.GlassCard
import dev.goutou.wingman.ui.GlassQuality
import dev.goutou.wingman.ui.GoutouTheme
import dev.goutou.wingman.ui.SettingsScreen
import dev.goutou.wingman.ui.TrialScreen
import dev.goutou.wingman.ui.drawBackdropArt
import dev.goutou.wingman.ui.rememberBackdrop
import org.junit.Assert.assertEquals
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
    fun `设置页能渲染 且含玻璃效果三档`() {
        page { SettingsScreen(store, store.load(), {}, {}) }
        rule.onNodeWithText("外观").assertExists()
        rule.onNodeWithText("玻璃效果", substring = true).assertExists()
        rule.onNodeWithText("自动").assertExists()
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
        rule.onNodeWithText("怎么用").assertExists()
        // 图片文字识别（OCR）：开关 + 「最近一次」状态行
        rule.onNodeWithText("图片文字识别（OCR）").assertExists()
        rule.onNodeWithText("识别聊天里的图片").assertExists()
        rule.onNodeWithText("会话白名单").assertExists()
        capture("advanced")
    }

    /**
     * 「回复风格（可多选）」那张卡的入口断言。
     *
     * 可以断言到后面几个分组（「图片文字识别（OCR）」等）也在，是因为这一页用的是
     * `verticalScroll` 的 Column 而不是 LazyColumn —— 所有子节点都会进语义树，
     * **加长页面不会把它们从树里挤掉**。换成 LazyColumn 的话就得改成滚动后再断言。
     *
     * 只断言卡片标题，不逐个断言 8 个风格名：那 8 个 pill 在 4 列布局里换行，
     * 一旦以后调整成别的列数/顺序，逐个断言就会毫无理由地红 —— 而卡片在不在才是要紧的。
     */
    @Test
    fun `高级设置里有风格多选（没有单独的条数选项）`() {
        page { AdvancedScreen(store, store.load(), {}, {}, {}, {}) }
        rule.onNodeWithText("回复风格（可多选）").assertExists()
        // 「可以多选」这句话必须在，否则用户不知道能多勾
        rule.onNodeWithText("可多选（最多 $MAX_REPLIES 种）：勾几种就写几条").assertExists()
        // 旧的「条数」入口不能再出现 —— 出现就说明那排 1~6 的药丸又回来了
        rule.onNodeWithText("条数").assertDoesNotExist()
        // 用户要求「选完风格要提示重启微信」——这条提示丢了就等于没告诉
        rule.onNodeWithText("强行停止微信", substring = true).assertExists()
    }

    /**
     * 自定义风格的入口。
     *
     * 断言的是**输入框的标签和按钮**，不是「有几个药丸」：药丸数量取决于用户加过什么，
     * 而这一条要钉的是「这个功能在这页上存在、能被操作」。
     */
    @Test
    fun `高级设置里能自己输入添加风格`() {
        page { AdvancedScreen(store, store.load(), {}, {}, {}, {}) }
        rule.onNodeWithText("自定义风格（加完直接选中，和上面同等）").assertExists()
        rule.onNodeWithText("添加风格").assertExists()
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
    fun `高级设置 深色能渲染`() {
        page(dark = true) { AdvancedScreen(store, store.load(), {}, {}, {}, {}) }
        rule.onNodeWithText("生成模式").assertExists()
        capture("advanced-dark")
    }

    /**
     * 改条数/风格之后，**提示词页显示的那份文本也跟着变**。
     *
     * 这是踩过的坑：规格重拼只放在调用时（`LlmClient` 里），
     * 于是实际行为已经变了、设置页存了新值，唯独「提示词」页那份快照纹丝不动 ——
     * 三个地方有两个是新的，只有给人看的那个是旧的，比「真没生效」还难查。
     *
     * 所以这里断的是**落盘的那份 prompt 本身**，不是界面上某个 Text 节点：
     * 界面那条已经被「高级设置里有条数与风格的选择」覆盖了。
     */
    @Test
    fun `改风格会同步重拼提示词快照，条数跟着勾选变`() {
        store.saveReplyPlan(listOf("safe", "humor", "advance"))
        val before = store.load().prompt
        assertTrue("勾了 3 种就该写 3 条，实际含：$before", before.contains("恰好给 3 条"))

        store.saveReplyPlan(listOf("empathy", "brief"))
        val after = store.load().prompt

        assertTrue("换规格后提示词快照必须变，否则提示词页看不到任何变化", before != after)
        assertTrue("勾了 2 种就是 2 条（条数从勾选派生），实际含：$after", after.contains("恰好给 2 条"))
        assertTrue("风格清单也要跟着换，含共情/简短：$after", after.contains("共情") && after.contains("简短"))
        assertTrue("旧条数不该残留：$after", !after.contains("恰好给 3 条"))
        // 正文是用户的，不能因为改了规格就被换掉
        assertTrue("正文必须保留", coreOf(before) == coreOf(after))
    }

    /**
     * 反复改条数不能把提示词越改越乱（规格段会重复叠加 / 契约会重复追加）。
     *
     * 重拼是「摘掉旧的两段再拼新的」，这里确认它真的幂等 ——
     * 如果某次没摘干净，用户每点一次设置就多一段，提示词会越滚越长还多花 token。
     *
     * 只断规格段标题，不拿 `"replies":` 的出现次数当指标：满血版 skill 正文里的
     * few-shot 示例本身也带 replies，那个数天然是 2，用它做判据会在换 skill 时误报。
     */
    @Test
    fun `反复改条数不会让提示词里叠加出多份规格段`() {
        // 连点四次，每次勾的个数都不一样（2、3、4、5）—— 规格段里的条数要跟着走
        repeat(4) { i -> store.saveReplyPlan(REPLY_STYLES.map { it.id }.take(2 + i)) }
        val p = store.load().prompt
        assertEquals(1, Regex("【这次要几条、什么风格】").findAll(p).count())
        assertEquals("最后一次勾了 5 种，就该写 5 条", true, p.contains("恰好给 5 条"))
    }

    /**
     * 内置 skill 的 few-shot 示例里也有 replies —— 换规格时它必须跟着换，
     * 否则满血版会出现「正文示例说 3 条、契约说 5 条」这种自相矛盾的提示词。
     */
    @Test
    fun `切到满血版 skill 时示例也跟着当前规格走`() {
        val cur = store.load()
        store.save(cur.copy(prompt = FULL_PROMPT))
        store.saveReplyPlan(listOf("empathy", "direct"))

        val p = store.load().prompt
        // 规格段：勾了 2 种 → 2 条，风格共情/直接
        assertTrue("规格段要写 2 条：$p", p.contains("恰好给 2 条"))
        assertTrue("规格段要写共情：$p", p.contains("共情"))
        // few-shot 示例：2 个 style 元素
        val example = p.substringAfter("【例子】").substringBefore("只输出下面这个 JSON")
        assertEquals("few-shot 示例要给 2 条：$example", 2, Regex("\"style\":").findAll(example).count())
    }
}
