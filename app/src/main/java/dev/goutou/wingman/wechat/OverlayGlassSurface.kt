package dev.goutou.wingman.wechat

import android.app.ActivityManager
import android.content.Context
import android.content.res.Configuration
import android.graphics.BlendMode
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.LinearGradient
import android.graphics.Outline
import android.graphics.Paint
import android.graphics.RadialGradient
import android.graphics.RectF
import android.graphics.Region
import android.graphics.RegionIterator
import android.graphics.RenderEffect
import android.graphics.RenderNode
import android.graphics.RuntimeShader
import android.graphics.Shader
import android.os.Build
import android.os.SystemClock
import android.util.Log
import android.view.MotionEvent
import android.view.View
import android.view.ViewGroup
import android.view.ViewOutlineProvider
import android.view.ViewTreeObserver
import android.widget.LinearLayout
import java.lang.ref.WeakReference
import kotlin.math.min
import kotlin.math.roundToInt

/**
 * 注入侧整卡玻璃。只记录同窗口中与浮窗互为兄弟的 content 视图，绝不记录 decor 本身。
 * 宿主内容只留在 GPU display list，既不读回 Bitmap，也不触碰窗口外的画面。
 * RenderEffect 仅挂在背景 RenderNode；正常的按钮、文本和滚动子视图保持清晰。
 */
internal class OverlayGlassSurface(context: Context) : LinearLayout(context) {
    private val density = resources.displayMetrics.density
    private val lowRam = runCatching {
        context.getSystemService(ActivityManager::class.java)?.isLowRamDevice == true
    }.getOrDefault(false)
    private var boundDecor = WeakReference<ViewGroup>(null)
    private var source = WeakReference<View>(null)
    private var observedTree: ViewTreeObserver? = null
    private var backgroundNode: RenderNode? = null
    private var effectFactory: LensEffect? = null
    private var effectKey: EffectKey? = null
    private var cachedEffect: RenderEffect? = null
    private var cachedBlur: RenderEffect? = null
    private var cachedBlurRadius = Float.NaN
    private var captureAllowed = false
    private var paused = false
    private var released = false
    private var captureFailed = false
    private var captureFailures = 0
    private var retryPosted = false
    private var lastFailureClass = "none"
    private var lastFailureStage = "none"
    private var effectMode = "fallback"
    private var lensUnavailable = false
    private var lastDebugState = ""
    private var lastHardwareDrawIncludedBackdrop = false
    private var lastCanvasHardware: Boolean? = null
    private var requestedMode = "auto"
    private var recording = false
    private var lastRecordAt = -1L
    private var quality = Quality.HIGH
    private var materialAlpha = 0.92f
    private var blurDp = 12f
    private var touchX = 0.5f
    private var touchY = 0.35f
    private var pressed = false
    private var ownsTouch = false
    private var dark = isNight()
    private val ownLocation = IntArray(2)
    private val sourceLocation = IntArray(2)
    private val bounds = RectF()
    private val strokeBounds = RectF()
    private val fillPaint = Paint(Paint.ANTI_ALIAS_FLAG)
    private val highlightPaint = Paint(Paint.ANTI_ALIAS_FLAG)
    private val strokePaint = Paint(Paint.ANTI_ALIAS_FLAG).apply { style = Paint.Style.STROKE }
    private var fillShader: Shader? = null
    private var glowShader: Shader? = null
    private var edgeShader: Shader? = null

    /** 是采样许可而非“GPU 已成功采样”；可用于复核白名单、暂停和低档的门禁。 */
    internal val backdropCaptureEnabled: Boolean
        get() = captureAllowed && !paused && !released && quality != Quality.LOW

    internal val hasRetainedBackdrop: Boolean
        get() = backgroundNode?.hasDisplayList() == true

