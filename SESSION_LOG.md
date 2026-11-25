# SESSION_LOG.md

## 2026-11-20 19:05–19:30｜阶段 A / T-A1–T-A4

**执行环境**：本机 Win11 26300 / Ryzen 5 3500X / 32GB / Arc A770；JBR 21；Gradle 8.11.1（wrapper）；SDK `D:/Android/Sdk`；设备 NX733J 已连接（装机 0.16-full/16，库 v11）。

### T-A1 数据逃生备份 — 通过

- `adb exec-out run-as com.zhuli.app cat databases/zhuli_inbox.db > zhuli_inbox_2026-11-20.db`
- 360,448 字节，MD5 `0e80e545d34ac72ba7451d120ae5c347`，`integrity_check=ok`，`user_version=11`，`journal_mode=delete`（无 WAL，cat 快照安全）
- 行数：inbox 372 / reminders 8 / ledger 12 / chat_log 25 / cloud_log 12 / skills 0 / embeddings 120
- 另存 `zhuli_secure_2026-11-20.xml`（密文）
- **实测发现**：库里**不存在 `inbox_fts`**（不是空表）。该 ROM 的 SQLite 不支持 `fts5(... tokenize='trigram')`，`InboxDatabase.onCreate` 的建表被 catch 吞掉。D5 应按「FTS 完全不可用」记录，阶段 C 必须覆盖此形态。

### T-A2 版本控制 — 通过

- 解包 `zhuli.7z` 到工程根目录（`zhuli/`、`交接_2026-11-19/`、`交接_2026-11-20/`）
- `.gitignore`：build/、.gradle/、.kotlin/、*.apk、local.properties、*.db、*.7z、*.zip、*.keystore、zhuli_secure_*.xml、.workbuddy/
- 仓库级身份 `zhuli <dev@zhuli.local>`（D-001），初始提交 `c9f2c69`，111 文件，敏感文件经 `git check-ignore` 逐条确认未入库

### T-A3 签名方案 — 决策已定、演练通过（真机待执行）

- 实测：手机上 APK 证书 SHA-1 `c6:f8:70:49…`（旧机 debug），本机 debug 证书 `1d:8d:61:0d…`，无法覆盖安装
- 新 keystore `C:\Users\a\.zhuli\zhuli-release.jks`（RSA 4096 / 10000 天 / alias `zhuli` / SHA-1 `38:e9:6a:b6…`），口令在仓外 `keystore.pass`
- 演练（AVD `zhuli_drill`）：旧包装入 → 新包覆盖报 `INSTALL_FAILED_UPDATE_INCOMPATIBLE` → 卸载 → 新包装入 → 首启建库 → `run-as` 回灌 → 回读 MD5 一致 → 启动后界面显示「收件 372 · 待办 7 · 提醒 1」
- 详见 `docs/技术资料库/10_架构与决策/ADR/0001-签名方案.md`

### T-A4 构建复现 — 通过（有偏离，见下）

- `local.properties`：`sdk.dir=D:/Android/Sdk`（原为旧机 `D:\zhuli-dev\android-sdk`），新增 `zhuli.keystore` / `zhuli.passFile`
- `app/build.gradle.kts`：新增 `signingConfigs.zhuliRelease` 并绑到 debug 构建（保留 debuggable，回灌需要）
- `gradle wrapper` 8.11.1：官方 `services.gradle.org` 不可达（SSL 中断），改用 `mirrors.cloud.tencent.com`；wrapper 自身下载仍得到损坏包，最终用 curl 完整下载 136,920,070 字节并放入 dists 目录解决
- 结果：`./gradlew assembleDebug` BUILD SUCCESSFUL（2m25s），产出 24,124,095 字节，签名 `38:e9:6a…`，`debuggable=true`

### 偏离与待确认

1. **JDK 21 代替计划要求的 JDK 17**：本机无独立 JDK 17，用 Android Studio 自带 JBR 21 构建通过（2026-11-19 也是这条路）。是否补装 JDK 17 待用户定。
2. **Gradle 分发包改用腾讯云镜像**：计划只要求「wrapper 8.11.1」，未规定分发源。
3. 真机卸载重装尚未执行，等用户批准。

### T-A3 真机执行（2026-11-20 19:30，用户批准）

- 卸载前补拉最新库：inbox **375**（19:10 时 372），MD5 `6b452dff01c1825664f4dcbc92e0575e`，ver 11，integrity ok → 存 `zhuli_inbox_pre_uninstall.db`
- `adb uninstall` → `adb install app-debug.apk`（新签名 `38:e9:6a…`，0.18-video/18）→ 首启建库 → `run-as` 回灌 → 回读 MD5 一致
- 启动后界面显示「收件 375 · 待办 7 · 提醒 1」，数据完整
- 授权恢复（用户批准脚本化）：补回 READ_SMS / ACCESS_FINE_LOCATION / ACCESS_COARSE_LOCATION / RECORD_AUDIO；`cmd notification allow_listener` 挂上通知监听（`dumpsys activity services` 已见系统绑定）；电池白名单原样保留（uid 10727）
- **未恢复/需手动**：无障碍服务、厂商自启动开关； **不可恢复**：云端 API Key（Keystore 密钥随卸载销毁）、zhuli_prefs.xml 里的设置（用户选择按计划原文不顺带备份）

### 云端通道恢复（2026-11-20 19:39–19:43）

- 用户提供新 Key，实测 `/v1/models` 返回 29 个模型；两个 0 价档中 `neohorse-1.5-flash-preview` 调用报 `permission_error`，`egg-alpha-003` 可用
- 直连对比：`egg-alpha-003` 3/3 成功、均 1737ms；`deepseek-v4.1-flash-1017` 3/3、均 1212ms；两者 tools 均 OK
- 按用户指示「先试免费」：设备当前模型 = `egg-alpha-003`，Base `https://tokenrhythm.studio/v1`，Key 经 `--es cloudkey` 写入（落盘为 Keystore 密文，无明文）
- 端到端验证：`cloud_log` 由 12 增至 14，新增两条 `model=egg-alpha-003 / ok=1 / note=tools`；`chat_log` 里对应回复为模型生成（冷笑话、生日祝福）
- 踩坑记录：① `--es q` 与 `cloud*` 不能同一条 am start（onCreate 先处理 q）；② 问句含「做什么/今天/待办/找一下」等会被本地规则分支截走、不出网，验证云端时必须用干净问句
- Key 副本存 `C:\Users\a\.zhuli\cloud_api_key.txt`（仓库外）

### 阶段 B / T-B1 修 D3（2026-11-20 19:50–20:05）

- `SensitiveGuard.redact()` 补两处：`ADDRESS` 命中即替换为 `[地址]`；`ORG`+`ROLE` 用**组合判定**（同现才替换为 `[公司]`/`[岗位]`，避免「我公司楼下」被误伤）
- 新增 `app/src/test/java/com/zhuli/core/SensitiveGuardTest.kt`，7 个用例：分类判定、判定-脱敏一致性（不变量：`reason(x)` 命中 ⇒ `reason(redact(x))` 必须为 null）、原文片段不残留、非敏感不改写、组合判定防误伤、幂等、红线可识别
- `testImplementation junit:junit:4.13.2`（D-008 已批准）
- **踩坑**：工程路径 `Desktop\zhuli'` 里的单引号会让 Gradle 测试执行器的类路径被截断，报 `ClassNotFoundException`（类路径里明明有该类）；把同样的源码复制到 `D:\zhuli_t` 跑，7/7 通过 → 定位为路径问题
- 处置：工作区复制到 **`C:\Users\a\Desktop\zhuli`**（纯 ASCII），旧目录因应用进程占用暂无法改名/删除，待新会话清理
- 结论：T-B1 代码完成，单测 7/7 绿（在新路径）；**修复尚未装到手机**（装机需批准）

### 阶段 B / T-B2 T-B3 T-B4 T-B5（2026-11-20 19:58–20:01）

| 任务 | 改动 | 要点 |
|---|---|---|
| T-B2 修 D2 | `CloudClient.chat()` 新增 `outgoing = verdict.text.ifBlank { user }`，组包改用 outgoing | 原来判定层脱敏完，组包又用 `json(user)`，脱敏等于白做 |
| T-B3 修 D1 | `chatWithTools()` 对 system / 历史 / 工具返回逐条 `gateText()`；`post()` 与 `postJson()` 出网前统一 `lastGate()` 复核请求体；新增 `PrivacyBlockedException`、`PrivacyRouter.hardRedLine()` | 策略按 D-011 a+b：可脱敏的脱敏照发，红线（证件/卡号/凭证）中止整轮回落本地 |
| T-B5 修 D6 | `SecureKeyStore.put()` 改 fail-closed：加密失败**不写明文**、打 `api_key_enc_failed` 标记、返回 false；`get()` 去掉明文兜底；`status()` 显示「已拒绝保存」 | 按 D-010 |
| T-B4 修 D4 | 语音上云改**一次性许可**：`armVoiceCloudOnce()` / `voiceCloudArmed()` / `consumeVoiceCloudConfirm()`；`CloudClient.transcribe()` 无券不发并留档；`ShareReceiverActivity`、`VideoNote`、设置面板同步改 | 分享链路无界面，弹不出「这次发不发」，故把确认前置到用户点开关那一刻，用完自动关（D-009） |

