package dev.goutou.wingman.ui

import android.app.ActivityManager
import androidx.activity.compose.BackHandler
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.ime
import android.content.Context
import android.graphics.BitmapFactory
import android.graphics.RenderEffect
import android.graphics.RuntimeShader
import android.net.Uri
import android.os.Build
import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.Spring
import androidx.compose.animation.core.animate
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.spring
import androidx.compose.animation.core.tween
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxScope
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.RowScope
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Check
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.Edit
import androidx.compose.material.icons.filled.Person
import androidx.compose.material.icons.filled.Pets
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material.icons.filled.Settings
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextFieldDefaults
import androidx.compose.material3.Text
import androidx.compose.material3.TextFieldColors
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.saveable.rememberSaveableStateHolder
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.TextButton
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.runtime.withFrameNanos
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.blur
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.clipToBounds
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.draw.shadow
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.Shape
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.graphics.asComposeRenderEffect
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.graphics.lerp
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.graphics.drawscope.withTransform
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.layout.onGloballyPositioned
import androidx.compose.ui.layout.positionInRoot
import androidx.compose.ui.hapticfeedback.HapticFeedbackType
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalHapticFeedback
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.IntSize
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import dev.goutou.wingman.ModuleStatus
import dev.goutou.wingman.config.ConfigData
import dev.goutou.wingman.config.RemoteSync
import dev.goutou.wingman.config.ConfigStore
import top.yukonga.miuix.kmp.squircle.LocalSquircleEnabled
import top.yukonga.miuix.kmp.squircle.squircleBackground
import top.yukonga.miuix.kmp.theme.ColorSchemeMode
import top.yukonga.miuix.kmp.theme.Colors
import top.yukonga.miuix.kmp.theme.MiuixTheme
import top.yukonga.miuix.kmp.theme.ThemeController
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import kotlin.math.max
import kotlin.math.min
import kotlin.math.roundToInt

/**
 * 配色 + 玻璃参数。
 *
 * 「液态玻璃」= 面板后面压一块**真正被模糊过的背景**（Modifier.blur 走 RenderEffect）。
 * 因为 minSdk = 31（Android 12），这里不再需要低版本降级分支。
 */
// ================= 设计 Token =================

/**
 * 圆角四档（界面改造批 4b，设计师规范）。
 *
 * **嵌套规则：内层 = 外层 − 6dp** —— 卡片里再套一个圆角块时别用同一个值，
 * 否则两层圆角一样大，看起来是「套娃」而不是「嵌进去」。
 */
val RadiusR1 = 10.dp   // chip / 筛选胶囊 / 小按钮
val RadiusR2 = 16.dp   // 输入框 / 列表行卡 / 普通按钮
val RadiusR3 = 24.dp   // 标准卡片
val RadiusR4 = 32.dp   // 顶部大面板 / 底部面板顶角

/**
 * 状态色令牌（界面改造批 4c）。
 *
 * 语义固定：绿 = 正常/安全，琥珀 = 注意，红 = 危险，紫 = 选中/主操作。
 * **状态色只允许出现在这四种形态里**：
 * ① 8dp 状态点（[StatusDot]）；② 13sp 文字；③ chip（[StatusChip]：底色 alpha [ChipBgAlpha] + 1dp 描边）；
 * ④ 1dp 描边（卡片 / chip，alpha [CardBorderAlpha]）。
 *
 * 两条附加规矩：一屏最多一处「大面积」状态色（运行状态那张 hero 卡的空心圈，其余一律走上面四种形态）；
 * **紫不承担「正常」语义** —— 正常 / 生效一律用绿，说不清就用 [Palette.sub]。
 * 「颜色 + 文案」双通道：状态色永远跟一句说清楚的文字一起出现，不许只靠颜色区分。
 */
val StatusDotSize = 8.dp
/** chip / 小标签的底色透明度（状态色或选中色 + 这一层 alpha 铺在玻璃上）。 */
/** 低配档的描边透明度（设计师 P2）：那一档没有模糊 / 折射衬托，bevel 梯度读不出来，改用均匀描边。 */
const val LOW_TIER_EDGE_ALPHA = 0.16f
const val ChipBgAlpha = 0.14f
/** 状态色 / 选中色的 1dp 描边透明度（批 4c 之前是 0.45 / 0.5 / 0.55 三档混用）。 */
const val CardBorderAlpha = 0.35f

/**
 * 「玻璃强度」(`glassAlpha`) 只调**透**、不调**厚**（第 7 版玻璃重构）。
 *
 * 以前填充 / 描边 / 顶边 / 底边 / 扫光**全都乘**它，滑到最低档卡片就变成一张纸。
 * 现在分三种走法：填充按 ±0.24 摆动且留底、描边衰减到 60% 就不再掉、
 * 厚度（顶边 / 底边 / 阴影）**完全不乘**。
 */
fun fillAlpha(base: Float, g: Float): Float = (base + (g - 0.5f) * 0.24f).coerceIn(0f, 1f)
fun edgeAlpha(base: Float, g: Float): Float = base * (0.6f + 0.4f * g)
fun sweepAlpha(base: Float, g: Float): Float = base * (0.3f + 0.7f * g)

/**
 * 输入框统一样式：半透明填充 + 淡紫描边 + 聚焦加亮。
 *
 * 以前一个字段都没设 `colors` → 全走 Material3 默认（容器透明），落在近白的卡片上
 * 就是一块白板（设计师点名的「白板第二大来源」）。数值见规范：浅色填充白 0.55 / 深色白 0.08。
 */
@Composable
fun glassFieldColors(): TextFieldColors {
    val palette = LocalPalette.current
    return OutlinedTextFieldDefaults.colors(
        focusedContainerColor = palette.fieldFill,
        unfocusedContainerColor = palette.fieldFill,
        focusedBorderColor = if (palette.dark) {
            Color.White.copy(alpha = 0.40f)
        } else {
            palette.primary.copy(alpha = 0.70f)
        },
        unfocusedBorderColor = palette.fieldBorder,
        focusedTextColor = palette.text,
        unfocusedTextColor = palette.text,
        focusedLabelColor = palette.primary,
        unfocusedLabelColor = palette.sub,
        cursorColor = palette.primary,
    )
}

