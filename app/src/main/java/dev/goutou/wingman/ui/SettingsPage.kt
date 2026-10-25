package dev.goutou.wingman.ui

import android.content.Intent
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.AutoAwesome
import androidx.compose.material.icons.outlined.Backup
import androidx.compose.material.icons.outlined.BugReport
import androidx.compose.material.icons.outlined.ChatBubbleOutline
import androidx.compose.material.icons.outlined.Code
import androidx.compose.material.icons.outlined.Group
import androidx.compose.material.icons.outlined.Image
import androidx.compose.material.icons.outlined.Info
import androidx.compose.material.icons.outlined.Palette
import androidx.compose.material.icons.outlined.Security
import androidx.compose.material.icons.outlined.Tune
import androidx.compose.material3.Slider
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import dev.goutou.wingman.config.RemoteSync
import kotlinx.coroutines.launch
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalUriHandler
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import dev.goutou.wingman.config.ConfigData
import dev.goutou.wingman.config.ConfigStore
import dev.goutou.wingman.config.GLASS_QUALITY_AUTO
import dev.goutou.wingman.config.GLASS_QUALITY_HIGH
import dev.goutou.wingman.config.GLASS_QUALITY_LOW

@Composable
fun SettingsScreen(
    store: ConfigStore,
    ui: ConfigData,
    onOpenAdvanced: () -> Unit,
    onOpenSection: (SettingsSection) -> Unit = { onOpenAdvanced() },
    onOpenDiag: () -> Unit = onOpenAdvanced,
    onOpenCandidates: () -> Unit = onOpenAdvanced,
    onOpenAppearance: () -> Unit = onOpenAdvanced,
    onOpenAbout: () -> Unit = onOpenAdvanced,
    onSaved: () -> Unit = {},
) {
    var current by remember(ui) { mutableStateOf(ui) }
    var qqNote by remember { mutableStateOf<String?>(null) }
    val scope = rememberCoroutineScope()
    fun automatic(on: Boolean) {
        store.save(store.load().copy(enabled = on))
        current = store.load()
        onSaved()
    }
    HyperPage("设置") {
        HyperGroup {
            HyperRow("自动分析", if (current.enabled) "打开聊天时生成建议" else "仅在手动识别时分析", Icons.Outlined.ChatBubbleOutline,
                trailing = { Switch(current.enabled, onCheckedChange = { automatic(it) }) })
        }
        HyperGroup("模型与回复") {
            HyperRow("接口与模型", current.model.ifBlank { "尚未设置" }, Icons.Outlined.Tune, onClick = { onOpenSection(SettingsSection.CONNECTION) })
            HyperRow("生成方式", if (current.graded) "模型分级 · 回复与风险独立处理" else "直通 · 一套接口", Icons.Outlined.AutoAwesome, onClick = { onOpenSection(SettingsSection.GENERATION) })
        }
        HyperGroup("微信助手") {
            HyperRow("分析设置", "参考 ${current.ctx} 条 · 间隔 ${current.minIntervalSec} 秒", Icons.Outlined.ChatBubbleOutline, onClick = { onOpenSection(SettingsSection.WECHAT) })
            HyperRow("会话白名单", if (current.whitelistEnabled) "已启用 · ${current.whitelist.size} 个会话" else "未启用 · 处理所有会话", Icons.Outlined.Security, onClick = { onOpenSection(SettingsSection.WHITELIST) })
            HyperRow("会话列表", "选择要加入白名单的会话", Icons.Outlined.Group, onClick = onOpenCandidates)
            HyperRow("识图与代理", "OCR ${if (current.ocrEnabled) "开" else "关"} · 本地代理 ${if (current.proxyEnabled) "开" else "关"}", Icons.Outlined.Image, onClick = { onOpenSection(SettingsSection.RECOGNITION) })
        }
        HyperGroup("QQ") {
            HyperRow("QQ 浮窗", "默认关闭；启用后手动识别。需在框架作用域勾选 QQ 并重启 QQ。", Icons.Outlined.ChatBubbleOutline,
                trailing = { Switch(current.qqEnabled, onCheckedChange = { enabled ->
                    store.save(store.load().copy(qqEnabled = enabled))
                    current = store.load()
                    onSaved()
                    if (enabled) RemoteSync.requestQqScope { _, message ->
                        scope.launch { if (store.load().qqEnabled) qqNote = message }
                    } else qqNote = null
                }) })
            qqNote?.let { Text(it, Modifier.padding(horizontal = 20.dp, vertical = 8.dp), fontSize = 13.sp, color = LocalPalette.current.sub) }
        }
        HyperGroup("应用") {
            HyperRow("外观", "液态玻璃、效果品质与背景", Icons.Outlined.Palette, onClick = onOpenAppearance)
            HyperRow("备份与恢复", "导入或导出配置与角色", Icons.Outlined.Backup, onClick = { onOpenSection(SettingsSection.BACKUP) })
            HyperRow("诊断与日志", "网络、界面快照与请求记录", Icons.Outlined.BugReport, onClick = onOpenDiag)
            HyperRow("关于 TalkTact", "版本与隐私", Icons.Outlined.Info, onClick = onOpenAbout)
        }
    }
}

