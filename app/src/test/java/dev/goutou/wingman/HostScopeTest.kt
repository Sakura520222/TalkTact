package dev.goutou.wingman

import android.content.Context
import android.content.Intent
import androidx.test.core.app.ApplicationProvider
import dev.goutou.wingman.config.ConfigData
import dev.goutou.wingman.config.ConfigStore
import dev.goutou.wingman.config.Keys
import dev.goutou.wingman.config.PREF_NAME
import dev.goutou.wingman.config.chatAllowed
import dev.goutou.wingman.wechat.ChatHost
import dev.goutou.wingman.wechat.QQ_KEY_PREFIX
import dev.goutou.wingman.wechat.conversationDisplayName
import dev.goutou.wingman.wechat.conversationRawName
import dev.goutou.wingman.wechat.isValidConversationKey
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

class HostScopeTest {
    @Test fun `only exact WeChat and QQ main processes are accepted`() {
        assertEquals(ChatHost.WECHAT, ChatHost.fromPackage("com.tencent.mm"))
        assertEquals(ChatHost.QQ, ChatHost.fromPackage("com.tencent.mobileqq"))
        assertTrue(ChatHost.matchesMainProcess("com.tencent.mm", "com.tencent.mm"))
        assertTrue(ChatHost.matchesMainProcess("com.tencent.mobileqq", "com.tencent.mobileqq"))
        assertFalse(ChatHost.matchesMainProcess("com.tencent.mm", "com.tencent.mm:push"))
        assertFalse(ChatHost.matchesMainProcess("com.tencent.mobileqq", "com.tencent.mobileqq:MSF"))
        assertFalse(ChatHost.matchesMainProcess("com.tencent.mobileqq", "com.tencent.mm"))
        assertFalse(ChatHost.matchesMainProcess("com.tencent.mm", "com.tencent.mobileqq"))
        listOf("com.tencent.tim", "com.tencent.wework", "com.tencent.mobileqq.lite", "com.example.app").forEach {
            assertNull(ChatHost.fromPackage(it))
            assertFalse(ChatHost.matchesMainProcess(it, it))
        }
    }

    @Test fun `QQ defaults to disabled independently of existing WeChat enablement`() {
        assertTrue(ConfigData().enabled)
        assertFalse(ConfigData().qqEnabled)
        assertFalse(ConfigData(enabled = false).qqEnabled)
        assertTrue(ConfigData(qqEnabled = true).qqEnabled)
    }

    @Test fun `same named contacts have distinct host identities without migrating WeChat keys`() {
        val wechat = ChatHost.WECHAT.conversationKey("  项目  群(12) ")
        val qq = ChatHost.QQ.conversationKey("  项目  群(12) ")
        assertEquals("项目 群", wechat)
        assertEquals(QQ_KEY_PREFIX + "项目 群", qq)
        assertNotEquals(wechat, qq)
        assertFalse(chatAllowed(true, setOf(wechat), qq))
        assertFalse(chatAllowed(true, setOf(qq), wechat))
        assertTrue(chatAllowed(true, setOf(qq), qq))
    }

    @Test fun `missing titles cannot produce a valid prefixed QQ identity`() {
        listOf("", "  ", "\n\t", "(12)").forEach {
            assertEquals("", ChatHost.WECHAT.conversationKey(it))
            assertEquals("", ChatHost.QQ.conversationKey(it))
        }
    }

    @Test fun `literal QQ prefix in a WeChat title cannot authorize a QQ contact`() {
        val wechat = ChatHost.WECHAT.conversationKey("QQ · 张三")
        val qq = ChatHost.QQ.conversationKey("张三")
        assertEquals("QQ · 张三", wechat)
        assertNotEquals(wechat, qq)
        assertFalse(chatAllowed(true, setOf(wechat), qq))
        assertFalse(chatAllowed(true, setOf(qq), wechat))
        assertTrue(QQ_KEY_PREFIX.length > 32)
    }