data class Palette(
    val primary: Color,
    val soft: Color,
    val text: Color,
    val sub: Color,
    val ok: Color,
    val warn: Color,
    val bad: Color,
    /**
     * 状态色的**图形档**（8dp 点 / chip 底色与描边 / 卡片描边 / hero 那个圈）。
     *
     * 为什么不复用 [ok]/[warn]/[bad]：浅色下同一支颜色当**文字**时对比度不够
     * （实测 ok 2.7:1、warn 2.2:1，都低于 AA 的 4.5:1），所以拆两档 ——
     * 上面那三个是**文字档**（更深），这三个是**图形档**（稍亮，配 8dp 点与 1dp 描边）。
     */
    val okMark: Color,
    val warnMark: Color,
    val badMark: Color,
    val bgTop: Color,
    /** 底色渐变的中间那一段。三段比两段更像「光」，不会是平涂。 */
    val bgMid: Color,
    val bgBottom: Color,
    /** 左上角那团光晕：整块背景的「光源」，玻璃面板的高光顺着它来。alpha = 0 就是不要。 */
    val bgGlow: Color,
    val bgGlowAlpha: Float,
    /** 上下两端的暗角：中间亮、两头沉，长列表滚到底也不飘。 */
    val bgVignette: Color,
    val bgVignetteAlpha: Float,
    /** 星尘噪点：给纯渐变补一点颗粒，不然大屏上会有塑料感。 */
    val bgGrain: Color,
    val bgGrainAlpha: Float,
    /** 微网格线：极淡，远看是织物纹理，近看才看得出是格子。只有最高档位才画。 */
    val bgLine: Color,
    val bgLineAlpha: Float,
    val glass: Color,
    /**
     * 设计重做（2026-10）：扁平 squircle 卡的**实底色**（浅 #FFFFFF / 深 #2A2D33）——
     * HyperOS 的卡片不是玻璃，是纯色超椭圆 + 极淡投影。旧玻璃字段保留只为兼容字段引用。
     */
    val cardBg: Color,
    /**
     * 玻璃面板的**染色**用色：两种明暗都是白。
     *
     * 低 alpha 的白才是「玻璃反光」；而 [glass] 是给 Material surface 用的**实色**
     * （深色下是深灰 #2A2734）—— 拿它按 0.7 铺满整张卡，卡片就变成一块不透明的板，
     * 这正是「一圈玻璃包着一块板」读感的来源。
     */
    val glassTint: Color,
    val glassBorder: Color,

    // ---- L1 主面板：填充 / 描边 / 厚度（第 7 版玻璃重构，数值全部来自设计师规范）----
    /**
     * 填充的上下两端颜色。浅色是**紫灰**（比背景更深、更紫 = 雾面亚克力），
     * 深色是「带紫的白」—— 而不是「在近白底上再提白」（那正是白板读感的根因）。
     */
    val glassFillTop: Color,
    val glassFillBottom: Color,
    val glassFillTopAlpha: Float,
    val glassFillBottomAlpha: Float,
    /** 描边三停：左上高光 / 中段极淡 / **右下折射暗边**（有色；白→白读不出厚度）。 */
    val glassEdgeHi: Color,
    val glassEdgeHiAlpha: Float,
    val glassEdgeMidAlpha: Float,
    val glassEdgeLow: Color,
    val glassEdgeLowAlpha: Float,
    /** 顶边光源（厚度之一，**不乘 glassAlpha**）：1.5dp 白。 */
    val glassTopEdgeAlpha: Float,
    val glassTopEdgeDp: Float,
    /** 底边反光 / 暗边（厚度之二，也不乘 glassAlpha）。 */
    val glassBottomEdge: Color,
    val glassBottomEdgeAlpha: Float,
    /** 镜面扫光的基准 alpha（按 sweepAlpha(g) 走）。 */
    val glassSweepAlpha: Float,
    /** 外阴影（厚度之三，不乘 glassAlpha —— 乘了滑到最低档卡片会像贴在纸上）。 */
    val glassShadow: Color,
    val glassShadowAlpha: Float,
    val glassShadowDp: Float,
    /** 输入框：半透明填充 + 淡描边（以前用 M3 默认，落在近白卡上就是一块白板）。 */
    val fieldFill: Color,
    val fieldBorder: Color,
    val dark: Boolean,
)

/**
 * Miuix / HyperOS 风格的浅色板（界面二改第一批）。
 *
 * 颜色对齐 Miuix 默认配色（compose-miuix-ui 文档「颜色系统」页）：
 * primary `#3482FF`、次级文字 `#8C93B0`、背景纯白系。语义色用 HyperOS 官方四色
 * （蓝 3482FF / 绿 00B578 / 橙 FF8F1F / 红 FA5151），文字档再压深一档保 AA 对比度
 * （沿用旧紫板的「文字 / 图形两档」结构，见 [Palette.okMark]）。
 *
 * 背景刻意**不是纯平白**：纯白底 + 白玻璃填充 = 白板（第 6 版玻璃的教训），
 * 给极淡的蓝灰渐变 + 极低的蓝光晕 —— 玻璃的折射 / 描边才有东西可立。
 */
private val LightPalette = Palette(
    primary = Color(0xFF3482FF),
    soft = Color(0xFFEAF2FF),
    text = Color(0xFF0B0B0F),
    sub = Color(0xFF8C93B0),
    // 文字档：HyperOS 语义色压深一档，浅底上才够 4.5:1
    ok = Color(0xFF00714A),
    warn = Color(0xFF8A5A00),
    bad = Color(0xFFC93A2E),
    okMark = Color(0xFF00A163),
    warnMark = Color(0xFFB07400),
    badMark = Color(0xFFE94634),
    // 背景：Miuix 式近纯色（极淡冷灰渐变，不再是紫玻璃时代的「起伏救场」背景）
    bgTop = Color(0xFFFAFBFD),
    bgMid = Color(0xFFF3F5F9),
    bgBottom = Color(0xFFEDF0F5),
    bgGlow = Color(0xFF3482FF),
    bgGlowAlpha = 0.07f,
    bgVignette = Color(0xFF3482FF),
    bgVignetteAlpha = 0.03f,
    bgGrain = Color(0xFFFFFFFF),
    bgGrainAlpha = 0.040f,
    bgLine = Color(0xFF3482FF),
    bgLineAlpha = 0.025f,
    glass = Color(0xFFFFFFFF),
    cardBg = Color(0xFFFFFFFF),
    glassTint = Color(0xFFFFFFFF),
    glassBorder = Color(0xB3FFFFFF),
    // L1：白玻璃（上白下淡蓝）。Miuix 底下背景近白，玻璃靠「白上更白 + 淡蓝底衬」立厚度
    glassFillTop = Color(0xFFFFFFFF),
    glassFillBottom = Color(0xFFDCEBFD),
    glassFillTopAlpha = 0.42f,
    glassFillBottomAlpha = 0.34f,
    glassEdgeHi = Color(0xFFFFFFFF),
    glassEdgeHiAlpha = 0.65f,
    glassEdgeMidAlpha = 0.18f,
    // 右下折射暗边：淡蓝（白→白读不出厚度）
    glassEdgeLow = Color(0xFF9EC5F2),
    glassEdgeLowAlpha = 0.34f,
    glassTopEdgeAlpha = 0.65f,
    glassTopEdgeDp = 1.5f,
    glassBottomEdge = Color(0xFF5B8FD6),
    glassBottomEdgeAlpha = 0.16f,
    glassSweepAlpha = 0.09f,
    glassShadow = Color(0xFF2E4E7E),
    glassShadowAlpha = 0.10f,
    glassShadowDp = 18f,
    fieldFill = Color(0x8CFFFFFF),
    fieldBorder = Color(0x478FBFF5),
    dark = false,
)

