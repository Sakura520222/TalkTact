package dev.goutou.wingman

import android.graphics.Color
import android.view.View
import android.view.ViewGroup
import android.widget.FrameLayout
import dev.goutou.wingman.wechat.OverlayGlassSurface
import dev.goutou.wingman.wechat.resolveOverlayBackdropSource
import org.junit.After
import org.junit.Assert.assertSame
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.Robolectric
import org.robolectric.RobolectricTestRunner
import org.robolectric.android.controller.ActivityController
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode

/** 重现微信保留隐藏 content 容器、把实际聊天放在另一个分支的窗口结构。 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34], qualifiers = "w400dp-h800dp-xxhdpi")
@GraphicsMode(GraphicsMode.Mode.NATIVE)
class OverlaySourceSelectionTest {
    private lateinit var controller: ActivityController<OverlayPreviewActivity>
    private lateinit var activity: OverlayPreviewActivity
    private lateinit var decor: ViewGroup

    @Before fun setup() {
        controller = Robolectric.buildActivity(OverlayPreviewActivity::class.java).setup().visible()
        activity = controller.get()
        decor = activity.window.decorView as ViewGroup
        decor.findViewById<View>(android.R.id.content).visibility = View.GONE
    }

    @After fun cleanup() { controller.pause().stop().destroy() }

    private fun layout() {
        decor.measure(View.MeasureSpec.makeMeasureSpec(1200, View.MeasureSpec.EXACTLY), View.MeasureSpec.makeMeasureSpec(2400, View.MeasureSpec.EXACTLY))
        decor.layout(0, 0, 1200, 2400)
    }

    @Test fun `隐藏的标准content不能盖过真实可见宿主分支`() {
        val visibleHost = FrameLayout(activity).apply { setBackgroundColor(Color.GRAY) }
        decor.addView(visibleHost, FrameLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT))
        layout()
        assertSame(visibleHost, resolveOverlayBackdropSource(decor, activity.glass))
    }

    @Test fun `候选父层含另一个浮窗时只可选安全的内容子分支`() {
        val hostRoot = FrameLayout(activity)
        val safeContent = FrameLayout(activity).apply { setBackgroundColor(Color.GRAY) }
        val otherSurface = OverlayGlassSurface(activity)
        hostRoot.addView(safeContent, FrameLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT))
        hostRoot.addView(otherSurface, FrameLayout.LayoutParams(120, 120))
        decor.addView(hostRoot, FrameLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT))
        layout()
        assertSame(safeContent, resolveOverlayBackdropSource(decor, activity.glass))
        otherSurface.releaseBackdrop()
    }

    @Test fun `超宽侧栏的空壳不能胜过可见聊天内容`() {
        val chatContent = FrameLayout(activity).apply { setBackgroundColor(Color.GRAY) }
        decor.addView(chatContent, FrameLayout.LayoutParams(1200, 2100))
        val sidebar = FrameLayout(activity)
        sidebar.addView(FrameLayout(activity), FrameLayout.LayoutParams(1200, 2400))
        decor.addView(sidebar, FrameLayout.LayoutParams(2300, 2400).apply { leftMargin = -1100 })
        layout()
        assertSame(chatContent, resolveOverlayBackdropSource(decor, activity.glass))
    }
}
