package dev.goutou.wingman

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import dev.goutou.wingman.proxy.ProxyService

/**
 * 开机 / 覆盖安装 / 快启之后把本地代理补起来。
 *
 * 以前刻意不做开机自启（少一个常驻入口），代价是**重启手机后代理不会自己回来**，
 * 得用户开一次 App —— 当时假设「开一次就够了」。issue #7 的真机反馈推翻了这条假设：
 * App 一退到后台代理就可能没了，而用户人在微信里，根本不会想起来去开 App。
 *
 * ⚠️ 这几个 action 恰好是 Android 12+ 里**允许后台启动前台服务**的豁免场景，
 * 所以这里直接调 [ProxyService.startIfEnabled] 是合规的。
 * （自定义广播那条路不享受这个豁免，所以 [dev.goutou.wingman.proxy.ProxyWakeReceiver]
 * 走的是「先把监听拉起来、再试着补前台服务」。）
 */
class BootReceiver : BroadcastReceiver() {

    override fun onReceive(context: Context, intent: Intent) {
        when (intent.action) {
            Intent.ACTION_BOOT_COMPLETED,
            Intent.ACTION_MY_PACKAGE_REPLACED,
            // 几个厂商的「快速开机」用的还是老 action，顺手也认一下
            "android.intent.action.QUICKBOOT_POWERON",
            "com.htc.intent.action.QUICKBOOT_POWERON",
            -> ProxyService.startIfEnabled(context.applicationContext)
        }
    }
}