    /** 仅结构和渲染状态，可进入用户主动生成的诊断；不含 View 文字或异常消息。 */
    internal fun reportState(): String {
        val content = source.get()
        val decor = boundDecor.get()
        return "mode=$requestedMode quality=$quality lowRam=$lowRam allowed=$captureAllowed paused=$paused released=$released " +
            "shown=$isShown attached=$isAttachedToWindow windowVisible=${windowVisibility == VISIBLE} " +
            "size=${width}x$height hardware=$isHardwareAccelerated canvasHardware=$lastCanvasHardware layerType=$layerType listener=${observedTree?.isAlive == true} " +
            "source=${metadataClass(content)} sourceSize=${content?.width ?: 0}x${content?.height ?: 0} " +
            "sourceChildren=${(content as? ViewGroup)?.childCount ?: 0} sourceShown=${content?.isShown == true} " +
            "sourceHardware=${content?.isHardwareAccelerated == true} sourceLayerType=${content?.layerType ?: -1} sourceOpaque=${content?.isOpaque == true} " +
            "sourceBackground=${metadataClass(content?.background)} windowBackground=${metadataClass(decor?.background)} " +
            "sameRoot=${content != null && content.rootView === rootView} sameWindow=${content != null && windowToken != null && content.windowToken == windowToken} " +
            "node=$hasRetainedBackdrop drawn=$lastHardwareDrawIncludedBackdrop effect=$effectMode " +
            "failed=$captureFailed failures=$captureFailures retry=$retryPosted lastFailure=$lastFailureStage/$lastFailureClass"
    }

    private val retryCapture = Runnable {
        retryPosted = false
        if (backdropCaptureEnabled && isAttachedToWindow && isShown && windowVisibility == VISIBLE) {
            captureFailed = false
            syncObserver()
            invalidate()
        }
        logState("retry")
    }

    private val preDraw = ViewTreeObserver.OnPreDrawListener {
        // 不 invalidate / post 下一帧：只有宿主本来就要绘制时才更新。
        if (canRecord()) recordBackdrop()
        true
    }

    init {
        orientation = VERTICAL
        setWillNotDraw(false)
        isClickable = true
        clipToOutline = true
        outlineProvider = object : ViewOutlineProvider() {
            override fun getOutline(view: View, outline: Outline) {
                outline.setRoundRect(0, 0, view.width, view.height, cornerRadius())
                outline.alpha = if (quality == Quality.LOW) 0f else 0.18f
            }
        }
        updateStyle("auto", materialAlpha, blurDp)
    }

    fun bindHost(decor: ViewGroup) {
        val nextSource = resolveOverlayBackdropSource(decor, this)
        if (boundDecor.get() !== decor || source.get() !== nextSource) {
            removeObserver()
            discardBackdrop()
            boundDecor = WeakReference(decor)
            source = WeakReference(nextSource)
            resetCaptureFailure()
        }
        released = false
        syncObserver()
        logState("bind")
    }

    fun updateStyle(mode: String, alpha: Float, blurDp: Float) {
        requestedMode = mode.takeIf { it == "high" || it == "low" } ?: "auto"
        val nextQuality = when {
            mode == "low" -> Quality.LOW
            mode != "high" && lowRam -> Quality.LOW
            Build.VERSION.SDK_INT < 33 -> Quality.MEDIUM
            else -> Quality.HIGH
        }
        val nextAlpha = if (alpha.isFinite()) alpha.coerceIn(0.3f, 1f) else 0.92f
        val nextBlur = if (blurDp.isFinite()) blurDp.coerceIn(8f, 24f) else 12f
        val nextDark = isNight()
        if (nextQuality == quality && nextAlpha == materialAlpha && nextBlur == this.blurDp && nextDark == dark) return
        quality = nextQuality
        materialAlpha = nextAlpha
        this.blurDp = nextBlur
        dark = nextDark
        resetCaptureFailure()
        lensUnavailable = false
        discardBackdrop()
        rebuildPaints()
        elevation = if (quality == Quality.LOW) 0f else 7f * density
        invalidateOutline()
        syncObserver()
        invalidate()
        logState("style")
    }

    /** 调用方必须在会话身份 / 白名单确认后放行；切换上下文时先设 false 清掉旧背景。 */
    fun setBackdropAllowed(allowed: Boolean) {
        if (captureAllowed == allowed) return
        captureAllowed = allowed
        if (!allowed) {
            resetCaptureFailure()
            removeObserver()
            discardBackdrop()
        } else {
            syncObserver()
        }
        invalidate()
        logState("permission")
    }

    fun pauseBackdrop() {
        paused = true
        captureAllowed = false
        pressed = false
        ownsTouch = false
        resetCaptureFailure()
        removeObserver()
        discardBackdrop()
        invalidate()
        logState("pause")
    }

    /** 恢复生命周期不会恢复采样许可，下一轮会话验证应再次显式放行。 */
    fun resumeBackdrop() {
        paused = false
        resetCaptureFailure()
        syncObserver()
        invalidate()
        logState("resume")
    }

