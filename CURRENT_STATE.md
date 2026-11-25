# CURRENT_STATE.md — 唯一权威状态

> 本文件是项目状态的**唯一权威来源**。任何与历史文档冲突之处，以本文件 + V3.1 计划为准。
> 最后更新：2026-11-24 19:00（**v16 补丁接入完成**：DB v15→v16 / 编译 + 219 例全绿 / Z70 Ultra 真机验收通过；见下方 v16 专节）
>
> ⚠️ 下方「版本 / 设备 / 备份 / 检索基线」等段落多数属于 **旧壳 com.zhuli.app 时代**的历史记录，
> 新壳包名是 `lastchat.rikkafork.cocolal.dev`（dev 变体），数据库已升到 `zhuli_inbox.db` **v16**。
> 新壳的进度与下一步见 `NEXT_STEP.md` 与 `.workbuddy/memory/2026-11-22.md`。

## 2026-11-24 19:00 · v16 补丁接入（当前权威状态）

**分支 / 基线**：`v16-integration`，从 `master` @ `7288f2d` 切出；`changes.patch` 无冲突接入；
未 force push、未碰 master。工作区改动尚未提交（本轮结束前提交）。

**补丁作者声明未编译未测试 —— 实测确认属实**：首轮编译即在 `MemoryIndexer.notify()` 触发
JVM 签名冲突（与 `Object.notify()` 撞名），另有 `com.sun.net.httpserver` 在 JBR 21 不可解析、
`repo.active("10")` 类型错、`SqlAgentStore` 双 `LIMIT` 等一批编译/运行错误，均已逐项修掉。

**修复的真实缺陷（不是测试妥协）**：
| 模块 | 缺陷 | 修法 |
|---|---|---|
| `MemoryIndexer` | `notify()` 撞 JVM 签名；`enqueueAllActive()` 因 `CONFLICT_IGNORE` 无法重置失败项；`drainLoop()` 丢唤醒竞态；状态分不清等待/处理中/失败；失败原因被清空 | 改名 `requestIndex()`；显式 `UPDATE` attempts/error=0；`queuedAgain` CAS 补跑；`Status.stateLabel` 四态；`hadFailure` 守卫 |
| `MemoryRepo` | 失败 3 次后队列项无法被重建救回 | `enqueueAllActive()` 显式重置 attempts/error |
| `OutgoingGate` / `CloudClient` | JSON 解析失败 / 根非对象 / 缺 messages 时原样放行，聊天请求可绕过闸门 | 加 `requireMessages`；聊天强制 true，结构异常即中止出网 |
| `AgentLoop` | 副作用后抛异常记成可重试 FAILED（实为 UNKNOWN）；FAILED 无法重试；降级时本地模型看不到"已做/未做"说明 | `Attempt.threw`→UNKNOWN；FAILED 复用 executionId 先置 RUNNING；降级用 `buildMessages()` 重建并加"降级说明" |
| `AgentState` / `SqlAgentStore` / `ZhuliAgentRuntime` | 恢复执行按全局跨任务误判；`findActiveByDedupeKey()` SQL 双 `LIMIT` 语法错 | `recoverRunningExecutions(now, taskId)` 按任务隔离；`first()` 不再追加 `LIMIT 1`；进程启动全局 recover、任务内按 taskId |
| `ZhuliAgentChat` | 审批先读 pending 再 init（进程重启后 store 未初始化）；多任务待审批取最新→"允许"可能指向错任务 | 先 init 再取候选；支持 `允许/拒绝 [任务号8位]`，多任务要求显式指定 |
| 测试基建 | Robolectric 启动真实 Application 触发 Koin/Firebase 冲突；`com.sun.net.httpserver` 不可用 | 新增 `PlainTestApp` + `@Config(application=...)`；HTTP 端点改 `ServerSocket` 手写 |

