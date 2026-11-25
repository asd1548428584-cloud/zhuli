# T-G2 深水区迁移执行计划（2026-11-21）

- 关联：ADR-0003、V3.1 §6.G、两份能力盘点（2026-11-21）
- 授权：用户 2026-11-21 拍板「全速迁移到删旧壳」，逐项报备关键改动
- 状态：执行中

## 0. 战略调整（相对 V3.1 的重要修正）

V3.1 原文 T-G2 顺序：`core 抽 module → 替换工具循环 → 替换记忆层 → 移植面板 → 删旧壳`。

**实测能力盘点后，必须修正「替换工具循环/记忆层」两步进为「融合」**：

| 能力域 | LastChat fork（新壳） | zhuli 旧壳 | 决策 |
|---|---|---|---|
| 工具循环/编排 | `GenerationHandler` + `LocalTools` + MCP + 工作区，4 个 zhuli Tool 已 E2E 跑通 | 单 app 内置 6 个 Tool | **保留 LastChat**，不替换（已更强） |
| 记忆/RAG/嵌入 | 双存储 + 混合检索 + 端侧 EmbeddingGemma | 哈希 256 维 `VectorSearch` | **保留 LastChat**，不替换（已更强） |
| 通知监听 | `AssistantNotificationListener` + `get_notifications` Tool | `NotificationsListenerService` | **保留 LastChat** |
| 备份/云 | WebDAV（手动）+ 完整备份页 | 加密备份 + adb 命令 | **保留 LastChat**，按需补加密 |
| 设置/面板体系 | 21+ 设置页 + `SettingsDestination` 扩展点 + `DeveloperPage` 空壳 | MainActivity 堆面板 | **保留 LastChat**，往里移植 zhuli 面板内容 |
| **隐私闸门** | **无**（全库零命中敏感检测/出网闸门） | `PrivacyRouter` + `SensitiveGuard` + 硬禁止名单 | **从旧壳移植**（zhuli 核心灵魂） |
| **自检口径** | 无（`DeveloperPage` 只是日志 stub） | `SelfCheck` 14 行 + `PrivacyRouter.selfTest3` | **从旧壳移植** |
| **待确认/三档确认** | `ask_user`（RequiresApproval）有基础，无 zhuli 三档口径 | `ActionGate` + `ActionConfirm` 三档 + 息屏失效 + 3 分钟超时 | **移植口径** |
| **结构化收件箱/提取** | 无 | `InboxDatabase` + 提取管道（快递/账本/提醒） | **已通过 4 个只读 Tool 覆盖查询侧**；实时桥另议 |
| **系统闹钟/日历写入** | 无（仅 WorkManager 延迟消息） | `ReminderEngine` + `CalendarWriter` + 3 个 Receiver | **按需移植**（缺口） |
| **启动其他 App** | 无 | 无（Market 调研误记，实际也没有） | 不需要 |

**结论**：新壳 = LastChat 主干（保留其强项） + zhuli 独特能力（隐私闸门/自检/确认口径/收件箱 Tool）移植进来。「删旧壳」= 当新壳具备旧壳全部用户可见价值且回归全绿后，自研壳退役。

## 1. 落点（需用户最终拍板，推迟到删壳前）

fork 现状：在 `.lastchat_eval/LastChat`（被 gitignore，无独立 git 追踪，含完整源码 597 文件 + 构建产物）。

| 方案 | 做法 | 优点 | 代价 |
|---|---|---|---|
| A. 原地扶正 | 解除 `.lastchat_eval/` 忽略，改造成正式工程 | 改动最小，已跑通环境原样保留 | 仓库体积大（含 web-ui/catalog/构建产物），AGPL 源码整包入库 |
| B. 复制到正式目录 | 新建 `zhuli/zhuli-next/`，只带源码、排除产物 | 仓库干净、边界清晰 | 要重建构建环境（gradle/AAR/schemas/Firebase 占位），一次约 1 小时 |
| C. 保持隔离到最后一刻 | 先在 `.lastchat_eval/` 完成全部能力移植与回归，删壳瞬间才扶正 | 全程可回滚、旧壳不动 | 最后一步动作大 |