    fun releaseBackdrop() {
        pauseBackdrop()
        released = true
        boundDecor.clear()
        source.clear()
        effectFactory = null
        cachedBlur = null
        cachedBlurRadius = Float.NaN
        logState("release")
    }

    override fun onAttachedToWindow() {
        super.onAttachedToWindow()
        syncObserver()
    }

    override fun onDetachedFromWindow() {
        pauseBackdrop()
        super.onDetachedFromWindow()
    }

    override fun onVisibilityChanged(changedView: View, visibility: Int) {
        super.onVisibilityChanged(changedView, visibility)
        if (visibility == VISIBLE) {
            syncObserver()
        } else {
            cancelRetry()
            removeObserver()
            discardBackdrop()
        }
    }

    override fun onSizeChanged(w: Int, h: Int, oldw: Int, oldh: Int) {
        super.onSizeChanged(w, h, oldw, oldh)
        discardBackdrop()
        bounds.set(0f, 0f, w.toFloat(), h.toFloat())
        rebuildPaints()
        invalidateOutline()
    }

    override fun onDraw(canvas: Canvas) {
        super.onDraw(canvas)
        if (width <= 0 || height <= 0) return
        lastCanvasHardware = canvas.isHardwareAccelerated
        val currentNight = isNight()
        if (currentNight != dark) {
            dark = currentNight
            rebuildPaints()
        }
        val r = cornerRadius()
        // content 根可能有透明区域；先铺安全底色，避免这些区域露出未经柔焦的正文。
        fillPaint.shader = null
        fillPaint.color = if (dark) Color.rgb(30, 29, 36) else Color.rgb(246, 243, 250)
        canvas.drawRoundRect(bounds, r, r, fillPaint)
        var drewBackdrop = false
        if (canvas.isHardwareAccelerated && canRecord()) {
            if (!hasRetainedBackdrop) recordBackdrop(force = true)
            backgroundNode?.takeIf { it.hasDisplayList() }?.let { node ->
                try {
                    canvas.drawRenderNode(node)
                    drewBackdrop = true
                } catch (failure: Throwable) {
                    failCapture("draw-node", failure)
                }
            }
        }
        if (canvas.isHardwareAccelerated) {
            lastHardwareDrawIncludedBackdrop = drewBackdrop
            if (drewBackdrop) captureFailures = 0
        }
        logState("draw")
        // 无硬件画布 / 采样被拒绝时使用近实底；不会只剩透明背景而露出下面的正文。
        fillPaint.shader = if (drewBackdrop) fillShader else null
        if (!drewBackdrop) fillPaint.color = if (dark) Color.rgb(30, 29, 36) else Color.rgb(246, 243, 250)
        canvas.drawRoundRect(bounds, r, r, fillPaint)
        if (quality != Quality.LOW) {
            highlightPaint.shader = glowShader
            canvas.drawRoundRect(bounds, r, r, highlightPaint)
        }
        strokePaint.shader = edgeShader
        val inset = strokePaint.strokeWidth * 0.5f
        strokeBounds.set(inset, inset, width - inset, height - inset)
        canvas.drawRoundRect(strokeBounds, (r - inset).coerceAtLeast(0f), (r - inset).coerceAtLeast(0f), strokePaint)
    }

    /** 空白、padding 和圆角区域同样消费触摸；子按钮/长按/ScrollView 仍先由正常分发处理。 */
    override fun dispatchTouchEvent(event: MotionEvent): Boolean {
        if (event.actionMasked == MotionEvent.ACTION_DOWN) {
            ownsTouch = event.x >= 0f && event.y >= 0f && event.x < width && event.y < height
            if (!ownsTouch) return false
        }
        if (ownsTouch) updateTouch(event)
        val consumedHere = ownsTouch
        val handled = super.dispatchTouchEvent(event)
        if (event.actionMasked == MotionEvent.ACTION_UP || event.actionMasked == MotionEvent.ACTION_CANCEL) ownsTouch = false
        return handled || consumedHere
    }

    override fun onTouchEvent(event: MotionEvent): Boolean {
        super.onTouchEvent(event)
        return ownsTouch || (event.x >= 0f && event.y >= 0f && event.x < width && event.y < height)
    }

    override fun performClick(): Boolean = super.performClick()