**自动化验证**：`:app:compileDevDebugKotlin` 成功；`:app:testDevDebugUnitTest` **27 套件 / 219 例 /
0 失败 / 0 错误 / 0 跳过**；`assembleDevDebug` 成功（4 个 ABI）。证据日志 `v16_*.log` 在仓库根。

**Z70 Ultra 真机验收（NX733J / Android 16 / arm64-v8a / serial 324450846696）**：
- 覆盖安装 arm64-v8a debug APK `Success`（versionCode 41 不变，同签名，不清数据）；
- **DB v15→v16 升级实测通过**：`user_version=15→16`；备份 `C:\Users\a\.zhuli\backup\v15_pre_v16_2026-11-24\zhuli_inbox.db`；
  迁移前后逐行比对 779 条存量 inbox **0 丢失 / 0 篡改**（新增 4 条是真实新到短信）；
  `zhuli_vec_queue` / `zhuli_tool_exec` 两表新建；`PRAGMA integrity_check=ok`；
- 记忆页：2 条事实索引由 0→2、`hash-2gram-v1|256` 空间、状态"已同步"；点「重建索引」显示
  "已重新排队，索引在后台进行…"，队列再次排空 —— 重建闭环真机可用；
- 自检："全部正常"，采集/监听/记忆/提醒/权限/出网全绿；隐私闸门"8 条规则自检全过"、
  三层自检 证件号/验证码 判"留本地·整句未出网"、手机号/工资/地址/姓名 判"可上云（已脱敏）"；
- 实时收到圆通快递通知并在自检里可见 —— 通知/短信采集链路真机真实可用；
- 真实短信采集：inbox 779→783（迁移期间新到 4 条）；POST_NOTIFICATIONS 真机 `granted=true`；
- `logcat` 在该努比亚 ROM 上恒为空（OEM 限制，非应用故障），故以 DB 状态 + 截图为权威证据；
- 应用连续约 10 分钟多页面操作后主进程与 `:zhuli` 服务均存活、无 FATAL。

**真机未实测（如实标注，不宣称通过）**：Agent 任务端到端执行（adb 无法注入中文，未在任务页真实发起）、
云端工具调用、Gemma 语义检索（需 arm64 + 已下载模型，当前为 hash 降级空间）、
长时后台冻结（未做 24h 浸泡）。这些在 JVM/Robolectric 层已覆盖，但未在真机人工走通。

## 2026-11-24 · 采集与界面返工（新壳）

用户真机反馈：视频播放被当消息、微信收不到、自检「最近入库」空白、隐私闸门未生效、设置混在原版里、后台页找不到。

| 项 | 结论 |
|---|---|
| 最近入库空白 | `ZhuliSelfCheckData` 里 `lastAt=0L` 写死；已改为读 `InboxDatabase` 真实统计 |
| 媒体通知误收 | 旧监听无任何结构过滤；新增 `NotificationCapture`（媒体/汇总/常驻/进度/通话） |
| 微信收不到 | 旧实现只读 `EXTRA_TEXT`；现拆 `EXTRA_MESSAGES`/`BIG_TEXT`/`TEXT_LINES`，并用 `cmd notification` 模拟验证入库 |
| 隐私闸门 | 新增 `ZhuliPrivacyInterceptor` 挂到全局 OkHttp；硬红线 403、敏感脱敏、写 cloud_log；自检显示"已接线（聊天请求）" |
| 设置入口 | 新增 `ZhuliHome / ZhuliSettings / ZhuliBackground / ZhuliSources` 四个页面 + 侧边栏盾牌入口；原设置只留一个指路牌 |
| 历史噪声 | 旧版一天收了 653 条（iKuuu 397 / 酷狗 187 / B 站 39）；新增"清理白名单外历史"（需用户二次确认，未代点） |
| 单测 | 128 例全绿（新增 `NotificationCaptureTest` 9 例） |
| 真机验证 | NX733J 装 `dev 1.4.8/41`；MessagingStyle 入库、媒体/白名单/常驻拦截、自检真实数字均通过 |

