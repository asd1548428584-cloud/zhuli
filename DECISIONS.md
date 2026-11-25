# DECISIONS.md — 已确认决策

> 只记「已拍板」的事，未定的进 BLOCKERS.md。时间均为 2026-11-20。

| # | 决策 | 结论 | 依据 | 状态 |
|---|---|---|---|---|
| D-001 | git 提交身份 | 仓库级占位身份 `zhuli <dev@zhuli.local>`（不加 `--global`，不污染本机其他仓库） | 计划未覆盖，用户 2026-11-20 选定 | 已实施（c9f2c69） |
| D-002 | 签名方案（§11-1） | **b) 新正式 keystore + 卸载重装 + 数据回灌**；旧机（`C:\gzq\zhuli`，SHA-1 `c6f87049…`）已不可访问，a 方案不可行 | 已装 0.16 APK 实测签名 SHA-1 `c6f87049…`，本机 debug 证书 `1d8d610d…`，无法覆盖安装 | 已确认，模拟器演练 + 真机执行均通过 |
| D-003 | 回灌演练方式 | **模拟器演练**（零风险），不先动真机数据 | 计划 §11-1b 要求「先演练」但未规定方式，用户选定 | 已完成（AVD `zhuli_drill`） |
| D-004 | keystore 口令管理 | 随机 28 位，保存在仓库外 `C:\Users\a\.zhuli\keystore.pass`；仓库只记路径与指纹，不记口令 | 计划未覆盖，用户选定 | 已实施 |
| D-005 | 功能冻结线（§11-2） | 确认：阶段 A–E 验收通过前不新增功能域 | 计划 §1.4-1 | 已确认 |
| D-006 | 资料库与询问函（§11-10） | 询问函按计划 §5.6「用户经官方渠道提交」，AI 负责起草正文；资料库目录与清单落地由 AI 执行 | 计划 §5.6 标题原文 | 部分确认（目录结构待用户确认） |
| D-007 | 云端服务商与模型 | 基元律动 `https://tokenrhythm.studio/v1`，模型 **`egg-alpha-003`（免费档，0 元）**；`deepseek-v4.1-flash-1017`（1/4 元每百万 token）为备选 | 用户 2026-11-20 指示「先试免费，确定不能再用 deepseek」；实测免费档两轮真实出网均成功 | 已实施 |
| D-008 | 引入 JUnit（B 类改动） | **批准**：`testImplementation junit:junit:4.13.2`，先覆盖 SensitiveGuard/PrivacyRouter 等纯 Kotlin 逻辑 | 计划 §6.B 要求报批；§7.1 要求 JVM 单元层 | 已确认 |
| D-009 | 语音上云策略（§11-3） | **默认关 + 每次转写前明示确认**；音频不默认出机（D4 修法） | 用户确认，采计划倾向 | 已确认 |
| D-010 | Keystore 失败策略（§11-7） | **fail-closed**：加密不可用则拒绝存储并明示，绝不写明文（D6 修法） | 用户确认，采计划倾向 | 已确认 |
| D-011 | 工具返回值出网策略（§11-8） | **a+b 混合**：可脱敏字段打码后回灌；证件/卡号/验证码等红线命中则改本地回答（D1 修法） | 用户确认，采计划倾向 | 已确认 |
| D-012 | 提醒置信度口径（§11-4） | **照附录 C 原样**：明确日期+时刻 → 高（自动建）；含糊表述/只有日期或只有时刻 → 中（进「待确认」，不排闹钟）；无法确定未来性 → 低（只存不提醒） | 用户 2026-11-20 确认 | 已实施 |
| D-014 | 备份格式 v10（阶段 E / T-E2） | **按表遍历 `SELECT *`** 全表导出（inbox/reminders/ledger/embeddings/chat_log/skills/cloud_log），列名从游标读；BLOB 走 `b64:` 前缀；导出不含向量（明文导出生效） | v9 手工枚举字段漏了三张表，恢复后技能/向量/出网留档静默消失。SQLite 游标列名可自描述，加列无需改代码：<https://www.sqlite.org/c3ref/column_name.html> | AI 代决策（用户授权），已实施 |
| D-015 | 恢复保留原 id | **显式带 id `INSERT OR IGNORE`**，不重新自增；跨设备 id 被占时退化为「同刻同正文」判重 | ledger/reminders/embeddings 都用 `inbox_id` 挂回收件箱行；重排 id 会让账本挂到别的消息上。`INSERT OR IGNORE` 语义：<https://www.sqlite.org/lang_conflict.html> | AI 代决策（用户授权），模拟器逐字段比对通过 |
| D-016 | 备份编码不引第三方 JSON | 自写 200 行 `Js`（`core/BackupJson.kt`），**不引入 org.json 测试依赖** | android.jar 的 org.json 在 JVM 单测是空壳（调用即 not mocked），备份"能写不能读"等于没有备份：<https://developer.android.com/training/testing/local-tests> | AI 代决策（用户授权），10 条单测覆盖 |
| D-017 | 备份演练入口用广播 | `am broadcast -n com.zhuli.app/com.zhuli.core.BackupCommandReceiver --es cmd counts\|backup\|restore`（non-debuggable 包内部直接 return） | Activity extra 路径实测无效（已在栈里时 START_DELIVERED_TO_TOP 但无任何回调）；广播不依赖前台状态。停止态需 `-f 32`：<https://stackoverflow.com/questions/29978715> | AI 代决策（用户授权），模拟器验证 |
| D-018 | 恢复失败文案 | Keystore 类异常**不再显示「null」**，改为「备份密钥已不在本机…（见 ADR-0002）」 | 实测 `pm clear` 后恢复抛 AEADBadTagException 且 message=null；密钥随清数据/卸载销毁属系统设计：<https://developer.android.com/privacy-and-security/keystore> | AI 代决策（用户授权），模拟器复现并验证新文案 |

