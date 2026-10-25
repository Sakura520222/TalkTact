package dev.goutou.wingman.ui

import android.graphics.Bitmap
import android.graphics.BitmapShader
import android.graphics.RenderEffect
import android.graphics.RuntimeShader
import android.graphics.Shader
import android.os.Build
import android.util.Log
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.tween
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.awaitEachGesture
import androidx.compose.foundation.gestures.awaitFirstDown
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.runtime.withFrameNanos
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.clipToBounds
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.isSpecified
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.TransformOrigin
import androidx.compose.ui.graphics.lerp
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.graphics.asComposeRenderEffect
import androidx.compose.ui.graphics.drawscope.withTransform
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.hapticfeedback.HapticFeedbackType
import androidx.compose.ui.input.pointer.PointerEventPass
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.onGloballyPositioned
import androidx.compose.ui.layout.positionInRoot
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalHapticFeedback
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.IntSize
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import top.yukonga.miuix.kmp.squircle.squircleBackground
import top.yukonga.miuix.kmp.squircle.squircleBorder
import androidx.compose.runtime.staticCompositionLocalOf
import kotlin.math.abs
import kotlin.math.cos
import kotlin.math.exp
import kotlin.math.roundToInt
import kotlin.math.sin
import kotlin.math.sqrt

/**
 * 底栏「液态玻璃」——Salt Player 12.5.0 MiniPlayer 的**完整**玻璃系统。
 *
 * 三个部分都来自对该 APK 的静态逆向（证据在仓库根 `MiniPlayerLiquidGlass/decompiled-evidence/`，
 * 类名 / 参数 / 公式全部对得上，不是凭视觉重写）：
 *
 * 1. **AGSL 折射着色器**（gr1.java 按 12 次 replace 顺序还原的最终源码）：边缘折射、
 *    RGB 色散、触点 dome。初值取自 C0060.java：refraction=.85 / curve=.7 / dispersion=.4 /
 *    saturation=1 / contrast=1 / tint=0 / edge=.2 / specStrength=0（MiniPlayer 没开高光，
 *    证据里找不到更新它的代码，不臆测补全）。
 * 2. **弹簧运动系统**（m50 / uq1 / vq1.java）：5 个欠阻尼解析积分弹簧 —— 按压、
 *    位移 x/y、触点 x/y；按下 / 松手各一套频率与阻尼，松手轻触还会踢一脚速度（vq1.m7901）。
 *    由此驱动 C0071 case 8 的液滴形变：横拖 → 横胖竖瘦，纵拖 → 竖长，整体平移，锚在触点。
 * 3. **softLight 噪点材质链**（AbstractC1049.m10085，AGSL 原文照抄）：折射之上再叠
 *    一层 softLight 噪点（Salt 用 haze_noise 贴图，这里程序化生成灰度噪声）——
 *    磨砂质感就是这层给的。
 *
 * 每帧 uniform 写法照抄 AbstractC1049.m10084：lensCenter / touchPoint 先经过
 * [GlassTransform] 仿射（`br1.m963`），shapeScale 用非均匀缩放，cornerRadius 夹到半短边。
 * 玻璃上的 UI（图标 / 文字）应用同一个 transform（kh1:420-435 的做法）。
 *
 * 需要 API 33+（RuntimeShader）。低版本 / Robolectric / 编译失败由 [LiquidGlassShell]
 * 退回 [GlassSurface]，档位语义见 [GlassQuality]。
 */