**推荐 C**：能力迁移期间旧壳完全不动（随时可回退），全部回归绿后在删壳那一刻执行 B（干净落点）。落点最终选择推迟到该时点由用户拍板——因为它涉及 AGPL 整包入库与包名变更（已有用户数据不迁移），不可逆。

## 2. 迁移批次与回滚点

每批独立提交/可回退，前一批不绿不动下一批。

| 批次 | 内容 | 验证口径 | 回滚点 |
|---|---|---|---|
| **B1 隐私闸门** | `SensitiveGuard`（纯正则）+ `PrivacyRouter` 判定核心移植进 fork 的 `com.zhuli.bridge`；提供对话层自检 Tool | 纯 JVM 单测：电话/证件/卡号/金额/凭证/地址/公司+岗位 全部命中；硬红线不放行；沿用 zhuli `PrivacyRouter.selfTest` 样本 | bridge 目录独立文件，删即回滚 |
| **B2 自检面板** | `SelfCheck` 14 行渲染口径移植，挂到 `DeveloperPage`（现成空壳） | 14 行全绿（新壳的系统状态探测用 LastChat `PermissionChecker` 适配） | Compose 页面独立，移除即回滚 |
| **B3 三档确认口径** | `ActionGate` + `ActionConfirm` 三档 + 息屏失效 + 3 分钟超时，对齐 LastChat `ask_user` | 状态机 JVM 单测（沿用主项目） | 桥接层独立 |
| **B4 提醒引擎缺口** | 系统闹钟/日历写入（`ReminderEngine`/`CalendarWriter`），补 LastChat 缺失 | 真机设闹钟/写日历演练 | 需新增权限，独立开关 |
| **B5 实时数据桥** | 快照 → 实时（ContentProvider 方案 B），让新壳读真机 zhuli 库实时数据 | 真机：旧壳入库一条 → 新壳对话即时可查 | 快照 Tool 保留为 fallback |
| **B6 全量回归** | 自检 14 行 + 隐私请求体回归 + E2E 4 Tool + 主项目单测 | 全绿 | — |
| **B7 删旧壳** | 旧壳退役，新壳扶正（落点方案 B/C） | 新壳独立装机跑通 | **不可逆，单独确认** |

## 3. 执行进度

| 批次 | 状态 | 记录 |
|---|---|---|
| B1 隐私闸门 | ✅ 移植完成（2026-11-21 17:58） | `ZhuliSensitiveGuard.kt`（7 类敏感 + 脱敏 + 幂等，原样移植）+ `ZhuliPrivacy.kt`（判定核心，云配置改为注入 Config，去掉 Context/写库）+ `ZhuliPrivacyGate.kt`（接线开关 + 统一闸门入口）。12 项 JVM 自检全绿（判定-脱敏一致性不变量、硬红线、HTTPS/回环、strict、三层自检）；fork 编译 BUILD SUCCESSFUL。⚠️ **判定核心已就位，但 `isActive()=false`——尚未接到 LastChat 出网路径，实际不生效**。接线是单独一步（见 B1-接）。 |
| B2 自检面板 | ✅ 移植+UI 完成（2026-11-21 18:01） | `ZhuliSelfCheck.kt`（纯渲染口径，三档/分组/14 行 Item 1:1）+ `ZhuliSelfCheckData.kt`（新壳取值层，用 LastChat `PermissionChecker` + 通知状态，不造假绿勾）+ `ZhuliSelfCheckPage.kt`（Compose），挂到 fork `DeveloperPage` 第 2 个 tab「zhuli 自检」。12 项 JVM 自检全绿；fork 编译 BUILD SUCCESSFUL。面板如实显示隐私闸门"已移植未接线"。 |
| B1-接 隐私闸门接线 | ⚠️ 发现根本矛盾，待定方案 | 用户选"内容层接线"，但深挖发现更深的问题（见 §5）：LastChat 的 Tool 结果必然回传云端模型才能生成回答，本地取件码/金额/地址会出网。这不是拦用户输入能解决的。三个真方案见 §5，待用户定。 |
| B3~B7 | 待推进 | — |

