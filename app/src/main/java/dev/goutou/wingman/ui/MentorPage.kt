package dev.goutou.wingman.ui

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.AutoAwesome
import androidx.compose.material.icons.outlined.ChatBubbleOutline
import androidx.compose.material.icons.outlined.CheckCircle
import androidx.compose.material.icons.outlined.Code
import androidx.compose.material.icons.outlined.Edit
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import dev.goutou.wingman.config.ConfigStore
import dev.goutou.wingman.llm.BUILT_IN_SKILLS
import dev.goutou.wingman.llm.RemoteSkill
import dev.goutou.wingman.llm.ensureJsonContract
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/** 军师选择、文件导入直接可见；长提示词在独立编辑页修改。 */
@Suppress("UNUSED_PARAMETER")
@Composable
fun MentorScreen(store: ConfigStore, glassAlpha: Float, onSaved: () -> Unit) {
    val palette = LocalPalette.current
    val scope = rememberCoroutineScope()
    var config by remember { mutableStateOf(store.load()) }
    var url by rememberSaveable { mutableStateOf("") }
    var importing by remember { mutableStateOf(false) }
    var notice by remember { mutableStateOf<String?>(null) }
    var importNotice by remember { mutableStateOf<String?>(null) }
    var importFailed by remember { mutableStateOf(false) }
    var editing by rememberSaveable { mutableStateOf(false) }
    var editBasePrompt by rememberSaveable { mutableStateOf(config.prompt) }
    var editBaseSkill by rememberSaveable { mutableStateOf(config.skillId) }
    var draft by rememberSaveable { mutableStateOf(config.prompt) }
    var conflict by rememberSaveable { mutableStateOf(false) }
    var confirmDiscard by remember { mutableStateOf(false) }
    var confirmReset by remember { mutableStateOf(false) }
    var confirmReload by remember { mutableStateOf(false) }

    val dirty = draft != editBasePrompt
    val hasContract = draft.contains("replies") && draft.contains("intent")
    val canSave = draft.isNotBlank() && (dirty || !hasContract) && !conflict
    val activePreset = BUILT_IN_SKILLS.firstOrNull { it.id == config.skillId }
    val activeName = activePreset?.name ?: "自定义军师"

    fun persist(prompt: String, skillId: String, unlimited: Boolean) {
        // 只从最新配置改军师字段，不把打开页面时的接口、白名单等旧值写回去。
        val latest = store.load()
        store.save(latest.copy(prompt = prompt, skillId = skillId, maxTokens = if (unlimited) 0 else latest.maxTokens))
        config = store.load()
        onSaved()
    }

    fun loadEditor() {
        config = store.load()
        editBasePrompt = config.prompt
        editBaseSkill = config.skillId
        draft = config.prompt
        conflict = false
        editing = true
    }

    fun closeEditor() {
        if (dirty) confirmDiscard = true else editing = false
    }

    fun saveEditor() {
        val latest = store.load()
        if (latest.prompt != editBasePrompt || latest.skillId != editBaseSkill) {
            conflict = true
            return
        }
        val prompt = ensureJsonContract(draft)
        val known = BUILT_IN_SKILLS.firstOrNull { it.prompt == prompt }?.id
        persist(prompt, known ?: "custom", unlimited = false)
        editBasePrompt = config.prompt
        editBaseSkill = config.skillId
        draft = config.prompt
        editing = false
        importNotice = null
        notice = "提示词已保存"
    }

    if (editing) {
        HyperPage(
            title = "提示词",
            subtitle = activeName,
            onBack = { closeEditor() },
            actions = {
                TextButton(onClick = { saveEditor() }, enabled = canSave) {
                    Text("保存", fontWeight = FontWeight.SemiBold)
                }
            },
        ) {
            if (conflict) {
                HyperGroup {
                    Column(Modifier.padding(horizontal = 20.dp, vertical = 12.dp)) {
                        Text("当前军师已在别处修改，尚未覆盖你的草稿。", color = palette.warn, fontSize = 14.sp)
                        TextButton(onClick = { confirmReload = true }) { Text("载入当前版本") }
                    }
                }
            }
            HyperGroup {
                Column(Modifier.fillMaxWidth().padding(20.dp)) {
                    Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                        Text(
                            if (dirty) "未保存" else "已保存",
                            color = if (dirty) palette.warn else palette.sub,
                            fontSize = 13.sp,
                            modifier = Modifier.weight(1f),
                        )
                        Text("${draft.length} 字", color = palette.sub, fontSize = 13.sp)
                    }
                    Spacer(Modifier.height(10.dp))
                    OutlinedTextField(
                        value = draft,
                        onValueChange = { draft = it },
                        label = { Text("军师提示词") },
                        colors = glassFieldColors(),
                        shape = RoundedCornerShape(RadiusR2),
                        modifier = Modifier.fillMaxWidth().heightIn(min = 320.dp, max = 560.dp),
                    )
                    Spacer(Modifier.height(12.dp))
                    Text(
                        if (hasContract) "回复输出格式已就绪" else "保存时会补齐回复输出格式",
                        color = if (hasContract) palette.sub else palette.warn,
                        fontSize = 13.sp,
                    )
                    Spacer(Modifier.height(12.dp))
                    Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                        OutlinedButton(
                            onClick = { confirmReset = true },
                            shape = RoundedCornerShape(RadiusR2),
                            modifier = Modifier.weight(1f),
                        ) { Text(if (activePreset == null) "恢复默认" else "恢复预设") }
                        Button(
                            onClick = { saveEditor() },
                            enabled = canSave,
                            colors = ButtonDefaults.buttonColors(containerColor = palette.primary),
                            shape = RoundedCornerShape(RadiusR2),
                            modifier = Modifier.weight(1f),
                        ) { Text("保存提示词") }
                    }
                }
            }
        }
    } else {
        HyperPage(title = "军师", subtitle = "选择回复风格") {
            HyperGroup(title = "正在使用") {
                HyperRow(
                    title = activeName,
                    subtitle = if (activePreset?.prompt == config.prompt) mentorSummary(config.skillId) else "自定义提示词 · ${config.prompt.length} 字",
                    icon = Icons.Outlined.AutoAwesome,
                    trailing = {
                        Icon(Icons.Outlined.CheckCircle, contentDescription = "正在使用", tint = palette.primary)
                    },
                )
                HyperRow(
                    title = "编辑提示词",
                    subtitle = "查看全文与调整回复要求",
                    icon = Icons.Outlined.Edit,
                    onClick = if (importing) null else { { loadEditor() } },
                )
                notice?.let {
                    Text(
                        it,
                        fontSize = 13.sp,
                        color = palette.ok,
                        modifier = Modifier.padding(horizontal = 20.dp, vertical = 8.dp),
                    )
                }
            }

            HyperGroup(title = "内置军师") {
                BUILT_IN_SKILLS.forEach { preset ->
                    val selected = config.skillId == preset.id && config.prompt == preset.prompt
                    HyperRow(
                        title = preset.name,
                        subtitle = mentorSummary(preset.id),
                        icon = when (preset.id) {
                            "coder" -> Icons.Outlined.Code
                            "full" -> Icons.Outlined.AutoAwesome
                            else -> Icons.Outlined.ChatBubbleOutline
                        },
                        value = if (selected) "已启用" else "启用",
                        onClick = if (importing || selected) null else {
                            {
                                persist(preset.prompt, preset.id, unlimited = true)
                                importNotice = null
                                notice = "已启用${preset.name} · 输出长度不限"
                            }
                        },
                    )
                }
            }

            HyperGroup(title = "从 GitHub 导入") {
                Column(Modifier.fillMaxWidth().padding(20.dp)) {
                    OutlinedTextField(
                        value = url,
                        onValueChange = { url = it },
                        enabled = !importing,
                        label = { Text("SKILL.md 文件链接") },
                        placeholder = { Text("https://github.com/…/SKILL.md") },
                        colors = glassFieldColors(),
                        shape = RoundedCornerShape(RadiusR2),
                        singleLine = true,
                        modifier = Modifier.fillMaxWidth(),
                    )
                    Spacer(Modifier.height(10.dp))
                    Button(
                        onClick = {
                            val target = url.trim()
                            val initial = store.load()
                            importing = true
                            notice = null
                            importNotice = null
                            importFailed = false
                            scope.launch {
                                try {
                                    val fetched = withContext(Dispatchers.IO) { RemoteSkill.fetch(target) }
                                    val latest = store.load()
                                    if (latest.prompt != initial.prompt || latest.skillId != initial.skillId) {
                                        config = latest
                                        importFailed = true
                                        importNotice = "当前军师已更改，导入结果未覆盖。请重新导入。"
                                    } else {
                                        persist(fetched, "custom", unlimited = true)
                                        importNotice = "已导入并启用 · ${fetched.length} 字 · 输出长度不限"
                                    }
                                } catch (cancelled: CancellationException) {
                                    throw cancelled
                                } catch (t: Throwable) {
                                    importFailed = true
                                    importNotice = "导入失败：${t.message ?: "请检查文件链接与网络"}"
                                } finally {
                                    importing = false
                                }
                            }
                        },
                        enabled = !importing && url.isNotBlank(),
                        colors = ButtonDefaults.buttonColors(containerColor = palette.primary),
                        shape = RoundedCornerShape(RadiusR2),
                        modifier = Modifier.fillMaxWidth().heightIn(min = 48.dp),
                    ) {
                        if (importing) {
                            CircularProgressIndicator(Modifier.size(18.dp), color = palette.glassTint, strokeWidth = 2.dp)
                            Spacer(Modifier.width(8.dp))
                        }
                        Text(if (importing) "正在导入" else "导入并启用")
                    }
                    importNotice?.let {
                        Text(
                            it,
                            fontSize = 13.sp,
                            color = if (importFailed) palette.bad else palette.ok,
                            modifier = Modifier.padding(top = 8.dp),
                        )
                    }
                    Text(
                        "自动补齐回复输出格式",
                        color = palette.sub,
                        fontSize = 12.sp,
                        modifier = Modifier.padding(top = 8.dp),
                    )
                }
            }
        }
    }

    if (confirmDiscard) {
        AlertDialog(
            onDismissRequest = { confirmDiscard = false },
            title = { Text("放弃未保存的修改？") },
            text = { Text("当前正在使用的提示词不会改变。") },
            confirmButton = {
                TextButton(onClick = { confirmDiscard = false; editing = false }) { Text("放弃修改") }
            },
            dismissButton = { TextButton(onClick = { confirmDiscard = false }) { Text("继续编辑") } },
        )
    }
    if (confirmReset) {
        val preset = activePreset ?: BUILT_IN_SKILLS.first()
        AlertDialog(
            onDismissRequest = { confirmReset = false },
            title = { Text("恢复「${preset.name}」？") },
            text = { Text("替换当前编辑内容，保存后生效。") },
            confirmButton = {
                TextButton(onClick = { draft = preset.prompt; confirmReset = false }) { Text("恢复") }
            },
            dismissButton = { TextButton(onClick = { confirmReset = false }) { Text("取消") } },
        )
    }
    if (confirmReload) {
        AlertDialog(
            onDismissRequest = { confirmReload = false },
            title = { Text("载入当前版本？") },
            text = { Text("当前编辑内容将被替换。") },
            confirmButton = {
                TextButton(onClick = { confirmReload = false; loadEditor() }) { Text("载入") }
            },
            dismissButton = { TextButton(onClick = { confirmReload = false }) { Text("取消") } },
        )
    }
}

private fun mentorSummary(id: String): String = when (id) {
    "classic" -> "日常聊天、邀约与朋友沟通"
    "full" -> "更细的意图分析与风险判断"
    "coder" -> "需求、排期与技术沟通"
    else -> "自定义回复风格"
}