**未覆盖**：微信真实新消息未实测（需真实来信）；搜索/图片/embedding 请求不在闸门内；对话主界面仍为上游原版。

## 2026-11-24 · 记忆核心与 Agent 核心（本次直接改造）

**从基线出发的真实结论**：改造前工程里没有 Agent 循环、没有任务表、没有结构化记忆；
「记忆」只有 inbox 行和 256 维字符哈希向量；`CloudClient.chatWithTools` 自己执行工具，
`Tools.run` 自己弹确认框，模型有绕过权限系统的旁路。这些都不是文档问题，是代码事实。

**已落地的代码**（未提交前为工作区改动）：

| 模块 | 文件 | 状态 |
|---|---|---|
| 数据库 v15 | `InboxDatabase.kt` | 新增 `zhuli_entity` / `zhuli_fact` / `zhuli_fact_vec` / `zhuli_agent_task` / `zhuli_agent_step`；迁移事务内建表，迁移前自动备份旧库；无存量行改写 |
| 记忆模型 | `com/zhuli/memory/MemoryTypes.kt` | 实体/事实/来源/状态/矛盾/纠错的数据结构，不依赖 Android |
| 事实抽取 | `MemoryExtractor.kt` + `ExtractPipeline.kt` | 规则式抽取取件码/金额/电话/地址/日程/待办；通知/短信入库后经提取流水线自动抽事实并带来源；低置信推断单独标记；热路径不跑模型。存量已提取行不回填（如实标注） |
| 检索排序 | `MemoryRanker.kt` | 字面 0.45 + 实体 0.20 + 时效 0.20 + 置信 0.15 + 类型意图；无相关性信号直接丢弃；矛盾检测 |
| Embedding | `MemoryEmbedding.kt` / `GemmaEmbedding.kt` | Gemma 端侧语义（arm64 + 已下载模型）与哈希字面降级；向量带 `providerId|dim` 空间标识，跨空间不比较 |
| 记忆持久化 | `MemoryRepo.kt` | 来源追溯、按槽位收敛、纠错保留历史、软删除、用户裁决矛盾、混合检索 |
| 统一网关 | `com/zhuli/agent/Gateway.kt` | `ModelProvider` / `ModelCapabilities` / `ModelRouter`；按能力路由，按错误类型降级，记账降级来源 |
| 策略与工具 | `Policy.kt` / `ZhuliAgentTools.kt` | 工具声明 Schema + 风险 + 同类授权键；只读放行、写本地问一次、对外/不可逆每次问；硬禁止词直接拒绝 |
| Agent 循环 | `AgentLoop.kt` | 目标→决策→工具→回灌→再决策；失败重试、超时/步数上限、挂起落库、审批恢复、30 分钟去重 |
| 任务持久化 | `AgentState.kt` / `SqlAgentStore.kt` | 任务与步骤落 SQLite；重启把 RUNNING 标 PAUSED 而非丢弃；步骤可审计 |
| 真实后端 | `CloudModelProvider.kt` / `LocalModelProvider.kt` / `ZhuliAgentRuntime.kt` | 云端经隐私闸门单轮调用（不执行工具）；本地复用上游 ProviderManager，明确 `tools=false` |
| UI | `ZhuliMemoryPage.kt` / `ZhuliTaskPage.kt` | 记忆查看/搜索/纠正/删除/矛盾裁决；任务步骤/审批/继续/取消；控制台两个新入口 |

**明确未做 / 不能宣称**：
- 主动助理（P3）：日程冲突、项目停滞、生活规律变化等尚未实现；
- SQLCipher（P4）：数据库仍明文，未引入新依赖；
- 文本之外的事实抽取（图片、语音）尚未接；
- 真机与模拟器：本轮只跑 JVM 单测和编译，**没有真机/模拟器验证结果**，不得标注为已验证。

## 工程根目录

**`C:\Users\a\Desktop\zhuli`**（纯 ASCII）