/**
 * Miuix / HyperOS 风格的深色板：背景 `#242424` 系（带蓝灰起伏），primary `#277AF7`。
 * 玻璃结构与旧深紫板一致，填充 / 暗边换蓝调（带蓝的白才是「有色玻璃」，纯白在深底上发灰）。
 */
private val DarkPalette = Palette(
    primary = Color(0xFF277AF7),
    soft = Color(0xFF2B3B54),
    text = Color(0xFFF2F2F2),
    sub = Color(0xFF787E96),
    // 深色下三支颜色两档都用同一支（沿用旧板做法，对比度足够）
    ok = Color(0xFF4FD296),
    warn = Color(0xFFF0B95B),
    bad = Color(0xFFFF8A8A),
    okMark = Color(0xFF4FD296),
    warnMark = Color(0xFFF0B95B),
    badMark = Color(0xFFFF8A8A),
    bgTop = Color(0xFF1B1D21),
    bgMid = Color(0xFF16181C),
    bgBottom = Color(0xFF111316),
    bgGlow = Color(0xFF277AF7),
    bgGlowAlpha = 0.10f,
    bgVignette = Color(0xFF000000),
    bgVignetteAlpha = 0.30f,
    bgGrain = Color(0xFFFFFFFF),
    bgGrainAlpha = 0.020f,
    bgLine = Color(0xFFFFFFFF),
    bgLineAlpha = 0.022f,
    glass = Color(0xFF242424),
    cardBg = Color(0xFF2A2D33),
    glassTint = Color(0xFFFFFFFF),
    glassBorder = Color(0x33FFFFFF),
    // 深色：上「带蓝的白」下淡蓝 —— 深底上白 0.12 会发灰，带蓝才是有色玻璃
    glassFillTop = Color(0xFFEAF2FF),
    glassFillBottom = Color(0xFF6FA5F5),
    glassFillTopAlpha = 0.12f,
    glassFillBottomAlpha = 0.05f,
    glassEdgeHi = Color(0xFFFFFFFF),
    glassEdgeHiAlpha = 0.55f,
    glassEdgeMidAlpha = 0.10f,
    glassEdgeLow = Color(0xFF8FBFF5),
    glassEdgeLowAlpha = 0.24f,
    glassTopEdgeAlpha = 0.55f,
    glassTopEdgeDp = 1.5f,
    glassBottomEdge = Color(0xFF000000),
    glassBottomEdgeAlpha = 0.22f,
    glassSweepAlpha = 0.075f,
    glassShadow = Color(0xFF060809),
    glassShadowAlpha = 0.45f,
    glassShadowDp = 24f,
    fieldFill = Color(0x14FFFFFF),
    fieldBorder = Color(0x24FFFFFF),
    dark = true,
)

val LocalPalette = staticCompositionLocalOf { LightPalette }

/**
 * 背景内容。图片在这里解码**一次**，所有玻璃面板共用，
 * 免得每个面板各自 produceState 重新解码一遍。
 */
data class Backdrop(
    /** 用户自选的背景图；为空 = 用程序化渐变（内置背景）。 */
    val bitmap: ImageBitmap?,
    /** 只对自定义背景图生效的压暗强度。 */
    val dim: Float,
    val bgTop: Color,
    val bgMid: Color,
    val bgBottom: Color,
    val bgGlow: Color,
    val bgGlowAlpha: Float,
    val bgVignette: Color,
    val bgVignetteAlpha: Float,
    val bgGrain: Color,
    val bgGrainAlpha: Float,
    val bgLine: Color,
    val bgLineAlpha: Float,
    val primary: Color,
    /** 玻璃面板背后的模糊半径（dp） */
    val blur: Dp,
)

/**
 * 把「当前配色 + 用户设置」拼成一份 [Backdrop] —— **全仓唯一一处映射**。
 *
 * 以前同样一组背景色写了三份（Palette / Backdrop 的构造处 / 这里的默认值），
 * 改一处忘两处是迟早的事，索性都收进这个函数。
 */
fun backdropOf(palette: Palette, bitmap: ImageBitmap?, dim: Float, blur: Dp) = Backdrop(
    bitmap = bitmap,
    dim = dim,
    bgTop = palette.bgTop,
    bgMid = palette.bgMid,
    bgBottom = palette.bgBottom,
    bgGlow = palette.bgGlow,
    bgGlowAlpha = palette.bgGlowAlpha,
    bgVignette = palette.bgVignette,
    bgVignetteAlpha = palette.bgVignetteAlpha,
    bgGrain = palette.bgGrain,
    bgGrainAlpha = palette.bgGrainAlpha,
    bgLine = palette.bgLine,
    bgLineAlpha = palette.bgLineAlpha,
    primary = palette.primary,
    blur = blur,
)

val LocalBackdrop = staticCompositionLocalOf { backdropOf(LightPalette, null, 0f, 24.dp) }

/** 根容器尺寸（px）。玻璃面板靠它把整屏背景平移对齐到自己身上。 */
val LocalRootSize = staticCompositionLocalOf { IntSize.Zero }

/**
 * 玻璃扫光的相位（0..1 一轮）。
 *
 * 单独做成一个持有者、而不是塞进 [Backdrop]：动画推进时会让读到它的组件重组，
 * 而 [Backdrop] 是所有玻璃面板都在读的 —— 那样每帧都会重组整屏。
 * 装在这里，配合「只在 draw 阶段读取」的写法，推进相位只会重画，不会重组。
 *
 * 镜面扫光与折射共用同一个相位，所以它们是同一个时钟，不会各动各的。
 */
private class GlassPhase {
    var phase by mutableFloatStateOf(0f)
}

private val LocalGlassPhase = staticCompositionLocalOf { GlassPhase() }

/**
 * 当前生效的玻璃档位（折射 / 模糊 / 扫光 各开不开）。
 *
 * 由 [App] 按「设置里的选择 + 设备能力」算一次后提供；玻璃面板只读它，不各自去问系统 ——
 * 读 `isLowRamDevice` 有成本，而且设置页要能显示「自动 = 实际判成了哪一档」。
 */
val LocalGlassQuality = staticCompositionLocalOf { GlassQuality.HIGH }

/**
 * 低内存设备（`ActivityManager.isLowRamDevice`）。拿不到就按「不是」处理 ——
 * 宁可多开点效果，也别因为一个查询失败把所有人降级。
 */