    private fun updateTouch(event: MotionEvent) {
        touchX = (event.x / width.coerceAtLeast(1)).coerceIn(0f, 1f)
        touchY = (event.y / height.coerceAtLeast(1)).coerceIn(0f, 1f)
        pressed = event.actionMasked != MotionEvent.ACTION_UP && event.actionMasked != MotionEvent.ACTION_CANCEL
        effectKey = null
        if (hasRetainedBackdrop) updateEffect()
        invalidate()
    }

    private fun canRecord(): Boolean = backdropCaptureEnabled && !captureFailed && !recording &&
        isAttachedToWindow && isShown && windowVisibility == VISIBLE && width > 0 && height > 0 &&
        width <= 4096 && height <= 4096 && width.toLong() * height <= 2_500_000L

    private fun recordBackdrop(force: Boolean = false) {
        if (!canRecord() || !isHardwareAccelerated) {
            logState("record-gated")
            return
        }
        val now = SystemClock.uptimeMillis()
        if (!force && lastRecordAt >= 0 && now - lastRecordAt < 50L) return
        val decor = boundDecor.get() ?: run { logState("missing-decor"); return }
        // 宿主可复用 Activity 并藏起旧 content；“还挂在窗口上”不等于当前可见来源。
        val previousSource = source.get()
        val content = try {
            previousSource?.takeIf { currentOverlayBackdropSource(decor, this, it) }
                ?: resolveOverlayBackdropSource(decor, this)
        } catch (failure: Throwable) {
            failCapture("source-select", failure)
            return
        }
        if (content !== previousSource) {
            discardBackdrop()
            source = WeakReference(content)
            resetCaptureFailure()
            logState("source-changed")
            logSourceGeometry(content)
        }
        if (content == null) {
            logState("missing-source")
            return
        }
        if (!content.isShown || content.width <= 0 || content.height <= 0 || !content.isHardwareAccelerated || content.rootView !== rootView ||
            content.windowToken != windowToken || isWithin(content, this)) {
            discardBackdrop()
            logState("source-gated")
            return
        }
        recording = true
        val previouslyReady = hasRetainedBackdrop
        var stage = "node-create"
        try {
            getLocationInWindow(ownLocation)
            content.getLocationInWindow(sourceLocation)
            val node = backgroundNode ?: RenderNode("TalkTact.overlay.backdrop").also {
                it.setClipToBounds(true)
                backgroundNode = it
            }
            node.setPosition(0, 0, width, height)
            stage = "begin-record"
            val recordingCanvas = node.beginRecording(width, height)
            try {
                recordingCanvas.clipRect(0, 0, width, height)
                // 窗口背景常由 decor 而不是透明的 content 根绘制。只画 Drawable，绝不 decor.draw。
                stage = "window-background"
                decor.background?.let { background ->
                    val originalBounds = android.graphics.Rect(background.bounds)
                    val originalAlpha = background.alpha
                    val originalCallback = background.callback
                    val position = IntArray(2)
                    decor.getLocationInWindow(position)
                    val checkpoint = recordingCanvas.save()
                    try {
                        // 临时 bounds 修复不应通过 Drawable 回调制造新的宿主绘制循环。
                        background.callback = null
                        recordingCanvas.translate((position[0] - ownLocation[0]).toFloat(), (position[1] - ownLocation[1]).toFloat())
                        if (originalBounds.isEmpty) background.setBounds(0, 0, decor.width, decor.height)
                        background.draw(recordingCanvas)
                    } finally {
                        try {
                            if (background.bounds != originalBounds) background.bounds = originalBounds
                            if (background.alpha != originalAlpha) background.alpha = originalAlpha
                        } finally {
                            background.callback = originalCallback
                            recordingCanvas.restoreToCount(checkpoint)
                        }
                    }
                }
                stage = "source-draw"
                recordingCanvas.translate(
                    (sourceLocation[0] - ownLocation[0]).toFloat(),
                    (sourceLocation[1] - ownLocation[1]).toFloat(),
                )
                content.draw(recordingCanvas)
            } finally {
                try {
                    node.endRecording()
                } catch (failure: Throwable) {
                    stage = "end-record"
                    throw failure
                }
            }
            lastRecordAt = now
            stage = "effect"
            updateEffect()
            // 首次绘制若走过安全实底，其 View display list 中还没有背景 node 的 draw 命令。
            // 只在缺失 -> 就绪时使该 display list 失效一次，后续宿主帧复用 node 引用。
            if (!previouslyReady && node.hasDisplayList()) invalidate()
            logState("record-ready")
        } catch (failure: Throwable) {
            // 宿主的自绘 View、硬件资源或平台能力异常不能影响聊天应用。
            failCapture(stage, failure)
        } finally {
            recording = false
        }
    }

