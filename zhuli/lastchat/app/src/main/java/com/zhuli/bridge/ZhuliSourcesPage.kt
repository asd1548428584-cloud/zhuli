package com.zhuli.bridge

import android.content.Intent
import android.content.pm.PackageManager
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.Apps
import androidx.compose.material.icons.rounded.Layers
import androidx.compose.material.icons.rounded.Search
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.unit.dp
import com.zhuli.core.CapturePrefs
import com.zhuli.core.InboxDatabase
import com.zhuli.core.SourceRules
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

/**
 * 来源白名单（2026-11-24 新增）。
 *
 * 用户要求"获取哪个 App 改成白名单机制，而且这些要能让我自己设"。
 * 这里列出手机上所有有启动图标的 App（+ 已经喂过消息的来源），逐个开关；
 * 默认只开微信/QQ/企业微信/钉钉/飞书/短信这类人发给人的通道。
 */
@Composable
fun ZhuliSourcesScreen() {
    val context = LocalContext.current
    var allowed by remember { mutableStateOf(CapturePrefs.allowedPackages(context)) }
    var whitelistOn by remember { mutableStateOf(CapturePrefs.whitelistEnabled(context)) }
    var query by remember { mutableStateOf("") }

    val apps by produceState<List<AppEntry>>(initialValue = emptyList()) {
        value = withContext(Dispatchers.IO) { loadApps(context) }
    }

    val filtered = remember(apps, query, allowed) {
        val q = query.trim()
        apps.filter { q.isBlank() || it.label.contains(q, true) || it.pkg.contains(q, true) }
            .sortedWith(compareByDescending<AppEntry> { it.pkg in allowed }.thenBy { it.label })
    }

    ZhuliScaffold(
        title = "来源白名单",
        subtitle = "只有打开的应用，助理才收得到",
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
                    eyebrow = "采集范围",
                    title = if (whitelistOn) "只收白名单" else "收全部应用",
                    subtitle = if (whitelistOn) {
                        "已允许 ${allowed.count { CapturePrefs.isInstalled(context, it) }} 个已安装应用"
                    } else {
                        "白名单已关，任何 App 的推送都会进来"
                    },
                    icon = Icons.Rounded.Layers,
                    tone = if (whitelistOn) ZhuliTone.OK else ZhuliTone.WARN,
                )
            }

            item {
                ZhuliSection(title = "白名单模式") {
                    ZhuliSwitchRow(
                        icon = Icons.Rounded.Layers,
                        title = "只收勾选的应用",
                        subtitle = "关掉等于谁弹都收，视频/广告推送会灌进来",
                        checked = whitelistOn,
                        onCheckedChange = {
                            whitelistOn = it
                            CapturePrefs.setWhitelistEnabled(context, it)
                        },
                    )
                    ZhuliRow(
                        icon = Icons.Rounded.Apps,
                        title = "恢复默认（聊天 + 短信）",
                        subtitle = "微信 / QQ / 企业微信 / 钉钉 / 飞书 / Telegram / 短信",
                        onClick = {
                            CapturePrefs.resetWhitelist(context)
                            allowed = CapturePrefs.allowedPackages(context)
                        },
                    )
                }
            }

            item {
                OutlinedTextField(
                    value = query,
                    onValueChange = { query = it },
                    modifier = Modifier.fillMaxWidth(),
                    placeholder = { Text("搜索应用名或包名") },
                    leadingIcon = { Icon(Icons.Rounded.Search, contentDescription = null) },
                    singleLine = true,
                    keyboardOptions = androidx.compose.foundation.text.KeyboardOptions(
                        imeAction = ImeAction.Search,
                    ),
                    shape = androidx.compose.foundation.shape.RoundedCornerShape(18.dp),
                )
            }

            item {
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.SpaceBetween,
                ) {
                    Text(
                        text = "已列出 ${filtered.size} 个应用",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        modifier = Modifier.padding(start = 4.dp),
                    )
                    TextButton(
                        onClick = {
                            val toAdd = filtered.map { it.pkg }
                            CapturePrefs.allowAll(context, toAdd)
                            allowed = CapturePrefs.allowedPackages(context)
                        },
                    ) { Text("全部打开（当前列表）") }
                }
            }

            items(filtered, key = { it.pkg }) { app ->
                Column {
                    ZhuliSwitchRow(
                        icon = Icons.Rounded.Apps,
                        title = app.label,
                        subtitle = buildString {
                            append(app.pkg)
                            if (app.seen) append(" · 已喂过消息")
                            if (!whitelistOn) append(" · 白名单关闭时全部放行")
                        },
                        checked = app.pkg in allowed,
                        enabled = whitelistOn,
                        onCheckedChange = { on ->
                            CapturePrefs.setAllowed(context, app.pkg, on)
                            allowed = CapturePrefs.allowedPackages(context)
                        },
                    )
                    HorizontalDivider(
                        modifier = Modifier.padding(start = 64.dp),
                        color = MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.4f),
                    )
                }
            }

            if (filtered.isEmpty()) {
                item {
                    ZhuliEmpty(
                        icon = Icons.Rounded.Search,
                        title = "没找到这个应用",
                        hint = "换个关键词，或确认它是否安装在这台手机上",
                    )
                }
            }

            item {
                Text(
                    text = "提示：新装的应用默认不在白名单里。想让它进来，来这里打开即可；已入库的历史不会因为关掉白名单而删除。",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.padding(horizontal = 4.dp, vertical = 6.dp),
                )
            }
        }
    }
}

private data class AppEntry(
    val pkg: String,
    val label: String,
    val seen: Boolean,
)

private fun loadApps(context: android.content.Context): List<AppEntry> {
    val pm = context.packageManager
    val out = LinkedHashMap<String, AppEntry>()

    // 1) 有启动图标的普通应用
    runCatching {
        val intent = Intent(Intent.ACTION_MAIN).addCategory(Intent.CATEGORY_LAUNCHER)
        pm.queryIntentActivities(intent, PackageManager.MATCH_ALL).forEach { info ->
            val pkg = info.activityInfo.packageName
            if (pkg == context.packageName) return@forEach
            val label = runCatching { info.loadLabel(pm).toString() }.getOrDefault(pkg)
            out[pkg] = AppEntry(pkg, label, false)
        }
    }

    // 2) 已经喂过消息、但没有启动图标的（短信、系统通道等）
    runCatching {
        val seen = LinkedHashSet<String>()
        val db = InboxDatabase(context.applicationContext)
        try {
            db.topSources(40).forEach { s ->
                seen.add(s.pkg)
                if (s.pkg.isBlank()) return@forEach
                val label = SourceRules.pickLabel(
                    runCatching {
                        pm.getApplicationLabel(pm.getApplicationInfo(s.pkg, 0)).toString()
                    }.getOrNull(),
                    s.label,
                    s.pkg,
                )
                out[s.pkg] = AppEntry(s.pkg, label, true)
            }
        } finally {
            runCatching { db.close() }
        }
        CapturePrefs.recentEvents(context, CapturePrefs.MAX_EVENTS)
            .map { it.pkg }
            .filter { it.isNotBlank() }
            .forEach { seen.add(it) }
        CapturePrefs.allowedPackages(context).forEach { seen.add(it) }
        // 白名单里但既没图标也没进过库的（比如系统短信），也补一行，保证能取消
        seen.forEach { pkg ->
            if (pkg !in out) {
                val label = CapturePrefs.SUGGESTED.firstOrNull { it.first == pkg }?.second
                    ?: pkg.substringAfterLast('.')
                out[pkg] = AppEntry(pkg, label, false)
            }
        }
    }

    return out.values.toList()
}
