package com.zhuli.bridge

import android.content.Intent
import android.provider.Settings
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.CheckCircle
import androidx.compose.material.icons.rounded.Cloud
import androidx.compose.material.icons.rounded.Error
import androidx.compose.material.icons.rounded.Info
import androidx.compose.material.icons.rounded.MarkEmailRead
import androidx.compose.material.icons.rounded.NotificationsActive
import androidx.compose.material.icons.rounded.Refresh
import androidx.compose.material.icons.rounded.Shield
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
import com.zhuli.core.InboxDatabase
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/**
 * zhuli 自检（2026-11-24 重做）。
 *
 * 上一版是"Card + 两行纯文本"，而且"最近入库"取的是写死的 0。
 * 现在数据全部来自真实库与真实服务状态，视觉换成 zhuli 自己的组件。
 */
@Composable
fun ZhuliSelfCheckPage() {
    val context = LocalContext.current
    val lifecycleOwner = LocalLifecycleOwner.current
    val scope = rememberCoroutineScope()
    var tick by remember { mutableIntStateOf(0) }
    var evictMsg by remember { mutableStateOf("") }

    DisposableEffect(lifecycleOwner) {
        val observer = LifecycleEventObserver { _, e ->
            if (e == Lifecycle.Event.ON_RESUME) tick++
        }
        lifecycleOwner.lifecycle.addObserver(observer)
        onDispose { lifecycleOwner.lifecycle.removeObserver(observer) }
    }

    val state by produceState<SelfCheckState?>(initialValue = null, tick) {
        value = withContext(Dispatchers.IO) { loadSelfCheck(context) }
    }

    val smsLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.RequestMultiplePermissions()
    ) { tick++ }

    ZhuliScaffold(
        title = "zhuli 自检",
        subtitle = "凭什么知道 · 凭什么出网",
        actions = {
            TextButton(onClick = { tick++ }) {
                Icon(Icons.Rounded.Refresh, contentDescription = "重新检查")
            }
        },
    ) { padding ->
        val s = state
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
                val overall = s?.items?.let { ZhuliSelfCheck.overall(it) } ?: ZhuliSelfCheck.Level.OK
                val tone = when (overall) {
                    ZhuliSelfCheck.Level.OK -> ZhuliTone.OK
                    ZhuliSelfCheck.Level.WARN -> ZhuliTone.WARN
                    ZhuliSelfCheck.Level.BAD -> ZhuliTone.BAD
                }
                ZhuliHero(
                    eyebrow = "自检",
                    title = s?.items?.let { ZhuliSelfCheck.headline(it) } ?: "正在检查…",
                    subtitle = s?.let {
                        "今日入库 ${it.snap.today} 条 · 共 ${it.snap.total} 条 · 待提取 ${it.snap.pending} 条"
                    } ?: "",
                    icon = if (overall == ZhuliSelfCheck.Level.OK) Icons.Rounded.CheckCircle else Icons.Rounded.Warning,
                    tone = tone,
                )
            }

            if (s != null) {
                val groups = s.items.groupBy { it.group }
                groups.forEach { (group, items) ->
                    item(key = "group_$group") {
                        ZhuliSection(title = group) {
                            items.forEachIndexed { index, item ->
                                SelfCheckRow(item)
                                if (index != items.lastIndex) {
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
                        title = "隐私三层自检",
                        subtitle = "判定 → 脱敏 → 请求体。每条都用真实规则跑，不联网",
                    ) {
                        s.privacyRows.forEachIndexed { index, row ->
                            PrivacyRow(row)
                            if (index != s.privacyRows.lastIndex) {
                                HorizontalDivider(
                                    modifier = Modifier.padding(start = 16.dp),
                                    color = MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.35f),
                                )
                            }
                        }
                    }
                }

                item {
                    ZhuliSection(
                        title = "最近出网",
                        subtitle = "只记宿主、模型和脱敏处数，不存内容",
                    ) {
                        if (s.cloud.isEmpty()) {
                            ZhuliEmpty(
                                icon = Icons.Rounded.Cloud,
                                title = "还没往外发过东西",
                                hint = "用云模型聊一句后，这里会出现一条记录",
                            )
                        } else {
                            s.cloud.forEachIndexed { index, row ->
                                CloudRow(row)
                                if (index != s.cloud.lastIndex) {
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
                    ZhuliSection(title = "快捷修复") {
                        ZhuliRow(
                            icon = Icons.Rounded.NotificationsActive,
                            title = "通知使用权",
                            subtitle = if (s.hasAccess) "已开启，点一下可去系统里复核" else "没开，现在收不到任何通知",
                            trailing = {
                                ZhuliPill(
                                    if (s.hasAccess) "正常" else "去开启",
                                    if (s.hasAccess) ZhuliTone.OK else ZhuliTone.BAD,
                                )
                            },
                            onClick = { openListenerSettings(context) },
                        )
                        ZhuliRow(
                            icon = Icons.Rounded.MarkEmailRead,
                            title = "短信（取件码）",
                            subtitle = if (s.hasSms) "已授权" else "没授权 —— 快递只能靠通知和分享进来",
                            trailing = {
                                ZhuliPill(
                                    if (s.hasSms) "已授权" else "去授权",
                                    if (s.hasSms) ZhuliTone.OK else ZhuliTone.WARN,
                                )
                            },
                            onClick = {
                                if (!s.hasSms) {
                                    smsLauncher.launch(
                                        arrayOf(
                                            android.Manifest.permission.RECEIVE_SMS,
                                            android.Manifest.permission.READ_SMS,
                                        )
                                    )
                                }
                            },
                        )
                    }
                }

                item {
                    ZhuliSection(
                        title = "高级",
                        subtitle = "本地模型与 Shizuku 一键授权",
                    ) {
                        Column(
                            modifier = Modifier.padding(horizontal = 16.dp, vertical = 8.dp),
                            verticalArrangement = Arrangement.spacedBy(8.dp),
                        ) {
                            Text("本地模型", style = MaterialTheme.typography.titleSmall)
                            Text(
                                "发热时手动卸载，立即降温；下次提问会重新加载。",
                                style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                            )
                            Button(onClick = {
                                scope.launch {
                                    evictMsg = "正在卸载…"
                                    evictMsg = runCatching {
                                        val manager = org.koin.java.KoinJavaComponent.get<me.rerere.common.inference.LocalInferenceManager>(
                                            me.rerere.common.inference.LocalInferenceManager::class.java
                                        )
                                        manager.requestEviction(me.rerere.common.inference.LocalInferenceWorkload.CHAT)
                                        "已请求卸载：本地模型会在空闲时释放"
                                    }.getOrElse {
                                        "卸载失败：${it.message ?: it::class.java.simpleName}"
                                    }
                                }
                            }) { Text("卸载当前已加载的本地模型") }
                            if (evictMsg.isNotBlank()) {
                                Text(evictMsg, style = MaterialTheme.typography.bodySmall)
                            }
                            HorizontalDivider(
                                color = MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.4f)
                            )
                            ShizukuBlock()
                        }
                    }
                }
            }
        }
    }
}

@Composable
private fun SelfCheckRow(item: ZhuliSelfCheck.Item) {
    val tone = when (item.level) {
        ZhuliSelfCheck.Level.OK -> ZhuliTone.OK
        ZhuliSelfCheck.Level.WARN -> ZhuliTone.WARN
        ZhuliSelfCheck.Level.BAD -> ZhuliTone.BAD
    }
    val icon = when (item.level) {
        ZhuliSelfCheck.Level.OK -> Icons.Rounded.CheckCircle
        ZhuliSelfCheck.Level.WARN -> Icons.Rounded.Warning
        ZhuliSelfCheck.Level.BAD -> Icons.Rounded.Error
    }
    ZhuliRow(
        icon = icon,
        title = item.label,
        subtitle = item.value,
        tint = zhuliToneColor(tone),
        trailing = {
            ZhuliPill(
                when (item.level) {
                    ZhuliSelfCheck.Level.OK -> "正常"
                    ZhuliSelfCheck.Level.WARN -> "注意"
                    ZhuliSelfCheck.Level.BAD -> "断了"
                },
                tone,
            )
        },
    )
}

@Composable
private fun PrivacyRow(row: ZhuliPrivacy.SelfCheck) {
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = 16.dp, vertical = 10.dp),
        verticalArrangement = Arrangement.spacedBy(3.dp),
    ) {
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            Text(
                text = row.verdict,
                style = MaterialTheme.typography.bodyMedium,
                fontWeight = FontWeight.Medium,
                color = if (row.allOk) MaterialTheme.colorScheme.tertiary else MaterialTheme.colorScheme.error,
            )
        }
        Text(
            text = row.text,
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        if (row.note.isNotBlank()) {
            Text(
                text = row.note,
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.8f),
            )
        }
    }
}

