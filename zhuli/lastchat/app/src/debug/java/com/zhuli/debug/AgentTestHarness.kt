package com.zhuli.debug

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import com.zhuli.agent.AgentTask
import com.zhuli.agent.ModelCapabilities
import com.zhuli.agent.ModelError
import com.zhuli.agent.ModelProvider
import com.zhuli.agent.ModelRequest
import com.zhuli.agent.ModelResponse
import com.zhuli.agent.ProviderKind
import com.zhuli.agent.RegisteredTool
import com.zhuli.agent.Risk
import com.zhuli.agent.ToolCall
import com.zhuli.agent.ToolResult
import com.zhuli.agent.ToolSpec
import com.zhuli.agent.ZhuliAgentRuntime
import com.zhuli.core.ZhuliLog
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import org.json.JSONArray
import org.json.JSONObject
import java.io.File
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.CopyOnWriteArrayList
import java.util.concurrent.atomic.AtomicLong

/**
 * 批次 B1：Debug 专用 Agent 测试钩子（只存在于 src/debug，不进 Release APK）。
 *
 * 设计约束（对应 B1 指令）：
 * 1. **结果必须来自真实 AgentRuntime**：脚本后端与 Mock 工具通过
 *    [ZhuliAgentRuntime.registerTestProvider] / [registerTestTool] 注册进生产共享的
 *    AgentLoop —— 同一个 PolicyEngine、同一个审批状态机、同一个 SQLite store。
 *    这里没有任何"直接改库""跳过审批"的旁路。
 * 2. **Mock 工具**只有四种：只读回显 / 写本地（走 ASK 审批）/ 必定失败 / 可配时长的慢工具。
 *    全部不碰真实用户数据。
 * 3. **脚本化模型**按请求 tag（= taskId 前 8 位）分流剧本，多任务并发时互不串线。
 *
 * 使用方式（adb，设备端）：
 * ```
 * adb shell am broadcast -a com.zhuli.debug.TEST --es cmd run --es scenario multi_tool
 * adb shell am broadcast -a com.zhuli.debug.TEST --es cmd status
 * adb shell run-as lastchat.rikkafork.cocolal.dev cat files/agent_test/status.json
 * ```
 * 命令：setup / run / approve / deny / pause / resume / cancel / status / list
 */

private const val TAG = "zhuli.agenttest"
private const val ACTION = "com.zhuli.debug.TEST"

/** 脚本化模型后端：记录每次请求（上下文条数、可见工具），按剧本返回。 */
class ScriptedTestProvider : ModelProvider {
    override val capabilities = ModelCapabilities(
        providerId = "zhuli-test", modelId = "scripted-debug", kind = ProviderKind.CLOUD,
        tools = true, tier = 3,
    )

    /** 当前剧本：(请求, 该任务第几轮) -> 响应。由 run 命令按场景设置。 */
    @Volatile
    var script: ((ModelRequest, Int) -> ModelResponse)? = null

    /** 每个任务（tag）已消费的轮次。 */
    private val turnIndex = ConcurrentHashMap<String, Int>()
    private val counter = AtomicLong(1000)

    data class ReqStat(val tag: String, val messages: Int, val tools: List<String>, val at: Long)
    val requestLog = CopyOnWriteArrayList<ReqStat>()

    override suspend fun complete(request: ModelRequest): ModelResponse {
        val turn = (turnIndex.merge(request.tag, 1) { old, _ -> old + 1 } ?: 0) - 1
        requestLog.add(ReqStat(request.tag, request.messages.size, request.tools.map { it.name }, System.currentTimeMillis()))
        ZhuliLog.i(TAG, "scripted 第${turn + 1}轮 tag=${request.tag} messages=${request.messages.size}")
        val s = script ?: throw ModelError(ModelError.Kind.FATAL, "测试剧本未配置")
        return s(request, turn)
    }

    private fun id(): String = "t${counter.incrementAndGet()}"

    // ---- 剧本构造辅助 ----
    private fun final(text: String) = ModelResponse(text = text, providerId = capabilities.providerId, modelId = capabilities.modelId)
    private fun calls(vararg c: ToolCall) = ModelResponse(text = "", toolCalls = c.toList(), providerId = capabilities.providerId, modelId = capabilities.modelId)
    private fun tc(name: String, argsJson: String) = ToolCall(id = id(), name = name, arguments = argsJson)

