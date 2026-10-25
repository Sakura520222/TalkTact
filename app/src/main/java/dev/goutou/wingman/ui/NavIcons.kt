package dev.goutou.wingman.ui

import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.StrokeJoin
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.graphics.vector.path
import androidx.compose.ui.unit.dp

/**
 * 底栏图标（2026-10 重绘）：不再用 Material Icons 的默认形状 —— 那套「默认感」
 * 是旧界面像说明书的一部分。全部按 HyperOS 的线性图标规范自绘：
 * 24dp 网格、2dp 线宽、圆头（[StrokeCap.Round]）圆角连接，可被 Icon 的 tint 染色。
 */

/** 状态：活动脉冲线（运行心跳）。 */
val NavIconStatus: ImageVector by lazy {
    navIcon("status") {
        path(
            stroke = androidx.compose.ui.graphics.SolidColor(androidx.compose.ui.graphics.Color.Black),
            strokeLineWidth = 2f,
            strokeLineCap = StrokeCap.Round,
            strokeLineJoin = StrokeJoin.Round,
        ) {
            moveTo(3f, 12f)
            lineTo(8f, 12f)
            lineTo(10.5f, 6f)
            lineTo(13.5f, 18f)
            lineTo(16f, 12f)
            lineTo(21f, 12f)
        }
    }
}

/** 军师：AI 火花（HyperOS 的 AI 图标语言）。 */
val NavIconMentor: ImageVector by lazy {
    navIcon("mentor") {
        path(
            stroke = androidx.compose.ui.graphics.SolidColor(androidx.compose.ui.graphics.Color.Black),
            strokeLineWidth = 2f,
            strokeLineCap = StrokeCap.Round,
            strokeLineJoin = StrokeJoin.Round,
        ) {
            moveTo(12f, 3f)
            curveTo(12.6f, 7.8f, 16.2f, 11.4f, 21f, 12f)
            curveTo(16.2f, 12.6f, 12.6f, 16.2f, 12f, 21f)
            curveTo(11.4f, 16.2f, 7.8f, 12.6f, 3f, 12f)
            curveTo(7.8f, 11.4f, 11.4f, 7.8f, 12f, 3f)
            close()
        }
    }
}

/** 角色：联系人（头 + 肩的开口弧）。 */
val NavIconRoles: ImageVector by lazy {
    navIcon("roles") {
        path(
            stroke = androidx.compose.ui.graphics.SolidColor(androidx.compose.ui.graphics.Color.Black),
            strokeLineWidth = 2f,
            strokeLineCap = StrokeCap.Round,
            strokeLineJoin = StrokeJoin.Round,
        ) {
            // 头：整圆（两段弧拼成）
            moveTo(8.5f, 8f)
            arcToRelative(3.5f, 3.5f, 0f, false, true, 7f, 0f)
            arcToRelative(3.5f, 3.5f, 0f, false, true, -7f, 0f)
            close()
            // 肩：开口弧（不 close，两端是圆头）
            moveTo(5f, 20f)
            curveToRelative(0f, -3.9f, 3.1f, -6.5f, 7f, -6.5f)
            curveToRelative(3.9f, 0f, 7f, 2.6f, 7f, 6.5f)
        }
    }
}

/** 试一试：闪电（快速运行一次）。 */
val NavIconTrial: ImageVector by lazy {
    navIcon("trial") {
        path(
            stroke = androidx.compose.ui.graphics.SolidColor(androidx.compose.ui.graphics.Color.Black),
            strokeLineWidth = 2f,
            strokeLineCap = StrokeCap.Round,
            strokeLineJoin = StrokeJoin.Round,
        ) {
            moveTo(13f, 3f)
            lineTo(6f, 13f)
            lineTo(11f, 13f)
            lineTo(10f, 21f)
            lineTo(18f, 10f)
            lineTo(13f, 10f)
            close()
        }
    }
}

/** 设置：调杆（三条线两个滑点，控制中心语言）。 */
val NavIconSettings: ImageVector by lazy {
    navIcon("settings") {
        path(
            stroke = androidx.compose.ui.graphics.SolidColor(androidx.compose.ui.graphics.Color.Black),
            strokeLineWidth = 2f,
            strokeLineCap = StrokeCap.Round,
            strokeLineJoin = StrokeJoin.Round,
        ) {
            // 上排：线 + 滑点（12,7）
            moveTo(4f, 7f)
            lineTo(9.5f, 7f)
            moveTo(14.5f, 7f)
            lineTo(20f, 7f)
            moveTo(10f, 7f)
            arcToRelative(2f, 2f, 0f, false, true, 4f, 0f)
            arcToRelative(2f, 2f, 0f, false, true, -4f, 0f)
            close()
            // 下排：线 + 滑点（9,17）
            moveTo(4f, 17f)
            lineTo(6.5f, 17f)
            moveTo(11.5f, 17f)
            lineTo(20f, 17f)
            moveTo(7f, 17f)
            arcToRelative(2f, 2f, 0f, false, true, 4f, 0f)
            arcToRelative(2f, 2f, 0f, false, true, -4f, 0f)
            close()
        }
    }
}

private inline fun navIcon(
    name: String,
    block: androidx.compose.ui.graphics.vector.ImageVector.Builder.() -> Unit,
): ImageVector = ImageVector.Builder(
    name = "NavIcon$name",
    defaultWidth = 24.dp,
    defaultHeight = 24.dp,
    viewportWidth = 24f,
    viewportHeight = 24f,
).apply(block).build()