internal fun isLowRamDevice(context: Context): Boolean = runCatching {
    (context.getSystemService(Context.ACTIVITY_SERVICE) as ActivityManager).isLowRamDevice
}.getOrDefault(false)

/**
 * 一支 AGSL 折射着色器（对应 liquidGL 的 refraction + aberration + bevel）。
 *
 * 只说一句实话：这是整次改造里**唯一没法在这儿验证**的部分 —— 着色器是运行时编译的，
 * 编译不过只会在真机上抛异常。所以全都包了 try/catch：任何一步出问题都返回 null，
 * 上层自动退回「模糊 / 只染色」，最坏情况只是没有折射，不会崩。
 *
 * 需要 API 33+（RuntimeShader）。minSdk 是 31，31/32 直接走降级。
 */
private const val AGSL_GLASS = """
uniform shader uBackdrop;
uniform float2 uSize;
uniform float uRadius;
uniform float uStrength;
uniform float uAberration;

half4 main(float2 fragCoord) {
    float2 c = uSize * 0.5;
    float2 p = fragCoord - c;
    float m = min(uSize.x, uSize.y);
    float r = min(uRadius, m * 0.5);
    // 圆角矩形 SDF：面板内部为负、边缘为 0、外部为正
    float2 q = abs(p) - c + float2(r, r);
    float sd = min(max(q.x, q.y), 0.0) + length(max(q, float2(0.0, 0.0))) - r;
    // t: 边缘 0 → 内部 1
    float t = clamp(-sd / (m * 0.24), 0.0, 1.0);
    // 近似外法线；加个极小量避免正中心取到 0 向量
    float2 n = normalize(p + float2(0.0001, 0.0001));
    // 越靠边位移越大 —— 斜面折射就是这个平方衰减
    float k = (1.0 - t) * (1.0 - t) * uStrength * m;
    float2 uv = fragCoord - n * k;
    // 色散：R / B 往两边错开一点点采样
    float ab = uAberration * m * (1.0 - t);
    half3 col;
    col.r = uBackdrop.eval(uv + n * ab).r;
    col.g = uBackdrop.eval(uv).g;
    col.b = uBackdrop.eval(uv - n * ab).b;
    // 斜面上一圈冷白高光
    float rim = pow(1.0 - t, 5.0);
    col += half3(0.55, 0.60, 0.75) * rim * 0.30;
    return half4(col, 1.0);
}
"""

private class AgslGlass {
    private val shader: RuntimeShader? =
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            try { RuntimeShader(AGSL_GLASS) } catch (t: Throwable) { null }
        } else null

    /** 尺寸 → RenderEffect 的缓存：尺寸不变的场景下不用反复创建。 */
    private val cache = HashMap<Long, RenderEffect?>()

    val available: Boolean get() = shader != null

    fun effect(w: Int, h: Int): RenderEffect? {
        val sh = shader ?: return null
        if (w <= 0 || h <= 0) return null
        return cache.getOrPut((w.toLong() shl 32) or h.toLong()) {
            try {
                sh.setFloatUniform("uSize", w.toFloat(), h.toFloat())
                sh.setFloatUniform("uRadius", min(w, h) * 0.30f)
                // 3.2% 短边：看得出边缘弯折，又不至于把背景图案揉变形
                sh.setFloatUniform("uStrength", 0.032f)
                sh.setFloatUniform("uAberration", 0.006f)
                RenderEffect.createRuntimeShaderEffect(sh, "uBackdrop")
            } catch (t: Throwable) { null }
        }
    }
}

/**
 * 主题。
 *
 * [dark] 留空 = 跟随系统（正式路径）；显式传值只有两个用途：截图回归要深色 / 浅色各出一张，
 * 以及 Compose Preview 想钉住某一套配色 —— 都比改 Robolectric 限定符干净。
 */
/**
 * 从 Miuix 的 Monet 色板（取自壁纸）派生整套 [Palette]。
 *
 * 分工遵循 HyperOS 惯例：**Monet 只染强调色与容器**（primary / tertiaryContainer /
 * surfaceContainer 系的背景与卡片），**文字与语义色保持中性** —— 正文永远近黑/近白，
 * 红 / 绿 / 琥珀语义不随壁纸跑（不然状态色被洗掉，「正常」会看不出）。
 * 玻璃参数（液态玻璃底栏用的 tint / 顶边光）保持中性白。
 */
private fun monetPalette(mc: Colors, dark: Boolean): Palette = Palette(
    primary = mc.primary,
    soft = mc.tertiaryContainer,
    text = if (dark) Color(0xFFF2F2F2) else Color(0xFF0B0B0F),
    sub = if (dark) Color(0xFFB9B3C0) else Color(0xFF625E69),
    // 语义色（HyperOS 四色的文字 / 图形两档），不参与 Monet
    ok = if (dark) Color(0xFF4FD296) else Color(0xFF00714A),
    warn = if (dark) Color(0xFFF0B95B) else Color(0xFF8A5A00),
    bad = if (dark) Color(0xFFFF8A8A) else Color(0xFFC93A2E),
    okMark = if (dark) Color(0xFF4FD296) else Color(0xFF00A163),
    warnMark = if (dark) Color(0xFFF0B95B) else Color(0xFFB07400),
    badMark = if (dark) Color(0xFFFF8A8A) else Color(0xFFE94634),
    // 背景三段：Monet 的面色系 —— 浅色白→微染沉；深色反过来（旧玻璃时代的
    // 光晕 / 噪点 / 网格字段保留但 alpha 全 0，绘制已随重做删除）
    bgTop = if (dark) mc.background else lerp(Color.White, mc.background, 0.30f),
    bgMid = if (dark) mc.background else lerp(Color.White, mc.background, 0.30f),
    bgBottom = if (dark) mc.background else lerp(Color.White, mc.background, 0.30f),
    bgGlow = mc.tertiaryContainer,
    bgGlowAlpha = 0f,
    bgVignette = Color.Black,
    bgVignetteAlpha = 0f,
    bgGrain = Color.White,
    bgGrainAlpha = 0f,
    bgLine = Color.White,
    bgLineAlpha = 0f,
    glass = mc.surface,
    cardBg = mc.surfaceContainer,
    glassTint = Color.White,
    glassBorder = if (dark) Color(0x33FFFFFF) else Color(0xB3FFFFFF),
    // 旧 L1 玻璃字段：新设计里无人读，中性占位
    glassFillTop = Color.White,
    glassFillBottom = Color.White,
    glassFillTopAlpha = 0f,
    glassFillBottomAlpha = 0f,
    glassEdgeHi = Color.White,
    glassEdgeHiAlpha = 0f,
    glassEdgeMidAlpha = 0f,
    glassEdgeLow = mc.primary,
    glassEdgeLowAlpha = 0f,
    glassTopEdgeAlpha = 0.55f,
    glassTopEdgeDp = 1.5f,
    glassBottomEdge = Color.Black,
    glassBottomEdgeAlpha = 0f,
    glassSweepAlpha = 0f,
    glassShadow = Color.Black,
    glassShadowAlpha = 0f,
    glassShadowDp = 12f,
    fieldFill = if (dark) Color(0x26FFFFFF) else Color(0x0F000000),
    fieldBorder = if (dark) Color(0x24FFFFFF) else Color(0x14000000),
    dark = dark,
)