private const val LIQUID_GLASS_AGSL = """
uniform float2 resolution;
uniform float2 lensCenter;
uniform float2 lensSize;
uniform float4 cornerRadii;
uniform float refraction;
uniform float curve;
uniform float dispersion;
uniform float saturation;
uniform float contrast;
uniform float4 tint;
uniform float edge;
uniform float2 lightDir;
uniform float specStrength;
uniform float specPower;
uniform float specRimMix;     // body<->rim crossfade
uniform float specWidthPx;
uniform float specLightZ;
uniform float specDomeFrac;
uniform float specBodyPower;
uniform float specBodyGain;
uniform float specFocalK;      // focal-pool offset toward light (fraction of minHalf)
uniform float specPoolFrac;    // focal-pool radius (fraction of minHalf)
uniform float specPoolGain;    // focal-pool peak scale
uniform float2 touchPoint;
uniform float touchStrength;
uniform float2 shapeScale;
uniform float blurRadius;
uniform shader content;

const float SMOOTH_EDGE_PX = 1.5;
const float SEAM_BLEND_PX = 8.0;   // diagonal blend half-width (px); larger = softer interior crease

// 5×5 高斯权重网格（25-tap，可分离高斯权重 + GPU 双线性采样）：
// 平滑的「真高斯」观感（Backdrop 的 blur() 用系统 BlurEffect 真高斯；我们挂在整页
// content 上不能区域化系统 blur，等价做法是在 shader 内做足采样的高斯）。
// ⚠️ SkSL 不支持嵌套 for 循环（编译直接失败）—— 25 个 tap 用单层循环 + 整除展开。
// r ≤ 0.5 时直通采样；只在底栏矩形内被调用，矩形外不付出这笔采样成本。
half3 blur3(float2 c, float r) {
    if (r <= 0.5) {
        return content.eval(c).rgb;
    }
    half3 acc = half3(0.0);
    float total = 0.0;
    float s = r * 0.5;
    for (int i = 0; i < 25; i++) {
        int gx = i / 5 - 2;
        int gy = i - (i / 5) * 5 - 2;
        float w = exp(-float(gx * gx + gy * gy) * 0.25);
        acc += content.eval(c + float2(float(gx), float(gy)) * s).rgb * w;
        total += w;
    }
    return acc / total;
}

// TL, TR, BR, BL; coordinates are relative to the lens center.
float cornerRadiusAt(float2 p, float2 halfDim) {
    float r = p.y < 0.0
        ? (p.x < 0.0 ? cornerRadii.x : cornerRadii.y)
        : (p.x < 0.0 ? cornerRadii.w : cornerRadii.z);
    return clamp(r, 0.0, min(halfDim.x, halfDim.y));
}

// Signed distance to a box with rounded corners
// Negative = inside, Positive = outside, Zero = on boundary
float boxRoundedSDF(float2 p, float2 halfDim, float r) {
    r = cornerRadiusAt(p, halfDim);
    float2 d = abs(p) - halfDim + float2(r);
    float exterior = length(max(d, 0.0));
    float interior = min(max(d.x, d.y), 0.0);
    return exterior + interior - r;
}

// Outward-facing direction vector from the lens surface
float2 lensNormalDirection(float2 p, float2 halfDim, float r) {
    r = cornerRadiusAt(p, halfDim);
    float2 d = abs(p) - halfDim + float2(r);
    float2 s = float2(p.x >= 0.0 ? 1.0 : -1.0, p.y >= 0.0 ? 1.0 : -1.0);

    if (max(d.x, d.y) > 0.0) {
        return s * normalize(max(d, 0.0));
    }
    return d.x > d.y ? float2(s.x, 0.0) : float2(0.0, s.y);
}

// Perceptual brightness (ITU-R BT.709 standard)
float toBrightness(half3 c) {
    return dot(c, half3(0.2126, 0.7152, 0.0722));
}

// Color processing: vibrancy, intensity adjustment, and color overlay
half3 processColor(half3 src, float vibrancy, float intensity, float4 overlay) {
    float mono = toBrightness(src);
    half3 vibrant = half3(clamp(mix(half3(mono), src, vibrancy), 0.0, 1.0));
    half3 adjusted = half3(clamp((vibrant - 0.5) * intensity + 0.5, 0.0, 1.0));
    return mix(adjusted, half3(overlay.rgb), overlay.a);
}

half4 main(float2 xy) {
    float2 halfDim = lensSize * 0.5;
    float r = cornerRadiusAt((xy - lensCenter) / shapeScale, halfDim);

    float2 p = (xy - lensCenter) / shapeScale;
    float sdf = boxRoundedSDF(p, halfDim, r);

    // Skip pixels outside the lens boundary
    if (sdf > SMOOTH_EDGE_PX) {
        return content.eval(xy);
    }

    float2 normal = normalize(lensNormalDirection(p, halfDim, r) / shapeScale);

    // Compute refracted sample position
    // A local lens dome follows the finger, independently of the capsule's global stretch.
// The smooth boundary mask prevents refraction leaking outside the shared silhouette.
float touchRadius = max(lensSize.y * 0.85, 1.0);
float2 touchDelta = (xy - touchPoint) / touchRadius;
float touchDome = exp(-dot(touchDelta, touchDelta) * 2.0);
float touchMask = 1.0 - smoothstep(-3.0, 0.0, sdf);
float2 touchBend = touchDelta * touchDome * touchStrength * lensSize.y * 0.055 * touchMask;
float2 sampleXY = xy - touchBend;
    if (refraction > 0.0 && curve > 0.0) {
        float minDim = min(halfDim.x, halfDim.y);
        float depth = clamp(-sdf / (minDim * refraction), 0.0, 1.0);
        float curvature = 1.0 - depth;
        float bend = 1.0 - sqrt(1.0 - curvature * curvature);
        sampleXY = xy - touchBend - bend * curve * minDim * normal;
    }

    // RGB channel separation for prismatic effect
    half4 pixel;
    if (dispersion > 0.0) {
        float2 normP = p / halfDim;
        float2 shift = dispersion * normP * normP * normP * min(halfDim.x, halfDim.y) * 0.1;

        float2 xyR = sampleXY - shift;
        float2 xyG = sampleXY;
        float2 xyB = sampleXY + shift;

        if (blurRadius > 0.5) {
            // 磨砂：三个色散通道各自过螺旋高斯（模糊的色散）
            half3 gVal = blur3(xyG, blurRadius);
            half3 rVal = blur3(xyR, blurRadius * 0.9);
            half3 bVal = blur3(xyB, blurRadius * 0.9);
            pixel = half4(rVal.r, gVal.g, bVal.b, 1.0);
        } else {
            float sdfR = boxRoundedSDF((xyR - lensCenter) / shapeScale, halfDim, r);
            float sdfB = boxRoundedSDF((xyB - lensCenter) / shapeScale, halfDim, r);

            half4 gVal = content.eval(xyG);
            half4 rVal = (sdfR <= 0.0) ? content.eval(xyR) : gVal;
            half4 bVal = (sdfB <= 0.0) ? content.eval(xyB) : gVal;

            pixel = half4(rVal.r, gVal.g, bVal.b, gVal.a);
        }
    } else if (blurRadius > 0.5) {
        pixel = half4(blur3(sampleXY, blurRadius), 1.0);
    } else {
        pixel = content.eval(sampleXY);
    }

    // Handle fully transparent samples
    if (pixel.a <= 0.0) {
        pixel = content.eval(xy);
    }

    pixel.rgb = processColor(pixel.rgb, saturation, contrast, tint);

    // Specular highlight: 4 terms — focal pool (dual-axis moving hotspot) + body sheen + Blinn rim
    // glint + back-rim fill. The gate also tests specStrength, so NoGlow is bit-exact off.
    if (edge > 0.0 && specStrength > 0.0) {
        float2 lightVec = normalize(lightDir);

        // Seam-free in-plane direction (specular only; refraction's 'normal' is untouched). The
        // rounded-rect interior is an L-inf field, so the true gradient is discontinuous along the
        // diagonal; blend it over SEAM_BLEND_PX.
        float2 d2 = abs(p) - halfDim + float2(r);
        float2 s2 = float2(p.x >= 0.0 ? 1.0 : -1.0, p.y >= 0.0 ? 1.0 : -1.0);
        float2 specDir2;
        if (max(d2.x, d2.y) > 0.0) {
            specDir2 = s2 * normalize(max(d2, 0.0));
        } else {
            // +1e-4 guards the dead-center normalize singularity.
            float w  = clamp(0.5 + 0.5 * (d2.x - d2.y) / SEAM_BLEND_PX, 0.0, 1.0);
            float2 v = float2(s2.x * w, s2.y * (1.0 - w)) + float2(0.0, 1.0e-4);
            specDir2 = normalize(v);
        }

        // Fake-3D surface normal from the rounded-rect bevel.
        float minHalf = min(halfDim.x, halfDim.y);
        float bevelPx = max(minHalf * specDomeFrac, 1.0);
        float depthIn = max(-sdf, 0.0);
        float t       = clamp(depthIn / bevelPx, 0.0, 1.0);
        float n_cos   = 1.0 - t;
        float n_sin   = sqrt(max(1.0 - n_cos * n_cos, 0.0));      // float, not half: cancellation near n_cos~1
        float3 N      = normalize(float3(specDir2 * n_cos, n_sin + 1.0e-3));

        float3 L = normalize(float3(lightVec, specLightZ));
        float3 V = float3(0.0, 0.0, 1.0);

        // Moving focal hotspot: offset toward lightVec so the bright pool tracks tilt on both axes
        // (pitch=vertical, roll=horizontal). inside masks it off past the rim.
        float2 focal     = lightVec * (minHalf * specFocalK);
        float  poolR     = max(minHalf * specPoolFrac, 1.0);      // guard against zero-width smoothstep
        float  poolD     = length(p - focal);
        float  pool      = 1.0 - smoothstep(0.0, poolR, poolD);   // 1 at focal, 0 at rim
        float  inside    = 1.0 - smoothstep(-6.0, 0.0, sdf);
        float  focalPool = pool * pool * specStrength * specPoolGain * inside; // pool^2 = tighter core

        // Broad body sheen (gentle modeling fill). float, not half: pow bands in fp16 (applies below too).
        float ndl       = max(dot(N, L), 0.0);
        float bodySheen = pow(ndl, specBodyPower) * specStrength * specBodyGain;

        // Tight Blinn rim glint, confined to the rim band. max(specWidthPx, 1.0) guards the
        // zero-width (implementation-defined hard-step) smoothstep.
        float3 H       = normalize(L + V);
        float  rimBand = smoothstep(-max(specWidthPx, 1.0), 0.0, sdf);
        float  glint   = pow(max(dot(N, H), 0.0), specPower) * specStrength;
        float  rim     = glint * rimBand;

        // Back-rim fill (opposite light, rim-locked, 1/4 weight).
        float3 Lb   = normalize(float3(-lightVec, specLightZ));
        float  back  = pow(max(dot(N, Lb), 0.0), specPower) * specStrength * rimBand * 0.25;

        // Ordered dither to break 8-bit Mach banding on the body ramp. fract-bound the coord before
        // sin so the sin argument can't blow up on large lenses.
        float2 hp = fract((p / minHalf) * 0.5 + 0.5);             // bounded ~[0,1)
        float  dn = fract(sin(dot(hp, float2(12.9898, 78.233))) * 43758.5453) - 0.5;

        // Linear body<->rim crossfade: rimMix=0 pure focal pool, rimMix=1 pure rim glint (legacy look).
        float body      = focalPool + bodySheen + dn * (1.0 / 255.0) * specStrength;
        float rimMix    = clamp(specRimMix, 0.0, 1.0);
        float highlight = body * (1.0 - rimMix) + (rim + back) * rimMix;

        // Screen blend, clamped to [0,1] so it survives bright backgrounds and never exceeds 1.0.
        pixel.rgb += half3((1.0 - pixel.rgb) * clamp(highlight, 0.0, 1.0));
    }

    // Anti-aliased edge transition
    float alpha = 1.0 - smoothstep(-SMOOTH_EDGE_PX * 0.5, SMOOTH_EDGE_PX * 0.5, sdf);
    half4 bg = content.eval(xy);
    return mix(bg, pixel, alpha);
}
"""

