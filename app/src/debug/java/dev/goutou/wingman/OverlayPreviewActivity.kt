package dev.goutou.wingman

import android.app.Activity
import android.content.res.Configuration
import android.graphics.Color
import android.graphics.Typeface
import android.graphics.drawable.GradientDrawable
import android.os.Bundle
import android.view.Gravity
import android.view.View
import android.view.ViewGroup
import android.widget.Button
import android.widget.FrameLayout
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView
import dev.goutou.wingman.wechat.OverlayGlassSurface
import java.io.File

/**
 * Debug-only GPU / touch fixture. All content is synthetic; this Activity does not read ConfigStore,
 * messages, clipboard or provider credentials. The glass is a decor sibling of the host content.
 */
class OverlayPreviewActivity : Activity() {
    internal lateinit var glass: OverlayGlassSurface
        private set
    internal lateinit var foregroundButton: Button
        private set
    internal lateinit var underlayButton: Button
        private set
    internal lateinit var outsideButton: Button
        private set
    internal var underlayClicks = 0
        private set
    internal var foregroundClicks = 0
        private set
    internal var foregroundLongClicks = 0
        private set

    private lateinit var counters: TextView
    private lateinit var backdropStatus: TextView
    private val previewSession = System.nanoTime().toString()
    private val refreshBackdropStatus = Runnable {
        if (::glass.isInitialized && !isDestroyed) updateCounters()
    }
    private val density get() = resources.displayMetrics.density
    private fun dp(value: Int) = (value * density + 0.5f).toInt()

    private val dark get() = resources.configuration.uiMode and Configuration.UI_MODE_NIGHT_MASK == Configuration.UI_MODE_NIGHT_YES
    private val textColor get() = if (dark) Color.rgb(242, 239, 247) else Color.rgb(25, 23, 30)
    private val subColor get() = if (dark) Color.rgb(187, 181, 196) else Color.rgb(100, 94, 110)
    private val accent get() = if (dark) Color.rgb(205, 191, 250) else Color.rgb(98, 85, 143)

    @Suppress("DEPRECATION")
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        val pageBackground = if (dark) Color.rgb(18, 17, 24) else Color.rgb(250, 247, 253)
        window.statusBarColor = pageBackground
        window.navigationBarColor = pageBackground
        window.decorView.systemUiVisibility = if (dark) 0 else View.SYSTEM_UI_FLAG_LIGHT_STATUS_BAR or View.SYSTEM_UI_FLAG_LIGHT_NAVIGATION_BAR

