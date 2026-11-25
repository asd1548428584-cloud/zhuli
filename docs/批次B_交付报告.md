# 批次 B（Agent 核心可靠性整改）交付报告

- 基线：`batch-a` = `9555babd49baffd76ea35da5d1e4620ff37e77e5`（1.4.8 / 41 / DB v16）
- 批次 B 代码 commit：`3dacb8968798045d92f87a024398deb1bba2ad0e`
- 日期：2026-11-25
- 未推送远端；未创建/移动 tag；`batch-a` 保持在 `9555bab`

> 报告文件在代码提交之后生成，因此单独有一条文档提交；批次 B 的代码变更以上面的 commit 为准。

---

## 1. 执行报告

### 1.1 实际修改内容（18 个文件，1803 insertions / 315 deletions）

**新增（9）**

| 文件 | 作用 |
|---|---|
| `agent/TaskContext.kt` | 审批信封：taskId、sessionId、调用指纹、过期时间、剩余调用、阶段、取消原因；兼容 v16 旧格式 |
| `agent/AgentCoordinator.kt` | 按 session/task 粒度的 Mutex、取消标志、子 Job 注册 |
| `agent/ToolArguments.kt` | 基于 JSON Schema 的工具参数校验（必填/类型/枚举/范围/长度/数组项数） |
| `agent/ProviderMessageFormat.kt` | 中立 AgentMessage → OpenAI / Claude / Gemini 三种格式，共用配对规则 |
| `test/AgentReliabilityTest.kt` | 16 例：多工具、取消、暂停、并发隔离、循环保护 |
| `test/AgentApprovalTest.kt` | 10 例：审批绑定、过期、重复、进程恢复、高风险拦截 |
| `test/AgentStateMachineTest.kt` | 9 例：状态转换、CAS、信封兼容、上下文裁剪 |
| `test/ToolArgumentsTest.kt` | 12 例：参数校验 + 三种 Provider 格式 |
| `test/AgentTestFixtures.kt` | 测试共用假模型/工具夹具 |

**修改（9）**

`AgentLoop.kt`、`AgentState.kt`、`Policy.kt`、`SqlAgentStore.kt`、`ZhuliAgentRuntime.kt`、`CloudModelProvider.kt`、`ZhuliAgentChat.kt`、`ZhuliTaskPage.kt`、`AgentFlowTest.kt`。

### 1.2 每项缺陷的修复前后行为

| # | 问题 | 改前 | 改后 |
|---|---|---|---|
| 1 | 状态机形同虚设 | `move()` 对合法/非法迁移走同一分支，照写不误 | `canMoveTo` 唯一判定表 + `AgentStore.transition()` CAS；非法迁移拒绝并留 NOTE |
| 2 | 缺少 CANCELLING | 取消直接落 CANCELLED，无法表达"工具正在停" | RUNNING 取消先落 CANCELLING，安全点落 CANCELLED；进程重启时 CANCELLING→CANCELLED 而非 PAUSED |
| 3 | 多工具调用丢失 | 一轮只取前 3 个，剩余静默丢弃；审批点之后的调用全丢 | 逐个执行并回灌；超出的记 TOOL 失败步"未执行"；剩余调用写进审批信封，批准后按原顺序继续 |
| 4 | 审批不绑定参数 | `granted` 只按 op 授权，参数一变仍可执行 | 指纹 = taskId+调用 ID+工具名+规范化参数；UI 传用户当时看到的指纹，不一致即拒绝 |
| 5 | 审批无过期 | 旧授权无限期有效 | TTL 5 分钟；旧 v16 记录 expiresAt=0，一律视为过期 |
| 6 | 重复批准 | 非 WAITING_APPROVAL 时重新 `run()`，可能与运行中的循环并发 | 返回 `AlreadyActive`，记录 NOTE，不执行第二次 |
| 7 | 审批串任务 | 只按 taskId 查记录 | 信封内 taskId 与所批准任务必须一致；指纹不符直接 FAILED |
| 8 | 并发状态覆盖 | 每次 `loop()` 新建实例与 PolicyEngine，无会话隔离 | 单例 AgentLoop + session/task 粒度锁；同会话串行、不同会话并行 |
| 9 | 取消误伤调用方 | 无取消机制 | 取消只作用于 Agent 子 Job；模型调用前、工具执行前后、每个调用后都检查 |
| 10 | 暂停与取消混淆 | 只有 PAUSED/CANCELLED | 独立 PAUSED 与 CANCELLING/CANCELLED；暂停后不自动调工具，恢复从原任务原位置继续 |
| 11 | 上下文裁剪拆散工具配对 | `steps.takeLast(24)` 按裸步数切，可只留 call 或只留 result | 按完整 assistant+tool 组裁剪（上限 12 组），中间 NOTE 不再拆组；缺失结果补合成 tool 消息 |
| 12 | 工具参数无校验 | `JSONObject.optString` 吞非法 JSON，错误类型因工具而异 | 执行前统一按 JSON Schema 校验；失败只回灌错误，不执行、不过权限 |
| 13 | 高风险可绕过 | WRITE_EXTERNAL/IRREVERSIBLE 依赖单一 `decide` 路径 | `policy.authorize(fingerprint)` 逐次授权 + `isAuthorized` 执行前二次校验；同类免问对高风险无效 |