/**
 * softLight 噪点材质链（AbstractC1049.m10085 里的 AGSL **原文**，仅删了 Android Studio 式缩进外层换行）。
 * Salt Player 用它把 haze_noise 贴图以 softLight 混进折射结果，再叠 tintColor —— 磨砂质感的来源。
 */
private const val NOISE_AGSL = """
uniform shader content;
uniform shader noise;
uniform float noiseAlpha;
layout(color) uniform half4 tintColor;

half guardedDivide(half numerator, half denominator) {
    return denominator == 0.0 ? 0.0 : numerator / denominator;
}

half softLightComponent(half2 source, half2 destination) {
    if (2.0 * source.x <= source.y) {
        return guardedDivide(
            destination.x * destination.x * (source.y - 2.0 * source.x),
            source.y
        ) + (1.0 - source.y) * source.x
        + destination.x * (-source.y + 2.0 * source.x + 1.0);
    } else if (4.0 * destination.x <= destination.y) {
        half destinationSquared = destination.x * destination.x;
        half destinationCubed = destinationSquared * destination.x;
        half destinationAlphaSquared = destination.y * destination.y;
        half destinationAlphaCubed = destinationAlphaSquared * destination.y;
        return guardedDivide(
            destinationAlphaSquared
                * (source.x - destination.x * (3.0 * source.y - 6.0 * source.x - 1.0))
                + 12.0 * destination.y * destinationSquared * (source.y - 2.0 * source.x)
                - 16.0 * destinationCubed * (source.y - 2.0 * source.x)
                - destinationAlphaCubed * source.x,
            destinationAlphaSquared
        );
    } else {
        return destination.x * (source.y - 2.0 * source.x + 1.0)
            + source.x
            - sqrt(destination.y * destination.x) * (source.y - 2.0 * source.x)
            - destination.y * source.x;
    }
}

half4 softLight(half4 source, half4 destination) {
    if (destination.a == 0.0) {
        return source;
    }
    return half4(
        softLightComponent(source.ra, destination.ra),
        softLightComponent(source.ga, destination.ga),
        softLightComponent(source.ba, destination.ba),
        source.a + (1.0 - source.a) * destination.a
    );
}

half4 main(float2 coord) {
    half4 blurred = content.eval(coord);
    half4 noiseColor = noise.eval(coord) * noiseAlpha;
    half4 withNoise = softLight(noiseColor, blurred);
    half4 tint = tintColor.rgb1 * tintColor.a;
    return tint + withNoise * (1.0 - tintColor.a);
}
"""

