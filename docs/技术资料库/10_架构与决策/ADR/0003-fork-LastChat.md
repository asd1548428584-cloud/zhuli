# ADR-0003 — 是否 fork LastChat 作为新交互壳

- 状态：**T-G1 已跑通（真机验收 OK 2 tests）；T-G2 只读 Tool 从 1 扩到 4，且端到端真机验收 OK 4 tests（2026-11-21 17:39，免费 egg-alpha-003 在真实 GenerationHandler 里自主调用全部 4 个 Tool 并返回真库值）；fork 路线成立。用户拍板：完整 fork + 真机跑通；接受 AGPL，自用 + 未来整包开源**
- 决策日期：2026-11-21
- 关联：V3.1 计划 T-G1/T-G2、市场调研 V1.0 §2.4、复用台账

## 背景

V3.1 阶段 G 的决策点 T-G1 要在两条路里选：fork LastChat（已自带本地向量记忆、读通知、发通知、设闹钟、WebDAV 备份、QuickJS）还是保留自研壳（T-G3）。用户要求"具体代码怎么写也调查过"，并要求外部求证 AGPL 边界与仓库真实性。

## 核实结论（先取证，不凭 README）

| 项 | 取证方式 | 结论 |
|---|---|---|
| 仓库真实性 | GitHub API：`api.github.com/repos/Cocolalilal/LastChat` | 真实存在，412★ / 59 fork / 39 open issues / `pushed_at` 2026-11-18（活跃） |
| 代码质量 | 默认分支 `LastChat` 最近提交 + `AGENTS.md` | 提交信息规范、带单测与 issue 编号（如 #249 ApiKeyResolution 单测）；README 自述"Claude/Gemini 生成"但仓库有 `AGENTS.md`/`CLAUDE.md`/`.codex` 多_agent 约定，非纯玩具 |
| License | GitHub API `license` + 三方分析（hysenlabs） | **AGPL-3.0**，网络 copyleft |
| 技术栈/规模 | `AGENTS.md` + `app/build.gradle.kts` | Kotlin/Compose/Koin/Room；24 个 Gradle 模块；compile/target 36、min 28、JVM 17；KMP `:shared`；包 `me.rerere.rikkahub`（**明确禁止改名**，会破坏 Room 迁移/DataStore key）；需 npm 构建 `web-ui`；`:workspace` 内嵌 PRoot Linux |
| 我们侧 | 本地读码 | 纯 JVM `core/*` 编进单 app 模块；compileSdk 35、min 24、Kotlin 2.3.21；inbox 库 v14 / 450 条；namespace `com.zhuli.app` |

## 决策

**采用 fork LastChat 路线（走 T-G1 → T-G2）**，在隔离目录验证跑通后再决定是否并入主工程。接受 AGPL-3.0 的传染性：自用/内部分发无开源义务；**一旦对外分发或上架，整包必须以 AGPL-3.0 开源**——此条为硬约束，写进入库前合规检查清单。

## AGPL 边界（必须让所有参与者知道）

1. 自用、仅个人设备：不触发源码提供义务。
2. 把 APK 给任何人（含"给别人装"）：视为分发，须随附完整对应源码，且衍生作品整体 AGPL-3.0。
3. 通过网络提供服务（LastChat 自带 Ktor web server + React web-ui，可被远程访问）：AGPL §13 触发，须向交互用户提供源码。
4. 因此：**主工程 com.zhuli.app 保持现状不动**；fork 在 `.lastchat_eval/` 隔离验证；对外发布前先做一次合规评审（源码可获取途径、许可声明、上游 attribution）。

## 关键技术障碍：数据桥（T-G1 真正的成败点）

LastChat 与 zhuli 是两个独立应用，各自私有 SQLite（`lastchat` Room 库 vs `zhuli_inbox.db` v14）。**`me.rerere.rikkahub` 进程读不到 `com.zhuli.app` 的私有库文件**，这是注入能否跑通的决定性因素，比 Tool 契约本身更根本。

三条候选桥（按推荐度排序）：

