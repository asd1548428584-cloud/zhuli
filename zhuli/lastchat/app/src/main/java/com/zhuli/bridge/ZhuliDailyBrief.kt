package com.zhuli.bridge

import android.content.Context
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.Today
import androidx.compose.material3.Button
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.Dialog
import java.time.LocalDate
import java.time.format.DateTimeFormatter

/**
 * 每日简报（本地版）。
 *
 * 分工：
 * - [ZhuliBriefWorker] 每天 07:30 用模型写一段人话简报 → 通知栏（已有能力）；
 * - 这里负责「看得见、随时能查」：冷启动当天第一次弹一次卡片，设置页也能随时打开。
 *
 * 卡片里的数字永远直接查本地库（待办 / 待取快递 / 近 7 天支出），不依赖模型、不联网 ——
 * 所以没网、没 provider、模型没下完，这张卡片照样有内容。
 */
object ZhuliBriefStore {
    private const val PREFS = "zhuli_brief_store"
    private const val K_LAST_TEXT = "last_text"
    private const val K_LAST_AT = "last_at"
    private const val K_SHOWN_DATE = "shown_date"
    private const val K_LAST_NOTE = "last_note"

    private fun sp(ctx: Context) = ctx.applicationContext.getSharedPreferences(PREFS, Context.MODE_PRIVATE)

    fun saveText(ctx: Context, text: String, at: Long = System.currentTimeMillis()) {
        sp(ctx).edit().putString(K_LAST_TEXT, text).putLong(K_LAST_AT, at).apply()
    }

    fun lastText(ctx: Context): String = sp(ctx).getString(K_LAST_TEXT, "").orEmpty()

    fun lastAt(ctx: Context): Long = sp(ctx).getLong(K_LAST_AT, 0L)

    /** 上次生成的结果/失败原因：让"今天没生成"不再是一句没有下文的话。 */
    fun saveNote(ctx: Context, note: String) {
        sp(ctx).edit().putString(K_LAST_NOTE, note.take(160)).apply()
    }

    fun lastNote(ctx: Context): String = sp(ctx).getString(K_LAST_NOTE, "").orEmpty()

    /** 今天是否还没弹过（每天只自动弹一次，别当骚扰）。 */
    fun shouldShowToday(ctx: Context): Boolean {
        if (!com.zhuli.core.AssistantPrefs.briefEnabled(ctx)) return false
        val today = LocalDate.now().toString()
        return sp(ctx).getString(K_SHOWN_DATE, "") != today
    }

    fun markShownToday(ctx: Context) {
        sp(ctx).edit().putString(K_SHOWN_DATE, LocalDate.now().toString()).apply()
    }
}

/** 简报卡片的数据（全部本地算）。 */
data class ZhuliBriefNumbers(
    val todoCount: Int,
    val todoPreview: String,
    val courierCount: Int,
    val courierPreview: String,
    val weekSpend: Double,
)

fun collectBriefNumbers(ctx: Context): ZhuliBriefNumbers {
    val tools = ZhuliQueryTools(ctx)
    val now = System.currentTimeMillis()
    val todos = runCatching { tools.queryTodo(5) }.getOrDefault(emptyList())
    val couriers = runCatching { ZhuliCourierTool(ctx).queryCourier(now) }
        .getOrDefault(emptyList())
        .filterNot { it.note.contains("签收") || it.text.contains("签收") }
    val ledger = runCatching { tools.queryLedger(7, now) }.getOrDefault(emptyList())
    // 支出 = 方向不是「收」（与 ZhuliQueryAnswer.ledger 同口径）
    val spend = ledger.filter { it.dir != "收" }.sumOf { it.amount }
    return ZhuliBriefNumbers(
        todoCount = todos.size,
        todoPreview = todos.firstOrNull()
            ?.let { ZhuliQueryAnswer.todo(listOf(it), now).lineSequence().firstOrNull().orEmpty() }
            ?.let(::prettyPreview)
            .orEmpty(),
        courierCount = couriers.size,
        courierPreview = couriers.firstOrNull()?.note?.replace(Regex("^快递[·•]\\s*"), "").orEmpty(),
        weekSpend = spend,
    )
}