### 1.3 核心架构变化

- 审批模型：`pendingCall: ToolCall?` → `pendingApproval: PendingApproval?`（信封，含完整恢复上下文）。
- 状态写入：`store.update(task)` → 需要状态迁移时走 `store.transition(id, from, to, now)` CAS。
- 运行时装配：`ZhuliAgentRuntime.loop()` 从"每次 new"改为返回单例。
- Provider 消息：`CloudModelProvider` 改用 `ProviderMessageFormat.openAi()`，与 Claude/Gemini 共用一套配对规则。

### 1.4 数据库变化

**DB_VERSION 仍为 16，无迁移。** 审批信封写进 v16 已有的 `zhuli_agent_task.pending_call` 列：

- 新行是 `{"v":2,...}` 信封；
- 旧行是 `[{"id":...}]` ToolCall 数组，`TaskContextCodec.decode()` 兼容；旧行无可靠授权时间，一律视为过期并要求重新确认。

因此老用户升级到批次 B 不需要迁移，存量等待审批任务不会丢。

### 1.5 未完成/部分完成

见第 5 节。

---

## 2. 测试报告

### 2.1 命令与结果

```
# 全量单测 + DevDebug 构建（同一条命令）
cd zhuli/lastchat
JAVA_HOME="C:/Program Files/Android/Android Studio/jbr" ./gradlew --no-daemon --console=plain \
  :app:testDevDebugUnitTest :app:assembleDevDebug \
  -x :app:buildWebUi -x :speech:downloadSherpaOnnxAar
→ BUILD SUCCESSFUL in 1m 42s

# 强制重跑全量单测（确认不是 UP-TO-DATE）
JAVA_HOME="C:/Program Files/Android/Android Studio/jbr" ./gradlew --no-daemon --console=plain \
  :app:testDevDebugUnitTest --rerun-tasks -x :app:buildWebUi -x :speech:downloadSherpaOnnxAar
→ BUILD SUCCESSFUL in 3m 43s
→ 255 actionable tasks: 255 executed
```

| 项 | 数值 |
|---|---|
| 测试类文件 | 38 |
| 测试总数 | **312** |
| 通过 | **312** |
| 失败 | **0** |
| 错误 | **0** |
| 跳过 | **0** |
| 相对批次 A 增量 | +47（265 → 312） |
| DevDebug 构建 | 成功（4 ABI APK） |

Agent 相关类：`AgentReliabilityTest 16`、`AgentApprovalTest 10`、`AgentStateMachineTest 9`、`ToolArgumentsTest 12`、`AgentFlowTest 8`、`AgentDegradeTest 7`、`AgentStoreSqliteTest 9`、`GatewayPolicyTest 10`、`LocalBackendPolicyTest 2`、`ToolIdempotencyTest 8`。

### 2.2 未执行的测试

| 测试 | 原因 |
|---|---|
| `app/src/test-offline/` 5 个类 | 目标类（`BackupLegacy`/`BackupPlan`/`PinHash`/`VideoAgent`/`VideoNote`）在新壳中不存在，目录明确标注不参与编译；未改动、未删除 |
| `androidTest`（`ZhuliCourierInstrumentedTest` / `ZhuliE2ETest` / `ZhuliLocalE2ETest`） | 本环境无可用设备；未运行，不声称通过 |
| `fixtures/privacy/run_privacy_test.py` | 需要真机 + `adb reverse`，批次 A 起未运行 |
| 雷电模拟器 / 努比亚 Z70 Ultra 真机验收 | 环境未接入，属批次 F |

---

## 3. 关键场景测试证据

以下均为**实际执行**的 JUnit 用例，测试名即证据索引：