> 2026-11-20 20:00 从 `C:\Users\a\Desktop\zhuli'` 迁入。原因：原路径含**单引号**，导致 Gradle 测试执行器拼类路径时被截断，`testDebugUnitTest` 恒报 `ClassNotFoundException: com.zhuli.core.SensitiveGuardTest`（同样的代码在纯 ASCII 路径下 7/7 通过）。旧目录在新会话中删除，不要再用。

> **仓库形态（2026-11-24 更新）**：本地仍是权威源（`master`，HEAD `861ec74`），已加远端
> `origin = https://github.com/asd1548428584-cloud/zhuli.git`，并 **force push** 成同一提交
> （`0f2e3e2 → 861ec74`）。两条历史 SHA 仍不通，**不能 merge**；以后同步一律「本地提交 → force push」。
> 凭据走 Git Credential Manager（Windows），未落明文。冷备见 `docs/规划/02_执行记录.md`。

## 版本（新壳 · 2026-11-23 核实）

| 项 | 值 |
|---|---|
| 源码版本 | **`1.4.8` / versionCode 41**（`app/build.gradle.kts` defaultConfig） |
| 装机版本 | `dev 1.4.8 / 41`（AVD 2026-11-22 00:52；真机上次覆盖为 11-22 同版本） |
| 包名 | `lastchat.rikkafork.cocolal.dev`（dev 变体带 `.dev` 后缀；stable 无后缀） |
| minSdk / targetSdk / compileSdk | **28 / 36 / 36** |
| 数据库 | `zhuli_inbox.db` **v15**，原 8 张表 + 新增 5 张（实体 / 事实 / 事实向量 / Agent 任务 / Agent 步骤） |
| 备份格式 | v10（含 `zhuli_inbox.db` 本体；**密钥绑机，跨重装不可解**，见 B-9） |
| 模块 | `:app :shared :ai :common :search :document :workspace :local-llm :speech :tts :highlight :ui-core :iosApp :app:baselineprofile` |

> 下表为**旧壳 com.zhuli.app 时代**的历史记录，仅作考古，不要再当现状引用。

| 项 | 值 |
|---|---|
| 源码版本（旧壳） | `0.22-sources` / versionCode **22** |
| 装机版本 | **`0.22-sources` / 22**（2026-11-21 13:54 覆盖安装，真机 Z70 Ultra，签名 `38:e9:6a:b6…`） |
| 装机验收状态 | 真机已验：v12→v13→v14 迁移数据不丢（450 条）、周期旧值迁移（weekly:2→BYDAY=MO、monthly:31→BYMONTHDAY=-1）、Kotlin 2.3.21 装机正常、**A7 崩溃落盘 + 崩溃页 + 分享入口**（触发钩子 `a7_crash_test`）、自检面板进日志；**视频复验仍未做** |
| 数据库 | **v14**（`user_version=14`；v14 将 inbox/reminders 存量周期规则迁成 RFC5545） |
| 备份格式 | **v10**（全 7 表 + 保留原 id + 兼容 v9 旧包；2026-11-20 22:45） |
| 包名 | `com.zhuli.app` |
| minSdk / targetSdk / compileSdk | 24 / 35 / 35 |

## 设备

| 项 | 值 |
|---|---|
| 型号 | 努比亚 Z70 Ultra（NX733J / PQ84A01 / 代号 sun） |
| 系统 | Nebula AIOS `2.0.14MR2`，**Android 16 / API 36**（vendor 仍 Android 15 基线） |
| SoC / 内存 | 骁龙 8 至尊版 SM8750 / 约 16GB，**仅 arm64-v8a + 16KB 页** |
| GMS | **完整可用**（旧「无 GMS」结论作废） |
| 后台风险 | superkiller + cgroup_freezer_v2 + DefendManagerService + AutoLaunch（未做长测） |

## 当前阶段

