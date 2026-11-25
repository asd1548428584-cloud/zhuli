# zhuli 功能盘点与开源复用评估 V1.0

日期：2026-11-21 12:50
性质：为《V3.1 执行计划》提供事实底稿。本文件只回答三件事——**做了什么、怎么做的、该不该继续自己写**。
口径：所有「实现方式」均核到代码行（`zhuli-assistant-step2/core/`，共 51 文件 10,894 行；壳 `MainActivity.kt` 1,594 行）；所有开源结论标来源，⚠ = 未完全核实。

---

## 0. 结论先行

| 判断 | 数量 | 一句话 |
|---|---|---|
| **保留**（自研即最优，或没有现成轮子） | 10 项 | 隐私闸门、提醒引擎、噪声闸门、自检面板、备份格式、来源静音 —— 这六项是本项目真正的资产，换任何库都会掉分 |
| **保留骨架、换内核**（自研边界留，算法换库） | 6 项 | 时间解析、周期规则、正文提取、敏感判定、快递单号、无障碍选择器 |
| **退役替换**（纯属重复造轮子） | 4 项 | 哈希伪向量、链接正则洗标签、ModelStore 空壳、skills 死表 |
| **新增用开源**（别自己写） | 7 项 | ASR、Embedding、本地 LLM、TTS、整库加密、崩溃日志、工具参数校验 |

**总账**：可复用项约省 **4–6 周**；其中 RikkaHub 二开（3–4 周）与端侧 AI 两条链（7–10 人日）占大头。
**代价**：APK 体积从 24MB 涨到约 300–400MB（三个模型），或改为首次启动下载（推荐后者）。

---

## 1. 进度快照（2026-11-21 12:50 实测）

| 项 | 状态 |
|---|---|
| 源码 / 装机 | 0.19-sources / versionCode 19，真机已装（Z70 Ultra，Android 16） |
| 数据库 | v13（v13 新增 `source_rule` 来源静音表） |
| 阶段 | A/B/C 完成；D 除真机项外完成；E 部分（T-E1/E3）；F 大部分；G 收尾（差视频复验）；H 未开始 |
| 真机数据 | 收件 436 条 / 15 来源；提醒 3 条；账本 13 笔；进程连续存活 14.4h |
| 单测 | **114 个 @Test，实跑 108 绿 / 6 红**（状态文件写的「98/98 全绿」已过期，见 §5） |
| 依赖 | 仅 kotlin-stdlib + ML Kit 中文 OCR。**零第三方业务库** |
| 阻塞 | B-1 keystore 未异地备份；B-3 七项决策未拍板；B-9 备份不可跨重装；B-4 无障碍/自启动未开 |

---

## 2. 已实现功能全表

### 2.1 采集（"不会漏"）