@Composable
fun AppearanceScreen(store: ConfigStore, onSaved: () -> Unit, onBack: () -> Unit) {
    val context = LocalContext.current
    val palette = LocalPalette.current
    var current by remember { mutableStateOf(store.load()) }
    var note by remember { mutableStateOf<String?>(null) }
    fun save(transform: (ConfigData) -> ConfigData) {
        store.save(transform(store.load()))
        current = store.load()
        onSaved()
    }
    val pickBackground = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocument()) { uri ->
        if (uri != null) {
            try {
                context.contentResolver.takePersistableUriPermission(uri, Intent.FLAG_GRANT_READ_URI_PERMISSION)
                save { it.copy(bgUri = uri.toString()) }
                note = null
            } catch (_: Exception) { note = "无法读取这张图片，请重新选择" }
        }
    }
    HyperPage("外观", onBack = onBack) {
        HyperGroup("液态玻璃") {
            HyperRow("效果品质", "高品质保留边缘折射、中央柔焦和高光")
            Row(Modifier.fillMaxWidth().padding(horizontal = 20.dp, vertical = 8.dp), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                listOf("自动" to GLASS_QUALITY_AUTO, "高" to GLASS_QUALITY_HIGH, "低" to GLASS_QUALITY_LOW).forEach { (label, value) ->
                    GlassPill(label, current.glassQuality == value, Modifier.weight(1f)) { save { it.copy(glassQuality = value) } }
                }
            }
            HyperRow("自动降级", "低内存设备关闭玻璃特效，Android 13 以下使用普通卡片")
        }
        HyperGroup("背景") {
            HyperRow("背景图片", if (current.bgUri.isBlank()) "使用默认纯色背景" else "已使用自选图片", Icons.Outlined.Image, onClick = { pickBackground.launch(arrayOf("image/*")) })
            if (current.bgUri.isNotBlank()) {
                HyperRow("恢复默认背景", onClick = { save { it.copy(bgUri = "") } })
                Column(Modifier.padding(horizontal = 20.dp)) {
                    Text("背景压暗 · ${(current.bgDim * 100).toInt()}%", fontSize = 14.sp, color = palette.text)
                    Slider(current.bgDim, onValueChange = { current = current.copy(bgDim = it) }, onValueChangeFinished = { val dim = current.bgDim; save { it.copy(bgDim = dim) } })
                    Text("图片柔焦 · ${current.glassBlur.toInt()} dp", fontSize = 14.sp, color = palette.text)
                    Slider(current.glassBlur, onValueChange = { current = current.copy(glassBlur = it) }, valueRange = 0f..40f, onValueChangeFinished = { val blur = current.glassBlur; save { it.copy(glassBlur = blur) } })
                }
            }
            note?.let { Text(it, Modifier.padding(20.dp), fontSize = 13.sp, color = palette.bad) }
        }
    }
}

@Composable
fun AboutScreen(onBack: () -> Unit) {
    val context = LocalContext.current
    val uriHandler = LocalUriHandler.current
    HyperPage("关于 TalkTact", onBack = onBack) {
        HyperGroup {
            HyperRow("TalkTact", "微信聊天助手", Icons.Outlined.ChatBubbleOutline)
            HyperRow("版本", value = appVersion(context))
            HyperRow("许可证", value = "GPL-3.0")
            HyperRow("独立二改", "Sakura520222 · 原作者 shibry88-netizen")
            HyperRow("GitHub 仓库", "github.com/Sakura520222/TalkTact", Icons.Outlined.Code,
                onClick = { uriHandler.openUri("https://github.com/Sakura520222/TalkTact") })
        }
        HyperGroup("隐私与使用") {
            HyperRow("发送由你决定", "候选仅在点击后填入输入框，不会自动发送。")
            HyperRow("聊天内容", "分析时会发送到你配置的模型接口，请选择可信服务。")
            HyperRow("API Key", "保存在应用私有目录。使用本地代理时，凭据不会交给微信进程。")
            HyperRow("离线识图", "原始图片不上传、不落盘；识别的文字会随上下文发往模型。")
            HyperRow("诊断包", "仅主动导出；可能含聊天内容，不包含密钥。")
            HyperRow("使用风险", "模块会修改微信客户端行为，建议先使用测试账号。")
        }
    }
}
