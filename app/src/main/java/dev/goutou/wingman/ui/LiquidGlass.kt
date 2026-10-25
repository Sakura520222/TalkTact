package dev.goutou.wingman.ui

import android.graphics.BlendMode
import android.graphics.RenderEffect
import android.graphics.RuntimeShader
import android.graphics.Shader
import android.os.Build
import android.util.Log
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.tween
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.gestures.awaitEachGesture
import androidx.compose.foundation.gestures.awaitFirstDown
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.selection.selectable
import androidx.compose.foundation.selection.selectableGroup
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.runtime.withFrameNanos
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.draw.drawWithCache
import androidx.compose.ui.draw.shadow
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.geometry.isSpecified
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.TransformOrigin
import androidx.compose.ui.graphics.lerp
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.graphics.asComposeRenderEffect
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.hapticfeedback.HapticFeedbackType
import androidx.compose.ui.input.pointer.PointerEventPass
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.onGloballyPositioned
import androidx.compose.ui.layout.positionInRoot
import androidx.compose.ui.platform.LocalHapticFeedback
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.unit.IntSize
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import top.yukonga.miuix.kmp.squircle.squircleBackground
import top.yukonga.miuix.kmp.squircle.squircleBorder
import androidx.compose.runtime.staticCompositionLocalOf
import kotlin.math.abs
import kotlin.math.cos
import kotlin.math.exp
import kotlin.math.sin
import kotlin.math.sqrt

/**
 * 底栏液态玻璃：保留既有透镜折射、触点 dome 和欠阻尼弹簧。
 * 清晰背景先折射为透镜，再向中心渐入柔焦；边缘始终采样清晰背景，导航前景不参与模糊。
 * API 33 以下或着色器不可用时使用普通卡片，低档不启用玻璃与阴影。
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
uniform float frostedPass;
uniform float frostWidthPx;
uniform shader content;

const float SMOOTH_EDGE_PX = 1.5;
const float SEAM_BLEND_PX = 8.0;   // diagonal blend half-width (px); larger = softer interior crease

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

    // Transparent outside the lens: the separate original-page branch stays sharp.
    if (sdf > SMOOTH_EDGE_PX) {
        return half4(0.0);
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

    // 两路输入分别是清晰原页与系统模糊原页，均使用同一个透镜形变。
    half4 pixel = content.eval(sampleXY);
    if (dispersion > 0.0) {
        float2 normP = p / halfDim;
        float2 shift = dispersion * normP * normP * normP * min(halfDim.x, halfDim.y) * 0.1;
        pixel.r = content.eval(sampleXY - shift).r;
        pixel.b = content.eval(sampleXY + shift).b;
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
    // 柔焦从透镜边缘向内渐入，保留最能看出弯折的清晰倒角带。
    if (frostedPass > 0.5) {
        alpha *= smoothstep(2.0, max(frostWidthPx, 3.0), -sdf) * 0.88;
    }
    return pixel * half(alpha);
}
"""

private const val TAG = "WingmanUi"

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
    fun map(x: Float, y: Float, origin: Offset = Offset.Zero): Offset = Offset(
        (x - pivotX) * scaleX + pivotX + translationX + origin.x,
        (y - pivotY) * scaleY + pivotY + translationY + origin.y,
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
 * 透镜 RuntimeShader 与系统模糊合成链的缓存。
 * RuntimeShader 的参数变化后须重新创建运行时 effect；原 effect 持有参数快照。
 * 静止时复用整个合成链，手势帧只更新透镜快照，系统模糊 effect 单独复用。
 */
