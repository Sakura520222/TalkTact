package dev.goutou.wingman.ui

import androidx.activity.compose.BackHandler
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.RowScope
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.asPaddingValues
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.navigationBars
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.outlined.ArrowBack
import androidx.compose.material.icons.automirrored.outlined.KeyboardArrowRight
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.key
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp

/** 大标题、分组卡片与直接可见的操作；底部留足悬浮导航和系统手势区域。 */
@Composable
fun HyperPage(
    title: String,
    subtitle: String? = null,
    onBack: (() -> Unit)? = null,
    actions: @Composable RowScope.() -> Unit = {},
    content: @Composable ColumnScope.() -> Unit,
) {
    val palette = LocalPalette.current
    val scroll = key(title) { rememberScrollState() }
    if (onBack != null) BackHandler(onBack = onBack)
    Column(
        Modifier.fillMaxSize().imePadding(),
    ) {
        Row(
            Modifier.fillMaxWidth().padding(start = 14.dp, end = 14.dp, top = 10.dp).heightIn(min = 44.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.End,
        ) {
            if (onBack != null) {
                IconButton(onClick = onBack) { Icon(Icons.AutoMirrored.Outlined.ArrowBack, "返回", tint = palette.text) }
            }
            Spacer(Modifier.weight(1f))
            actions()
        }
        Column(
            Modifier.fillMaxWidth().weight(1f).verticalScroll(scroll)
                .padding(bottom = 106.dp + WindowInsets.navigationBars.asPaddingValues().calculateBottomPadding()),
        ) {
            Text(
                title,
                modifier = Modifier.padding(start = 26.dp, end = 20.dp, top = 8.dp, bottom = if (subtitle == null) 18.dp else 4.dp),
                fontSize = 34.sp,
                fontWeight = FontWeight.Medium,
                color = palette.text,
            )
            if (subtitle != null) Text(
                subtitle, modifier = Modifier.padding(start = 28.dp, end = 24.dp, bottom = 18.dp),
                fontSize = 14.sp, color = palette.sub,
            )
            content()
        }
    }
}

@Composable
fun HyperGroup(title: String? = null, content: @Composable ColumnScope.() -> Unit) {
    val palette = LocalPalette.current
    if (title != null) Text(
        title, modifier = Modifier.padding(start = 32.dp, end = 24.dp, top = 20.dp, bottom = 8.dp),
        fontSize = 14.sp, fontWeight = FontWeight.Medium, color = palette.primary,
    )
    Column(
        Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 5.dp)
            .clip(RoundedCornerShape(24.dp)).background(palette.cardBg).padding(vertical = 4.dp),
        content = content,
    )
}

@Composable
fun HyperRow(
    title: String,
    subtitle: String? = null,
    icon: ImageVector? = null,
    value: String? = null,
    onClick: (() -> Unit)? = null,
    trailing: (@Composable () -> Unit)? = null,
) {
    val palette = LocalPalette.current
    Row(
        Modifier.fillMaxWidth().heightIn(min = 66.dp)
            .then(if (onClick != null) Modifier.clickable(onClick = onClick) else Modifier)
            .padding(horizontal = 20.dp, vertical = 15.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(16.dp),
    ) {
        if (icon != null) Icon(icon, null, tint = palette.text, modifier = Modifier.size(24.dp))
        Column(Modifier.weight(1f)) {
            Text(title, fontSize = 16.sp, fontWeight = FontWeight.Medium, color = palette.text)
            if (!subtitle.isNullOrBlank()) Text(subtitle, fontSize = 13.sp, color = palette.sub)
        }
        if (value != null) Text(value, fontSize = 13.sp, color = palette.sub)
        if (trailing != null) trailing()
        else if (onClick != null) Icon(Icons.AutoMirrored.Outlined.KeyboardArrowRight, null, tint = palette.sub, modifier = Modifier.size(24.dp))
    }
}

enum class SettingsSection(val title: String) {
    CONNECTION("接口与模型"), GENERATION("生成方式"), WECHAT("微信助手"),
    WHITELIST("会话白名单"), RECOGNITION("识图与代理"), BACKUP("备份与恢复"),
    ALL("全部设置"),
}