/** App 界面侧的日志 tag（诊断底栏玻璃效果时用 logcat 抓这个）。 */
private const val TAG = "WingmanUi"

/** softLight 噪点的强度（Salt 的 haze_noise 走默认 1.0 缩放，实测观感取略低）。 */
private const val NOISE_ALPHA = 0.12f

// ================= 弹簧（m50 / vq1 照抄） =================

/**
 * 欠阻尼弹簧的**解析积分**（Salt Player m50.m4743 同公式）：给定固有频率与阻尼比，
 * 一步跳到 t+dt 的精确解 —— 不做欧拉逐步积分，大 dt 也不发散。
 * 参数（vq1.m7900）：按住时频率高、阻尼大（跟手），松手频率低、明显欠阻尼（回弹）。
 */
class DampedSpring(initial: Float = 0f) {
    var value = initial
    var target = initial
    var velocity = 0f

    fun step(dt: Float, frequency: Float, zeta: Float) {
        val decay = frequency * zeta
        val omega = frequency * sqrt(1f - zeta * zeta)
        val delta = value - target
        val c1 = (decay * delta + velocity) / omega
        val e = exp(-decay * dt)
        val s = sin(omega * dt)
        val c = cos(omega * dt)
        val raw = c * delta + s * c1
        value = e * raw + target
        velocity = e * (c1 * omega * c - decay * raw - delta * omega * s)
        if (settled()) snap()
    }

    private fun settled() = abs(value - target) < 1.0e-4f && abs(velocity) < 0.003f

    private fun snap() {
        value = target
        velocity = 0f
    }
}

/** 一帧的运动状态（ar1 / MiniPlayerGlassMotionFrame）。 */
data class GlassMotionFrame(
    val press: Float = 0f,
    val offsetX: Float = 0f,
    val offsetY: Float = 0f,
    val touchX: Float = 0.5f,
    val touchY: Float = 0.5f,
)

/** 玻璃的形变（br1 / MiniPlayerGlassTransform）：锚点 + 非均匀缩放 + 平移，单位 px。 */
data class GlassTransform(
    val pivotX: Float,
    val pivotY: Float,
    val scaleX: Float = 1f,
    val scaleY: Float = 1f,
    val translationX: Float = 0f,
    val translationY: Float = 0f,
) {
    /** br1.m963：面板坐标点经过本形变后的新位置（lensCenter / touchPoint uniform 都要先过它）。 */
    fun map(x: Float, y: Float): Offset = Offset(
        (x - pivotX) * scaleX + pivotX + translationX,
        (y - pivotY) * scaleY + pivotY + translationY,
    )
}

/**
 * C0071 case 8 的液滴形变公式（spanY = 面板高）：
 * - `scaleX = 1 + (0.025·|ox| + 0.075·press)·spanY / w`
 * - `scaleY = 1 + 0.06·press + 0.025·|oy| − 0.012·|ox|` —— 横拖把玻璃拉横胖竖瘦
 * - `translation = (0.13·ox, 0.09·oy)·spanY` —— 玻璃被拖走一点（跟手但不全跟）
 * - 锚点 = 当前触点（缩放围绕手指发生）
 */