| 方案 | 做法 | 优点 | 代价/风险 | 结论 |
|---|---|---|---|---|
| **A. 编译期并入 + 库文件随包**（推荐给 Demo） | 把 `core/VectorSearch.kt` + `InboxDatabase.kt`（只读查询所需部分）编进 fork；真机导出的 `zhuli_inbox.db` 快照放入 fork 的 assets/filesDir；注入 Tool 直接查这份快照 | 无跨 App IPC、无权限、纯本地、真机可复现 | 数据是快照非实时；`InboxDatabase` 依赖 Android Context 与 SQLiteOpenHelper，需裁剪成只读 path 版 | **T-G1 Demo 走这条**：验证"对话层能查到本地库并回答"这个决策点足够 |
| B. ContentProvider 暴露 zhuli 库 | zhuli 侧加 `exported` provider，LastChat 侧 ContentResolver 查询 | 实时、两 App 解耦 | 要改两个 App + 权限/签名 + 用户须同时装两个；合规面变大 | T-G2 若走实时路线再评估 |
| C. zhuli 侧导出 JSON/文件 + SAF 选入 | 复用 LastChat 的 `:document`/SAF 能力读取导出文件 | 改动小 | 需用户手动选文件，问答链路不自动 | 备选，不做自动问答 |

**T-G1 采用 A**：不是因为它是最终产品形态，而是因为它能以最小代价证伪/证实"注入能否跑通"这个决策点。若 A 都跑不通（Tool 契约、编排器、编译链任一环失败），则走 T-G3 保留路线。

## 注入点（代码级，来自实际读码）

1. `ai/src/main/java/me/rerere/ai/core/Tool.kt`：`Tool(name, description, parameters, systemPrompt, approvalMode, execute: suspend (JsonElement)->JsonElement)` —— Tool 契约，注入实现照此 data class。
2. `app/.../data/ai/tools/LocalTools.kt:getTools()`（约 700 行）：把本地工具汇总成 `List<Tool>`；新增 `LocalToolOption` 分支 + 一个 `Tool(...)` 即可。**这是最小改动注入点**。
3. `app/.../data/ai/GenerationHandler.kt:generateText()`（约 694 行）：`toolsInternal = buildList { buildMemoryTools(...); createSkillManagementTool(...); addAll(tools) }` —— 外部 `tools` 参数被原样加入；`getTools()` 结果从这里进入编排器。
4. 执行回填：同文件约 807 行 `toolsInternal.find { it.name == toolCall.toolName }` → `execute(args)` → `UIMessagePart.ToolResult`。注入 Tool 的返回走同一条路，无需改编排器。

## 验收口径（T-G1）

- 真机 LastChat fork：问一句"我快递到哪了"，助手调用注入 Tool，返回 zhuli 库里真实快递通知（取件码/地址/承运商），而不是编造。
- 数据取自真机导出的 `zhuli_inbox.db` 快照（已确认含 450 条，快递类通知 id 1064/1065/1066/1067/1068 等，取件码与地址齐全）。
- 跑通 → 进入 T-G2（fork 迁移：core 抽 module → 替换工具循环 → 替换记忆层 → 移植面板 → 删旧壳）。
- 跑不通 → 走 T-G3（保留自研壳，拆 MainActivity），并把失败环节记录到本 ADR。

## T-G1 进展（2026-11-21，真库验证）

### 已完成（离线可复现，12 项单测全绿）

- **仓库取证**：`git clone --depth 1 --filter=blob:none --sparse -b LastChat` 成功（HEAD `b8a2cb5`），核心源码 597 文件已拉到 `.lastchat_eval/LastChat`；注入点文件 `LocalTools.kt` / `GenerationHandler.kt` / `Tool.kt` / `InputSchema.kt` 均已本地读码确认。
- **注入桥代码**（在 `.lastchat_eval/zhuli_bridge/`，尚未并入 LastChat）：
  - `ZhuliToolSpecs.kt`：把 zhuli 的 6 个本地工具声明成 LastChat `Tool` 契约的规格层（纯 JVM），含参数归一化与必填校验；8 项单测验证规格合法性/钳制/丢多余字段/坏 JSON 不崩。
  - `CourierAnswer.kt`：快递回答纯函数层。
- **真实 450 条库验证**：从 `zhuli_inbox_pre_v14_20261121.db` 导出 8 条快递记录为 fixture，喂给 `CourierAnswer`，断言答案含真实取件码 `6825`/`18-3-8221` 与地点，不编造、不错答——4 项单测通过。

### 发现并修正的真实缺陷（T-G1 的核心价值）

- zhuli 现有 `Tools.run` 的 `list_courier` 用 `deadlineAt > now` 过滤；真库 8 条快递中有 7 条 `deadlineAt=0`（无期限）、1 条已过期。真机问"我快递到哪了"会答"眼下没有待取的快递"，而库里明明有 8 条取件码。
- **修正（已同步回主项目，2026-11-21 14:30）**：用户拍板"按你说的办"后，把同口径修进 zhuli 主项目 `core/Tools.kt`：
  - 抽出纯函数 `pickCourierRows(rows, now, windowDays=14)`（近 14 天 → 签收字样单列 → 未签收里 `deadlineAt>now` 排前、其余按到站倒序）与 `courierAnswer(pending, signed, now)`；
  - `list_courier` 分支改调这两个函数，不再用 `deadlineAt > now` 过滤；
  - 新增 `app/src/test/java/com/zhuli/core/ToolsCourierTest.kt`（6 用例：无期限/已过/签收单列/排序/14天窗口/空库），全量单测 **155/155 全绿**。
  - bridge 侧 `CourierAnswer.kt` 口径与主项目保持一致（"近 14 天 + 签收单列 + 有期限排前"）。

