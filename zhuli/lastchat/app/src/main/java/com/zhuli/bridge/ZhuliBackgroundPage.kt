package com.zhuli.bridge

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.Bolt
import androidx.compose.material.icons.rounded.CheckCircle
import androidx.compose.material.icons.rounded.Inbox
import androidx.compose.material.icons.rounded.Info
import androidx.compose.material.icons.rounded.NotificationsOff
import androidx.compose.material.icons.rounded.Refresh
import androidx.compose.material.icons.rounded.Warning
import androidx.compose.material3.Button
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.lifecycle.compose.LocalLifecycleOwner
import com.zhuli.core.CapturePrefs
import com.zhuli.core.ExtractPipeline
import com.zhuli.core.InboxDatabase
import com.zhuli.core.SourceRules
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import me.rerere.rikkahub.utils.PermissionChecker

/**
 * 后台处理（2026-11-24 新增）。
 *
 * 用户之前只能看到"自检"里几行结论，看不到"后台到底在干什么"：
 * 这条通知是被白名单拦了、被媒体规则拦了，还是入库了？待提取堆了几条？
 * 这个页面把整条流水线的每一步都摊开，并提供「立即处理」按钮。
 */
@Composable
fun ZhuliBackgroundScreen() {
    val context = LocalContext.current
    val lifecycleOwner = LocalLifecycleOwner.current
    val scope = rememberCoroutineScope()
    var tick by remember { mutableIntStateOf(0) }
    var notice by remember { mutableStateOf("") }
    var running by remember { mutableStateOf(false) }

    DisposableEffect(lifecycleOwner) {
        val observer = LifecycleEventObserver { _, e ->
            if (e == Lifecycle.Event.ON_RESUME) tick++
        }
        lifecycleOwner.lifecycle.addObserver(observer)
        onDispose { lifecycleOwner.lifecycle.removeObserver(observer) }
    }

    val data by produceState<BackgroundData?>(initialValue = null, tick) {
        value = withContext(Dispatchers.IO) { loadBackground(context) }
    }

    ZhuliScaffold(
        title = "后台处理",
        subtitle = "从通知到入库，每一步都看得见",
        actions = {
            TextButton(onClick = { tick++ }) {
                Icon(Icons.Rounded.Refresh, contentDescription = "刷新")
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
                val d = data
                val tone = when {
                    d == null -> ZhuliTone.NEUTRAL
                    !d.hasAccess || !d.connected -> ZhuliTone.BAD
                    !d.captureOn -> ZhuliTone.WARN
                    else -> ZhuliTone.OK
                }
                ZhuliHero(
                    eyebrow = "采集链路",
                    title = when {
                        d == null -> "正在读取…"
                        !d.hasAccess -> "没有通知使用权"
                        !d.connected -> "通知服务未连接"
                        !d.captureOn -> "采集已暂停"
                        else -> "链路正常"
                    },
                    subtitle = when {
                        d == null -> ""
                        !d.hasAccess -> "去「zhuli 设置 → 通知使用权」打开"
                        !d.connected -> "权限在、服务没绑上：把通知使用权关开一次"
                        !d.captureOn -> "在 zhuli 设置里重新打开"
                        else -> "最近入库 ${ZhuliSelfCheck.ago(System.currentTimeMillis() - d.snap.lastIngestAt)} · 待提取 ${d.snap.pending} 条"
                    },
                    icon = if (tone == ZhuliTone.OK) Icons.Rounded.Bolt else Icons.Rounded.Warning,
                    tone = tone,
                )
            }

            item {
                Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                    ZhuliStat(
                        value = (data?.snap?.pending ?: 0).toString(),
                        label = "待提取",
                        modifier = Modifier.weight(1f),
                        tone = if ((data?.snap?.pending ?: 0) > 0) ZhuliTone.WARN else ZhuliTone.NEUTRAL,
                    )
                    ZhuliStat(
                        value = (data?.snap?.today ?: 0).toString(),
                        label = "今日入库",
                        modifier = Modifier.weight(1f),
                    )
                    ZhuliStat(
                        value = (data?.snap?.total ?: 0).toString(),
                        label = "总入库",
                        modifier = Modifier.weight(1f),
                    )
                }
            }

            item {
                Column(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(horizontal = 2.dp),
                    verticalArrangement = Arrangement.spacedBy(6.dp),
                ) {
                    Button(
                        enabled = !running,
                        onClick = {
                            running = true
                            notice = "正在处理…"
                            scope.launch {
                                val n = withContext(Dispatchers.IO) {
                                    runCatching { ExtractPipeline.run(context) }.getOrDefault(0)
                                }
                                running = false
                                notice = if (n > 0) "处理了 $n 条" else "没有待提取的条目"
                                tick++
                            }
                        },
                    ) {
                        Text(if (running) "处理中…" else "立即处理待提取")
                    }
                    if (notice.isNotBlank()) {
                        Text(
                            notice,
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    }
                }
            }

            item {
                ZhuliSection(
                    title = "最近收下 / 拦下",
                    subtitle = "最多 10 条。被拦下会写原因，方便判断是不是误伤",
                ) {
                    val events = data?.events.orEmpty()
                    // 同一条刷屏通知（iKuuu 每秒一次、计步每分钟一次）合并成一行，
                    // 否则真正的消息会被几十条"常驻通知已拦"挤出视野。
                    val merged = run {
                        val inserted = events.filterNot { it.blocked }.map { it to 1 }
                        val blocked = events.filter { it.blocked }
                            .groupBy { it.pkg to it.reason }
                            .map { (_, list) -> list.first() to list.size }
                        (inserted + blocked).sortedByDescending { it.first.at }.take(10)
                    }
                    if (merged.isEmpty()) {
                        ZhuliEmpty(
                            icon = Icons.Rounded.Inbox,
                            title = "还没有处理记录",
                            hint = "收到一条通知或短信后，这里会出现第一行",
                        )
                    } else {
                        merged.forEachIndexed { index, (ev, count) ->
                            EventRow(ev, count)
                            if (index != merged.lastIndex) {
                                HorizontalDivider(
                                    modifier = Modifier.padding(start = 64.dp),
                                    color = MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.5f),
                                )
                            }
                        }
                    }
                }
            }

            item {
                ZhuliSection(
                    title = "流水线顺序",
                    subtitle = "为什么有的收、有的不收，看这里",
                ) {
                    PipelineRow("1", "白名单", "不在名单里的 App 直接不进")
                    PipelineRow("2", "结构过滤", "媒体播放 / 群聊汇总 / 常驻 / 进度条")
                    PipelineRow("3", "快递识别", "命中取件码就单独记一条并排提醒")
                    PipelineRow("4", "噪音闸门", "回声源、10 分钟内重复、刷屏熔断")
                    PipelineRow("5", "入库", "写入收件箱，成为可检索的记忆")
                    PipelineRow("6", "提取 + 提醒", "拆时间/金额/分类，到点叫醒你")
                }
            }

            item {
                ZhuliSection(
                    title = "来源",
                    subtitle = "按 App 静音：静音后不再收，也不删已有记录",
                ) {
                    val rows = data?.sources.orEmpty()
                    if (rows.isEmpty()) {
                        ZhuliEmpty(
                            icon = Icons.Rounded.NotificationsOff,
                            title = "还没有来源",
                            hint = "收到第一条消息后，这里会列出是哪个 App 在喂消息",
                        )
                    } else {
                        rows.forEachIndexed { index, row ->
                            ZhuliSwitchRow(
                                icon = Icons.Rounded.Inbox,
                                title = row.label,
                                subtitle = "${row.count} 条 · 最近 ${if (row.lastAt > 0) ZhuliSelfCheck.ago(System.currentTimeMillis() - row.lastAt) else "无"}",
                                checked = !row.muted,
                                onCheckedChange = { on ->
                                    scope.launch {
                                        withContext(Dispatchers.IO) {
                                            runCatching {
                                                val db = InboxDatabase(context.applicationContext)
                                                try {
                                                    SourceRules.setMuted(db, row.pkg, !on)
                                                } finally {
                                                    runCatching { db.close() }
                                                }
                                            }
                                        }
                                        tick++
                                    }
                                },
                            )
                            if (index != rows.lastIndex) {
                                HorizontalDivider(
                                    modifier = Modifier.padding(start = 64.dp),
                                    color = MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.5f),
                                )
                            }
                        }
                    }
                }
            }

            item {
                Row(
                    modifier = Modifier.padding(horizontal = 4.dp, vertical = 4.dp),
                    horizontalArrangement = Arrangement.spacedBy(6.dp),
                ) {
                    Icon(
                        Icons.Rounded.Info,
                        contentDescription = null,
                        tint = MaterialTheme.colorScheme.onSurfaceVariant,
                        modifier = Modifier.padding(top = 2.dp),
                    )
                    Text(
                        "被拦不代表出问题：微信的「N 条新消息」汇总条、视频播放条都应该被拦。真正要看的是「入库」这一栏有没有涨。",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
            }
        }
    }
}