fun glassTransformOf(frame: GlassMotionFrame, w: Float, h: Float): GlassTransform {
    val press = frame.press.coerceIn(-0.18f, 1.15f)
    return GlassTransform(
        pivotX = frame.touchX * w,
        pivotY = frame.touchY * h,
        scaleX = ((abs(frame.offsetX) * 0.025f + 0.075f * press) * h) / w + 1f,
        scaleY = abs(frame.offsetY) * 0.025f + 0.06f * press + 1f - abs(frame.offsetX) * 0.012f,
        translationX = frame.offsetX * h * 0.13f,
        translationY = frame.offsetY * h * 0.09f,
    )
}

/**
 * 5 弹簧运动系统（uq1 + vq1）。触点 / 位移都用「面板归一坐标」（0..1，和 uq1.m7704 一致）。
 * 频率 / 阻尼 / 松手踢速度全部照抄 vq1.m7900 / m7901。
 */
class LiquidGlassMotion {
    private val press = DampedSpring()
    private val offsetX = DampedSpring()
    private val offsetY = DampedSpring()
    private val touchX = DampedSpring(0.5f)
    private val touchY = DampedSpring(0.5f)

    var pressed = false
        private set

    /** 按下：press 目标 1，触点弹簧开始追手指。 */
    fun down(fx: Float, fy: Float) {
        pressed = true
        press.target = 1f
        retargetTouch(fx, fy)
    }

    /** 拖动：触点跟着走；位移目标 = 拖动量（每轴各自归一，±1 封顶）。 */
    fun move(fx: Float, fy: Float, dragX: Float, dragY: Float) {
        retargetTouch(fx, fy)
        offsetX.target = dragX.coerceIn(-1f, 1f)
        offsetY.target = dragY.coerceIn(-1f, 1f)
    }

    private fun retargetTouch(fx: Float, fy: Float) {
        touchX.target = fx.coerceIn(0f, 1f)
        touchY.target = fy.coerceIn(0f, 1f)
    }

    /**
     * 松手（vq1.m7901）：目标全部归零；**没有拖动过且按得很浅**时给速度 +7 ——
     * 轻轻一点，回弹多弹一下。
     */
    fun release(dragged: Boolean) {
        if (!pressed) return
        pressed = false
        if (!dragged && press.value < 0.15f) press.velocity += 7.0f
        press.target = 0f
        offsetX.target = 0f
        offsetY.target = 0f
    }

    /** 一帧积分（参数照抄 vq1.m7900），返回当前帧。 */
    fun advance(dt: Float): GlassMotionFrame {
        if (dt > 0f) {
            press.step(dt, if (pressed) 42f else 24f, if (pressed) 0.82f else 0.52f)
            val (of, oz) = if (pressed) 38f to 0.72f else 26f to 0.55f
            offsetX.step(dt, of, oz)
            offsetY.step(dt, of, oz)
            touchX.step(dt, 48f, 0.9f)
            touchY.step(dt, 48f, 0.9f)
        }
        return GlassMotionFrame(press.value, offsetX.value, offsetY.value, touchX.value, touchY.value)
    }

    /** uq1.m7703：全部弹簧到位 —— 静止时可以不推帧。 */
    fun settled() = !pressed &&
        abs(press.value - press.target) < 1.0e-4f && abs(press.velocity) < 0.003f &&
        abs(offsetX.value) < 1.0e-4f && abs(offsetX.velocity) < 0.003f &&
        abs(offsetY.value) < 1.0e-4f && abs(offsetY.velocity) < 0.003f
}

// ================= shader 封装（AbstractC1049.m10084/m10085 对应） =================

/**
 * 两个 RuntimeShader（折射 + 噪点材质链）与 RenderEffect 缓存。
 * 触摸类 uniform 每帧单独更新 —— RenderEffect 持有同一个 shader 实例，改 uniform 立即生效。
 */