- 当前阶段：**G 交互体验收尾 + 真机验收**
- 已完成：阶段 A（T-A1–A8）、阶段 B（T-B1–B7）、阶段 C（T-C1 数据层、T-C2 基线、T-C3 正名、T-C4 冲突检测真机通过）
- 阶段 G 进度：**自检面板**（一屏回答"知道什么/来源/推断/任务/权限/出网/凭什么/怎么改"）**已在真机验证 14 行全绿**；
  **提醒依据**已取得真机通知实体证据（`bigText` 含 `依据：相对时刻（N 分钟后）`）；
  **B 类复验**：App 锁 4 条路径、数据安全 5 动作、导出 CSV/JSON 真文件、设置持久化全过；
  复验中修掉 5 个问题（双弹窗 / message+items 冲突 / 导出实为文本 / text-csv 无接收方 / 设置文案打架）
- 真机新抓两个缺陷（模拟器测不出）：来源面板第三方 App 名字错、对话建提醒意图被「查待办」吃掉 —— 均已修（D-024）
- 取件码提醒口径已定（用户选「识别到取件码就自动排」，D-025），真机验证 scheduled/high
- T-C4：冲突检测 ✅ 真机；「已推迟 N 次」发现 `snooze_count` 从不累加的 bug，已修，最终观察按用户指示跳过（代码完成、真机未验）
- **剩余**：视频复验（需真实视频素材 + 转写链路）；F 漏收率数值（需真机对照）

## 提醒误报基线（T-D2，2026-11-20）

| 指标 | 清理前 | 清理后 |
|---|---|---|
| 提醒数 | 8 | **1**（真实的「分享 下午3点开会」） |
| bilibili 来源 | 7 条同刻同标题 | 0（连 inbox 记录一并清） |
| 已处置项误报率 | **85.7%（6/7）** | — |
| 该来源误报率 | **100%（6/6）** | — |

根因：NoiseGuard 未拦视频标题；去重只比 scheduled。已加护栏四 `hasCanceledSame`（同刻同标题取消过就不再重建）。

## 检索基线（T-C2，2026-11-20，真库副本 375 行）

| 指标 | LIKE（现网） | FTS trigram（候选） |
|---|---|---|
| 通用查询命中 | 13/13 | 0/13（10 个两字词不可用） |
| 空查询误报 | 0/14 | 0/14 |
| 40 个真实高频词召回 | 40/40 | 17/40（23 个两字词不可用） |
| 平均耗时 | 0.3ms | 0.1ms |

**结论**：不接 trigram FTS（中文两字查询占 23/40，trigram 要求 ≥3 字）；LIKE 保持主力。
D5 状态：数据层已修（触发器 + 失配才 rebuild + 空结果复核），当前**无用户可见影响**，列为潜在风险。
复跑命令：`python fixtures/search/eval_baseline.py zhuli_inbox_pre_uninstall.db`


## 真机采集现状（2026-11-21 11:41 拉库，F 阶段抽样原料）

| 项 | 值 |
|---|---|
| 收件 | **436 条 / 15 个来源**（B 站 170 + iKuuu 169 = **78%**，已由来源静音应对） |
| 真实即时通讯 | 微信 18 / QQ 2 / 短信通道 7（韵达·圆通·极兔，取件码自动建提醒） |
| 提醒 | 3 条（1 done / 1 fired / 1 pending 中置信取件码） |
| 账本 / 出网 | 13 笔（近 30 天 ¥8688.70）/ 26 次 |
| 进程存活 | 11-20 21:18 起连续存活 **14.4h** 未被杀（真机后台存活第一档证据） |
| 库副本 | `fixtures/realdev_20261121/`（装机前 v12 / 迁移后 v13 / 最终 db_final.db + 12 张验收截图） |

**漏收率尚无数值**：需要「系统通知实际弹出数 vs 入库数」对照，靠现有数据算不出来。
下一轮办法：真机上对微信/银行发固定测试消息（需用户配合），或对同一时段取系统通知历史对比。

## 备份（T-A1）