/**
 * Robolectric 的 SkSL 实现不支持 miuix squircle 着色器里的 layout(color) 限定符。
 * 纯 JVM 测试（无 Robolectric）里 [Build.FINGERPRINT] 是 null —— 不崩，按非 Robolectric 处理。
 */
private val IS_ROBOLECTRIC = Build.FINGERPRINT?.startsWith("robolectric") == true

@Composable
fun GoutouTheme(dark: Boolean? = null, content: @Composable () -> Unit) {
    val isDark = dark ?: isSystemInDarkTheme()
    // 莫奈取色（2026-10）：整套主题从壁纸动态取色 —— Miuix 的 Monet 模式，
    // keyColor 留空 = 取系统壁纸。[dark] 参数（测试 / 预览钉深浅）映射到 MonetLight / MonetDark。
    val controller = remember(dark) {
        ThemeController(
            when (dark) {
                null -> ColorSchemeMode.MonetSystem
                false -> ColorSchemeMode.MonetLight
                true -> ColorSchemeMode.MonetDark
            },
        )
    }
    // squircle（HyperOS 超圆角）真机开；Robolectric 退普通圆角
    //（它的 SkSL 不认 layout(color)，会直接抛 IllegalArgumentException）
    CompositionLocalProvider(LocalSquircleEnabled provides !IS_ROBOLECTRIC) {
        MiuixTheme(controller = controller) {
            val palette = monetPalette(MiuixTheme.colorScheme, isDark)
            CompositionLocalProvider(LocalPalette provides palette) {
                MaterialTheme(
                    colorScheme = if (isDark) {
                        darkColorScheme(primary = palette.primary, background = palette.bgTop, surface = palette.glass)
                    } else {
                        lightColorScheme(primary = palette.primary, background = palette.bgTop, surface = palette.glass)
                    },
                    content = content,
                )
            }
        }
    }
}

// ================= 背景绘制 =================

/**
 * 把「整屏背景」画一遍。
 *
 * 调用方负责先把坐标系平移到目标区域（面板）再裁剪，所以同一个函数既能画最底层背景，
 * 也能画出「面板背后那块背景」—— 后者再套一层折射 / 模糊就是真实的玻璃。
 *
 * 默认背景**不再是一张图**，而是程序化画出来的：底色渐变 + 左上光晕 + 上下暗角 +
 * 星尘噪点 + 微网格。这么换掉有三个好处：
 * - 安装包不再背着几百 KB 的大图，也不用每次进前台解码一遍；
 * - 每一层都是矢量绘制，平板 / 折叠屏上不会糊；
 * - 「面板背后做一次真实模糊」这条最贵的路径会自然短路（见 [GlassSurface]）——
 *   纯渐变没有细节可模糊，跳过整整一次离屏渲染。
 *
 * 用户在设置里选了自己的图就以他的图为准，装饰层会被盖住，索性不画。
 */
// internal 而不是 private：截图回归要**直接**调它。
// 走 BackgroundLayer 不行 —— Compose 测试会等不到 idle。
internal fun DrawScope.drawBackdropArt(
    b: Backdrop,
    w: Float,
    h: Float,
    quality: GlassQuality = GlassQuality.HIGH,
    /**
     * 0..1 的动画相位 —— 设计重做后背景不再有动效，参数保留只为兼容旧签名。
     */
    drift: Float = 0f,
) {
    // ① 底色渐变（斜向）：Miuix 式极淡冷灰渐变，永远画。图还没解码完、或者用户选了张
    //    很亮的图时，兜住文字对比度。
    drawRect(
        brush = Brush.linearGradient(
            colors = listOf(b.bgTop, b.bgMid, b.bgBottom),
            start = Offset(w * 0.18f, 0f),
            end = Offset(w * 0.82f, h),
        ),
        topLeft = Offset.Zero,
        size = Size(w, h),
    )

    val img = b.bitmap
    if (img != null) {
        // 用户自选的背景图：贴上去就完事。
        // 等价于 ContentScale.Crop：按「铺满」的比例缩放后居中
        val scale = max(w / img.width.toFloat(), h / img.height.toFloat())
        val dw = (img.width * scale).roundToInt()
        val dh = (img.height * scale).roundToInt()
        drawImage(
            image = img,
            srcOffset = IntOffset.Zero,
            srcSize = IntSize(img.width, img.height),
            dstOffset = IntOffset(((w - dw) / 2f).roundToInt(), ((h - dh) / 2f).roundToInt()),
            dstSize = IntSize(dw, dh),
        )
        // 压暗只对自定义图片开放：内置渐变本身已经调过明度，再压一层会连同配色一起糊掉
        if (b.dim > 0f) {
            drawRect(color = Color.Black.copy(alpha = b.dim), topLeft = Offset.Zero, size = Size(w, h))
        }
        return
    }

    // ② 莫奈色斑（App **自绘**的背景内容，不是壁纸图片 —— 颜色取自壁纸的莫奈色板）：
    //    两团大色块是液态玻璃底栏的「可折射内容」—— 色块边缘穿过玻璃时被弯折、色散
    if (b.bgGlowAlpha > 0f) {
        drawRect(
            brush = Brush.radialGradient(
                colors = listOf(b.bgGlow.copy(alpha = b.bgGlowAlpha), Color.Transparent),
                center = Offset(w * 0.74f, h * 0.16f),
                radius = max(w * 0.85f, h * 0.42f),
            ),
            topLeft = Offset.Zero,
            size = Size(w, h),
        )
        drawRect(
            brush = Brush.radialGradient(
                colors = listOf(b.primary.copy(alpha = b.bgGlowAlpha * 0.7f), Color.Transparent),
                center = Offset(w * 0.18f, h * 0.70f),
                radius = max(w * 0.70f, h * 0.36f),
            ),
            topLeft = Offset.Zero,
            size = Size(w, h),
        )
    }
}

/**
 * 玻璃扫光的节奏（镜面扫光与折射共用同一个相位）。
 *
 * 想彻底关掉这层动画（比如觉得费电）把 [GLASS_SWEEP_ENABLED] 改成 false 就行 ——
 * 玻璃的折射、高光、描边都还在，只是不再缓缓移动。
 */
private const val GLASS_SWEEP_ENABLED = true