class LiquidGlass {
    private val liquid: RuntimeShader? =
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            try {
                RuntimeShader(LIQUID_GLASS_AGSL)
            } catch (t: Throwable) {
                Log.w(TAG, "liquid glass AGSL compile failed", t)
                null
            }
        } else null

    private val noise: RuntimeShader? =
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            try {
                RuntimeShader(NOISE_AGSL).also {
                    it.setInputShader("noise", noiseTile())
                    it.setFloatUniform("noiseAlpha", NOISE_ALPHA)
                    // 染色仍走 Compose 那层（跟玻璃强度联动），材质链里保持无 tint
                    it.setColorUniform("tintColor", android.graphics.Color.argb(0, 0, 0, 0))
                }
            } catch (t: Throwable) {
                Log.w(TAG, "noise material AGSL compile failed", t)
                null
            }
        } else null

    val available: Boolean get() = liquid != null

    /** (w, h) → RenderEffect 的缓存；内容层 = 整屏，尺寸基本不变，实际只建一次。 */
    private val cache = HashMap<Long, RenderEffect?>()

    /**
     * 静态 uniform（C0060 初值）+ RenderEffect 链：refraction → softLight 噪点（m10085 的
     * createChainEffect，噪点层在外层、折射在内层 —— 即先折射再给噪点做 softLight）。
     * **挂在内容层上**：shader 只在底栏矩形内做折射，矩形外逐像素直通 ——
     * content 输入是整页真实内容（滚过底栏的文字就是在这里被弯折的）。
     */
    fun effect(w: Int, h: Int, blurPx: Float): RenderEffect? {
        val sh = liquid ?: return null
        if (w <= 0 || h <= 0) return null
        return cache.getOrPut((w.toLong() shl 20) or h.toLong()) {
            try {
                // C0060.java 初值（README 头有对照）。偏离的两处：
                // ① specStrength 0 → 0.65：Salt 没开高光，但玻璃的「静态存在感」全靠
                //    边缘透镜高光；② blurRadius 是我们加的磨砂层（Salt 用 Haze 材质做）；
                // ③ saturation 1 → 1.12：磨砂会把背景洗灰，提一点饱和补回来（vibrancy）。
                sh.setFloatUniform("resolution", w.toFloat(), h.toFloat())
                sh.setFloatUniform("refraction", 0.85f)
                sh.setFloatUniform("curve", 0.7f)
                sh.setFloatUniform("dispersion", 0.4f)
                sh.setFloatUniform("saturation", 1.12f)
                sh.setFloatUniform("contrast", 1f)
                sh.setFloatUniform("tint", 0f, 0f, 0f, 0f)
                sh.setFloatUniform("edge", 0.2f)
                sh.setFloatUniform("blurRadius", blurPx)
                sh.setFloatUniform("lightDir", -1f, -1f)
                sh.setFloatUniform("specStrength", 0.65f)
                sh.setFloatUniform("specPower", 1f)
                sh.setFloatUniform("specRimMix", 0.4f)
                sh.setFloatUniform("specWidthPx", 12f)
                sh.setFloatUniform("specLightZ", 0.55f)
                sh.setFloatUniform("specDomeFrac", 1.15f)
                sh.setFloatUniform("specBodyPower", 2.5f)
                sh.setFloatUniform("specBodyGain", 0.6f)
                sh.setFloatUniform("specFocalK", 0.55f)
                sh.setFloatUniform("specPoolFrac", 0.7f)
                sh.setFloatUniform("specPoolGain", 1.3f)
                val liquidEffect = RenderEffect.createRuntimeShaderEffect(sh, "content")
                // Salt 的 softLight 噪点材质链（m10085）按需求移除：那层颗粒是「磨砂感」
                // 的来源，这里要的是干净的高斯模糊 —— 折射 + 高斯 + 透镜高光即可。
                liquidEffect
            } catch (t: Throwable) {
                Log.w(TAG, "liquid glass effect build failed", t)
                null
            }
        }
    }

    /**
     * 每帧的动态 uniform（m10084 的映射）：**底栏矩形**（内容层坐标系）+ 弹簧形变。
     * lensCenter / touchPoint 先过 transform 仿射；lensSize = 底栏原始尺寸
     * （SDF 里除回 shapeScale，与 UI 层的非均匀缩放严格同步）。
     */
    fun updateFrame(
        frame: GlassMotionFrame,
        transform: GlassTransform,
        barLeft: Float,
        barTop: Float,
        barW: Float,
        barH: Float,
        radiusPx: Float,
    ) {
        val sh = liquid ?: return
        try {
            sh.setFloatUniform("lensSize", barW, barH)
            val r = radiusPx.coerceIn(0f, minOf(barW, barH) * 0.5f)   // m10087：夹到半短边
            sh.setFloatUniform("cornerRadii", r, r, r, r)
            val center = transform.map(barLeft + barW * 0.5f, barTop + barH * 0.5f)
            sh.setFloatUniform("lensCenter", center.x, center.y)
            sh.setFloatUniform("shapeScale", transform.scaleX, transform.scaleY)
            val touch = transform.map(barLeft + frame.touchX * barW, barTop + frame.touchY * barH)
            sh.setFloatUniform("touchPoint", touch.x, touch.y)
            // m10084：clamp(press, 0, 1)（负值是松手回弹的过冲，不给 dome 用）
            sh.setFloatUniform("touchStrength", frame.press.coerceIn(0f, 1f))
        } catch (t: Throwable) {
            // uniform 名对不上等不该发生；真出问题就只是没有形变
        }
    }

    /** 程序化灰度噪声贴图（替代 Salt 的 haze_noise 资源）：固定种子，确定性、可复现。 */
    private fun noiseTile(): BitmapShader {
        val n = 96
        val pixels = IntArray(n * n)
        val rnd = java.util.Random(7)
        for (i in pixels.indices) {
            val g = rnd.nextInt(256)
            pixels[i] = (0xFF shl 24) or (g shl 16) or (g shl 8) or g
        }
        val bmp = Bitmap.createBitmap(n, n, Bitmap.Config.ARGB_8888)
        bmp.setPixels(pixels, 0, n, 0, 0, n, n)
        return BitmapShader(bmp, Shader.TileMode.REPEAT, Shader.TileMode.REPEAT)
    }
}

// ================= 底栏（2026-10 全新重写，折射 App 自己滚动的内容） =================

