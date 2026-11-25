package com.zhuli.bridge

import android.content.Intent
import android.provider.Settings
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.Bolt
import androidx.compose.material.icons.rounded.CalendarMonth
import androidx.compose.material.icons.rounded.ChevronRight
import androidx.compose.material.icons.rounded.Cloud
import androidx.compose.material.icons.rounded.DeleteSweep
import androidx.compose.material.icons.rounded.Inbox
import androidx.compose.material.icons.rounded.Layers
import androidx.compose.material.icons.rounded.MarkEmailRead
import androidx.compose.material.icons.rounded.NotificationsActive
import androidx.compose.material.icons.rounded.PlayCircle
import androidx.compose.material.icons.rounded.RestartAlt
import androidx.compose.material.icons.rounded.Shield
import androidx.compose.material.icons.rounded.Today
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.lifecycle.compose.LocalLifecycleOwner
import com.zhuli.core.AssistantPrefs
import com.zhuli.core.CapturePrefs
import kotlinx.coroutines.launch
import me.rerere.rikkahub.utils.PermissionChecker

/**
 * zhuli 独立设置页（2026-11-24）。
 *
 * 用户明确要求："新做的设置不要和原版软件原来的设置混在一起，要单独一个入口。"
 * 所以这里不再往 LastChat 的 SettingPage 里塞条目，而是自己的页面、自己的分组，
 * 所有开关都直接落到 SharedPreferences（[CapturePrefs] / [AssistantPrefs]），即时生效。
 */