### 2026-11-24 · v16 接入决策

| # | 决策 | 结论 | 依据 | 状态 |
|---|---|---|---|---|
| D-019 | v16 补丁接入方式 | 独立分支 `v16-integration`（从 `master` @ `7288f2d` 切），只以 `changes.patch` 为来源，`git apply --check` 后 apply；不用 `files/` 覆盖 | 用户要求保护工作区、不覆盖 master；基线核对一致、工作区干净 | 已实施，无冲突 |
| D-020 | 补丁自带测试不可信 | 不信任 `02_VERIFY.md`「代码已写」声明，以实际编译/测试为准；补丁作者的未验证警告属实（首轮编译即失败） | 实测 `notify()` JVM 签名冲突 + 一批编译/运行错误 | 已确认，逐项修复 |
| D-021 | 副作用后抛异常的状态 | 记 `UNKNOWN`（副作用是否已发生未知），**不记 FAILED**；FAILED 仅代表确认无副作用、可安全重试 | 幂等正确性：把可能已发生的副作用标成可重试会导致重复执行 | 已实施，`ToolIdempotencyTest` 覆盖 |
| D-022 | 聊天请求 JSON 异常 | `OutgoingGate.scrub(requireMessages=true)`：结构异常即中止出网，不静默原样放行 | 防止闸门被畸形载荷绕过；fail-closed 原则 | 已实施，`OutgoingGateTest` 覆盖 |
| D-023 | 工具执行恢复粒度 | 按 `taskId` 隔离 `recoverRunningExecutions(now, taskId)`；进程启动做全局 recover、任务内按 taskId | 全局恢复会把别的任务的执行误判为本任务，破坏幂等 | 已实施，SQLite 测试覆盖 |
| D-024 | 多任务待审批 | 用户可用 `允许/拒绝 + 8 位任务号` 显式指定；多个待审批且无任务号时要求明确指定，不默认取最新 | 防止"允许"指向错误任务；审批恢复必须先 `Runtime.init()` | 已实施，`ZhuliAgentChatTest` 覆盖 |
| D-025 | 直接真机验收 | 用户 2026-11-24 要求跳过雷电模拟器，直接在努比亚 Z70 Ultra 验收 | 用户明确指示；真机 MSI 已连接且能覆盖安装 | 已执行，验收通过 |
| D-026 | 真机 logcat 为空的处理 | 该努比亚 ROM `logcat` 恒为空（OEM 限制），不视为应用故障；以 DB 状态 + 截图为权威证据，不编造日志 | 多 buffer 实测均空，但进程存活、界面正常、DB 迁移正确 | 已确认，已按此取证 |