/**
 * 底栏与折射引擎之间的共享状态。
 *
 * 结构反转（关键）：**折射 RenderEffect 挂在整个内容层上**，shader 只在底栏矩形内
 * 做折射、矩形外逐像素直通 —— 于是 content 输入就是**整页真实内容**：从底栏下方
 * 滚过的文字 / 卡片会被真实弯折、色散（这就是「折射自己的文字」，不需要任何
 * 内容捕获 API —— 那个 API 在本工具链的 Compose 构件里不存在，见 LiquidGlass 头注释）。
 * 底栏 UI 层只负责：上报矩形、弹簧手势、染色与页签 —— 折射不发生在它身上。
 */
class LiquidNavState {
    val motion = LiquidGlassMotion()

    /** 弹簧当前帧（引擎的 graphicsLayer 每帧读它更新 uniform，不触发重组）。 */
    var frame by mutableStateOf(GlassMotionFrame())

    /** 底栏在内容层（≈ 屏幕）里的矩形。UI 层上报，引擎消费。 */
    var barPos by mutableStateOf(Offset.Zero)
    var barSize by mutableStateOf(IntSize.Zero)
}

val LocalLiquidNav = staticCompositionLocalOf<LiquidNavState?> { null }

/**
 * 内容层的折射引擎：整页过 AGSL（底栏矩形内折射 / 色散 / 透镜高光 + softLight 噪点），
 * 矩形外直通。只在 HIGH 档 + 着色器可用时挂 effect；否则原样返回（中低档位 / Robolectric）。
 */
/**
 * 高斯模糊半径。2026-10-25 决定**去掉模糊**（纯折射 / 色散 / 高光的透明玻璃）：
 * 设 0 时 shader 走直通采样，blur3 的 25-tap 路径零成本保留着，想再开改这一个值。
 */
private val LiquidBlurRadius = 0.dp

fun Modifier.liquidGlassRefraction(
    state: LiquidNavState,
    quality: GlassQuality,
    glass: LiquidGlass,
): Modifier {
    if (quality != GlassQuality.HIGH || !glass.available) return this
    return graphicsLayer {
        val size = this.size
        val w = size.width
        val h = size.height
        if (w <= 0f || h <= 0f) return@graphicsLayer
        val bar = state.barSize
        if (bar.width <= 0 || bar.height <= 0) return@graphicsLayer
        val pos = state.barPos
        val transform = glassTransformOf(state.frame, bar.width.toFloat(), bar.height.toFloat())
        glass.updateFrame(
            state.frame, transform,
            pos.x, pos.y, bar.width.toFloat(), bar.height.toFloat(),
            RadiusR4.value * density,
        )
        renderEffect = glass.effect(w.toInt(), h.toInt(), LiquidBlurRadius.toPx())?.asComposeRenderEffect()
    }
}

/** 一个页签：图标 + 文字。 */
private data class NavTab(val label: String, val icon: ImageVector)

/** 底栏高度（Miuix 风格：比原版的 62dp 略高，图标呼吸感更好）。 */
private val LiquidNavHeight = 66.dp

/**
 * 液态玻璃底部导航的 **UI 层**（全新重写，不基于原版 NavBar / GlassSurface）。
 *
 * 折射发生在 [liquidGlassRefraction]（挂在 App 的内容层上）；本组件只做：
 * - 上报自己的矩形给引擎（`LocalLiquidNav`）；
 * - Salt Player 的弹簧手势（按压 / 拖拽液滴形变 / 轻点回弹 —— 见文件头）；
 * - 玻璃的「存在感」：薄白染色 + squircle 亮边；页签 = Miuix 风格图标 + 小字，
 *   选中变主色，无滑动指示块；状态页签带模块健康徽标（8dp 圆点）；
 * - 降级（API<33 / Robolectric / 编译失败）：莫奈扁卡底，页签行为不变。
 */