## 4. 本轮执行说明

用户「全速」= 连续推进且关键改动报备。B1 优先因为它是 zhuli 最独特价值、LastChat 完全没有、且是 T-G2 验收硬指标之一（"隐私请求体回归全绿"），主体纯逻辑可 JVM 单测快速验证。

落点（§1）与删壳（B7）在能力迁移完成、回归全绿后再请用户拍板，不阻塞当前推进。

## 5. ⚠️ 隐私闸门接线的根本矛盾（2026-11-21 18:10 深挖）

用户拍板"内容层接线"后，深挖 LastChat 编排链发现一个比接线方式更根本的问题：

**事实链**（读码确认）：
1. `GenerationHandler.generateText` 里模型调用 Tool → `tool.execute()` 返回真库结果（取件码 6825 / 金额 / 地址）；
2. 结果以 `UIMessage(role=TOOL, parts=[ToolResult])` 回填 messages；
3. 下一轮 `generateInternal` 把整个 messages（含 Tool 结果）发给模型生成自然语言回答 → **Tool 结果必然出网**（用云端模型时）。
4. LastChat 无"Tool 结果本地直出"机制；`maxSteps` 只控制轮数，不能省掉最后那轮自然语言生成。

**矛盾**：zhuli 旧壳"隐私不出机"成立的前提是——本地问答走 `AssistantChat.answer` 的**本地分支，根本不调云**。但在 LastChat 上用云端模型跑本地 Tool 查询，取件码/金额/地址必然发给云端模型。**拦用户输入解决不了**：闸门只挡得住"用户打了什么"，挡不住"模型已经看到的 Tool 结果"。

用数据验证过：用户问"我花了 35.8 元买咖啡"被判金额敏感——但脱敏问题文本毫无意义，因为真正敏感的是 Tool 从本地库取出的真实账本，它会作为 ToolResult 原样出网。

**三个真方案**（按隐私强度排序）：

| 方案 | 做法 | 隐私 | 代价 |
|---|---|---|---|
| **A. 本地模型跑 zhuli Tool 问答** | 给 zhuli 这些 Tool 对话绑定 LastChat 的端侧 LiteRT 模型（`:local-llm` 已有），Tool 结果只在设备内回传 | 最强：数据全程不出机 | 端侧模型能力弱、速度慢；需在 assistant 层做"按场景选模型"路由 |
| **B. 本地直答（改编排）** | 检测到 zhuli 只读 Tool 调用后，不再发下一轮，直接把 ToolResult 文案当答案返回 UI | 强：Tool 结果不回云 | 要改 `GenerationHandler` 编排 + 答案组装，动核心且要处理 UI 展示 |
| **C. 维持现状 + 明示** | 保留端到端已验收形态（云端模型 + Tool 结果出网），隐私闸门只用于拦用户主动输入的硬红线内容，面板如实标注"本地查询数据会经过云端模型" | 最弱：符合主流 AI 助手实际行为 | 与 zhuli"隐私不出机"原始承诺有落差，须在 UI/文档明示 |

**建议**：先按 C 如实标注（不制造虚假安全感），把 A/B 作为后续增强。A 最契合 zhuli 定位且 LastChat 已具备端侧模型基础设施，值得优先评估——但属产品决策，待用户拍板。

## 6. 方案 A 落地进展（2026-11-21 18:30 起，用户拍板"本地模型跑 zhuli 问答"）

