package dev.goutou.wingman.proxy

/**
 * 代理的运行状态。
 *
 * 为什么用静态对象而不是 SharedPreferences：服务与设置界面在**同一个进程**里，
 * 静态字段是即时的 —— 用户点完开关，界面立刻能读到「服务到底起没起来、为什么没起来」，
 * 不用等落盘再读回来。（原来的代码把启动异常全吞了，所以界面只能显示一句「连不上」，没法排查。）
 *
 * 时间戳那几个字段是 issue #7 加的：用户反馈「App 不在前台时微信侧连不上代理」，
 * 而当时界面只能说「服务在跑」—— 分不清是「一直好好跑着」还是「刚被系统杀掉又被拉起来」。
 * 有了 [startedAt] / [lastRequestAt] / [restarts] 之后，这三件事一眼可辨：
 * - 服务是新起的（[startedAt] 才几秒）→ 之前那次多半被系统回收了；
 * - 从来没人来过（[lastRequestAt] == 0）→ 微信侧压根没走代理，问题不在服务端；
 * - [restarts] > 0 → 进程被杀过，系统按 START_STICKY 又把它拉回来了。
 */
object ProxyState {
    @Volatile
    var running: Boolean = false

    @Volatile
    var port: Int = 0

    /** 启动 / 绑定失败的原因（给设置页显示）。 */
    @Volatile
    var lastError: String? = null

    /** 最近一次转发结论：HTTP 状态 + 耗时。 */
    @Volatile
    var lastResult: String? = null

    /** 监听起来的时刻（0 = 没起来）。设置页用它显示「已运行 xx 分钟」。 */
    @Volatile
    var startedAt: Long = 0L

    /** 关掉的时刻（0 = 这一段还没关过）。 */
    @Volatile
    var stoppedAt: Long = 0L

    /**
     * 最近一次真的接到请求的时刻（0 = 这一段从来没被请求过）。
     *
     * 「没被请求过」是很有信息量的一格：它说明**服务端没问题，是微信那边没往这儿发** ——
     * 排查方向完全不同（前者看保活，后者看注入侧的路由判断）。
     */
    @Volatile
    var lastRequestAt: Long = 0L

    /**
     * 前台服务有没有真的挂上。
     *
     * false = 代理只在进程里跑着，系统随时可能连进程一起回收它 —— 这正是 issue #7 里
     * 「App 一退到后台就连不上」的土壤，所以界面上要明说，并引导用户去加电池白名单。
     */
    @Volatile
    var foreground: Boolean = false

    /** 服务被系统重启过几次（> 0 说明进程被杀过，系统按 START_STICKY 又把它拉回来了）。 */
    @Volatile
    var restarts: Int = 0

    /** 把「上一段跑了多久」抹掉，准备开新的一段。 */
    fun markStarted(port: Int) {
        running = true
        this.port = port
        startedAt = System.currentTimeMillis()
        stoppedAt = 0L
        lastRequestAt = 0L
        lastError = null
    }

    fun markStopped() {
        running = false
        startedAt = 0L
        stoppedAt = System.currentTimeMillis()
    }
}