### T-G1 完成：真机验收通过（2026-11-21 16:50）

**决策点结论：fork LastChat + 注入本地 Tool 的方案完全跑通。** 真机 instrumentation 测试 `OK (2 tests)`：注入的 `list_courier` Tool 在真机上从 assets 快照查出真实取件码 6825，回答含"待取快递"且不错答"没有待取"。

- **完整编译成功**：`:app:assembleDevDebug` BUILD SUCCESSFUL，产出 4 个 ABI 的 APK；arm64 包 191MB 装机成功，App 启动无崩溃。注入代码 `com.zhuli.bridge.*` 与主项目 `LocalTools.getTools()` 的注入行均通过真实 Kotlin 编译。
- **数据桥最终形态**：不用裁剪 `InboxDatabase`（SQLiteOpenHelper + 私有库）。改为 `ZhuliCourierTool`（`app/src/main/java/com/zhuli/bridge/`）——把真库快照 `zhuli_inbox_pre_v14_20261121.db`（496KB，8 条快递）放进 `app/src/main/assets/zhuli_inbox_snapshot.db`，首次运行复制到 filesDir 用 `SQLiteDatabase.OPEN_READONLY` 查 `inbox` 表，`execute` 复用 `CourierAnswer` 纯函数。注入点：`LocalTools.kt:getTools()`（705 行后）加一行 `tools.add(ZhuliCourierTool(context = context).asTool())`；`LocalTools` 是 class 且有 `context` 字段，无需改构造器；唯一调用方 `ChatService.kt:1937` 无需改。
- **真机验收证据**：`app/src/androidTest/.../ZhuliCourierInstrumentedTest.kt`（2 用例）真机跑通——① `queryCourier_fromAssetsSnapshot_returnsRealPickupCodes` 断言含 6825 + "待取"；② `asTool_hasCorrectNameAndSchema` 断言注册为 `list_courier`。

#### 编译攻关记录（可复用的环境坑，详见接线 RUNBOOK.md）
1. **拉源码**：github.com 断、raw.githubusercontent.com 通（间歇）。用 `git ls-tree -r --name-only HEAD <dir>`（只需本地元数据）列路径 + `curl raw` 逐文件拉；分模块多轮快速扫描（每文件短超时、失败跳过、多轮迭代），最终 12 模块 + web-ui(151) + catalog(125) + app/res(67) + app/schemas(39) + dev/stable flavor res 全部拉齐。git sparse 失败的真因是 stale `*.lock` + 临时 pack，清锁后单进程才成功。
2. **sherpa-onnx AAR**：`:speech:downloadSherpaOnnxAar` 走 objects.githubusercontent.com 不通；改用 gh-proxy.com 镜像下载官方 AAR（47MB，SHA256 `03f9c4df...` 校验通过）。注意：不能让 gradle 任务跑（它会删已就位的 AAR 重下卡死），编译需用 `-x :speech:downloadSherpaOnnxAar`。
3. **Gradle 发行版**：`./gradlew` 卡在下 `gradle-8.14-bin.zip`（services.gradle.org 断）；直接用本地已缓存的 `gradle-8.14-all`（417MB，含 bin/gradle）绝对路径编译。
4. **SDK/toolchain/Firebase**：local.properties 指向 D 盘 SDK（含 NDK27 + 已接受 license，C 盘 AppData SDK 缺 NDK）；5 个 KMP 模块 `jvmToolchain(17)`→21（本机仅 JDK21）；造最小 `google-services.json` 占位覆盖 3 个变体包名（base/.dev/.debug）过配置阶段；`buildWebUi` 因沙盒批量删除拦截（react-router 清 build 目录 79>50）用 `-x` 跳过（npm build 产物已手工生成）。
5. **Room schemas**：sparse 没拉 `app/schemas/`（39 个 JSON），KSP 报 37/38.json 缺失，补齐后通过。