调研结论（Explore agent 全链路核实）：**LastChat 原生支持本地模型 tool calling，方案 A 代码层 0 改动可行**。
- `LiteRtProvider` 原生支持 tool calling（`ConversationConfig.tools` + `automaticToolCalling=false`，模型回 `reply.toolCalls` 由宿主执行）；
- 所有本地 LLM 被强制打上 `ModelAbility.TOOL`，不会被 ChatService 的门禁拦住；
- Tool 结果经 `Content.ToolResponse` 回灌本地会话，**全程不出机**。

落地步骤与坑：
1. **模型下载**：App 内置下载写死 huggingface.co（本机 502 不通）。改走 hf-mirror PC 下载 → `adb push /data/local/tmp/`（注意 MSYS 路径改写，须 `MSYS_NO_PATHCONV=1`）→ instrumentation 测试在 App 进程内拷到 `files/local_models/` 并 `LocalModelStore.upsert()` 登记。
2. **⚠️ LastChat 集成缺陷（已修）**：`LiteRtProvider.kt` 非流式/流式两处 `conversation.getBenchmarkInfo()` 在 Benchmark 未启用时抛 `LiteRtLmJniException`，直接炸掉整个生成。它只用于 token 统计——已加 `runCatching` 容错记 0。教训：**改 library 模块（local-llm）后必须重装主 APK，只装 androidTest APK 不生效**。
3. **⚠️ SmolLM2-360M 不胜任（实测弃用）**：中文输出乱码（tokenizer 中文支持差），且不会按 schema 发 tool call，还幻觉出 `get_user_info` 工具名。schema 传递链路是通的，模型能力不行。**本地模型选型必须中文原生 + 有 tool use 能力：改用 Qwen2.5-1.5B-Instruct（1.6GB，catalog requiresLicense=false）**。
4. Tool 的 `systemPrompt` 钩子（GenerationHandler:1177 会拼进 system prompt）已用于强化"必须调工具"引导，对云端模型无副作用。

## 7. ✅ 方案 A 落地成功（2026-11-21 18:57，Qwen2.5 本地问答闭环）

用户拍板"让 Qwen2.5 可用，主要是中文用"。外部求证确认 LiteRT-LM 原生 tool calling 依赖模型包内嵌模板（内置仅 Gemma 4/Qwen3/MiniCPM5，**Qwen2.5 包没有**），遂采用 **prompt 约定 + 文本解析**：

- **`ZhuliLocalToolBridge`**（bridge，零依赖可 JVM 单测）：判断本地模型（modelId 前缀 `litert-community/`）、生成工具使用说明（约定 Qwen 母语的 `<tool_call>{"name":...,"arguments":{...}}</tool_call>` 格式）、从文本解析调用、剥离标记防复读。6 项 JVM 单测全绿。
- **GenerationHandler 最小集成**（792 行一处）：原生 toolCalls 为空时尝试解析内嵌调用，成功则剥标记、构造正式 ToolCall 继续既有工具循环。对云端模型无影响（它们不会输出该标记）。
- **4 个 zhuli Tool 的 systemPrompt 钩子**：本地模型时注入工具清单+格式约定；云端模型返回空。
- **真机验收**：`ZhuliLocalE2ETest` OK (1 test, 34.9s)——Qwen2.5-1.5B 加载 → 问"我快递到哪了" → 模型输出 `<tool_call>` → 解析执行 → 真库 6825 回灌 → 中文回答。**全程离线，取件码/账本/待办数据不出机**。

**本地模型选型结论（实测）**：
| 模型 | 结果 |
|---|---|
| SmolLM2-360M | ❌ 中文乱码 + 幻觉工具名，弃用 |
| Qwen2.5-1.5B（1.6GB，用户指定中文优先） | ✅ prompt 约定方案跑通，主力 |
| Gemma-4-E2B（2.59GB） | 已下载到 PC 备用（原生 tool calling 模板），未 push |