    /**
     * 场景注册表。key = 场景名。
     * [ScenarioDef.goal] 生成带 nonce 的目标文案（避免跨次运行撞上 30 分钟去重窗口）；
     * [ScenarioDef.scriptFactory] 拿 nonce 生成剧本闭包（按轮次返回响应）。
     */
    data class ScenarioDef(val goal: (Long) -> String, val scriptFactory: (Long) -> (ModelRequest, Int) -> ModelResponse)

    val scenarios: Map<String, ScenarioDef> = mapOf(
        // A：一轮 3 个只读调用，顺序执行
        "multi_tool" to ScenarioDef(
            { nonce -> "测试任务：multi_tool @$nonce" },
            { nonce -> { _, turn ->
                if (turn == 0) calls(
                    tc("test_read", """{"query":"A-$turn"}"""),
                    tc("test_read", """{"query":"B-$turn"}"""),
                    tc("test_read", """{"query":"C-$turn"}"""),
                ) else final("多工具场景完成（第${turn + 1}轮）")
            } }
        ),

        // 超限：一轮给 5 个调用，本轮只执行前 3 个
        "over_limit" to ScenarioDef(
            { nonce -> "测试任务：over_limit @$nonce" },
            { _ -> { _, turn ->
                if (turn == 0) calls(
                    tc("test_read", """{"query":"1"}"""),
                    tc("test_read", """{"query":"2"}"""),
                    tc("test_read", """{"query":"3"}"""),
                    tc("test_read", """{"query":"4"}"""),
                    tc("test_read", """{"query":"5"}"""),
                ) else final("超限场景完成（第${turn + 1}轮）")
            } }
        ),

        // 工具失败：调用必败工具，模型看到失败后收尾
        "tool_fail" to ScenarioDef(
            { nonce -> "测试任务：tool_fail @$nonce" },
            { _ -> { _, turn ->
                if (turn == 0) calls(tc("test_boom", """{"query":"x"}"""))
                else final("工具失败场景完成（工具报错已被模型看到）")
            } }
        ),

        // 需审批：写本地工具 → WAITING_APPROVAL；批准/拒绝后模型收尾
        "approval" to ScenarioDef(
            { nonce -> "测试任务：approval @$nonce" },
            { nonce -> { _, turn ->
                if (turn == 0) calls(tc("test_note", """{"text":"B1-审批场景-$nonce"}"""))
                else final("审批场景完成")
            } }
        ),

        // 拒绝审批：拒绝后模型看到拒绝结果并收尾
        "reject" to ScenarioDef(
            { nonce -> "测试任务：reject @$nonce" },
            { nonce -> { _, turn ->
                if (turn == 0) calls(tc("test_note", """{"text":"B1-拒绝场景-$nonce"}"""))
                else final("拒绝场景完成（写操作未执行）")
            } }
        ),

        // 审批过期：等待 TTL(5min) 后批准 → 应判过期并重新询问
        "approval_expire" to ScenarioDef(
            { nonce -> "测试任务：approval_expire @$nonce" },
            { nonce -> { _, turn ->
                if (turn <= 1) calls(tc("test_note", """{"text":"B1-过期场景-$nonce"}"""))
                else final("过期场景完成")
            } }
        ),

        // 慢工具：暂停 / 取消用（15 秒，足够外部发控制命令）
        "pause_tool" to ScenarioDef(
            { nonce -> "测试任务：pause_tool @$nonce" },
            { _ -> { _, turn ->
                if (turn == 0) calls(tc("test_slow", """{"ms":15000}"""))
                else final("暂停恢复场景完成")
            } }
        ),
        "cancel_tool" to ScenarioDef(
            { nonce -> "测试任务：cancel_tool @$nonce" },
            { _ -> { _, turn ->
                if (turn == 0) calls(tc("test_slow", """{"ms":20000}"""))
                else final("取消场景完成（不应到达：中途会取消）")
            } }
        ),

        // 审批等待中取消：WAITING_APPROVAL → cancel → CANCELLED，之后批准必须无效
        "cancel_wait" to ScenarioDef(
            { nonce -> "测试任务：cancel_wait @$nonce" },
            { nonce -> { _, turn ->
                if (turn == 0) calls(tc("test_note", """{"text":"B1-等待中取消-$nonce"}"""))
                else final("取消等待场景完成（不应到达）")
            } }
        ),

        // 并发：每个任务按自己的 tag 走剧本
        "concurrent" to ScenarioDef(
            { nonce -> "测试任务：concurrent @$nonce" },
            { _ -> { req, turn ->
                if (turn == 0) calls(tc("test_read", """{"query":"tag=${req.tag}"}"""))
                else final("并发任务 ${req.tag} 完成")
            } }
        ),

        // 长历史：10 轮工具调用。终身预算 GOAL(1)+20(轮)+2(上限NOTE)+1(收尾)=24 步，恰好压着
        // hardStepCap=24；组数 10 交换+1 收尾+2 NOTE=13 组 > MAX_CONTEXT_GROUPS=12 → 裁剪必然生效
        "long_context" to ScenarioDef(
            { nonce -> "测试任务：long_context @$nonce" },
            { _ -> { _, turn ->
                if (turn < 10) calls(tc("test_read", """{"query":"step-$turn"}"""))
                else final("长历史场景完成：共 ${turn + 1} 轮")
            } }
        ),

        // 本地模式：localOnly 任务 + 云端脚本后端 → 路由必须拒绝，脚本一次都不能被调用
        "local_only" to ScenarioDef(
            { nonce -> "测试任务：local_only @$nonce" },
            { _ -> { _, _ -> throw ModelError(ModelError.Kind.FATAL, "local_only 任务不应到达云端脚本后端") } }
        ),
    )
}