/** 一道扫光走完整屏的时长。够慢才是「流动」，短了就成了「闪烁」。 */
private const val GLASS_SWEEP_PERIOD_MS = 26_000L

/** 最短重绘间隔：扫光变化很慢，没必要跑满 60fps，省一半的电。 */
private const val GLASS_SWEEP_MIN_FRAME_MS = 32L

/**
 * 背景层。
 *
 * 背景只有一张图（内置默认图，或者用户自选的那张），本身是静态的。这里唯一要做的事，
 * 是推进「玻璃扫光」的相位：
 * - 动画值只在**绘制阶段**读取（`drawBehind` 里的 `phase`），所以每帧只重画，
 *   **不会触发重组**，玻璃面板不会被拖着一起重组。
 * - 图还没解码完时只画底色渐变，不闪白也不闪黑。
 */
@Composable
fun BackgroundLayer(backdrop: Backdrop) {
    val root = LocalRootSize.current
    // 设计重做（2026-10）：镜面扫光的 withFrameNanos 循环删除 ——
    // 新背景是静态的（渐变 / 图片），没有任何需要驱动的动效
    Spacer(
        Modifier
            .fillMaxSize()
            .drawBehind {
                val w = if (root.width > 0) root.width.toFloat() else size.width
                val h = if (root.height > 0) root.height.toFloat() else size.height
                drawBackdropArt(backdrop, w, h)
            },
    )
}

private fun decodeImage(context: Context, uriStr: String): ImageBitmap? = try {
    val uri = Uri.parse(uriStr)
    context.contentResolver.openInputStream(uri)?.use { input ->
        val options = BitmapFactory.Options().apply { inSampleSize = 2 }
        BitmapFactory.decodeStream(input, null, options)?.asImageBitmap()
    }
} catch (t: Throwable) {
    null
}

/**
 * 背景参数：把当前配色 + 用户设置拼成一份 [Backdrop]。
 *
 * 单独抽出来是因为**截图回归也要渲染同一份背景** —— 两个主题各出一张图，
 * 免得测试里自己拼一套、跟线上跑的不是同一个东西。
 */
@Composable
fun rememberBackdrop(bgUri: String, dim: Float, blur: Float): Backdrop {
    val palette = LocalPalette.current
    return backdropOf(palette, decodeBackdrop(bgUri), dim, blur.dp)
}

@Composable
private fun decodeBackdrop(uriStr: String): ImageBitmap? {
    val context = LocalContext.current
    // 解码放到 IO 线程，避免切页时卡一下
    val bitmap by produceState<ImageBitmap?>(initialValue = null, uriStr) {
        // 没选自定义背景 → null，交给程序化渐变去画（这是默认路径）
        value = if (uriStr.isBlank()) null else withContext(Dispatchers.IO) { decodeImage(context, uriStr) }
    }
    return bitmap
}

// ================= 玻璃组件 =================

/**
 * 真·液态玻璃。
 *
 * 面板内容分四层，从下往上：
 *   ① 被模糊的真实背景 —— 把整屏背景按 -面板位置 平移进来，裁剪成面板形状，再 blur
 *   ② 玻璃染色（半透明白/黑 + 顶部高光）
 *   ③ 面板内容
 *   ④ 1px 亮边
 *
 * 关键点：模糊层的尺寸只有**面板那么大**（不是整屏），所以代价和面板面积成正比。
 * 默认渐变背景没有细节可模糊，这时直接跳过 ①（省一次离屏渲染）。
 */
/**
 * 扁平 squircle 行卡（设计重做 2026-10）：HyperOS 式实底色超椭圆，可点。
 * 旧签名（shape / glassAlpha / tint* / refract / big）保留只为兼容现有调用点 ——
 * 玻璃染色 / 扫光 / 多层描边那套已随重做删除。
 */
@Composable
fun GlassSurface(
    shape: Shape,
    glassAlpha: Float,
    modifier: Modifier = Modifier,
    borderColor: Color? = null,
    /** 不再区分大面板：统一同一套卡片视觉。参数保留兼容。 */
    big: Boolean = false,
    tintTop: Float? = null,
    tintBottom: Float? = null,
    refract: Boolean = true,
    onClick: (() -> Unit)? = null,
    content: @Composable BoxScope.() -> Unit,
) {
    val palette = LocalPalette.current
    val radius = if (big) RadiusR4 else RadiusR2
    Box(
        modifier
            .squircleBackground(palette.cardBg, radius, 1f)
            .then(
                if (borderColor != null) {
                    Modifier.border(1.dp, borderColor, RoundedCornerShape(radius))
                } else {
                    Modifier
                },
            )
            .then(if (onClick != null) Modifier.clickable(onClick = onClick) else Modifier),
    ) {
        content()
    }
}

/**
 * 扁平 squircle 卡片（设计重做 2026-10）：HyperOS 式实底色超椭圆 + 极淡投影。
 * 旧签名保留（[glassAlpha] 不再影响填充；[border] 变成 1dp 状态色描边），
 * 全部页面无需改动即自动换新视觉。
 */
@Composable
fun GlassCard(
    glassAlpha: Float,
    modifier: Modifier = Modifier,
    border: Color? = null,
    content: @Composable ColumnScope.() -> Unit,
) {
    val palette = LocalPalette.current
    val quality = LocalGlassQuality.current
    Column(
        modifier
            .fillMaxWidth()
            .padding(horizontal = 16.dp, vertical = 6.dp)
            .squircleBackground(palette.cardBg, RadiusR3, 1f)
            // 极淡投影：HyperOS 卡的「浮起」感来自这里，低配档整层去掉（弱机 shadow 贵）
            .then(
                if (quality == GlassQuality.LOW) {
                    Modifier
                } else {
                    Modifier.shadow(
                        elevation = 2.dp,
                        shape = RoundedCornerShape(RadiusR3),
                        clip = false,
                        ambientColor = Color.Black.copy(alpha = 0.05f),
                        spotColor = Color.Black.copy(alpha = 0.06f),
                    )
                },
            )
            .then(
                if (border != null) {
                    Modifier.border(1.dp, border, RoundedCornerShape(RadiusR3))
                } else {
                    Modifier
                },
            )
            .padding(18.dp),
        content = content,
    )
}

/**
 * 状态点（批 4c）：8dp 圆点 —— 状态色最小的那个形态，永远跟一句说清楚的文字一起出现（双通道）。
 * 颜色可以给状态色（绿 / 琥珀 / 红），也可以给选中色（紫）。
 */
@Composable
fun StatusDot(color: Color, size: Dp = StatusDotSize) {
    Box(Modifier.size(size).clip(CircleShape).background(color))
}

/**
 * 状态 chip（批 4c）：底色 = 颜色 alpha [ChipBgAlpha]，1dp 描边 alpha [CardBorderAlpha]，13sp 文字。
 * 需要「一眼看出状态、又不许铺一片色」的地方用它（[SourceChip] 也是它的一个用法）。
 */
