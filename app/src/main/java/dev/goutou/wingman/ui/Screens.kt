package dev.goutou.wingman.ui

import android.content.Intent
import androidx.activity.compose.BackHandler
import androidx.compose.runtime.DisposableEffect
import androidx.compose.material.icons.outlined.ChatBubbleOutline
import androidx.compose.material.icons.outlined.Tune
import androidx.compose.material.icons.outlined.BugReport
import androidx.compose.material.icons.outlined.Speed
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.tween
import androidx.compose.animation.expandVertically
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.shrinkVertically
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.clickable
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.ExpandMore
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Checkbox
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.Icon
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Slider
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Brush
import dev.goutou.wingman.llm.ApiShape
import dev.goutou.wingman.llm.PROVIDER_PRESETS
import dev.goutou.wingman.llm.presetOf
import dev.goutou.wingman.llm.shapeOf
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.draw.rotate
import androidx.compose.ui.platform.LocalClipboardManager
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.TextUnit
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.compose.LifecycleEventEffect
import androidx.compose.ui.unit.sp
import android.Manifest
import android.content.Context
import android.content.pm.PackageManager
import android.os.Build
import dev.goutou.wingman.config.ConfigData
import dev.goutou.wingman.config.Backup
import dev.goutou.wingman.config.ConfigStore
import dev.goutou.wingman.config.DiagExport
import androidx.core.content.ContextCompat
import dev.goutou.wingman.proxy.ProxyProtocol
import dev.goutou.wingman.proxy.ProxyState
import dev.goutou.wingman.proxy.ProxyService
import dev.goutou.wingman.config.GLASS_QUALITY_AUTO
import dev.goutou.wingman.config.GLASS_QUALITY_HIGH
import dev.goutou.wingman.config.GLASS_QUALITY_LOW
import dev.goutou.wingman.config.Role
import dev.goutou.wingman.config.Roles
import dev.goutou.wingman.llm.BUILT_IN_SKILLS
import dev.goutou.wingman.llm.Geo
import dev.goutou.wingman.llm.LlmClient
import dev.goutou.wingman.llm.LlmException
import dev.goutou.wingman.llm.NetInfo
import dev.goutou.wingman.llm.RemoteSkill
import dev.goutou.wingman.llm.Graded
import dev.goutou.wingman.llm.REWRITE_PRESETS
import dev.goutou.wingman.llm.Suggestion
import dev.goutou.wingman.llm.ModelList
import dev.goutou.wingman.llm.gradedWaitMs
import dev.goutou.wingman.llm.isReplyTooLong
import dev.goutou.wingman.wechat.ChatMsg
import dev.goutou.wingman.wechat.Sensitive
import dev.goutou.wingman.wechat.filterNames
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import dev.goutou.wingman.config.SELF_ROLE_KEY
import dev.goutou.wingman.llm.StyleSkill
import dev.goutou.wingman.style.SelfStyle
import kotlin.math.roundToInt

private enum class Level(val label: String) { OK("通过"), WARN("待确认"), BAD("有问题") }

private data class Check(val title: String, val desc: String, val level: Level, val onClick: (() -> Unit)? = null)

/**
 * 状态等级 → **图形档**状态色（8dp 点 / hero 那个圈 / 卡片描边）。
 *
 * 批 4c：状态色只有这一个出口，别再各写一份 when。
 * 第 7 版又拆了一档：浅色下当**文字**用对比度不够（warn 只有 2.2:1），所以图形用 [Palette.okMark]
 * 这一组，文字用 [levelText] 那一组（更深）。
 */
private fun levelColor(palette: Palette, level: Level): Color = when (level) {
    Level.OK -> palette.okMark
    Level.WARN -> palette.warnMark
    Level.BAD -> palette.badMark
}

/** 状态等级 → **文字档**状态色（正文里那行说明）。 */
private fun levelText(palette: Palette, level: Level): Color = when (level) {
    Level.OK -> palette.ok
    Level.WARN -> palette.warn
    Level.BAD -> palette.bad
}

/**
 * 页面顶栏上的小按钮（返回 / 刷新）。
 *
 * 以前这两个动作是纯文字（TextButton），看着像标题的一部分、点起来也没有「按钮」的反馈；
 * 现在统一给它们一个描边框 —— 一眼能看出是能点的东西。
 */
/**
 * 会折叠的玻璃卡片：标题行一直在（带一行「现在是什么」的摘要），点一下才展开内容。
 *
 * 和「运行状态」页那个 [DetailToggle] 是同一套观感，区别是这里直接包成一张卡片 ——
 * 页面上那些「配一次就不常看」的大块内容用它，页面能短一大截。
 */
/**
 * 长说明的折叠显示 —— 只做呈现，不参与任何逻辑。
 *
 * 默认最多显示 [collapsedLines] 行；**只有真的被截断**（hasVisualOverflow）
 * 才在下面给一个「展开 / 收起」。短文案不会多出任何按钮，
 * 所以调大调小 [collapsedLines] 只影响观感，不影响功能。
 */
@Composable
private fun HintText(text: String, color: Color, modifier: Modifier = Modifier, fontSize: TextUnit = 13.sp) {
    Text(text, modifier = modifier, fontSize = fontSize, color = color)
}

/** 一行「标题 + 值」的网络信息。值取不到就显示 null —— 不编。 */
@Composable
private fun NetRow(label: String, value: String?, hint: String? = null) {
    val palette = LocalPalette.current
    val missing = value.isNullOrBlank()
    Row(Modifier.fillMaxWidth().padding(vertical = 3.dp), verticalAlignment = Alignment.CenterVertically) {
        Text(label, fontSize = 13.sp, color = palette.sub, modifier = Modifier.width(76.dp))
        Column(Modifier.weight(1f)) {
            Text(value ?: "null", fontSize = 13.sp, color = if (missing) palette.warn else palette.text)
            if (!hint.isNullOrBlank()) Text(hint, fontSize = 10.sp, color = palette.sub)
        }
    }
}

/** 把「IP · 省份 · 运营商」拼成一行；全空返回 null（UI 那边显示 null）。 */
private fun joinInfo(vararg parts: String?): String? =
    parts.filter { !it.isNullOrBlank() }.joinToString(" · ").ifBlank { null }

@Composable
private fun HeaderButton(text: String, onClick: () -> Unit) {
    val palette = LocalPalette.current
    OutlinedButton(
        onClick = onClick,
        modifier = Modifier.height(36.dp),
        shape = RoundedCornerShape(RadiusR1),
        contentPadding = PaddingValues(horizontal = 14.dp, vertical = 0.dp),
    ) { Text(text, fontSize = 13.sp, color = palette.primary) }
}

@Composable
fun StatusScreen(store: ConfigStore, onTrial: () -> Unit, onConnection: () -> Unit = {}, onDiag: () -> Unit = {}) {
    val palette = LocalPalette.current
    val context = LocalContext.current
    var tick by remember { mutableStateOf(0) }
    var cfg by remember { mutableStateOf(store.load()) }
    val usage = remember(tick) { store.usage() }
    val beat = remember(tick) { store.heartbeatAt() }
    val probeReqAt = remember(tick) { store.probeAt() }
    val probeAckAt = remember(tick) { store.probeAck() }
    val probeWhere = remember(tick) { store.probeInfo() }
    var probeVerdict by remember { mutableStateOf<String?>(null) }
    var probeDetail by remember { mutableStateOf<String?>(null) }
    var probeOk by remember { mutableStateOf(false) }
    var probing by remember { mutableStateOf(false) }
    /** 「详细状态」展开了没有。默认收起：这一页只需要一眼看健康。 */
    val scope = rememberCoroutineScope()
    val glass = cfg.glassAlpha
    val active = moduleActive(store)
    val fresh = beat > 0 && System.currentTimeMillis() - beat < 6 * 3600_000L
    val confirmed = store.scopeConfirmed()

    // 实时探测的结论（问题 5）：模块「现在还在不在」只认这条，心跳时间戳退居「历史」。
    val nowMs = System.currentTimeMillis()
    // 回执里带着请求号（「#<那次请求的时间戳>」）：只有**号和当前这一轮对得上**才算「这一轮被回答了」。
    // 否则新建的面板会去答一个很久以前的旧请求，界面会误报「刚刚回过话」。
    val ackId = probeWhere.removePrefix("#").substringBefore(' ').toLongOrNull() ?: 0L
    val answered = probeReqAt > 0 && ackId == probeReqAt
    val whereText = probeWhere.substringAfter(" · ", "").ifBlank { probeWhere }

    val liveText: String
    val liveLevel: Level
    when {
        answered -> {
            val secs = (nowMs - probeAckAt) / 1000
            liveText = "刚刚回过话：${secs} 秒前" + if (whereText.isBlank()) "" else " · $whereText"
            liveLevel = if (nowMs - probeAckAt < 120_000L) Level.OK else Level.WARN
        }
        probeReqAt > 0 && nowMs - probeReqAt < 10_000L -> {
            liveText = "已发出探测，正在等微信进程回话…（要微信在运行）"
            liveLevel = Level.WARN
        }
        probeReqAt > 0 -> {
            liveText = "探测没有回应（${formatTime(probeReqAt)}）—— 确认微信在运行、作用域勾了微信，再强杀微信重开；也可以点这一行重试"
            liveLevel = Level.BAD
        }
        else -> {
            liveText = "还没探测过 —— 点这一行探测一下（会在微信进程里跑一轮再回话）"
            liveLevel = Level.WARN
        }
    }

    // 进这一页自动探一次；等回话的那十几秒里每秒刷一下。
    // 有界轮询（repeat 12 次）—— 无限协程会把截图回归测试挂住（这个坑踩过）。
    LaunchedEffect(Unit) {
        store.requestProbe()
        tick++
    }
    LaunchedEffect(answered) {
        if (!answered) repeat(12) {
            kotlinx.coroutines.delay(1000)
            tick++
        }
    }

    val checks = listOf(
        Check(
            "模块激活",
            moduleActiveReason(store) ?: "未激活：在框架里启用本模块并勾选微信，然后强杀微信重开",
            if (active) Level.OK else Level.BAD,
        ),
        Check(
            "模块是否在跑（实时探测）",
            liveText,
            liveLevel,
        ) {
            // 点这一行 = 重新探一次
            store.requestProbe()
            tick++
        },
        Check(
            "微信内已生效（历史）",
            when {
                fresh -> "微信进程最近报过心跳：${formatTime(beat)}"
                confirmed -> "你手动确认过（还没收到心跳）"
                else -> "没收到微信进程心跳：确认作用域勾了微信并强杀重开"
            },
            if (fresh) Level.OK else Level.WARN,
        ) {
            store.setScopeConfirmed(!confirmed)
            cfg = store.load()
        },
        Check(
            "API Key",
            if (cfg.apiKey.isBlank()) "未填写，到「设置」填" else "已填写（明文存本机，见设置页说明）",
            if (cfg.apiKey.isBlank()) Level.BAD else Level.OK,
        ),
        Check(
            "接口与模型",
            if (cfg.graded) {
                "${cfg.model}（写回复）+ ${cfg.riskEndpoint().model}（风险） · 分级模式"
            } else {
                "${cfg.model} · ${cfg.baseUrl}"
            },
            if (cfg.baseUrl.startsWith("http")) Level.OK else Level.BAD,
        ),
        Check(
            "当前军师",
            "${skillName(cfg.skillId)}" +
                " · ${cfg.prompt.length} 字 · " + if (cfg.prompt.contains("replies")) "含 JSON 契约" else "缺少 JSON 契约",
            if (cfg.prompt.length >= 80 && cfg.prompt.contains("replies")) Level.OK else Level.WARN,
        ),
        Check(
            "调用节奏",
            "参考最近 ${cfg.ctx} 条 · 最短间隔 ${cfg.minIntervalSec}s · " +
                if (cfg.maxTokens == 0) "token 无限制" else "单次上限 ${cfg.maxTokens} token",
            Level.OK,
        ),
        Check(
            "敏感内容检查",
            if (cfg.allowSensitive) "已关闭：聊天内容会直接发给接口" else "开启：命中验证码/银行卡/转账等会先问一次",
            if (cfg.allowSensitive) Level.WARN else Level.OK,
        ),
    )
    val bad = checks.count { it.level == Level.BAD }
    val warn = checks.count { it.level == Level.WARN }
    val ok = checks.count { it.level == Level.OK }
    val overall = if (bad > 0) Level.BAD else if (warn > 0) Level.WARN else Level.OK
    // 明细按「**异常优先**」排：有问题 → 待确认 → 通过（同档内保持原来的顺序 —— Kotlin 的 sortedBy 是稳定排序）。
    // 以前固定按声明顺序铺开，出问题时最该动的那一条可能排在第 5 位，一眼看不出该先处理哪个。
    val ordered = checks.sortedBy {
        when (it.level) {
            Level.BAD -> 0
            Level.WARN -> 1
            Level.OK -> 2
        }
    }
    fun runProbe() {
    probing = true
    probeVerdict = null
    probeDetail = null
    val conf = store.load()
    scope.launch {
        var verdict: String
        var detail: String? = null
        try {
            val r = withContext(Dispatchers.IO) { LlmClient(conf).probe() }
            if (r.totalTokens > 0) store.addUsage(r.totalTokens)
            probeOk = true
            verdict = "通 · 往返 ${r.totalMs}ms · 模型 ${r.model} · ${r.totalTokens} token"
            val sb = StringBuilder()
            sb.append("写回复一路：连接 ")
                .append(r.connectMs?.let { "${it}ms" } ?: "未测得")
                .append(" · DNS ${r.dnsMs}ms · 往返 ${r.totalMs}ms")
            r.targetIp?.let { sb.append(" · 目标 ").append(it) }

            // 分级模式：第二套接口也得单独测 —— 它可能是完全不同的服务商。
            // 两路的延迟要分别列出来；慢的那一路决定整张卡片什么时候能出来。
            var riskMs = 0L
            if (conf.graded) {
                try {
                    val r2 = withContext(Dispatchers.IO) {
                        LlmClient(conf.riskEndpoint(), second = true).probe()
                    }
                    if (r2.totalTokens > 0) store.addUsage(r2.totalTokens)
                    riskMs = r2.totalMs
                    sb.append("\n风险一路：连接 ")
                        .append(r2.connectMs?.let { "${it}ms" } ?: "未测得")
                        .append(" · DNS ${r2.dnsMs}ms · 往返 ${r2.totalMs}ms")
                    r2.targetIp?.let { sb.append(" · 目标 ").append(it) }
                    val diff = kotlin.math.abs(r.totalMs - r2.totalMs)
                    if (diff > 300) {
                        sb.append("\n两路差 ${diff}ms —— 快的那路要等慢的那路（自检只量最小请求，实际生成更久）")
                    }
                } catch (t: Throwable) {
                    sb.append("\n风险一路：失败 —— ").append(t.message)
                }
            }
            detail = sb.toString()
            // 记下来：设置页的公告栏要显示，运行时「两路对齐等待」也用它
            store.saveProbeMs(r.totalMs, riskMs)
        } catch (t: Throwable) {
            probeOk = false
            verdict = "失败：${t.message}"
            detail = (t as? LlmException)?.hint
        }
        // 让归属地跟着这次自检重新查一遍
        NetInfo.clearGeoCache()
        tick++
        probeVerdict = verdict
        probeDetail = detail
        probing = false
    }
    }
    HyperPage("TalkTact", actions = {
        TextButton(onClick = { cfg = store.load(); store.requestProbe(); tick++ }) { Text("刷新") }
    }) {
        Row(Modifier.fillMaxWidth().padding(horizontal = 16.dp), horizontalArrangement = Arrangement.spacedBy(12.dp)) {
            Column(
                Modifier.weight(1f).height(176.dp).clip(RoundedCornerShape(24.dp))
                    .background(levelColor(palette, if (active) Level.OK else Level.BAD).copy(alpha = if (palette.dark) 0.22f else 0.14f))
                    .padding(18.dp),
            ) {
                Text(if (active) "模块已激活" else "模块未激活", fontSize = 21.sp, fontWeight = FontWeight.SemiBold, color = palette.text)
                Text(if (answered) "微信已响应" else "等待微信回执", fontSize = 13.sp, color = palette.sub)
                Spacer(Modifier.weight(1f))
                Text(if (active) "✓" else "!", fontSize = 48.sp, fontWeight = FontWeight.Medium, color = levelText(palette, if (active) Level.OK else Level.BAD), modifier = Modifier.align(Alignment.End))
            }
            Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(12.dp)) {
                DashboardMetric("调用次数", "${usage.first}", Modifier.height(82.dp))
                DashboardMetric("累计 token", "${usage.second}", Modifier.height(82.dp))
            }
        }
        HyperGroup("快捷操作") {
            HyperRow("开始试聊", "预览候选回复", Icons.Outlined.ChatBubbleOutline, onClick = onTrial)
            HyperRow("接口与模型", cfg.model.ifBlank { "尚未配置" }, Icons.Outlined.Tune, onClick = onConnection)
            HyperRow("诊断与日志", "界面快照、请求和决策轨迹", Icons.Outlined.BugReport, onClick = onDiag)
        }
        HyperGroup("接口自检") {
            HyperRow("连接测试", if (cfg.graded) "分别测试回复和风险接口" else "发送最小请求，测量连通性与延迟", Icons.Outlined.Speed, trailing = {
                TextButton(onClick = { runProbe() }, enabled = !probing) { Text(if (probing) "测试中…" else "开始自检") }
            })
            if (probeVerdict != null) Text(probeVerdict.orEmpty(), Modifier.padding(horizontal = 20.dp, vertical = 8.dp), fontSize = 13.sp, color = if (probeOk) palette.ok else palette.bad)
            if (probeDetail != null) Text(probeDetail.orEmpty(), Modifier.padding(horizontal = 20.dp, vertical = 8.dp), fontSize = 13.sp, color = palette.sub)
        }
        HyperGroup("运行检查 · $ok 通过 / $warn 待确认 / $bad 异常") {
            ordered.forEach { check ->
                HyperRow(
                    check.title,
                    check.desc.substringBefore(" —— ").take(130),
                    onClick = check.onClick ?: if (check.title == "API Key" || check.title == "接口与模型") onConnection else null,
                    trailing = { Text(check.level.label, fontSize = 12.sp, color = levelText(palette, check.level)) },
                )
            }
            HyperRow("最近心跳", if (beat > 0) formatTime(beat) else "尚未收到")
        }
        HyperGroup("网络信息") {
            Column(Modifier.padding(horizontal = 20.dp, vertical = 12.dp)) {
                val baseUrl = cfg.baseUrl
                val host = remember(baseUrl) { NetInfo.hostOf(baseUrl) }
                // 内网 IP / 目标 IP：本地或 DNS 就能拿到，不依赖任何第三方
                val localIp by produceState<String?>(null, tick) {
                    value = withContext(Dispatchers.IO) { NetInfo.localIpv4() }
                }
                val targetIp by produceState<String?>(null, tick) {
                    value = withContext(Dispatchers.IO) { NetInfo.resolve(host) }
                }
                // 归属地：要问第三方，拿不到就 null（缓存 10 分钟；点「开始自检」会强制重查）。
                // 关掉开关就一次都不问；地址也能换成自己的（见「高级设置 → 诊断 → 网络信息」）。
                val geoUrl = remember(cfg.geoEndpoint) { cfg.geoEndpoint.trim().ifBlank { NetInfo.GEO_ENDPOINT } }
                val localGeo by produceState<Geo?>(null, tick, cfg.geoEnabled, geoUrl) {
                    value = if (!cfg.geoEnabled) null
                    else withContext(Dispatchers.IO) { NetInfo.geo(geoUrl) }
                }
                val targetGeo by produceState<Geo?>(null, tick, targetIp, cfg.geoEnabled, geoUrl) {
                    value = if (!cfg.geoEnabled || targetIp == null) null
                    else withContext(Dispatchers.IO) { NetInfo.geo(geoUrl, ip = targetIp) }
                }

                Spacer(Modifier.height(10.dp))
                NetRow("本机内网", localIp)
                NetRow(
                    "本机公网",
                    if (!cfg.geoEnabled) "（归属地查询已关闭）"
                    else joinInfo(localGeo?.ip, localGeo?.province ?: localGeo?.country, localGeo?.isp),
                )
                NetRow("目标服务器", joinInfo(targetIp, targetGeo?.province ?: targetGeo?.country), host)
                Spacer(Modifier.height(4.dp))
                HintText(
                    if (!cfg.geoEnabled) "归属地查询已关闭。"
                    else "归属地来自第三方，可在诊断页关闭。",
                    fontSize = 10.sp,
                    color = palette.sub,
                )

                Spacer(Modifier.height(10.dp))
            }
        }
    }
}