/** Mock 工具集（B1：不得绕过 PolicyEngine —— 全部按真实风险等级注册）。 */
fun buildMockTools(): List<RegisteredTool> = listOf(
    RegisteredTool(
        ToolSpec("test_read", "测试：只读回显（无副作用）",
            """{"type":"object","properties":{"query":{"type":"string"}},"required":["query"]}"""),
        Risk.READ_ONLY, "test_read",
    ) { raw ->
        val q = try { JSONObject(raw.ifBlank { "{}" }).optString("query") } catch (t: Throwable) { "" }
        if (q.isBlank()) ToolResult.fail("没给 query") else ToolResult.ok("read ok：$q")
    },
    RegisteredTool(
        ToolSpec("test_note", "测试：写本地（走审批确认）",
            """{"type":"object","properties":{"text":{"type":"string"}},"required":["text"]}"""),
        Risk.WRITE_LOCAL, "test_note",
    ) { raw ->
        val t = try { JSONObject(raw.ifBlank { "{}" }).optString("text") } catch (t: Throwable) { "" }
        ToolResult.ok("note ok：$t")
    },
    RegisteredTool(
        ToolSpec("test_boom", "测试：必定失败",
            """{"type":"object","properties":{"query":{"type":"string"}},"required":["query"]}"""),
        Risk.READ_ONLY, "test_boom",
    ) { _ -> ToolResult.fail("boom：预设失败") },
    RegisteredTool(
        ToolSpec("test_slow", "测试：慢工具（取消/暂停时序用）",
            """{"type":"object","properties":{"ms":{"type":"integer"}},"required":[]}"""),
        Risk.READ_ONLY, "test_slow",
    ) { raw ->
        val ms = try { JSONObject(raw.ifBlank { "{}" }).optLong("ms", 5000) } catch (t: Throwable) { 5000L }
        delay(ms)
        ToolResult.ok("slow ok after ${ms}ms")
    },
)

/** 运行期状态：场景 → taskId 映射、结局记录。 */
object AgentTestState {
    @Volatile
    var provider: ScriptedTestProvider? = null
    val taskByScenario = ConcurrentHashMap<String, String>()
    val outcomeByScenario = ConcurrentHashMap<String, String>()
    @Volatile
    var lastError: String = ""
    val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
}

class AgentTestReceiver : BroadcastReceiver() {

    override fun onReceive(context: Context, intent: Intent) {
        if (intent.action != ACTION) return
        val pending = goAsync()
        val app = context.applicationContext
        AgentTestState.scope.launch {
            try {
                handle(app, intent)
            } catch (t: Throwable) {
                AgentTestState.lastError = t.message ?: t.javaClass.simpleName
                ZhuliLog.w(TAG, "测试命令执行失败", t)
                android.util.Log.w(TAG, "测试命令执行失败", t)
            } finally {
                try { writeStatus(app) } catch (t: Throwable) { ZhuliLog.w(TAG, "状态落盘失败", t); android.util.Log.w(TAG, "状态落盘失败", t) }
                pending.finish()
            }
        }
    }