class LiquidGlass {
    private fun lensShader(): RuntimeShader? =
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            try {
                RuntimeShader(LIQUID_GLASS_AGSL)
            } catch (t: Throwable) {
                Log.w(TAG, "liquid glass AGSL compile failed", t)
                null
            }
        } else null

    private val liquid = lensShader()
    private val frosted = lensShader()

    val available: Boolean get() = liquid != null && frosted != null

    // 只缓存当前尺寸与半径，旋转或密度变化后替换，避免无限累积离屏 effect。
    private var cachedSize = IntSize.Zero
    private var cachedBlur = Float.NaN
    private var cachedEffect: RenderEffect? = null
    private data class LensFrame(
        val motion: GlassMotionFrame,
        val transform: GlassTransform,
        val origin: Offset,
        val size: Size,
        val radius: Float,
    )
    private var currentFrame: LensFrame? = null
    private var cachedFrame: LensFrame? = null
    private var blurEffect: RenderEffect? = null
    private var blurEffectRadius = Float.NaN

    /** 清晰折射为底，中心渐入高斯柔焦；最后覆盖回清晰原页，蒙版外保持原样。 */
    fun effect(w: Int, h: Int, blurPx: Float): RenderEffect? {
        val sh = liquid ?: return null
        val frost = frosted ?: return null
        if (w <= 0 || h <= 0) return null
        val blur = blurPx.coerceAtLeast(0f)
        if (cachedSize == IntSize(w, h) && cachedBlur == blur && cachedFrame == currentFrame) return cachedEffect
        cachedSize = IntSize(w, h)
        cachedBlur = blur
        cachedFrame = currentFrame
        cachedEffect = try {
            for (shader in listOf(sh, frost)) {
                shader.setFloatUniform("resolution", w.toFloat(), h.toFloat())
                shader.setFloatUniform("refraction", 0.85f)
                shader.setFloatUniform("curve", 0.7f)
                shader.setFloatUniform("dispersion", 0.24f)
                shader.setFloatUniform("saturation", 1.08f)
                shader.setFloatUniform("contrast", 1f)
                shader.setFloatUniform("tint", 0f, 0f, 0f, 0f)
                shader.setFloatUniform("edge", 0.2f)
                shader.setFloatUniform("lightDir", -1f, -1f)
                shader.setFloatUniform("specStrength", 0.38f)
                shader.setFloatUniform("specPower", 3f)
                shader.setFloatUniform("specRimMix", 0.65f)
                shader.setFloatUniform("specWidthPx", 12f)
                shader.setFloatUniform("specLightZ", 0.55f)
                shader.setFloatUniform("specDomeFrac", 1.15f)
                shader.setFloatUniform("specBodyPower", 2.5f)
                shader.setFloatUniform("specBodyGain", 0.4f)
                shader.setFloatUniform("specFocalK", 0.55f)
                shader.setFloatUniform("specPoolFrac", 0.7f)
                shader.setFloatUniform("specPoolGain", 0.8f)
                shader.setFloatUniform("frostWidthPx", blur * 1.15f)
            }
            sh.setFloatUniform("frostedPass", 0f)
            frost.setFloatUniform("frostedPass", 1f)
            val lens = RenderEffect.createRuntimeShaderEffect(sh, "content")
            val frostedLens = if (blur > 0f) {
                if (blurEffectRadius != blur) {
                    blurEffect = RenderEffect.createBlurEffect(blur, blur, Shader.TileMode.CLAMP)
                    blurEffectRadius = blur
                }
                RenderEffect.createBlendModeEffect(
                    lens,
                    RenderEffect.createChainEffect(
                        RenderEffect.createRuntimeShaderEffect(frost, "content"),
                        requireNotNull(blurEffect),
                    ),
                    BlendMode.SRC_OVER,
                )
            } else lens
            RenderEffect.createBlendModeEffect(
                RenderEffect.createOffsetEffect(0f, 0f),
                frostedLens,
                BlendMode.SRC_OVER,
            )
        } catch (t: Throwable) {
            Log.w(TAG, "liquid glass effect build failed", t)
            null
        }
        return cachedEffect
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
        if (!available) return
        val next = LensFrame(frame, transform, Offset(barLeft, barTop), Size(barW, barH), radiusPx)
        if (next == currentFrame) return
        try {
            for (sh in listOfNotNull(liquid, frosted)) {
                sh.setFloatUniform("lensSize", barW, barH)
                val r = radiusPx.coerceIn(0f, minOf(barW, barH) * 0.5f)   // m10087：夹到半短边
                sh.setFloatUniform("cornerRadii", r, r, r, r)
                // pivot 是底栏局部坐标；先做局部形变，再平移到内容层，不能把屏幕原点一起缩放。
                val origin = Offset(barLeft, barTop)
                val center = transform.map(barW * 0.5f, barH * 0.5f, origin)
                sh.setFloatUniform("lensCenter", center.x, center.y)
                sh.setFloatUniform("shapeScale", transform.scaleX, transform.scaleY)
                val touch = transform.map(frame.touchX * barW, frame.touchY * barH, origin)
                sh.setFloatUniform("touchPoint", touch.x, touch.y)
                // m10084：clamp(press, 0, 1)（负值是松手回弹的过冲，不给 dome 用）
                sh.setFloatUniform("touchStrength", frame.press.coerceIn(0f, 1f))
            }
            currentFrame = next
        } catch (t: Throwable) {
            // uniform 名对不上等不该发生；真出问题就只是没有形变
        }
    }
}