@Composable
private fun DashboardMetric(label: String, value: String, modifier: Modifier = Modifier) {
    val palette = LocalPalette.current
    Column(modifier.fillMaxWidth().clip(RoundedCornerShape(24.dp)).background(palette.cardBg).padding(horizontal = 16.dp, vertical = 12.dp)) {
        Text(label, fontSize = 13.sp, color = palette.sub)
        Text(value, fontSize = 23.sp, fontWeight = FontWeight.Medium, color = palette.text)
    }
}

// ================= 高级设置（设置 → 二级页） =================

/**
 * 高级设置的分组标题：12sp、次级色、上面留 24dp 的呼吸位。
 *
 * 九张卡平铺时是一串并列名词，扫视没有动线；加上「接入 / 生成 / 识别 / 微信内 / 维护与排障」
 * 之后，看到的是五个词而不是九个名词，也更不容易把「要配的」和「排障用的」混在一起。
 * 左对齐到卡片外沿再往右 4dp，跟卡内文字错开半格 —— 看起来是「标题」而不是「正文」。
 */
@Composable
private fun GroupTitle(text: String) {
    val palette = LocalPalette.current
    Text(
        text,
        modifier = Modifier.padding(start = 18.dp, top = 18.dp, bottom = 3.dp),
        fontSize = 13.sp,
        fontWeight = FontWeight.Medium,
        color = palette.sub.copy(alpha = 0.75f),
        letterSpacing = 0.8.sp,
    )
}

/**
 * 来源小标签：一眼看出这份提示词是「内置」的还是「导入 / 自定义」的。
 * 只做染色 + 一个词，不占语义 —— 「现在到底生效的是哪一份」由旁边的名字负责。
 */
@Composable
private fun SourceChip(skillId: String) {
    // 形态统一交给 StatusChip（chip 底色 alpha 0.14 + 1dp 描边 + 13sp 字），别再手搓一份
    StatusChip(if (skillId == "custom") "导入 / 自定义" else "内置", LocalPalette.current.primary)
}

/**
 * 设置 → 高级设置。
 *
 * 从设置首页搬过来的四块：接口地址、微信内自动分析（含敏感检查）、备份 / 迁移、诊断入口。
 * 它们的共同点是「配一次就不常动」，所以单独一层；诊断再往里一层（三级）。
 * 「会话白名单」卡里的候选人名单同理 —— 拉回来的联系人放 [ChatCandidatesScreen]（也是三级）。
 */