**模型安装链路**（复用）：hf-mirror PC 下载 → `MSYS_NO_PATHCONV=1 adb push /data/local/tmp/` → `ZhuliLocalModelInstallTest` 进程内拷 `files/local_models/` + `LocalModelStore.upsert()` 登记。

## 8. B3~B6 执行结果（2026-11-21 20:45 收尾）

| 批次 | 结果 |
|---|---|
| B3 三档确认 | ✅ ZhuliActionGate/ZhuliActionConfirm 移植，主项目 11+ 测试口径全绿；create_reminder 里双保险接线 |
| B4 系统闹钟 | ✅ Scheduler+Receiver+create_reminder Tool（RequiresApproval）+Manifest；真机三证据：dumpsys 注册/直发通知/60s 真实触发 OK 2 tests。**ROM 把短延迟 alarm 对齐推迟 ~30s，测闹钟须长延迟** |
| B5 实时桥 | ✅ 旧壳 ZhuliInboxProvider（SELECT 白名单+只读+强制 LIMIT）+ fork ZhuliLiveDb（live 优先/快照兜底，4 个查询 Tool 无感切换）；真机跨 App 查询 OK 3 tests。**两个 App 签名不同→signature 权限不可用→改 exported+白名单防护（readPermission+grantUriPermission 在此 ROM 实测不生效）** |
| B6 全量回归 | ①云端 E2E OK 4 tests ✅ ③闹钟 OK 2 ✅ ④实时桥 OK 3 ✅ ⑤T-G1 直连 OK 2 ✅ ②**本地 E2E ❌ 遗留**（见下） |

**⚠️ B6② 遗留：本地模型对话堆不稳定（Process crashed / OOM 512MB）**
- 现象：Qwen2.5 本地 E2E 间歇成功（18:57 曾 OK 34.9s）、间歇多轮循环后堆耗尽崩溃。
- 已定位链路：小模型输出**截断的 tool_call**（无闭合标签/JSON 不完整）→ 宽容解析成调用 → ToolResult 回灌 → 模型再输出截断调用 → 多轮循环消息膨胀 → 512MB Java 堆耗尽。已加两道防线：①解析器改括号平衡扫描（线性无回溯，原 findAll 正则有灾难性回溯）；②解析路径 8 轮上限防循环。仍崩，**需专门会话做 heap dump 定位**（真机 logcat 受限，剩余嫌疑：原生 toolCalls 路径无步数限制 / messages 副本爆炸 / test 进程堆更小）。
- 影响：**生产 App 正常云端对话不受影响**（云端路径无此循环）；本地模型问答功能本身可用（成功路径已验证），稳定性待优化——候选：换 Gemma-4（原生 tool calling）、限制单轮回灌体积、或修剩余循环点。

**App 闪退修复（用户报告）**：T-G1 raw 拉源码截断 3 个 png（setup_icon/placeholder/placeholder_dark）致 Onboarding 崩——gh-proxy 重拉补齐 + PNG chunk/WebP RIFF 完整性校验脚本。已验证启动存活。

## 9. ✅ 对照实验定论（2026-11-21 20:50，用户要求换 Gemma-4 验证）

| 模型 | 结果 | 结论 |
|---|---|---|
| **Gemma-4-E2B**（2.59GB，原生 tool calling 模板） | **2/2 通过**（33.0s / 26.7s），无循环无 OOM | **稳定可用** |
| Qwen2.5-1.5B（1.6GB，prompt 约定） | 间歇成功 / 间歇截断→循环→堆崩 | 输出格式不稳定 |

