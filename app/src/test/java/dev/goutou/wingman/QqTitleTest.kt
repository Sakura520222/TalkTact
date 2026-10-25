package dev.goutou.wingman

import android.app.Activity
import android.content.Context
import android.view.View
import android.widget.FrameLayout
import android.widget.TextView
import dev.goutou.wingman.wechat.ViewReader
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.Robolectric
import org.robolectric.RobolectricTestRunner
import org.robolectric.android.controller.ActivityController
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34], qualifiers = "w400dp-h800dp-xxhdpi")
class QqTitleTest {
    class HostActivity : Activity() {
        override fun getPackageName() = "com.tencent.mobileqq"
    }

    class DrawnTitle(context: Context) : View(context) {
        fun getText(): CharSequence = "合成联系人"
    }

    private lateinit var controller: ActivityController<HostActivity>
    private lateinit var activity: HostActivity
    private lateinit var root: FrameLayout
    private lateinit var list: FrameLayout
    private lateinit var reader: ViewReader

    @Before fun setup() {
        controller = Robolectric.buildActivity(HostActivity::class.java).setup().visible()
        activity = controller.get()
        root = FrameLayout(activity)
        list = FrameLayout(activity)
        root.addView(list, FrameLayout.LayoutParams(1200, 1800).apply { topMargin = 290 })
        list.addView(TextView(activity).apply { text = "合成消息正文"; textSize = 18f },
            FrameLayout.LayoutParams(600, 90).apply { topMargin = 600 })
        activity.setContentView(root)
        reader = ViewReader(activity)
    }

    @After fun cleanup() { controller.pause().stop().destroy() }

    private fun placeTitle(view: View) {
        view.id = View.generateViewId() // 混淆资源不能成为拒绝左对齐标题的理由。
        root.addView(view, FrameLayout.LayoutParams(600, 72).apply { leftMargin = 150; topMargin = 150 })
        root.measure(View.MeasureSpec.makeMeasureSpec(1200, View.MeasureSpec.EXACTLY), View.MeasureSpec.makeMeasureSpec(2400, View.MeasureSpec.EXACTLY))
        root.layout(0, 0, 1200, 2400)
    }

    @Test fun `可读取资源名未知且靠左的QQ标题`() {
        placeTitle(TextView(activity).apply { text = "合成群聊(22)"; textSize = 18f })
        assertEquals("合成群聊(22)", reader.findChatTitle(root, list))
    }

    @Test fun `可读取自绘QQ标题的公开文本方法`() {
        placeTitle(DrawnTitle(activity))
        assertEquals("合成联系人", reader.findChatTitle(root, list))
    }

    @Test fun `没有标题时不能从消息或列表下方横幅借用会话名`() {
        placeTitle(TextView(activity).apply { text = ""; textSize = 18f })
        root.addView(TextView(activity).apply { text = "合成活动横幅"; textSize = 18f },
            FrameLayout.LayoutParams(900, 90).apply { leftMargin = 150; topMargin = 340 })
        root.layout(0, 0, 1200, 2400)
        assertNull(reader.findChatTitle(root, list))
    }

    @Test fun `工具栏状态和主动排除的名称不能充当会话标题`() {
        val title = TextView(activity).apply { text = "对方正在输入…"; textSize = 18f }
        placeTitle(title)
        assertNull(reader.findChatTitle(root, list))
        title.text = "合成联系人"
        assertNull(reader.findChatTitle(root, list, listOf("合成联系人")))
    }
}
