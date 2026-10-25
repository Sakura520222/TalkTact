package dev.goutou.wingman

import android.graphics.PointF
import android.graphics.Rect
import android.os.Looper
import android.os.SystemClock
import android.view.MotionEvent
import android.view.View
import android.view.ViewGroup
import android.view.ViewConfiguration
import android.widget.LinearLayout
import android.widget.FrameLayout
import android.widget.ScrollView
import android.widget.TextView
import dev.goutou.wingman.wechat.OverlayGlassSurface
import dev.goutou.wingman.wechat.resolveOverlayBackdropSource
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.Robolectric
import org.robolectric.RobolectricTestRunner
import org.robolectric.Shadows
import org.robolectric.android.controller.ActivityController
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode
import java.time.Duration

/** Real View dispatch over a clickable synthetic host, never a live messaging process. */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34], qualifiers = "w400dp-h800dp-xxhdpi")
@GraphicsMode(GraphicsMode.Mode.NATIVE)
class OverlayInteractionTest {
    private lateinit var controller: ActivityController<OverlayPreviewActivity>
    private lateinit var activity: OverlayPreviewActivity
    private lateinit var decor: ViewGroup

    @Before fun setup() {
        controller = Robolectric.buildActivity(OverlayPreviewActivity::class.java).setup().visible()
        activity = controller.get()
        decor = activity.window.decorView as ViewGroup
        layout()
    }

    @After fun cleanup() {
        controller.pause().stop().destroy()
    }

    private fun layout() {
        decor.measure(View.MeasureSpec.makeMeasureSpec(1200, View.MeasureSpec.EXACTLY), View.MeasureSpec.makeMeasureSpec(2400, View.MeasureSpec.EXACTLY))
        decor.layout(0, 0, 1200, 2400)
        Shadows.shadowOf(Looper.getMainLooper()).idle()
    }

    private fun center(view: View): PointF {
        val rect = Rect()
        assertTrue("Target must be laid out and visible", view.getGlobalVisibleRect(rect))
        val origin = IntArray(2)
        decor.getLocationOnScreen(origin)
        return PointF(rect.exactCenterX() - origin[0], rect.exactCenterY() - origin[1])
    }

    private fun event(action: Int, point: PointF, down: Long, at: Long = SystemClock.uptimeMillis()): Boolean {
        val motion = MotionEvent.obtain(down, at, action, point.x, point.y, 0)
        return try { decor.dispatchTouchEvent(motion) } finally { motion.recycle() }
    }

    private fun tap(point: PointF) {
        val down = SystemClock.uptimeMillis()
        assertTrue(event(MotionEvent.ACTION_DOWN, point, down))
        assertTrue(event(MotionEvent.ACTION_UP, point, down, down + 16))
        Shadows.shadowOf(Looper.getMainLooper()).idle()
    }

    @Test fun `blank card area consumes taps before the underlying host`() {
        val point = center(activity.underlayButton)
        val card = Rect()
        activity.glass.getGlobalVisibleRect(card)
        val origin = IntArray(2)
        decor.getLocationOnScreen(origin)
        assertTrue(card.contains((point.x + origin[0]).toInt(), (point.y + origin[1]).toInt()))
        tap(point)
        assertEquals(0, activity.underlayClicks)
        assertEquals(0, activity.foregroundClicks)
    }

    @Test fun `foreground actions receive taps while card still protects host`() {
        assertTrue(activity.foregroundButton.height >= (48 * activity.resources.displayMetrics.density).toInt())
        tap(center(activity.foregroundButton))
        assertEquals(1, activity.foregroundClicks)
        assertEquals(0, activity.underlayClicks)
    }

    @Test fun `long press stays with the child and does not click host`() {
        val point = center(activity.foregroundButton)
        val down = SystemClock.uptimeMillis()
        assertTrue(event(MotionEvent.ACTION_DOWN, point, down))
        Shadows.shadowOf(Looper.getMainLooper()).idleFor(Duration.ofMillis(ViewConfiguration.getLongPressTimeout().toLong() + ViewConfiguration.getTapTimeout() + 20))
        assertTrue(event(MotionEvent.ACTION_UP, point, down))
        assertEquals(1, activity.foregroundLongClicks)
        assertEquals(0, activity.foregroundClicks)
        assertEquals(0, activity.underlayClicks)
    }