    private fun updateEffect() {
        val node = backgroundNode ?: return
        if (quality == Quality.LOW) {
            node.setRenderEffect(null)
            effectMode = "tint"
            return
        }
        val radius = cornerRadius()
        val blur = this.blurDp * density
        val key = EffectKey(width, height, radius, blur, touchX, touchY, pressed, quality)
        if (effectKey == key) return
        if (cachedBlurRadius != blur) {
            cachedBlur = RenderEffect.createBlurEffect(blur, blur, Shader.TileMode.CLAMP)
            cachedBlurRadius = blur
        }
        var lensEffect: RenderEffect? = null
        if (quality == Quality.HIGH && Build.VERSION.SDK_INT >= 33 && !lensUnavailable) {
            try {
                val lens = effectFactory ?: LensEffect().also { effectFactory = it }
                lensEffect = lens.effect(key, 12f * density, cachedBlur)
            } catch (failure: Throwable) {
                lensUnavailable = true
                lastFailureStage = "lens-effect"
                lastFailureClass = metadataClass(failure)
            }
        }
        cachedEffect = lensEffect ?: cachedBlur
        effectMode = if (lensEffect != null) "lens" else "blur"
        node.setRenderEffect(cachedEffect)
        effectKey = key
    }

    private fun syncObserver() {
        if (!backdropCaptureEnabled || !isAttachedToWindow || !isShown || captureFailed) {
            removeObserver()
            return
        }
        val tree = boundDecor.get()?.viewTreeObserver ?: return
        if (!tree.isAlive || observedTree === tree) return
        removeObserver()
        tree.addOnPreDrawListener(preDraw)
        observedTree = tree
    }

    private fun removeObserver() {
        observedTree?.takeIf { it.isAlive }?.removeOnPreDrawListener(preDraw)
        observedTree = null
    }

    private fun discardBackdrop() {
        backgroundNode?.let { node ->
            runCatching { node.setRenderEffect(null); node.discardDisplayList() }
        }
        effectKey = null
        cachedEffect = null
        effectMode = if (quality == Quality.LOW) "tint" else "fallback"
        lastHardwareDrawIncludedBackdrop = false
        lastRecordAt = -1L
    }

    private fun cancelRetry() {
        removeCallbacks(retryCapture)
        retryPosted = false
    }

    private fun resetCaptureFailure() {
        cancelRetry()
        captureFailed = false
        captureFailures = 0
    }

    private fun failCapture(stage: String, failure: Throwable) {
        lastFailureStage = stage
        lastFailureClass = metadataClass(failure)
        captureFailures++
        captureFailed = true
        removeObserver()
        discardBackdrop()
        cancelRetry()
        if (captureFailures < 3 && backdropCaptureEnabled && isAttachedToWindow && isShown) {
            retryPosted = true
            postDelayed(retryCapture, 500L)
        }
        logState("capture-failed")
    }

    private fun logState(stage: String) {
        val state = reportState()
        if (state == lastDebugState) return
        lastDebugState = state
        Log.d("TalkTactGlass", "$stage $state")
    }

    /** 仅在来源切换时记录有界几何信息，不读取控件文字、描述或 tag。 */
    private fun logSourceGeometry(content: View?) {
        if (content == null) return
        var remaining = 24
        fun visit(view: View, depth: Int) {
            if (remaining-- <= 0 || depth > 4) return
            val location = IntArray(2)
            view.getLocationInWindow(location)
            Log.d("TalkTactGlass", "geometry depth=$depth class=${metadataClass(view)} " +
                "size=${view.width}x${view.height} location=${location[0]},${location[1]} " +
                "scroll=${view.scrollX},${view.scrollY} visibility=${view.visibility} alpha=${view.alpha} " +
                "children=${(view as? ViewGroup)?.childCount ?: 0} layer=${view.layerType}")
            if (view is ViewGroup) for (i in 0 until minOf(view.childCount, 5)) visit(view.getChildAt(i), depth + 1)
        }
        runCatching { visit(content, 0) }
    }