- 单测仍 7/7；`assembleDebug` 通过（20:01，24,124,095B）
- **未装机**：手机上跑的还是修复前的 0.18
- 下一步 T-B6：重建 `mock_openai.py` + `adb reverse`，用敏感样本集断言「请求体里零敏感原文、红线零请求、cloud_log 与实际一致」

### 阶段 B / T-B6 假端点全请求体测试（2026-11-20 20:03–20:12）— 全部通过

- 新增 `fixtures/privacy/mock_openai.py`（记录完整 body；带 `__tool__` 时返回 tool_calls）与 `run_privacy_test.py`（5 个样本 + 断言）
- 设备指向 `http://127.0.0.1:8799/v1`（回环明文，`isEndpointSafe` 允许），`adb reverse tcp:8799`
- 结果：**5/5 PASS**
  | 样本 | 请求数 | 断言 |
  |---|---|---|
  | 非敏感闲聊 | 1 | 正常出网 |
  | 地址脱敏 | 1 | 请求体为「讲个冷笑话吧，[地址] [地址] [地址]」，无街道/门牌 |
  | 手机号脱敏 | 1 | 「我手机号 [手机号]」 |
  | 红线拦截 | **0** | 证件号整轮未发（本地兜底） |
  | 工具返回回灌 | 2 | 第二轮带 role=tool，回灌内容无敏感原文 |
- **实测发现两处问题**：
  1. 测试脚本 `adb shell` 会被设备端 sh 再解析一次，句子里的空格把 `--es q` 切断了（身份证号没送到 App，红线样本假通过）→ 改为整串加引号；
  2. **真实缺陷**：`ADDRESS` 正则不容忍空格，`建国路 88 号 3 号楼` 只吃掉「朝阳区建国路」→ 已把数字两侧改为 `\s*`，并补单测（现共 8 个用例）
- 跑完已把云配置改回 `https://tokenrhythm.studio/v1` + `egg-alpha-003`，并撤掉 `adb reverse`
- `requests.jsonl` 含真实工具返回，已加 `.gitignore`
- 当前手机 = **修复版 0.18**（20:02 与 20:07 两次覆盖安装，数据与授权未丢）

### 阶段 B / T-B7 三层自检面板（2026-11-20 20:12–20:14）— 通过

- `SensitiveGuard.hits()`：抽出命中的**原始片段**，供"请求体里还在不在"的硬比对（不靠再判一次，避免循环论证）
- `PrivacyRouter.selfTest3()`：每行给三层结果 —— ①判定 ②实际发出内容 ③模拟请求体残留检查
- 面板改为三层展示；真机截图确认 **8 条样本全 ✅**，结论「8 条样本三层全过」，云通道显示「已配置（加密存储 · 尾号 PaXU）」
- 面板第一版自己误报：占位符 `[公司]`/`[岗位]` 里含"公司/岗位"两字，`hits()` 比对时命中 → 扫描前先剔除 `[...]` 占位符后修复
- 出网留档抽查：`cloud_log` 34 条中，mock 期间 7 条与 5 次实际请求一一对应（host `127.0.0.1`、model `mock-1`、note `tools`）；红线样本 0 请求且无留档，一致
- 单测 10/10（新增 2 条：片段逐条消失、hits 对非敏感为空）

### 阶段 B 验收结论（2026-11-20）

| 验收项 | 结果 |
|---|---|
| T-B6 假端点全请求体测试 | **5/5 通过**（脚本 `fixtures/privacy/`，可重复执行） |
| v0.15「敏感脱敏后上云」结论（原 A-） | **重新签发**：请求体级证据成立，D1/D2/D3 关闭 |
| 出网留档与请求一致 | 抽查一致 |
| 单测 | 10/10 |

遗留：手机 `chat_log` 里留有 T-B6 的 5 条测试对话（"讲个冷笑话吧…"），属真实库里的测试残留，待用户决定是否清理。

### 阶段 C / T-C1 修 D5 + T-C2 基线（2026-11-20 20:18–20:27）

**T-C1 数据层修复**
- `InboxDatabase.onOpen`（进程内只跑一次）→ `ensureFtsSync`：建 `inbox_ai/ad/au` 三个触发器（外部内容表不会自动同步，官方要求自己做）；
  用 `integrity-check`（rank=1 比对内容表）判断是否失配，**只在失配时** `rebuild`（这就是幂等保护）
- `InboxSearch`：FTS 返回空且库非空 → 用 LIKE 复核（防「索引没同步导致假空」）
- **PC 侧双形态验证** `fixtures/search/fts_dual_test.py`（真库副本）：形态 A 8 项全过（含「绕过触发器插一行 → integrity-check 必须报错」）；形态 B 3 项全过（`no such table` 触发兜底）

**核代码时发现一个和计划假设不符的事实**
- `InboxSearch.search`（唯一用 FTS 的地方）**全工程零调用方**，是死代码；真正在跑的是 `VectorSearch`（哈希向量，最近 400 条）→ 失败再 `betweenKeyword`（带时间窗的 LIKE）
- 所以 D5「FTS 索引恒空」当前**对用户没有可见影响**；本机 ROM 更建不出 trigram FTS 表

**T-C2 基线（`fixtures/search/eval_baseline.py`，真库副本，可重复跑）**

| 指标 | LIKE（现网路径） | FTS trigram（候选） |
|---|---|---|
| 应找到命中 | **13/13** | 0/13（其中 10 个两字词根本不可用） |
| 空查询误报 | 0/14 | 0/14 |
| 平均耗时 | 0.3ms | 0.1ms |
| 自动抽样 40 个真实高频词 | **40/40** | 17/40（23 个两字词全部不可用） |

关键词长度分布：40 个高频词里 **23 个是两字词**。trigram 分词器要求查询 ≥3 字符，
**中文两字词（快递/取件/工资…）直接失效** —— 这是「接不接 FTS」的决定性数据。

**结论**：不把 trigram FTS 接进检索链路；LIKE 保持主力，FTS 只作为「≥3 字词」的增量通道留待需要时再加。
真语义/更好召回留给阶段 H 的 Embedding 方案（用同一套评测脚本对比）。

**T-C3 正名核查**：`VectorSearch` 注释已明确「不是语义模型」；界面文案无「向量/语义」表述；`ModelStore` 那句是描述未来模型，不算误导。

### 阶段 C / T-C4 提醒项复验（2026-11-20 20:26–20:41，部分通过）

**冲突检测 — 真机通过**
- 让模型走 `create_reminder` 工具建了两条提醒（20:27:11 / 20:31:39，相差 4 分钟）
- 第二条的 reminder body 里带 `⚠️ 与「喝水」只差 4 分钟`；20:31:39 准点弹出，通知正文确实是这句
- 第一条 `when=1795177631000` 与 due_at 完全一致 → 准点（0 秒偏差）再次得证

**发现两个真 bug（已修）**
1. `ReminderEngine.snooze` 只调 `rescheduleReminder(dueAt)`，**从不累加 `snooze_count`** →
   `ReminderNotifier` 里那句「已推迟 N 次，要不要换个时间？」永远不会出现，是死功能；
2. `rescheduleReminder` 只清 `fired`、**不清 `notified`** → 重排后的通知会被合批器当成"已发过"而跳过。
   修法：`rescheduleReminder(id, dueAt, countSnooze)` 同时清 `notified`，`countSnooze=true` 时 `snooze_count+1`。
- 单测 10/10，装机 20:36

**未完成（用户指示跳过）**：点两次「稍后」后第三次通知标题带「已推迟 2 次」的最终观察。
代码路径已修，但不是真机证据 —— 记为「代码完成、真机未验」。

**⚠️ 更正（2026-11-20 21:20）**：先前判断「shortcuts.xml 零引用、快捷指令点了没反应」是**错的**。
`MainActivity:222-227` 确有 `when (intent?.action)` 处理三个动作；我当时的 grep 只搜了 `shortcut|memo` 等关键词，
动作串写在 when 分支里所以漏了。真机实测：`COURIER` 打开快递面板、`BRIEF` 打开今日简报，均正常。
教训记下：判断"死代码"必须按符号全量搜，不能只搜文件名/关键词。

### 测试数据清理（2026-11-20 20:46–20:52，用户批准）