    @Test fun `cancelled gesture cannot activate a button behind or inside the card`() {
        val point = center(activity.foregroundButton)
        val down = SystemClock.uptimeMillis()
        assertTrue(event(MotionEvent.ACTION_DOWN, point, down))
        assertTrue(event(MotionEvent.ACTION_CANCEL, point, down, down + 16))
        Shadows.shadowOf(Looper.getMainLooper()).idle()
        assertEquals(0, activity.foregroundClicks)
        assertEquals(0, activity.underlayClicks)
    }

    @Test fun `touches outside card still reach the host page`() {
        tap(center(activity.outsideButton))
        assertEquals(1, activity.underlayClicks)
        assertEquals(0, activity.foregroundClicks)
    }

    @Test fun `scrolling child keeps its own gesture stream`() {
        activity.glass.removeAllViews()
        val density = activity.resources.displayMetrics.density
        val scroll = ScrollView(activity)
        val rows = LinearLayout(activity).apply { orientation = LinearLayout.VERTICAL }
        repeat(30) { index -> rows.addView(TextView(activity).apply { text = "示例回复 $index" }, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, (32 * density).toInt())) }
        scroll.addView(rows)
        activity.glass.addView(scroll, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, (190 * density).toInt()))
        layout()
        val origin = center(scroll)
        val down = SystemClock.uptimeMillis()
        assertTrue(event(MotionEvent.ACTION_DOWN, PointF(origin.x, origin.y + 50 * density), down))
        event(MotionEvent.ACTION_MOVE, PointF(origin.x, origin.y + 10 * density), down, down + 16)
        event(MotionEvent.ACTION_MOVE, PointF(origin.x, origin.y - 40 * density), down, down + 32)
        event(MotionEvent.ACTION_UP, PointF(origin.x, origin.y - 50 * density), down, down + 48)
        assertTrue("Parent shielding must not swallow child scrolls", scroll.scrollY > 0)
        assertEquals(0, activity.underlayClicks)
    }

    @Test fun `privacy gate and pause discard backdrop and require fresh permission`() {
        val glass = activity.glass
        glass.updateStyle("high", 0.92f, 12f)
        glass.setBackdropAllowed(true)
        assertTrue(glass.backdropCaptureEnabled)
        glass.setBackdropAllowed(false)
        assertFalse(glass.backdropCaptureEnabled)
        assertFalse(glass.hasRetainedBackdrop)
        glass.setBackdropAllowed(true)
        glass.pauseBackdrop()
        assertFalse(glass.backdropCaptureEnabled)
        assertFalse(glass.hasRetainedBackdrop)
        glass.resumeBackdrop()
        assertFalse("Resume alone must not restore the old conversation permission", glass.backdropCaptureEnabled)
        glass.setBackdropAllowed(true)
        assertTrue(glass.backdropCaptureEnabled)
        glass.updateStyle("low", 0.92f, 12f)
        assertFalse(glass.backdropCaptureEnabled)
        assertFalse(glass.hasRetainedBackdrop)
    }

    @Test fun `only sibling host content can be used as the backdrop source`() {
        val content = decor.findViewById<View>(android.R.id.content)
        assertTrue(resolveOverlayBackdropSource(decor, activity.glass) === content)

        val missing = FrameLayout(activity)
        assertEquals(null, resolveOverlayBackdropSource(missing, activity.glass))

        val contentAsDecor = FrameLayout(activity).apply { id = android.R.id.content }
        assertEquals(null, resolveOverlayBackdropSource(contentAsDecor, activity.glass))

        val parent = FrameLayout(activity)
        val containingSurface = FrameLayout(activity).apply { id = android.R.id.content }
        val nestedSurface = OverlayGlassSurface(activity)
        parent.addView(containingSurface)
        containingSurface.addView(nestedSurface)
        assertEquals(null, resolveOverlayBackdropSource(parent, nestedSurface))

        val surfaceParent = FrameLayout(activity)
        val surfaceWithContent = OverlayGlassSurface(activity)
        surfaceWithContent.addView(FrameLayout(activity).apply { id = android.R.id.content })
        surfaceParent.addView(surfaceWithContent)
        assertEquals(null, resolveOverlayBackdropSource(surfaceParent, surfaceWithContent))
        nestedSurface.releaseBackdrop()
        surfaceWithContent.releaseBackdrop()
    }
}
