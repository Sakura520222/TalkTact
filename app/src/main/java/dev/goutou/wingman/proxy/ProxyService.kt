package dev.goutou.wingman.proxy

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Context
import android.content.Intent
import android.os.IBinder
import dev.goutou.wingman.MainActivity
import dev.goutou.wingman.R
import dev.goutou.wingman.config.ConfigStore

/**
 * 本地代理的宿主服务。
 *
 * 为什么必须是**前台服务**：代理得在微信随时来请求时都活着，而 targetSdk 34 起常驻服务必须是前台服务
 * （带常驻通知，类型要在 manifest 里声明）。
 *
 * 类型选 `specialUse` 而不是 `dataSync`：dataSync 在 Android 15+ 有「每天最多 6 小时」的时长上限，
 * 而代理是要一直开着的，会被系统掐掉。specialUse 本来用于「不属于其它任何类型」的用途，
 * 我们不上应用商店，但它是这里最贴切的类型（manifest 里还写了 subtype 说明）。
 *
 * ## 为什么多了「死了自己回来」这一套（issue #7）
 *
 * 用户实测：开代理后 App **不在前台**时，微信侧连 127.0.0.1 是 connect 超时 ——
 * 因为监听端口随 App 进程一起没了，而当时除了「把 App 再打开一次」没有任何恢复途径。
 * 现在四条路一起兜：
 * 1. 进程被杀后由 `START_STICKY` 拉回来（[onStartCommand] 收到 `intent == null` 就是这条路），
 *    这一条也记进 [ProxyState.restarts]；
 * 2. 从最近任务划掉 App 时 [onTaskRemoved] 立刻补一次；
 * 3. 开机 / 覆盖安装 / 快启后由 [BootReceiver] 补一次（这几个 action 恰好是 Android 12+
 *    **允许**后台启动前台服务的豁免场景）；
 * 4. 微信侧连不上时会发一条**带 token 的唤醒广播**（[ProxyWakeReceiver]），
 *    由它先在本进程里把监听拉起来 —— 这一轮请求当场就能完成。
 *
 * 还有一件事这里做不到、只能交给用户：国产 ROM 的「后台冻结 / 自启动管理」。
 * 所以设置页把 [ProxyState.foreground] 显式显示出来，并给一个「加电池优化白名单」的按钮。
 */
class ProxyService : Service() {

    companion object {
        private const val CHANNEL = "talktact-proxy"
        private const val NOTIF_ID = 1001
        const val ACTION_START = "dev.goutou.wingman.PROXY_START"
        const val ACTION_STOP = "dev.goutou.wingman.PROXY_STOP"

        /** 开代理。只能在界面里调 —— 后台启动前台服务从 Android 12 起会被系统拦掉。 */
        fun start(context: Context) {
            val i = Intent(context, ProxyService::class.java).setAction(ACTION_START)
            runCatching { context.startForegroundService(i) }
                .onFailure { e ->
                    // 后台起前台服务被拦是**预期内**的事（唤醒广播那条路就会撞上），
                    // 所以只有「代理本来也没在跑」时才值得把原因写进界面，免得盖掉更有用的信息。
                    if (!ProxyState.running) {
                        ProxyState.lastError = "拉起前台服务被系统拦下（${e.javaClass.simpleName}）"
                    }
                }
        }

        fun stop(context: Context) {
            val i = Intent(context, ProxyService::class.java).setAction(ACTION_STOP)
            runCatching { context.startService(i) }
        }

        /**
         * App 一打开就补一次（重启手机后代理不会自己回来，用户开一次 App 就够了）。
         *
         * 注意：监听已经在跑、只是没挂上前台服务时，**仍然要再 start 一次** ——
         * 那正是「需要把它变成前台服务」的情况（比如刚被唤醒广播拉起来过）。
         */
        fun startIfEnabled(context: Context) {
            if (!ConfigStore(context.applicationContext).load().proxyEnabled) return
            if (ProxyState.running && ProxyState.foreground) return
            start(context)
        }
    }

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        if (intent?.action == ACTION_STOP) {
            runCatching { stopForeground(STOP_FOREGROUND_REMOVE) }
            ProxyState.foreground = false
            ProxyHost.shutdown()
            stopSelf()
            return START_NOT_STICKY
        }

