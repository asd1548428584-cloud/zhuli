package com.zhuli.bridge

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
import androidx.compose.material.icons.rounded.Delete
import androidx.compose.material.icons.rounded.Edit
import androidx.compose.material.icons.rounded.Memory
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import com.zhuli.agent.ZhuliAgentRuntime
import com.zhuli.memory.Fact
import com.zhuli.memory.FactHit
import com.zhuli.memory.MemoryBackfill
import com.zhuli.memory.MemoryIndexer
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import androidx.compose.runtime.rememberCoroutineScope
import kotlinx.coroutines.withContext
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/**
 * 记忆管理页：让用户能看见、能搜、能改、能删 AI 记住的东西。
 *
 * 这一页不是「锦上添花」——没有它，后台写入的事实用户无从纠正，
 * 记忆越积越多只会变成不可控的黑箱。
 */
@Composable
fun ZhuliMemoryScreen() {
    val context = LocalContext.current
    ZhuliAgentRuntime.init(context)

    var query by remember { mutableStateOf("") }
    var tick by remember { mutableIntStateOf(0) }
    var editing by remember { mutableStateOf<Fact?>(null) }
    var deleting by remember { mutableStateOf<Fact?>(null) }
    var editValue by remember { mutableStateOf("") }
    var indexMsg by remember { mutableStateOf("") }

    val indexStatus by produceState(initialValue = MemoryIndexer.status(), tick) {
        value = withContext(Dispatchers.IO) { MemoryIndexer.status() }
    }
    val backfill by produceState(initialValue = MemoryBackfill.progress(), tick) {
        value = withContext(Dispatchers.IO) { MemoryBackfill.progress() }
    }
    val unconfirmed by produceState(initialValue = emptyList<Fact>(), tick) {
        value = withContext(Dispatchers.IO) { ZhuliAgentRuntime.memory.unconfirmed(50) }
    }

    val hits by produceState<List<FactHit>>(initialValue = emptyList(), query, tick) {
        value = withContext(Dispatchers.IO) {
            val repo = ZhuliAgentRuntime.memory
            val now = System.currentTimeMillis()
            if (query.isBlank()) repo.active(200).map { FactHit(it, 1.0) }
            else repo.search(query, now, 60)
        }
    }
    val conflicts by produceState<List<Pair<Fact, Fact>>>(initialValue = emptyList(), tick) {
        value = withContext(Dispatchers.IO) { ZhuliAgentRuntime.memory.conflicts() }
    }

    ZhuliScaffold(
        title = "我的记忆",
        subtitle = "AI 记住的每条事实，都能追到来源、能改、能删",
    ) { padding ->
        LazyColumn(
            modifier = Modifier.fillMaxSize().padding(padding),
            contentPadding = PaddingValues(
                start = ZhuliStyle.ScreenPadding, end = ZhuliStyle.ScreenPadding,
                top = 4.dp, bottom = 28.dp,
            ),
            verticalArrangement = Arrangement.spacedBy(ZhuliStyle.Gap),
        ) {
            item {
                OutlinedTextField(
                    value = query,
                    onValueChange = { query = it },
                    modifier = Modifier.fillMaxWidth(),
                    label = { Text("搜索事实：取件码 / 人名 / 金额 / 会议…") },
                    singleLine = true,
                )
            }

            if (conflicts.isNotEmpty()) {
                item {
                    ZhuliSection(title = "需要你裁决（${conflicts.size} 组）", subtitle = "同一件事有两个值，AI 不会替你偷偷选") {
                        for ((a, b) in conflicts) {
                            Column(Modifier.padding(horizontal = 16.dp, vertical = 8.dp)) {
                                Text("① ${a.display}", style = MaterialTheme.typography.bodyMedium)
                                Text("② ${b.display}", style = MaterialTheme.typography.bodyMedium)
                                Row {
                                    TextButton(onClick = {
                                        resolve(a, b, tick) { tick++ }
                                    }) { Text("保留 ①") }
                                    TextButton(onClick = {
                                        resolve(b, a, tick) { tick++ }
                                    }) { Text("保留 ②") }
                                }
                            }
                        }
                    }
                }
            }

            item {
                ZhuliSection(
                    title = if (query.isBlank()) "全部事实（${hits.size}）" else "检索结果（${hits.size}）",
                    subtitle = "置信度低于 0.5 的是推断，不一定准",
                ) {
                    if (hits.isEmpty()) {
                        ZhuliEmpty(Icons.Rounded.Memory, "还没有记住这些", "收到通知或说了「记住…」之后，事实会出现在这里")
                    }
                    for (hit in hits) {
                        val f = hit.fact
                        ZhuliRow(
                            icon = if (f.confidence >= 0.8) Icons.Rounded.Memory else Icons.Rounded.Edit,
                            title = f.display,
                            subtitle = "来源 ${f.sourceType} #${f.sourceId}｜置信度 ${String.format(Locale.US, "%.2f", f.confidence)}" +
                                "｜${fmt(f.observedAt)}｜${hit.reasons.joinToString("+")}",
                            tint = if (f.confidence >= 0.8) MaterialTheme.colorScheme.primary
                            else MaterialTheme.colorScheme.tertiary,
                            trailing = {
                                Row {
                                    TextButton(onClick = {
                                        editing = f; editValue = f.value
                                    }) { Text("纠正") }
                                    TextButton(onClick = { deleting = f }) { Text("删除") }
                                }
                            },
                        )
                    }
                }
            }

            item {
                ZhuliSection(
                    title = "向量索引",
                    subtitle = "事实写入后异步建索引；换过模型或觉得检索不准时可以重建",
                ) {
                    val scope = rememberCoroutineScope()
                    Text(
                        buildString {
                            append("已索引空间：")
                            append(indexStatus.spaces.joinToString().ifBlank { "（还没有）" })
                            append("\n有效事实 ${indexStatus.facts} 条｜索引状态：${indexStatus.stateLabel}")
                            append("\n队列 ${indexStatus.pending} 条（等待 ${indexStatus.waiting}｜失败 ${indexStatus.failed}）")
                            if (indexStatus.lastError.isNotBlank()) append("\n最近一次失败：${indexStatus.lastError}")
                            if (indexMsg.isNotBlank()) append("\n$indexMsg")
                        },
                        style = MaterialTheme.typography.bodySmall,
                        modifier = Modifier.padding(horizontal = 16.dp, vertical = 10.dp),
                    )
                    TextButton(
                        onClick = {
                            indexMsg = "已重新排队，索引在后台进行…"
                            scope.launch(Dispatchers.IO) {
                                MemoryIndexer.rebuild()
                                tick++
                            }
                        },
                        modifier = Modifier.padding(horizontal = 16.dp),
                    ) { Text("重建索引") }
                }
            }

            item {
                ZhuliSection(
                    title = "历史回填（可选）",
                    subtitle = "把过去一段时间的通知补成结构化事实；不会自动跑，也不会覆盖已有记忆",
                ) {
                    val scope = rememberCoroutineScope()
                    val bp = backfill
                    Text(
                        buildString {
                            append("状态：${bp.state}")
                            append("｜已扫描 ${bp.scanned} 条｜写入 ${bp.facts} 条")
                            if (bp.unconfirmed > 0) append("｜待确认 ${bp.unconfirmed} 条")
                            if (bp.failed > 0) append("｜失败 ${bp.failed} 条")
                            if (bp.lastError.isNotBlank()) append("\n最近错误：${bp.lastError}")
                            append("\n低置信度的会进「待确认」，不会当成你的事实。")
                        },
                        style = MaterialTheme.typography.bodySmall,
                        modifier = Modifier.padding(horizontal = 16.dp, vertical = 10.dp),
                    )
                    Row {
                        TextButton(
                            onClick = {
                                val now = System.currentTimeMillis()
                                scope.launch(Dispatchers.IO) {
                                    MemoryBackfill.start(context, now - 30L * 86_400_000L, now)
                                }
                                tick++
                            },
                            modifier = Modifier.padding(start = 16.dp),
                        ) { Text("回填最近 30 天") }
                        TextButton(onClick = {
                            val now = System.currentTimeMillis()
                            scope.launch(Dispatchers.IO) {
                                MemoryBackfill.start(context, now - 90L * 86_400_000L, now)
                            }
                            tick++
                        }) { Text("最近 90 天") }
                    }
                    Row {
                        TextButton(
                            onClick = { MemoryBackfill.pause(); tick++ },
                            modifier = Modifier.padding(start = 16.dp),
                        ) { Text("暂停") }
                        TextButton(onClick = { scope.launch(Dispatchers.IO) { MemoryBackfill.resume(context) }; tick++ }) { Text("继续") }
                        TextButton(onClick = { scope.launch(Dispatchers.IO) { MemoryBackfill.retry(context) }; tick++ }) { Text("重试失败") }
                    }
                }
            }

            if (unconfirmed.isNotEmpty()) {
                item {
                    ZhuliSection(
                        title = "待确认（${unconfirmed.size}）",
                        subtitle = "历史回填里置信度不够的推断：确认后才会成为你的事实",
                    ) {
                        for (f in unconfirmed) {
                            ZhuliRow(
                                icon = Icons.Rounded.Memory,
                                title = f.display.take(60),
                                subtitle = "置信度 ${(f.confidence * 100).toInt()}%｜来源 ${f.sourceType} #${f.sourceId}",
                                tint = MaterialTheme.colorScheme.tertiary,
                                trailing = {
                                    Row {
                                        TextButton(onClick = {
                                            ZhuliAgentRuntime.memory.confirmFact(f.id, System.currentTimeMillis())
                                            tick++
                                        }) { Text("确认") }
                                        TextButton(onClick = {
                                            ZhuliAgentRuntime.memory.deleteFact(f.id, System.currentTimeMillis())
                                            tick++
                                        }) { Text("丢弃") }
                                    }
                                },
                            )
                        }
                    }
                }
            }

            item {
                ZhuliSection(title = "推理后端", subtitle = "能调工具的模型才会被用于多步骤任务") {
                    Text(
                        ZhuliAgentRuntime.describeBackends(),
                        style = MaterialTheme.typography.bodySmall,
                        modifier = Modifier.padding(horizontal = 16.dp, vertical = 10.dp),
                    )
                }
            }
        }
    }

    editing?.let { f ->
        AlertDialog(
            onDismissRequest = { editing = null },
            title = { Text("纠正事实") },
            text = {
                Column {
                    Text("原值：${f.value}", style = MaterialTheme.typography.bodyMedium)
                    OutlinedTextField(
                        value = editValue, onValueChange = { editValue = it },
                        label = { Text("改成什么") }, singleLine = true,
                    )
                    Text("旧值会保留在历史里，不会凭空消失。", style = MaterialTheme.typography.bodySmall)
                }
            },
            confirmButton = {
                TextButton(onClick = {
                    val v = editValue.trim()
                    if (v.isNotBlank()) {
                        ZhuliAgentRuntime.memory.correct(f.id, v, System.currentTimeMillis())
                        tick++
                    }
                    editing = null
                }) { Text("保存") }
            },
            dismissButton = { TextButton(onClick = { editing = null }) { Text("取消") } },
        )
    }

    deleting?.let { f ->
        AlertDialog(
            onDismissRequest = { deleting = null },
            title = { Text("删除这条记忆？") },
            text = { Text("${f.display}\n\n只是标记为已删除，历史仍可追溯。") },
            confirmButton = {
                TextButton(onClick = {
                    ZhuliAgentRuntime.memory.deleteFact(f.id, System.currentTimeMillis())
                    deleting = null; tick++
                }) { Text("删除") }
            },
            dismissButton = { TextButton(onClick = { deleting = null }) { Text("取消") } },
        )
    }
}

private fun resolve(keep: Fact, drop: Fact, tick: Int, done: () -> Unit) {
    ZhuliAgentRuntime.memory.resolveConflict(keep.id, drop.id, System.currentTimeMillis())
    done()
}

private fun fmt(ms: Long): String =
    if (ms <= 0L) "时间未知"
    else SimpleDateFormat("M-d HH:mm", Locale.CHINA).format(Date(ms))