| 场景 | 用例 | 断言要点 |
|---|---|---|
| 多工具连续调用 | `一次两个工具按顺序执行 结果都回到模型` | 执行顺序 `[a,b]`，第二轮请求含 `c1`、`c2` 两条 TOOL 消息且内容正确 |
| 工具结果作为下一步输入 | `工具A的结果能作为工具B的输入` | B 的参数里确实出现 A 的返回文本 `A-result` |
| 超限调用不丢 | `超过三轮的调用记为未执行而不是静默丢弃` | 4 个调用只执行 3 个，4 条 TOOL 步齐全，最后一条 `ok=false` 且含"未执行" |
| 失败后继续 | `工具失败后模型继续处理` | 第一个工具失败后第二个仍执行，最终答复正确 |
| 循环保护 | `反复请求同一工具在步数上限处停止` | `Paused`，工具执行次数 ≤ 2 |
| 审批后恢复 | `进程重启后仍能按原任务批准并保留完整上下文` | 换 loop/policy 后批准只执行一次；模型请求含原目标、assistant 调用、真实工具结果、系统提示 |
| 审批点后续调用 | `批准之后同一轮剩余的调用按顺序执行` | `[write, read]` 顺序执行 |
| 重复/过期/串任务 | `重复点击批准只执行一次`、`过期的审批不能执行任何操作`、`批准时指纹不匹配就拒绝执行` | 副作用次数为 0 或 1，无重复 |
| 取消 | `工具执行期间取消会中断工具且不触发第二次执行`、`多工具调用中途取消 排队工具不会启动`、`推理阶段取消后不再执行工具` | `Cancelled`，后续工具未启动，模型未被再次调用 |
| 暂停恢复 | `暂停后不再调用工具 恢复时从原位置继续` | 复用原任务 ID，暂停期间工具执行次数 0 |
| 并发隔离 | `同一会话的两个任务串行执行`（peak=1）、`不同会话的任务可以并行`（peak=2）、`一个任务等待审批时另一个任务照常完成`、`快速连续发送同目标只创建一个任务` | 无串线、无状态覆盖、无重复任务 |
| 上下文裁剪 | `上下文裁剪保留系统提示与当前目标`、`裁剪后每一条工具调用都有对应结果`、`孤立的 tool 结果不会被静默丢弃` | 系统提示与目标保留；每个 call 都有配对结果；孤立结果不丢 |
| 高风险拦截 | `对外高风险工具没有批准就绝不执行`、`不可逆操作不会因为同类免问而放行` | 副作用次数 0，状态停在 `WAITING_APPROVAL` |
| 参数校验 | `参数错误时工具不执行 错误回灌模型`、`非法 JSON 被拒绝`、`错误类型被拒绝`、`越界参数被拒绝` | 工具未执行，模型收到"参数校验失败" |
| Provider 格式 | `OpenAI/Claude/Gemini 格式保留工具调用与结果配对` | 三种格式均含 tool_call_id / tool_use_id / functionResponse |

---

## 4. Git 状态证据

```
批次 A commit：9555babd49baffd76ea35da5d1e4620ff37e77e5（未变）
batch-a tag  ：9555babd49baffd76ea35da5d1e4620ff37e77e5（未移动）
批次 B 代码 commit：3dacb8968798045d92f87a024398deb1bba2ad0e
当前分支     ：master
远端推送     ：未执行（无 push / force push / 历史重写）
```

批次 A 的隐私边界未改动：`PrivacyPipeline.kt`、`PrivacyDecision.kt`、`ZhuliPrivacyInterceptor.kt`、`LocalBackendPolicy.kt`、`AndroidManifest.xml` 相对 `batch-a` 的 diff 为 0 个文件。批次 A 的 265 例测试在本次 312 例中全部包含并通过，其中 `AgentDegradeTest` 验证了本地模式不切云、`LocalBackendPolicyTest` 与 `ManifestSecurityTest` 保持不变。

---

## 5. 遗留问题清单

### 已完成

八项整改均有实际代码与测试：多工具循环、审批保存与恢复、并发控制、取消与暂停、状态机、上下文裁剪、参数校验、审批风险控制。数据库未升版本。

### 部分完成

- Provider 格式：`openAi` 已接入生产链路；`claude` / `gemini` 是完整实现并有测试，但**尚未接入生产 Provider**（当前只有 OpenAI 兼容端点），属批次 D 的接线范围。
- "等待审批期间退出应用 / 进程被杀"：通过共享 store + 新 loop 的 `进程重启后仍能按原任务批准并保留完整上下文` 用例覆盖；**未做真实进程 kill 的 instrumentation 验证**。
- "工具执行期间取消"：以协程子 Job 取消覆盖；工具本身若吞掉中断，只能在安全点停下，无法强制终止系统 API 调用——UI 需展示"正在取消"状态（CANCELLING 已落库，任务页已加暂停/取消按钮）。

### 未完成 / 环境无法验证

- androidTest 与真机（雷电 / Z70 Ultra）验收。
- `run_privacy_test.py` 真机全请求体测试。
- `app/src/test-offline/` 5 个类目标实现未迁移，无法运行。
- 应用进程被系统回收后的真机恢复验证。