    private fun cornerRadius(): Float = min(24f * density, min(width, height).coerceAtLeast(0) * 0.5f)

    private fun isNight() = (resources.configuration.uiMode and Configuration.UI_MODE_NIGHT_MASK) == Configuration.UI_MODE_NIGHT_YES

    private fun rebuildPaints() {
        if (width <= 0 || height <= 0) return
        val strength = 0.65f + materialAlpha * 0.35f
        val tint = if (dark) Color.rgb(29, 28, 35) else Color.rgb(247, 244, 251)
        fillShader = LinearGradient(0f, 0f, width.toFloat(), height.toFloat(),
            withAlpha(tint, (if (dark) 0.38f else 0.28f) * strength),
            withAlpha(tint, (if (dark) 0.27f else 0.18f) * strength), Shader.TileMode.CLAMP)
        glowShader = RadialGradient(width * 0.18f, -height * 0.22f, width.coerceAtLeast(height) * 0.9f,
            withAlpha(Color.WHITE, if (dark) 0.12f else 0.30f), Color.TRANSPARENT, Shader.TileMode.CLAMP)
        val accent = runCatching { context.getColor(if (dark) android.R.color.system_accent1_200 else android.R.color.system_accent1_600) }
            .getOrDefault(if (dark) Color.rgb(207, 193, 249) else Color.rgb(96, 87, 138))
        edgeShader = LinearGradient(0f, 0f, width.toFloat(), height.toFloat(),
            intArrayOf(withAlpha(Color.WHITE, if (dark) 0.38f else 0.84f), withAlpha(Color.WHITE, 0.09f), withAlpha(accent, 0.17f), withAlpha(Color.WHITE, if (dark) 0.20f else 0.58f)),
            floatArrayOf(0f, 0.4f, 0.7f, 1f), Shader.TileMode.CLAMP)
        strokePaint.strokeWidth = (density * 0.8f).coerceAtLeast(1f)
    }

    private enum class Quality { LOW, MEDIUM, HIGH }
    private data class EffectKey(val width: Int, val height: Int, val radius: Float, val blur: Float,
        val touchX: Float, val touchY: Float, val pressed: Boolean, val quality: Quality)

    /** API 33 的类单独封装，API 31–32 不实例化，也不把前景 UI 放进滤镜。 */
    @android.annotation.TargetApi(33)
    private class LensEffect {
        private val sharp = RuntimeShader(LENS_AGSL)
        private val frosted = RuntimeShader(LENS_AGSL)

        fun effect(frame: EffectKey, rim: Float, blur: RenderEffect?): RenderEffect {
            for ((shader, pass) in listOf(sharp to 0f, frosted to 1f)) {
                shader.setFloatUniform("size", frame.width.toFloat(), frame.height.toFloat())
                shader.setFloatUniform("radius", frame.radius)
                shader.setFloatUniform("rim", rim.coerceAtMost(min(frame.width, frame.height) * 0.28f))
                shader.setFloatUniform("frostPass", pass)
                shader.setFloatUniform("touch", frame.touchX * frame.width, frame.touchY * frame.height)
                shader.setFloatUniform("press", if (frame.pressed) 1f else 0f)
            }
            val clear = RenderEffect.createRuntimeShaderEffect(sharp, "content")
            val soft = RenderEffect.createRuntimeShaderEffect(frosted, "content")
            return RenderEffect.createBlendModeEffect(clear,
                if (blur == null) soft else RenderEffect.createChainEffect(soft, blur), BlendMode.SRC_OVER)
        }
    }
}

/**
 * 先选可见 content。部分宿主把旧 content 留在隐藏容器中，此时选同窗口、覆盖浮窗的最大宿主分支。
 * 结构扫描有预算，未知分支按不安全处理；任何包含模块浮窗的祖先都不会被记录。
 */
internal fun resolveOverlayBackdropSource(decor: ViewGroup, surface: View): View? {
    val analysis = BackdropTreeAnalysis(decor)
    val detachedStructure = !decor.isAttachedToWindow && !surface.isAttachedToWindow
    val staleContainers = hiddenContentAncestors(analysis, detachedStructure)
    val content = analysis.nodes.firstOrNull { it.id == android.R.id.content }
    if (content != null && !staleContainers.containsKey(content) && analysis.safe(content) && backdropSourceFits(decor, surface, content, detachedStructure)) return content
    return analysis.nodes.asSequence()
        .filter { it is ViewGroup && !staleContainers.containsKey(it) && analysis.safe(it) && backdropSourceFits(decor, surface, it, detachedStructure) }
        .maxWithOrNull(compareBy<View>({ supportedBackdropArea(it, detachedStructure) }, { visibleBackdropArea(it, detachedStructure) }))
}

