package com.zhuli.bridge

import android.content.Context
import android.content.Intent
import android.provider.Settings
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TextField
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import com.zhuli.core.Persona
import com.zhuli.core.Personas
import kotlinx.coroutines.launch
import me.rerere.rikkahub.data.datastore.SettingsStore
import org.koin.compose.koinInject

/**
 * T-G2/B8 新用户引导（用户要求"新用户要主动向我要信息/权限"）：
 * 首次启动主界面弹出，一次搞定：
 * - 昵称（怎么称呼你）+ 人格档（均衡/温和/老大）
 * - 通知使用权（采集必需）→ 跳系统设置
 * - 电池优化豁免提示（Shizuku 向导在自检面板）
 * 完成后标记不再弹；自检面板可重开。
 */
object ZhuliSetupDialogState {
    private const val PREFS = "zhuli_setup"
    fun shouldShow(context: Context): Boolean =
        !context.getSharedPreferences(PREFS, Context.MODE_PRIVATE).getBoolean("done", false)

    fun markDone(context: Context) {
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE).edit().putBoolean("done", true).apply()
    }
}

@OptIn(ExperimentalLayoutApi::class)
@Composable
fun ZhuliSetupDialog(onDismiss: () -> Unit) {
    val context = LocalContext.current
    val settingsStore: SettingsStore = koinInject()
    val scope = rememberCoroutineScope()
    var nickname by remember { mutableStateOf("") }
    var personaId by remember { mutableStateOf(Persona.DEFAULT_ID) }
    var step by remember { mutableStateOf(0) }

    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("zhuli 助理 · 首次设置") },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
                if (step == 0) {
                    Text("1/2 · 怎么称呼你？想用哪种语气？", style = MaterialTheme.typography.bodyMedium)
                    TextField(
                        value = nickname,
                        onValueChange = { nickname = it },
                        label = { Text("你的昵称（可留空）") },
                        modifier = Modifier.fillMaxWidth(),
                    )
                    // 真机（1216px 宽）上三个按钮塞不进一行，第三个会被挤成 1px 竖条 ——
                    // 改 FlowRow：放不下自动换行，不再挤压。
                    FlowRow(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.spacedBy(8.dp),
                        verticalArrangement = Arrangement.spacedBy(8.dp),
                    ) {
                        Personas.presets.forEach { p ->
                            val selected = personaId == p.id
                            val label = "${p.emoji}${p.name}"
                            if (selected) {
                                Button(onClick = { personaId = p.id }) { Text("✅ $label") }
                            } else {
                                OutlinedButton(onClick = { personaId = p.id }) { Text(label) }
                            }
                        }
                    }
                } else {
                    Text("2/2 · 需要你手动开启两项权限（采集数据必需）：", style = MaterialTheme.typography.bodyMedium)
                    Text("① 通知使用权 —— 让我能读到你的通知并整理成记忆", style = MaterialTheme.typography.bodySmall)
                    Button(onClick = {
                        runCatching {
                            context.startActivity(Intent(Settings.ACTION_NOTIFICATION_LISTENER_SETTINGS)
                                .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
                        }
                    }) { Text("去开启通知使用权") }
                    Text("② 电池优化豁免 —— 防止系统后台杀掉我", style = MaterialTheme.typography.bodySmall)
                    Text("开启方式：开发者页 → zhuli 自检 → Shizuku 授权向导「一键执行」（有 Shizuku 时）；或系统设置手动关闭电池优化。", style = MaterialTheme.typography.bodySmall)
                }
            }
        },
        confirmButton = {
            if (step == 0) {
                Button(onClick = {
                    val picked = Personas.presets.firstOrNull { it.id == personaId }
                        ?: Personas.BALANCED
                    val p2 = if (nickname.isNotBlank()) picked.copy(callUser = "「$nickname」") else picked
                    runCatching {
                        com.zhuli.core.AssistantPrefs.setPersonaId(context, p2.id)
                        if (nickname.isNotBlank()) {
                            context.getSharedPreferences("zhuli_persona", Context.MODE_PRIVATE)
                                .edit().putString("nickname", nickname).apply()
                        }
                    }
                    // 人格要真的生效：zhuli 助手是导入时按当时的 Persona 生成 systemPrompt 的，
                    // 这里选完必须把提示词同步刷新，否则界面上选了「温和」，模型还是旧语气。
                    scope.launch {
                        runCatching {
                            settingsStore.update { s ->
                                val idx = s.assistants.indexOfFirst {
                                    it.name == ZhuliPersonaBootstrap.ZHULI_ASSISTANT_NAME
                                }
                                if (idx < 0) s else s.copy(
                                    assistants = s.assistants.toMutableList().apply {
                                        set(idx, this[idx].copy(systemPrompt = ZhuliPersonaBootstrap.buildSystemPrompt(p2)))
                                    }
                                )
                            }
                        }
                    }
                    step = 1
                }) { Text("下一步") }
            } else {
                Button(onClick = {
                    ZhuliSetupDialogState.markDone(context)
                    onDismiss()
                }) { Text("完成") }
            }
        },
        dismissButton = {
            TextButton(onClick = {
                if (step == 1) step = 0 else {
                    ZhuliSetupDialogState.markDone(context)
                    onDismiss()
                }
            }) { Text(if (step == 1) "上一步" else "稍后再说") }
        },
    )
}
