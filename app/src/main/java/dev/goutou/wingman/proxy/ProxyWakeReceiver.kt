package dev.goutou.wingman.proxy

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import dev.goutou.wingman.config.ConfigStore

/**
 * 「代理没在跑，起一下」—— 由微信侧发过来（issue #7①）。
 *
 * 为什么需要它：App 不在前台时进程可能被系统回收，于是 `127.0.0.1:PORT` 连不上。
 * 而这条路必须是**静态注册**的接收器才能用 —— 静态接收器写在 manifest 里，
 * 即使 App 进程已经没了，系统也会为了投递这条广播**把进程重新拉起来**。
 * 拉起来之后这里先在本进程里把监听绑上，微信侧紧接着重试的那一次就能成功。
 *
 * 三道闸，免得这条广播变成「任何 App 都能拿来耗电」的入口：
 * 1. **必须带正确的 proxyToken**（只有读得到配置的注入侧才拿得到；32 位随机串，猜不出来）。
 *    刻意复用同一套凭据判断（[ProxyProtocol.isAuthorized] 用的就是它），不再引入第二套；
 * 2. 代理开关关着直接忽略；
 * 3. 注入侧自己还有冷却（[dev.goutou.wingman.wechat.ProxyWake]），不会每轮都发。
 *
 * 注意这里**不依赖前台服务**：Android 12+ 从后台启动前台服务会被
 * `ForegroundServiceStartNotAllowedException` 拦下，而「进程刚被 broadcast 拉起、
 * 只要多活几秒就够这一轮请求用」这件事不受那条限制。所以顺序是
 * **先同步把监听拉起来**，再顺手试着补前台服务（补上了更好）。
 */
class ProxyWakeReceiver : BroadcastReceiver() {

    override fun onReceive(context: Context, intent: Intent) {
        if (intent.action != ProxyProtocol.ACTION_WAKE) return
        val app = context.applicationContext
        val cfg = ConfigStore(app).load()
        if (!cfg.proxyEnabled) return
        if (intent.getStringExtra(ProxyProtocol.EXTRA_TOKEN) != cfg.proxyToken) return

        ProxyHost.ensure(app, cfg.proxyPort)
        ProxyService.start(app)
    }
}