| # | 功能 | 实现方式 | 成熟度 | 判断 | 开源替代 / 动作 |
|---|---|---|---|---|---|
| 1 | 通知采集 | `NotificationsListenerService` 127 行；解析 `EXTRA_TITLE/TEXT/BIG_TEXT` + MessagingStyle `EXTRA_MESSAGES` | 真机 436 条/15 源 | **保留** | Notification Vault（MIT）做到「存+按 App/关键词忽略」，无 MessagingStyle 结构化、无快递语义 —— 我们是它的超集，只借鉴其规则管理 UI |
| 2 | 分享接收 | `ShareReceiverActivity` 413 行，`ACTION_SEND` 多类型路由 | 真机验过 | **保留** | 无等价库，系统 API 本身简单 |
| 3 | 链接入库 | `LinkExtractor` 281 行：正则找 URL → 敏感特征拦截 → 云端摘要/本地抓取 | 可用但正文质量差 | **换内核** | `Readability4J`（Mozilla Readability 的 Kotlin 移植，基于 Jsoup，⚠ License 待核）+ 保留自研的敏感 URL 拦截 |
| 4 | 噪声闸门 | `NoiseGuard` 218 行：包名黑名单 + 关键词 + 熔断 + 回声抑制 + 同刻同标题判重 | 误报率 85.7%→0 | **保留** | 无轮子。误报基线和四道护栏是真机上试出来的，换库等于重踩 |
| 5 | 来源静音（v13） | `SourceRules` 106 行 + `source_rule` 表，采集侧就不收 | 真机验过 | **保留** | Notification Vault 有同构能力但更弱 |
| 6 | 快递/取件码 | `Express` 222 行：11 家承运商关键词 + 到站语义 + 取件码正则 + `CourierPolicy` 置信依据 | 真机 7 条短信命中 | **保留 + 补** | 缺**运单号**识别。移植快递100 风格承运商正则 JSON（无维护中的中文快递解析库） |
| 7 | 账单 OCR | `BillOcr` 240 行，ML Kit bundled 中文识别 | 真机验过 | **保留** | 不换 RapidOCR：OR T 官方预编译 16KB 未决，无精度优势证据 |
| 8 | 视频抽帧/音轨 | `VideoNote` 354 行：MediaMetadataRetriever 抽帧 + 字幕带裁剪 + 音轨抽 m4a；9 点均匀采样 | 真机验过 | **保留** | 这个设计是对的：只做「手」，抽几帧/怎么读交给模型（`VideoAgent` 215 行）。别改成固定流程 |
| 9 | 语音转写 | `VoiceNote` 241 行 + `CloudClient.transcribe`，默认关、每次明示确认 | 未用真 Key 验 | **保留（过渡）** | H 阶段换 `sherpa-onnx` + SenseVoice int8，音频不出机，D4 根治 |

### 2.2 理解（"看得懂"）

| # | 功能 | 实现方式 | 成熟度 | 判断 | 开源替代 / 动作 |
|---|---|---|---|---|---|
| 10 | 结构化提取 | `ExtractPipeline` 441 行纯正则：分类词表 + 15 类时间表达 + 金额上下文词 + 周期 | 真机 20 条实弹分类 | **保留骨架、换时间内核** | 时间解析换 `xk-time` 的 `TimeNLPUtil`（Apache-2.0，源自复旦 Time-NLP，支持区间/多时间/未来倾向）；自研只留「中文表层 → 字段」适配层 |
| 11 | 周期规则 | `Repeat` 79 行，只支持 `daily / weekly:N / monthly:N` | 单测 6 条 | **退役替换** | `lib-recur`（Apache-2.0，0.17.x，Etar 日历在用，完整 RFC5545：每 N 天/双周/工作日/排除日/UNTIL/COUNT） |
| 12 | 提醒置信度 | `ReminderConfidence` 70 行三级 + 依据字符串 + `CourierPolicy` 提级 | 真机验过（D-012/D-025） | **保留** | 无轮子。这是产品差异点（"凭什么提醒我"可解释） |
| 13 | 记账 | `Ledger` 115 行，`UNIQUE(inbox_id)` 幂等入账 | 真机 13 笔 | **保留** | 无轮子，规则简单 |
| 14 | 实体归一 | 未做（代码注释明确「人名不硬猜」） | — | 见 §3 | 候选 `HanLP portable`（⚠ Android aar 待核，+7.6MB）；建议先云端做 |

### 2.3 提醒（"不会忘"）

| # | 功能 | 实现方式 | 成熟度 | 判断 | 开源替代 / 动作 |
|---|---|---|---|---|---|
| 15 | 提醒引擎 | `ReminderEngine` 448 行：`setAlarmClock` 优先、去重、snooze_count、晚响补响、同刻同标题已取消不重建 | 真机准点 + 修掉 D15 与 snooze bug | **保留（核心资产）** | 无等价物。市面 AlarmManager 封装库都是薄封装，换掉会丢这四道真机护栏 |
| 16 | 通知呈现 | `ReminderNotifier` 277 行：`bigText` 带「依据」、完成/稍后动作 | 真机验过 | **保留** | 无轮子 |
| 17 | 日历双写 | `CalendarWriter` 120 行写 CalendarProvider | 真机验过 | **保留** | 若接 lib-recur，RRULE 可直接写进日历，减少一次转换 |
| 18 | 晨间简报 / 小组件 | `DailyBrief` 173 / `BriefWidget` 91 | 简报可用；小组件未真机验 | **保留** | 无轮子 |
| 19 | 重启恢复 / 自启兜底 | `BootReceiver` 29 + `StartupGuard` 174（开 App sweep） | 模拟器过 | **保留** | 无轮子（ROM 坑专用） |