@Composable
private fun CloudRow(row: Array<String>) {
    val at = row.getOrNull(0)?.toLongOrNull() ?: 0L
    val host = row.getOrNull(2).orEmpty()
    val model = row.getOrNull(3).orEmpty()
    val ok = row.getOrNull(5) == "成功"
    val note = row.getOrNull(6).orEmpty()
    ZhuliRow(
        icon = Icons.Rounded.Cloud,
        title = host.ifBlank { "云端" },
        subtitle = listOfNotNull(
            if (at > 0) ZhuliSelfCheck.ago(System.currentTimeMillis() - at) else null,
            model.takeIf { it.isNotBlank() },
            note.takeIf { it.isNotBlank() },
        ).joinToString(" · "),
        tint = if (ok) MaterialTheme.colorScheme.tertiary else MaterialTheme.colorScheme.error,
        trailing = { ZhuliPill(if (ok) "已发" else "拦截", if (ok) ZhuliTone.OK else ZhuliTone.BAD) },
    )
}

@Composable
private fun ShizukuBlock() {
    val context = LocalContext.current
    val installed = remember { ZhuliShizukuRunner.isShizukuInstalled(context) }
    var available by remember { mutableStateOf(ZhuliShizukuRunner.isShizukuAvailable()) }
    val scope = rememberCoroutineScope()
    val results = remember { mutableStateOf<Map<String, String>>(emptyMap()) }

    androidx.compose.runtime.DisposableEffect(installed) {
        val listener = rikka.shizuku.Shizuku.OnRequestPermissionResultListener { _, grantResult ->
            available = grantResult == android.content.pm.PackageManager.PERMISSION_GRANTED
        }
        runCatching { rikka.shizuku.Shizuku.addRequestPermissionResultListener(listener) }
        onDispose {
            runCatching { rikka.shizuku.Shizuku.removeRequestPermissionResultListener(listener) }
        }
    }

    Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
        Text("Shizuku 一键授权", style = MaterialTheme.typography.titleSmall)
        Text(
            text = when {
                !installed -> "未安装 Shizuku（shizuku.rikka.app）"
                !available -> "已安装但本应用未授权"
                else -> "已就绪"
            },
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        if (installed && !available) {
            Button(onClick = { ZhuliShizukuRunner.requestPermission() }) { Text("请求 Shizuku 授权") }
            TextButton(onClick = { available = ZhuliShizukuRunner.isShizukuAvailable() }) {
                Text("我已授权，重新检测")
            }
        }
        if (available) {
            Button(onClick = {
                scope.launch {
                    val r = withContext(Dispatchers.IO) { ZhuliShizukuRunner.runAll() }
                    results.value = r.toMap().mapValues { if (it.value) "成功" else "失败" }
                }
            }) { Text("一键执行全部授权") }
            ZhuliShizukuRunner.COMMANDS.forEach { cmd ->
                val result = results.value[cmd.id]
                Text(
                    text = "· ${cmd.label}" + if (result != null) "：$result" else "",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        }
    }
}

private data class SelfCheckState(
    val snap: ZhuliSelfCheckData.Snapshot,
    val items: List<ZhuliSelfCheck.Item>,
    val privacyRows: List<ZhuliPrivacy.SelfCheck>,
    val cloud: List<Array<String>>,
    val hasAccess: Boolean,
    val hasSms: Boolean,
)

private fun loadSelfCheck(context: android.content.Context): SelfCheckState {
    val snap = ZhuliSelfCheckData.snapshot(context)
    val health = ZhuliSelfCheckData.health(context)
    val items = ZhuliSelfCheckData.collect(context, health, snap)
    val cloud = try {
        val db = InboxDatabase(context.applicationContext)
        try {
            db.recentCloudLog(6)
        } finally {
            runCatching { db.close() }
        }
    } catch (_: Throwable) {
        emptyList()
    }
    return SelfCheckState(
        snap = snap,
        items = items,
        privacyRows = ZhuliSelfCheckData.privacyRows(health.cloudReady),
        cloud = cloud,
        hasAccess = me.rerere.rikkahub.utils.PermissionChecker.hasNotificationListenerAccess(context),
        hasSms = context.checkSelfPermission(android.Manifest.permission.READ_SMS) ==
            android.content.pm.PackageManager.PERMISSION_GRANTED,
    )
}

private fun openListenerSettings(context: android.content.Context) {
    val intents = listOf(
        Intent(Settings.ACTION_NOTIFICATION_LISTENER_SETTINGS),
        Intent(Settings.ACTION_SETTINGS),
    )
    for (intent in intents) {
        intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        if (runCatching { context.startActivity(intent) }.isSuccess) return
    }
}
