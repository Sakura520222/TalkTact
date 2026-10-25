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
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.ChatBubbleOutline
import androidx.compose.material.icons.outlined.ContentCopy
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalClipboardManager
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.compose.LifecycleEventEffect
import dev.goutou.wingman.config.ConfigData
import dev.goutou.wingman.config.ConfigStore
import dev.goutou.wingman.llm.Graded
import dev.goutou.wingman.llm.LlmClient
import dev.goutou.wingman.llm.REWRITE_PRESETS
import dev.goutou.wingman.llm.Suggestion
import dev.goutou.wingman.llm.describe
import dev.goutou.wingman.llm.gradedWaitMs
import dev.goutou.wingman.llm.isReplyTooLong
import dev.goutou.wingman.wechat.ChatMsg
import dev.goutou.wingman.wechat.Sensitive
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

private const val TRIAL_EXAMPLE = "对方: 在吗\n我: 在\n对方: 周末有空吗，想约你吃个饭"
private val trialSpeakerPrefix = Regex("^(我|对方)\\s*[:：]\\s*(.*)$")

/** 只去掉行首的说话人标记，保留正文里的冒号（时间、网址等）。 */
internal fun trialMessagesOf(input: String): List<ChatMsg> = input.lineSequence()
    .map(String::trim)
    .filter(String::isNotBlank)
    .map { line ->
        val tagged = trialSpeakerPrefix.matchEntire(line)
        ChatMsg(
            fromMe = tagged?.groupValues?.get(1) == "我",
            text = tagged?.groupValues?.get(2)?.trim()?.ifBlank { line } ?: line,
        )
    }.toList()

private data class TrialOutcome(
    val suggestion: Suggestion?,
    val tokens: Int,
    val info: String,
    val error: String? = null,
)

/** 两套接口的成败仍交给 Graded 合并，这里只把一次结果带回 UI。 */
private fun generateTrial(config: ConfigData, input: String): TrialOutcome {
    val messages = trialMessagesOf(input)
    return if (config.graded) {
        val result = Graded.run(
            replyClient = LlmClient(config),
            riskClient = LlmClient(config.riskEndpoint(), second = true),
            skillPrompt = config.prompt,
            msgs = messages,
            roleContext = null,
            waitMs = gradedWaitMs(config.probeReplyMs, config.probeRiskMs),
        )
        TrialOutcome(
            suggestion = result.suggestion,
            tokens = result.totalTokens,
            info = "${result.millis} ms · ${result.totalTokens} token · 模型分级",
            error = if (result.suggestion == null) {
                "风险评估：${result.riskError ?: "未完成"}\n候选回复：${result.replyError ?: "未完成"}"
            } else null,
        )
    } else {
        val result = LlmClient(config).analyze(messages)
        TrialOutcome(
            suggestion = result.suggestion,
            tokens = result.totalTokens,
            info = "${result.millis} ms · ${result.totalTokens} token · ${result.model}",
        )
    }
}