/**
 * 把查询结果那行"技术味"的文本洗成人话：
 * 去掉「· 」前缀、括号里的相对时间提示、以及「| 时间来源不明」这类内部标注。
 * 简报是给用户看的，不是调试面板。
 */
private fun prettyPreview(raw: String): String = raw
    .replace(Regex("^\\s*[·•]\\s*"), "")
    .replace(Regex("[（(]时间已过[^）)]*[）)]"), "")
    .replace(Regex("\\s*[|｜]\\s*时间来源不明\\s*"), "")
    .replace(Regex("^待办[:：]\\s*"), "")
    .replace(Regex("\\s{2,}"), " ")
    .trim()
    .take(80)

@Composable
fun ZhuliDailyBriefContent(compact: Boolean = false, onGenerate: (() -> Unit)? = null) {
    val ctx = LocalContext.current
    val numbers = remember { collectBriefNumbers(ctx) }
    val modelText = remember { ZhuliBriefStore.lastText(ctx) }
    val modelAt = remember { ZhuliBriefStore.lastAt(ctx) }
    val lastNote = remember { ZhuliBriefStore.lastNote(ctx) }
    var msg by remember { androidx.compose.runtime.mutableStateOf("") }
    val today = LocalDate.now().format(DateTimeFormatter.ofPattern("M 月 d 日"))

    Column(
        modifier = Modifier
            .fillMaxWidth()
            .verticalScroll(rememberScrollState()),
        verticalArrangement = Arrangement.spacedBy(10.dp),
    ) {
        if (!compact) {
            ZhuliHero(
                eyebrow = today,
                title = briefHeadline(numbers),
                subtitle = "数字直接来自本机库，不联网也算得出来",
                icon = Icons.Rounded.Today,
                tone = ZhuliTone.ACCENT,
            )
        }

        Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
            ZhuliStat(
                value = numbers.todoCount.toString(),
                label = "待办",
                modifier = Modifier.weight(1f),
                tone = if (numbers.todoCount > 0) ZhuliTone.ACCENT else ZhuliTone.NEUTRAL,
            )
            ZhuliStat(
                value = numbers.courierCount.toString(),
                label = "待取快递",
                modifier = Modifier.weight(1f),
            )
            ZhuliStat(
                value = "¥" + "%.2f".format(numbers.weekSpend),
                label = "近 7 天支出",
                modifier = Modifier.weight(1f),
            )
        }

        val previewLines = listOfNotNull(
            numbers.todoPreview.takeIf { it.isNotBlank() }?.let { "待办：$it" },
            numbers.courierPreview.takeIf { it.isNotBlank() }?.let { "快递：$it" },
        )
        if (previewLines.isNotEmpty()) {
            ZhuliSection(title = "先说这两件") {
                previewLines.forEach { line ->
                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(horizontal = 16.dp, vertical = 10.dp),
                    ) {
                        Text(line, style = MaterialTheme.typography.bodyMedium)
                    }
                }
            }
        }

        if (modelText.isNotBlank()) {
            val whenText = if (modelAt > 0) {
                java.text.SimpleDateFormat("M-d HH:mm", java.util.Locale.CHINA)
                    .format(java.util.Date(modelAt))
            } else ""
            ZhuliSection(title = "早上那段话（$whenText）") {
                Text(
                    text = modelText,
                    style = MaterialTheme.typography.bodyMedium,
                    modifier = Modifier.padding(horizontal = 16.dp, vertical = 12.dp),
                )
            }
        } else {
            ZhuliSection(title = "人话总结") {
                Column(
                    modifier = Modifier.padding(horizontal = 16.dp, vertical = 12.dp),
                    verticalArrangement = Arrangement.spacedBy(6.dp),
                ) {
                    Text(
                        text = "每天 07:30 会在这里写一段人话简报；今天还没生成。上面的数字是直接从本机库算的。",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                    if (lastNote.isNotBlank()) {
                        Text(
                            text = "上次尝试：$lastNote",
                            style = MaterialTheme.typography.labelSmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.85f),
                        )
                    }
                    if (msg.isNotBlank()) {
                        Text(
                            text = msg,
                            style = MaterialTheme.typography.labelSmall,
                            color = MaterialTheme.colorScheme.primary,
                        )
                    }
                    if (onGenerate != null) {
                        Button(
                            onClick = {
                                onGenerate()
                                msg = "已排队生成，等 10–30 秒回来后刷新"
                            },
                        ) { Text("现在生成一次") }
                    }
                }
            }
        }
    }
}