    private suspend fun handle(ctx: Context, intent: Intent) {
        val cmd = intent.getStringExtra("cmd") ?: return
        zhuli("收到测试命令：$cmd")
        when (cmd) {
            "setup" -> setup(ctx)
            "run" -> runScenario(ctx, intent)
            "approve" -> control(intent) { id -> ZhuliAgentRuntime.approve(id, true) ; "approved" }
            "deny" -> control(intent) { id -> ZhuliAgentRuntime.approve(id, false) ; "denied" }
            "pause" -> control(intent) { id -> ZhuliAgentRuntime.pause(id)?.let { "paused:${it.status}" } ?: "pause返回空" }
            "resume" -> control(intent) { id -> ZhuliAgentRuntime.resume(id); "resumed" }
            "cancel" -> control(intent) { id -> ZhuliAgentRuntime.cancel(id)?.let { "cancel:${it.status}" } ?: "cancel返回空" }
            "status" -> Unit // 落盘由 finally 统一处理
            "list" -> Unit
            else -> AgentTestState.lastError = "未知命令：$cmd"
        }
    }

    /** 同时写 ZhuliLog（文件，可审计）与 android Log（logcat，现场可见）。 */
    private fun zhuli(msg: String) {
        ZhuliLog.i(TAG, msg)
        android.util.Log.i(TAG, msg)
    }

    private fun setup(ctx: Context) {
        // 接收器可能在用户打开应用之前触发：先保证 runtime 已初始化
        ZhuliAgentRuntime.init(ctx)
        if (AgentTestState.provider == null) {
            val p = ScriptedTestProvider()
            ZhuliAgentRuntime.registerTestProvider(p)
            buildMockTools().forEach { ZhuliAgentRuntime.registerTestTool(it) }
            AgentTestState.provider = p
            zhuli("测试后端与 Mock 工具已注册（真实 loop/policy/store）")
        }
        restoreScriptForActiveTasks()
    }

    /**
     * 进程重启后（kill-recovery 场景）内存里的剧本丢了：恢复到一半的测试任务批准后
     * 还要再调一次模型。按活跃测试任务 goal 里的场景名把剧本接回去——统一给"收尾分支"
     * （turn 取大数），因为重启前的中间轮次已经落库，不需要重放。
     */
    private fun restoreScriptForActiveTasks() {
        val p = AgentTestState.provider ?: return
        for (t in ZhuliAgentRuntime.store.active()) {
            if (t.origin != "test") continue
            val m = Regex("^测试任务：([^ ]+) @").find(t.goal) ?: continue
            val def = p.scenarios[m.groupValues[1]] ?: continue
            p.script = { req, _ -> def.scriptFactory(0L)(req, Int.MAX_VALUE / 2) }
            zhuli("已为存活任务恢复剧本：${t.id.take(8)} 场景=${m.groupValues[1]}")
        }
    }

    private suspend fun runScenario(ctx: Context, intent: Intent) {
        setup(ctx)
        val scenario = intent.getStringExtra("scenario") ?: run { AgentTestState.lastError = "缺 scenario"; return }
        val p = AgentTestState.provider!!
        val def = p.scenarios[scenario] ?: run { AgentTestState.lastError = "未知场景：$scenario"; return }
        val nonce = System.currentTimeMillis()
        val goal = def.goal(nonce)
        p.script = def.scriptFactory(nonce)
        val session = intent.getStringExtra("session")?.takeIf { it.isNotBlank() } ?: "test"
        val localOnly = intent.getBooleanExtra("localOnly", false)
        zhuli("发起测试任务：$goal session=$session localOnly=$localOnly")

        // 找到新任务的 id：goal 精确匹配（dedupeKey == goal.trim()）
        AgentTestState.scope.launch {
            try {
                val outcome = ZhuliAgentRuntime.start(goal, origin = "test", localOnly = localOnly, sessionId = session)
                val detail = when (outcome) {
                    is com.zhuli.agent.RunOutcome.Failed -> "：${outcome.error}"
                    else -> ""
                }
                AgentTestState.outcomeByScenario["${scenario}@$nonce"] = outcome.javaClass.simpleName + detail
                zhuli("测试任务结束：$scenario → ${outcome.javaClass.simpleName}$detail")
            } catch (t: Throwable) {
                AgentTestState.outcomeByScenario["${scenario}@$nonce"] = "THROWN:${t.message}"
            }
        }
        // 等任务出现在 store 里（含已完成的），记录映射（最多 10 秒）
        repeat(50) {
            val t = ZhuliAgentRuntime.store.recent(30).firstOrNull { it.goal == goal }
            if (t != null) {
                AgentTestState.taskByScenario[scenario] = t.id
                zhuli("任务已创建：${t.id} 状态=${t.status}")
                return
            }
            delay(200)
        }
        AgentTestState.lastError = "任务 10 秒内未出现在 store：$scenario"
    }