| 文件 | 内容 | 校验 |
|---|---|---|
| `zhuli_inbox_2026-11-20.db` | 19:10 拉取 | 360,448B / MD5 `0e80e545d34ac72ba7451d120ae5c347` / inbox **372** / v11 |
| `zhuli_inbox_pre_uninstall.db` | 19:30 卸载前补拉，**回灌用的就是这份** | 360,448B / MD5 `6b452dff01c1825664f4dcbc92e0575e` / inbox **375** |
| `zhuli_verify_restored.db` | 真机回灌后回读 | MD5 与上面一致 ✅ |
| `zhuli_secure_2026-11-20.xml` | 旧 Key 密文（**已不可解**，Keystore 随卸载销毁） | — |

当前库行数（回灌后）：inbox 375 / reminders 8 / ledger 12 / chat_log 25→（含恢复后新增）/ cloud_log 14 / skills 0 / embeddings 120。
**`inbox_fts` 不存在**（该 ROM SQLite 不支持 fts5+trigram，建表被静默吞掉）——D5 按「FTS 完全不可用」处理。

## 构建环境（T-A4）

| 项 | 值 |
|---|---|
| JDK | Android Studio 自带 **JBR 21**（`C:\Program Files\Android\Android Studio\jbr`）—— 偏离计划写的 JDK 17，用户已确认沿用 |
| Gradle | wrapper **8.11.1**，`distributionUrl` 指向 `mirrors.cloud.tencent.com`（官方源不可达） |
| Android SDK | `D:/Android/Sdk`（platforms 33/34/35/36，build-tools 30.0.3–36.0.0，NDK 27） |
| buildToolsVersion | 35.0.0（工程内钉死） |
| 签名 | `C:\Users\a\.zhuli\zhuli-release.jks`，alias `zhuli`，RSA 4096 / 10000 天，SHA-1 `38:e9:6a:b6:cf:4f:a5:aa:73:78:e8:db:9a:1e:f3:88:ec:13:a8:50` |
| 口令 | `C:\Users\a\.zhuli\keystore.pass`（仓库外） |
| 构建命令 | `./gradlew --no-daemon assembleDebug`（`JAVA_HOME` 指 JBR 21），全量约 2m25s |
| 单测命令 | `./gradlew --no-daemon testDebugUnitTest`，约 38s；**要求工程路径纯 ASCII**（含 `'` 会失败） |
| 产物 | `app/build/outputs/apk/debug/app-debug.apk`，24,124,095B，`debuggable=true` |

## 云端（D-007）

- 服务商：基元律动 `https://tokenrhythm.studio/v1`（OpenAI 兼容）
- 当前模型：**`egg-alpha-003`**（免费档；备选 `deepseek-v4.1-flash-1017`）
- Key：设备端 Keystore 密文；PC 副本 `C:\Users\a\.zhuli\cloud_api_key.txt`

## 已知问题

D1–D16 见 V2.0 计划第四部分。隐私验收结论作废（A-），阶段 B 重验。
另有实测新增：FTS 表在本机不存在（D5 的具体形态）。

## 设备授权状态（卸载重装后恢复情况）

| 授权 | 状态 |
|---|---|
| 通知使用权 | ✅ 已恢复（`cmd notification allow_listener`） |
| 短信 / 精确定位 + 粗略定位 / 录音 | ✅ 已恢复（`pm grant`） |
| 日历读写 / 通知 / 精确闹钟 | ✅ 原样保留 |
| 电池优化白名单 | ✅ 原样保留（uid 10727） |
| 无障碍服务 | ❌ 需用户在设置手动开 |
| 厂商「自启动 / 后台运行」 | ❌ 需用户在设置手动开 |

## 资料库状态

- `docs/技术资料库/10_架构与决策/ADR/0001-签名方案.md` 已建
- 目录结构（§5.2）与资料收集清单待用户确认 §11-10
- 努比亚询问函：**未起草、未提交**

## 夜间推进小结（2026-11-20 21:00–22:50）