@Composable
fun AdvancedScreen(
    store: ConfigStore,
    ui: ConfigData,
    onSaved: () -> Unit,
    onOpenDiag: () -> Unit,
    onOpenCandidates: () -> Unit,
    onBack: () -> Unit,
    section: SettingsSection = SettingsSection.ALL,
    onDraftState: (Boolean, () -> Unit) -> Unit = { _, _ -> },
) {
    val palette = LocalPalette.current
    val context = LocalContext.current
    var d by remember { mutableStateOf(ui) }
    var includeKey by remember { mutableStateOf(false) }
    var backupNote by remember { mutableStateOf<String?>(null) }
    val scope = rememberCoroutineScope()
    // 「拉取模型列表」：拉回来存本机（列表 + 时间），进页面读缓存；手动输入永远保留
    val cachedModels = remember { store.modelList() }
    var models by remember { mutableStateOf(cachedModels.first) }
    var modelsAt by remember { mutableStateOf(cachedModels.second) }
    var modelsOpen by remember { mutableStateOf(false) }
    var modelsNote by remember { mutableStateOf<String?>(null) }
    var pulling by remember { mutableStateOf(false) }
    // 第二套接口（分级模式那一跳）的模型列表：跟第一套各存各的
    val cachedModels2 = remember { store.modelList(second = true) }
    var models2 by remember { mutableStateOf(cachedModels2.first) }
    var models2At by remember { mutableStateOf(cachedModels2.second) }
    var models2Open by remember { mutableStateOf(false) }
    var modelsNote2 by remember { mutableStateOf<String?>(null) }
    var pulling2 by remember { mutableStateOf(false) }
    // 「有没有还没点保存的改动」：拿草稿和**上次落盘的那份**比。
    // 不能只看一个「保存过没有」的标志 —— 它一开始就是「没保存过」，
    // 会把刚打开的页面也说成「未保存」，红字就废了。
    var persisted by remember { mutableStateOf(ui) }
    val dirty = d != persisted

    fun update(next: ConfigData) {
        d = next
    }

    fun saveAll() {
        store.save(d)
        d = store.load()
        persisted = d
        onSaved()
    }

    /** 疑似非对话模型（embedding / tts 这些）排到后面 —— 只排序，不隐藏。 */
    fun orderedModels(list: List<String>): List<String> =
        list.filterNot { ModelList.looksNonChat(it) } + list.filter { ModelList.looksNonChat(it) }

    /**
     * 拉一次模型列表。用的是**当前草稿**里的地址与 Key（可能还没保存）——
     * 「填完 Key 先试一下能不能拉」不用先按保存。失败也不清空手里那份：服务商偶尔抽风。
     */
    fun pullModels(second: Boolean = false) {
        if (second) {
            pulling2 = true
            modelsNote2 = null
        } else {
            pulling = true
            modelsNote = null
        }
        // 第二套留空 = 复用第一套（和「生成模式」里那三格的规矩一致）
        val base = if (second) d.baseUrl2.trim().ifBlank { d.baseUrl } else d.baseUrl
        val key = if (second) d.apiKey2.trim().ifBlank { d.apiKey } else d.apiKey
        scope.launch {
            val got = withContext(Dispatchers.IO) { runCatching { ModelList.fetch(base, key) } }
            got.onSuccess { list ->
                val note = if (list.isEmpty()) "接口通了，但没解析出模型名 —— 手动填吧" else "拉到 ${list.size} 个模型"
                if (second) {
                    models2 = list
                    models2At = System.currentTimeMillis()
                    modelsNote2 = note
                } else {
                    models = list
                    modelsAt = System.currentTimeMillis()
                    modelsNote = note
                }
                store.saveModelList(list, second)
            }.onFailure { t ->
                if (second) modelsNote2 = "拉取失败：${t.message}" else modelsNote = "拉取失败：${t.message}"
            }
            if (second) pulling2 = false else pulling = false
        }
    }

    /**
     * 白名单的开关 / 添加 / 移除都**立刻落盘**（跟「本地代理」那个开关同一个道理）。
     *
     * 这张卡是全页唯一没有自己「保存」按钮的卡 —— 只改草稿 d 的话，注入侧读到的还是旧值，
     * 表现出来就是「白名单明明开了，没加的聊天照样被分析」（用户实测踩到，还以为是缓存）。
     * 只写这两项（`ConfigStore.saveWhitelist`）：这页还有别的卡在编辑、还没点保存，
     * 不能顺手把它们一起定死。二级页也要用同一份写法，所以这件事放在 store 上。
     */
    fun saveWhitelist(enabled: Boolean, chats: Set<String>) {
        store.saveWhitelist(enabled, chats)
        val fresh = store.load()
        // 只把这两项同步回草稿 —— 别的地方可能还有没保存的编辑，不能整份覆盖
        d = d.copy(whitelistEnabled = fresh.whitelistEnabled, whitelist = fresh.whitelist)
        persisted = fresh
        onSaved()
    }

    /**
     * 「图片文字识别」开关也**立刻落盘** —— 和它上面那张「本地代理」卡连着用：
     * 代理开着、识图关着，效果是白装十几 MB；两个开关的行为一致，才不会让人以为坏了。
     */
    fun saveOcr(on: Boolean) {
        store.saveOcr(on)
        val fresh = store.load()
        d = d.copy(ocrEnabled = fresh.ocrEnabled)
        persisted = fresh
        onSaved()
    }

    /**
     * 「生成模式」两个 pill 也**立刻落盘**（原因见 `ConfigStore.saveGraded`）。
     * 只把这一项同步回草稿与「上次落盘的那份」—— 别的卡可能还在编辑、还没点保存，
     * 不能整份覆盖，也不能让假红字亮起来。
     */
    fun saveGraded(on: Boolean) {
        store.saveGraded(on)
        val fresh = store.load()
        d = d.copy(graded = fresh.graded)
        persisted = fresh
        onSaved()
    }

    val exportLauncher = rememberLauncherForActivityResult(ActivityResultContracts.CreateDocument("application/json")) { uri ->
        if (uri != null) {
            backupNote = try {
                context.contentResolver.openOutputStream(uri)?.use { out ->
                    out.write(Backup.export(store.load(), store.roles(), includeKey).toByteArray(Charsets.UTF_8))
                }
                "已导出到所选文件"
            } catch (t: Throwable) {
                "导出失败：${t.message}"
            }
        }
    }

    val importLauncher = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocument()) { uri ->
        if (uri != null) {
            backupNote = try {
                val text = context.contentResolver.openInputStream(uri)?.bufferedReader()?.use { it.readText() }.orEmpty()
                val result = Backup.import(text, store.load(), store.roles())
                if (result == null) {
                    "导入失败：不是有效的备份文件"
                } else {
                    store.save(result.config)
                    store.saveRoles(result.roles)
                    d = store.load()
                    persisted = d
                    onSaved()
                    "已导入：配置 + ${result.roleCount} 个角色"
                }
            } catch (t: Throwable) {
                "导入失败：${t.message}"
            }
        }
    }

    var diagNote by remember { mutableStateOf<String?>(null) }
    var proxyNote by remember { mutableStateOf<String?>(null) }
    var ocrNote by remember { mutableStateOf<String?>(null) }
    var whitelistNote by remember { mutableStateOf<String?>(null) }
    // 会话名候选是注入侧用广播回传的，这一页不会自己重组 —— 停在这里时每 1.5 秒自己看一眼。
    // （去微信转一圈再回来，看到的就是刚回传的那份。）
    // 这里的第三项是「图片识别」的最近一次结果 —— 它同样是注入侧广播回传的，
    // 借同一次轮询一起读，省掉第二个定时器。
    //
    // ⚠️ 轮询是**有上限**的（下面 repeat(200)，约 5 分钟）。而典型用法恰好是
    // 「打开这一页看一眼 → 去微信里试 → 切回来」，那往往已经超过 5 分钟了 ——
    // 于是页面还停在旧值上（真机反馈过「明明认了字，App 里还是『还没试过』」就是这个）。
    // 所以每次回到前台（ON_RESUME）都重启一轮。
    val resumed = resumedTick()
    val pulled by produceState(
        initialValue = Triple(store.chatCandidates(), store.chatPullInfo(), store.ocrInfo()),
        key1 = resumed,
    ) {
        // 轮询**有上限**（约 5 分钟）：一个是别让这个页面永远挂着定时任务，
        // 另一个是单元测试里跑渲染回归时，无限循环的协程可能把测试挂住 —— 有界就没有这种风险。
        repeat(200) {
            kotlinx.coroutines.delay(1500)
            value = Triple(store.chatCandidates(), store.chatPullInfo(), store.ocrInfo())
        }
    }
    val cands = pulled.first
    val reqAt = store.chatRequestAt()
    // 拿「我什么时候点的」和「微信侧什么时候回的」比 —— 这两件事分开显示，
    // 才能一眼看出是「请求没送到」还是「送到了但没读出名字」（排查方向完全相反）
    val pullInfo = pulled.second.let { (info, at) ->
        when {
            at > reqAt -> info.ifBlank { "已收到回传" } + " · " + clockText(at)
            reqAt > 0L -> "已请求 ${clockText(reqAt)} · 微信侧还没回过话"
            else -> "还没拉过"
        }
    }
    val ocrStatus = pulled.third.let { (info, at) ->
        val head = info.ifBlank { "还没试过（微信里遇到图片消息才会有记录）" }
        if (at > 0L) "$head · ${clockText(at)}" else head
    }
    var newChat by remember { mutableStateOf("") }
    // 白名单的「长按多选」状态。
    // （候选名单那一份跟着名单一起搬到二级页了，见 ChatCandidatesScreen —— 那边自己持有一份。）
    var whitePicking by remember { mutableStateOf(false) }
    var whitePicked by remember { mutableStateOf(emptySet<String>()) }
    // 被「删掉」的候选个数（记在「已忽略」里）—— 界面靠它显示「恢复」
    var ignoredCount by remember { mutableStateOf(store.chatIgnored().size) }
    val proxyScope = rememberCoroutineScope()
    // 13+ 才有的通知权限。注意：前台服务**没有**它也照样能跑（系统仍会显示这条常驻通知），
    // 但既然要弹，就在用户打开开关时顺手要一下 —— 不然以后那条通知可能被折叠/静音。
    val notifPermission = rememberLauncherForActivityResult(
        ActivityResultContracts.RequestPermission(),
    ) { granted ->
        proxyNote = if (granted) "已授予通知权限" else "没给通知权限 —— 代理照跑，但系统更容易把它回收"
    }
    val diagLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.CreateDocument("application/zip"),
    ) { uri ->
        if (uri != null) {
            diagNote = try {
                context.contentResolver.openOutputStream(uri)?.use { it.write(buildDiagZip(context, store, d)) }
                "已导出诊断包 —— 发之前建议先打开看一眼"
            } catch (t: Throwable) {
                "导出失败：${t.message}"
            }
        }
    }

    var pendingExit by remember { mutableStateOf<(() -> Unit)?>(null) }
    fun leave(action: () -> Unit) { if (dirty) pendingExit = action else action() }
    LaunchedEffect(dirty) { onDraftState(dirty) { saveAll() } }
    DisposableEffect(Unit) { onDispose { onDraftState(false) {} } }
    HyperPage(section.title, onBack = { leave(onBack) }, actions = {
        TextButton(onClick = { saveAll() }, enabled = dirty) { Text(if (dirty) "保存" else "已保存") }
    }) {
        if (section == SettingsSection.ALL || section == SettingsSection.CONNECTION) {
            GlassCard(d.glassAlpha) {
                // 公告栏放最上面：换接口/换模型最该先知道的就是「这俩大概要等多久」
                val (replyMs, riskMs) = store.probeMs()
                Text("自检延迟：回复 ${if (replyMs > 0) "${replyMs}ms" else "未测试"}" + if (d.graded) " · 风险 ${if (riskMs > 0) "${riskMs}ms" else "未测试"}" else "", fontSize = 13.sp, color = palette.sub)
                Spacer(Modifier.height(10.dp))
                Text("接口地址", fontSize = 17.sp, fontWeight = FontWeight.SemiBold, color = palette.text)
                Spacer(Modifier.height(8.dp))

                // 服务商预设：**只省掉「去官网查地址」这一步**。选中后地址 / 模型照样能手改，
                // 也不改变任何请求逻辑 —— 我们本来就是「OpenAI 兼容 + 自定义 base_url + 自定义 model」。
                val chosenPreset = presetOf(d.provider)
                var providerOpen by remember { mutableStateOf(false) }
                Column {
                    Text("服务商", fontSize = 13.sp, color = palette.sub)
                    Spacer(Modifier.height(6.dp))
                    Box(Modifier.fillMaxWidth()) {
                        OutlinedButton(
                            modifier = Modifier.fillMaxWidth(),
                            onClick = { providerOpen = true },
                            shape = RoundedCornerShape(RadiusR1),
                        ) { Text(chosenPreset?.label ?: "自定义（自己填）", fontSize = 13.sp) }
                        DropdownMenu(expanded = providerOpen, onDismissRequest = { providerOpen = false }) {
                            DropdownMenuItem(
                                text = { Text("自定义（自己填）", fontSize = 13.sp) },
                                onClick = {
                                    providerOpen = false
                                    update(d.copy(provider = ""))
                                },
                            )
                            PROVIDER_PRESETS.forEach { p ->
                                DropdownMenuItem(
                                    text = { Text(p.label, fontSize = 13.sp) },
                                    onClick = {
                                        providerOpen = false
                                        // 选预设 = 把「地址 / 形态 / 模型（有才填）」写进草稿，仍然可改
                                        update(
                                            d.copy(
                                                provider = p.id,
                                                baseUrl = p.baseUrl,
                                                apiShape = p.shape.id,
                                                model = p.model.ifBlank { d.model },
                                            ),
                                        )
                                    },
                                )
                            }
                        }
                    }
                }
                chosenPreset?.note?.let {
                    Spacer(Modifier.height(4.dp))
                    Text(it, fontSize = 13.sp, color = palette.sub)
                }
                Spacer(Modifier.height(10.dp))

                // 接口形态：差异只有 URL 路径 / 请求体 / 鉴权头三处（见 llm/ApiShape.kt）。
                val shapeNow = shapeOf(d.apiShape)
                var shapeOpen by remember { mutableStateOf(false) }
                Column {
                    Text("接口形态", fontSize = 13.sp, color = palette.sub)
                    Spacer(Modifier.height(6.dp))
                    Box(Modifier.fillMaxWidth()) {
                        OutlinedButton(
                            modifier = Modifier.fillMaxWidth(),
                            onClick = { shapeOpen = true },
                            shape = RoundedCornerShape(RadiusR1),
                        ) { Text(shapeNow.label, fontSize = 13.sp) }
                        DropdownMenu(expanded = shapeOpen, onDismissRequest = { shapeOpen = false }) {
                            ApiShape.values().forEach { s ->
                                DropdownMenuItem(
                                    text = { Text(s.label, fontSize = 13.sp) },
                                    onClick = {
                                        shapeOpen = false
                                        update(d.copy(apiShape = s.id))
                                    },
                                )
                            }
                        }
                    }
                }
                Text(shapeNow.note, fontSize = 13.sp, color = palette.sub)
                if (d.proxyEnabled && !shapeNow.usableThroughProxy) {
                    Spacer(Modifier.height(4.dp))
                    Text(
                        "⚠ 本地代理只转发 OpenAI 兼容形态：「${shapeNow.label}」请直连 —— " +
                            "请到「设置 → 识图与代理」关闭代理，或选择支持代理的接口形态。",
                        fontSize = 13.sp,
                        color = palette.warn,
                    )
                }
                if (shapeNow == ApiShape.CUSTOM) {
                    Spacer(Modifier.height(8.dp))
                    OutlinedTextField(
                        colors = glassFieldColors(),
                        shape = RoundedCornerShape(RadiusR2),
                        value = d.customPath,
                        onValueChange = { update(d.copy(customPath = it)) },
                        modifier = Modifier.fillMaxWidth(),
                        label = { Text("路径（留空 = 这个地址本身就是完整端点）") },
                        singleLine = true,
                    )
                }
                Spacer(Modifier.height(10.dp))

                OutlinedTextField(
                    colors = glassFieldColors(),
                    shape = RoundedCornerShape(RadiusR2),
                    value = d.baseUrl,
                    onValueChange = { update(d.copy(baseUrl = it)) },
                    modifier = Modifier.fillMaxWidth(),
                    label = { Text("接口地址（OpenAI 兼容，写到 /v1）") },
                    singleLine = true,
                )
                Spacer(Modifier.height(8.dp))
                OutlinedTextField(
                    colors = glassFieldColors(),
                    shape = RoundedCornerShape(RadiusR2),
                    value = d.apiKey,
                    onValueChange = { update(d.copy(apiKey = it)) },
                    modifier = Modifier.fillMaxWidth(),
                    label = { Text("API Key") },
                    singleLine = true,
                    visualTransformation = PasswordVisualTransformation(),
                )
                Spacer(Modifier.height(8.dp))
                OutlinedTextField(
                    colors = glassFieldColors(),
                    shape = RoundedCornerShape(RadiusR2),
                    value = d.model,
                    onValueChange = { update(d.copy(model = it)) },
                    modifier = Modifier.fillMaxWidth(),
                    label = { Text("模型") },
                    singleLine = true,
                )
                Spacer(Modifier.height(8.dp))
                Row(verticalAlignment = Alignment.CenterVertically) {
                    OutlinedButton(
                        onClick = { pullModels(second = false) },
                        enabled = !pulling,
                        modifier = Modifier.weight(1f),
                        shape = RoundedCornerShape(RadiusR2),
                    ) { Text(if (pulling) "拉取中…" else "获取模型列表") }
                    if (models.isNotEmpty()) {
                        Spacer(Modifier.width(8.dp))
                        Box {
                            OutlinedButton(onClick = { modelsOpen = true }, shape = RoundedCornerShape(RadiusR2)) {
                                Text("选模型（${models.size}）")
                            }
                            DropdownMenu(expanded = modelsOpen, onDismissRequest = { modelsOpen = false }) {
                                orderedModels(models).take(80).forEach { id ->
                                    DropdownMenuItem(
                                        text = { Text(id, fontSize = 13.sp) },
                                        onClick = {
                                            update(d.copy(model = id))
                                            modelsOpen = false
                                        },
                                    )
                                }
                            }
                        }
                    }
                }
                modelsNote?.let {
                    Spacer(Modifier.height(4.dp))
                    Text(it, fontSize = 13.sp, color = if (it.startsWith("拉取失败")) palette.bad else palette.ok)
                }
                if (modelsAt > 0L) {
                    Spacer(Modifier.height(2.dp))
                    Text("列表更新于 ${formatTime(modelsAt)}", fontSize = 10.sp, color = palette.sub)
                }
                Spacer(Modifier.height(4.dp))
                HintText(
                    "不支持模型列表的服务商可直接手动填写。",
                    fontSize = 10.sp,
                    color = palette.sub,
                )
                Spacer(Modifier.height(12.dp))
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Column(Modifier.weight(1f)) {
                        Text("严格 JSON 输出", fontSize = 15.sp, color = palette.text)
                        HintText(
                            "要求服务商返回合法 JSON；接口不支持时请关闭。",
                            fontSize = 13.sp,
                            color = palette.sub,
                        )
                    }
                    Switch(checked = d.jsonMode, onCheckedChange = { update(d.copy(jsonMode = it)) })
                }
                Spacer(Modifier.height(12.dp))
                Text("单次回复的 token 上限", fontSize = 13.sp, color = palette.sub)
                Spacer(Modifier.height(6.dp))
                Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                    GlassPill("200", d.maxTokens == 200, Modifier.weight(1f)) { update(d.copy(maxTokens = 200)) }
                    GlassPill("400", d.maxTokens == 400, Modifier.weight(1f)) { update(d.copy(maxTokens = 400)) }
                    GlassPill("600", d.maxTokens == 600, Modifier.weight(1f)) { update(d.copy(maxTokens = 600)) }
                    GlassPill("无限制", d.maxTokens == 0, Modifier.weight(1f)) { update(d.copy(maxTokens = 0)) }
                }
                Spacer(Modifier.height(6.dp))
                Text(
                    if (d.maxTokens == 0) "由服务端决定回复长度"
                    else "越小越省额度，但 skill 提示词较长时可能被截断",
                    fontSize = 13.sp,
                    color = palette.sub,
                )
            }
        }
        if (section == SettingsSection.ALL || section == SettingsSection.RECOGNITION) {
            GlassCard(d.glassAlpha) {
                Text("图片文字识别（OCR）", fontSize = 17.sp, fontWeight = FontWeight.SemiBold, color = palette.text)
                Text(
                    "图片在本机离线识别，不上传、不落盘。识别的文字会随上下文发送给模型。",
                    fontSize = 13.sp,
                    color = palette.sub,
                )
                Spacer(Modifier.height(8.dp))
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Column(Modifier.weight(1f)) {
                        Text("识别聊天里的图片", fontSize = 15.sp, color = palette.text)
                        Text(
                            if (d.ocrEnabled) {
                                "开着：每轮分析前，屏幕上的图会先认一遍字"
                            } else {
                                "关着：图片还是老样子 —— 用 [图片/表情/语音] 占位"
                            },
                            fontSize = 13.sp,
                            color = palette.sub,
                        )
                    }
                    Switch(
                        checked = d.ocrEnabled,
                        onCheckedChange = { on ->
                            saveOcr(on)
                            ocrNote = if (on) "已开 · 微信里下次识别生效" else "已关 · 图片回落成占位"
                        },
                    )
                }
                if (d.ocrEnabled && !d.proxyEnabled) {
                    Spacer(Modifier.height(6.dp))
                    Text(
                        "识图需要本地代理，请在下方启用。代理不可用时会保留图片占位。",
                        fontSize = 13.sp,
                        color = palette.warn,
                    )
                }
                Spacer(Modifier.height(8.dp))
                Text("最近一次：$ocrStatus", fontSize = 13.sp, color = palette.sub)
                ocrNote?.let {
                    Spacer(Modifier.height(4.dp))
                    Text(it, fontSize = 13.sp, color = palette.ok)
                }
            }
        }
        if (section == SettingsSection.ALL || section == SettingsSection.RECOGNITION) {
            GlassCard(d.glassAlpha) {
                Text("本地代理（API Key 不出本应用）", fontSize = 17.sp, fontWeight = FontWeight.SemiBold, color = palette.text)
                Text(
                    "使用本机代理转发请求，API Key 仅保留在本应用。",
                    fontSize = 13.sp,
                    color = palette.sub,
                )
                Spacer(Modifier.height(8.dp))
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Column(Modifier.weight(1f)) {
                        Text("启用本地代理", fontSize = 15.sp, color = palette.text)
                        Text(
                            if (d.proxyEnabled) {
                                "已开：App 需要保持运行（前台服务 + 一条常驻通知）"
                            } else {
                                "关着：走直连（Key 明文存在本机，并且会推给微信进程）"
                            },
                            fontSize = 13.sp,
                            color = palette.sub,
                        )
                    }
                    Switch(
                        checked = d.proxyEnabled,
                        onCheckedChange = { on ->
                            // 开关立刻落盘：注入侧是按配置里的 proxyEnabled 决定走哪条路的，
                            // 等用户再点一次「保存」的话，这里会有一段「看起来开了其实没开」的空窗。
                            store.save(d.copy(proxyEnabled = on))
                            d = store.load()
                            persisted = d
                            if (on) {
                                store.ensureProxyToken()
                                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU &&
                                    ContextCompat.checkSelfPermission(context, Manifest.permission.POST_NOTIFICATIONS) !=
                                    PackageManager.PERMISSION_GRANTED
                                ) {
                                    notifPermission.launch(Manifest.permission.POST_NOTIFICATIONS)
                                }
                                ProxyService.start(context)
                                proxyNote = "已开启。到微信里试一次识别；如果一直失败，把这里关掉就退回直连。"
                            } else {
                                ProxyService.stop(context)
                                proxyNote = "已关闭：Key 会重新推给微信进程（下次识别生效）。"
                            }
                        },
                    )
                }
                if (d.proxyEnabled) {
                    Spacer(Modifier.height(6.dp))
                    // 状态行直接读进程内的 ProxyState：服务到底起没起来、为什么没起来，一眼可见
                    Text(
                        when {
                            ProxyState.running ->
                                "● 服务在跑：127.0.0.1:${ProxyState.port}" +
                                    (ProxyState.lastResult?.let { " · 最近：$it" } ?: " · 还没被请求过")
                            ProxyState.lastError != null -> "● 服务没起来：${ProxyState.lastError}"
                            else -> "● 服务没起来（点上面的开关关掉再开一次；若仍不行点「测试代理」看详情）"
                        },
                        fontSize = 13.sp,
                        color = if (ProxyState.running) palette.ok else palette.bad,
                    )
                    val (lastRoute, lastRouteAt) = store.lastRoute()
                    if (lastRoute.isNotBlank() && lastRouteAt > 0L) {
                        val ok = lastRoute == ProxyProtocol.ROUTE_PROXY
                        Text(
                            "微信侧最近一次走的是：" + (if (ok) "本地代理 ✅" else "直连（代理还没被用上）") +
                                " · " + java.text.SimpleDateFormat("HH:mm:ss", java.util.Locale.getDefault())
                                    .format(java.util.Date(lastRouteAt)),
                            fontSize = 13.sp,
                            color = if (ok) palette.ok else palette.warn,
                        )
                    } else {
                        Text("尚未收到微信侧的路由记录", fontSize = 13.sp, color = palette.sub)
                    }
                    Spacer(Modifier.height(4.dp))
                    Text(
                        "同屏结果会缓存；可在微信中手动重新识别。",
                        fontSize = 13.sp,
                        color = palette.sub,
                    )
                    Spacer(Modifier.height(8.dp))
                    OutlinedTextField(
                        colors = glassFieldColors(),
                        shape = RoundedCornerShape(RadiusR2),
                        value = d.proxyPort.toString(),
                        onValueChange = { update(d.copy(proxyPort = it.toIntOrNull() ?: d.proxyPort)) },
                        modifier = Modifier.fillMaxWidth(),
                        label = { Text("端口（1024-65535）") },
                        singleLine = true,
                        keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number),
                    )
                    Spacer(Modifier.height(8.dp))
                    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        OutlinedButton(
                            onClick = {
                                proxyNote = "测试中…"
                                val port = d.proxyPort
                                proxyScope.launch {
                                    proxyNote = withContext(Dispatchers.IO) {
                                        runCatching {
                                            val c = (java.net.URL("http://127.0.0.1:$port${ProxyProtocol.PATH_HEALTH}")
                                                .openConnection() as java.net.HttpURLConnection)
                                            c.connectTimeout = 3_000
                                            c.readTimeout = 3_000
                                            val code = c.responseCode
                                            runCatching { c.disconnect() }
                                            code
                                        }.fold(
                                            { code -> if (code == 200) "代理正常（HTTP 200）" else "代理回话：HTTP $code" },
                                            { err ->
                                                "连不上：${err.message}" + (
                                                    ProxyState.lastError?.let { " · 服务端说：$it" }
                                                        ?: " · 服务没起来（把上面的开关关掉再打开一次）"
                                                    )
                                            },
                                        )
                                    }
                                }
                            },
                            modifier = Modifier.weight(1f),
                            shape = RoundedCornerShape(RadiusR2),
                        ) { Text("测试代理") }
                        OutlinedButton(
                            onClick = {
                                store.resetProxyToken()
                                d = store.load()
                                proxyNote = "已换新 token（微信里下次识别生效）"
                            },
                            modifier = Modifier.weight(1f),
                            shape = RoundedCornerShape(RadiusR2),
                        ) { Text("换新 token") }
                    }
                    Spacer(Modifier.height(6.dp))
                    Text(
                        "⚠ 微信进程对 http 明文的策略不归我们管：如果开了之后微信里识别一直失败，" +
                            "把这里关掉就退回直连（注入侧给的报错里也会这么提示）。\n" +
                            "重启手机后代理不会自己回来，打开一次 App 就会自动恢复。",
                        fontSize = 13.sp,
                        color = palette.warn,
                    )
                    Spacer(Modifier.height(4.dp))
                    HintText(
                        "如服务被系统回收，请允许应用后台运行。",
                        fontSize = 13.sp,
                        color = palette.sub,
                    )
                }
                proxyNote?.let {
                    Spacer(Modifier.height(6.dp))
                    Text(it, fontSize = 13.sp, color = palette.sub)
                }
            }
        }
        if (section == SettingsSection.ALL || section == SettingsSection.GENERATION) {
            GlassCard(d.glassAlpha) {
                Text("生成模式", fontSize = 17.sp, fontWeight = FontWeight.SemiBold, color = palette.text)
                Spacer(Modifier.height(8.dp))
                Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                    GlassPill("直通（一套接口）", !d.graded, Modifier.weight(1f)) { saveGraded(false) }
                    GlassPill("模型分级", d.graded, Modifier.weight(1f)) { saveGraded(true) }
                }
                Spacer(Modifier.height(6.dp))
                HintText(
                    if (!d.graded) {
                        "一套接口完成意图、风险与候选回复。"
                    } else {
                        "风险与回复由两路接口并行处理，任一路失败仍保留另一路结果。"
                    },
                    fontSize = 13.sp,
                    color = palette.sub,
                )
                Spacer(Modifier.height(6.dp))
                Text(
                    "模式切换立即生效；接口修改需保存。",
                    fontSize = 13.sp,
                    color = palette.ok,
                )
                if (d.graded) {
                    Spacer(Modifier.height(10.dp))
                    Text("第二套接口（只给「风险评估」那一路用）", fontSize = 13.sp, color = palette.sub)
                    Spacer(Modifier.height(6.dp))
                    OutlinedTextField(
                        colors = glassFieldColors(),
                        shape = RoundedCornerShape(RadiusR2),
                        value = d.baseUrl2,
                        onValueChange = { update(d.copy(baseUrl2 = it)) },
                        modifier = Modifier.fillMaxWidth(),
                        label = { Text("接口地址（留空 = 复用上面那套）") },
                        singleLine = true,
                    )
                    Spacer(Modifier.height(8.dp))
                    OutlinedTextField(
                        colors = glassFieldColors(),
                        shape = RoundedCornerShape(RadiusR2),
                        value = d.apiKey2,
                        onValueChange = { update(d.copy(apiKey2 = it)) },
                        modifier = Modifier.fillMaxWidth(),
                        label = { Text("API Key（留空 = 复用）") },
                        singleLine = true,
                        visualTransformation = PasswordVisualTransformation(),
                    )
                    Spacer(Modifier.height(8.dp))
                    OutlinedTextField(
                        colors = glassFieldColors(),
                        shape = RoundedCornerShape(RadiusR2),
                        value = d.model2,
                        onValueChange = { update(d.copy(model2 = it)) },
                        modifier = Modifier.fillMaxWidth(),
                        label = { Text("模型（留空 = 复用）") },
                        singleLine = true,
                    )
                    Spacer(Modifier.height(8.dp))
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        OutlinedButton(
                            onClick = { pullModels(second = true) },
                            enabled = !pulling2,
                            modifier = Modifier.weight(1f),
                            shape = RoundedCornerShape(RadiusR2),
                        ) { Text(if (pulling2) "拉取中…" else "获取风险模型列表") }
                        if (models2.isNotEmpty()) {
                            Spacer(Modifier.width(8.dp))
                            Box {
                                OutlinedButton(onClick = { models2Open = true }, shape = RoundedCornerShape(RadiusR2)) {
                                    Text("选模型（${models2.size}）")
                                }
                                DropdownMenu(expanded = models2Open, onDismissRequest = { models2Open = false }) {
                                    orderedModels(models2).take(80).forEach { id ->
                                        DropdownMenuItem(
                                            text = { Text(id, fontSize = 13.sp) },
                                            onClick = {
                                                update(d.copy(model2 = id))
                                                models2Open = false
                                            },
                                        )
                                    }
                                }
                            }
                        }
                    }
                    modelsNote2?.let {
                        Spacer(Modifier.height(4.dp))
                        Text(it, fontSize = 13.sp, color = if (it.startsWith("拉取失败")) palette.bad else palette.ok)
                    }
                    if (models2At > 0L) {
                        Spacer(Modifier.height(2.dp))
                        Text("上次拉取 ${formatTime(models2At)}（存在本机）", fontSize = 10.sp, color = palette.sub)
                    }
                    Spacer(Modifier.height(4.dp))
                    Text(
                        "拉取用的是第二套自己的地址 / Key；这两格也留空的话，就跟第一套一模一样。",
                        fontSize = 10.sp,
                        color = palette.sub,
                    )
                    Spacer(Modifier.height(8.dp))
                    GlassPill("复用第一套接口", false, Modifier.fillMaxWidth()) {
                        update(d.copy(baseUrl2 = "", apiKey2 = "", model2 = ""))
                    }
                    Spacer(Modifier.height(6.dp))
                    Text(
                        "地址、Key、模型可分别留空，缺项会复用第一套。",
                        fontSize = 13.sp,
                        color = palette.sub,
                    )
                }
            }
        }
        if (section == SettingsSection.ALL || section == SettingsSection.WECHAT) {
            GlassCard(d.glassAlpha) {
                Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                    OutlinedTextField(
                        colors = glassFieldColors(),
                        shape = RoundedCornerShape(RadiusR2),
                        value = d.ctx.toString(),
                        onValueChange = { update(d.copy(ctx = it.toIntOrNull() ?: d.ctx)) },
                        modifier = Modifier.weight(1f),
                        label = { Text("参考消息数（2–20）") },
                        singleLine = true,
                        keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number),
                    )
                    OutlinedTextField(
                        colors = glassFieldColors(),
                        shape = RoundedCornerShape(RadiusR2),
                        value = d.minIntervalSec.toString(),
                        onValueChange = { update(d.copy(minIntervalSec = it.toIntOrNull() ?: d.minIntervalSec)) },
                        modifier = Modifier.weight(1f),
                        label = { Text("请求间隔（秒）") },
                        singleLine = true,
                        keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number),
                    )
                }
                Spacer(Modifier.height(8.dp))
                OutlinedTextField(
                    colors = glassFieldColors(),
                    shape = RoundedCornerShape(RadiusR2),
                    value = d.temperature.toString(),
                    onValueChange = { update(d.copy(temperature = it.toDoubleOrNull() ?: d.temperature)) },
                    modifier = Modifier.fillMaxWidth(),
                    label = { Text("创造性（temperature）") },
                    singleLine = true,
                    keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Decimal),
                )
                Spacer(Modifier.height(10.dp))
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Column(Modifier.weight(1f)) {
                        Text("微信内自动分析", fontSize = 15.sp, color = palette.text)
                        Text("关掉后只在手动点「识别」时出现卡片", fontSize = 13.sp, color = palette.sub)
                    }
                    Switch(checked = d.enabled, onCheckedChange = { update(d.copy(enabled = it)) })
                }
                Spacer(Modifier.height(6.dp))
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Column(Modifier.weight(1f)) {
                        Text("敏感内容检查", fontSize = 15.sp, color = palette.text)
                        Text("命中验证码/银行卡/转账时先拦一次（推荐开）", fontSize = 13.sp, color = palette.sub)
                    }
                    Switch(checked = !d.allowSensitive, onCheckedChange = { update(d.copy(allowSensitive = !it)) })
                }
                Spacer(Modifier.height(14.dp))
                Text("修改后点右上角保存。", fontSize = 13.sp, color = palette.sub)

            }
        }
        if (section == SettingsSection.ALL || section == SettingsSection.WHITELIST) {
            GlassCard(d.glassAlpha) {
                Text("会话白名单", fontSize = 17.sp, fontWeight = FontWeight.SemiBold, color = palette.text)
                // 白底说明卡放最上面：这一页最容易踩的坑（名字对不上、开关开着名单空着）先说清楚。
                // 白卡和玻璃卡刻意不同 —— 玻璃是界面的一部分，白卡是「贴上去的说明书」。
                Text("仅处理选定会话。开启后，空名单或无法识别名称的会话均不处理。", fontSize = 13.sp, color = palette.sub)
                Spacer(Modifier.height(8.dp))
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Column(Modifier.weight(1f)) {
                        Text("只对白名单里的聊天生效", fontSize = 15.sp, color = palette.text)
                        Text(
                            if (d.whitelistEnabled) {
                                "已开：只有下面 ${d.whitelist.size} 个会话会工作"
                            } else {
                                "关着：所有聊天都会分析（默认）"
                            },
                            fontSize = 13.sp,
                            color = palette.sub,
                        )
                    }
                    Switch(
                        checked = d.whitelistEnabled,
                        onCheckedChange = { on ->
                            saveWhitelist(on, d.whitelist)
                            whitelistNote = if (on) {
                                if (d.whitelist.isEmpty()) "已开，但名单还是空的 —— 等于所有聊天都不工作"
                                else "已开 · 只对下面这几个会话生效"
                            } else {
                                "已关 · 所有聊天都会分析"
                            }
                        },
                    )
                }
                if (d.whitelistEnabled) {
                    Spacer(Modifier.height(8.dp))
                    Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        OutlinedTextField(
                            colors = glassFieldColors(),
                            shape = RoundedCornerShape(RadiusR2),
                            value = newChat,
                            onValueChange = { newChat = it },
                            modifier = Modifier.weight(1f),
                            label = { Text("写微信里显示的那个名字") },
                            singleLine = true,
                        )
                        Button(
                            onClick = {
                                val k = Roles.normalizeKey(newChat)
                                if (k.isNotBlank()) {
                                    saveWhitelist(d.whitelistEnabled, d.whitelist + k)
                                    whitelistNote = "已加入「$k」"
                                    newChat = ""
                                }
                            },
                            shape = RoundedCornerShape(RadiusR2),
                            colors = ButtonDefaults.buttonColors(containerColor = palette.primary),
                        ) { Text("添加") }
                    }
                    if (d.whitelist.isEmpty()) {
                        Spacer(Modifier.height(6.dp))
                        Text(
                            "还是空的 —— 开着白名单却一条都没加，等于所有聊天都不工作。",
                            fontSize = 13.sp,
                            color = palette.warn,
                        )
                    } else {
                        Spacer(Modifier.height(6.dp))
                        val whiteList = d.whitelist.sorted()
                    if (whitePicking) {
                        PickBar(
                            count = whitePicked.size,
                            total = whiteList.size,
                            palette = palette,
                            primaryLabel = "移出白名单",
                            onPrimary = {
                                val gone = whitePicked
                                saveWhitelist(d.whitelistEnabled, d.whitelist - gone)
                                whitelistNote = "已移出 ${gone.size} 个"
                                whitePicked = emptySet()
                                whitePicking = false
                            },
                            dangerLabel = "删除（不再出现）",
                            onDanger = {
                                val gone = whitePicked
                                saveWhitelist(d.whitelistEnabled, d.whitelist - gone)
                                store.ignoreChats(gone)
                                ignoredCount = store.chatIgnored().size
                                whitelistNote = "已删除 ${gone.size} 个，以后拉取不再出现"
                                whitePicked = emptySet()
                                whitePicking = false
                            },
                            onSelectAll = {
                                whitePicked = if (whitePicked.size == whiteList.size) emptySet() else whiteList.toSet()
                            },
                            onCancel = {
                                whitePicked = emptySet()
                                whitePicking = false
                            },
                        )
                        Spacer(Modifier.height(6.dp))
                    }
                    whiteList.forEach { name ->
                        PickRow(
                            label = name,
                            picking = whitePicking,
                            selected = name in whitePicked,
                            palette = palette,
                            actionLabel = "移除",
                            actionColor = palette.bad,
                            onToggle = {
                                whitePicked = if (name in whitePicked) whitePicked - name else whitePicked + name
                            },
                            onLongPress = {
                                whitePicking = true
                                whitePicked = whitePicked + name
                            },
                            onAction = {
                                saveWhitelist(d.whitelistEnabled, d.whitelist - name)
                                whitelistNote = "已移除「$name」"
                            },
                        )
                    }
                    Text("长按任意一项可多选，然后一次移出 / 删除。", fontSize = 13.sp, color = palette.sub)
                    }
                    Spacer(Modifier.height(6.dp))
                    HintText(
                        "名字要和微信里显示的一致（群聊填群名；改过备注的用改过的名字）；懒得敲字就用下面的「拉取会话列表」。",
                        fontSize = 13.sp,
                        color = palette.sub,
                    )
                }

                Spacer(Modifier.height(12.dp))
                Text("从微信里列出来", fontSize = 13.sp, fontWeight = FontWeight.Medium, color = palette.text)
                HintText(
                    "点一下，然后回微信的首页或通讯录停两秒（滚一下能多收几个），再回来打开下面的「拉取到的联系人」去挑。\n" +
                        "白名单开着的时候，你打开过的那个聊天也会自己进来（认的是聊天页顶上那个名字，也就是备注）。\n" +
                        "⚠ 个人资料页不是来源 —— 那一页只有「微信号 / 地区」这种字段，读出来全是杂项，别在那里等。",
                    fontSize = 13.sp,
                    color = palette.sub,
                )
                Spacer(Modifier.height(6.dp))
                Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                    Button(
                        onClick = {
                            store.requestChats()
                            whitelistNote = "已请求 · 回微信首页 / 通讯录停两秒，再回来这里看"
                        },
                        modifier = Modifier.height(44.dp),
                        shape = RoundedCornerShape(RadiusR2),
                        colors = ButtonDefaults.buttonColors(containerColor = palette.primary),
                    ) { Text("拉取会话列表") }
                    Text(pullInfo, fontSize = 13.sp, color = palette.sub, modifier = Modifier.weight(1f))
                }
                val todo = cands.filter { it !in d.whitelist }
                Spacer(Modifier.height(10.dp))
                // 名单本身搬到二级页了：拉到几十个名字时铺在卡里会把整页撑得很长，也没法搜。
                // 这里只留一个入口 + 数量。
                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    modifier = Modifier
                        .fillMaxWidth()
                        .clip(RoundedCornerShape(RadiusR2))
                        .background(palette.primary.copy(alpha = ChipBgAlpha))
                        .clickable { onOpenCandidates() }
                        .padding(horizontal = 12.dp, vertical = 10.dp),
                ) {
                    Column(Modifier.weight(1f)) {
                        Text("拉取到的联系人（${cands.size}）", fontSize = 14.sp, color = palette.text)
                        Text(
                            when {
                                cands.isEmpty() -> "还没收到候选 —— 点上面的按钮，然后去微信首页 / 通讯录停两秒"
                                todo.isEmpty() -> "拉回来的都加进去了 · 点开可以搜、也可以移出"
                                else -> "还没加进去的 ${todo.size} 个 · 点开可以搜索、批量加入"
                            },
                            fontSize = 13.sp,
                            color = palette.sub,
                        )
                    }
                    Text("查看 ›", fontSize = 13.sp, color = palette.primary)
                }
                if (ignoredCount > 0) {
                    Spacer(Modifier.height(4.dp))
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Text(
                            "已忽略 $ignoredCount 个（删掉的杂项）",
                            fontSize = 13.sp,
                            color = palette.sub,
                            modifier = Modifier.weight(1f),
                        )
                        Text(
                            "恢复",
                            fontSize = 13.sp,
                            color = palette.primary,
                            modifier = Modifier
                                .clip(RoundedCornerShape(RadiusR1))
                                .clickable {
                                    store.clearIgnored()
                                    ignoredCount = 0
                                    whitelistNote = "已恢复：删掉的候选下次拉取会重新出现"
                                }
                                .padding(horizontal = 8.dp, vertical = 4.dp),
                        )
                    }
                }
                whitelistNote?.let {
                    Spacer(Modifier.height(6.dp))
                    Text(it, fontSize = 13.sp, color = palette.ok)
                }
            }
        }
        if (section == SettingsSection.ALL || section == SettingsSection.BACKUP) {
            GlassCard(d.glassAlpha) {
                Text("备份 / 迁移", fontSize = 17.sp, fontWeight = FontWeight.SemiBold, color = palette.text)
                Text(
                    "导入会合并同名角色，保留备份中没有的记录。",
                    fontSize = 13.sp,
                    color = palette.sub,
                )
                Spacer(Modifier.height(8.dp))
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Column(Modifier.weight(1f)) {
                        Text("导出时包含 API Key", fontSize = 13.sp, color = palette.text)
                        Text("开了的话导出文件里有明文 Key，别往公开地方放", fontSize = 13.sp, color = palette.sub)
                    }
                    Switch(checked = includeKey, onCheckedChange = { includeKey = it })
                }
                Spacer(Modifier.height(6.dp))
                Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                    Button(
                        onClick = { exportLauncher.launch(Backup.FILE_NAME) },
                        modifier = Modifier.weight(1f).height(48.dp),
                        shape = RoundedCornerShape(RadiusR2),
                        colors = ButtonDefaults.buttonColors(containerColor = palette.primary),
                    ) { Text("导出配置") }
                    OutlinedButton(
                        onClick = { importLauncher.launch(arrayOf("application/json", "text/plain", "*/*")) },
                        modifier = Modifier.weight(1f).height(48.dp),
                        shape = RoundedCornerShape(RadiusR2),
                    ) { Text("导入配置") }
                }
                backupNote?.let {
                    Spacer(Modifier.height(6.dp))
                    Text(it, fontSize = 13.sp, color = if (it.startsWith("已")) palette.ok else palette.bad)
                }
                Spacer(Modifier.height(10.dp))
                OutlinedButton(
                    onClick = { diagLauncher.launch("TalkTact-诊断包.zip") },
                    modifier = Modifier.fillMaxWidth().height(48.dp),
                    shape = RoundedCornerShape(RadiusR2),
                ) { Text("导出诊断包（.zip）") }
                Spacer(Modifier.height(4.dp))
                HintText(
                    "出问题时用它：环境 / 配置（不含 Key）/ 角色条数 / 抓到的界面结构 / 最近一次调用。\n" +
                        "⚠️ 里面有你和对方的聊天内容，发出去之前先自己看一眼。",
                    fontSize = 13.sp,
                    color = palette.sub,
                )
                diagNote?.let {
                    Spacer(Modifier.height(6.dp))
                    Text(it, fontSize = 13.sp, color = if (it.startsWith("已")) palette.ok else palette.bad)
                }
            }
        }
        if (section == SettingsSection.ALL) {
            GlassCard(d.glassAlpha) {
                Text("诊断", fontSize = 17.sp, fontWeight = FontWeight.SemiBold, color = palette.text)
                Text(
                    "某个聊天页连按钮都不弹、或者「运行状态」报错的时候用这里：\n" +
                        "让微信进程抓一次当前界面、看它真正发出去的那次调用，再复制出来发我。",
                    fontSize = 13.sp,
                    color = palette.sub,
                )
                Spacer(Modifier.height(8.dp))
                Button(
                    onClick = { leave(onOpenDiag) },
                    modifier = Modifier.fillMaxWidth().height(48.dp),
                    shape = RoundedCornerShape(RadiusR2),
                    colors = ButtonDefaults.buttonColors(containerColor = palette.primary),
                ) { Text("进入诊断") }
            }
        }
    }
    pendingExit?.let { action ->
        AlertDialog(
            onDismissRequest = { pendingExit = null },
            title = { Text("有未保存的设置") },
            text = { Text("保存后离开，或放弃这次修改。") },
            confirmButton = { TextButton(onClick = { saveAll(); pendingExit = null; action() }) { Text("保存并离开") } },
            dismissButton = { TextButton(onClick = { pendingExit = null; action() }) { Text("放弃修改") } },
        )
    }
}