@Composable
fun LiquidNavBar(
    tab: Int,
    modifier: Modifier = Modifier,
    onTab: (Int) -> Unit,
) {
    val palette = LocalPalette.current
    val quality = LocalGlassQuality.current
    val nav = LocalLiquidNav.current
    val tabs = remember {
        listOf(
            NavTab("状态", NavIconStatus),
            NavTab("军师", NavIconMentor),
            NavTab("角色", NavIconRoles),
            NavTab("试一试", NavIconTrial),
            NavTab("设置", NavIconSettings),
        )
    }

    // 每帧弹簧积分（settle 后不写 frame）；只在引擎可用时驱动
    val active = nav != null && quality == GlassQuality.HIGH
    if (active && nav != null) {
        LaunchedEffect(nav) {
            var last = 0L
            while (true) {
                val now = withFrameNanos { it }
                if (!nav.motion.settled()) {
                    val dt = if (last == 0L) 0f else ((now - last) / 1_000_000_000f).coerceIn(0f, 0.05f)
                    nav.frame = nav.motion.advance(dt)
                }
                last = now
            }
        }
    }

    Box(
        modifier
            .onGloballyPositioned {
                if (nav != null) {
                    nav.barPos = it.positionInRoot()
                    nav.barSize = it.size
                }
            }
            // 玻璃上的 UI 应用同一个 transform（kh1:420-435）：缩放锚在触点、
            // 非均匀缩放（液滴感）、整体被拖走一点 —— 和 shader 的 shapeScale 严格同步
            .graphicsLayer {
                if (nav == null || nav.barSize.width <= 0) return@graphicsLayer
                val w = nav.barSize.width.toFloat()
                val h = nav.barSize.height.toFloat()
                val t = glassTransformOf(nav.frame, w, h)
                transformOrigin = TransformOrigin(
                    pivotFractionX = (t.pivotX / w).coerceIn(0f, 1f),
                    pivotFractionY = (t.pivotY / h).coerceIn(0f, 1f),
                )
                scaleX = t.scaleX
                scaleY = t.scaleY
                translationX = t.translationX
                translationY = t.translationY
            }
            .clip(RoundedCornerShape(RadiusR4))
            .pointerInput(active) {
                if (!active) return@pointerInput
                awaitEachGesture {
                    val state = nav ?: return@awaitEachGesture
                    val down = awaitFirstDown(requireUnconsumed = false, pass = PointerEventPass.Initial)
                    val size = this.size
                    val downPos = down.position
                    val downFx = (downPos.x / size.width).coerceIn(0f, 1f)
                    val downFy = (downPos.y / size.height).coerceIn(0f, 1f)
                    state.motion.down(downFx, downFy)
                    var dragged = false
                    var released = false
                    while (!released) {
                        val event = awaitPointerEvent(PointerEventPass.Initial)
                        val change = event.changes.firstOrNull() ?: break
                        if (change.position.isSpecified) {
                            val dx = (change.position.x - downPos.x) / size.width
                            val dy = (change.position.y - downPos.y) / size.height
                            if (abs(dx) > 0.02f || abs(dy) > 0.02f) dragged = true
                            if (dragged) {
                                state.motion.move(
                                    fx = (change.position.x / size.width).coerceIn(0f, 1f),
                                    fy = (change.position.y / size.height).coerceIn(0f, 1f),
                                    dragX = dx,
                                    dragY = dy,
                                )
                            } else {
                                state.motion.move(downFx, downFy, 0f, 0f)
                            }
                        }
                        if (event.changes.none { it.pressed }) released = true
                    }
                    // 轻点（没拖 + 按得浅）会多弹一下 —— vq1.m7901 的 velocity += 7
                    state.motion.release(dragged)
                }
            },
    ) {
        if (active) {
            // 玻璃的「存在感」底：薄白染色（折射在内容层引擎里发生，这里不画内容）
            Spacer(
                Modifier.matchParentSize().background(
                    Brush.verticalGradient(
                        listOf(
                            palette.glassTint.copy(alpha = if (palette.dark) 0.06f else 0.12f),
                            palette.glassTint.copy(alpha = if (palette.dark) 0.03f else 0.06f),
                        ),
                    ),
                ),
            )
            // squircle 亮边：浅色下透镜高光的 screen blend 不可见，轮廓感靠这一圈
            Spacer(
                Modifier
                    .matchParentSize()
                    .squircleBorder(1.dp, Color.White.copy(alpha = if (palette.dark) 0.28f else 0.55f), RadiusR4, 1f),
            )
        } else {
            // 降级：莫奈扁卡底 + 中性描边
            Spacer(Modifier.matchParentSize().squircleBackground(palette.cardBg, RadiusR4, 1f))
            Spacer(
                Modifier
                    .matchParentSize()
                    .squircleBorder(1.dp, if (palette.dark) Color(0x24FFFFFF) else Color(0x0F000000), RadiusR4, 1f),
            )
        }
        Row(
            Modifier
                .fillMaxWidth()
                .height(LiquidNavHeight),
            horizontalArrangement = Arrangement.SpaceEvenly,
            verticalAlignment = Alignment.CenterVertically,
        ) {
            tabs.forEachIndexed { index, t ->
                NavTabItem(
                    tab = t,
                    selected = index == tab,
                    modifier = Modifier.weight(1f),
                    onClick = { onTab(index) },
                )
            }
        }
    }
}

/** 单个页签：Miuix 风格（线性图标 + 小字、选中变主色），带轻微缩放过渡。
 *  模块健康徽标（绿点）已按需求移除 —— 状态详情在「状态」页里看。 */
@Composable
private fun NavTabItem(
    tab: NavTab,
    selected: Boolean,
    modifier: Modifier = Modifier,
    onClick: () -> Unit,
) {
    val palette = LocalPalette.current
    val haptics = LocalHapticFeedback.current
    // 0 → 1 的选中进度：图标缩放 / 变色 / 字重跟着走，切换有连续感
    val p by animateFloatAsState(if (selected) 1f else 0f, tween(240))
    val tint = lerp(palette.sub, palette.primary, p)
    val interaction = remember { MutableInteractionSource() }

    Column(
        modifier
            .clip(RoundedCornerShape(RadiusR3))
            // 自带选中态了，不要涟漪 —— 否则会闪出和玻璃不搭的方块
            .clickable(interactionSource = interaction, indication = null) {
                if (!selected) haptics.performHapticFeedback(HapticFeedbackType.LongPress)
                onClick()
            }
            .graphicsLayer {
                val scale = 0.94f + 0.06f * p
                scaleX = scale
                scaleY = scale
                translationY = -1.5f * p
            }
            .padding(horizontal = 4.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.Center,
    ) {
        Icon(
            tab.icon,
            contentDescription = tab.label,
            tint = tint,
            modifier = Modifier.size(22.dp + 2.dp * p),
        )
        Spacer(Modifier.height(3.dp))
        Text(
            tab.label,
            fontSize = 11.sp,
            color = tint,
            fontWeight = if (selected) FontWeight.Medium else FontWeight.Normal,
        )
    }
}