### 2.4 检索与记忆（"找得到"）

| # | 功能 | 实现方式 | 成熟度 | 判断 | 开源替代 / 动作 |
|---|---|---|---|---|---|
| 20 | 表结构与迁移 | `InboxDatabase` 1,526 行，v13，事务化迁移 + 失败保护 | 真机 v12→v13 零丢失 | **保留** | 借鉴 Room 的迁移测试思路即可，不整体引 Room（KSP + 实体重写，收益不抵成本） |
| 21 | 全文检索 | LIKE 三列（title/text/note） | 命中 13/13，40 个高频词召回 40/40 | **保留（主力）** | 待验「裸 fts5 + unicode61 + jieba」这一唯一没验过的前置事实（此前只试过 trigram，建表被 ROM 吞掉） |
| 22 | FTS 虚表 | `inbox_fts` 建表尝试，失败静默降级 | **本 ROM 建不出来** | **降级为条件特性** | 不要假装有；面板要显示「当前 ROM 不支持」而不是静默 |
| 23 | 向量召回 | `VectorSearch` 119 行，FNV-1a 字符 1/2-gram 哈希到 256 维 + 余弦 0.45 / 关键词 0.55 | 非语义，代码自认 | **退役替换** | 先用 **`bge-small-zh-v1.5`**（MIT，24M，fp16≈47.5MB，512 维，中文记忆 hit@1 0.893）；`EmbeddingGemma 2`（Apache-2.0，多模态）与 `Qwen3-Emb-0.6B`（天花板）作升级路径 |
| 24 | 混合排序 | 手工加权 0.45/0.55 | 可用 | **换算法** | RRF（`score=Σ1/(k+rank)`, k=60，约 20 行）：排名融合不需要对齐两种分数 |
| 25 | 冲突检测/待确认 | `PendingExpiry` 32 + 冲突表 | 真机验过 | **保留** | 移植 mem0 的 ADD/UPDATE/DELETE 决策表与 graphiti 的 `valid_at/invalid_at` 字段设计 |
| 26 | skills 表 | 建表 + CRUD，**零调用方** | 死代码 | **退役** | 要么按 H 阶段接线，要么删表。不要留着当"能力" |

### 2.5 云端与隐私（"不泄漏"）

| # | 功能 | 实现方式 | 成熟度 | 判断 | 开源替代 / 动作 |
|---|---|---|---|---|---|
| 27 | 出网闸门 | `PrivacyRouter` 186 行三级判定 + `SensitiveGuard` 121 行正则（手机/身份证/银行卡/金额/凭证/地址/单位+角色） | 假端点请求体全绿 | **保留（核心资产）** | 无可替代的 Android 库（Presidio 是 Python 服务端）。**移植** Presidio 的两件东西：Luhn/身份证校验位、上下文词加权 —— 直接降误报 |
| 28 | 密钥存储 | `SecureKeyStore` 162 行，Keystore AES-GCM，失败 fail-closed | 已验 | **保留** | 不引 Tink：已直接用 Keystore，换库不解决「Keystore 不可导出」的本质 |
| 29 | 工具调用 | `Tools` 182 行 6 个工具 + `AssistantChat` 506 行循环（3 轮上限） | 真云跑通 | **保留 + 补** | 不引 koog/langchain4j（6×3 循环不值得依赖膨胀）；补一层 JSON Schema 校验（`everit` Apache-2.0 基于 org.json 零额外依赖，⚠ 维护放缓；不济就手写百行） |
| 30 | 出网留档 | `cloud_log` 表 + 面板 | 真机验过 | **保留** | 无轮子 |
| 31 | 视觉理解 | `CloudClient.chatVision` 多模态帧输入 | 真机验过（读对字幕） | **保留** | 无轮子 |

