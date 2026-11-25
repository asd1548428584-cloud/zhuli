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
import androidx.compose.material.icons.rounded.CheckCircle
import androidx.compose.material.icons.rounded.ListAlt
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import com.zhuli.agent.AgentStep
import com.zhuli.agent.AgentTask
import com.zhuli.agent.RunOutcome
import com.zhuli.agent.ZhuliAgentRuntime
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/**
 * 任务页：看得到步骤、等确认时能点、暂停了能继续、失败能重试。
 * 这一步完成之前，Agent 在后台做了什么是用户看不见的，不能算「可信任的助理」。
 */
@Composable
fun ZhuliTaskScreen() {
    val context = LocalContext.current
    ZhuliAgentRuntime.init(context)
    val scope = rememberCoroutineScope()
    var tick by remember { mutableIntStateOf(0) }
    var selected by remember { mutableStateOf<AgentTask?>(null) }
    var message by remember { mutableStateOf("") }
    var goal by remember { mutableStateOf("") }

    val tasks by produceState<List<AgentTask>>(initialValue = emptyList(), tick) {
        value = withContext(Dispatchers.IO) { ZhuliAgentRuntime.store.recent(50) }
    }
    val steps by produceState<List<AgentStep>>(initialValue = emptyList(), selected?.id, tick) {
        val id = selected?.id
        value = if (id == null) emptyList() else withContext(Dispatchers.IO) { ZhuliAgentRuntime.store.steps(id) }
    }

    ZhuliScaffold(title = "任务", subtitle = "多步骤执行记录、等待确认与恢复") { padding ->
        LazyColumn(
            modifier = Modifier.fillMaxSize().padding(padding),
            contentPadding = PaddingValues(
                start = ZhuliStyle.ScreenPadding, end = ZhuliStyle.ScreenPadding,
                top = 4.dp, bottom = 28.dp,
            ),
            verticalArrangement = Arrangement.spacedBy(ZhuliStyle.Gap),
        ) {
            if (message.isNotBlank()) {
                item {
                    ZhuliHero(
                        eyebrow = "结果", title = "已处理", subtitle = message,
                        icon = Icons.Rounded.CheckCircle,
                        tone = if (message.contains("失败") || message.contains("拒绝")) ZhuliTone.WARN else ZhuliTone.OK,
                    )
                }
            }

            item {
                ZhuliSection(title = "发起一个任务", subtitle = "需要动手的事会分步执行，写操作先问你") {
                    androidx.compose.material3.OutlinedTextField(
                        value = goal,
                        onValueChange = { goal = it },
                        modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 6.dp),
                        label = { Text("例如：查一下我的取件码，然后把备忘记下来") },
                        maxLines = 2,
                    )
                    TextButton(
                        onClick = {
                            val g = goal.trim()
                            if (g.isBlank()) return@TextButton
                            scope.launch {
                                message = describe(ZhuliAgentRuntime.start(g))
                                goal = ""
                                tick++
                            }
                        },
                        modifier = Modifier.padding(horizontal = 16.dp),
                    ) { Text("执行") }
                }
            }

            item {
                ZhuliSection(title = "任务列表（${tasks.size}）") {
                    if (tasks.isEmpty()) {
                        ZhuliEmpty(Icons.Rounded.ListAlt, "还没有任务", "在对话里说「帮我查一下…」会在这里留下可追溯的步骤")
                    }
                    for (t in tasks) {
                        ZhuliRow(
                            icon = Icons.Rounded.ListAlt,
                            title = t.goal.take(60),
                            subtitle = "${t.status}｜${t.stepCount} 步｜${fmt(t.createdAt)}" +
                                (if (t.error.isNotBlank()) "｜${t.error.take(30)}" else ""),
                            tint = when (t.status.name) {
                                "DONE" -> MaterialTheme.colorScheme.primary
                                "FAILED", "CANCELLED" -> MaterialTheme.colorScheme.error
                                "WAITING_APPROVAL" -> MaterialTheme.colorScheme.tertiary
                                else -> MaterialTheme.colorScheme.onSurfaceVariant
                            },
                            onClick = { selected = t },
                        )
                    }
                }
            }

            val current = selected
            if (current != null) {
                item {
                    ZhuliSection(
                        title = "执行记录",
                        subtitle = "任务 ${current.id.take(8)}｜${current.status}",
                    ) {
                        if (current.status.name == "WAITING_APPROVAL") {
                            ZhuliRow(
                                icon = Icons.Rounded.CheckCircle,
                                title = "等待你的确认",
                                subtitle = current.pendingCall?.let { "工具 ${it.name}｜参数 ${it.arguments.take(80)}" }
                                    ?: "（没有找到待确认的调用）",
                                tint = MaterialTheme.colorScheme.tertiary,
                            )
                            Row(Modifier.padding(horizontal = 16.dp, vertical = 6.dp)) {
                                OutlinedButton(onClick = {
                                    scope.launch {
                                        val r = ZhuliAgentRuntime.approve(current.id, true, current.pendingApproval?.fingerprint)
                                        message = describe(r)
                                        selected = ZhuliAgentRuntime.store.get(current.id)
                                        tick++
                                    }
                                }) { Text("允许") }
                                TextButton(onClick = {
                                    scope.launch {
                                        val r = ZhuliAgentRuntime.approve(current.id, false, current.pendingApproval?.fingerprint)
                                        message = describe(r)
                                        selected = ZhuliAgentRuntime.store.get(current.id)
                                        tick++
                                    }
                                }) { Text("拒绝") }
                            }
                        }
                        if (current.status.name == "PAUSED" || current.status.name == "PENDING") {
                            TextButton(onClick = {
                                scope.launch {
                                    val r = ZhuliAgentRuntime.resume(current.id)
                                    message = describe(r)
                                    selected = ZhuliAgentRuntime.store.get(current.id)
                                    tick++
                                }
                            }, modifier = Modifier.padding(horizontal = 16.dp)) { Text("继续执行") }
                        }
                        if (current.status.name in setOf("RUNNING", "WAITING_APPROVAL", "CANCELLING")) {
                            TextButton(onClick = {
                                scope.launch {
                                    ZhuliAgentRuntime.pause(current.id)
                                    selected = ZhuliAgentRuntime.store.get(current.id)
                                    tick++
                                }
                            }, modifier = Modifier.padding(horizontal = 16.dp)) { Text("暂停任务") }
                        }
                        TextButton(onClick = {
                            scope.launch {
                                ZhuliAgentRuntime.cancel(current.id)
                                selected = ZhuliAgentRuntime.store.get(current.id)
                                tick++
                            }
                        }, modifier = Modifier.padding(horizontal = 16.dp)) { Text("取消任务") }

                        if (steps.isEmpty()) {
                            ZhuliEmpty(Icons.Rounded.ListAlt, "暂时没有步骤", "每次模型决策与工具结果都会记在这里")
                        }
                        for (s in steps) {
                            Column(Modifier.padding(horizontal = 16.dp, vertical = 6.dp)) {
                                Text(
                                    "${s.seq}. ${s.kind}｜${s.toolName.ifBlank { s.providerId.ifBlank { "系统" } }}｜${fmt(s.at)}",
                                    style = MaterialTheme.typography.labelSmall,
                                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                                )
                                Text(
                                    s.content.take(300).ifBlank { "（无输出）" },
                                    style = MaterialTheme.typography.bodySmall,
                                    color = if (s.ok) MaterialTheme.colorScheme.onSurface else MaterialTheme.colorScheme.error,
                                )
                            }
                        }
                    }
                }
            }
        }
    }
}

private fun describe(r: RunOutcome): String = when (r) {
    is RunOutcome.Completed -> if (r.task.result.isNotBlank()) r.task.result.take(120) else "任务已结束：${r.task.status}"
    is RunOutcome.Suspended -> "还在等待确认：${r.call.name}"
    is RunOutcome.Paused -> "已暂停：${r.reason}"
    is RunOutcome.Failed -> "失败：${r.error}"
    is RunOutcome.Cancelled -> "已取消"
    is RunOutcome.AlreadyActive -> "同一件事已在执行，没有重复开跑"
}

private fun fmt(ms: Long): String =
    if (ms <= 0L) "—" else SimpleDateFormat("M-d HH:mm", Locale.CHINA).format(Date(ms))