@Composable
private fun EventRow(ev: CapturePrefs.Event, repeatCount: Int = 1) {
    val tone = when (ev.action) {
        "inserted" -> ZhuliTone.OK
        "courier" -> ZhuliTone.ACCENT
        else -> ZhuliTone.WARN
    }
    val icon = when (ev.action) {
        "inserted", "courier" -> Icons.Rounded.CheckCircle
        else -> Icons.Rounded.NotificationsOff
    }
    ZhuliRow(
        icon = icon,
        title = ev.title.ifBlank { ev.label },
        subtitle = listOfNotNull(
            ev.label.takeIf { it.isNotBlank() },
            ZhuliSelfCheck.ago(System.currentTimeMillis() - ev.at),
            if (ev.blocked) com.zhuli.core.NotificationCapture.describe(ev.reason) else "已入库",
            if (repeatCount > 1) "近 ${repeatCount} 次" else null,
        ).joinToString(" · "),
        trailing = {
            ZhuliPill(
                when (ev.action) {
                    "inserted" -> "收下"
                    "courier" -> "快递"
                    else -> "拦下"
                },
                tone,
            )
        },
    )
}

@Composable
private fun PipelineRow(step: String, title: String, desc: String) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = 16.dp, vertical = 8.dp),
        horizontalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        ZhuliPill(step, ZhuliTone.NEUTRAL)
        Column {
            Text(title, style = MaterialTheme.typography.bodyMedium, fontWeight = FontWeight.Medium)
            Text(
                desc,
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
    }
}

private data class BackgroundData(
    val snap: ZhuliSelfCheckData.Snapshot,
    val events: List<CapturePrefs.Event>,
    val sources: List<SourceRules.Row>,
    val hasAccess: Boolean,
    val connected: Boolean,
    val captureOn: Boolean,
)

private fun loadBackground(context: android.content.Context): BackgroundData {
    val snap = ZhuliSelfCheckData.snapshot(context)
    val sources = try {
        val db = InboxDatabase(context.applicationContext)
        try {
            SourceRules.rows(context, db, 12)
        } finally {
            runCatching { db.close() }
        }
    } catch (_: Throwable) {
        emptyList()
    }
    return BackgroundData(
        snap = snap,
        events = CapturePrefs.recentEvents(context, 10),
        sources = sources,
        hasAccess = PermissionChecker.hasNotificationListenerAccess(context),
        connected = me.rerere.rikkahub.service.AssistantNotificationListener.connected.value,
        captureOn = CapturePrefs.enabled(context),
    )
}