- 清理前备份：`zhuli_inbox_pre_cleanup.db`（487KB，integrity ok）
- 删除：测试提醒 2 条（喝水 335/336）、对应 inbox 2 条、测试对话 40 余行、mock 出网留档 15 条
- 保留：用户真实提醒 8 条、真实出网留档 19 条、其余历史对话
- 回写后设备校验：`inbox 378 / reminders 8 / chat_log 23 / cloud_log 19 / user_version 11`，界面显示「收件 378 · 待办 7 · 提醒 1」
- 提醒：删掉 reminder 行后，20:42 那次的系统闹钟到点会查不到行，不再弹通知（`ReminderEngine.onAlarm` 取不到即返回）

### 阶段 D / T-D1 置信度三级（2026-11-20 20:55–21:05）

- 新增 `core/ReminderConfidence.kt`（纯逻辑）：高 = 明确日期+时刻 / 相对时刻；中 = 含糊词（约/左右/下周/月底前）、只有日期、只有时刻；低 = 时间不在未来
- 库 **v11→v12**：`reminders` 加 `confidence`、`basis` 两列；老行一律 `high`（已排过闹钟、用户见过，不能升级后被降级）
- `ReminderEngine.scan`：低置信跳过；中置信写入 `status=pending`，**不排闹钟、不写日历**；高置信走原链路
- 同时挡住一条会反复重建的路径：`scan` 对已存在的 pending 提醒不再 `schedule`
- 新增 `ReminderEngine.confirm()`：待确认 → 排闹钟 + 写日历
- 主界面新增「待确认」按钮：列出 pending（时间｜标题），点单条或「全部确认」转正
- 单测新增 `ReminderConfidenceTest` 6 例（含「一个小时后」这种带量词的说法，第一版正则漏了，测试抓到后已修）
- 单测合计 **16/16**；装机 21:00，实测迁移成功（`user_version=12`，8 条老提醒保留 `high`）

**验证边界（如实标注）**：中置信→待确认的端到端路径**未在真机跑到**。
原因是模型调 `create_reminder` 时总会把 when 落成具体时刻（实测「明天叫我交材料」→ `明天上午9点` → 判 high，这是正确判定）。
中置信实际作用于**通知/消息原文**（「明天左右给你答复」这类），本机无法可靠构造。
结论：规则层有 6 个单测；扫描接线代码完成；真机端到端待有真实含糊消息时自然覆盖。

### 阶段 D / T-D2 存量误报回顾（2026-11-20 21:03–21:08）

**清理前基线（8 条提醒）**

| 指标 | 数值 |
|---|---|
| 来源 | bilibili 通知 7 条／zhuli 分享 1 条 |
| 用户处置 | 取消 6 · 完成 1 · 仍待响 1 |
| 已处置项误报率 | **6/7 = 85.7%** |
| bilibili 来源误报率 | **6/6 = 100%** |
| 重复率 | 7 条同刻同标题 → **6/7 = 85.7% 重复** |

**根因**
1. `NoiseGuard` 没拦住视频标题（「“小刻这里有好吃的蜜饼」被当成事件并解析出 19:30）；
2. 去重只比 `scheduled`：取消一条后同类通知再进来又会新建 → 刷出 7 条。

**处置**
- 清理 7 条 bilibili 误报及其 7 条 inbox 记录（备份 `zhuli_inbox_pre_fpcleanup.db`），保留真实的「分享 下午3点开会」
- 清理后：`reminders 1 / inbox 371`，integrity ok；App 重开扫描**未重建**任何一条
- 新增护栏四 `InboxDatabase.hasCanceledSame(dueAt, title)`：同刻同标题曾被取消 → 不再重建（永久生效，不做时间窗）

**待办**：NoiseGuard 对「视频/音乐类 App 的标题含时间词」仍需规则，已记入阶段 F 清单。

### 阶段 D / T-D3 首轮专项（2026-11-20 21:09–21:25）

产出：`docs/技术资料库/01_设备与系统/T-D3_实测报告_2026-11-20.md`

| 结果 | 项目 |
|---|---|
| 通过 | #3 闹钟误差（+12ms / +19ms 两样本）、#11 16KB/64 位（so 段对齐 0x4000） |
| 有条件通过 | #5 无障碍（绑定成功、能收窗口事件，点击未测，测完已恢复关闭）、#6 通知完整性（抽检无漏收） |
| 未测试 | #1 后台存活长测（基线已采）、#2 后台定位（需走动） |
| 待执行 | #4 重启恢复（等时间窗） |
| 不适用 | #7 Health Connect / #8 MediaProjection / #9 FGS / #10 FCM（代码与 Manifest 均未实现） |
| 阻塞 | #12 厂商 SDK（询问函未发） |

期间又抓到一个判定缺口并修复：`关灯（11-20 21:17）` 里的数字日期不被识别 → 误判成「只有时刻」进了待确认。
已给 `DATE` 正则补 `2026-11-20` / `11-20` / `11/22` 三种写法，加单测（现 **17/17**）。

另纠正设备报告一处：`pagesize.max=16384` 但 `getconf PAGE_SIZE=4096`，本机当前是 4KB 页。


### 阶段 D / T-D4 B 类提醒项复验（2026-11-20 21:17–21:25）

| 项目 | 结论 | 证据 |
|---|---|---|
| 周期顺延 | **逻辑通过**（6 个单测），真机未观察 | 新增 `RepeatTest`：daily 保留时分、weekly 推到目标星期、monthly 31 号夹到 2 月 28、firstAfter 跳过已过时刻、label 文案 |
| 今日简报 | ✅ 真机通过 | `am start -a com.zhuli.action.BRIEF` 打开简报：收到 100 条（其他 96/金融 2/生活 2）、账本近七天 ¥619.20 |
| 长按快捷指令 | ✅ 真机通过 | `COURIER` → 快递面板（列出 7 条取件码）；`BRIEF` → 今日简报；`QUICK_NOTE` → 输入框聚焦（代码路径） |
| 快递识别 | ✅ 顺带验证 | 面板显示 7 条真实取件码（韵达/圆通/极兔/快递），来源为短信 |
| 勿扰时段 | 未验证 | 代码在 `ReminderNotifier.inQuietHours`，需 23:00–07:00 真机观察 |
| 撤回（撤销完成） | 未验证 | 需在通知栏点「完成」后找撤销入口 |
| 断网补跑 | 未验证 | `LinkExtractor.retryFailedAsync` 在 onCreate 调用（有调用点），未实测断网场景 |
| 桌面小组件 | 未验证 | `BriefWidget.refresh` 在 onCreate 调用；桌面添加组件未测 |

单测合计 **23/23**（SensitiveGuard 10 + Repeat 6 + ReminderConfidence 7）。


### 阶段 C / T-C2 评测集补齐（2026-11-20 21:23–21:26）

- 新增 `fixtures/search/eval_set.json`：40 条通用查询，分 4 类（精确事实 15 / 多词组合 8 / 多条汇总 5 / 不存在 12），不含用户原文
- 新增 `fixtures/search/eval_runner.py`：真值运行时按库内容判定，只评测"库里确有对应内容"的条目
- 结果（库 380 行，22 条有真值）：

| 类别 | 条数 | 整串 LIKE | 分词 AND | 关键词打分（VectorSearch 路径） |
|---|---|---|---|---|
| 精确事实 | 7 | 7/7 | 7/7 | 7/7 |
| 多词组合 | 2 | **0/2** | 2/2 | **2/2** |
| 多条汇总 | 1 | 1/1 | 1/1 | 1/1 |
| 不存在信息 | 12 | 12/12 | 12/12 | 12/12 |

**结论**：
1. 多词查询在**整串 LIKE** 下 100% 失败（`"快递 取件"` 被当成一个子串去匹配），但实际链路先走 `VectorSearch.keywordScore`（按空白切分算比例），所以用户侧仍能命中 —— 这是"实现看着糙、实际被上游兜住"的典型；
2. 「不存在信息」12/12 正确返回 0 条，检索层没有编造风险（编造风险在模型组织回答那层，属 B 阶段已覆盖）；
3. 该评测集可用于回归：以后换 Embedding / 改检索策略，跑同一脚本对比。

### 下一步

长测（#1/#2）用户决定推迟到夜间息屏再做。等重启时间窗 → 执行 #4；剩余未验证项随真实使用自然覆盖。










### 阶段 E / T-E1 迁移事务化 —— 模拟器双路径验证通过（2026-11-20 21:40–21:43）

环境：雷电模拟器 `emulator-5554`（Android 14 / x86_64 / arm64 转译），装机 0.18-video（含事务迁移改动）

| 路径 | 做法 | 结果 |
|---|---|---|
| A 正常升级 | 构造 v10 库（含 3 条收件 + 1 条提醒，缺 snooze_count/confidence/basis，无 embeddings）推进设备，冷启动 | ✅ `user_version` 10→12，三列齐备，embeddings 建出，**数据 3+1 行完好** |
| B 迁移失败 | 把 `reminders` 换成同名**视图**（ALTER 必然失败），同样推进设备冷启动 | ✅ 日志「alter reminders.snooze_count failed — 整个迁移将回滚」「迁移失败，已回滚（库仍为 v10）」；库停在 **v10**、无半套字段、3 行数据完好 |