## 云端配置档案（2026-11-20 19:41 实配）

| 项 | 值 |
|---|---|
| Base URL | `https://tokenrhythm.studio/v1`（OpenAI 兼容） |
| 当前模型 | `egg-alpha-003`（免费，支持 tools，实测 3/3 直连 + 2/2 应用内出网成功） |
| 备选模型 | `deepseek-v4.1-flash-1017`（1.0 / 4.0 元每百万 token，实测 3/3，延迟更低 1.2s vs 1.7s） |
| 不可用模型 | `neohorse-1.5-flash-preview`（标价 0 但实测 permission_error）；`egg-alpha`（旧 id，历史 400） |
| Key 存放 | 设备端 `shared_prefs/zhuli_secure.xml`（Keystore AES-GCM 密文）；PC 端副本 `C:\Users\a\.zhuli\cloud_api_key.txt`（仓库外，重装后可直接取用） |
| 换模型命令 | `adb shell am force-stop com.zhuli.app` 后 `am start -n com.zhuli.app/com.zhuli.app.MainActivity --es cloudmodel "<id>"` |

> 注意：`onCreate` 里 `--es q` 先于 `cloud*` 处理（MainActivity:230 vs 235），所以**配置与提问不能放在同一条 am start 里**，否则那一问会用旧配置作答。

## keystore 档案（口令不入库）

| 项 | 值 |
|---|---|
| 文件 | `C:\Users\a\.zhuli\zhuli-release.jks` |
| 口令文件 | `C:\Users\a\.zhuli\keystore.pass`（28 位随机，无特殊字符） |
| Alias | `zhuli` |
| 算法 | RSA 4096 / SHA384withRSA / 有效期 10000 天 |
| DN | `CN=zhuli, OU=zhuli, O=zhuli, L=Beijing, ST=Beijing, C=CN` |
| SHA-1 | `38:E9:6A:B6:CF:4F:A5:AA:73:78:E8:DB:9A:1E:F3:88:EC:13:A8:50` |
| SHA-256 | `22:88:08:35:44:9F:AC:79:A6:19:26:7A:EA:5C:79:13:62:62:1E:AA:2D:1E:23:D2:FB:F1:B8:DD:60:54:C3:E3` |
| 用途 | **签 debug 构建**（必须 debuggable，否则 `run-as` 不可用，回灌链路断） |
| 备份要求 | 该 jks 丢失 = 以后所有升级都无法覆盖安装，等同再次走卸载回灌。**必须与 T-A1 明文库分开备份** |

## 记录更正（2026-11-20 21:40）

**D-013 作废**。先前把「DeepSeek V4.1 Flash → 混元 4 Flash → GLM5.3 Flash → Space-Bunny」误当成
本 App 的云端降级链，写进了 `CloudClient`；用户澄清这是**给 WorkBuddy 助手（AI 自身）**定的模型使用顺序，
与 App 无关。相关代码已全部回滚（`git checkout 0aa5bdc^ -- CloudClient.kt AssistantPrefs.kt`，
删除 `CloudClientQuotaTest`），App 恢复原状。

教训：主语是「你」时，先确认指的是 AI 自身还是项目产物——这两者的实现位置完全不同（一个是界面设置，
一个是代码）。以及：用户给的名称（deepseek-v4.1-flash、hy4 flash）不要"顺手补全"，补全即篡改。

---

## D-019 schema 漂移：onCreate 必须建最新结构（2026-11-21，P0）

**现象**：清数据/全新安装后，`ingest` 抛 `table inbox has no column named repeat_rule`。
升级上来的库一切正常 —— 所以 64 条单测、真机库、恢复演练全绿，只有新装路径炸。