### 阶段进度
| 阶段 | 状态 |
|---|---|
| A 工程基线 | ✅ 完成 |
| B 隐私出网 | ✅ 完成 |
| C 检索 | ✅ 完成（T-C4 已补真机验证；FTS 结论：不接） |
| D 提醒可靠性 | 🟡 T-D1/D2 完成；T-D3 十项有结论（重启项已在模拟器通过）；T-D4 大半通过；**T-D5 报告已出，围栏已修** |
| F 采集完整性 | 🟡 NoiseGuard 误杀复核（误杀 0/覆盖不足）、**来源管理（每 App 静音）已上机**、**撤权停采确证**、**通道完整性 10/10** 均完成；余：真机主流 App 抽样、群聊摘要（**用户已定：不做**） |
| E 数据安全 | 🟡 T-E1 完成、T-E3 完成、T-E2 格式部分完成；**口令派生密钥（C 类）待批准** |
| G 交互体验 | 🟡 **自检面板 / 提醒依据 / B 类复验 4 项**均已上真机（0.19）；真机补修来源名与建提醒两个缺陷；余：视频复验 |
| H 能力扩张 | ⬜ 未开始 |

### 本轮修掉的缺陷
| 缺陷 | 说明 | 验证 |
|---|---|---|
| D9 | 迁移非事务、ALTER 失败被吞 | 模拟器双路径（成功/回滚）+ 失败保护弹窗 |
| D12 | allowBackup 允许系统备份数据库 | 装机后包 flags 无 `ALLOW_BACKUP` |
| D13 | PIN 固定盐单轮 SHA-256（可秒破） | PBKDF2 + 随机盐，单测覆盖 |
| D15 | 晚响提醒被静默取消 | 晚 2 分钟 → 补响 |
| 围栏只响一次 | 触发后 disarm 且无人重挂 | 代码修复，待真机走动验证 |
| 撤销入口不可达 | `setNeutralButton` 被第二次调用覆盖 | 按钮出现 + `done→scheduled` |
| 恢复失败不清库 | 恢复前快照 + 解密前置 | 两次异常恢复数据完好 |
| 来源面板名字错 | 包可见性导致第三方 App 显示成包名尾巴 | 真机复验：bilibili/微信/QQ 正常显示（D-024） |
| 建提醒意图丢失 | 「3分钟后提醒我喝水」被当查待办 | 真机：reminders 多一条 喝水/scheduled/high（D-024） |

### 仍未验 / 待决
- B-2 努比亚询问函（草稿已写，需你提交）
- B-4 恢复失败曾伴随清库（未复现，护栏已加）
- T-D4 桌面小组件渲染、无障碍滑动能力（代码无此能力）
- T-E2 口令派生密钥 / T-E4 SQLCipher —— **C 类，等你批准**
- 真机侧：围栏走动、开机自启（模拟器已过）、后台存活长测（14.4h 一档已过，四档未做）
- ~~取件码提醒的置信度口径~~ → 已定（D-025）：识别到取件码即高置信，没码仍待确认

### 数字
~~单测 98/98~~ → ~~114 个 @Test，108 绿 / 6 红~~ → ~~2026-11-21：149 个 @Test 全绿（**旧壳口径**）~~

**2026-11-23 重新统计（新壳，命令产出）**：
- 自研 `com.zhuli` **纯 JVM 单测：0**——19 个测试文件随旧壳在 `4c3f630`（104 文件 / −17,286 行）里一起被删；新壳 `app/src/test` 目录此前不存在。
- 自研 instrumented：`app/src/androidTest` **11 文件 / 32 个 `@Test`**。
- 全仓：**79 文件 / 410 个 `@Test`**，其余全部来自上游 `ai` / `common` 等模块。
- 结论：「149 全绿」属于旧壳时代，**不得再作为当前质量证据引用**。回填进度见 `docs/规划/02_执行记录.md`。

git 累计提交 **70** 次（HEAD `1e14502`）；模拟器验证项 25+，真机验收 12 项。
**口径修正**：本文件所有数字必须由命令产出，不得手写估算；跨壳比较时必须注明是哪个壳。