    /** task 参数支持：完整 id / 前 8 位 / 场景名。 */
    private fun resolveTask(key: String?): AgentTask? {
        if (key.isNullOrBlank()) return null
        ZhuliAgentRuntime.store.get(key)?.let { return it }
        AgentTestState.taskByScenario[key]?.let { return ZhuliAgentRuntime.store.get(it) }
        return ZhuliAgentRuntime.store.recent(50).firstOrNull { it.id.startsWith(key) }
    }

    private suspend fun control(intent: Intent, op: suspend (String) -> String) {
        val t = resolveTask(intent.getStringExtra("task"))
        if (t == null) { AgentTestState.lastError = "找不到任务：${intent.getStringExtra("task")}"; return }
        val msg = op(t.id)
        zhuli("控制命令完成：task=${t.id.take(8)} → $msg")
    }

    // ---- 状态落盘（adb run-as 可读） ----
    private fun writeStatus(ctx: Context) {
        val dir = File(ctx.filesDir, "agent_test").apply { mkdirs() }
        val root = JSONObject()
        root.put("time", System.currentTimeMillis())
        root.put("lastError", AgentTestState.lastError)

        // runtime 尚未初始化时也要能落盘（否则脚本端无从排障）
        if (!ZhuliAgentRuntime.isInitialized) {
            root.put("note", "ZhuliAgentRuntime 未初始化（setup 命令会先 init）")
            File(dir, "status.json").writeText(root.toString(2))
            return
        }

        val tasks = JSONArray()
        for (t in ZhuliAgentRuntime.store.recent(20)) {
            val o = JSONObject()
            o.put("id", t.id)
            o.put("status", t.status.name)
            o.put("goal", t.goal)
            o.put("session", t.sessionId)
            o.put("origin", t.origin)
            o.put("result", t.result)
            o.put("error", t.error)
            o.put("pendingRisk", t.pendingRisk)
            o.put("phase", t.phase)
            o.put("localOnly", t.localOnly)
            val steps = JSONArray()
            for (s in ZhuliAgentRuntime.store.steps(t.id)) {
                val so = JSONObject()
                so.put("seq", s.seq)
                so.put("kind", s.kind.name)
                so.put("tool", s.toolName)
                so.put("ok", s.ok)
                so.put("content", s.content.take(120))
                steps.put(so)
            }
            o.put("steps", steps)
            val execs = JSONArray()
            for (e in ZhuliAgentRuntime.store.toolExecutions(t.id)) {
                val eo = JSONObject()
                eo.put("tool", e.tool)
                eo.put("state", e.state.name)
                eo.put("attempts", e.attempts)
                eo.put("result", e.result.take(80))
                execs.put(eo)
            }
            o.put("execs", execs)
            tasks.put(o)
        }
        root.put("tasks", tasks)

        val map = JSONObject()
        AgentTestState.taskByScenario.forEach { (k, v) -> map.put(k, v) }
        root.put("taskByScenario", map)
        val outcomes = JSONObject()
        AgentTestState.outcomeByScenario.forEach { (k, v) -> outcomes.put(k, v) }
        root.put("outcomes", outcomes)

        AgentTestState.provider?.let { p ->
            val po = JSONObject()
            po.put("requestCount", p.requestLog.size)
            val reqs = JSONArray()
            p.requestLog.forEach { r ->
                reqs.put(JSONObject().put("tag", r.tag).put("messages", r.messages).put("tools", JSONArray(r.tools)))
            }
            po.put("requests", reqs)
            root.put("provider", po)
        }

        File(dir, "status.json").writeText(root.toString(2))
    }
}
