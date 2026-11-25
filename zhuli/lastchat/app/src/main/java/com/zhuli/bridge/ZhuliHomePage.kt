package com.zhuli.bridge

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.Bolt
import androidx.compose.material.icons.rounded.DeleteSweep
import androidx.compose.material.icons.rounded.Inbox
import androidx.compose.material.icons.rounded.Layers
import androidx.compose.material.icons.rounded.NotificationsActive
import androidx.compose.material.icons.rounded.Settings
import androidx.compose.material.icons.rounded.Shield
import androidx.compose.material.icons.rounded.Today
import androidx.compose.material.icons.rounded.Tune
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.lifecycle.compose.LocalLifecycleOwner
import com.zhuli.core.CapturePrefs
import com.zhuli.core.InboxDatabase
import com.zhuli.core.InboxRecord
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import me.rerere.rikkahub.utils.PermissionChecker

/**
 * zhuli 控制台：一个页面回答"现在什么状态、接下来点哪里"。
 *
 * 这是用户从侧边栏/设置进来的第一个 zhuli 页面，跟原版 LastChat 的设置彻底分开。
 */
@Composable
fun ZhuliHomeScreen(
    onSelfCheck: () -> Unit,
    onBackground: () -> Unit,
    onSources: () -> Unit,
    onSettings: () -> Unit,
    onBrief: () -> Unit,
    onMemory: () -> Unit,
    onTasks: () -> Unit,
) {
    val context = LocalContext.current
    val lifecycleOwner = LocalLifecycleOwner.current
    var tick by remember { mutableIntStateOf(0) }
    DisposableEffect(lifecycleOwner) {
        val observer = LifecycleEventObserver { _, event ->
            if (event == Lifecycle.Event.ON_RESUME) tick++
        }
        lifecycleOwner.lifecycle.addObserver(observer)
        onDispose { lifecycleOwner.lifecycle.removeObserver(observer) }
    }

    val data by produceState<HomeData?>(initialValue = null, tick) {
        value = withContext(Dispatchers.IO) { loadHome(context) }
    }
    val d = data

    ZhuliScaffold(
        title = "zhuli 控制台",
        subtitle = "采集 · 隐私 · 后台，都在这里",
        actions = {
            TextButton(onClick = onSettings) {
                Icon(Icons.Rounded.Tune, contentDescription = "设置")
            }
        },
    ) { padding ->
        LazyColumn(
            modifier = Modifier
                .fillMaxSize()
                .padding(padding),
            contentPadding = PaddingValues(
                start = ZhuliStyle.ScreenPadding,
                end = ZhuliStyle.ScreenPadding,
                top = 4.dp,
                bottom = 28.dp,
            ),
            verticalArrangement = Arrangement.spacedBy(ZhuliStyle.Gap),
        ) {
            item {
                val status = heroStatus(d)
                ZhuliHero(
                    eyebrow = "zhuli · 私人助理",
                    title = status.first,
                    subtitle = status.second,
                    icon = Icons.Rounded.Shield,
                    tone = status.third,
                )
            }

            item {
                Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                    ZhuliStat(
                        value = (d?.snap?.today ?: 0).toString(),
                        label = "今日入库",
                        modifier = Modifier.weight(1f),
                        onClick = onBackground,
                    )
                    ZhuliStat(
                        value = (d?.snap?.pending ?: 0).toString(),
                        label = "待提取",
                        modifier = Modifier.weight(1f),
                        tone = if ((d?.snap?.pending ?: 0) > 0) ZhuliTone.WARN else ZhuliTone.NEUTRAL,
                        onClick = onBackground,
                    )
                    ZhuliStat(
                        value = (d?.snap?.scheduled ?: 0).toString(),
                        label = "已排提醒",
                        modifier = Modifier.weight(1f),
                        onClick = onSelfCheck,
                    )
                }
            }

            item {
                Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                    ZhuliActionTile(
                        icon = Icons.Rounded.Shield,
                        title = "自检",
                        subtitle = "凭什么知道 / 凭什么出网",
                        onClick = onSelfCheck,
                        modifier = Modifier.weight(1f),
                    )
                    ZhuliActionTile(
                        icon = Icons.Rounded.Bolt,
                        title = "后台处理",
                        subtitle = "收/拦了什么，一眼看到",
                        onClick = onBackground,
                        modifier = Modifier.weight(1f),
                        tone = ZhuliTone.WARN,
                    )
                }
            }
            item {
                Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                    ZhuliActionTile(
                        icon = Icons.Rounded.NotificationsActive,
                        title = "来源白名单",
                        subtitle = "只收你允许的 App",
                        onClick = onSources,
                        modifier = Modifier.weight(1f),
                        tone = ZhuliTone.OK,
                    )
                    ZhuliActionTile(
                        icon = Icons.Rounded.Settings,
                        title = "zhuli 设置",
                        subtitle = "采集 / 隐私 / 提醒",
                        onClick = onSettings,
                        modifier = Modifier.weight(1f),
                        tone = ZhuliTone.NEUTRAL,
                    )
                }
            }

            item {
                Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                    ZhuliActionTile(
                        icon = Icons.Rounded.Layers,
                        title = "记忆",
                        subtitle = "查看、纠正、删除 AI 记住的事实",
                        onClick = onMemory,
                        modifier = Modifier.weight(1f),
                        tone = ZhuliTone.ACCENT,
                    )
                    ZhuliActionTile(
                        icon = Icons.Rounded.Today,
                        title = "任务",
                        subtitle = "多步骤执行记录与待确认操作",
                        onClick = onTasks,
                        modifier = Modifier.weight(1f),
                        tone = ZhuliTone.NEUTRAL,
                    )
                }
            }

            val junk = d?.junkCount ?: 0
            if (junk > 0) {
                item {
                    ZhuliHero(
                        eyebrow = "建议清理",
                        title = "有 $junk 条历史噪声",
                        subtitle = "来自已不在白名单的 App，会污染检索和今日简报",
                        icon = Icons.Rounded.DeleteSweep,
                        tone = ZhuliTone.WARN,
                        trailing = {
                            TextButton(onClick = onSettings) { Text("去清理") }
                        },
                    )
                }
            }

            item {
                ZhuliSection(title = "最近入库") {
                    val recent = d?.recent.orEmpty()
                    if (recent.isEmpty()) {
                        ZhuliEmpty(
                            icon = Icons.Rounded.Inbox,
                            title = "还没有收到消息",
                            hint = if (d?.hasAccess == false) "先去打开通知使用权" else "收到一条微信/短信后会出现在这里",
                        )
                    } else {
                        recent.forEachIndexed { index, rec ->
                            InboxRow(rec)
                            if (index != recent.lastIndex) {
                                HorizontalDivider(
                                    modifier = Modifier.padding(start = 64.dp),
                                    color = MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.5f),
                                )
                            }
                        }
                        Row(
                            modifier = Modifier
                                .fillMaxWidth()
                                .padding(horizontal = 8.dp),
                            horizontalArrangement = Arrangement.End,
                        ) {
                            TextButton(onClick = onBackground) { Text("看全部处理记录") }
                        }
                    }
                }
            }

            item {
                ZhuliSection(
                    title = "今日简报",
                    subtitle = "每天 07:30 自动生成，也可以随时看",
                ) {
                    ZhuliRow(
                        icon = Icons.Rounded.Today,
                        title = "打开今日简报",
                        subtitle = "待办 / 快递 / 近 7 天支出",
                        onClick = onBrief,
                    )
                }
            }

            item {
                ZhuliSection(title = "采集范围") {
                    ZhuliRow(
                        icon = Icons.Rounded.Layers,
                        title = if (d?.whitelistOn == false) "收全部应用" else "白名单 ${d?.allowedCount ?: 0} 个应用",
                        subtitle = if (d?.captureOn == false) "采集已暂停" else "微信 / QQ / 短信等（可在来源白名单里改）",
                        onClick = onSources,
                    )
                }
            }
        }
    }
}