**顺带抓到的关键对照**：第一次测 B 时用的是旧包（未重装），日志显示 `alter ... skipped` + `user_version` 仍被推到 12——
这正是 D9 的原始症状："改了版本号、字段没加全，App 带着半套结构继续跑"。换成新包后行为才正确。

**另一个发现**：模拟器（Android 14）的 SQLite **同样不支持** `fts5(... tokenize='trigram')`，
日志 `no inbox_fts on this ROM — LIKE-only mode` 证实降级分支正确；意味着 D5 的 FTS 路径在任何现有环境都验不了。

单测 23/23；构建通过。

### 阶段 C / T-C4 收尾 + 阶段 D / T-D5（2026-11-20 21:48–21:54）

**T-C4「已推迟 N 次」——补齐**
- 把文案拼装抽成纯函数 `ReminderText`（`snoozeSuffix` / `title` / `conflictLine`），`ReminderNotifier` 与 `ReminderEngine` 改为调用它
- 新增 `ReminderTextTest` 5 例（含"推迟一次不啰嗦、两次才劝换时间、补响前缀不吞后缀、冲突提示只留 12 字"），单测 **28/28**
- **模拟器真机验证**：
  - 真实点击通知「稍后10分钟」→ `snooze_count` 0→1、`due_at` +10 分钟（说明计数修复生效）
  - 以 `snooze_count=2` 触发到点 → 通知标题实际渲染为 `⏰ 补个提醒｜稍后测试（已推迟 2 次，要不要换个时间？）`
- 说明：第 2 次真实点击没做成（改时钟触发了下面的新缺陷），渲染部分用 `snooze_count=2` 直接构造验证，已如实标注

**新缺陷 D15：晚响的提醒被静默取消**
- 现象：把模拟器时钟前拨 78 秒越过 due 后，`TIME_SET` 广播触发 `ensureAlarms`，该提醒直接变 `canceled`
- 根因：`ReminderEngine.ensureAlarmsWith` 对「超过 60 秒没响」的提醒直接 `setReminderStatus(CANCELED)`；
  而 `sweep` 的设计是「1 小时内补响」。**两条规则互相打架**，且 `BootReceiver` 先调 ensureAlarms，导致 sweep 的补响分支基本不可达
- 影响：关机/重启/长时间冻结期间到点的提醒，可能既不补响也不保留，用户只看到"提醒没了"

**T-D5 复验（并行 Agent 只读分析）**
产出 `docs/技术资料库/01_设备与系统/T-D5_围栏与无障碍复验_2026-11-20.md`，核心结论：
- 地理围栏：`addProximityAlert` 触发后无「清旧+重登记」路径，出围栏即永久失效；超时参数让 24h 后自动过期
- 无障碍：只有 `FLAG_REQUEST_FILTER_KEY_EVENTS`，**不具备滑动能力**；窗口缓存 3 秒未刷新即失效；服务断开无自动重连
- 已给出降级方案（通知操作/分享入口/Intent 唤起）与待真机清单

### 阶段 E / T-E1 完成 + D15 修复（2026-11-20 21:55–22:00，模拟器）

**T-E1 六个子项全部落地**

| 子项 | 实现 | 验证 |
|---|---|---|
| 迁移事务化 | `onUpgrade` 整体 begin/endTransaction，失败即回滚 | v10→v12 成功；注入失败回滚到 v10 |
| ALTER 失败不吞异常 | `addColumnIfMissing` 失败即抛（原来只打日志） | 日志「alter ... failed — 整个迁移将回滚」 |
| 迁移前自动备份 | `backupBeforeMigration` 拷到 `databases/pre_migration/`，只留最近 3 份 | 实测生成 `v10_20261120_215710.db`（88KB） |
| 失败保护模式 | 迁移失败不抛到界面：`onCreate` 先探库，失败设 `dbBlocked`、弹窗说明、`onResume` 直接返回 | 截图确认弹窗「数据库升级没成功 / 已整体回滚 / 原因 v10→v12: Cannot add a column to a view」；`FATAL EXCEPTION` 计数 0 |
| v9 分支顺序 | `< 9` 段从 `< 12` 之后挪到 `< 10` 之前 | 分支顺序现为 9→10→11→12 |
| 双路径演练 | 见上 | 通过 |

**D15 修复并验证**
- 改 `ensureAlarmsWith`：晚于 due 60 秒的提醒**不再取消**，只跳过重排，交给 `sweep` 补响（1 小时内补一条、超 1 小时才归档）
- 实测：造一条「晚 2 分钟」的提醒 → 冷启动后通知显示 `⏰ 补个提醒｜晚响测试`，库状态 `fired`/`notified=1`（修复前是 `canceled`，等于凭空消失）

单测 28/28；构建通过；模拟器装机验证。

### 阶段 E / T-E3 + 阶段 D / T-D3 #4（2026-11-20 22:06–22:16，模拟器）

**T-E3 修 D12 / D13**
- D12：Manifest `allowBackup="false"` + `dataExtractionRules`（cloud-backup / device-transfer 全部 exclude databases/sharedpref/file/external）；装机后 `dumpsys package` 的 flags 里已无 `ALLOW_BACKUP`
- D13：`Backup.hashPin` 由「固定盐 `zhuli:` + 单轮 SHA-256」改为 **PBKDF2-HMAC-SHA256 + 16 字节随机盐 + 12 万次迭代**，格式自描述 `pbkdf2:{iter}:{salt}:{hash}`
  - 兼容：`verifyPin` 自动识别新旧格式；旧哈希验通过后由 `AppLock.verify` 静默升级为新格式，用户无感
  - 定长比较（xor 累加）避免前缀早退泄露
  - Base64 走 JDK 实现、API 24/25 回落 `android.util`，这样逻辑能在 JVM 里被测
- 单测新增 `PinHashTest` 4 例（随机盐、正确/错误 PIN、旧格式兼容与升级标记、损坏哈希不崩），合计 **32/32**

**T-D3 #4 重启恢复（模拟器）——通过**
- 布置：一条 `scheduled`（due now+210s）+ 一条 `pending`（中置信）
- `adb reboot` 后日志：`reschedule on android.intent.action.BOOT_COMPLETED` → `alarmClock id=3 at=1795183940199`（按原时刻重建）
- 22:12:21 到点：`flush 1 reminders` → 通知 `⏰ 重启待响`；库状态 `fired/notified=1`
- **待确认那条仍为 `pending`**，没有被 scan 误排成闹钟（T-D1 的守卫在重启路径上也成立）

### 阶段 D / T-D4 + 阶段 C 收尾 + T-D5 修复（2026-11-20 22:18–22:22，模拟器）

**勿扰时段（T-D4）——通过**
- 把模拟器时钟拨到 23:30，触发一条提醒：通知属性 `vibrate=null sound=null defaults=0x0`（静默）
- 拨回 22:00 再触发：`vibrate=default sound=default defaults=0xffffffff`（有声音）
- 同一套代码、同一台机器，只差时间 → 勿扰判定（23:00–07:00）确认生效

**地理围栏「只响一次」（T-D5 缺口）——代码修复**
- 原逻辑：触发后 `disarm()`，注释写"下次由上层重新 arm"，但全工程只有 `MainActivity.onResume` 一处调用 →
  在家触发过一次后，上班路过公司、下班回家都不会再响
- 改为：触发后无条件 `arm()` 重挂（"一天一次"由 `markNotifiedOnce` 保证，不靠拆围栏）
- 状态：**代码完成、未运行验证**（需要真实移动位置，模拟器 `emu geo fix` 未接入）

**InboxSearch 死代码处置**
- 确认全工程零调用方，删除文件；FTS 触发器与 `ensureFtsSync` 保留在数据层
- 修掉三处指向它的注释

### 真机验证抓到的第 4 个 bug：撤销入口被覆盖（2026-11-20 22:26–22:31）

- 现象：点「提醒检查」→「提醒体检」对话框里只有 `通知设置 / 精确闹钟 / 关闭`，**没有「撤销完成」**
- 根因：`openReminderSettings()` 里连续调了两次 `setNeutralButton` ——
  `setNeutralButton("撤销完成") { openUndoPanel() }` 之后又 `.setNeutralButton("关闭", null)`，后者把前者覆盖，
  于是 `openUndoPanel()` 成了**永远调不到的死代码**
- 修法：删掉覆盖的那一行（注释说明原因）
- 验证（模拟器）：
  - 修前：对话框无「撤销完成」按钮
  - 修后：按钮出现（462,579）→ 点开列表显示「11月20日 22:16｜已完成｜撤销测试」→ 选中后库状态 `done → scheduled`、`notified` 归零

### 备份格式升级（由 22:27 定时任务产出，已核验）
- `BackupJson.kt`（272 行）+ `Backup.kt` 重写（+421/-126），格式升版并兼容 v9 按表名映射
- `BackupJsonTest` 10 例全绿；单测合计 **42/42**
- 已随 `719ba8a` 一并入库（我 `git add -A` 时把定时任务的未提交改动带进去了，已补核验）