**成因**：`repeat_rule` 是 v9 通过 `ALTER TABLE` 加的，但 `onCreate` 里的建表语句只拼到
v3+v5 的列。于是存在两套 schema：新装 16 列、升级 17 列。而 `insert()` 的 ContentValues
里带着 `repeat_rule` → 新装第一条通知入库即失败。

**定法**（业界通行，两种实现二选一，这里都做了）：
1. `onCreate` 直接建**最新** schema —— 迁移链只服务老库；
2. `onOpen` 里按已知列清单 `addColumnIfMissing` 自愈 —— 任何来源的漂移（历史 beta 包、
   恢复回来的旧结构）都能自动补齐。

依据：`SQLiteOpenHelper` 的约定就是 onCreate 建全量、onUpgrade 只处理老库
<https://developer.android.com/reference/android/database/sqlite/SQLiteOpenHelper>；
「fresh database 必须建出代码会查询的每一列」也是社区反复强调的排查要点
<https://rottenwifi.com/how-to-fix-sqliteexception-no-such-table-in-an-android-app>。

**没选的路**：把 `insert()` 改成"列不存在就跳过" —— 那只是把错误藏起来，周期规则会静默丢失，
和这次要修的备份丢列是同一类 bug。

**验证**：模拟器 `pm clear` → 冷启动建库 → 7 张表列名与真机升级库**逐列一致**；
真实入库路径 `insert ok id=1`；带 `repeat_rule` 的备份恢复后 377 条全字段 + 向量字节逐行一致。

**约定（写给以后的自己）**：新增列要同时改三处 —— `onCreate` 的列拼装、`onUpgrade` 的
`addColumnIfMissing`、`ensureColumns` 的清单。README 已加这条。

---

## D-020 噪声治理改为「按 App 静音」，在采集侧就不收（2026-11-21，阶段 F）

**数据**（真库 375 条）：`org.ikuuu.vpn` 169 条、`com.bilibili.app.in` 144 条 —— 两个包占 **83%**。
而现有 NoiseGuard 黑名单（日历/时钟/计步）在这份数据上**命中 0 条**：它对"自己叫自己"有效，
对"人主动装的 App 天天推"完全无效。

**定法**：给用户一个「来源管理」面板，按 App 开关；关掉后在 `NoiseGuard.dropReason` 第一道判定就丢弃，
**不落库**。

**为什么不是「收下来再隐藏」**（Bundl 那种聚合器的做法）：
收件箱是长期记忆，噪声留在库里会污染检索候选集、提醒候选集，还得每次重建索引再筛一遍。
用户说「别收」就是别收。参考：Bundl 按 App 建规则 <https://github.com/eliasfloreteng/bundl>、
Message Mirror 在采集侧按 allowed packages 过滤 <https://github.com/Dragon-Born/message-mirror>。

**清理口径**：静音时可选择清掉已收历史 —— 删收件行 + 它们挂的提醒 + 向量，**账本不删**
（金额/分类/时间是自包含的财务记录，静音点错了不该顺手抹掉收支）。

## D-021 删消息必须连带删向量；同表自愈不能在 onOpen 里开库（2026-11-21）

**现象**：静音清掉 169 条后，`embeddings` 仍 120 条 —— 其中 **51 条是孤儿**（`inbox_id` 指向已删消息）。
语义检索会把这些 id 当候选，回查正文时查空。

**修法**：`deleteInboxByIds` / `deleteRowsOfPackage` 在**同一事务**里先删向量再删消息；
`onOpen` 每进程跑一次 `purgeOrphanEmbeddings()` 清历史遗留。

**踩的坑**：自愈一开始写成 `purgeOrphanEmbeddings()` 无参版，内部用 `writableDatabase` ——
在 `onOpen` 里调用会递归进 `getDatabaseLocked`，抛 `getWritableDatabase called recursively`，
异常被自己的 catch 吞掉，日志里只有一行 W，看起来"跑了但没效果"。
改成 `purgeOrphanEmbeddings(db)` 传当前句柄后正常（120 → 69，孤儿 0）。

**验证**：模拟器 `mute --pkg com.bilibili.app.in --purge` → inbox 377→233、embeddings 120→71、
**ledger 12 不动**；恢复备份后回到 377/120。


