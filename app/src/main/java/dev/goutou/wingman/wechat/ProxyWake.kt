package dev.goutou.wingman.wechat

import android.content.Context
import android.content.Intent
import dev.goutou.wingman.config.ConfigData
import dev.goutou.wingman.config.MODULE_PKG
import dev.goutou.wingman.proxy.ProxyProtocol

/**
 * 回环代理连不上时，从**微信进程**把 App 叫起来（issue #7①）。
 *
 * 背景：开本地代理之后，真正发请求的是微信进程，目标却是 App 进程里的
 * `127.0.0.1:PORT`。App 一退到后台，进程就可能被系统回收 —— 端口跟着没了，
 * 微信侧只会看到 connect 超时。而 App 自己**没有任何办法知道**有人在外面敲它。
 *
 * 这条广播就是那个「敲门」：它由 manifest 里的静态接收器接（见
 * [dev.goutou.wingman.proxy.ProxyWakeReceiver]），所以**能把已经死掉的 App 进程拉起来**。
 *
 * 三件事刻意做得很保守：
 * - **只在这条路确实走代理时发**（直连失败跟 App 没关系，发了纯属白耗电）；
 * - **60 秒冷却**：一轮分析失败、下一轮又失败，不该把 App 拉起十次；
 * - 任何异常都吞掉：叫不醒就叫不醒，绝不能让它把这次请求的原始错误盖掉。
 */
internal object ProxyWake {

    /** 两次唤醒之间的最小间隔。 */
    private const val COOLDOWN_MS = 60_000L

    @Volatile
    private var lastAt = 0L

    /**
     * @param context 微信那边的 Context（用它 sendBroadcast 最省事，见 Heartbeat.send）
     * @param cfg 当前配置；代理没开 / token 是空的一律不发
     * @param why 一次失败的简述，只用于决策记录，不参与广播内容
     */
    fun tryWake(context: Context?, cfg: ConfigData, why: String) {
        if (context == null) return
        if (!cfg.proxyEnabled || cfg.proxyToken.isBlank()) return
        val now = System.currentTimeMillis()
        synchronized(this) {
            if (now - lastAt < COOLDOWN_MS) return
            lastAt = now
        }
        Trace.note("唤醒", "回环代理连不上（$why）→ 发广播把 App 叫起来（60 秒内只叫一次）")
        runCatching {
            context.sendBroadcast(
                Intent(ProxyProtocol.ACTION_WAKE)
                    .setPackage(MODULE_PKG)
                    .putExtra(ProxyProtocol.EXTRA_TOKEN, cfg.proxyToken),
            )
        }
    }
}