        // intent == null：系统按 START_STICKY 把服务重新拉起来了 —— 也就是说**进程之前被杀过**
        if (intent == null) ProxyState.restarts++

        val cfg = ConfigStore(this).load()

        // 前台服务只是「别被系统回收」，**代理本身不依赖它** ——
        // 所以这里的失败只记下来给用户看，绝不 return：起不来通知也要把代理跑起来。
        ProxyState.foreground = tryStartForeground(cfg.proxyPort)

        ProxyHost.ensure(this, cfg.proxyPort)
        return START_STICKY
    }

    /**
     * 挂上前台服务。返回 true = 挂上了。
     *
     * 失败原因翻成用户能看懂的一句话，并且**区分「被系统拦了」和「别的原因」**：
     * 前者在国产 ROM 上很常见，解决方式是去加电池白名单；后者才是代码问题。
     * 重试一次：Android 12+ 有「后台启动前台服务在 5 秒宽限期内仍允许」的说法，
     * 而这里往往正是刚被广播拉起、处在那个窗口里的时刻。
     */
    private fun tryStartForeground(port: Int): Boolean {
        val notif = buildNotification(port)
        var last: Throwable? = null
        repeat(2) { attempt ->
            val r = runCatching { startForeground(NOTIF_ID, notif) }
            if (r.isSuccess) {
                if (ProxyState.lastError?.contains("前台服务") == true) ProxyState.lastError = null
                return true
            }
            last = r.exceptionOrNull()
            if (attempt == 0) runCatching { Thread.sleep(150) }
        }
        val name = last?.javaClass?.simpleName ?: "未知"
        ProxyState.lastError =
            "前台服务没起来（$name）：代理仍在跑，但系统随时可能把它一起回收 —— " +
                "建议点下面的「加电池优化白名单」"
        return false
    }

    /**
     * 从最近任务里划掉 App 时补一次。
     *
     * 用 [ProxyHost.ensure] 而不是再 start 一次服务：这一刻服务自己还活着，
     * 需要保证的是**监听还在**；至于前台服务，系统随后要不要回收，用户划掉动作已经决定了。
     */
    override fun onTaskRemoved(rootIntent: Intent?) {
        val cfg = ConfigStore(this).load()
        if (cfg.proxyEnabled) ProxyHost.ensure(this, cfg.proxyPort)
        super.onTaskRemoved(rootIntent)
    }

    override fun onDestroy() {
        ProxyState.foreground = false
        ProxyHost.shutdown()
        super.onDestroy()
    }

    private fun buildNotification(port: Int): Notification {
        val nm = getSystemService(NotificationManager::class.java)
        if (nm.getNotificationChannel(CHANNEL) == null) {
            nm.createNotificationChannel(
                NotificationChannel(CHANNEL, "本地代理", NotificationManager.IMPORTANCE_LOW).apply {
                    description = "API Key 留在 App 进程里的本地代理"
                    setShowBadge(false)
                },
            )
        }
        val open = PendingIntent.getActivity(
            this,
            0,
            Intent(this, MainActivity::class.java),
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT,
        )
        return Notification.Builder(this, CHANNEL)
            .setContentTitle("TalkTact 本地代理运行中")
            .setContentText("127.0.0.1:$port · API Key 没有离开本应用")
            // 必须用 drawable 里的单色图标：mipmap 的 ic_launcher 是 adaptive-icon，通知不接受
            .setSmallIcon(R.drawable.ic_stat_talktact)
            .setContentIntent(open)
            .setOngoing(true)
            .build()
    }
}