@Composable
fun StatusChip(text: String, color: Color) {
    Text(
        text,
        modifier = Modifier
            .clip(RoundedCornerShape(RadiusR1))
            .background(color.copy(alpha = ChipBgAlpha))
            .border(1.dp, color.copy(alpha = CardBorderAlpha), RoundedCornerShape(RadiusR1))
            .padding(horizontal = 8.dp, vertical = 2.dp),
        fontSize = 13.sp,
        fontWeight = FontWeight.Medium,
        color = color,
    )
}

/** 胶囊按钮（筛选、档位选择）：选中 = 主色实底白字，未选中 = 中性灰底。 */
@Composable
fun GlassPill(text: String, selected: Boolean, modifier: Modifier = Modifier, onClick: () -> Unit) {
    val palette = LocalPalette.current
    val shape = RoundedCornerShape(50)
    Box(
        modifier
            .clip(shape)
            .background(
                if (selected) {
                    palette.primary
                } else {
                    // HyperOS 的未选块是中性灰（浅 #F0F0F0 / 深 #434343），不带任何描边
                    if (palette.dark) Color(0xFF434343) else Color(0xFFF0F0F0)
                },
            )
            .clickable(onClick = onClick)
            .padding(horizontal = 14.dp, vertical = 8.dp),
        contentAlignment = Alignment.Center,
    ) {
        Text(
            text,
            fontSize = 13.sp,
            color = if (selected) Color.White else palette.text,
            fontWeight = if (selected) FontWeight.Medium else FontWeight.Normal,
        )
    }
}