### 新阻塞 B-3：加密备份没写出文件
- `--es cmd backup` 能触发（同入口的 `counts` 正常），但 `files/backups/` 不生成文件，也无日志
- 记入 BLOCKERS.md，待定位是 intent 分支没进、还是 Keystore 抛错被吞

### 阶段 E / T-E2 备份升全表 + 导出/恢复演练（2026-11-20 22:25–22:45，模拟器）

**备份格式 v9 → v10：从「四张表」升到「全表」**

| 项 | v9（旧） | v10（今） |
|---|---|---|
| 覆盖表 | inbox / reminders / ledger / chat | 再加 **skills / embeddings / cloud_log**（共 7 表） |
| 字段 | 手工枚举 | 按表 `SELECT *`，列名从游标读 → 以后加列不用改备份代码 |
| 二进制 | 无（向量被整表漏掉） | BLOB → `b64:` 前缀，256 维 float32 原样带走 |
| 恢复 | 重新 insert（id 变） | **保留原 id** + `INSERT OR IGNORE`（幂等） |
| 旧包 | — | 兼容：v9 顶层数组自动映射到真实表名/列名 |

**为什么必须做**：v9 恢复后技能的自动规则、检索向量、出网留档会一起消失，用户完全看不出来。

**演练（emulator-5554）四段对照**

| 阶段 | inbox | reminders | ledger | embeddings | chat_log | skills | cloud_log |
|---|---|---|---|---|---|---|---|
| 灌种子后基线 | 5 | 2 | 2 | 2 | 5 | 2 | 2 |
| 备份 | 已加密 5KB（20 行 / 7 表） | | | | | | |
| `rm databases/zhuli_inbox.db` 后 | 0 | 0 | 0 | 0 | 0 | 0 | 0 |
| 恢复后 | **5** | **2** | **2** | **2** | **5** | **2** | **2** |

逐字段比对（不是只比行数）：inbox 关键字段、ledger 的 inbox_id 关联、**embeddings 的向量字节 hex**、
skills、cloud_log、chat_log 全部逐行相等；id 仍是 1–5（没有被重新编号），`user_version=12`。
再恢复一次 → **0 行新增**（幂等）。

**过程中发现并修掉的三件事**

1. **备份入口对脚本不可用**：`am start --es backupcmd ...` 在 App 已在栈里时走 `onNewIntent`，
   实测 START_DELIVERED_TO_TOP 但 App 侧**零回调**。改成广播接收器
   `am broadcast -n com.zhuli.app/com.zhuli.core.BackupCommandReceiver --es cmd backup`
   （non-debuggable 包内部直接 return），冷启动/后台/停止态都能触发（停止态要 `-f 32`）。
2. **恢复失败提示是「null」**：`pm clear` 后恢复，Keystore 密钥已销毁，抛 AEADBadTagException 且 message=null，
   面板显示「恢复失败：null」。现在翻译成「备份密钥已不在本机…（见 ADR-0002）」，实测生效。
3. **org.json 无法在 JVM 单测里用**：自写 `core/BackupJson.kt`（200 行极简 JSON），
   备份的写与读都能在本地跑测试 —— 备份「能写不能读」等于没有备份。

**已知限制（写进 BLOCKERS B-9）**：备份密钥绑定本机 App，卸载重装/清数据即不可解。
能救「库被写坏、误删表、升级失败」，救不了「卸载重装」。可迁移方案在 ADR-0002，属 C 类待批。

单测 **48/48**（新增 BackupJsonTest 10 例 + BackupLegacyTest 6 例）；构建通过；模拟器装机验证。

### 备份/恢复：入口纠正 + 两道护栏（2026-11-20 22:37–22:42，模拟器）

- **纠正**：`cmd backup` 要走广播（`am broadcast -a com.zhuli.app.BACKUP --es cmd`），
  我之前用 `am start --es cmd` 属用错入口 —— 备份生成立刻成功（5445 字节）。B-3 撤销
- **新问题 B-4**：一次 `restore` 报 `AEADBadTagException` 后，`counts` 七表全 0、库文件 94KB 但无数据。
  `loadJson` 本身在事务内且只 `INSERT OR IGNORE`，理论上不删数据；未能复现
- **护栏**（无论根因如何都该有）：
  1. `restoreFrom` 先解密、失败直接返回，**碰库之前就退出**
  2. 导入前快照当前库到 `files/restore_snapshot.db`；导入异常则回滚覆盖，并在提示里说明"已回滚"
- 实测：连做两次异常恢复（坏包 / 解密失败），数据保持 inbox 3 / reminders 1

### T-D4 断网补跑（2026-11-20 22:44–22:47，模拟器）——通过

做法：插一条 `url=http://127.0.0.1:1/x`、`summary=[读取失败：超时（重试 0 次）]` 的收件，反复冷启动观察计数。

| 启动次数 | summary 里的计数 |
|---|---|
| 第 1 次 | 重试 **1** 次 |
| 第 2 次 | 重试 **2** 次 |
| 第 3 次 | 仍是 **2**（`tries >= 2` 跳过，不再打扰） |

结论：24 小时窗口 + 最多 2 次重试的记账正确，不会无限重试、也不会一次就放弃。
（顺带确认失败文案会把真实原因带出来：`Failed to connect to /127.0.0.1`。）

### 阶段 D / T-D3 二轮（模拟器）+ T-D5 围栏修复实测（2026-11-20 22:46–22:52）

| 项 | 结果 |
|---|---|
| 后台存活（短测） | 退后台 + 灭屏 + `battery unplug` 进 Doze，5 分钟后 `due 22:46:09 → flush 22:46:11`，通知正常（+2s） |
| 闹钟误差 | 同上，新增样本 **+2s**（首轮真机 +12ms/+19ms，差异是模拟器时钟精度） |
| **覆盖安装后闹钟重建** | 新增路径：`adb install -r` → `MY_PACKAGE_REPLACED` → `reschedule` → `alarmClock id=3 at=1795189569945`（与安装前同一时刻） |
| **T-D5 围栏** | 抽出 `GeoFence.handleEntered()`（接收器只解 Intent），用 debug 广播驱动：同日第一次 `notified=true`、第二次 `false`（去重生效），触发后 `re-armed 1`（**修复生效**），通知「📍 到家了」弹出 |
| 长测 65 分钟档 | 已布置（id=3, due 23:47），跑完再补结论 |

**模拟器做不到的**：`adb emu geo fix` 连接被拒（雷电不开放 5554 控制台）→ 系统 `addProximityAlert`
回调本身仍未验证，只验了接收端逻辑；真机走动仍是必须项。

单测 48/48；构建通过。

### 阶段 D / T-D1 验收缺口：待确认超期自动归档（2026-11-20 22:50–22:56，模拟器）

计划里 T-D1 的验收写着「待确认超期自动归档」，此前**只做了前半句**（进待确认、不排闹钟），
没有归档逻辑 —— 中置信事项会永远挂在「待确认」里，面板只显示 20 条、越堆越久，
而且用户点「确认」确认的是一个早已过去的时间，点了只会立刻补响一条。

- 新增 `core/PendingExpiry.kt`（纯函数）：宽限期 **24 小时**（"待确认不打扰，用户可能一天才开一次 App"）
- `InboxDatabase.expirePendingBefore(before)`：`pending` 且 `due_at < 截止点` → 状态改 `expired`
  （**归档不删除**，误报统计还要用；`due_at<=0` 的坏数据不动）
- `ReminderEngine.sweep` 里调用：sweep 本来就跑在「每次开 App / 每次重启」，不需要额外闹钟
- 新增 `Reminder.STATUS_EXPIRED`

**模拟器实测**（两条样本：3 天前到期 / 10 分钟前到期）：

```
zhuli.reminder: expired 1 pending reminders（超过 24 小时未确认）
```

库里：`陈年待确认 → expired`、`刚到期待确认 → pending`（宽限期内仍可确认）；面板待确认数 1。

单测 **54/54**（新增 PendingExpiryTest 6 例，含 24h 边界与非法数据）。

### P0：新装库 schema 漂移（2026-11-21 10:27–10:36，模拟器）

**怎么发现的**：验证「375 条丢失」修复时，恢复出来的新库与真机库**列数不一致**
（新库 16 列，真机 17 列，差 `inbox.repeat_rule`）。用 python 对新库直接 `INSERT` 带该列 → 
`table inbox has no column named repeat_rule`。也就是说：**新装/清数据后，第一条通知入库就会失败。**

**为什么之前没发现**：所有验证都跑在「真机库灌进模拟器」的数据上，那条路径是 v11 升级到 v12，
`repeat_rule` 早就被 `onUpgrade` 补过了。新装路径（`onCreate`）从没被单独验过 —— 单测也碰不到 SQLite。