## D-022 阶段 G 自检：一屏讲明白，不打分、不假绿勾（2026-11-21）

**要解决的问题**：G 阶段验收不是"功能存在"，而是用户能自己讲明白八件事 —— 它知道什么 / 来源 /
哪些是推断 / 任务是否在跑 / 权限状态 / 哪些数据出网 / 提醒凭什么 / 怎么纠正。
这些信息原本散在七个弹窗里（来源、隐私自检、出网记录、白名单引导、待确认…），用户不会去拼。

**定法**：状态行可点 → 一屏自检，按链路排序 ① 采集 → ② 知道什么 → ③ 提醒 → ④ 权限与保活 → ⑤ 出网 → ⑥ 纠正。

**三个取舍**（参考 Permission Pilot / Privamatic / Android 12 Privacy Dashboard）：
1. **能查的才查，查不到的明说"需手动确认"** —— 开机自启没有公开 API，就写"需手动确认"，
   绝不画假绿勾（与 `StartupGuard` 的「自启动：需手动确认」同款原则）。
2. **不打分**。Privamatic 给 0–100 分适合"体检"，自己一个人的助理打分只会变成噪音；
   这里只给三档（✅/⚠️/❌）+ 每行写后果，总评取最高档（一条断了不能被九条正常的平均掉）。
3. **只读 + 直达系统页**。第三方 App 无权改权限（Permission Pilot 的 README 写明了），
   面板只负责指出哪没开，跳转交给系统设置。

**判断与取值分离**：`SelfCheck` 全是纯函数（什么算正常、阈值、文案），`SelfCheckAndroid.collect`
只负责摸 API 和库。这样"3 天没数据竟然显示绿灯"这类错误在单测就红，不用装到机器上才发现。

**当场抓到的 bug**：第一版把绝对时间戳直接传给 `ago()`，面板显示"数据外出 12 次，最近 692 个月前"
（1970 年至今）。改成 `ago(now - lastAt)`，并对不合理时间戳降级为"时间未知"——
自检面板自己先把自检的错抓了出来，算是个不错的实证。

## D-023 提醒通知要带"依据"，推断必须可见（2026-11-21，阶段 G）

**问题**：通知只有标题和时间，用户看到一个凭空的结论。排错了既不知道为什么，也不知道改哪。

**定法**：
- 通知展开区（BigText）在正文后追加 `依据：<basis，截 60 字>`；无依据返回空串，不硬编假话。
- 「待确认」点开一条不再直接排闹钟，先弹详情：想提醒什么 / 时间 / **凭什么** / 置信度 / 原文，
  三个动作：确认并排闹钟 / 忽略这条 / 关闭。
- 忽略写 `canceled` 而不是删除 —— 留痕给误报统计，同时 `hasCanceledSame` 靠它防止同一条明天又被排进来。

---

## D-024 装机到真机后连抓两个缺陷：来源面板名字错、提醒意图被吃掉（2026-11-21）

真机（Z70 Ultra / Android 16）装上 0.19 后逐项点界面，抓到两个不是模拟器能暴露的问题。

### 缺陷一：来源面板把第三方 App 显示成包名尾巴

B 站显示成 `in`、微信显示成 `mm`、QQ 显示成 `mobileqq`、酷狗显示成 `lite`。
库里 `app_label` 明明是 `bilibili / 微信 / QQ / 酷狗概念版`（采集侧记的）。

**成因**：`SourceRules.labelOf` 用 `PackageManager.getApplicationInfo(pkg, 0)` 现查名字。
Android 11+ 包可见性限制下，Activity 进程查**第三方**包抛 `NameNotFoundException`，
`catch` 后回退成 `pkg.substringAfterLast('.')`。自家 App 与系统包（电话短信存储）可见，所以只有第三方错。

**定法**：显示名三级降级 `系统名 → 库里 app_label → 包名尾巴`。
抽成纯函数 `SourceRules.pickLabel(pmLabel, dbLabel, pkg)`，4 条单测覆盖三条分支；
`topSources` 的 SQL 顺带 `MAX(app_label)` 取回来，不改表结构。