### 2.6 交互与数据安全

| # | 功能 | 实现方式 | 成熟度 | 判断 | 开源替代 / 动作 |
|---|---|---|---|---|---|
| 32 | 主界面 | `MainActivity` 1,594 行单文件纯 View | 能跑，难改 | **不急着拆，先做复用 Demo** | `RikkaHub`（AGPL-3.0，9k 星，2026-11-19 仍在更，Kotlin/Compose/多模块/MCP/Room）fork 后注入 core。见 §4 决策树 |
| 33 | 自检面板 | `SelfCheck` 425 行，14 行链路自检（知道什么/来源/推断/任务/权限/出网/凭什么/怎么改） | 真机 14 行全绿 | **保留（差异点）** | 无轮子。开源聊天壳都没有"可解释性面板" |
| 34 | 待确认面板 | 中置信事项列出，一键确认/归档 | 真机验过 | **保留** | 无轮子 |
| 35 | 备份/恢复 | `Backup` 591 + `BackupJson` 272（自写 JSON）+ v10 全 7 表 + 保留原 id | 模拟器 3 路径演练 | **保留** | 不换 kotlinx.serialization：收益只是少 272 行，代价是重测 16 条单测（D-014/15/16 的教训很贵） |
| 36 | 加密备份 | Keystore AES-GCM，**跨重装不可解**（B-9） | 实测 AEADBadTag | **换密钥体系** | 无轮子（ADR-0002 口令派生 PBKDF2/Argon2，C 类待批）。`sqlcipher-android` 不解决备份问题，只解决库文件 |
| 37 | 导出 CSV/JSON | 真文件导出 | 真机验过 | **保留** | 无轮子 |
| 38 | App 锁 | `AppLock` 50 + PIN PBKDF2 随机盐 12 万次 | 真机 4 条路径 | **保留** | 系统 BiometricPrompt 可加，不是必需 |
| 39 | 无障碍 | `ZhuliA11yService` 77 行只注册不解析 + `UiBridge` 209 行按需抓树 | 能力有限（不做多步） | **保留骨架** | H 阶段移植 `GKD` 的类 CSS 选择器语法（GPL-3.0，个人自用可接受），不引其规则引擎 |

### 2.7 工程

| # | 项 | 现状 | 判断 | 动作 |
|---|---|---|---|---|
| 40 | 单测 | 114 用例 / 108 绿 / 6 红 | **需修红** | 见 §5 |
| 41 | 日志/崩溃 | **无**。logcat 恒空，排障靠读库 | **缺，要补** | `CustomActivityOnCrash`（⚠ License 待核）或自写 `UncaughtExceptionHandler` 落盘；日志用自写 `FileLoggingTree`（照 Timber 设计，不引 Timber） |
| 42 | 依赖 | 零第三方业务库 | 好事 | 新增依赖一律过 §6 的核实清单 |

---

## 3. 未实现功能全表