// ================= 底栏（2026-10 全新重写，折射 App 自己滚动的内容） =================

/**
 * 底栏与折射引擎之间的共享状态。
 *
 * 结构反转（关键）：**折射 RenderEffect 挂在整个内容层上**，shader 只在底栏矩形内
 * 做折射、矩形外逐像素直通 —— 于是 content 输入就是**整页真实内容**：从底栏下方
 * 滚过的文字 / 卡片会被真实弯折、色散（这就是「折射自己的文字」，不需要任何
 * 内容捕获 API）。
 * 底栏 UI 层只负责：上报矩形、弹簧手势、染色与页签 —— 折射不发生在它身上。
 */
class LiquidNavState(val refractionAvailable: Boolean = false) {
    val motion = LiquidGlassMotion()

    /** 弹簧当前帧（引擎的 graphicsLayer 每帧读它更新 uniform，不触发重组）。 */
    var frame by mutableStateOf(GlassMotionFrame())

    /** 底栏在内容层（≈ 屏幕）里的矩形。UI 层上报，引擎消费。 */
    var barPos by mutableStateOf(Offset.Zero)
    var barSize by mutableStateOf(IntSize.Zero)

    /** 只在新手势时唤醒弹簧；收敛后不再向 Choreographer 要帧。 */
    var gestureId by mutableIntStateOf(0)
}

val LocalLiquidNav = staticCompositionLocalOf<LiquidNavState?> { null }

/**
 * 内容层的折射引擎：清晰折射 + 中心柔焦 → 与清晰原页合成。
 * 模糊只在底栏内可见，图标和文字在独立前景层，始终保持清晰。
 * 系统高斯模糊半径；不用稀疏采样网格，避免文字透底和重影。
 */