**修法**：
- `onCreate` 建表列表补上 v9 的 `repeat_rule`（抽成 `V9_REPEAT_COLUMN` 常量，三处共用）
- `onOpen` 增加 schema 自愈 `ensureColumns()`：按已知后加列清单 `addColumnIfMissing`
  （只读库跳过；表不存在跳过）—— 任何来源的漂移都能补齐
- `BackupCommandReceiver` 新增 `ingest` 自检命令：走真实 `insert()` 路径（ContentValues 全列），写完即删

**验证**（emulator-5554）：
| 项 | 结果 |
|---|---|
| `pm clear` → 冷启动建库 → `cmd ingest` | `insert ok id=1（已删除）`（修复前必抛 no column） |
| 7 张表列名对比（新装 vs 真机升级库） | **全部逐列一致**，`user_version=12` |
| 带周期数据的备份→删库→恢复 | 377 条（原 375 + 2 条 `daily`/`weekly:1`）全字段逐行一致，`repeat_rule` 保留，向量字节一致 |

单测 **64/64**。决策见 DECISIONS D-019，README 加了「新增列要改三处」的约定。

### 阶段 F 第一项：来源管理（每 App 静音）+ 孤儿向量修复（2026-11-21 10:36–10:50，模拟器）

**先量再改**（375 条真库）：

| 项 | 结果 |
|---|---|
| 现 NoiseGuard 黑名单（日历/时钟/计步）命中 | **0 条** —— 对"自己叫自己"有效，对"App 天天推"无效 |
| 两个高频源占比 | `org.ikuuu.vpn` 169 + `com.bilibili.app.in` 144 = **83%** |
| 误杀（被黑名单拦掉但含 category 的） | 0 条 —— 规则是安全的，只是覆盖不到真问题 |

结论：问题不是"拦错了"，而是"该拦的没人拦"。所以做**用户可控的按 App 静音**，而不是继续猜阈值。

**实现**

- 库 **v13**：`source_rule(pkg PK, muted, updated_at)`；onCreate / onUpgrade / onOpen 三处都调 `createSourceTable`（IF NOT EXISTS）
- `NoiseGuard.dropReason` 第一道判定查静音 —— 采集侧直接不收，通知根本不落库
- 备份表清单加 `source_rule`（8 表），恢复后静音状态跟着回来
- 主界面新增「来源」按钮 → 面板列出条数最多的 20 个来源（App 名 + 包名 + 条数 + 开关）；
  打开开关时问「是否清掉已收的 N 条」

**验证（emulator-5554）**

| 步骤 | 结果 |
|---|---|
| install（v12 库）→ 自动迁移 | `pre-migration backup v12_...db` → `user_version=13`，377 行不动 |
| `sources` | ikuuu 169 / bilibili 144 / kugou 18 / … |
| `filter pkg=ikuuu` 静音前 → 静音 → 取消 | `收下` → `已静音该来源` → `收下` |
| 静音 bilibili → 备份 → 删库 → 恢复 | 备份 8 表 556 行；恢复后 `filter bilibili` 仍 `已静音` ✅ |
| 静音+清历史（ikuuu 169 条） | inbox 377→208 |
| 静音+清历史（bilibili 144 条） | inbox 377→233、embeddings 120→71、**ledger 12 不动**、B 站挂的 7 条噪声提醒一起消失 |
| 恢复备份 | 回到 377 / 120 |

**过程中修掉的 bug**：清 169 条后留下 **51 条孤儿向量**（删消息没删向量）。
`deleteInboxByIds` / `deleteRowsOfPackage` 改为同事务内先删向量；`onOpen` 每进程清一次历史遗留。
踩坑：自愈函数在 `onOpen` 里用 `writableDatabase` 会递归进 `getDatabaseLocked` 抛异常并被吞掉，
必须传当前句柄 —— 日志里那一行 W 差点让我以为"清理跑过了"。

单测 **64/64**；决策见 DECISIONS D-020 / D-021。

### 阶段 F 验收：撤权停采 + 通道完整性（2026-11-21 10:44–10:52，模拟器）

| 项 | 做法 | 结果 |
|---|---|---|
| 正向对照 | 授权通知使用权 → `cmd notification post` 一条取件短信样式 | 377→378，入库并自动排了取件提醒 ✅ |
| **撤权停采（通知）** | `cmd notification disallow_listener` → 再发一条同格式 | **378 不变**、零 `zhuli.inbox` 日志 ✅ |
| **撤权停采（短信）** | `pm revoke READ_SMS` → 触发扫短信 | 走 `hasSmsPermission` 分支请求授权，**不读库、不崩**（进程 3124 存活，FATAL 0 次）✅ |
| **通道完整性** | 连发 10 条标题正文各不相同的通知 | **+10 / 10 = 100%**（模拟器 shell 源；真机主流 App 抽样仍需真机） |

副作用验证：`pm clear` 会清掉通知使用权（`enabled_notification_listeners` 为空）—— 这解释了之前
「发通知没反应」，也是"重装后必须重新授权"的实证。

清理：用新做的来源管理把 10 条测试行 `mute --purge` 掉，inbox 388→377、reminders 11→10，功能本身又验了一次。

### 阶段 G：助理自检（8 问一屏）+ 提醒依据（2026-11-21 11:05–11:30，模拟器）

**先看别人怎么做**（按用户要求，动手前先查开源实现）：

| 项目 | 可取之处 | 没照搬的 |
|---|---|---|
| Permission Pilot | 只看不改，改权限交给系统设置 | 它的"权限矩阵"对单人自用太重 |
| Privamatic | 读不到的检查请用户核验，不默认通过；逐条给出"怎么修" | 0–100 分（自己用，分数是噪音） |
| Android 12 Privacy Dashboard | 按时间线看"谁动了我的数据" | 我们没有传感器，只有通知与提醒 |

**做出来的东西**

- 状态行（"收件 377｜待提取 0｜待办 9｜提醒 2"）末尾加 `▸ 自检`，点开是一屏：
  ① 采集 → ② 知道什么 → ③ 提醒 → ④ 权限与保活 → ⑤ 出网 → ⑥ 怎么纠正
- 三档判断（✅ / ⚠️ / ❌），总评取最高档；每行都写"后果"，比如
  「通知使用权：没开 —— 现在收不到任何 App 的通知」「设了围栏但没定位权限 —— 到了也不会响」
- 判断与取值分离：`SelfCheck` 纯函数（19 条单测），`SelfCheckAndroid.collect` 只摸 API 和库
- **待确认点开先讲凭什么**：时间 / 依据（basis）/ 置信度 / 原文，再给「确认并排闹钟 / 忽略这条」
- 通知展开区追加 `依据：…`（`ReminderText.basisLine`，截 60 字，空依据不硬编）

**验证（emulator-5554）**

| 场景 | 做法 | 结果 |
|---|---|---|
| 正常态 | 点状态行 | 14 行全绿，标题「全部正常」 |
| 本应用通知被关 | `pm revoke POST_NOTIFICATIONS` | 该行变 ❌「提醒排了也不会显示」，标题变「1 项断了，先修它」 |
| 通知使用权被撤 | `cmd notification disallow_listener …` | 该行 ❌ + 标题「2 项断了」 |
| 内容溢出 | 横屏 1080×610 | 标题自动加「（可下滑）」，滑到底 ⑤⑥ 完整可见 |
| 推断可见 | 点待确认 1 条 | 弹出「凭什么：时间来源不明 / 置信度：中」 |
| 忽略 | 点「忽略这条」 | 待确认清零，自检「推断出来的事：没有」，聊天区留痕 |

**自检自己抓到的 bug**：第一版把绝对时间戳当"距今多久"传给 `ago()`，面板显示
「数据外出 12 次，最近 **692 个月前**」（1970 年至今）。改成 `ago(now - lastAt)`，
并对不合理时间戳降级成"时间未知"，加了一条专门盯这个的测试。

**踩的坑（记下来省下次）**

- 判断"通知使用权"不能用 `getEnabledListenerPackages`：它返回的是 manifest 里声明了监听的包，
  跟用户勾没勾无关；也不能用 onListenerConnected 的静态标志位（没授权时也会回调）。
  API 27+ 用 `isNotificationListenerAccessGranted`，24–26 解析 `enabled_notification_listeners`
  并按 ComponentName 精确比对（`contains(包名)` 会被 `com.zhuli.app.fake` 骗过）。
- `adb shell run-as … cat databases/zhuli_inbox.db` 拉下来的文件 `page_count=0`、无表，
  不能用来取证；UI 与 logcat 更可信（`sqlite3` 直连也报 no such table）。
- 模拟器横屏只有 610px 高，对话框必须假设内容会超一屏 —— 加"（可下滑）"比让用户自己发现强。

单测 **85/85**；决策见 DECISIONS D-022 / D-023。

### 阶段 G · B 类复验：数据安全 / App 锁 / 导出 / 设置（2026-11-21 11:22–11:40，模拟器）

