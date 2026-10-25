package dev.goutou.wingman.ui

import androidx.activity.compose.BackHandler
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.Close
import androidx.compose.material.icons.outlined.ChevronRight
import androidx.compose.material.icons.outlined.PersonOutline
import androidx.compose.material.icons.outlined.RecordVoiceOver
import androidx.compose.material.icons.outlined.Refresh
import androidx.compose.material.icons.outlined.Search
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.compose.LifecycleEventEffect
import dev.goutou.wingman.config.ConfigStore
import dev.goutou.wingman.config.Role
import dev.goutou.wingman.config.Roles
import dev.goutou.wingman.config.SELF_ROLE_KEY
import dev.goutou.wingman.llm.StyleSkill
import java.util.Locale

/** 角色首页以搜索、本人风格和联系人为主；档案编辑仍沿用同一条保存与合并链路。 */
@OptIn(ExperimentalFoundationApi::class)
@Composable
fun RolesScreen(store: ConfigStore, glassAlpha: Float, open: String?, onOpen: (String?) -> Unit) {
    val palette = LocalPalette.current
    var tick by remember { mutableIntStateOf(0) }
    var query by remember { mutableStateOf("") }
    var pendingDelete by remember { mutableStateOf<String?>(null) }
    LifecycleEventEffect(Lifecycle.Event.ON_RESUME) { tick++ }

    // ConfigStore.roles() 保证本人条目常驻，不在界面层创建或改写它的 key。
    val roles = remember(store, tick) { store.roles() }
    val contacts = remember(roles) {
        roles.filterNot { it.key == SELF_ROLE_KEY }
            .sortedWith(compareByDescending<Role> { it.lastAt }.thenBy { it.name })
    }
    val current = open?.let { key -> roles.firstOrNull { it.key == key } }
    BackHandler(enabled = open != null) { onOpen(null) }
    LaunchedEffect(open, roles) {
        if (open != null && current == null) onOpen(null)
    }

    if (current != null) {
        RoleDetail(
            store = store,
            role = current,
            glassAlpha = glassAlpha,
            onBack = { onOpen(null); tick++ },
            onRename = { name -> store.renameRole(current.key, name); tick++ },
            onChanged = { tick++ },
        )
        return
    }

    val styleEnabled = remember(store, tick) { store.selfStyleEnabled() }
    val hasStyle = remember(store, tick) { store.selfSkill().isNotBlank() }
    val selfSamples = remember(roles) {
        StyleSkill.count(roles.firstOrNull { it.key == SELF_ROLE_KEY }?.msgs.orEmpty())
    }
    val normalizedQuery = Roles.normalizeKey(query).lowercase(Locale.ROOT)
    val shown = remember(contacts, normalizedQuery) {
        if (normalizedQuery.isBlank()) contacts else contacts.filter { role ->
            listOf(role.name, role.key, role.relation).any { value ->
                Roles.normalizeKey(value).lowercase(Locale.ROOT).contains(normalizedQuery)
            }
        }
    }

    HyperPage(
        title = "角色",
        subtitle = "${contacts.size} 位联系人 · ${contacts.sumOf { it.msgs.size }} 条记录",
        actions = {
            IconButton(onClick = { tick++ }) {
                Icon(Icons.Outlined.Refresh, contentDescription = "刷新角色", tint = palette.text)
            }
        },
    ) {
        HyperGroup {
            OutlinedTextField(
                value = query,
                onValueChange = { query = it },
                modifier = Modifier.fillMaxWidth().padding(14.dp),
                shape = RoundedCornerShape(RadiusR2),
                colors = glassFieldColors(),
                placeholder = { Text("搜索姓名、关系") },
                leadingIcon = { Icon(Icons.Outlined.Search, contentDescription = null) },
                trailingIcon = if (query.isNotEmpty()) {
                    {
                        IconButton(onClick = { query = "" }) {
                            Icon(Icons.Outlined.Close, contentDescription = "清除角色搜索")
                        }
                    }
                } else null,
                singleLine = true,
            )
        }

        HyperGroup(title = "本人") {
            HyperRow(
                title = "我的说话风格",
                subtitle = when {
                    !styleEnabled -> "未开启 · $selfSamples 条样本"
                    hasStyle -> "已开启 · 风格档案已生成"
                    else -> "已开启 · $selfSamples 条样本待提炼"
                },
                icon = Icons.Outlined.RecordVoiceOver,
                value = if (styleEnabled) "已开启" else "未开启",
                onClick = { onOpen(SELF_ROLE_KEY) },
            )
        }

        HyperGroup(title = if (normalizedQuery.isBlank()) "联系人" else "搜索结果 · ${shown.size}") {
            when {
                contacts.isEmpty() -> Column(Modifier.padding(22.dp)) {
                    Text("还没有联系人", color = palette.text, fontSize = 17.sp, fontWeight = FontWeight.Medium)
                    Text("打开微信聊天页后，记录会同步到这里。", color = palette.sub, fontSize = 13.sp, modifier = Modifier.padding(top = 6.dp))
                }
                shown.isEmpty() -> Column(Modifier.padding(22.dp)) {
                    Text("没有找到联系人", color = palette.text, fontSize = 17.sp, fontWeight = FontWeight.Medium)
                    Text("换个姓名或关系试试。", color = palette.sub, fontSize = 13.sp, modifier = Modifier.padding(top = 6.dp))
                }
                else -> shown.forEach { role ->
                    Box(
                        Modifier.fillMaxWidth().combinedClickable(
                            onClick = { onOpen(role.key) },
                            onLongClick = { pendingDelete = role.key },
                            onLongClickLabel = "删除角色",
                        ),
                    ) {
                        HyperRow(
                            title = role.name,
                            subtitle = buildString {
                                append(role.relation.ifBlank { "未设置关系" })
                                if (role.note.isNotBlank()) append(" · ").append(role.note.lineSequence().first().take(36))
                                if (role.renamed) append("\n识别名：").append(role.key)
                                if (role.lastAt > 0) append("\n最近：").append(formatTime(role.lastAt))
                            },
                            icon = Icons.Outlined.PersonOutline,
                            trailing = {
                                Row(verticalAlignment = Alignment.CenterVertically) {
                                    Text("${role.msgs.size} 条", color = palette.sub, fontSize = 13.sp)
                                    Icon(Icons.Outlined.ChevronRight, contentDescription = null, tint = palette.sub)
                                }
                            },
                        )
                    }
                }
            }
        }
    }

    pendingDelete?.let { key ->
        // 对话框打开后归档仍可能更新；执行时只删除对应 key，不回写旧列表快照。
        val name = roles.firstOrNull { it.key == key }?.name ?: key
        AlertDialog(
            onDismissRequest = { pendingDelete = null },
            title = { Text("删除角色「$name」？") },
            text = { Text("档案和聊天记录将一并删除，无法恢复。") },
            confirmButton = {
                TextButton(onClick = {
                    if (key != SELF_ROLE_KEY) store.removeRole(key)
                    pendingDelete = null
                    tick++
                }) { Text("删除", color = palette.bad) }
            },
            dismissButton = { TextButton(onClick = { pendingDelete = null }) { Text("取消") } },
        )
    }
}
