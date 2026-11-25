package com.zhuli.bridge

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.RowScope
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import me.rerere.rikkahub.ui.components.nav.BackButton

/**
 * zhuli 自己的视觉层（2026-11-24）。
 *
 * 之前所有 zhuli 页面都是"Card + 两行 Text"，跟原版 LastChat 的设置页没有区别，
 * 用户一眼看过去还是"原版软件加了几行字"。这里抽一套独立组件：
 * 大圆角、柔和渐变、状态徽章、数据块，页面只用这些积木拼，换主题时一起变。
 *
 * 原则：颜色全部取自 MaterialTheme，不写死浅色 —— 用户在 App 里换主题/深色模式时要跟着走。
 */
object ZhuliStyle {
    val ScreenPadding = 20.dp
    val CardRadius = 24.dp
    val HeroRadius = 30.dp
    val Gap = 12.dp
}

enum class ZhuliTone { OK, WARN, BAD, NEUTRAL, ACCENT }

@Composable
fun zhuliToneColor(tone: ZhuliTone): Color = when (tone) {
    ZhuliTone.OK -> MaterialTheme.colorScheme.tertiary
    ZhuliTone.WARN -> MaterialTheme.colorScheme.error.copy(alpha = 0.72f)
    ZhuliTone.BAD -> MaterialTheme.colorScheme.error
    ZhuliTone.NEUTRAL -> MaterialTheme.colorScheme.onSurfaceVariant
    ZhuliTone.ACCENT -> MaterialTheme.colorScheme.primary
}

/** 页面骨架：顶部返回 + 标题/副标题，内容自己滚动。 */
@Composable
fun ZhuliScaffold(
    title: String,
    subtitle: String? = null,
    actions: @Composable RowScope.() -> Unit = {},
    content: @Composable (PaddingValues) -> Unit,
) {
    Scaffold(
        modifier = Modifier.fillMaxSize(),
        containerColor = MaterialTheme.colorScheme.background,
        topBar = {
            TopAppBar(
                title = {
                    Column {
                        Text(
                            text = title,
                            style = MaterialTheme.typography.titleLarge,
                            fontWeight = FontWeight.SemiBold,
                        )
                        if (!subtitle.isNullOrBlank()) {
                            Text(
                                text = subtitle,
                                style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                            )
                        }
                    }
                },
                navigationIcon = { BackButton() },
                actions = actions,
                colors = TopAppBarDefaults.topAppBarColors(
                    containerColor = MaterialTheme.colorScheme.background,
                ),
            )
        },
        content = content,
    )
}

/** 渐变 Hero：一屏里最先看到的东西，回答"现在什么状态"。 */
@Composable
fun ZhuliHero(
    eyebrow: String,
    title: String,
    subtitle: String,
    icon: ImageVector,
    tone: ZhuliTone = ZhuliTone.ACCENT,
    trailing: @Composable (() -> Unit)? = null,
) {
    val accent = zhuliToneColor(tone)
    Box(
        modifier = Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(ZhuliStyle.HeroRadius))
            .background(
                Brush.linearGradient(
                    listOf(
                        accent.copy(alpha = 0.20f),
                        MaterialTheme.colorScheme.surfaceContainerLow,
                        MaterialTheme.colorScheme.secondaryContainer.copy(alpha = 0.45f),
                    )
                )
            )
            .padding(20.dp),
    ) {
        Row(verticalAlignment = Alignment.Top) {
            Box(
                modifier = Modifier
                    .size(46.dp)
                    .clip(CircleShape)
                    .background(accent.copy(alpha = 0.16f)),
                contentAlignment = Alignment.Center,
            ) {
                Icon(icon, contentDescription = null, tint = accent, modifier = Modifier.size(24.dp))
            }
            Spacer(Modifier.width(14.dp))
            Column(modifier = Modifier.weight(1f)) {
                Text(
                    text = eyebrow,
                    style = MaterialTheme.typography.labelMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                Spacer(Modifier.height(2.dp))
                Text(
                    text = title,
                    style = MaterialTheme.typography.headlineSmall,
                    fontWeight = FontWeight.SemiBold,
                )
                Spacer(Modifier.height(4.dp))
                Text(
                    text = subtitle,
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
            if (trailing != null) {
                Spacer(Modifier.width(8.dp))
                trailing()
            }
        }
    }
}

/** 数据块：一个大数字 + 一句说明。 */
@Composable
fun ZhuliStat(
    value: String,
    label: String,
    modifier: Modifier = Modifier,
    tone: ZhuliTone = ZhuliTone.NEUTRAL,
    onClick: (() -> Unit)? = null,
) {
    val base = modifier
        .clip(RoundedCornerShape(ZhuliStyle.CardRadius))
        .background(MaterialTheme.colorScheme.surfaceContainerLow)
        .then(if (onClick != null) Modifier.clickable(onClick = onClick) else Modifier)
        .padding(horizontal = 16.dp, vertical = 14.dp)
    Column(modifier = base) {
        Text(
            text = value,
            style = MaterialTheme.typography.headlineSmall,
            fontWeight = FontWeight.SemiBold,
            color = if (tone == ZhuliTone.NEUTRAL) MaterialTheme.colorScheme.onSurface else zhuliToneColor(tone),
            maxLines = 1,
        )
        Spacer(Modifier.height(2.dp))
        Text(
            text = label,
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            maxLines = 2,
            overflow = TextOverflow.Ellipsis,
        )
    }
}

/** 分组标题 + 卡片容器。 */
@Composable
fun ZhuliSection(
    title: String,
    subtitle: String? = null,
    content: @Composable ColumnScope.() -> Unit,
) {
    Column(modifier = Modifier.fillMaxWidth()) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 4.dp, vertical = 8.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Text(
                text = title,
                style = MaterialTheme.typography.titleSmall,
                fontWeight = FontWeight.SemiBold,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
        if (!subtitle.isNullOrBlank()) {
            Text(
                text = subtitle,
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.8f),
                modifier = Modifier.padding(horizontal = 4.dp, vertical = 2.dp),
            )
        }
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .clip(RoundedCornerShape(ZhuliStyle.CardRadius))
                .background(MaterialTheme.colorScheme.surfaceContainerLowest)
                .padding(vertical = 4.dp),
            content = content,
        )
    }
}