@Composable
fun ScreenHeader(title: String, sub: String, actions: @Composable RowScope.() -> Unit = {}) {
    val palette = LocalPalette.current
    // Miuix 设置页式大标题（设计重做 2026-10）：大字标题 + 灰色副题（不再用主色当副题）
    Row(
        Modifier.fillMaxWidth().padding(start = 18.dp, end = 16.dp, top = 18.dp, bottom = 8.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Column(Modifier.weight(1f)) {
            Text(title, fontSize = 32.sp, fontWeight = FontWeight.Medium, color = palette.text)
            Text(sub, fontSize = 13.sp, color = palette.sub)
        }
        actions()
    }
}

@Composable
fun StatCell(big: String, small: String, modifier: Modifier = Modifier) {
    val palette = LocalPalette.current
    Column(
        modifier
            .clip(RoundedCornerShape(RadiusR2))
            .background(palette.glassTint.copy(alpha = 0.10f))
            .padding(vertical = 10.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        Text(big, fontSize = 16.sp, fontWeight = FontWeight.Bold, color = palette.text)
        Text(small, fontSize = 13.sp, color = palette.sub)
    }
}

// ================= 运行状态 =================

enum class Health(val label: String) { OK("就绪"), WARN("待确认"), BAD("有问题") }

/** skillId → 给人看的名字。运行状态页和军师页共用，别再各写一份 if-else。 */
fun skillName(id: String): String = when (id) {
    "full" -> "狗头军师·满血版"
    "coder" -> "程序员搭子"
    "custom" -> "自定义 skill"
    else -> "原版狗头军师"
}

/** 顶栏那个小圆点/勾叉就靠它：模块激活 + Key + （心跳或手动确认）。 */
/**
 * 「模块到底生效了没」的判定，返回能说清缘由的说明；没生效返回 null。
 *
 * 为什么要三个信号：现代 API 之后，模块被注入哪些进程**严格跟随作用域勾选**。
 * legacy 时代框架会无条件把模块也注入它自己的 App 进程（那是 New XSharedPreferences
 * 机制的一部分），所以老的「自注入探针」一直成立；换成现代 API 后这条不再成立 ——
 * 用户只勾了微信时探针永远不亮，但模块其实工作得好好的。所以改成
 * 「任意一个信号成立即算生效」：
 *
 * 1. 自注入探针：用户显式把本模块也勾进作用域时成立（scope.list 里已放了本模块的包名）；
 * 2. service 通道已建立：框架认得本模块、并且正在跟它通信（见 RemoteSync）；
 * 3. 微信进程报过心跳：最硬的证据 —— 模块真的在微信里跑起来了。
 */
fun moduleActiveReason(store: ConfigStore): String? = when {
    ModuleStatus.isActive() -> "框架已把模块注入本应用"
    RemoteSync.bound -> "框架已连上本模块（service 通道已建立）"
    store.heartbeatAt() > 0 -> "微信进程里跑过本模块"
    else -> null
}

fun moduleActive(store: ConfigStore): Boolean = moduleActiveReason(store) != null

/** 顶栏那个小圆点/勾叉就靠它：模块激活 + Key + （心跳或手动确认）。 */
fun healthOf(store: ConfigStore): Health {
    val active = moduleActive(store)
    val cfg = store.load()
    val heartbeat = store.heartbeatAt()
    val fresh = heartbeat > 0 && System.currentTimeMillis() - heartbeat < 6 * 3600_000L
    return when {
        !active || cfg.apiKey.isBlank() -> Health.BAD
        !fresh && !store.scopeConfirmed() -> Health.WARN
        else -> Health.OK
    }
}

fun appVersion(context: Context): String = try {
    val info = context.packageManager.getPackageInfo(context.packageName, 0)
    "v${info.versionName}(${info.versionCode})"
} catch (t: Throwable) {
    "v?"
}

fun formatTime(ts: Long): String =
    if (ts <= 0) "从未" else SimpleDateFormat("MM-dd HH:mm", Locale.getDefault()).format(Date(ts))

// ================= 主界面 =================

/**
 * 底栏五个 tab 的下标。
 *
 * 顺序就是「用得上的先后」：先看出没出问题 → 让军师给出怎么回 → 管角色的人设 →
 * 实在想自己试一句才去「试一试」。设置永远最后。
 * 常量而不是裸数字：改顺序时漏改一处是最容易犯的错，让编译器帮忙盯着。
 */
private const val TAB_STATUS = 0
private const val TAB_MENTOR = 1
private const val TAB_ROLES = 2
private const val TAB_TRIAL = 3
private const val TAB_SETTINGS = 4

@Composable
fun App(store: ConfigStore) {
    var tab by rememberSaveable { mutableIntStateOf(0) }
    // 「角色」的二级页（打开了某个人）也放在这一层：切走 tab 再回来时能回到原位
    var roleOpen by rememberSaveable { mutableStateOf<String?>(null) }
    // 「设置」这条线上现在有四层：设置(0) → 高级设置(1) → 诊断(2) / 拉取到的联系人(3)
    var settingsPage by rememberSaveable { mutableIntStateOf(0) }
    var settingsReturnPage by rememberSaveable { mutableIntStateOf(0) }
    var settingsSection by rememberSaveable { mutableStateOf(SettingsSection.CONNECTION) }
    var settingsDirty by remember { mutableStateOf(false) }
    var settingsSave by remember { mutableStateOf<(() -> Unit)?>(null) }
    var pendingTab by remember { mutableStateOf<Int?>(null) }
    val savedPages = rememberSaveableStateHolder()
    fun selectTab(next: Int) {
        if (next != TAB_ROLES || next == tab) roleOpen = null
        if (next != TAB_SETTINGS || next == tab) settingsPage = 0
        tab = next
    }
    // 只关心「影响外观」的那几个字段：玻璃透明度/模糊、背景
    var ui by remember { mutableStateOf(store.load()) }
    // 模块健康状态：底栏徽标已移除，详情在「状态」页里看；healthOf 留作页面内部用
    // 设备能力只问一次：isLowRamDevice 有成本，运行中也不会变
    val context = LocalContext.current
    val lowRam = remember { isLowRamDevice(context) }
    val glassQuality = remember(ui.glassQuality) {
        decideGlassQuality(ui.glassQuality, lowRam, Build.VERSION.SDK_INT)
    }

    BackHandler(enabled = tab == TAB_SETTINGS && settingsPage in 2..3) { settingsPage = settingsReturnPage }
    GoutouTheme {
        val imeVisible = WindowInsets.ime.getBottom(LocalDensity.current) > 0
        var rootSize by remember { mutableStateOf(IntSize.Zero) }
        val backdrop = rememberBackdrop(ui.bgUri, ui.bgDim, ui.glassBlur)

        val phase = remember { GlassPhase() }
        // 液态玻璃结构反转（2026-10 重写）：折射 RenderEffect 挂在**整个内容层**上，
        // shader 只在底栏矩形内折射（content = 整页真实内容 —— 滚过底栏的文字被真实
        // 弯折 / 色散），矩形外逐像素直通。底栏 UI 只上报矩形 + 弹簧手势。
        val refraction = remember { LiquidGlass() }
        val nav = remember { LiquidNavState(refraction.available) }
        CompositionLocalProvider(
            LocalBackdrop provides backdrop,
            LocalRootSize provides rootSize,
            LocalGlassPhase provides phase,
            LocalGlassQuality provides glassQuality,
            LocalLiquidNav provides nav,
        ) {
            var contentAlpha by remember { mutableStateOf(0f) }
            LaunchedEffect(tab) {
                contentAlpha = 0f
                animate(0f, 1f, animationSpec = tween(220)) { value, _ -> contentAlpha = value }
            }
            Box(
                Modifier
                    .fillMaxSize()
                    .onGloballyPositioned { rootSize = it.size },
            ) {
                Box(Modifier.fillMaxSize().then(if (imeVisible) Modifier else Modifier.liquidGlassRefraction(nav, glassQuality, refraction))) {
                    if (ui.bgUri.isBlank()) {
                        Spacer(Modifier.fillMaxSize().background(LocalPalette.current.bgTop))
                    } else BackgroundLayer(backdrop)
                    Column(
                        Modifier
                            .fillMaxSize()
                            .statusBarsPadding()
                            .graphicsLayer {
                                alpha = contentAlpha
                                translationY = (1f - contentAlpha) * 36f
                            },
                    ) {
                    savedPages.SaveableStateProvider(tab) {
                    when (tab) {
                        TAB_STATUS -> StatusScreen(store,
                            onTrial = { tab = TAB_TRIAL },
                            onConnection = { settingsSection = SettingsSection.CONNECTION; settingsPage = 1; tab = TAB_SETTINGS },
                            onDiag = { settingsReturnPage = 0; settingsPage = 2; tab = TAB_SETTINGS },
                        )
                        TAB_MENTOR -> MentorScreen(store, ui.glassAlpha) { ui = store.load() }
                        TAB_ROLES -> RolesScreen(store, ui.glassAlpha, roleOpen) { roleOpen = it }
                        TAB_TRIAL -> TrialScreen(store, ui.glassAlpha)
                        else -> when (settingsPage) {
                            1 -> AdvancedScreen(
                                store = store,
                                ui = ui,
                                onSaved = { ui = store.load() },
                                onOpenDiag = { settingsReturnPage = 1; settingsPage = 2 },
                                onOpenCandidates = { settingsReturnPage = 1; settingsPage = 3 },
                                onBack = { settingsPage = 0 },
                                section = settingsSection,
                                onDraftState = { dirty, save -> settingsDirty = dirty; settingsSave = save },
                            )
                            // 诊断从「高级设置」里进，所以返回也应该回到高级设置
                            2 -> DiagScreen(store, ui.glassAlpha) { settingsPage = settingsReturnPage }
                            // 「拉取到的联系人」也是从高级设置里进的（白名单卡），返回同理
                            3 -> ChatCandidatesScreen(
                                store = store,
                                ui = ui,
                                onSaved = { ui = store.load() },
                                onBack = { settingsPage = settingsReturnPage },
                            )
                            4 -> AppearanceScreen(store, onSaved = { ui = store.load() }, onBack = { settingsPage = 0 })
                            5 -> AboutScreen { settingsPage = 0 }
                            else -> SettingsScreen(
                                store = store,
                                ui = ui,
                                onOpenAdvanced = { settingsSection = SettingsSection.CONNECTION; settingsPage = 1 },
                                onOpenSection = { settingsSection = it; settingsPage = 1 },
                                onOpenDiag = { settingsReturnPage = 0; settingsPage = 2 },
                                onOpenCandidates = { settingsReturnPage = 0; settingsPage = 3 },
                                onOpenAppearance = { settingsPage = 4 },
                                onOpenAbout = { settingsPage = 5 },
                                onSaved = { ui = store.load() },
                            )
                        }
                    }
                }
                }   // 保存各页草稿
                }   // 折射内容层（liquidGlassRefraction 那个 Box）
                if (!imeVisible) LiquidNavBar(
                    tab = tab,
                    modifier = Modifier.align(Alignment.BottomCenter).navigationBarsPadding().padding(14.dp),
                ) { next ->
                    if (settingsDirty) pendingTab = next else selectTab(next)
                }
                pendingTab?.let { next ->
                    AlertDialog(
                        onDismissRequest = { pendingTab = null },
                        title = { Text("有未保存的设置") },
                        text = { Text("保存后切换页面，或放弃这次修改。") },
                        confirmButton = { TextButton(onClick = { settingsSave?.invoke(); pendingTab = null; selectTab(next) }) { Text("保存并切换") } },
                        dismissButton = { TextButton(onClick = { pendingTab = null; selectTab(next) }) { Text("放弃修改") } },
                    )
                }
            }
        }
    }
}