**没选的路**：加 `QUERY_ALL_PACKAGES` 权限 —— 只为了在面板上显示个名字，去要「查看所有应用」的权限，不值。

### 缺陷二：「3 分钟后提醒我喝水」被当成「查待办」

真机原话：「3分钟后提醒我喝水」→ 助理回「眼下没有明确的待办。有期限的事可以直接丢给我…」。
`ExtractPipeline.parseDeadline` 本来认得「N 分钟后」（`P_AFTER`），`ReminderConfidence` 也判它高置信，
缺的只是**路由**：`Q_TODO` 的正则里有「提醒」，抢在建提醒之前。

**定法**：新增 `Q_REMIND_CREATE`（提醒我/叫我/记得提醒/别让我忘），排在 `Q_TODO` 前，
且**必须同时解析出未来时间**才走建提醒分支；解析不出就落回原来的查询/闲聊
（「提醒我这周都干了啥」不能被误判成下提醒）。

建提醒走原管线：入库（`source=chat`）→ `ReminderEngine.scan` 判置信度并排闹钟，
不另写一套排闹钟 —— 否则去重、同刻已取消、噪声源这些护栏两个入口迟早走偏。
回话只说库里真发生的事：高置信「到点会响」/ 中置信「在待确认」/ 没建成就直说没建成。

**验证**：真机说同一句话 → `reminders` 多出一条 `喝水 / 11:53:42 / scheduled / high / 相对时刻（N 分钟后）`；
单测 89 → 95（`SourceRulesTest` 4 条 + `ReminderIntakeTest` 6 条）。


---

## 开源复用报告的处置口径（2026-11-21，用户交付调研成果）

`zhuli_开源项目复用评估报告_V1.0.md` 已入库为
`docs/技术资料库/10_架构与决策/开源项目候选清单_V1.0.md`。

**它是什么**：候选清单 + 复用四档（直接依赖 / 移植规则 / 仅设计参考 / 不建议）+ 一批「⚠ 待核实」项。
**它不是什么**：采用决策。按 V2.0 §8.1，引依赖一律 B 类、加密与架构改动一律 C 类 —— 报告本身不构成批准。

方向性结论先记下来（立项时按各自阶段再验）：

| 判断 | 依据 | 状态 |
|---|---|---|
| 端侧 AI 走 `sherpa-onnx`（ASR/VAD/TTS）+ `LiteRT-LM`（Embedding/Qwen3-0.6B）两条链 | 都有 16KB 支持；已下载的三个模型直接对口；合计约 7–10 人日 | 候选，H 阶段立项时先 Demo |
| Agent 循环**不引框架**（koog / langchain4j） | 6 工具 × 3 轮，引框架属依赖膨胀；只借设计 + 补 Schema 校验 | 方向确定，仍待 ADR 落笔 |
| 阶段 C 检索不接 trigram FTS | 本机 40 个高频词里 23 个是两字词，trigram 要求 ≥3 字；实测 trigram 表在本 ROM 建不出来 | 已实测，维持 LIKE 主力 |
| 整库加密用 `sqlcipher-android` 4.6.1+，禁旧包 | 旧 `android-database-sqlcipher` 2023 EOL 且不支持 16KB | 待 E 阶段 C 类批准 |
| RikkaHub fork 注入 core（G 阶段路线） | Kotlin/Compose/多模块、自带 MCP 与 Room；省 3–4 周 UI | 候选，未启动 |
| 保活不用任何灰产库 | 1px / 双进程 / 无声音乐在 Android 10+/16 失效或违规 | 确定 |

**报告里与现状冲突的一处，以实测为准**：报告 §1.2 建议「jieba 分词 + 普通 FTS5 表」。
本机从未验证过**普通** fts5（此前只试过 `tokenize='trigram'`，建表被吞），
所以这条路线的前置事实还缺一环 —— 等真要动检索时，先在真机上单独验一次裸 fts5 再谈接不接。

## D-025 取件码提醒：识别到码就提为高置信（2026-11-21 用户拍板）