        val host = FrameLayout(this).apply { setBackgroundColor(pageBackground) }
        val messages = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(18), dp(14), dp(18), dp(28))
        }
        messages.addView(label("液态浮窗预览", 23f, textColor).apply { setTypeface(typeface, Typeface.BOLD) })
        messages.addView(label("合成场景 · 无模型请求", 13f, subColor).apply { setPadding(0, dp(6), 0, dp(30)) })
        listOf(
            "示例联系人" to "周末有空吗，想一起吃个饭。",
            "我" to "可以呀，你有什么想吃的？",
            "示例联系人" to "换一家没去过的店吧。",
            "我" to "听起来不错，我找找附近的餐厅。",
            "示例联系人" to "下午再确认时间。",
        ).forEachIndexed { index, (speaker, message) ->
            messages.addView(label(speaker, 12f, subColor).apply {
                gravity = if (index % 2 == 1) Gravity.END else Gravity.START
                setPadding(dp(6), dp(16), dp(6), dp(5))
            })
            messages.addView(label(message, 16f, textColor).apply {
                background = rounded(if (index % 2 == 1) 0x445FBD95 else if (dark) 0x442A2440 else 0x44B2A3CE)
                setPadding(dp(14), dp(12), dp(14), dp(12))
            })
        }
        val scroll = ScrollView(this).apply { addView(messages) }
        host.addView(scroll, FrameLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT))

        // Deliberately behind the blank lower portion of the glass. It must never receive those taps.
        underlayButton = Button(this).apply {
            text = "下层按钮（测试）"
            contentDescription = "overlay-preview-underlay-button"
            setOnClickListener { underlayClicks++; updateCounters() }
        }
        host.addView(underlayButton, FrameLayout.LayoutParams(dp(210), dp(44), Gravity.TOP or Gravity.CENTER_HORIZONTAL).apply { topMargin = dp(300) })
        counters = label("", 14f, textColor).apply {
            contentDescription = "overlay-preview-counters"
            gravity = Gravity.CENTER
            setPadding(dp(10), dp(10), dp(10), dp(10))
            background = rounded(if (dark) 0xEE25212E.toInt() else 0xEEEEE8F4.toInt())
        }
        host.addView(counters, FrameLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, dp(54)).apply { setMargins(dp(16), dp(405), dp(16), 0) })
        outsideButton = Button(this).apply {
            text = "卡片外页面按钮"
            contentDescription = "overlay-preview-outside-button"
            setOnClickListener { underlayClicks++; updateCounters() }
        }
        host.addView(outsideButton, FrameLayout.LayoutParams(dp(210), dp(48), Gravity.TOP or Gravity.CENTER_HORIZONTAL).apply { topMargin = dp(470) })
        backdropStatus = label("背景未就绪", 13f, subColor).apply {
            contentDescription = "overlay-preview-backdrop-status"
            gravity = Gravity.CENTER
            background = rounded(if (dark) 0xEE25212E.toInt() else 0xEEEEE8F4.toInt())
        }
        host.addView(backdropStatus, FrameLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, dp(36)).apply { setMargins(dp(16), dp(532), dp(16), 0) })
        updateCounters()
        setContentView(host)

        glass = OverlayGlassSurface(this).apply {
            orientation = LinearLayout.VERTICAL
            contentDescription = "overlay-preview-glass"
            setPadding(dp(16), dp(12), dp(16), dp(12))
        }
        val header = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
        }
        header.addView(label("回复建议", 17f, textColor).apply { setTypeface(typeface, Typeface.BOLD); gravity = Gravity.CENTER_VERTICAL }, LinearLayout.LayoutParams(0, dp(44), 1f))
        header.addView(label("低风险", 12f, accent).apply { gravity = Gravity.CENTER }, LinearLayout.LayoutParams(dp(64), dp(44)))
        glass.addView(header)
        glass.addView(label("可以呀，周末一起找家新餐厅。", 16f, textColor).apply { gravity = Gravity.CENTER_VERTICAL }, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, dp(48)))
        foregroundButton = Button(this).apply {
            text = "复制示例"
            contentDescription = "overlay-preview-foreground-button"
            setTextColor(accent)
            background = rounded(0x335F4C92)
            minimumHeight = dp(48)
            setOnClickListener { foregroundClicks++; updateCounters() }
            setOnLongClickListener { foregroundLongClicks++; updateCounters(); true }
        }
        glass.addView(foregroundButton, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, dp(48)).apply { topMargin = dp(8) })
        val decor = window.decorView as ViewGroup
        decor.addView(glass, FrameLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, dp(250), Gravity.TOP).apply {
            setMargins(dp(16), dp(130), dp(16), 0)
        })
        glass.bindHost(decor)
        glass.updateStyle("high", 0.92f, 12f)
        // One bounded read after attachment; screenshot evidence distinguishes actual recording
        // from the normal legible fallback without running a perpetual UI polling loop.
        glass.postDelayed(refreshBackdropStatus, 350L)
    }

    override fun onResume() {
        super.onResume()
        if (::glass.isInitialized) {
            glass.resumeBackdrop()
            glass.setBackdropAllowed(true)
        }
    }

    override fun onPause() {
        if (::glass.isInitialized) glass.pauseBackdrop()
        super.onPause()
    }

    override fun onDestroy() {
        if (::glass.isInitialized) {
            glass.removeCallbacks(refreshBackdropStatus)
            glass.releaseBackdrop()
            (glass.parent as? ViewGroup)?.removeView(glass)
        }
        super.onDestroy()
    }

    private fun updateCounters() {
        counters.text = "下层 $underlayClicks · 浮窗 $foregroundClicks · 长按 $foregroundLongClicks"
        if (::backdropStatus.isInitialized) {
            backdropStatus.text = if (::glass.isInitialized && glass.hasRetainedBackdrop) "背景就绪" else "背景未就绪"
        }
        // 仅调试合成场景的计数与 GPU 就绪状态，供 ADB 核验触摸，不含配置或聊天。
        runCatching {
            val ready = ::glass.isInitialized && glass.hasRetainedBackdrop
            File(filesDir, "overlay-preview.json").writeText(
                "{\"session\":\"$previewSession\",\"underlay\":$underlayClicks,\"foreground\":$foregroundClicks,\"longPress\":$foregroundLongClicks,\"backdropReady\":$ready}",
            )
        }
    }

    private fun label(value: String, size: Float, color: Int) = TextView(this).apply {
        text = value
        textSize = size
        setTextColor(color)
    }

    private fun rounded(color: Int) = GradientDrawable().apply {
        setColor(color)
        cornerRadius = dp(16).toFloat()
    }
}