    @Test fun `title bounds are applied before host namespace is added`() {
        val longest = "名".repeat(32)
        assertEquals(longest, ChatHost.WECHAT.conversationKey(longest))
        val qq = ChatHost.QQ.conversationKey(longest)
        assertEquals(QQ_KEY_PREFIX + longest, qq)
        assertTrue(isValidConversationKey(qq))
        assertTrue(isValidConversationKey(longest))
        val tooLong = longest + "外"
        assertEquals("", ChatHost.WECHAT.conversationKey(tooLong))
        assertEquals("", ChatHost.QQ.conversationKey(tooLong))
        assertFalse(isValidConversationKey(tooLong))
        assertFalse(isValidConversationKey(QQ_KEY_PREFIX + tooLong))
    }

    @Test fun `display labels do not replace internal keys or alter legacy WeChat labels`() {
        val raw = "项目 群"
        val qq = ChatHost.QQ.conversationKey(raw)
        assertEquals(raw, conversationRawName(qq))
        assertEquals("QQ · $raw", conversationDisplayName(qq))
        assertEquals("QQ · 张三", conversationDisplayName("QQ · 张三"))
        assertEquals("QQ · 张三", conversationRawName("QQ · 张三"))
        assertEquals("张三", conversationDisplayName("张三"))
    }

    @Test fun `reserved malformed namespace and invalid XML titles fail closed`() {
        listOf(QQ_KEY_PREFIX, "talktact:host:unknown:张三", "talktact:host:com.tencent.mm:张三",
            QQ_KEY_PREFIX + "talktact:host:张三", QQ_KEY_PREFIX + "   ", "张\u0000三").forEach {
            assertFalse(isValidConversationKey(it))
            assertEquals("", ChatHost.QQ.conversationKey(it))
            assertEquals("", ChatHost.WECHAT.conversationKey(it))
        }
        val emoji = "😀".repeat(16)
        assertTrue(isValidConversationKey(ChatHost.QQ.conversationKey(emoji)))
    }
}

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class HostConfigStoreTest {
    @Test fun `QQ opt in persists and an absent key falls back to disabled`() {
        val context = ApplicationProvider.getApplicationContext<Context>()
        val preferences = context.getSharedPreferences(PREF_NAME, Context.MODE_PRIVATE)
        preferences.edit().clear().commit()
        assertFalse(ConfigData.from(preferences).qqEnabled)

        val store = ConfigStore(context)
        store.save(store.load().copy(qqEnabled = true))
        assertTrue(preferences.getBoolean(Keys.QQ_ENABLED, false))
        assertTrue(ConfigStore(context).load().qqEnabled)
        store.save(store.load().copy(qqEnabled = false))
        assertFalse(ConfigStore(context).load().qqEnabled)
    }

    @Test fun `saving QQ opt in preserves independently maintained proxy token`() {
        val context = ApplicationProvider.getApplicationContext<Context>()
        context.getSharedPreferences(PREF_NAME, Context.MODE_PRIVATE).edit().clear().commit()
        val store = ConfigStore(context)
        val oldSnapshot = store.load()
        val token = store.ensureProxyToken()
        store.save(oldSnapshot.copy(qqEnabled = true))
        assertTrue(store.load().qqEnabled)
        assertEquals(token, store.load().proxyToken)
    }

    @Test fun `receiver accepts a maximum length QQ title and rejects malformed keys`() {
        val context = ApplicationProvider.getApplicationContext<Context>()
        context.getSharedPreferences(PREF_NAME, Context.MODE_PRIVATE).edit().clear().commit()
        val key = ChatHost.QQ.conversationKey("名".repeat(32))
        val invalid = QQ_KEY_PREFIX + "外".repeat(33)
        HeartbeatReceiver().onReceive(context, Intent(Heartbeat.ACTION).putExtra("chats", "$key\n$invalid\n$QQ_KEY_PREFIX\ntalktact:host:unknown:张三"))
        assertEquals(setOf(key), ConfigStore(context).chatCandidates())
    }
}