**复验方式**：打开 → 边界 → 异常。每项都要点到底，不看代码判断"应该没问题"。

| 项 | 覆盖 | 结果 |
|---|---|---|
| App 锁 | 设 PIN → 杀进程重开 → 错 PIN → 对 PIN → 留空关闭 | 四条全过：重启弹「解锁」、错 PIN 提示「PIN 不对」且不解锁、对 PIN 后输入框恢复、留空保存后不再拦 |
| 数据安全 | 五个动作入口 | 修好后全可见（见下） |
| 导出 CSV | 点「导出账本 CSV」 | 生成 `cache/exports/zhuli-账本.csv` **973 字节**，BOM + 表头 + 14 行真实账本，分享面板显示「正在分享 1 个文件 · zhuli-账本.csv」 |
| 导出 JSON | 点「导出完整 JSON」 | `zhuli-备份.json` **99,116 字节**，`json.load` 通过，顶层 app/version/dbVersion/at/tables/rows |
| 设置 | 关「晨间简报」→ 杀进程重开 | 仍为关，持久化正常；另核对了 briefEnabled / quietHoursEnabled 确实被使用 |

**复验抓到的 5 个问题（都已修）**

1. **「数据安全」同时弹两个窗**：状态窗被动作窗盖住，「加密备份」根本没机会点。
2. **改成一个后动作列表整片消失**：AlertDialog 的 `message` 和 `items` 不能同时用（设了 message，items 不显示）。
   最终：状态进标题、说明写进每个动作名（「加密备份（留在 App 私有目录，拔走也解不开）」）。
3. **"导出"其实是分享文本**：`EXTRA_TEXT` 而不是文件，微信里收到的是一段聊天内容；超过 20 万字还会截断
   —— 注释写着"太大就落文件"，代码里没有。改为写 `cache/exports/` + FileProvider + `EXTRA_STREAM`。
4. **`text/csv` 没有应用接受**：干净系统直接"没有应用可执行此操作"。改 `*/*`，类型交给扩展名，不赌 MIME 兼容。
5. **设置文案与代码打架**：「到点提醒」写"已排的会撤销"，实际 `scan()` 只是 return 0，已排闹钟照常响。
   改成"关掉后不再新建提醒；已排的照常响"。

**验证手段补充**：`run-as com.zhuli.app ls -l cache/exports` 能直接看到导出文件大小，
比看分享面板可靠 —— 以后验导出都用这条。

**未做**：视频复验（需要真实视频素材 + 语音转写链路），留到下一轮。

单测 **85/85**。

### 真机接入：0.19 装机 + 三个缺陷/验收（2026-11-21 11:40–12:00，Z70 Ultra）

**背景**：用户把手机接上了。真机原装 `0.18-video/18`，库里 `user_version=12` —— 也就是说昨天 21:14 那版之后
所有改动（v13 来源静音、自检面板、提醒依据、导出真文件）都只在模拟器上，机上没有。本轮先补装再验收。

**装机前留档**：`fixtures/realdev_20261121/zhuli_inbox_pre_v13_install.db`（434 条 / v12），拉取时带 `-wal`（61B，已 checkpoint）。

#### 1. 迁移（真机，不是模拟器）

| 项 | 结果 |
|---|---|
| 覆盖安装 | `adb install -r` → Success，签名 `38:E9:6A:B6…` 与 0.18 一致，无需卸载 |
| 库迁移 | `user_version 12 → 13`，`source_rule` 建出（pkg/muted/updated_at） |
| 数据完整性 | inbox **434 → 434**，reminders 1 / ledger 13 / chat_log 24 一条不动 |
| 顺带自愈 | embeddings **180 → 173**，D-021 的 `purgeOrphanEmbeddings` 在真机上清掉 7 条孤儿向量 |

#### 2. 缺陷一（真机独有）：来源面板把第三方 App 显示成包名尾巴

`in / vpn / mm / lite / mobileqq / chatgpt / aligames / dsh_app` —— 而库里 `app_label` 是对的
（bilibili / iKuuuVPN / 微信 / 酷狗概念版 / QQ / ChatGPT / 决战！平安京 / DSH Go）。
自家 App 与系统包（电话和短信存储、AI翻译）显示正常，**只有第三方错** → Android 11+ 包可见性。

- 修法：显示名三级降级 `系统名 → 库里 app_label → 包名尾巴`，抽成纯函数 `SourceRules.pickLabel`（4 条单测）；
  `topSources` 的 SQL 补 `MAX(app_label)`。
- **没加 `QUERY_ALL_PACKAGES`**：为了显示个名字去要「查看所有应用」，不值。
- 复验：装回后面板显示 bilibili / iKuuuVPN / DSH Go / 微信 / 酷狗概念版 / 决战！平安京 / QQ / ChatGPT。

#### 3. 缺陷二（真机独有，更严重）：建提醒的意图被「查待办」吃掉

真机原话「3分钟后提醒我喝水」→ 助理回「眼下没有明确的待办。有期限的事可以直接丢给我…」。
`parseDeadline` 认「N 分钟后」、`ReminderConfidence` 判它高置信，缺的是路由：`Q_TODO` 正则里有「提醒」。

- 修法：新增 `Q_REMIND_CREATE`，排在 `Q_TODO` 前，**且必须解析出未来时间**才走建提醒；解析不出落回查询
  （「提醒我这周都干了啥」不能变成下提醒）。建提醒复用原管线（入库 → `ReminderEngine.scan`），不另写排闹钟。
- 回话只说库里真发生的事：高置信「到点会响」/ 中置信「在待确认」/ 没建成就直说没建成。
- 复验：同一句话 → `reminders` 多出 `喝水 / 11-21 11:53:42 / scheduled / high / 相对时刻（N 分钟后）`，
  聊天区回「好，11-21 11:53 叫你：喝水。（依据：相对时刻（N 分钟后）｜到点会响…）」。

#### 4. 验收：提醒依据（此前只有单测锁文案，没真机证据）

到点后从 `dumpsys notification --noredact` 取到通知实体：

```
android.title  = ⏰ 喝水
android.text   = 对话：喝水
android.bigText= 11月21日 11:53
                 对话：喝水
                 依据：相对时刻（N 分钟后）
actions        = 完成 / 稍后10分钟
```

- 同时看到日历双写生效：日历里另有一条「喝水 11:53」。
- 「待确认」真机点开：标题「这条是推断出来的」，正文 想提醒你 / 时间 / **凭什么：时间来源不明** /
  **置信度：中 —— 时间说法含糊，所以先问你** / 原文，按钮 关闭 / 忽略这条 / 确认并排闹钟。

#### 5. 真机采集现状（F 阶段抽样的原料）

| 项 | 值 |
|---|---|
| 收件 | 436 条 / 15 个来源（其中 B 站 170 + iKuuu 169 = 78%） |
| 真实即时通讯 | 微信 18 / QQ 2 / 短信通道 7（韵达·圆通·极兔·快递短信，取件码已自动建提醒） |
| 最近入库 | 8 分钟内（11:34 B 站）；进程自 11-20 21:18 起存活 **14.4h** 未被杀 |
| 账本 | 13 笔、近 30 天 ¥8688.70 |
| 出网 | 26 次（cloud_log），最近 14 小时内 |

**说明**：漏收率仍算不出来 —— 需要「系统通知栏实际弹出数 vs 入库数」的对照，靠肉眼抽样撑不起数值。
下一轮可考虑在真机上对微信/银行各发几条固定测试消息（用户配合），或对同一时段用系统通知历史对比。

#### 6. 待用户拍板（本轮新增）

- **取件码提醒进了「待确认」**：快递取件是主力场景，但 deadline 是「到站 +2h」推出来的，
  依据写「时间来源不明」→ 按 D-012 归中置信，不排闹钟、等确认。要么接受（宁可问一次），
  要么给快递来源单开一条「到站即高置信」的口径。这属于提醒策略变更，等用户定。

单测 **95/95**（新增 SourceRules 4 + ReminderIntake 6）。

#### 7. 快递取件提级（用户拍板 A，2026-11-21 12:0x）

真机上刚收到的一条取件短信躺在「待确认」里，依据写着「时间来源不明」—— 主力场景没通电。
用户选「识别到取件码就自动排」。改法：`CourierPolicy.basisOf(code)` 纯函数，
有码 → 高置信 + 依据如实写「快递到站（取件码 X，按到站 +2h 推定）」；没码交还原判（仍待确认）。

**验证**：真机发一条取件通知 → `reminders` 多出
`<承运商>·…取件码 8899 / scheduled / high / 快递到站（取件码 8899，按到站 +2h 推定）`；
测试数据用 `mute --purge` 清掉（inbox 438→437、reminder 342 连同日历事件一起撤）。单测 **98/98**。

> 批次 A 脱敏说明（2026-11-25）：本段原先写有用户真实取件码与驿站名，已替换为合成值；
> 真实截图与真机 dump 已从仓库移除。历史提交仍含旧内容，需单独授权后清理。

