package dev.goutou.wingman

import android.content.Context
import android.content.SharedPreferences
import androidx.test.core.app.ApplicationProvider
import dev.goutou.wingman.config.Keys
import dev.goutou.wingman.config.PREF_NAME
import dev.goutou.wingman.config.RemoteSync
import org.junit.Assert.assertEquals
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class RemoteSyncPrivacyTest {
    private fun mirror(test: (SharedPreferences, SharedPreferences) -> Unit) {
        val context = ApplicationProvider.getApplicationContext<Context>()
        val local = context.getSharedPreferences(PREF_NAME, Context.MODE_PRIVATE)
        val remote = context.getSharedPreferences("synthetic_remote", Context.MODE_PRIVATE)
        local.edit().clear().commit()
        remote.edit().clear().commit()
        val contextField = RemoteSync::class.java.getDeclaredField("appContext").apply { isAccessible = true }
        val remoteField = RemoteSync::class.java.getDeclaredField("remote").apply { isAccessible = true }
        val oldContext = contextField.get(RemoteSync)
        val oldRemote = remoteField.get(RemoteSync)
        contextField.set(RemoteSync, context)
        remoteField.set(RemoteSync, remote)
        try { test(local, remote) } finally {
            contextField.set(RemoteSync, oldContext)
            remoteField.set(RemoteSync, oldRemote)
        }
    }

    @Test fun `代理模式不向任何宿主镜像两套服务商密钥`() = mirror { local, remote ->
        local.edit().putString(Keys.KEY, "synthetic-primary").putString(Keys.KEY2, "synthetic-risk")
            .putString(Keys.PROXY_TOKEN, "synthetic-loopback").putBoolean(Keys.PROXY_ON, true).commit()
        remote.edit().putString(Keys.KEY, "stale").putString(Keys.KEY2, "stale").commit()
        RemoteSync.syncAll()
        assertEquals("", remote.getString(Keys.KEY, null))
        assertEquals("", remote.getString(Keys.KEY2, null))
        assertEquals("synthetic-loopback", remote.getString(Keys.PROXY_TOKEN, null))
        assertEquals("synthetic-primary", local.getString(Keys.KEY, null))
        assertEquals("synthetic-risk", local.getString(Keys.KEY2, null))
    }

    @Test fun `直连镜像当前值且清除已不存在的风险密钥与QQ授权`() = mirror { local, remote ->
        local.edit().putString(Keys.KEY, "synthetic-primary").putBoolean(Keys.PROXY_ON, false).commit()
        remote.edit().putString(Keys.KEY2, "stale").putBoolean(Keys.QQ_ENABLED, true).commit()
        RemoteSync.syncAll()
        assertEquals("synthetic-primary", remote.getString(Keys.KEY, null))
        assertEquals("", remote.getString(Keys.KEY2, null))
        assertEquals(false, remote.getBoolean(Keys.QQ_ENABLED, true))
    }
}