private fun briefHeadline(n: ZhuliBriefNumbers): String {
    val parts = buildList {
        if (n.todoCount > 0) add("${n.todoCount} 件待办")
        if (n.courierCount > 0) add("${n.courierCount} 件快递")
        if (n.weekSpend > 0) add("近 7 天花了 ¥${"%.2f".format(n.weekSpend)}")
    }
    return if (parts.isEmpty()) "今天暂时没有要提醒的事" else parts.joinToString(" · ")
}

@Composable
fun ZhuliDailyBriefDialog(onDismiss: () -> Unit) {
    Dialog(onDismissRequest = onDismiss) {
        Surface(
            shape = RoundedCornerShape(28.dp),
            color = MaterialTheme.colorScheme.surface,
            tonalElevation = 6.dp,
            modifier = Modifier
                .fillMaxWidth()
                .widthIn(max = 460.dp),
        ) {
            Column(modifier = Modifier.padding(20.dp)) {
                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(10.dp),
                ) {
                    Icon(
                        Icons.Rounded.Today,
                        contentDescription = null,
                        tint = MaterialTheme.colorScheme.primary,
                    )
                    Column(Modifier.weight(1f)) {
                        Text(
                            "今日简报",
                            style = MaterialTheme.typography.titleLarge,
                            fontWeight = FontWeight.SemiBold,
                        )
                        Text(
                            LocalDate.now().format(DateTimeFormatter.ofPattern("M 月 d 日")),
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    }
                    ZhuliPill("本地算的", ZhuliTone.OK)
                }

                Spacer(Modifier.height(14.dp))
                Box(modifier = Modifier.heightIn(max = 430.dp)) {
                    ZhuliDailyBriefContent(compact = true)
                }
                Spacer(Modifier.height(16.dp))

                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.End,
                ) {
                    TextButton(onClick = onDismiss) { Text("关闭") }
                    Spacer(Modifier.width(8.dp))
                    Button(onClick = onDismiss) { Text("知道了") }
                }
            }
        }
    }
}

@Composable
fun ZhuliDailyBriefScreen() {
    val ctx = LocalContext.current
    ZhuliScaffold(
        title = "今日简报",
        subtitle = "待办 / 快递 / 近 7 天支出",
    ) { padding ->
        Box(
            modifier = Modifier
                .fillMaxSize()
                .padding(padding)
                .padding(horizontal = ZhuliStyle.ScreenPadding),
        ) {
            ZhuliDailyBriefContent(onGenerate = { enqueueBriefNow(ctx) })
        }
    }
}

/** 手动触发一次晨间简报（与每 07:30 的周期任务同一个 Worker）。 */
private fun enqueueBriefNow(ctx: Context) {
    runCatching {
        val request = androidx.work.OneTimeWorkRequestBuilder<ZhuliBriefWorker>().build()
        androidx.work.WorkManager.getInstance(ctx.applicationContext).enqueueUniqueWork(
            "zhuli_daily_brief_now",
            androidx.work.ExistingWorkPolicy.REPLACE,
            request,
        )
    }
}