// ================= 拉取到的联系人（设置 → 高级设置 → 白名单卡的二级页） =================

/**
 * 设置 → 高级设置 → 「拉取到的联系人」。
 *
 * 从微信读回来的会话名（候选）以前直接铺在白名单卡里：一次只列 24 条、也没法搜 ——
 * 拉回来的名字一多，那一块就成了整页最长的一段。所以搬到单独一层：顶上一个搜索框，下面是完整名单。
 *
 * 名单**不因为「加入」而消失**：加进去的那条挪到下面的「已经在白名单里」那一段。
 * 「微信里到底读到了谁」始终是一份看得全的名单，比「加一个少一个」好找。
 */
@Composable
fun ChatCandidatesScreen(
    store: ConfigStore,
    ui: ConfigData,
    onSaved: () -> Unit,
    onBack: () -> Unit,
) {
    val palette = LocalPalette.current
    var d by remember { mutableStateOf(ui) }
    var note by remember { mutableStateOf<String?>(null) }
    var query by remember { mutableStateOf("") }
    var picking by remember { mutableStateOf(false) }
    var picked by remember { mutableStateOf(emptySet<String>()) }
    var ignoredCount by remember { mutableStateOf(store.chatIgnored().size) }

    // 候选是注入侧用广播回传的，这一页不会自己重组 —— 停在这里时每 1.5 秒自己看一眼。
    // 轮询**有上限**（约 5 分钟），理由和白名单卡那边一样：别留一个永远挂着的定时任务，
    // 也让渲染回归测试不会被一个无限协程挂住。
    val resumed = resumedTick()
    val pulled by produceState(
        initialValue = store.chatCandidates() to store.chatPullInfo(),
        key1 = resumed,
    ) {
        repeat(200) {
            kotlinx.coroutines.delay(1500)
            value = store.chatCandidates() to store.chatPullInfo()
        }
    }
    val cands = pulled.first
    val reqAt = store.chatRequestAt()
    val pullInfo = pulled.second.let { (info, at) ->
        when {
            at > reqAt -> info.ifBlank { "已收到回传" } + " · " + clockText(at)
            reqAt > 0L -> "已请求 ${clockText(reqAt)} · 微信侧还没回过话"
            else -> "还没拉过"
        }
    }

    fun saveWhitelist(enabled: Boolean, chats: Set<String>) {
        store.saveWhitelist(enabled, chats)
        d = store.load()
        // App 层的 ui 也要跟着刷新，否则回上一页看到的是旧名单（再点一次保存还会把它写回去）
        onSaved()
    }

    val all = cands.sorted()
    val todo = all.filter { it !in d.whitelist }
    val added = all.filter { it in d.whitelist }
    val hitTodo = filterNames(todo, query)
    val hitAdded = filterNames(added, query)

    LazyColumn(Modifier.fillMaxSize(), contentPadding = PaddingValues(bottom = 120.dp)) {
        item {
            ScreenHeader("拉取到的联系人", "微信里读回来的名单 · 点一下加入白名单") {
                HeaderButton("← 返回", onBack)
            }
        }

        item {
            GlassCard(d.glassAlpha) {
                Text("搜索", fontSize = 17.sp, fontWeight = FontWeight.SemiBold, color = palette.text)
                Text(
                    "拉回来的名字可能有几十个 —— 输一两个字就能筛出来（只筛这一页的名单）。",
                    fontSize = 13.sp,
                    color = palette.sub,
                )
                Spacer(Modifier.height(8.dp))
                OutlinedTextField(
                    colors = glassFieldColors(),
                    shape = RoundedCornerShape(RadiusR2),
                    value = query,
                    onValueChange = {
                        query = it
                        // 边筛边选容易错位：一改搜索词就退出多选，重新选
                        if (picking) {
                            picking = false
                            picked = emptySet()
                        }
                    },
                    modifier = Modifier.fillMaxWidth(),
                    label = { Text("搜索名字") },
                    singleLine = true,
                )
                if (!d.whitelistEnabled) {
                    Spacer(Modifier.height(10.dp))
                    WhiteCard {
                        Text(
                            "白名单现在是关着的",
                            fontSize = 13.sp,
                            fontWeight = FontWeight.Medium,
                            color = Color(0xFFB3261E),
                        )
                        Text(
                            "这里加进去的名字，在开关打开之前都不会生效 —— " +
                                "回上一页把「只对白名单里的聊天生效」打开。",
                            fontSize = 13.sp,
                            color = Color(0xFF55525E),
                            modifier = Modifier.padding(top = 3.dp),
                        )
                    }
                }
                Spacer(Modifier.height(10.dp))
                Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                    Button(
                        onClick = {
                            store.requestChats()
                            note = "已请求 · 回微信首页 / 通讯录停两秒，再回来看"
                        },
                        modifier = Modifier.height(44.dp),
                        shape = RoundedCornerShape(RadiusR2),
                        colors = ButtonDefaults.buttonColors(containerColor = palette.primary),
                    ) { Text("拉取会话列表") }
                    Text(pullInfo, fontSize = 13.sp, color = palette.sub, modifier = Modifier.weight(1f))
                }
                note?.let {
                    Spacer(Modifier.height(6.dp))
                    Text(it, fontSize = 13.sp, color = palette.ok)
                }
            }
        }

        if (picking) {
            item {
                GlassCard(d.glassAlpha, border = palette.primary.copy(alpha = CardBorderAlpha)) {
                    PickBar(
                        count = picked.size,
                        total = hitTodo.size,
                        palette = palette,
                        primaryLabel = "加入白名单",
                        onPrimary = {
                            val add = picked
                            saveWhitelist(d.whitelistEnabled, d.whitelist + add)
                            store.unignoreChats(add)
                            ignoredCount = store.chatIgnored().size
                            note = "已加入 ${add.size} 个"
                            picked = emptySet()
                            picking = false
                        },
                        dangerLabel = "删除（不再出现）",
                        onDanger = {
                            val gone = picked
                            store.ignoreChats(gone)
                            ignoredCount = store.chatIgnored().size
                            note = "已删除 ${gone.size} 个杂项 —— 以后拉取不会再冒出来"
                            picked = emptySet()
                            picking = false
                        },
                        onSelectAll = {
                            picked = if (picked.size == hitTodo.size) emptySet() else hitTodo.toSet()
                        },
                        onCancel = {
                            picked = emptySet()
                            picking = false
                        },
                    )
                }
            }
        }

        item {
            Text(
                "还没加进去的（${todo.size}）" + if (query.isNotBlank()) " · 筛出 ${hitTodo.size} 个" else "",
                fontSize = 13.sp,
                color = palette.sub,
                modifier = Modifier.padding(start = 30.dp, end = 30.dp, top = 10.dp, bottom = 4.dp),
            )
        }

        if (hitTodo.isEmpty()) {
            item {
                GlassCard(d.glassAlpha) {
                    Text(
                        when {
                            all.isEmpty() -> "这一页还是空的 —— 点上面的「拉取会话列表」，然后回微信首页 / 通讯录停两秒。"
                            query.isNotBlank() -> "没搜到「${query.trim()}」—— 换个字试试，或者清空搜索框。"
                            else -> "拉回来的 ${all.size} 个都已经加进白名单了。"
                        },
                        fontSize = 13.sp,
                        color = palette.sub,
                    )
                }
            }
        }

        items(hitTodo) { name ->
            Box(Modifier.padding(horizontal = 30.dp)) {
                PickRow(
                    label = name,
                    picking = picking,
                    selected = name in picked,
                    palette = palette,
                    actionLabel = "加入",
                    actionColor = palette.primary,
                    onToggle = { picked = if (name in picked) picked - name else picked + name },
                    onLongPress = {
                        picking = true
                        picked = picked + name
                    },
                    onAction = {
                        saveWhitelist(d.whitelistEnabled, d.whitelist + name)
                        store.unignoreChats(setOf(name))
                        ignoredCount = store.chatIgnored().size
                        note = "已加入「$name」"
                    },
                )
            }
        }

        if (hitAdded.isNotEmpty()) {
            item {
                Text(
                    "已经在白名单里的（${added.size}）" + if (query.isNotBlank()) " · 筛出 ${hitAdded.size} 个" else "",
                    fontSize = 13.sp,
                    color = palette.sub,
                    modifier = Modifier.padding(start = 30.dp, end = 30.dp, top = 14.dp, bottom = 4.dp),
                )
            }
            items(hitAdded) { name ->
                Box(Modifier.padding(horizontal = 30.dp)) {
                    PickRow(
                        label = name,
                        picking = false,
                        selected = false,
                        palette = palette,
                        actionLabel = "移出",
                        actionColor = palette.bad,
                        onToggle = {},
                        onLongPress = {},
                        onAction = {
                            saveWhitelist(d.whitelistEnabled, d.whitelist - name)
                            note = "已移出「$name」"
                        },
                    )
                }
            }
        }

        item {
            Column(Modifier.padding(horizontal = 30.dp, vertical = 10.dp)) {
                Text(
                    "长按任意一项可多选，然后一次加入或删除。删除的杂项会被记下来，下次拉取不再出现。",
                    fontSize = 13.sp,
                    color = palette.sub,
                )
                if (ignoredCount > 0) {
                    Spacer(Modifier.height(4.dp))
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Text(
                            "已忽略 $ignoredCount 个（删掉的杂项）",
                            fontSize = 13.sp,
                            color = palette.sub,
                            modifier = Modifier.weight(1f),
                        )
                        Text(
                            "恢复",
                            fontSize = 13.sp,
                            color = palette.primary,
                            modifier = Modifier
                                .clip(RoundedCornerShape(RadiusR1))
                                .clickable {
                                    store.clearIgnored()
                                    ignoredCount = 0
                                    note = "已恢复：删掉的候选下次拉取会重新出现"
                                }
                                .padding(horizontal = 8.dp, vertical = 4.dp),
                        )
                    }
                }
            }
        }
    }
}