#### 验收边界（诚实说明）
- 本次验证的是**决策点**：Tool 能注入 LastChat、能在真机查到本地库、能返回真实答案。已全部通过。
- **未验证**：真实对话 UI 里模型自主调用 Tool（需云 provider key，用户未提供）。这一步是 LastChat 既有能力（编排器 `toolsInternal` 按 `tool.name` 执行，读码确认），非本次注入引入的新风险；配置 provider 后即可用。
- **数据形态**：Demo 用 assets 快照（非实时）。实时路线（方案 B ContentProvider）属 T-G2，未在本 Demo 范围。

### T-G2 进展（2026-11-21 17:10，用户："继续做吧，先不要测；用免费鸡蛋模型测"）

- **免费模型验证**：用户给了云 Key（`C:\Users\a\.zhuli\cloud_api_key.txt`，基元律动 `tokenrhythm.studio/v1`），明确只用免费 `egg-alpha-003`、不用付费。PC 直连求证：HTTP 200，且**支持 tool calling**——问"我快递到哪了"返回 `list_courier` 调用。这补上了 T-G1 验收边界里"模型端自主调用"唯一的缺口（模型侧已证；真实对话 UI 端到端仍未跑，按用户"先不要测"暂缓）。
- **只读 Tool 扩展**：把 `ZhuliToolSpecs` 规划的 6 个 Tool 里的 3 个只读 Tool 落地到 fork（`search_memory` / `list_todo` / `ledger_summary`），加上 T-G1 的 `list_courier`，现共 4 个。新增 `ZhuliQueryAnswer.kt`（纯 JVM 回答层，口径对齐主项目）/ `ZhuliSnapshotDb.kt`（共享只读快照 + 向量解码）/ `ZhuliQueryTools.kt`（查询 + Tool 装配）。注入点仍只改 `LocalTools.getTools()` 一行。
- **第二处真库口径修正**：`list_todo` 主项目查 `inbox.deadline_at > now`，真机快照里待办全在 `reminders` 表且时间已过 → 照搬会空答；改为查 `reminders` 的 `status IN ('pending','scheduled')`，标注"时间已过"与置信依据。与 T-G1 的 `list_courier` 缺陷同类——**每次照搬主项目口径到快照库，都必须用真库数据验一遍**。
- **验证边界**：10 项纯 JVM 口径自检全绿（真库 fixture `fixture_query.json`，临时编进主项目测试源跑完已清理，主工程未污染）；fork `:app:compileDevDebugKotlin` BUILD SUCCESSFUL。按用户"先不要测"：**未 assemble、未装机、未跑真机对话**。
### T-G2 端到端验收（2026-11-21 17:39，用户："跑吧，端到端跑一遍，然后继续推进"）
- **结果**：真机 `OK (4 tests)`。免费 `egg-alpha-003` 经 `-e zhuli.e2e.key` 注入（不落 APK/盘），走 LastChat 真实 `GenerationHandler.generateText`，构造内存 Settings 让模型在对话里**自主**选 Tool，逐一验证 4 个 Tool 都被调用且 ToolResult 含真库值（6825 / 6825 / 餐饮通讯 / 取件驿站）。至此 T-G1 遗留的"模型端自主调用"缺口完全闭环。
- **验收暴露并修复 3 个真问题**：① `list_todo` 的 `IFNULL(basis,'')` 无别名导致 `getColumnIndexOrThrow("basis")` 抛异常被吞 → 加 `AS basis`；② 真机 filesDir 残留 T-G1 旧快照库（缺 reminders/embeddings、大小却相同）→ `ZhuliSnapshotDb` 加"必需表校验 + 缺表重拷"自愈；③ 测试里 `MessageRole` 包路径（`me.rerere.ai.core`）。三者都只有真机跑才暴露。
- **T-G2 深水区（2026-11-21 17:45，用户拍板"全速迁移到删旧壳"）**：已放行。但两份能力盘点后做**策略修正**——原 V3.1 的"替换工具循环/记忆层"是倒退（LastChat 这两块远强于旧壳），改为"保留 LastChat 主干 + 移植 zhuli 独有能力（隐私闸门/自检/确认口径/收件箱 Tool）"。详见 [T-G2深水区迁移执行计划.md](./T-G2深水区迁移执行计划.md)。分 B1~B7 批推进，每批可回滚；删旧壳（B7）不可逆，回归全绿后单独拍板落点。

### 后续

- 若最终并入主工程：需重开 ADR 讨论 namespace 冲突（LastChat 的 `me.rerere.rikkahub` 不可改，与 zhuli 共存须靠 module 边界而非同包）。
- AGPL 合规：fork 一旦分发/上架或经其 Ktor web server 对外提供，整包须以 AGPL-3.0 开源；发布前做合规评审。