| # | 功能 | 现状 / 缺口 | 计划方法 | 首选开源 | 复用档 | 阶段 | 量级 |
|---|---|---|---|---|---|---|---|
| 1 | 真语义检索 | 哈希伪向量，同义改写搜不到 | bge-small-zh 出 512 维 → 存 `embeddings` → RRF 与 LIKE 融合；模型缺失自动回退 | `bge-small-zh-v1.5` | 直接依赖 | H | 2–3 人日 |
| 2 | 端侧 ASR | 云转写，音频出机 | sherpa-onnx AAR + SenseVoice int8，VAD 内置 | `sherpa-onnx`（v2.0.1, 2026-10, Apache-2.0） | 直接依赖 | H | 2–3 人日 |
| 3 | 本地 LLM | ModelStore 能下不能用 | LiteRT-LM + Qwen3-0.6B-int4，先测首 token/内存/发热 | `LiteRT-LM` | 直接依赖 | H | 2–3 人日 |
| 4 | TTS | 未做 | 同一个 sherpa AAR，零新增运行时 | `sherpa-onnx` VITS/Matcha | 直接依赖 | H（暂缓） | 1–2 人日 |
| 5 | 整库加密 | 明文库 | sqlcipher-android 4.6.1+，`sqlcipher_export()` 迁移，副本演练 | `sqlcipher-android` | 直接依赖 | E（C 类） | 2–3 人日 |
| 6 | 跨设备备份 | Keystore 不可迁移 | 口令派生密钥（PBKDF2/Argon2），与本地备份并存 | 无 | 自研 | E（C 类） | 2 人日 |
| 7 | 运单号识别 | 只有取件码 | 承运商正则 JSON 转资源文件 + 排除 11 位手机号 | 快递100 风格正则集 | 移植规则 | F | 1–2 人日 |
| 8 | 每 App 通知开关 | **已做**（v13 来源静音） | — | — | — | 完成 | — |
| 9 | 群聊摘要 | 用户已定**不做** | — | — | — | — | — |
| 10 | FGS / FCM 保活 | 只有闹钟 + sweep + 白名单 | 先按 T-D3 数据定 FGS 类型，再决定 FCM | 标准 API | 不引库 | D/H | 2–3 人日 |
| 11 | 文件日志/崩溃页 | 无 | 崩溃弹页展示堆栈 + 重启；文件日志 + 面板 + 分享 | `CustomActivityOnCrash`（备选自写） | 直接依赖 | A（提前） | 1–2 人日 |
| 12 | 工具参数校验 | 无 Schema 校验 | 每工具声明 JSON Schema，调用前校验 | `everit json-schema` 或手写 | 直接依赖/自研 | B | 1–2 人日 |
| 13 | skills 参数化技能 | 死表 | 先定冲突/优先级/误伤口径，再接线；否则删 | 无 | 自研 | H | 按立项 |
| 14 | 无障碍选择器 | 只能读树 + 单步点击 | 移植 GKD 的 `[vid=..] < [..]` 选择器语法 | `GKD` | 移植 | H | 约 1 周 |
| 15 | 漏收率数值 | 无基线 | 固定测试消息 vs 系统通知历史对照 | 无 | 测试方法 | F | 需配合 |
| 16 | 视频复验 | 未做 | 一段真实视频跑通抽帧→视觉→摘要 | 无 | — | G | 需素材 |
| 17 | 实体归一 | 未做 | 先云端做同名消歧，端侧后置 | `HanLP portable`（⚠ 待核） | 待定 | F/H | 3–5 人日 |
| 18 | 桌面小组件 | 有代码未真机验 | 真机验 | 无 | — | G | 0.5 人日 |

---

## 4. 三个必须先定的判断

### 4.1 聊天壳：fork RikkaHub 还是保留 1594 行 Activity

**建议：先花 1–2 天做注入 Demo，再决定。** 不做 Demo 就 fork，等于赌 3–4 周。

| 路径 | 收益 | 风险 |
|---|---|---|
| A. fork RikkaHub | 省 3–4 周 UI；Compose/MCP/Room/多模态现成 | AGPL 传染边界（自用不分发无义务，一旦分发就要开源）；需处理 `google-services.json`；core 注入要动它的工具循环与记忆层；它的版本在动（3.3.0），跟进成本持续 |
| B. 保留现状，逐步拆 | 零风险，core 已稳定 | 每加一个面板都更痛；1594 行继续长 |
| C. 只抄不 fork | 抄它的 Provider 抽象、消息分支、Markdown 渲染 | 抄 UI 的工作量并不比写少多少 |

**Demo 的验收**：把 `PrivacyRouter + Tools + ReminderEngine` 编进 RikkaHub 的一个新模块，能在它的对话界面里问一句"我快递到哪了"并拿到本地库答案。跑通 → 走 A；跑不通 → 走 B，并把"拆 MainActivity"提前到 G 阶段第一批。

### 4.2 依赖体积：模型进包还是首启下载