// ================= 诊断（设置 → 高级设置 → 诊断） =================

/**
 * 网络信息（归属地）—— 从「高级设置」沉到「诊断」页顶部。
 *
 * 它是只读的排障信息（自检里那两行省份是从哪儿来的），本质属于网络排障，和接口地址那种
 * 「要你去配的」不是一类东西；放在九张配置卡中间，它是唯一一张「看一眼就行」的卡。
 *
 * 开关与自定义地址都**立刻落盘**（跟白名单 / 识图 / 生成模式同一个道理），没有「保存」按钮 ——
 * 所以这里自己读一份 store，不依赖外面那页的草稿（诊断页本来也没有草稿）。
 */
@Composable
private fun GeoCard(store: ConfigStore, glassAlpha: Float) {
    val palette = LocalPalette.current
    var d by remember { mutableStateOf(store.load()) }
    var persisted by remember { mutableStateOf(d) }

    fun saveGeoNow(enabled: Boolean, endpoint: String) {
        store.saveGeo(enabled, endpoint)
        d = store.load()
        persisted = d
    }

    GlassCard(glassAlpha) {
        Text("网络信息（归属地）", fontSize = 17.sp, fontWeight = FontWeight.SemiBold, color = palette.text)
        Text(
            "「运行状态 → 接口自检」里那两行省份，是拿 IP 去问第三方库要的 —— 这是整个模块唯一一处" +
                "会把 IP 发给别人的地方。关掉之后自检只显示 IP，一次都不问。",
            fontSize = 13.sp,
            color = palette.sub,
        )
        Spacer(Modifier.height(8.dp))
        Row(verticalAlignment = Alignment.CenterVertically) {
            Column(Modifier.weight(1f)) {
                Text("查询 IP 归属地", fontSize = 15.sp, color = palette.text)
                Text(
                    if (d.geoEnabled) "自检时带上省份（结果缓存 10 分钟）" else "已关闭：不请求任何第三方",
                    fontSize = 13.sp,
                    color = palette.sub,
                )
            }
            Switch(checked = d.geoEnabled, onCheckedChange = { saveGeoNow(it, d.geoEndpoint) })
        }
        Spacer(Modifier.height(8.dp))
        OutlinedTextField(
            colors = glassFieldColors(),
            shape = RoundedCornerShape(RadiusR2),
            value = d.geoEndpoint,
            onValueChange = { d = d.copy(geoEndpoint = it) },
            modifier = Modifier.fillMaxWidth(),
            label = { Text("自定义接口地址（留空 = 用内置的）") },
            singleLine = true,
            enabled = d.geoEnabled,
        )
        if (d.geoEnabled && d.geoEndpoint.trim() != persisted.geoEndpoint.trim()) {
            Spacer(Modifier.height(6.dp))
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text("地址还没保存", modifier = Modifier.weight(1f), fontSize = 13.sp, color = palette.bad)
                Button(
                    onClick = { saveGeoNow(d.geoEnabled, d.geoEndpoint) },
                    modifier = Modifier.height(42.dp),
                    shape = RoundedCornerShape(RadiusR2),
                    colors = ButtonDefaults.buttonColors(containerColor = palette.primary),
                ) { Text("保存地址") }
            }
        }
        Spacer(Modifier.height(4.dp))
        HintText(
            "自己填的地址要能返回 JSON（内置用的是 ip.useragentinfo.com）；认不出的字段一律当没有，不会报错。",
            fontSize = 10.sp,
            color = palette.sub,
        )
    }
}