/** 一行：图标 + 标题/说明 + 右侧内容。 */
@Composable
fun ZhuliRow(
    icon: ImageVector,
    title: String,
    subtitle: String? = null,
    trailing: @Composable (() -> Unit)? = null,
    onClick: (() -> Unit)? = null,
    enabled: Boolean = true,
    tint: Color = MaterialTheme.colorScheme.primary,
) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .then(if (onClick != null && enabled) Modifier.clickable(onClick = onClick) else Modifier)
            .padding(horizontal = 16.dp, vertical = 12.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Box(
            modifier = Modifier
                .size(36.dp)
                .clip(CircleShape)
                .background(tint.copy(alpha = if (enabled) 0.12f else 0.05f)),
            contentAlignment = Alignment.Center,
        ) {
            Icon(
                icon,
                contentDescription = null,
                tint = if (enabled) tint else tint.copy(alpha = 0.4f),
                modifier = Modifier.size(20.dp),
            )
        }
        Spacer(Modifier.width(12.dp))
        Column(modifier = Modifier.weight(1f)) {
            Text(
                text = title,
                style = MaterialTheme.typography.bodyLarge,
                color = if (enabled) MaterialTheme.colorScheme.onSurface
                else MaterialTheme.colorScheme.onSurface.copy(alpha = 0.5f),
            )
            if (!subtitle.isNullOrBlank()) {
                Text(
                    text = subtitle,
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    maxLines = 2,
                    overflow = TextOverflow.Ellipsis,
                )
            }
        }
        if (trailing != null) {
            Spacer(Modifier.width(8.dp))
            trailing()
        }
    }
}

@Composable
fun ZhuliSwitchRow(
    icon: ImageVector,
    title: String,
    subtitle: String? = null,
    checked: Boolean,
    onCheckedChange: (Boolean) -> Unit,
    enabled: Boolean = true,
) {
    ZhuliRow(
        icon = icon,
        title = title,
        subtitle = subtitle,
        enabled = enabled,
        onClick = { if (enabled) onCheckedChange(!checked) },
        trailing = {
            Switch(checked = checked, onCheckedChange = onCheckedChange, enabled = enabled)
        },
    )
}

/** 小徽章：状态词。固定最小宽度，避免「拦下」被挤成两行。 */
@Composable
fun ZhuliPill(text: String, tone: ZhuliTone = ZhuliTone.NEUTRAL) {
    val c = zhuliToneColor(tone)
    Box(
        modifier = Modifier
            .widthIn(min = 46.dp)
            .clip(CircleShape)
            .background(c.copy(alpha = 0.14f))
            .padding(horizontal = 10.dp, vertical = 4.dp),
        contentAlignment = Alignment.Center,
    ) {
        Text(
            text = text,
            style = MaterialTheme.typography.labelSmall,
            color = c,
            maxLines = 1,
            softWrap = false,
        )
    }
}

/** 空状态：别只写"无"，要告诉用户下一步能干什么。 */
@Composable
fun ZhuliEmpty(icon: ImageVector, title: String, hint: String) {
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = 20.dp, vertical = 28.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.spacedBy(6.dp),
    ) {
        Icon(
            icon,
            contentDescription = null,
            tint = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.55f),
            modifier = Modifier.size(28.dp),
        )
        Text(title, style = MaterialTheme.typography.titleSmall)
        Text(
            hint,
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
    }
}

/** 快捷入口方块：图标、标题、一句说明。 */
@Composable
fun ZhuliActionTile(
    icon: ImageVector,
    title: String,
    subtitle: String,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    tone: ZhuliTone = ZhuliTone.ACCENT,
) {
    val accent = zhuliToneColor(tone)
    Column(
        modifier = modifier
            .clip(RoundedCornerShape(ZhuliStyle.CardRadius))
            .background(MaterialTheme.colorScheme.surfaceContainerLow)
            .clickable(onClick = onClick)
            .padding(16.dp),
        verticalArrangement = Arrangement.spacedBy(6.dp),
    ) {
        Box(
            modifier = Modifier
                .size(38.dp)
                .clip(CircleShape)
                .background(accent.copy(alpha = 0.14f)),
            contentAlignment = Alignment.Center,
        ) {
            Icon(icon, contentDescription = null, tint = accent, modifier = Modifier.size(20.dp))
        }
        Text(
            text = title,
            style = MaterialTheme.typography.titleSmall,
            fontWeight = FontWeight.SemiBold,
            maxLines = 1,
        )
        Text(
            text = subtitle,
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            maxLines = 2,
            overflow = TextOverflow.Ellipsis,
        )
    }
}
