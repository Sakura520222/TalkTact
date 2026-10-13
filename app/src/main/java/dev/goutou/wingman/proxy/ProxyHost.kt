package dev.goutou.wingman.proxy

import android.content.Context

/**
 * 本地代理服务器的**唯一归属**。
 *
 * 为什么要多这一层（issue #7 的产物）：代理现在有两个可能的拉起者 ——
 * 正常路径是 [ProxyService]（前台服务），应急路径是 [ProxyWakeReceiver]（微信侧连不上时把 App 叫起来）。
 * 两边各持一份 [ProxyServer] 实例的话，第二个绑同一个端口必然 `Address already in use`，
 * 而失败分支会把 `ProxyState.running` 抹成 false —— 明明在跑的服务，界面上显示成「没起来」。
 * 所以实例收到这儿，谁要起都走 [ensure]，同一个端口只会被绑一次。
 */
object ProxyHost {

    @Volatile
    private var server: ProxyServer? = null

    /** 已经有一个在跑？ */
    val alive: Boolean get() = server?.running == true

    /**
     * 确保监听在跑。已经在跑就直接返回 true（不重复绑定端口）。
     *
     * @return true = 这一刻监听是好的
     */
    @Synchronized
    fun ensure(context: Context, port: Int): Boolean {
        val cur = server
        if (cur != null && cur.running) return true
        val s = ProxyServer(context.applicationContext)
        return runCatching { s.start(port) }.fold(
            onSuccess = {
                server = s
                true
            },
            onFailure = { e ->
                server = null
                ProxyState.running = false
                ProxyState.lastError = when {
                    e.message?.contains("in use", ignoreCase = true) == true ->
                        "端口 $port 被占用了，换一个再试"
                    else -> "端口 $port 起不来：${e.message}"
                }
                false
            },
        )
    }

    /** 关掉并忘掉实例（用户关开关、或服务 onDestroy）。 */
    @Synchronized
    fun shutdown() {
        runCatching { server?.stop() }
        server = null
        ProxyState.markStopped()
    }
}