@Composable
fun ZhuliSettingsScreen(
    onBackground: () -> Unit,
    onSources: () -> Unit,
    onSelfCheck: () -> Unit,
    onBrief: () -> Unit,
) {
    val context = LocalContext.current
    val lifecycleOwner = LocalLifecycleOwner.current
    var tick by remember { mutableIntStateOf(0) }
    DisposableEffect(lifecycleOwner) {
        val observer = LifecycleEventObserver { _, e ->
            if (e == Lifecycle.Event.ON_RESUME) tick++
        }
        lifecycleOwner.lifecycle.addObserver(observer)
        onDispose { lifecycleOwner.lifecycle.removeObserver(observer) }
    }

    var capture by remember(tick) { mutableStateOf(CapturePrefs.enabled(context)) }
    var whitelist by remember(tick) { mutableStateOf(CapturePrefs.whitelistEnabled(context)) }
    var allowMedia by remember(tick) { mutableStateOf(CapturePrefs.allowMedia(context)) }
    var allowOngoing by remember(tick) { mutableStateOf(CapturePrefs.allowOngoing(context)) }
    var autoExtract by remember(tick) { mutableStateOf(CapturePrefs.autoExtract(context)) }
    var gate by remember(tick) { mutableStateOf(AssistantPrefs.privacyGateEnabled(context)) }
    var strict by remember(tick) { mutableStateOf(AssistantPrefs.privacyMode(context) == "strict") }
    var reminder by remember(tick) { mutableStateOf(AssistantPrefs.reminderEnabled(context)) }
    var calendar by remember(tick) { mutableStateOf(AssistantPrefs.reminderCalendarSync(context)) }
    var brief by remember(tick) { mutableStateOf(AssistantPrefs.briefEnabled(context)) }

    var showReset by remember { mutableStateOf(false) }
    var showClearDiag by remember { mutableStateOf(false) }
    var showPurge by remember { mutableStateOf(false) }
    var purgeMsg by remember { mutableStateOf("") }
    val scope = androidx.compose.runtime.rememberCoroutineScope()

    val hasNotifAccess = remember(tick) { PermissionChecker.hasNotificationListenerAccess(context) }
    val hasSms = remember(tick) {
        context.checkSelfPermission(android.Manifest.permission.READ_SMS) ==
            android.content.pm.PackageManager.PERMISSION_GRANTED
    }
    val permitted = remember(tick) { CapturePrefs.installedAllowedCount(context) }

    val smsLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.RequestMultiplePermissions()
    ) { tick++ }

    ZhuliScaffold(
        title = "zhuli 设置",
        subtitle = "只影响助理，不动原版软件的设置",
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
                ZhuliHero(
                    eyebrow = "采集与隐私",
                    title = if (capture) "助理在工作" else "采集已暂停",
                    subtitle = if (whitelist) "只收白名单里的 $permitted 个应用" else "当前收全部应用",
                    icon = Icons.Rounded.Shield,
                    tone = if (capture) ZhuliTone.OK else ZhuliTone.WARN,
                )
            }

            item {
                ZhuliSection(
                    title = "采集",
                    subtitle = "控制助理能听到哪些 App 的通知",
                ) {
                    ZhuliSwitchRow(
                        icon = Icons.Rounded.NotificationsActive,
                        title = "接收通知",
                        subtitle = "总开关。关掉后不再入库，也不再读给模型看",
                        checked = capture,
                        onCheckedChange = {
                            capture = it
                            CapturePrefs.setEnabled(context, it)
                        },
                    )
                    ZhuliSwitchRow(
                        icon = Icons.Rounded.Layers,
                        title = "只收白名单里的应用",
                        subtitle = "关掉会回到「谁弹都收」，不建议",
                        checked = whitelist,
                        enabled = capture,
                        onCheckedChange = {
                            whitelist = it
                            CapturePrefs.setWhitelistEnabled(context, it)
                        },
                    )
                    ZhuliRow(
                        icon = Icons.Rounded.Layers,
                        title = "管理来源白名单",
                        subtitle = "现在允许 $permitted 个应用",
                        trailing = { Icon(Icons.Rounded.ChevronRight, contentDescription = null) },
                        onClick = onSources,
                        enabled = capture && whitelist,
                    )
                    ZhuliSwitchRow(
                        icon = Icons.Rounded.PlayCircle,
                        title = "收媒体播放通知",
                        subtitle = "视频/音乐的播放条。默认关，避免被当成新消息",
                        checked = allowMedia,
                        enabled = capture,
                        onCheckedChange = {
                            allowMedia = it
                            CapturePrefs.setAllowMedia(context, it)
                        },
                    )
                    ZhuliSwitchRow(
                        icon = Icons.Rounded.Bolt,
                        title = "收常驻通知",
                        subtitle = "下载中、前台服务这类不会消失的通知。默认关",
                        checked = allowOngoing,
                        enabled = capture,
                        onCheckedChange = {
                            allowOngoing = it
                            CapturePrefs.setAllowOngoing(context, it)
                        },
                    )
                    ZhuliRow(
                        icon = Icons.Rounded.MarkEmailRead,
                        title = "通知使用权",
                        subtitle = if (hasNotifAccess) "已开启" else "没开 —— 现在收不到任何通知",
                        trailing = {
                            ZhuliPill(
                                if (hasNotifAccess) "正常" else "去开启",
                                if (hasNotifAccess) ZhuliTone.OK else ZhuliTone.BAD,
                            )
                        },
                        onClick = { openNotificationAccess(context) },
                    )
                    ZhuliRow(
                        icon = Icons.Rounded.Inbox,
                        title = "短信（取件码）",
                        subtitle = if (hasSms) "已授权，能自动抓取件码" else "未授权，快递只能靠通知进来",
                        trailing = {
                            ZhuliPill(
                                if (hasSms) "已授权" else "去授权",
                                if (hasSms) ZhuliTone.OK else ZhuliTone.WARN,
                            )
                        },
                        onClick = {
                            if (!hasSms) {
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
                    title = "后台处理",
                    subtitle = "入库之后，拆时间/金额、排提醒都在后台做",
                ) {
                    ZhuliSwitchRow(
                        icon = Icons.Rounded.Bolt,
                        title = "自动提取",
                        subtitle = "新消息进来就解析，不用等打开 App",
                        checked = autoExtract,
                        onCheckedChange = {
                            autoExtract = it
                            CapturePrefs.setAutoExtract(context, it)
                        },
                    )
                    ZhuliRow(
                        icon = Icons.Rounded.Inbox,
                        title = "打开后台处理",
                        subtitle = "看最近收下/拦下了什么、待提取多少",
                        trailing = { Icon(Icons.Rounded.ChevronRight, contentDescription = null) },
                        onClick = onBackground,
                    )
                }
            }

            item {
                ZhuliSection(
                    title = "隐私与出网",
                    subtitle = "内容离开手机之前先过闸门",
                ) {
                    ZhuliSwitchRow(
                        icon = Icons.Rounded.Shield,
                        title = "隐私闸门",
                        subtitle = "硬红线（证件/卡号/验证码）整条不发出",
                        checked = gate,
                        onCheckedChange = {
                            gate = it
                            AssistantPrefs.setPrivacyGateEnabled(context, it)
                            ZhuliPrivacyGate.refresh(context)
                        },
                    )
                    ZhuliSwitchRow(
                        icon = Icons.Rounded.Cloud,
                        title = "严格模式",
                        subtitle = "命中任何敏感项就只在本地说，不脱敏也不发",
                        checked = strict,
                        enabled = gate,
                        onCheckedChange = {
                            strict = it
                            AssistantPrefs.setPrivacyMode(context, if (it) "strict" else "redact")
                        },
                    )
                    ZhuliRow(
                        icon = Icons.Rounded.Cloud,
                        title = "出网记录",
                        subtitle = "每次请求都留一条，只记宿主和模型",
                        trailing = { Icon(Icons.Rounded.ChevronRight, contentDescription = null) },
                        onClick = onSelfCheck,
                    )
                }
            }

            item {
                ZhuliSection(title = "提醒与简报") {
                    ZhuliSwitchRow(
                        icon = Icons.Rounded.NotificationsActive,
                        title = "到点提醒",
                        subtitle = "识别到时间就排闹钟",
                        checked = reminder,
                        onCheckedChange = {
                            reminder = it
                            AssistantPrefs.setReminderEnabled(context, it)
                        },
                    )
                    ZhuliSwitchRow(
                        icon = Icons.Rounded.CalendarMonth,
                        title = "同步到系统日历",
                        subtitle = "提醒会写一条日程（提前 10 分钟）",
                        checked = calendar,
                        enabled = reminder,
                        onCheckedChange = {
                            calendar = it
                            AssistantPrefs.setReminderCalendarSync(context, it)
                        },
                    )
                    ZhuliSwitchRow(
                        icon = Icons.Rounded.Today,
                        title = "每日简报",
                        subtitle = "每天 07:30 一段人话总结",
                        checked = brief,
                        onCheckedChange = {
                            brief = it
                            AssistantPrefs.setBriefEnabled(context, it)
                        },
                    )
                    ZhuliRow(
                        icon = Icons.Rounded.Today,
                        title = "现在看今日简报",
                        onClick = onBrief,
                        trailing = { Icon(Icons.Rounded.ChevronRight, contentDescription = null) },
                    )
                }
            }

            item {
                ZhuliSection(title = "数据") {
                    ZhuliRow(
                        icon = Icons.Rounded.DeleteSweep,
                        title = "清理白名单外的历史消息",
                        subtitle = purgeMsg.ifBlank { "把之前误收的刷屏 App 记录删掉；分享/链接/账本保留" },
                        onClick = { showPurge = true },
                        enabled = whitelist,
                    )
                    ZhuliRow(
                        icon = Icons.Rounded.RestartAlt,
                        title = "恢复默认白名单",
                        subtitle = "微信 / QQ / 企业微信 / 钉钉 / 飞书 / 短信",
                        onClick = { showReset = true },
                    )
                    ZhuliRow(
                        icon = Icons.Rounded.DeleteSweep,
                        title = "清空采集诊断记录",
                        subtitle = "只清「收/拦」记录，不动收件箱",
                        onClick = { showClearDiag = true },
                    )
                }
            }

            item {
                Text(
                    text = "这里的设置只作用于 zhuli 助理；原版 LastChat 的模型、外观、备份仍在「设置」里。",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.padding(horizontal = 4.dp, vertical = 8.dp),
                )
            }
        }
    }

    if (showReset) {
        AlertDialog(
            onDismissRequest = { showReset = false },
            title = { Text("恢复默认白名单？") },
            text = { Text("会把你手动加进来的应用去掉，只保留聊天与短信类。") },
            confirmButton = {
                TextButton(onClick = {
                    CapturePrefs.resetWhitelist(context)
                    showReset = false
                    tick++
                }) { Text("恢复") }
            },
            dismissButton = {
                TextButton(onClick = { showReset = false }) { Text("取消") }
            },
        )
    }

    if (showPurge) {
        AlertDialog(
            onDismissRequest = { showPurge = false },
            title = { Text("清理白名单外的历史？") },
            text = {
                Text(
                    "会删掉所有不在白名单里的 App 的收件箱记录和挂在它们上面的提醒，" +
                        "并把它们静音。分享、链接、账本保留。此操作不能撤销。"
                )
            },
            confirmButton = {
                TextButton(onClick = {
                    showPurge = false
                    purgeMsg = "正在清理…"
                    scope.launch {
                        val r = kotlinx.coroutines.withContext(kotlinx.coroutines.Dispatchers.IO) {
                            ZhuliMaintenance.purgeOutsideWhitelist(context)
                        }
                        purgeMsg = if (r.failed > 0) {
                            "清理了 ${r.rows} 条，${r.failed} 个来源失败"
                        } else {
                            "已清理 ${r.rows} 条（${r.packages} 个来源）"
                        }
                        tick++
                    }
                }) { Text("清理") }
            },
            dismissButton = {
                TextButton(onClick = { showPurge = false }) { Text("取消") }
            },
        )
    }

    if (showClearDiag) {
        AlertDialog(
            onDismissRequest = { showClearDiag = false },
            title = { Text("清空诊断记录？") },
            text = { Text("只清后台处理页里「最近收下/拦下」的列表，收件箱与提醒不受影响。") },
            confirmButton = {
                TextButton(onClick = {
                    CapturePrefs.clearEvents(context)
                    showClearDiag = false
                    tick++
                }) { Text("清空") }
            },
            dismissButton = {
                TextButton(onClick = { showClearDiag = false }) { Text("取消") }
            },
        )
    }
}

private fun openNotificationAccess(context: android.content.Context) {
    val intents = listOf(
        Intent(Settings.ACTION_NOTIFICATION_LISTENER_SETTINGS),
        Intent("android.settings.ACTION_NOTIFICATION_LISTENER_SETTINGS"),
        Intent(Settings.ACTION_SETTINGS),
    )
    for (intent in intents) {
        intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        if (runCatching { context.startActivity(intent) }.isSuccess) return
    }
}