#### 8. 2026-11-24 14:0x · 记忆核心 + Agent 核心（直接改代码）

基线：HEAD `ec7bc77`、数据库 v14、128 例单测全绿。确认「没有 Agent、没有任务表、记忆只有 inbox 行 + 哈希向量」后直接开写。

- 数据库 v15：新增实体 / 事实 / 事实向量 / 任务 / 步骤五张表；迁移在原有事务内，迁移前自动备份。
- `com.zhuli.memory`：抽取、四路排序（无相关性信号不返回）、纠错留历史、矛盾裁决、Gemma 语义 + 哈希降级（空间隔离）。
- `com.zhuli.agent`：统一网关、工具注册表、策略引擎、AgentLoop（重试 / 超时 / 挂起 / 审批恢复 / 去重）、SQLite 任务存储、云 / 本地真实后端。
- 通知提取流水线上接事实抽取；控制台新增「记忆」「任务」页。
- 测试过程中抓到并修掉一个真 bug：审批后 `run()` 再次 `beginRound()` 清空刚授予的同类授权，导致用户点「允许」后又被问一次。
- 结果：JVM 单测 **21 文件 / 169 例 / 0 失败**；`assembleDevDebug` 成功。
- 真机 NX733J：arm64 APK 覆盖安装 Success；冷启动无 FATAL；`zhuli_inbox.db` `user_version=15`，五张新表齐全，存量 **759 行 inbox 未丢**。
- 未验：Agent 页实际发起任务、云端工具调用、Gemma 语义检索（需 arm64 + 已下载模型）、存量行事实回填（设计上不做）。


#### 9. 2026-11-24 15:0x–19:0x · v16 补丁接入 + 缺陷修复 + 编译测试 + 真机验收

用户给 `zhuli_v16_patch_2026-11-24.zip`，要求安全接入、审计真实缺陷、编译测试、真机验收、更新文档。
补丁包声明"未编译未测试"，实测确认属实。

**环境/仓库**：仓库 `asd1548428584-cloud/zhuli`；基线 `master` @ `7288f2d` 工作区干净；
建 `v16-integration` 分支；`git apply --check` → `git apply` 无冲突。

**审计 + 修复**（详见 CURRENT_STATE v16 专节）：补丁首个编译错误 `MemoryIndexer.notify()` 与
`Object.notify()` JVM 签名冲突 → 改名 `requestIndex()`。逐项修：`com.sun.net.httpserver` 在 JBR21
不可解析 → `ServerSocket` 手写端点；`repo.active("10")` 类型错；Robolectric 起真实 App 触发
Koin/Firebase 冲突 → 新增 `PlainTestApp`；`SqlAgentStore` 双 `LIMIT` 语法错；`enqueueAllActive()`
因 `CONFLICT_IGNORE` 救不回失败项 → 显式重置 attempts/error；丢唤醒竞态 → `queuedAgain` CAS；
失败原因被清空 → `hadFailure` 守卫；聊天 JSON 异常原样放行 → `requireMessages=true`；
副作用后抛异常误记 FAILED → UNKNOWN；FAILED 无法重试 → 复用 executionId 先置 RUNNING；
降级本地模型看不到说明 → `buildMessages()` 重建；工具恢复跨任务误判 → 按 taskId 隔离；
审批恢复顺序 + 多任务歧义 → 先 init 再取候选、任务号显式指定。

**验证**：`:app:compileDevDebugKotlin` 成功；`:app:testDevDebugUnitTest` **27 套件 / 219 例 /
0 失败/错误/跳过**；`assembleDevDebug` 4 ABI 成功。

**真机 Z70 Ultra**（NX733J / Android16 / arm64 / `324450846696`）：用户要求跳过模拟器直接真机。
arm64-v8a debug APK 覆盖安装 Success（不清数据）。DB v15→v16 实测：user_version 15→16；
迁移前备份 `~/.zhuli/backup/v15_pre_v16_2026-11-24/zhuli_inbox.db`；779 条存量 inbox 逐行比对
0 丢失 0 篡改，新增 4 条为真短信；新表 `zhuli_vec_queue`/`zhuli_tool_exec` 建成；integrity_check=ok。
记忆页事实索引 0→2、重建闭环真机可用；自检全绿、隐私闸门 8 规则 + 三层自检通过；
收到圆通快递通知证明采集链路真实可用；inbox 779→783；POST_NOTIFICATIONS granted=true。
连续多页面操作约 10 分钟进程存活无 FATAL。该 ROM logcat 恒空（已记 D-026），以 DB+截图取证。

**真机未实测**：Agent 任务端到端（adb 无法注入中文，未真实发起）、云端工具调用、Gemma 语义检索
（当前 hash 降级）、24h 长时浸泡。JVM 层已覆盖但未在真机人工走通，不宣称通过。

**待办**：提交 `v16-integration`（排除日志/证据）；用户决定何时合并 master。

### 2026-11-24 22:13–23:50｜全库系统性过代码 + 输入框重复缺陷定位（未修，按用户决定先入库）

**任务**：用户报「页面输入框那会有重复」，要求把项目所有代码从头到尾系统性过一遍。
本轮产出为**排查与定位**，修复按下���决定**暂不做**，先连同 v17 进展一起提交 GitHub。

**已确认缺陷（真机取证，可稳定复现）**：聊天输入框内**占位文本「询问zhuli」与用户输入文字叠印**，
即用户所见「输入框那有重复」。最小复现：清空输入 → 打 1 个字（如 `Z`）→ 占位符仍压在文字上；
再打字、展开/收起拾取面板强制重组合、收起键盘，占位符均不消失。
取证链：截图叠影 + `uiautomator` 同屏两节点（EditText `text='Z'` 与 TextView `text='询问zhuli'`
bounds 相互嵌套）+ 像素对比（两状态 diff 恰落在文字原点 x≈287 处）。

**根因定位（代码级）**：`MinimalChatInput.kt:956` 的 `TextField(state=…)` 走 material3
**1.5.0-alpha08** `TextFieldDefaults.decorator` → `CommonDecorationBox`，占位显隐 = 装饰层内
`visualText.isEmpty()` 门闩 + `placeholderOpacity` 动画（`InputPhase.Focused → 1f`，即聚焦时常驻不透明）。
打字后该门闩不随 `TextFieldState` 重算，占位层常驻；且占位内容自带 `AnimatedVisibility`
（977–994 行），叠上本机已证实的「AnimatedVisibility 退场卡死叠加常驻」（同 ChatPage 1674-1676 修复注释）。
反编译 material3 1.5.0-alpha08 AAR 已核对门闩字节码与上述源码一致。
**修复方向**（未实施）：放弃 M3 `placeholder` 槽，在输入框 Box 内自绘占位层，仅以
`if (activeTextState.text.isEmpty())` 裁剪组合（不依赖退场动画），照抄 v17 欢迎语修复手法。

**全库扫描并列问题**（Explore 专项排查，按可疑度排序，均未修）：
1. **STT 合并去重不全**（`ChatInputStt.kt:18-25`）：`mergeCommittedSttTranscript` 只防后缀重复，
   转写全文被替换进输入框后再续打字触发 merge 会整段重复 → 「输入文字重复」的另一候选成因；
2. **全屏 Dialog 与内联输入框共享同一 `TextFieldState`**（`MinimalChatInput.kt:1144/1239`）：
   Compose 不支持一 state 双 TextField，且 Dialog 滑入动画期间两框同屏；
3. **输入区多处 `AnimatedVisibility` 退场卡死残留**（STT 覆盖层 889–946 最可疑，另有建议行/问卷卡/审批卡等）；
4. **AssistantOverlayScreen 半透明 + backdrop 截图被 `onTrimMemory` 清掉** → 透明透出底层输入框，视觉双框；
5. overlay STT 双通道写同一 `ChatInputState`（替换式 + 追加式并存）；
6. web 端 `message-turns.ts` turn.id 可能撞 key 致消息重复渲染（低）。
已排查排除：Kotlin 输入点全景、LazyColumn key、监听器配对、草稿双写、web ChatInput 单点渲染。

**顺带入库的 v17 进展**（6 文件改动 + 2 新测试）：云端 OpenAI 兼容配置回落到「设置→提供商」
（`AssistantPrefs.providerFallback`，修真机「云通道未配置」）、zhuli 助手删除标记/撤销自愈
（`ZhuliPersonaBootstrap.markUserRemoved/clearUserRemoved`）、`ChatPage` 冷启动欢迎语叠加
修复（等 `conversationInitialized` + `if` 裁剪替代 AnimatedVisibility）与 `ZhuliLog` 诊断线、
`ChatService/GenerationHandler` 诊断日志、新增 `ConversationNodesDecodeTest`/`ZhuliPersonaBootstrapTest`。

**下一步（待用户指示）**：按上述修复方向消掉占位叠影（优先 #1 STT 去重与占位自绘），再走真机验收。