/**
 * 设置 → 高级设置 → 诊断（第三层）。
 *
 * 这三块原来是挤在「运行状态」页上的（抓取界面 / 微信进程的诊断 / 最后一次调用），
 * 把那一页撑得很长，而且它们都是「出问题了才看」的东西，所以搬进设置这条线里。
 * 「返回」回高级设置 —— 它就是从那儿进来的。
 */
@Composable
fun DiagScreen(store: ConfigStore, glassAlpha: Float, onBack: () -> Unit) {
    val palette = LocalPalette.current
    val clipboard = LocalClipboardManager.current
    var tick by remember { mutableStateOf(0) }
    var diagAsked by remember { mutableStateOf(false) }
    val diag = remember(tick) { store.diag() }
    val diagAt = remember(tick) { store.diagAt() }
    val lastCall = remember(tick) { store.lastCall() }
    val lastCallAt = remember(tick) { store.lastCallAt() }
    val trace = remember(tick) { store.trace() }
    val traceAt = remember(tick) { store.traceAt() }
    val context = LocalContext.current
    var exportNote by remember { mutableStateOf("") }
    // 页底的「导出诊断包」：排障时「抓完就能打包发给对方」，不用退回「高级设置 → 备份」去找。
    val diagLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.CreateDocument("application/zip"),
    ) { uri ->
        if (uri != null) {
            exportNote = try {
                context.contentResolver
                    .openOutputStream(uri)
                    ?.use { it.write(buildDiagZip(context, store, store.load())) }
                "已导出 —— 发之前建议先打开看一眼（含聊天内容，不含 API Key）"
            } catch (t: Throwable) {
                "导出失败：${t.message}"
            }
        }
    }

    Column(Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(bottom = 112.dp)) {
        ScreenHeader("诊断", "抓界面 · 看轨迹与调用 · 复制发我") {
            HeaderButton("↻ 刷新") { tick++ }
            Spacer(Modifier.width(8.dp))
            HeaderButton("← 返回", onBack)
        }

        // 「最近一次调用」提到**页首**：出问题时第一眼看的就是「实际发出去的到底是什么」，
        // 而它原本排在整页最后，滚半天才到 —— 偏偏这是最常被复制的那一段。
        if (lastCall.isNotBlank()) {
            GlassCard(glassAlpha, border = palette.primary.copy(alpha = CardBorderAlpha)) {
                Text("最近一次调用（注入侧真正发出去的）", fontSize = 17.sp, fontWeight = FontWeight.SemiBold, color = palette.text)
                Text(
                    "发生于 ${formatTime(lastCallAt)} · 这里是微信进程实际拿去调接口的那一份，不是本 App 里的配置。" +
                        "核对「当前军师」有没有真的生效，看 system 长度那一行。",
                    fontSize = 13.sp,
                    color = palette.sub,
                )
                Spacer(Modifier.height(8.dp))
                Text(lastCall, fontSize = 10.sp, color = palette.text)
                Spacer(Modifier.height(8.dp))
                OutlinedButton(
                    onClick = { clipboard.setText(AnnotatedString(lastCall)) },
                    shape = RoundedCornerShape(RadiusR2),
                ) { Text("复制这一段") }
            }
        } else {
            GlassCard(glassAlpha) {
                Text("还没有调用记录", fontSize = 17.sp, fontWeight = FontWeight.SemiBold, color = palette.text)
                Text("在微信里生成过一次候选回复之后，那份请求就会留在这里。", fontSize = 13.sp, color = palette.sub)
            }
        }

        // 归属地从「高级设置」沉到这里：只读的排障信息，跟「要配的」分开。
        GeoCard(store, glassAlpha)

        GlassCard(glassAlpha) {
            Text("抓取微信界面", fontSize = 17.sp, fontWeight = FontWeight.SemiBold, color = palette.text)
            Text(
                "某些聊天页连卡片都不弹时用这个：先在微信里停在那个聊天页 → 切回这里点下面的按钮 → " +
                    "再切回微信（那个页面重新出现就会自动抓）→ 回来点「刷新」→ 复制下面的「诊断」发我。",
                fontSize = 13.sp,
                color = palette.sub,
            )
            Spacer(Modifier.height(8.dp))
            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                Button(
                    onClick = { store.requestDiag(); diagAsked = true },
                    shape = RoundedCornerShape(RadiusR2),
                    colors = ButtonDefaults.buttonColors(containerColor = palette.primary),
                ) { Text("抓当前微信界面") }
                if (diagAsked) {
                    Text("已排队，切回微信那一页即抓", fontSize = 13.sp, color = palette.ok)
                }
            }
        }

        if (trace.isNotBlank()) {
            GlassCard(glassAlpha, border = palette.okMark.copy(alpha = CardBorderAlpha)) {
                Text("决策轨迹（每一轮读到什么、停在哪一步）", fontSize = 17.sp, fontWeight = FontWeight.SemiBold, color = palette.text)
                Text(
                    "更新于 ${formatTime(traceAt)} · 卡片不弹时先看它：注入侧每一轮都留一条，" +
                        "扫一眼就知道是卡在「找不到输入框」还是「最后一条是我发的」。它刻意不含聊天正文。",
                    fontSize = 13.sp,
                    color = palette.sub,
                )
                Spacer(Modifier.height(8.dp))
                Text(trace, fontSize = 10.sp, color = palette.text)
                Spacer(Modifier.height(8.dp))
                OutlinedButton(
                    onClick = { clipboard.setText(AnnotatedString(trace)) },
                    shape = RoundedCornerShape(RadiusR2),
                ) { Text("复制轨迹") }
            }
        } else {
            GlassCard(glassAlpha) {
                Text("还没有轨迹", fontSize = 17.sp, fontWeight = FontWeight.SemiBold, color = palette.text)
                Text(
                    "在微信里打开一个聊天页停一会儿，注入侧就会把判定记下来并自动回传；" +
                        "也可以点上面那个「抓当前微信界面」立刻带一份回来。",
                    fontSize = 13.sp,
                    color = palette.sub,
                )
            }
        }

        if (diag.isNotBlank()) {
            GlassCard(glassAlpha, border = palette.warnMark.copy(alpha = CardBorderAlpha)) {
                Text("诊断（来自微信进程）", fontSize = 17.sp, fontWeight = FontWeight.SemiBold, color = palette.text)
                Text(
                    "生成于 ${formatTime(diagAt)} · 排查「读不到消息 / 全是图片」时把它复制给对方",
                    fontSize = 13.sp,
                    color = palette.sub,
                )
                Spacer(Modifier.height(8.dp))
                Text(diag, fontSize = 10.sp, color = palette.text)
                Spacer(Modifier.height(8.dp))
                OutlinedButton(
                    onClick = { clipboard.setText(AnnotatedString(diag)) },
                    shape = RoundedCornerShape(RadiusR2),
                ) { Text("复制诊断") }
            }
        } else {
            GlassCard(glassAlpha) {
                Text("还没有诊断", fontSize = 17.sp, fontWeight = FontWeight.SemiBold, color = palette.text)
                Text("上面那个按钮抓过一次之后，微信进程会把界面结构留在这里。", fontSize = 13.sp, color = palette.sub)
            }
        }

        // 页底固定一个次级导出入口（排障动线：抓完 → 打包 → 发出去）
        Spacer(Modifier.height(6.dp))
        OutlinedButton(
            onClick = { diagLauncher.launch("TalkTact-诊断包.zip") },
            modifier = Modifier.fillMaxWidth().height(46.dp),
            shape = RoundedCornerShape(RadiusR2),
        ) { Text("导出诊断包（.zip）") }
        if (exportNote.isNotBlank()) {
            Text(exportNote, fontSize = 13.sp, color = palette.sub, modifier = Modifier.padding(top = 6.dp))
        }
    }
}