@Composable
private fun InboxRow(rec: InboxRecord) {
    ZhuliRow(
        icon = Icons.Rounded.Inbox,
        title = rec.title.ifBlank { rec.appLabel.ifBlank { "通知" } },
        subtitle = listOfNotNull(
            rec.appLabel.takeIf { it.isNotBlank() },
            ZhuliSelfCheck.ago(System.currentTimeMillis() - rec.receivedAt),
            rec.text.take(40).takeIf { it.isNotBlank() },
        ).joinToString(" · "),
        trailing = {
            if (rec.category.isNotBlank()) {
                ZhuliPill(rec.category, ZhuliTone.NEUTRAL)
            }
        },
    )
}

private data class HomeData(
    val snap: ZhuliSelfCheckData.Snapshot,
    val recent: List<InboxRecord>,
    val recentEvents: List<CapturePrefs.Event>,
    val hasAccess: Boolean,
    val connected: Boolean,
    val captureOn: Boolean,
    val whitelistOn: Boolean,
    val allowedCount: Int,
    val junkCount: Int,
)

private fun loadHome(context: android.content.Context): HomeData {
    val snap = ZhuliSelfCheckData.snapshot(context)
    val recent = try {
        val db = InboxDatabase(context.applicationContext)
        try {
            db.recent(6)
        } finally {
            runCatching { db.close() }
        }
    } catch (_: Throwable) {
        emptyList()
    }
    return HomeData(
        snap = snap,
        recent = recent,
        recentEvents = CapturePrefs.recentEvents(context, 3),
        hasAccess = PermissionChecker.hasNotificationListenerAccess(context),
        connected = me.rerere.rikkahub.service.AssistantNotificationListener.connected.value,
        captureOn = CapturePrefs.enabled(context),
        whitelistOn = CapturePrefs.whitelistEnabled(context),
        allowedCount = CapturePrefs.installedAllowedCount(context),
        junkCount = if (CapturePrefs.whitelistEnabled(context)) {
            ZhuliMaintenance.countOutsideWhitelist(context)
        } else 0,
    )
}

private fun heroStatus(d: HomeData?): Triple<String, String, ZhuliTone> {
    if (d == null) return Triple("正在读取状态…", "稍等一下", ZhuliTone.NEUTRAL)
    return when {
        !d.hasAccess -> Triple(
            "还差一个通知使用权",
            "没有它，任何 App 的消息都收不到。点下方「自检」有直达入口。",
            ZhuliTone.BAD,
        )

        !d.captureOn -> Triple("采集已暂停", "开关在 zhuli 设置 → 采集", ZhuliTone.WARN)
        !d.connected -> Triple(
            "通知服务没连上",
            "权限在，但系统把服务解绑了 —— 去自检页看修复办法",
            ZhuliTone.WARN,
        )

        else -> {
            val scope = if (d.whitelistOn) "${d.allowedCount} 个白名单应用" else "全部应用"
            Triple(
                "正在替你看着 $scope",
                "今天入库 ${d.snap.today} 条 · 待提取 ${d.snap.pending} 条 · 已排提醒 ${d.snap.scheduled} 个",
                ZhuliTone.OK,
            )
        }
    }
}