private val LiquidBlurRadius = 12.dp

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
 * - 柔焦底色、柔和高光与渐变亮边；页签保留既有图标，选中变主色；
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
    val active = nav?.refractionAvailable == true && quality == GlassQuality.HIGH
    if (active) {
        LaunchedEffect(nav, nav.gestureId) {
            var last = 0L
            while (!nav.motion.settled()) {
                val now = withFrameNanos { it }
                val dt = if (last == 0L) 0f else ((now - last) / 1_000_000_000f).coerceIn(0f, 0.05f)
                nav.frame = nav.motion.advance(dt)
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
                if (!active || nav.barSize.width <= 0) return@graphicsLayer
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
            .then(
                if (quality != GlassQuality.LOW) Modifier.shadow(
                    elevation = 12.dp,
                    shape = RoundedCornerShape(RadiusR4),
                    clip = false,
                    ambientColor = Color.Black.copy(alpha = 0.06f),
                    spotColor = Color.Black.copy(alpha = 0.10f),
                ) else Modifier,
            )
            .clip(RoundedCornerShape(RadiusR4))
            .pointerInput(active) {
                if (!active) return@pointerInput
                awaitEachGesture {
                    val state = nav
                    val down = awaitFirstDown(requireUnconsumed = false, pass = PointerEventPass.Initial)
                    val size = this.size
                    val downPos = down.position
                    val downFx = (downPos.x / size.width).coerceIn(0f, 1f)
                    val downFy = (downPos.y / size.height).coerceIn(0f, 1f)
                    state.motion.down(downFx, downFy)
                    state.gestureId++
                    var dragged = false
                    var released = false
                    try {
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
                    } finally {
                        // 系统取消手势 / 离开页面也必须松手，不能让玻璃永久停在按压态。
                        state.motion.release(dragged)
                    }
                }
            },
    ) {
        if (active) {
            // 染色和反光独立于背后的模糊，不会把导航图标一起模糊掉。
            Spacer(
                Modifier.matchParentSize().background(
                    Brush.verticalGradient(
                        listOf(
                            (if (palette.dark) palette.cardBg else Color.White).copy(alpha = if (palette.dark) 0.32f else 0.18f),
                            (if (palette.dark) palette.cardBg else Color.White).copy(alpha = if (palette.dark) 0.20f else 0.08f),
                        ),
                    ),
                ),
            )
            Spacer(
                Modifier.matchParentSize().drawWithCache {
                    // 宽而柔的反光光斑，使用连续渐变，不额外创建模糊离屏层。
                    val topLight = Brush.radialGradient(
                        listOf(Color.White.copy(alpha = if (palette.dark) 0.15f else 0.30f), Color.Transparent),
                        center = Offset(size.width * 0.22f, -size.height * 0.65f),
                        radius = size.width * 0.66f,
                    )
                    val bottomLight = Brush.radialGradient(
                        listOf(Color.White.copy(alpha = if (palette.dark) 0.10f else 0.20f), Color.Transparent),
                        center = Offset(size.width * 0.88f, size.height * 1.7f),
                        radius = size.width * 0.46f,
                    )
                    onDrawBehind {
                        drawRect(topLight)
                        drawRect(bottomLight)
                    }
                },
            )
            Spacer(
                Modifier
                    .matchParentSize()
                    .border(
                        0.8.dp,
                        Brush.linearGradient(
                            listOf(
                                Color.White.copy(alpha = if (palette.dark) 0.44f else 0.88f),
                                Color.White.copy(alpha = 0.10f),
                                palette.primary.copy(alpha = if (palette.dark) 0.16f else 0.12f),
                                Color.White.copy(alpha = if (palette.dark) 0.28f else 0.64f),
                            ),
                        ),
                        RoundedCornerShape(RadiusR4),
                    ),
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
                .height(LiquidNavHeight)
                .padding(horizontal = 6.dp)
                .selectableGroup(),
            horizontalArrangement = Arrangement.SpaceEvenly,
            verticalAlignment = Alignment.CenterVertically,
        ) {
            tabs.forEachIndexed { index, t ->
                NavTabItem(
                    tab = t,
                    selected = index == tab,
                    modifier = Modifier.weight(1f).fillMaxHeight(),
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
    val tint = lerp(lerp(palette.sub, palette.text, 0.28f), palette.primary, p)
    val interaction = remember { MutableInteractionSource() }

    Column(
        modifier
            .clip(RoundedCornerShape(RadiusR3))
            // 自带选中态了，不要涟漪 —— 否则会闪出和玻璃不搭的方块
            .selectable(selected = selected, role = Role.Tab, interactionSource = interaction, indication = null) {
                if (!selected) haptics.performHapticFeedback(HapticFeedbackType.LongPress)
                onClick()
            }
            .drawBehind {
                drawRoundRect(
                    color = palette.primary.copy(alpha = (if (palette.dark) 0.14f else 0.08f) * p),
                    topLeft = Offset(3.dp.toPx(), 7.dp.toPx()),
                    size = Size((size.width - 6.dp.toPx()).coerceAtLeast(0f), (size.height - 14.dp.toPx()).coerceAtLeast(0f)),
                    cornerRadius = CornerRadius(22.dp.toPx()),
                )
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
            contentDescription = null,
            tint = tint,
            modifier = Modifier.size(22.dp + 2.dp * p),
        )
        Spacer(Modifier.height(3.dp))
        Text(
            tab.label,
            fontSize = 12.sp,
            color = tint,
            fontWeight = if (selected) FontWeight.Medium else FontWeight.Normal,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
        )
    }
}