/** 缓存来源每次录制前重新校验，避免隐藏的旧页面一直占住弱引用。 */
private fun currentOverlayBackdropSource(decor: ViewGroup, surface: View, candidate: View): Boolean {
    if (!backdropSourceFits(decor, surface, candidate, detachedStructure = false)) return false
    // 侧滑容器的外框可能仍覆盖全屏，而子内容已经移到屏幕外，不能继续复用空壳。
    if (supportedBackdropArea(candidate, false) * 2 < visibleBackdropArea(candidate, false)) return false
    val analysis = BackdropTreeAnalysis(candidate)
    return analysis.safe(candidate) && !hiddenContentAncestors(analysis, detachedStructure = false).containsKey(candidate)
}

/** 旧 content 的外层可能仍是全屏 VISIBLE；不能把这个空壳误选成当前画面。 */
private fun hiddenContentAncestors(analysis: BackdropTreeAnalysis, detachedStructure: Boolean): java.util.IdentityHashMap<View, Boolean> {
    val stale = java.util.IdentityHashMap<View, Boolean>()
    for (node in analysis.nodes) {
        if (node.id != android.R.id.content || backdropBranchVisible(node, detachedStructure)) continue
        var current: View? = node
        var depth = 0
        while (current != null && depth++ < 96) {
            stale[current] = true
            current = current.parent as? View
        }
    }
    return stale
}

private fun backdropBranchVisible(view: View, detachedStructure: Boolean): Boolean {
    if (!detachedStructure && !view.isShown) return false
    var current: View? = view
    var depth = 0
    while (current != null && depth++ < 96) {
        if (current.visibility != View.VISIBLE || current.alpha <= 0.01f) return false
        current = current.parent as? View
    }
    return current == null
}

private fun backdropSourceFits(decor: ViewGroup, surface: View, candidate: View, detachedStructure: Boolean): Boolean {
    if (candidate === decor || candidate === surface || moduleSurface(candidate) || isWithin(candidate, surface) || isWithin(surface, candidate)) return false
    if (candidate.visibility != View.VISIBLE) return false
    var ancestor: View? = candidate
    var depth = 0
    while (ancestor !== decor && ancestor != null && depth++ < 96) {
        if (moduleSurface(ancestor) || ancestor.visibility != View.VISIBLE || ancestor.alpha <= 0.01f) return false
        ancestor = ancestor.parent as? View
    }
    if (ancestor !== decor) return false
    // 未附着的结构测试不声称可以采样；运行时 recordBackdrop 仍强制硬件和窗口校验。
    if (detachedStructure) return true
    if (!candidate.isAttachedToWindow || !candidate.isShown || candidate.width <= 0 || candidate.height <= 0 ||
        candidate.rootView !== surface.rootView || surface.windowToken == null || candidate.windowToken != surface.windowToken) return false
    val sourceBounds = android.graphics.Rect()
    if (!candidate.getGlobalVisibleRect(sourceBounds)) return false
    val targetBounds = android.graphics.Rect()
    if (surface.width > 0 && surface.height > 0 && surface.getGlobalVisibleRect(targetBounds)) {
        // 一像素的舍入误差不应使同一窗口的兄弟布局失配。
        sourceBounds.inset(-1, -1)
        if (!sourceBounds.contains(targetBounds)) return false
    }
    return true
}

private fun visibleBackdropArea(view: View, detachedStructure: Boolean): Long {
    if (detachedStructure) return view.width.toLong() * view.height
    val bounds = android.graphics.Rect()
    return if (view.getGlobalVisibleRect(bounds)) bounds.width().toLong() * bounds.height() else 0L
}