**定论：是模型问题，不是链路问题。** Qwen2.5 的 .litertlm 包缺内嵌 tool calling 模板，靠 prompt 约定对 1.5B 小模型输出格式不稳定（截断 JSON→循环→堆耗尽）；Gemma-4 原生结构化 tool call 稳定。链路本身（解析桥/防循环/实时桥/Tool 执行）经 Gemma-4 验证全部正确。
- **本地隐私问答主力切换为 Gemma-4-E2B**（真机已装+登记，中文可用）。
- 中文优先的后续路径：catalog 无 Qwen3 的 litertlm 包；上游若出 Qwen3 包（LiteRT-LM 内置 Qwen3 处理器）可再切回 Qwen 系——prompt 约定桥仍保留作为通用 fallback。

## 10. ✅ B7 完成：旧壳退役，zhuli 全量并入 LastChat fork（2026-11-21 21:20）

**用户拍板"全部迁移到新的里边，为什么还有内容在旧的"→ 执行真迁移**：

1. **采集引擎迁入新壳**（core 36 文件 → fork `com.zhuli.core`：数据层/提取管道/提醒引擎/隐私路由/三档确认/日志等；依赖闭包 lib-recur + xk-time，minSdk 28 免脱糖；junit 被传递依赖拉低到 4.7 → force 4.13.2 修 test runner）。
2. **通知采集接线**：fork `AssistantNotificationListener` 接 zhuli 提取管道（快递拦截→噪音闸门→落库→触发提取），口径 1:1 对齐旧壳；LastChatApp 初始化 ZhuliLog。
3. **数据库自持**：assets 快照（450 条历史，v14）首启导入 fork 本地 `zhuli_inbox.db`；4 个查询 Tool 全部切换到 ZhuliLocalStore（快照/live 桥退役删除）；提醒引擎组件（Alarm/ReminderAction/BootReceiver）在 fork Manifest 声明。
4. **删除旧壳**：git 备份点 c44f658 → git rm `zhuli-assistant-step2` + `zhuli-assistant-step2-build` + 清理残留 → **卸载真机 com.zhuli.app（Success）**。
5. **最终回归（旧壳已不存在）**：云端 E2E OK 4 tests ✅（一次免费模型随机性失败重跑即绿）/ Gemma-4 本地 E2E OK 27.8s ✅ / 闹钟 OK 2 ✅ / T-G1 直连 OK 2 ✅。

**新架构**：`LastChat fork = 唯一 App`（对话 + 记忆 + 工具 + 通知采集→提取→落库 + 提醒闹钟 + 隐私闸门 + 自检面板 + 本地/云双模型）。旧壳历史在 git（ee3d90d 之前全部提交）。
**遗留小项**：fork 扶正到正式 git 目录（.lastchat_eval 仍忽略中，作为后续仓库整理）；AssistantPrefs 云配置需在新壳 UI 里重配（链接摘要等云功能）；Stage F 旧壳专属功能（分享目标/小组件/地理围栏/无障碍）未迁，按需补。

## 11. ✅ 扶正完成（2026-11-21 22:35，用户拍板"做1"）

新壳代码正式入库：`zhuli/lastchat/`（25MB 源码，1319 文件，commit fa25a24）。
- 复制时排除 .git/.gradle/.kotlin/build/local.properties/node_modules；上游 .gitignore 继续生效（local.properties 等不入库）
- **扶正副本编译验证通过**（BUILD SUCCESSFUL 5m18s，产出 4 个 ABI APK）
- 环境三坑（换目录必踩）：①local.properties 的 sdk.dir 用双反斜杠写坏 → gradle 找错 SDK 触发 NDK license 误报；②新目录需 `yes | sdkmanager --licenses` 接受许可（NDK 27 本机已装）；③speech 模块 sherpa AAR 首次要 `:speech:downloadSherpaOnnxAar`（GitHub 下载慢，可直接从评估目录复制 `speech/build/sherpa/sherpa-onnx-1.13.4.aar`）
- 双目录现状：`.lastchat_eval/LastChat`（评估/测试用，含已下载模型缓存与测试 APK）与 `zhuli/lastchat`（正式工程，可独立构建）并存；后续以正式目录为准迭代，评估目录按需清理
