package dev.goutou.wingman

import android.app.Activity
import android.view.View
import android.view.ViewGroup
import dev.goutou.wingman.wechat.Panel
import dev.goutou.wingman.wechat.OverlayGlassSurface
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Assert.assertFalse
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.Robolectric
import org.robolectric.RobolectricTestRunner
import org.robolectric.android.controller.ActivityController
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode

/** 真实状态渲染路径的回归：不调用模型，也不放行合成敏感内容。 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
@GraphicsMode(GraphicsMode.Mode.NATIVE)
class OverlayExpansionTest {
    class HostActivity : Activity() {
        override fun getPackageName(): String = "com.tencent.mm"
    }

    private lateinit var controller: ActivityController<HostActivity>
    private lateinit var panel: Panel

    @Before fun setup() {
        controller = Robolectric.buildActivity(HostActivity::class.java).setup().visible()
        panel = Panel(controller.get())
        panel.onResume()
        panel.javaClass.getDeclaredField("onChat").apply { isAccessible = true }.setBoolean(panel, true)
    }

    @After fun cleanup() {
        panel.onDestroy()
        controller.pause().stop().destroy()
    }

    private fun view(name: String): View = panel.javaClass.getDeclaredField(name).apply { isAccessible = true }.get(panel) as View
    private fun sensitive() {
        panel.javaClass.getDeclaredMethod("renderSensitive", List::class.java, String::class.java)
            .apply { isAccessible = true }.invoke(panel, listOf("手机号"), "合成测试内容")
    }
    private fun message(text: String, error: Boolean) {
        panel.javaClass.getDeclaredMethod("showMessage", String::class.java, Boolean::class.javaPrimitiveType)
            .apply { isAccessible = true }.invoke(panel, text, error)
    }
    private fun expanded(visible: Boolean, byUser: Boolean) {
        panel.javaClass.getDeclaredMethod("setExpanded", Boolean::class.javaPrimitiveType, String::class.java, Boolean::class.javaPrimitiveType)
            .apply { isAccessible = true }.invoke(panel, visible, "test", byUser)
    }

    @Test fun `首次敏感提示和错误都只更新气泡`() {
        sensitive()
        assertEquals(View.GONE, view("card").visibility)
        assertEquals(View.VISIBLE, view("chipSurface").visibility)
        message("合成错误", true)
        assertEquals(View.GONE, view("card").visibility)
        message("新的合成提示", false)
        assertEquals(View.GONE, view("card").visibility)
    }

    @Test fun `用户收起之后异步变化不会重新展开`() {
        sensitive()
        view("chip").performClick()
        assertEquals(View.VISIBLE, view("card").visibility)
        expanded(false, true)
        message("后台请求失败", true)
        sensitive()
        assertEquals(View.GONE, view("card").visibility)
        expanded(true, false)
        assertEquals(View.GONE, view("card").visibility)
    }

    @Test fun `用户展开后状态更新保留展开但暂停会收起`() {
        message("合成提示", false)
        view("chip").performClick()
        assertEquals(View.VISIBLE, view("card").visibility)
        sensitive()
        message("后台请求失败", true)
        assertEquals(View.VISIBLE, view("card").visibility)
        panel.onPause()
        assertEquals(View.GONE, view("card").visibility)
        assertEquals(View.GONE, view("chipSurface").visibility)
    }

    @Test fun `宿主移除浮窗后重挂可恢复采样而不复用旧许可`() {
        message("合成提示", false)
        view("chip").performClick()
        val card = view("card") as OverlayGlassSurface
        val decor = controller.get().window.decorView as ViewGroup
        val permission = panel.javaClass.getDeclaredMethod("allowBackdrop", Boolean::class.javaPrimitiveType).apply { isAccessible = true }
        permission.invoke(panel, true)
        assertTrue(card.backdropCaptureEnabled)
        decor.removeView(card)
        assertFalse(card.backdropCaptureEnabled)
        panel.javaClass.getDeclaredField("lastAttachTry").apply { isAccessible = true }.setLong(panel, -10_000L)
        panel.javaClass.getDeclaredMethod("ensureAttached", ViewGroup::class.java).apply { isAccessible = true }.invoke(panel, decor)
        assertEquals(decor, card.parent)
        assertFalse(card.backdropCaptureEnabled)
        permission.invoke(panel, true)
        assertTrue(card.backdropCaptureEnabled)
    }
}