// ================= 角色 =================

/** 「TA 是你什么人」的备选。用户也可以直接把自定义的写进「平时的关系」里。 */
private val RELATIONS = listOf("家人", "恋人", "暧昧", "朋友", "同学", "同事", "上级", "客户", "其他")

/**
 * 角色页。
 *
 * 列表里每个人 = 一个微信会话名。数据全自动来：模块在你打开某个聊天页时，把它读到的消息
 * 按会话名归档回来（1 小时内重复只留一条）。点进某人可以写「TA 是你什么人」和「平时的关系」，
 * 这两样 + 之前攒下的聊天记录会一起拼进提示词，直接影响当前 skill 生成出来的回复。
 */
@OptIn(ExperimentalFoundationApi::class)
@Composable
internal fun RoleDetail(
    store: ConfigStore,
    role: Role,
    glassAlpha: Float,
    onBack: () -> Unit,
    onRename: (String) -> Unit,
    onChanged: () -> Unit,
) {
    // 「本人」这条没有「TA 是你什么人」，它管的是另一件事（我的说话风格），单独一页
    BackHandler(onBack = onBack)
    if (role.key == SELF_ROLE_KEY) {
        SelfStyleDetail(store, glassAlpha, onBack)
        return
    }
    val palette = LocalPalette.current
    var relation by remember(role.key) { mutableStateOf(role.relation) }
    var note by remember(role.key) { mutableStateOf(role.note) }
    // 跟「外观」「高级设置」「军师」同一套判据：草稿和上次落盘的那份比。
    // 用本地快照而不是拿 role 再比一次 —— 上面刷新列表的时机不确定，靠 role 会出现「保存完红字还亮着」。
    var persisted by remember(role.key) { mutableStateOf(role.relation to role.note) }
    val dirty = (relation to note) != persisted
    var confirm by remember { mutableStateOf<String?>(null) }
    var renameOpen by remember { mutableStateOf(false) }
    var draft by remember(role.key) { mutableStateOf(role.name) }
    // 合并：先选「把谁并进来」，再确认一次（被并掉的那个角色会消失，不可撤销）
    var mergeOpen by remember { mutableStateOf(false) }
    var pendingMerge by remember { mutableStateOf<Role?>(null) }
    var mergedNote by remember(role.key) { mutableStateOf<String?>(null) }
    var listTick by remember { mutableStateOf(0) }
    // 候选：除自己以外的普通角色。「本人」不参与合并 —— 它装的是「我自己说过的话」，
    // 混进别人的话会直接污染说话风格 skill
    val mergeCandidates = remember(role.key, listTick) {
        store.roles().filterNot { it.key == role.key || it.key == SELF_ROLE_KEY }
    }

    Column(Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(bottom = 112.dp)) {
        ScreenHeader(role.name, "档案 · ${role.msgs.size} 条记录") {
            TextButton(onClick = { renameOpen = true }) { Text("改名字") }
            Spacer(Modifier.width(8.dp))
            HeaderButton("← 返回", onBack)
        }

        GlassCard(glassAlpha) {
            Text("关系", fontSize = 17.sp, fontWeight = FontWeight.SemiBold, color = palette.text)
            if (role.renamed) {
                Text(
                    "（识别到的会话名是「${role.key}」，它负责匹配、不会被改动，所以改了名字以后消息还是记到这一条）",
                    fontSize = 13.sp,
                    color = palette.sub,
                )
            }
            RELATIONS.chunked(4).forEach { row ->
                Row(Modifier.fillMaxWidth().padding(top = 6.dp), horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                    row.forEach { r ->
                        GlassPill(r, relation == r, Modifier.weight(1f)) {
                            relation = if (relation == r) "" else r
                        }
                    }
                    repeat(4 - row.size) { Spacer(Modifier.weight(1f)) }
                }
            }
            Spacer(Modifier.height(10.dp))
            OutlinedTextField(
                colors = glassFieldColors(),
                shape = RoundedCornerShape(RadiusR2),
                value = note,
                onValueChange = { note = it },
                modifier = Modifier.fillMaxWidth().heightIn(min = 110.dp),
                label = { Text("补充背景") },
            )
            Spacer(Modifier.height(6.dp))
            Text(
                "例：同一个组的后端，说话直接，最近在催我 review；上次帮他带过饭。",
                fontSize = 13.sp,
                color = palette.sub,
            )
            Spacer(Modifier.height(10.dp))
            if (dirty) {
                Text("有未保存的修改", fontSize = 13.sp, color = palette.bad)
                Spacer(Modifier.height(6.dp))
            }
            Button(
                onClick = {
                    store.setRoleProfile(role.key, relation, note)
                    persisted = relation to note
                    // 顺手刷新外层列表：原来这里没通知，保存完返回还显示旧档案
                    onChanged()
                },
                modifier = Modifier.fillMaxWidth().height(48.dp),
                shape = RoundedCornerShape(RadiusR2),
                colors = ButtonDefaults.buttonColors(containerColor = palette.primary),
            ) { Text(if (dirty) "保存" else "已保存") }
        }

        GlassCard(glassAlpha) {
            Text("聊天记录 · ${role.msgs.size} 条", fontSize = 17.sp, fontWeight = FontWeight.SemiBold, color = palette.text)
            Text(
                "时间是我「看到」它的时间，不是微信里那条消息的真实时间 —— 微信不给这条信息，" +
                    "而模块只在聊天页可见时读得到。生成回复时会带上最近 20 条（屏幕上已有的不再重复）。",
                fontSize = 13.sp,
                color = palette.sub,
            )
            Spacer(Modifier.height(6.dp))
            if (role.msgs.isEmpty()) {
                Text("（还没有）", fontSize = 13.sp, color = palette.sub)
            } else {
                var lastShown = 0L
                role.msgs.takeLast(60).forEach { m ->
                    if (m.at - lastShown > 10 * 60_000L) {
                        lastShown = m.at
                        Text(formatTime(m.at), fontSize = 10.sp, color = palette.sub, modifier = Modifier.padding(top = 6.dp))
                    }
                    Text(
                        "${if (m.fromMe) "我" else "对方"}：${m.text}",
                        fontSize = 13.sp,
                        color = if (m.fromMe) palette.sub else palette.text,
                    )
                }
            }
            Spacer(Modifier.height(12.dp))
            OutlinedButton(
                onClick = { mergeOpen = true },
                enabled = mergeCandidates.isNotEmpty(),
                modifier = Modifier.fillMaxWidth().height(46.dp),
                shape = RoundedCornerShape(RadiusR2),
            ) { Text("把另一个角色合并进来") }
            Spacer(Modifier.height(4.dp))
            Text(
                if (mergeCandidates.isEmpty()) {
                    "还没有别的角色可以合并。"
                } else {
                    "同一个人被记成两条时用这个：选一个角色，把它的记录并进这一条（按时间排好）。"
                },
                fontSize = 13.sp,
                color = palette.sub,
            )
            mergedNote?.let {
                Spacer(Modifier.height(6.dp))
                Text(it, fontSize = 13.sp, color = palette.ok)
            }
        }

        // 危险操作独立成一张卡（设计师 P1）：它们原来挤在「记下来的聊天」卡底部、
        // 和「合并进来」只隔一行，而这两个都**不可撤销** —— 误触的代价太大，不该混在浏览区里。
        GlassCard(glassAlpha, border = palette.badMark.copy(alpha = CardBorderAlpha)) {
            Text("危险操作", fontSize = 17.sp, fontWeight = FontWeight.SemiBold, color = palette.bad)
            Text(
                "下面两个都不能撤销：清空只删记下来的聊天（改名与档案保留）；" +
                    "删除会把这条角色整个删掉（记录 + 档案一起）。",
                fontSize = 13.sp,
                color = palette.sub,
            )
            Spacer(Modifier.height(10.dp))
            Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                OutlinedButton(
                    onClick = { confirm = "clear" },
                    enabled = role.msgs.isNotEmpty(),
                    modifier = Modifier.weight(1f),
                    shape = RoundedCornerShape(RadiusR2),
                ) { Text("清空记录") }
                OutlinedButton(
                    onClick = { confirm = "remove" },
                    modifier = Modifier.weight(1f),
                    shape = RoundedCornerShape(RadiusR2),
                ) { Text("删除角色") }
            }
        }
    }

    if (renameOpen) {
        AlertDialog(
            onDismissRequest = { renameOpen = false },
            title = { Text("给这个角色改个名字") },
            text = {
                Column {
                    OutlinedTextField(
                        colors = glassFieldColors(),
                        shape = RoundedCornerShape(RadiusR2),
                        value = draft,
                        onValueChange = { draft = it },
                        modifier = Modifier.fillMaxWidth(),
                        singleLine = true,
                        label = { Text("显示名字") },
                    )
                    Spacer(Modifier.height(8.dp))
                    Text(
                        "这里只改显示。识别到的会话名「${role.key}」不变 —— 所以改完名字，" +
                            "以后这个会话的消息还是记到同一条上，不会分家。",
                        fontSize = 13.sp,
                        color = palette.sub,
                    )
                }
            },
            confirmButton = {
                TextButton(onClick = {
                    onRename(draft)
                    renameOpen = false
                }) { Text("保存") }
            },
            dismissButton = { TextButton(onClick = { renameOpen = false }) { Text("取消") } },
        )
    }

    if (mergeOpen) {
        AlertDialog(
            onDismissRequest = { mergeOpen = false },
            title = { Text("把哪个角色合并进来？") },
            text = {
                Column(Modifier.heightIn(max = 320.dp).verticalScroll(rememberScrollState())) {
                    Text(
                        "合并后：两边记录按时间排好留在「${role.name}」，被并的那个角色会消失。",
                        fontSize = 13.sp,
                        color = palette.sub,
                    )
                    Spacer(Modifier.height(8.dp))
                    mergeCandidates.forEach { o ->
                        Column(
                            Modifier
                                .fillMaxWidth()
                                .padding(vertical = 2.dp)
                                .clip(RoundedCornerShape(RadiusR2))
                                .clickable {
                                    mergeOpen = false
                                    pendingMerge = o
                                }
                                .padding(horizontal = 12.dp, vertical = 10.dp),
                        ) {
                            Text(o.name, fontSize = 14.sp, color = palette.text)
                            Text(
                                "${o.msgs.size} 条记录" +
                                    if (o.relation.isNotBlank()) " · ${o.relation}" else "",
                                fontSize = 13.sp,
                                color = palette.sub,
                            )
                        }
                    }
                }
            },
            confirmButton = { TextButton(onClick = { mergeOpen = false }) { Text("取消") } },
        )
    }

    pendingMerge?.let { src ->
        AlertDialog(
            onDismissRequest = { pendingMerge = null },
            title = { Text("把「${src.name}」合并进来？") },
            text = {
                Text(
                    "「${src.name}」的 ${src.msgs.size} 条记录会并进「${role.name}」，按时间重新排好；" +
                        "合并完「${src.name}」这个角色就没了，无法撤销。\n\n" +
                        if (role.relation.isBlank() && (src.relation.isNotBlank() || src.note.isNotBlank())) {
                            "这边的「TA 是你什么人 / 平时的关系」还空着，会用它的补上。"
                        } else {
                            "「TA 是你什么人 / 平时的关系」保留这边的。"
                        },
                )
            },
            confirmButton = {
                TextButton(onClick = {
                    store.mergeRoles(src.key, role.key)
                    pendingMerge = null
                    listTick++
                    mergedNote = "已把「${src.name}」并进来"
                    onChanged()
                }) { Text("合并") }
            },
            dismissButton = { TextButton(onClick = { pendingMerge = null }) { Text("取消") } },
        )
    }

    confirm?.let { what ->
        AlertDialog(
            onDismissRequest = { confirm = null },
            title = { Text(if (what == "clear") "清空聊天记录？" else "删除这个角色？") },
            text = {
                Text(
                    if (what == "clear") "只清掉记录，「TA 是你什么人 / 平时的关系」会保留。"
                    else "档案和记录一起删掉，无法恢复。",
                )
            },
            confirmButton = {
                TextButton(onClick = {
                    if (what == "clear") store.clearRoleMsgs(role.key) else store.removeRole(role.key)
                    confirm = null
                    onBack()
                }) { Text("确定") }
            },
            dismissButton = { TextButton(onClick = { confirm = null }) { Text("取消") } },
        )
    }
}