/** 无自身背景的容器按子层实际覆盖范围评分，防止超宽、已移出屏幕的侧栏胜过聊天层。 */
private fun supportedBackdropArea(view: View, detachedStructure: Boolean): Long {
    if (detachedStructure || view !is ViewGroup || view.childCount == 0 || view.background != null) {
        return visibleBackdropArea(view, detachedStructure)
    }
    val coverage = Region()
    val childBounds = android.graphics.Rect()
    for (index in 0 until minOf(view.childCount, 128)) {
        val child = view.getChildAt(index)
        if (child.visibility == View.VISIBLE && child.alpha > 0.01f && child.getGlobalVisibleRect(childBounds)) {
            coverage.op(childBounds, Region.Op.UNION)
        }
    }
    val iterator = RegionIterator(coverage)
    var area = 0L
    while (iterator.next(childBounds)) area += childBounds.width().toLong() * childBounds.height()
    return area
}

private fun moduleSurface(view: View): Boolean {
    val tag = view.tag
    return view is OverlayGlassSurface || tag == OVERLAY_TAG || (tag is String && tag.startsWith("$OVERLAY_TAG."))
}

/** 一次有界元数据扫描，同时给祖先标记“包含浮窗”；不读取 TextView 内容或描述。 */
private class BackdropTreeAnalysis(root: View) {
    val nodes = ArrayList<View>()
    private val safety = java.util.IdentityHashMap<View, Boolean>()
    private var remaining = 8192

    init { inspect(root, 0) }

    fun safe(view: View): Boolean = safety[view] == true

    private fun inspect(view: View, depth: Int): Boolean {
        if (remaining-- <= 0 || depth > 96) {
            safety[view] = false
            return false
        }
        nodes.add(view)
        if (moduleSurface(view)) {
            safety[view] = false
            return false
        }
        var safe = true
        if (view is ViewGroup) {
            for (index in 0 until view.childCount) {
                if (remaining <= 0) {
                    safe = false
                    break
                }
                if (!inspect(view.getChildAt(index), depth + 1)) safe = false
            }
        }
        safety[view] = safe
        return safe
    }
}

private fun isWithin(ancestor: View, descendant: View): Boolean {
    var current: android.view.ViewParent? = descendant.parent
    var guard = 0
    while (current is View && guard++ < 128) {
        if (current === ancestor) return true
        current = current.parent
    }
    return false
}

private fun withAlpha(color: Int, alpha: Float): Int = Color.argb((alpha.coerceIn(0f, 1f) * 255f).roundToInt(), Color.red(color), Color.green(color), Color.blue(color))

private fun metadataClass(value: Any?): String = value?.javaClass?.name?.take(128) ?: "none"

private const val LENS_AGSL = """
uniform shader content;
uniform float2 size;
uniform float radius;
uniform float rim;
uniform float frostPass;
uniform float2 touch;
uniform float press;

float sdfAt(float2 p) {
    float2 q = abs(p) - size * 0.5 + radius;
    return length(max(q, 0.0)) + min(max(q.x, q.y), 0.0) - radius;
}

half4 main(float2 xy) {
    float2 p = xy - size * 0.5;
    float sdf = sdfAt(p);
    if (sdf > 0.75) return half4(0.0);
    float2 n = normalize(float2(sdfAt(p + float2(1.0, 0.0)) - sdfAt(p - float2(1.0, 0.0)),
        sdfAt(p + float2(0.0, 1.0)) - sdfAt(p - float2(0.0, 1.0))) + float2(0.0001));
    float depth = clamp(-sdf / max(rim, 1.0), 0.0, 1.0);
    float bevel = (1.0 - depth) * (1.0 - depth);
    float2 delta = (xy - touch) / max(rim * 5.0, 1.0);
    float dome = exp(-dot(delta, delta) * 1.8) * press;
    float2 sampleXY = clamp(xy - n * bevel * rim * 0.72 - delta * dome * rim * 0.1, float2(0.5), size - 0.5);
    half4 pixel = content.eval(sampleXY);
    float2 shift = n * bevel * rim * 0.025;
    pixel.r = content.eval(clamp(sampleXY - shift, float2(0.5), size - 0.5)).r;
    pixel.b = content.eval(clamp(sampleXY + shift, float2(0.5), size - 0.5)).b;
    float glint = pow(max(dot(n, normalize(float2(-1.0, -1.0))), 0.0), 4.0) * bevel * 0.20;
    pixel.rgb += half3((1.0 - pixel.rgb) * (glint + dome * 0.065));
    float alpha = 1.0 - smoothstep(-0.75, 0.75, sdf);
    if (frostPass > 0.5) alpha *= smoothstep(rim * 0.4, rim * 1.5, -sdf) * 0.94;
    return pixel * half(alpha);
}
"""