**问题**：取件短信里没有「几点去取」，deadline 是 `Express.pickupDue(到站 + 2h)` 推的，
于是 `ReminderConfidence` 判「时间来源不明」→ 中置信 → 进「待确认」不响。
真机上刚收到的韵达取件短信就是这样躺在待确认里（340 号）。**主力场景没通电。**

**用户三选一，选 A**：识别到取件码 → 直接高置信排闹钟。

**定法**：`CourierPolicy.basisOf(code)` 纯函数，有码返回依据（`快递到站（取件码 8899，按到站 +2h 推定）`），
没码返回 null 交还原判。`ReminderEngine.scan` 里只对 `category=快递` 的行做这一次提级，
其余逻辑（去重、同刻已取消、噪声源）一律不动。

**依据如实写**：那是推定就写「推定」，不冒充短信原文 —— 与 D-023「推断必须可见」同一条原则。

**没选的路**：给所有快递行无条件提级 —— 只有「您的包裹已派送」这种没码的，本来就不确定要不要去取，
提级只会变成又一个误报来源。

**验证**：真机发一条取件通知 → `reminders` 多出
`韵达·…取件码 8899 / scheduled / high / 快递到站（取件码 8899，按到站 +2h 推定）`；
单测 95 → 98。测试数据用 `mute --purge` 清掉后 inbox 回到原状。

---

## D-026 记忆核心：事实是最小单位，历史只标不删（2026-11-24）

**问题**：旧实现的「记忆」= inbox 行 + 256 维字符哈希向量。没有主体-谓词-值，没有来源追溯，
用户纠正一条信息时只能再插一行，旧的错值永远留在检索结果里。

**定法**：DB v15 新增 `zhuli_entity` / `zhuli_fact` / `zhuli_fact_vec`。
- 一条事实 = 主体 + 谓词 + 值 + 置信度 + 来源类型/ID/原文 + 观测时间 + 状态；
- 纠错 = 旧行标 `SUPERSEDED`、新行写 `superseded_by` 指回去，**从不物理删除**；
- 同槽位冲突不偷偷选一条，交给记忆页让用户裁决；
- 抽取先规则后模型：热路径不能等推理，且规则错误可定位（真机曾因发热被投诉）。

**没选的路**：把聊天记录直接当记忆（不可纠错）；把冲突按时间自动覆盖（等于偷偷丢用户事实）。

## D-027 Agent 核心：工具、策略、执行三段分离（2026-11-24）

**问题**：旧 `Tools.run` 把工具声明、参数解析、权限弹窗、副作用混在一个函数里，
模型可绕过闸门；`CloudClient.chatWithTools` 还自己执行工具，是第二条旁路。

**定法**：
- `ToolRegistry` 只声明「我是谁、多危险」，每个工具有 JSON Schema、风险等级、同类授权键；
- `PolicyEngine` 独立判 ALLOW / ASK / DENY，硬禁止词连问都不问；
- `AgentLoop` 是唯一能触发副作用的路径；`WAITING_APPROVAL + pendingCall` 落 SQLite，
  进程重启后能原样恢复。同一目标 30 分钟内不重复开跑（真机曾排出 7 个重复闹钟）。
- 云端 `completeOnce` 只返回 tool_calls，**不执行工具**；旧 `chatWithTools` 保留给已验证的旧聊天链路。

**没选的路**：把普通模型 API 调用包装成 Agent；用框架（koog / langchain4j）—— 工具数量撑不起依赖。

## D-028 Embedding：Gemma 端侧语义 + 哈希降级，空间严格隔离（2026-11-24）

**定法**：`EmbeddingProvider` 抽象两种实现：
`GemmaEmbeddingProvider`（EmbeddingGemma / LiteRT / 768 维，仅 arm64 + 用户已下载模型时可用）
与 `HashEmbeddingProvider`（256 维字符 2-gram，**明确不是语义模型**）。
每条向量带 `providerId|dim` 空间标识，跨空间不比较；检索以字面命中为主、语义仅兜底
（真机基线：LIKE 40/40，trigram 17/40）。x86_64 模拟器自动降级哈希，不伪造语义结果。

**没选的路**：为向量检索引入 ONNX Runtime 新 native 依赖；把哈希向量宣传成语义embedding。