三个模型：SenseVoice int8 ≈ 155–240MB、Qwen3-0.6B-int4 ≈ 400MB、bge-small-zh ≈ 48MB（若换 EmbeddingGemma 2 则 ≈485MB）。
**结论：一律首启下载 + sha256 固定 + 断点续传**（ModelStore 已有下载器，补 sha256 即可）。理由：APK 从 24MB 涨到 1GB 不可接受；且应用商店对单包体积有限制。

### 4.3 哪些"自研"必须留

**判据：真机试出来的、开源没有的、换掉会掉分的。** 六项：隐私闸门、提醒引擎、噪声闸门、提醒置信度、自检面板、备份格式。
这六项的共同点：都建立在「星云 OS 的坑 + 真机误报基线」上，任何通用库都不含这些知识。

---

## 5. 复核实测：单测 6 红（状态文件过期）

命令：`JAVA_HOME=<JBR21> ./gradlew --no-daemon testDebugUnitTest` → **114 tests completed, 6 failed**

| 失败 | 原因 | 性质 | 修法 |
|---|---|---|---|
| `VideoAgentPlanTest` ×3 | `java.lang.RuntimeException: Method optJSONArray in org.json.JSONObject not mocked` | 测试设计缺陷（新视频代码用 `org.json`，JVM 单测里是空壳）——这正是 D-016 踩过的同一个坑 | 改用自写 `Js`（`BackupJson.kt`），或把 JSON 解析抽到可注入接口 |
| `VideoNoteSamplingTest` ×3 | `expected:<3100000> but was:<3100>` | **测试过期**，非代码 bug：实现已改为毫秒（`samplingStamps` 返回 ms，`grabFrames` 内部 ×1000），测试仍按微秒断言 | 改断言为 3_100L / 27_900L |

**顺带发现**：`CURRENT_STATE.md` 的「单测 98/98」写于这批视频单测加入之前。状态文件的数字必须跟着跑，否则下一次"全绿"又是假的。

---

## 6. 复用前必须核实（7 项，采用一条勾一条）

1. **License 原文**：Readability4J、CustomActivityOnCrash、xk-time、jieba-analysis、everit。⚠ 目前只有二手描述。
2. **16KB 页**：sherpa-onnx ≥1.12.9 已修；LiteRT-LM 随 LiteRT 推断需自测；sqlcipher-android 4.6.1+ 官方支持。
3. **模型权重协议**：SenseVoice 是 `FunASR MODEL_LICENSE`（不是 Apache-2.0，代码与权重两回事）；bge-small-zh-v1.5 为 MIT；EmbeddingGemma 2 为 Apache-2.0（2026-10-06）。
4. **desugar**：`xk-time` 用 java.time，minSdk 24 必须开 `coreLibraryDesugaring`；lib-recur 无此问题。
5. **内存实测**：jieba-analysis 词典加载；SenseVoice int8 峰值 600–770MB（16GB 无压力）。
6. **AGPL 边界**：RikkaHub fork 只在自用不分发时无开源义务——一旦给别人装，整包要开源。
7. **镜像可达**：新依赖必须能从腾讯云镜像解析（官方源在本机不可达）。

---

## 7. 需要你拍板的 4 件事（比 V2.0 的 §11 少，因为能自己定的已经定了）

| # | 事项 | 选项 | 建议 |
|---|---|---|---|
| 1 | 是否接受"换内核"这批改动（lib-recur / xk-time / Readability4J） | a) 一次批准三项 b) 逐项批准 c) 暂不动 | **a**：都是 B 类，替换面小、可单测回归，拖着只会继续在正则里加分支 |
| 2 | 聊天壳路线 | a) 先做 RikkaHub 注入 Demo b) 直接 fork c) 保留现状 | **a** |
| 3 | 端侧 AI 何时立项 | a) 现在（不等 A–E 全过） b) E 之后再开 | **a 的变体**：先只做 EmbeddingGemma 2（检索受益最直接），ASR/LLM 仍等 H |
| 4 | 单测 6 红怎么处理 | a) 我这轮直接修 b) 留到阶段 G | **a**：30 分钟的事，留着就是"假绿" |

---

*本文件是底稿，不是决策。所有"换"的动作按 V3.0 §8.1 走 B/C 类流程。*