/**
 * 「本人」这条的详情页。
 *
 * 和其它角色最大的不同：这里没有「TA 是你什么人 / 平时的关系」，只有一个开关 +
 * 现在的状态 + 手动生成。因为这条线的产出不是"关系描述"，而是一份**说话风格档案**。
 */
@Composable
private fun SelfStyleDetail(store: ConfigStore, glassAlpha: Float, onBack: () -> Unit) {
    val palette = LocalPalette.current
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    var tick by remember { mutableStateOf(0) }
    var busy by remember { mutableStateOf(false) }
    var msg by remember { mutableStateOf<String?>(null) }
    val on = remember(tick) { store.selfStyleEnabled() }
    var hour by remember(tick) { mutableStateOf(store.selfStyleHour()) }
    val samples = remember(tick) { StyleSkill.count(store.selfSamples()) }
    val skill = remember(tick) { store.selfSkill() }
    val skillAt = remember(tick) { store.selfSkillAt() }

    LazyColumn(Modifier.fillMaxSize(), contentPadding = PaddingValues(bottom = 112.dp)) {
        item { ScreenHeader("我的说话风格", "") { HeaderButton("← 返回", onBack) } }

        item {
            GlassCard(glassAlpha, border = if (on) palette.okMark.copy(alpha = CardBorderAlpha) else null) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Column(Modifier.weight(1f)) {
                        Text(
                            "使用我的说话风格",
                            fontSize = 15.sp,
                            fontWeight = FontWeight.Medium,
                            color = palette.text,
                        )
                        Text(
                            if (on) "已开启：会采集我说的话" else "未开启：不采集、不生成、不存储",
                            fontSize = 13.sp,
                            color = if (on) palette.ok else palette.sub,
                        )
                    }
                    GlassPill(if (on) "已开启" else "开启", selected = on) {
                        val next = !on
                        store.setSelfStyleEnabled(next)
                        // 开关和定时任务是绑在一起的：开了才排任务，关了立刻撤掉
                        if (next) SelfStyle.schedule(context, hour) else SelfStyle.cancel(context)
                        msg = null
                        tick++
                    }
                }
                Text("关闭时停止采集和使用，并清空原始样本；已生成档案保留。", fontSize = 12.sp, color = palette.sub)
            }
        }

        item {
            GlassCard(glassAlpha) {
                Text(
                    "自动提炼时间",
                    fontSize = 15.sp,
                    fontWeight = FontWeight.Medium,
                    color = palette.text,
                )
                Text("每天 ${"$hour".padStart(2, '0')}:00", fontSize = 13.sp, color = palette.sub)
                Slider(
                    value = hour.toFloat(),
                    onValueChange = {
                        val h = it.roundToInt().coerceIn(0, 23)
                        if (h != hour) {
                            hour = h
                            store.setSelfStyleHour(h)
                        }
                    },
                    // 改完再重排：拖动过程中每变一格都去动 WorkManager 太浪费
                    onValueChangeFinished = { if (on) SelfStyle.schedule(context, hour) },
                    valueRange = 0f..23f,
                    steps = 22,
                )
            }
        }

        item {
            GlassCard(glassAlpha) {
                Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                    StatCell("$samples", "条样本", Modifier.weight(1f))
                    StatCell(if (skillAt > 0) formatTime(skillAt) else "—", "上次生成", Modifier.weight(1f))
                }
                if (skill.isBlank()) {
                    HintText(
                        "还没有生成过。攒够 ${StyleSkill.MIN_SAMPLES} 条样本之后，可以点下面手动生成一次（不用等到中午）。",
                        fontSize = 13.sp,
                        color = palette.sub,
                        modifier = Modifier.padding(top = 10.dp),
                    )
                } else {
                    Text(
                        "当前风格档案",
                        fontSize = 13.sp,
                        color = palette.sub,
                        modifier = Modifier.padding(top = 10.dp),
                    )
                    Text(
                        skill,
                        fontSize = 13.sp,
                        color = palette.text,
                        modifier = Modifier
                            .padding(top = 4.dp)
                            .fillMaxWidth()
                            .clip(RoundedCornerShape(RadiusR2))
                            .background(palette.glassTint.copy(alpha = 0.10f))
                            .padding(10.dp),
                    )
                }
                msg?.let {
                    Text(it, fontSize = 13.sp, color = palette.primary, modifier = Modifier.padding(top = 8.dp))
                }
                Row(Modifier.padding(top = 12.dp), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    GlassPill(if (busy) "生成中…" else "立即生成", selected = false) {
                        if (busy) return@GlassPill
                        busy = true
                        msg = null
                        scope.launch {
                            val r = withContext(Dispatchers.IO) {
                                runCatching { SelfStyle.generateNow(store) }
                            }
                            busy = false
                            msg = r.fold({ it }, { it.message ?: "生成失败" })
                            tick++
                        }
                    }
                    if (samples > 0) {
                        GlassPill("清空样本", selected = false) {
                            store.clearRoleMsgs(SELF_ROLE_KEY)
                            msg = "已清空采集到的样本"
                            tick++
                        }
                    }
                }
            }
        }

        // 底部导航是浮在上面的，留出空位免得被它挡住
        item { Spacer(Modifier.height(110.dp)) }
    }
}

/**
 * 组装诊断包。
 *
 * 刻意**不含 API Key** —— 诊断包是要发给别人的，Key 不该跟着走。
 * 里面的 04 / 05 会带聊天内容，所以说明文件（00）里明确提醒了「发之前先看一眼」。
 */
private fun buildDiagZip(context: Context, store: ConfigStore, cfg: ConfigData): ByteArray {
    val roles = store.roles()
    val (calls, tokens) = store.usage()
    val lowRam = isLowRamDevice(context)
    val stamp = java.text.SimpleDateFormat("yyyy-MM-dd HH:mm", java.util.Locale.getDefault())
        .format(java.util.Date())
    return DiagExport.zip(
        linkedMapOf(
            "00-说明.txt" to DiagExport.readme(appVersion(context)),
            "01-环境.txt" to buildString {
                append("App 版本：${appVersion(context)}\n")
                append("Android：SDK ${Build.VERSION.SDK_INT}（${Build.VERSION.RELEASE}）\n")
                append("机型：${Build.MANUFACTURER} ${Build.MODEL}\n")
                append("低内存设备：$lowRam\n")
                append("玻璃效果：设置=${cfg.glassQuality} → 实际=${decideGlassQuality(cfg.glassQuality, lowRam, Build.VERSION.SDK_INT)}\n")
                append("导出时间：$stamp\n")
            },
            "02-配置.txt" to buildString {
                append("接口地址：${cfg.baseUrl}\n")
                append("模型：${cfg.model}\n")
                append("生成模式：")
                append(
                    if (cfg.graded) {
                        "模型分级（第二套：${cfg.baseUrl2.ifBlank { "复用第一套" }} / ${cfg.model2.ifBlank { "复用" }}）"
                    } else {
                        "直通"
                    },
                )
                append("\n参考条数：${cfg.ctx}　最短间隔：${cfg.minIntervalSec}s　temperature：${cfg.temperature}　max_tokens：${cfg.maxTokens}\n")
                append("严格 JSON 输出：${cfg.jsonMode}　敏感内容检查：${!cfg.allowSensitive}\n")
                append("当前 skill：${cfg.skillId}　提示词 ${cfg.prompt.length} 字　含 JSON 契约：${cfg.prompt.contains("replies")}\n")
                // 识图排不进问题时最需要这两行：开关到底开没开、和「本地代理」有没有配套
                val (ocrLine, ocrAt) = store.ocrInfo()
                val ocrWhen = if (ocrAt > 0L) {
                    " · " + java.text.SimpleDateFormat("MM-dd HH:mm", java.util.Locale.getDefault())
                        .format(java.util.Date(ocrAt))
                } else {
                    ""
                }
                append("图片文字识别：${if (cfg.ocrEnabled) "开" else "关"}　本地代理：${if (cfg.proxyEnabled) "开" else "关"}\n")
                append("最近一次识图：${ocrLine.ifBlank { "（还没有记录）" }}$ocrWhen\n")
                append("API Key：已省略（不导出）\n")
            },
            "03-角色.txt" to buildString {
                append("角色数：${roles.size}\n")
                roles.forEach {
                    append("· ${it.name}（key=${it.key}）关系=${it.relation.ifBlank { "-" }} 记录=${it.msgs.size} 条\n")
                }
            },
            "04-诊断.txt" to store.diag().ifBlank { "（还没有诊断数据：到「诊断」页点一次「抓取微信界面」）" },
            "05-最近一次调用.txt" to store.lastCall().ifBlank { "（还没有调用记录）" },
            "06-用量.txt" to "调用次数：$calls\n累计 token：$tokens\n",
            "07-决策轨迹.txt" to store.trace().ifBlank {
                "（还没有轨迹：在微信里打开一个聊天页停一会儿，轨迹会自动回传）"
            },
        ),
    )
}

/**
 * 「回到前台」的信号：每次 ON_RESUME 自增，给 [produceState] 当重启键。
 *
 * 为什么需要：页面上的那些「注入侧广播回传」的状态（会话名候选、识图结果）都是靠
 * **有上限的轮询**刷新的（5 分钟就停了）。而典型用法是「打开看一眼 → 去微信里试 → 切回来」，
 * 那时候轮询早就停了，页面会一直显示旧值 —— 真机反馈过
 * 「微信里明明认了字，App 里还是『还没试过』」，就是它。
 */
@Composable
private fun resumedTick(): Int {
    var tick by remember { mutableIntStateOf(0) }
    LifecycleEventEffect(Lifecycle.Event.ON_RESUME) { tick++ }
    return tick
}

/**
 * 「贴在界面上的纸条」：白底 + 深色字、完全不透明，扫一眼就能读到，不会和背景混在一起。
 *
 * 和玻璃卡片刻意不同 —— 玻璃是界面的一部分，白卡是**说明书 / 公告**：说明、注意事项、
 * 延迟数字这类「要看清」的东西用它；能塞进玻璃卡的普通内容就别用，不然整页会花。
 */
@Composable
fun WhiteCard(modifier: Modifier = Modifier, content: @Composable ColumnScope.() -> Unit) {
    Column(
        modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(RadiusR2))
            .background(Color.White)
            .padding(12.dp),
        content = content,
    )
}

/** 白底公告栏：一行标题 + 若干行小字（[WhiteCard] 的一种用法）。 */
@Composable
fun NoticeBanner(title: String, lines: List<String>) {
    WhiteCard {
        Text(title, fontSize = 13.sp, fontWeight = FontWeight.Medium, color = Color(0xFF1C1B22))
        lines.forEach { line ->
            Text(
                line,
                fontSize = 13.sp,
                color = Color(0xFF55525E),
                modifier = Modifier.padding(top = 3.dp),
            )
        }
    }
}

/** 时间戳 -> 本地 HH:mm（只用在「上次拉取于 …」这种一句话里）。 */
/**
 * 白名单 / 候选共用的一行：不在多选态时右边是一个文字动作；**长按**任意一项进入多选。
 */
@OptIn(ExperimentalFoundationApi::class)
@Composable
private fun PickRow(
    label: String,
    picking: Boolean,
    selected: Boolean,
    palette: Palette,
    actionLabel: String,
    actionColor: Color,
    onToggle: () -> Unit,
    onLongPress: () -> Unit,
    onAction: () -> Unit,
) {
    Row(
        verticalAlignment = Alignment.CenterVertically,
        modifier = Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(RadiusR1))
            .combinedClickable(
                onClick = { if (picking) onToggle() },
                onLongClick = onLongPress,
            ),
    ) {
        if (picking) {
            Checkbox(
                checked = selected,
                onCheckedChange = { onToggle() },
                modifier = Modifier.size(26.dp),
            )
            Spacer(Modifier.width(2.dp))
        }
        Text(
            label,
            fontSize = 13.sp,
            color = palette.text,
            modifier = Modifier.weight(1f),
        )
        if (!picking) {
            Text(
                actionLabel,
                fontSize = 13.sp,
                color = actionColor,
                modifier = Modifier
                    .clip(RoundedCornerShape(RadiusR1))
                    .clickable { onAction() }
                    .padding(horizontal = 8.dp, vertical = 4.dp),
            )
        }
    }
}

/** 多选态的顶部：已选几个 / 全选 / 退出多选，外加两个批量动作。 */
@Composable
private fun PickBar(
    count: Int,
    total: Int,
    palette: Palette,
    primaryLabel: String,
    onPrimary: () -> Unit,
    dangerLabel: String,
    onDanger: () -> Unit,
    onSelectAll: () -> Unit,
    onCancel: () -> Unit,
) {
    Text("已选 $count / $total（长按进入多选，点一下勾选）", fontSize = 13.sp, color = palette.text)
    Spacer(Modifier.height(4.dp))
    Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(4.dp)) {
        Text(
            if (total > 0 && count >= total) "取消全选" else "全选",
            fontSize = 13.sp,
            color = palette.primary,
            modifier = Modifier
                .clip(RoundedCornerShape(RadiusR1))
                .clickable { onSelectAll() }
                .padding(horizontal = 6.dp, vertical = 4.dp),
        )
        Text(
            "退出多选",
            fontSize = 13.sp,
            color = palette.sub,
            modifier = Modifier
                .clip(RoundedCornerShape(RadiusR1))
                .clickable { onCancel() }
                .padding(horizontal = 6.dp, vertical = 4.dp),
        )
    }
    Spacer(Modifier.height(4.dp))
    Row(horizontalArrangement = Arrangement.spacedBy(8.dp), modifier = Modifier.fillMaxWidth()) {
        Button(
            onClick = onPrimary,
            enabled = count > 0,
            modifier = Modifier.weight(1f).height(42.dp),
            shape = RoundedCornerShape(RadiusR2),
            colors = ButtonDefaults.buttonColors(containerColor = palette.primary),
        ) { Text(primaryLabel, fontSize = 13.sp) }
        OutlinedButton(
            onClick = onDanger,
            enabled = count > 0,
            modifier = Modifier.weight(1f).height(42.dp),
            shape = RoundedCornerShape(RadiusR2),
        ) { Text(dangerLabel, fontSize = 13.sp, color = palette.bad) }
    }
}

private fun clockText(at: Long): String =
    java.text.SimpleDateFormat("HH:mm", java.util.Locale.getDefault()).format(java.util.Date(at))