@Composable
fun TrialScreen(store: ConfigStore, glassAlpha: Float) {
    val palette = LocalPalette.current
    val clipboard = LocalClipboardManager.current
    val scope = rememberCoroutineScope()
    val config = remember { store.load() }
    var input by rememberSaveable { mutableStateOf(TRIAL_EXAMPLE) }
    var suggestion by remember { mutableStateOf<Suggestion?>(null) }
    var loading by remember { mutableStateOf(false) }
    var rewriting by remember { mutableStateOf<Int?>(null) }
    var error by remember { mutableStateOf<String?>(null) }
    var info by remember { mutableStateOf<String?>(null) }
    var copied by remember { mutableStateOf<Int?>(null) }
    var revision by remember { mutableIntStateOf(0) }
    var operation by remember { mutableStateOf<Job?>(null) }
    val busy = loading || rewriting != null
    val hits = remember(input) { Sensitive.hits(input) }

    fun cancelOperation() {
        revision++
        operation?.cancel()
        operation = null
        loading = false
        rewriting = null
    }

    // 同步网络调用可能等到超时才退出，但取消后旧结果不能再回写这个页面。
    LifecycleEventEffect(Lifecycle.Event.ON_STOP) { cancelOperation() }
    DisposableEffect(Unit) { onDispose { cancelOperation() } }

    fun editInput(next: String) {
        if (busy) return
        revision++
        input = next
        suggestion = null
        copied = null
        error = null
        info = null
    }

    fun generate() {
        if (busy || input.isBlank()) return
        val request = ++revision
        val requestInput = input
        val requestConfig = store.load()
        error = null
        info = null
        suggestion = null
        copied = null
        loading = true
        operation = scope.launch {
            try {
                val result = withContext(Dispatchers.IO) { generateTrial(requestConfig, requestInput) }
                if (request != revision || input != requestInput) return@launch
                if (result.tokens > 0) store.addUsage(result.tokens)
                suggestion = result.suggestion
                info = result.info
                error = result.error
            } catch (cancelled: CancellationException) {
                throw cancelled
            } catch (failure: Throwable) {
                if (request == revision) error = describe(failure)
            } finally {
                if (request == revision) {
                    loading = false
                    operation = null
                }
            }
        }
    }

    fun rewrite(index: Int, instruction: String) {
        if (busy) return
        val original = suggestion ?: return
        val reply = original.replies.getOrNull(index) ?: return
        val request = ++revision
        val requestConfig = store.load()
        rewriting = index
        copied = null
        error = null
        operation = scope.launch {
            try {
                val result = withContext(Dispatchers.IO) {
                    LlmClient(requestConfig).rewrite(reply.text, instruction)
                }
                if (request != revision || suggestion !== original) return@launch
                if (result.second > 0) store.addUsage(result.second)
                suggestion = original.copy(
                    replies = original.replies.mapIndexed { current, item ->
                        if (current == index) item.copy(text = result.first) else item
                    },
                )
            } catch (cancelled: CancellationException) {
                throw cancelled
            } catch (failure: Throwable) {
                if (request == revision) error = "改写失败：${describe(failure)}"
            } finally {
                if (request == revision) {
                    rewriting = null
                    operation = null
                }
            }
        }
    }

    HyperPage(title = "试一试", subtitle = "先预览，再决定怎么回复") {
        HyperGroup {
            HyperRow(
                title = if (config.graded) "模型分级" else "直通模式",
                subtitle = config.model.ifBlank { "尚未选择模型" },
                icon = Icons.Outlined.ChatBubbleOutline,
                value = skillName(config.skillId),
            )
        }

        GlassCard(glassAlpha) {
            Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                Text("聊天内容", fontSize = 17.sp, fontWeight = FontWeight.SemiBold, color = palette.text, modifier = Modifier.weight(1f))
                TextButton(onClick = { editInput(TRIAL_EXAMPLE) }, enabled = !busy) { Text("示例") }
                TextButton(onClick = { editInput("") }, enabled = !busy && input.isNotEmpty()) { Text("清空") }
            }
            OutlinedTextField(
                value = input,
                onValueChange = ::editInput,
                enabled = !busy,
                modifier = Modifier.fillMaxWidth().heightIn(min = 174.dp),
                shape = RoundedCornerShape(RadiusR2),
                colors = glassFieldColors(),
                label = { Text("我：… / 对方：…") },
                minLines = 5,
            )
            if (hits.isNotEmpty()) {
                Spacer(Modifier.height(10.dp))
                Text("含敏感内容：${hits.joinToString("、")}。生成时会发送到所配置的接口。", fontSize = 13.sp, color = palette.warn)
            }
            Spacer(Modifier.height(14.dp))
            Button(
                onClick = ::generate,
                enabled = !busy && input.isNotBlank(),
                modifier = Modifier.fillMaxWidth().heightIn(min = 52.dp),
                shape = RoundedCornerShape(RadiusR2),
                colors = ButtonDefaults.buttonColors(containerColor = palette.primary),
            ) {
                if (loading) {
                    CircularProgressIndicator(Modifier.size(18.dp), color = palette.glassTint, strokeWidth = 2.dp)
                    Text("  正在生成…")
                } else {
                    Text("生成候选回复", fontSize = 16.sp)
                }
            }
            Text("只生成预览，不会自动发送", modifier = Modifier.padding(top = 10.dp), fontSize = 12.sp, color = palette.sub)
        }

        error?.let { message ->
            GlassCard(glassAlpha, border = palette.badMark.copy(alpha = CardBorderAlpha)) {
                Text(if (suggestion == null) "未能生成" else "操作未完成", fontSize = 16.sp, fontWeight = FontWeight.SemiBold, color = palette.bad)
                Text(message, fontSize = 13.sp, color = palette.text)
                if (suggestion == null && !busy) TextButton(onClick = ::generate) { Text("重新生成") }
            }
        }

        val result = suggestion
        when {
            loading -> GlassCard(glassAlpha) {
                Text("正在理解这段聊天", fontSize = 17.sp, fontWeight = FontWeight.Medium, color = palette.text)
                Text("${if (config.graded) "风险评估与候选回复会一起返回" else "结果会显示在这里"}", fontSize = 13.sp, color = palette.sub)
                TextButton(onClick = { cancelOperation(); info = "已取消" }) { Text("取消生成") }
            }
            result == null && error == null -> GlassCard(glassAlpha) {
                Icon(Icons.Outlined.ChatBubbleOutline, contentDescription = null, tint = palette.primary, modifier = Modifier.size(30.dp))
                Spacer(Modifier.height(10.dp))
                Text("回复预览", fontSize = 18.sp, fontWeight = FontWeight.Medium, color = palette.text)
                Text(info ?: "输入聊天，比较不同语气的候选回复", fontSize = 13.sp, color = palette.sub)
            }
            result != null -> {
                HyperGroup(title = "分析结果") {
                    HyperRow(title = result.intent.ifBlank { "聊天分析" }, subtitle = result.note.ifBlank { null }, value = "风险 ${result.risk}")
                }
                if (result.partial) {
                    GlassCard(glassAlpha, border = palette.warnMark.copy(alpha = CardBorderAlpha)) {
                        Text("模型输出不完整，已保留可用内容", fontSize = 14.sp, color = palette.warn)
                        Text("可重新生成以获得完整结果", fontSize = 13.sp, color = palette.sub)
                    }
                }
                result.warnings.forEach { warning ->
                    GlassCard(glassAlpha, border = palette.warnMark.copy(alpha = CardBorderAlpha)) {
                        Text(warning, fontSize = 13.sp, color = palette.warn)
                    }
                }
                if (result.replies.isEmpty()) {
                    GlassCard(glassAlpha) {
                        Text("本次没有候选回复", fontSize = 17.sp, fontWeight = FontWeight.Medium, color = palette.text)
                        Text("已保留上面的风险评估，可以重新生成", fontSize = 13.sp, color = palette.sub)
                    }
                }
                result.replies.forEachIndexed { index, reply ->
                    val best = result.best == index
                    GlassCard(glassAlpha, border = if (best) palette.primary.copy(alpha = CardBorderAlpha) else null) {
                        Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                            Column(Modifier.weight(1f)) {
                                Text(reply.style, fontSize = 16.sp, fontWeight = FontWeight.SemiBold, color = palette.primary)
                                if (best) Text("推荐回复", fontSize = 12.sp, color = palette.sub)
                            }
                            TextButton(onClick = {
                                clipboard.setText(AnnotatedString(reply.text))
                                copied = index
                            }) {
                                Icon(Icons.Outlined.ContentCopy, contentDescription = null, modifier = Modifier.size(16.dp))
                                Text(if (copied == index) " 已复制" else " 复制")
                            }
                        }
                        Text(reply.text, fontSize = 17.sp, lineHeight = 26.sp, color = palette.text)
                        if (best && result.why.isNotBlank()) {
                            Text(result.why, modifier = Modifier.padding(top = 8.dp), fontSize = 13.sp, color = palette.sub)
                        }
                        if (isReplyTooLong(reply.text)) {
                            Text("${reply.text.length} 字 · 可用「再短点」精简", modifier = Modifier.padding(top = 8.dp), fontSize = 12.sp, color = palette.warn)
                        }
                        Spacer(Modifier.height(12.dp))
                        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                            REWRITE_PRESETS.forEach { preset ->
                                OutlinedButton(
                                    onClick = { rewrite(index, preset.second) },
                                    enabled = !busy,
                                    modifier = Modifier.weight(1f).heightIn(min = 42.dp),
                                    contentPadding = androidx.compose.foundation.layout.PaddingValues(horizontal = 4.dp, vertical = 8.dp),
                                    shape = RoundedCornerShape(RadiusR1),
                                ) { Text(preset.first, fontSize = 12.sp) }
                            }
                        }
                        if (rewriting == index) {
                            Row(Modifier.padding(top = 8.dp), verticalAlignment = Alignment.CenterVertically) {
                                CircularProgressIndicator(Modifier.size(14.dp), strokeWidth = 2.dp)
                                Text("  正在改写这一条", fontSize = 12.sp, color = palette.sub)
                                TextButton(onClick = ::cancelOperation) { Text("取消") }
                            }
                        }
                    }
                }
                info?.let { Text(it, modifier = Modifier.padding(horizontal = 22.dp, vertical = 8.dp), fontSize = 12.sp, color = palette.sub) }
            }
        }
    }
}
