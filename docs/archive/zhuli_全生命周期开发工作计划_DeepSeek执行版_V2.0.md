# zhuli 全生命周期开发工作计划
## DeepSeek V4.1 Flash 1017 执行版 V2.0（自包含总计划）

制定日期：2026-11-20
项目：zhuli 原生 Android 个人 AI 助理（包名 `com.zhuli.app`）

**本版地位**：V2.0 是唯一权威执行计划，**替代 V1.0 与 V1.1**。执行 AI 只需本文件 + 状态文件 + 当前步骤涉及的代码/资料，不必回读旧版计划。

**编制依据**（全部已核验）：
1. `zhuli.7z` 全量源码与交接文档只读审计（2026-11-20，关键结论逐项核验到代码行）；
2. 《zhuli_Z70Ultra_星云OS设备摸底报告》（2026-11-20 ADB 实测，28 份原始证据）；
3. `交接_2026-11-19/`、`交接_2026-11-20/`、两份 step README、需求文档_v1、选型报告；
4. 外部评审意见（ChatGPT 对话，用户 2026-11-20 提供）：隐私出网、提醒误报、先打磨后扩张、开发依据库四条意见，前三条已用代码证实并纳入缺陷清单。

---

# 第一部分 给执行 AI 的指令

## 0.1 你的角色

你是本项目的技术负责人、架构师、Android 开发者、AI 工程师、测试工程师和项目管理者。你的工作不是机械照文档写代码，而是：理解目标 → 核对现状 → 按阶段实施 → 真实验证 → 记录结果 → 依据反馈调整。用户决定产品方向、重大取舍和最终验收。

## 0.2 不可违反的原则

1. **先备份后动手**：任何可能影响装机、签名、数据库的操作之前，确认第六部分 T-A1 的明文拉库备份已完成且可读。当前备份体系有跨设备断链（缺陷 D7），明文拉库是唯一可靠逃生通道。
2. **现有成果优先**：core 分层与规则/模型分工已被真机验证有效，不得推倒重写；不得因架构偏好引入大改。
3. **事实与推测分开**：服务存在 ≠ 第三方可调用；权限已授 ≠ 功能可靠；编译通过 ≠ 真机可用；判定层自检通过 ≠ 请求体不泄漏（本项目已因此翻车，见 D1–D3）。
4. **本地优先、隐私优先**：能本地完成的优先本地；一切出网内容（含工具返回值、历史消息、语音）必须过闸门、脱敏、留档。
5. **标准 API 优先**：厂商私有服务（AIService、vendor.zte.aimodel、私有传感器等）一律按"第三方可调用性 D 级"处理，架构不得依赖，除非官方渠道确认开放（见 4.6 询问函）。
6. **小步实施、及时验证**：一次聚焦少量相关改动；建立基线 → 修改 → 测试 → 回归。
7. **测试贯穿全程**：每个功能设计时即定义测试方法；安全项一票否决。
8. **真实设备优先**：后台、通知、闹钟、定位、无障碍、耗电必须在 Z70 Ultra 验证；模拟器通过不算通过。
9. **人保留最终决定权**：第十一部分决策项、一切装机/卸载/迁移/出网策略变更/权限扩大，先说明并获用户确认。
10. **不虚报完成**：区分代码完成 / 编译通过 / JVM 测试通过 / 假端点测试通过 / 真机通过 / 长稳通过，逐级标注。
11. **文档持续更新**：唯一权威状态 = CURRENT_STATE.md；技术文档与项目决策记录严格分开（第四部分）。
12. **控制上下文成本**：每次会话先读状态文件和本阶段章节，按需读代码/资料，不重读全部文档。
13. **先打磨后扩张**：阶段 A–E 验收通过前不新增功能域（功能冻结线，用户已确认方向）。

## 0.3 工作闭环

```text
读取 CURRENT_STATE / NEXT_STEP
→ 识别当前阶段与步骤
→ 检查输入、资料前置（第四部分映射表）、备份状态
→ 分析、研究、提出方案
→ 需要确认的先问用户（第十一部分）
→ 实施（小步）
→ 自动化测试 / 构建 / 静态检查
→ 目标设备验证（第七部分对应方法）
→ 分析问题、修复、回归
→ 记录结果、更新状态文件
→ 判断是否满足下一步条件
```

不得擅自跨步骤。发现顺序不合理可提出调整，说明原因、影响、替代方案，经用户确认后更新路线图并记 ADR。

## 0.4 执行权限边界

**可自主完成**：只读浏览代码/设备（只读 ADB）、搜索官方文档、生成设计与资料卡片、写隔离测试、独立 Demo、不修改用户数据的构建、已批准范围内的局部修复。

**必须事先确认**：修改真实数据库或迁移、删除/覆盖用户数据、装机/卸载/换签名、改系统设置和权限、引入高敏感权限、上传真实数据、对外发送信息、不可逆操作、大规模重构、改变产品目标或隐私策略、重启手机做测试。

**只读 ADB 铁律**（ROM 实证）：读库必须 `adb exec-out run-as com.zhuli.app cat databases/zhuli_inbox.db > x.db`（`adb shell cat` 会塞填充字节毁库）；手机无 sqlite3，改库走 pull → PC 改 → base64 分块写回且先备份；logcat 恒空，排障走读库 + dumpsys + 壳内面板；验收前必须亮屏解锁；`adb reverse` 每次先重建。

---

# 第二部分 产品定位与总方针

## 1.1 定位

**一个理解手机信息、替用户管理日常事务、能长期积累个人记忆的本地个人信息中枢。** 不是功能越来越多的手机 AI 助手，不是 AI 对话入口。

三件必须做到非常好的事（一切工作围绕它们排序）：

| # | 核心承诺 | 对应能力 | 当前最大威胁 |
|---|---|---|---|
| 1 | 重要信息不会漏 | 通知/分享/短信采集完整性 | 敏感通知 ignore、无每 App 开关、噪声闸门误杀 |
| 2 | 该提醒的时候不会忘 | 提醒引擎 + 后台可靠性 | 星云 OS 冻结/查杀、重启广播被拦、误报损害信任 |
| 3 | 问过去的事真的找得到 | 全文 + 向量混合检索 | FTS 索引恒空（D5）、哈希伪向量非语义 |

## 1.2 产品原则

- **本地优先**：原始数据默认存手机；规则明确的任务本地代码；本地模型适合的任务本地推理；云端用于本地不足或用户明确要求。
- **用户控制数据**：来源、存储位置、处理方式可知；可查看、修改、删除、导出、备份；可撤销授权、关闭来源。
- **规则与模型分工**：规则做确定性处理（分类/时间/金额/快递/周期）；模型做语义理解、推理、组织回答、工具选择；模型输出必须经结构校验、权限校验、业务校验。
- **只收不擅自发**：不代发信息、不代付款、不批量点屏幕；屏幕操作只做"用户确认后单步"。
- **可解释可追溯**：每条提醒追溯来源；每次出网留脱敏摘要；推断区分事实/模型推断/待确认。

## 1.3 权限三层模型（工具与自动化必须遵守）

| 层 | 范围 | 控制方式 |
|---|---|---|
| 读取 | 读用户已授权数据（记忆/账本/快递/日历） | 授权矩阵 + 工具内校验 |
| 执行 | 建提醒、写日历、存备忘、单步点击 | Schema 校验 + 幂等 + 审计；单步操作前说明目标 |
| 高风险 | 删除/批量修改、对外发送、付款、改系统权限、持续定位/录音/截屏、不可逆操作 | 明确二次确认；模型理解意图 ≠ 获得执行权限 |

## 1.4 本周期总方针

1. **功能冻结线**：阶段 A–E 验收通过前不新增功能域。冻结期内只做缺陷修复、可靠性加固、已实现功能复验、体验打磨。
2. **区分已实现/已验收/规划中**：配置预留（ModelStore 目录）、死代码（skills 表）、伪实现（哈希向量）不得宣称为能力。
3. **隐私优先于功能**：D1–D6 先于一切新功能，也先于数据库加密。
4. **提醒宁缺毋滥**：误报一次对信任的损害大于漏报一次；置信度三级机制（附录 C）。
5. **资料为开发服务**：先盘点、再按阶段定向收集（第四部分），不囤积文档。

---

# 第三部分 审计基线（2026-11-20 实测，替代一切旧文档状态表）

## 2.1 工程事实

| 项目 | 实测值 | 证据 |
|---|---|---|
| 结构 | `zhuli-assistant-step2/core/`（41 个纯 Kotlin 文件，无 UI 依赖）+ `zhuli-assistant-step2-build/`（Gradle 验收壳）；壳以 srcDirs 直引 core，非复制 | app/build.gradle.kts:35-38 |
| 版本 | **源码 versionCode=18 / "0.18-video"**；手机已装 APK 与交接文档均 0.16-full / 16；源码领先且未装机未验收 | app/build.gradle.kts:16-17 |
| 构建 | AGP 8.7.3 / Kotlin 2.0.21 / JDK 17 / compileSdk=targetSdk 35 / minSdk 24 / buildTools 35.0.0；外部 Gradle 8.11.1，**无 wrapper** | build.gradle.kts |
| 依赖 | 仅 kotlin-stdlib、mlkit text-recognition-chinese 16.0.1（bundled，不依赖 GMS）、vision-common 17.3.0；无协程/Compose/SQLCipher/推理运行时 | app/build.gradle.kts:46-51 |
| 签名 | **无 signingConfig，默认 debug 签名；keystore 不在包内**，旧电脑 `~/.android/debug.keystore` 未迁移 → 新电脑构建必然签名不符 | 全包搜索 |
| native | 无自有 .so；abiFilters 含 arm64-v8a + armeabi-v7a（目标机仅 arm64，v7a 应清理）；ML Kit native 的 16KB 页兼容未验证 | app/build.gradle.kts:22-24 |
| 版本控制 | **无 .git**，演进靠两份 README 手工日志 | find |
| 测试 | **零自动化测试**；fixtures 仅 1 张假账单图；唯一自检是 PrivacyRouter.selfTest 8 条样例（只测判定，不测脱敏与请求体） | 全工程搜索 |
| UI | MainActivity 单文件 1278 行巨型 Activity，纯 View 代码，承担全部面板 | MainActivity.kt |
| 数据库 | SQLite v11，7 业务表 + 1 FTS 虚表；迁移逐版本 if 补列，**每条 ALTER 各自吞异常，无回滚无报错** | InboxDatabase.kt:41,295-356 |
| Manifest | 11 权限；**无 FGS 声明**；allowBackup=true；网络安全配置全局禁明文仅回环例外 | AndroidManifest.xml |
| 云端 | OpenAI 兼容端点（基元律动 tokenrhythm.studio/v1，qwen3.8-flash；egg-alpha 免费档 400 不可用）；Key 存 Keystore AES-GCM | 换机交接 §5 |

## 2.2 版本与文档混乱（阶段 A 治理）

源码 0.18-video/18 ≠ 装机 0.16-full/16 ≠ build README 止于 v0.12 ≠ 交接说明"库 v4" ≠ CloudClient UA "0.9.2" ≠ Backup JSON version=9（且不含 skills/embeddings/cloud_log）。设备描述多处 "MyOS / Android 15 / 无 GMS" 全部过时。隐私口径旧文"敏感不出机" vs v0.15 起"脱敏后上云"。工具数旧文 5 个实际 6 个。地理围栏旧文"未做"实际已实现待复验。**处理原则：历史文档只加修订注记，唯一权威状态 = CURRENT_STATE.md。**

## 2.3 目标设备基线（以设备摸底报告为准）

- 努比亚 Z70 Ultra（NX733J / PQ84A01），Nebula AIOS 2.0.14MR2，**Android 16 / API 36**（vendor 分区仍 Android 15 基线，适配以 framework API 36 为准）
- Snapdragon 8 Elite（SM8750），约 16GB 内存，**仅 arm64-v8a，16KB 内存页**（一切 native 依赖必须 64 位 + 16KB 对齐）
- **GMS 完整可用** → FCM、Fused Location、Health Connect 基础设施可行（旧"无 GMS"结论作废）
- zhuli 已生效：精确闹钟（被系统采纳为唤醒源）、Doze 白名单、通知监听（运行中）、日历、前台定位、录音、短信
- **最大风险**：superkiller + cgroup_freezer_v2 + DefendManagerService + AutoLaunch 多层后台管控；白名单 ≠ 长期存活；12 项真机专项未做（7.5）
- 厂商 AI/健康/私有传感器均签名级，按 D 级处理
- Health Connect 组件在（APEX + controller），但 `enabled=0` 疑点 + 努比亚健康/华为健康是否写入未验证
- PC 环境：Intel Arc A770 16GB（本地推理/服务端走 OpenVINO 生态评估；NVIDIA 资料仅在用 N 卡服务器时适用）

## 2.4 功能状态矩阵

**A 已真机验收（证据较强）**：通知/分享/链接入库、20 条实弹分类、对话四问法、人格切换、提醒准点（息屏 0 秒偏差）+ 日历双写 + 完成/稍后、保活引导、自激回路修复（1002→148 自愈 + 25 次压测熔断）、快递识别（751 条真实短信扫出 7 条）、模型工具调用（真实云跑通）。

**A- 曾验收但被审计推翻，必须重验**：
- "敏感脱敏后上云实证"（v0.15）——redact 不覆盖地址/公司+岗位（D3）、chat 发原文（D2）、工具返回值不过闸门（D1）；
- "云端路由敏感 0 出网"（第 9 步）——假端点仅测判定层，未测完整请求体。

**B 已实现待复验**：周期顺延、晨间简报、勿扰、撤回、断网补跑、加密备份/恢复、CSV/JSON 导出、App 锁、小组件、快捷指令、设置面板、云端语音转写（未用真实 Key 验）、视频抽帧 OCR、无障碍读屏+单步点击、地理围栏、哈希混合检索、冲突检测/待办跟踪。

**C 明确未做**：整库加密、端侧语音运行时、每 App 通知开关、群聊摘要、快递主动查单号、真语义 Embedding、视频音轨转写、FGS/FCM 保活。

**D 死代码/名不副实**：skills 表（CRUD 齐全零调用方）；ModelStore（可下载 EmbeddingGemma/SenseVoice/Qwen3-0.6B，无运行时、无 sha256 校验）；inbox_fts（索引恒空）；embeddings（FNV 哈希 256 维伪向量，代码自认非语义）。

**暂缓**：剪贴板监听、TTS、CSV 批量导入、云备份/WebDAV。

## 2.5 ROM 实证坑清单（开发与验收必须遵守）

1. logcat 恒空 → 读库 + dumpsys + 壳内面板排障；
2. BOOT_COMPLETED 被拦 → 开 App sweep 兜底 + 白名单引导（重启恢复不能只靠广播）；
3. `setExactAndAllowWhileIdle` 息屏限流 → 代码已 `setAlarmClock` 优先（ReminderEngine.kt:204），保持；
4. 读库必须 exec-out；手机无 sqlite3；
5. `am start --es` 仅冷启动生效（栈内走 onNewIntent 已实现）；验收先亮屏解锁，息屏进程冻结截图全黑约 18KB；
6. adb 服务反复重启 → reverse 每次重建；
7. 系统快递卡片 `com.zte.mifavor.suggest` 受 signature|privileged 保护，别浪费时间；
8. FTS5/trigram 在该 ROM 建表失败被静默降级（反而掩盖了 D5）；
9. 换签名必须卸载（丢数据，先备份）；`pm uninstall -k` 留半卸载态；
10. 模型不支持 tools 会 400 → 已自动降级纯聊天；换模型先看出网记录 note；
11. 午夜 23:00–07:00 提醒静音（可关）；
12. 构建钉 build-tools 35.0.0、阿里云镜像、中文路径需 overridePathCheck。

---

# 第四部分 缺陷与风险清单（工作主线来源）

> 修复按第八部分分级：标【C】属系统级，须专项方案 + 用户批准 + 副本演练。

## P0（阶段 A/B 立即处理）

| # | 缺陷 | 证据 | 影响 | 修复方向 | 验证 |
|---|---|---|---|---|---|
| D1 | 工具返回值/历史/system 不过隐私闸门，role:tool 直接回灌下轮请求 | CloudClient.kt tools() 循环 | 问句不敏感但查出的记忆/快递/账本含地址、取件码、金额 → 原文出机 | 每条出网 message 逐条 decide；敏感工具结果按用户决策处置（§11-8）；闸门下沉到 postJson 前统一校验 | 假端点全请求体断言（7.2） |
| D2 | chat() 组包用原文，verdict.text 被丢弃 | CloudClient.kt:64-78 | 纯聊天脱敏失效 | 改用 verdict.text（对齐 tools() :109 正确写法） | 同上 |
| D3 | redact() 不覆盖地址、公司+岗位；判定认敏感、脱敏不替换 → 原文上云且标 redacted=true | SensitiveGuard.kt redact()；PrivacyRouter.decide() 末行 | 住址、单位+职位出机；留档同漏 | 补 ADDRESS/ORG+ROLE 占位符（ORG 需组合判定防误伤）；判定-脱敏一致性单测 | 单测 + 请求体断言 |
| D4 | 语音转写绕过闸门，原始音频直发，仅靠设置开关 | CloudClient.kt transcribe() | 音频可含任何敏感内容 | 默认关 + 每次明示确认（§11-3）+ 留档补全；阶段 H 本地 ASR 根治 | 关闭态零请求断言 |
| D5 | FTS 外部内容表无写入/触发器/rebuild → 索引恒空；FTS 可用时 MATCH 空结果不抛异常，LIKE 兜底永不触发 | InboxDatabase.kt:159-168；InboxSearch.kt:17-22 | "找得到"承诺直接受损；当前 ROM 建表失败靠 LIKE 掩盖，ROM 升级即暴露 | 增删改触发器 + 存量 rebuild + 空结果复核兜底 | 检索评测集双形态（7.3） |
| D6 | Keystore 失败静默降级明文存 Key | SecureKeyStore.kt:67-69 | Key 明文落盘 | fail-closed 拒绝存储并明示（§11-7） | 异常路径测试 |
| D7 | 备份 Keystore 密钥加密跨设备不可解，交接文档却指导"换机拷 backups 恢复"；JSON version=9 缺 3 表 | Backup.kt:44-58,149 | 换机/卸载 = 数据永久丢失风险 | 短期 T-A1 明文拉库 + 文档纠正；中期口令派生密钥可迁移加密包【C】+ 格式升 v11 全表 | 两次安装间恢复演练 |
| D8 | 无 git + debug keystore 未迁移 → 新电脑无法覆盖安装，卸载触发 D7 | 全工程 | 任何装机尝试都可能不可逆丢数据 | T-A1 备份 → git init → 找回旧 keystore 或经批准换签名（先演练回灌） | 备份可读核对 + git log + 决策记录 |

## P1（阶段 C–E）

| # | 缺陷 | 证据 | 修复方向 |
|---|---|---|---|
| D9 | 迁移吞异常、无事务回滚、失败无感知 | InboxDatabase.kt:295-356 | 事务化 + 失败保护模式 + 迁移前自动备份 + v9 分支顺序修正 |
| D10 | 提醒无置信度分级，识别出未来时间即自动建提醒 | ExtractPipeline→ReminderEngine | 附录 C 三级机制 + 存量误报回顾 |
| D11 | 无 FGS/FCM，保活仅闹钟 + sweep + 白名单 | Manifest；设备报告 §6 | 先 12 项专项量化现状，再定 FGS 类型与 FCM（阶段 H） |
| D12 | allowBackup=true 明文备份外流面 | Manifest:32 | 关闭或排除 databases，与 D7 统筹 |
| D13 | PIN 固定盐 SHA-256 | Backup.kt:318-321 | 随机盐 + 迭代哈希；文档明示 PIN 边界 |
| D14 | ModelStore 校验仅大小≥60%，sha256 预留未调用 | ModelStore.kt:72,129,155 | 启用 sha256；与运行时接入一起验收 |
| D15 | 零自动化测试 | 全工程 | 阶段 B 起 JVM 单测 + 假端点集成测试 |
| D16 | 文档版本混乱 | §2.2 | 阶段 A 统一版本说明 + 修订注记 |

## P2（阶段 F–H 择机）

哈希伪向量正名与真 Embedding（H）；skills 接线或删除（H，先定口径）；MainActivity 拆分（G，按需不顺手重构）；abiFilters 去 v7a；UA 版本硬编码；每 App 通知开关与群聊摘要（F）；16KB native 验证（H 随运行时引入）。

---

# 第五部分 开发依据库（资料工作流）

## 5.1 原则

1. **先盘点，再定向收集**：起点是附录 A（现有盘点）与附录 B（收集清单种子）。禁止一上来下载几百份文档。
2. **按阶段即时收集（just-in-time）**：每个阶段开工前只收集该阶段"资料前置"列出的条目（5.4 映射表）；P0 资料在对应阶段前完成，不提前囤积。
3. **技术文档与项目决策严格分开**：资料卡片回答"这个接口怎么用、有什么限制"；ADR/DECISIONS 回答"zhuli 为什么选它、放弃了什么、还有什么没解决"。资料卡片不得写成决策，决策不得只存链接。
4. **证据分级**：每条关键结论标 🟢已验证（本机实测/官方文档明文）/ 🟡推断 / 🔴未验证。厂商能力一律先按 🔴/D 级，官方确认后才升级。
5. **官方来源优先**：Android Developers / Google / Qualcomm / 努比亚-ZTE 官方 / 开源项目官方仓库。NVIDIA 不是手机端推理主路线（手机骁龙、PC Intel Arc，分别评估）。
6. **资料服务验收**：收集每份资料时必须回答"它影响哪个阶段哪个任务的哪个判断"，答不出就不收集。

## 5.2 目录结构（映射现有文件，不为格式搬动）

```text
zhuli_技术资料库/
├── 01_设备与系统/        ← 现有：设备摸底报告 + adb_evidence/（28 份）
├── 02_Android开发/       ← 缺：Android 16 行为变更、后台/FGS/闹钟/通知/无障碍等官方指南
├── 03_厂商与芯片/        ← 缺：努比亚/星云OS 开放能力确认；Qualcomm AI Hub/QNN；Intel OpenVINO（PC）
├── 04_AI与Agent/         ← 现有：选型报告（部分）；缺：Tool Calling/Agent 工作流/记忆系统/RAG
├── 05_本地推理/          ← 缺：LiteRT/LiteRT-LM、llama.cpp、ONNX Runtime Mobile、sherpa-onnx、SenseVoice
├── 06_数据与记忆/        ← 缺：SQLite/FTS5 官方、Room、SQLCipher、sqlite-vec、记忆治理原则（附录 D 为起点）
├── 07_第三方接口/        ← 缺：Health Connect、Calendar Provider、Geofencing、FCM；邮件/地图/天气（P2）
├── 08_安全与隐私/        ← 现有：PrivacyRouter/SensitiveGuard 代码即部分依据；缺：Keystore 官方、SQLCipher 设计、OAuth/PKCE
├── 09_测试与性能/        ← 缺：AndroidX Test/UI Automator/Macrobenchmark/Perfetto/16KB 指南/电池分析
└── 10_架构与决策/        ← 现有：选型报告、交接说明、需求文档_v1；ADR/ 待建；服务清单（5.3）待建
```

## 5.3 记录格式

**资料卡片**（每份关键资料一张，存对应分类目录）：
```text
标题 / 官方来源与URL / 发布或更新日期 / 适用API·SDK·设备 /
对应模块与阶段 / 关键限制（尤其 Android 16 行为变化、16KB/arm64、License）/
核验状态（🟢🟡🔴）/ 采用结论（用/不用/待定+理由）/ 影响的判断
```

**服务清单**（10_架构与决策/服务清单.md，每个外部服务一行）：
```text
服务名 / 类型（云LLM·本地LLM·Embedding·ASR·TTS·天气·地图·推送）/
API文档URL / Tool Calling与上下文限制 / 价格与限流 / 数据是否上传与隐私政策 /
离线能力 / 不可用时的替代方案 / 当前状态（在用·评估·弃用）
```
首批必录：基元律动 qwen3.8-flash（在用）、SenseVoice Small（已下载无运行时）、EmbeddingGemma-300m（候选）、Qwen3-0.6B-int4.litertlm（候选）、FCM（候选）。

## 5.4 收集优先级与阶段映射（资料前置表）

| 优先级 | 资料域 | 服务于 | 收集时机 |
|---|---|---|---|
| P0 | Android 16 行为变更、后台/FGS/Doze/精确闹钟/通知监听/开机广播 | 阶段 D 提醒可靠性 | D 开工前 |
| P0 | 星云 OS 后台管理/自启动/权限说明 + 官方开放能力确认（5.6 询问函） | 阶段 D/H；架构边界 | A 期间发出询问（异步），D 前汇总 |
| P0 | SQLite/FTS5 官方（外部内容表同步机制） | 阶段 C 检索修复 | C 开工前（量小，1–2 份） |
| P0 | Keystore、SQLCipher 设计与迁移、备份密钥管理 | 阶段 E 数据安全 | E 开工前 |
| P0 | Tool Calling/结构化输出/Agent 工作流/记忆系统（含权限中间层） | 阶段 B 闸门设计、H 工具扩展 | B 开工前精读 1–2 份权威指南即可，其余 H 前补 |
| P1 | Qualcomm AI Hub/QNN、LiteRT/LiteRT-LM、llama.cpp、ONNX Runtime Mobile、16KB 页指南 | 阶段 H 本地推理 | H 立项时按候选框架定向收集 |
| P1 | sherpa-onnx/SenseVoice/VAD | 阶段 H 端侧语音 | H 语音立项时 |
| P1 | Embedding 模型（EmbeddingGemma）、sqlite-vec/向量检索 | 阶段 H 真语义检索 | H 检索立项时 |
| P1 | Health Connect、Calendar Provider、Geofencing、FCM | 阶段 F/H 数据接入与保活 | 对应任务前 |
| P1 | AndroidX Test/UI Automator/Macrobenchmark/Perfetto/电池分析 | 阶段 D 长测、G 性能 | D 长测前 |
| P2 | 邮件（Gmail API/IMAP）、地图、天气 | 未来扩展 | 有明确需求再收集 |
| P2 | 厂商私有 AI/健康/自动化 SDK | 仅官方确认开放后 | 询问函有正面答复再深入 |
| PC | OpenVINO / Intel Arc 推理 | 仅 PC 侧服务端/评测用 | 需要 PC 推理时 |

## 5.5 现有资料盘点结论（详见附录 A）

已有：设备摸底报告（强，🟢）、adb 原始证据 28 份、需求文档_v1、选型报告（含历史取舍）、开发环境搭建指南、两份 step README（验收日志）、两批交接文档、源码本身（最权威依据）。
**最大缺口**：① 星云 OS 官方开放能力确认（只有存在性证据，无开放性证据）；② Android 16 行为变更系统梳理；③ FTS5 外部内容表官方机制（D5 修复直接需要）；④ SQLCipher 迁移与密钥管理设计；⑤ Agent/记忆治理的系统资料（现有实现是经验产物，缺理论对照）。

## 5.6 努比亚官方渠道询问函（阶段 A 发出，用户经官方渠道提交）

```text
致努比亚/中兴开发者支持：
我方在 Z70 Ultra（NX733J，Nebula AIOS 2.0.14MR2，Android 16）上开发个人助理类第三方应用
（包名 com.zhuli.app），咨询以下能力是否对普通第三方应用开放、有无公开 SDK/文档/权限申请流程：
1. 系统 AI 能力（AIService / vendor.zte.aimodel 的 BERT/BGE/LLM/Voice 模型）是否有第三方调用接口？
2. 自动化编排（orchestrationtools）与 AI 助手（com.zte.aiassistant）是否开放意图/技能接入？
3. 健康数据（nubia.system_healthy / cn.nubia.healthy）是否向 Health Connect 写入？有无第三方读取接口？
4. 后台运行：superkiller/cfreezer/AutoLaunch 的白名单机制，第三方应用如何申请稳定后台与自启动？
   精确闹钟（setAlarmClock）在息屏长周期的调度策略是否有文档？
5. 星云 OS 后续版本（2.0.14MR2 之后）对第三方后台/闹钟/通知监听/无障碍的行为变更是否有适配说明？
若无开放计划，请明确告知，我方将仅依赖标准 Android API。
```
**答复前，架构不得依赖任何厂商私有服务。** 答复与证据存入 03_厂商与芯片/，结论记 ADR。

---

# 第六部分 全生命周期路线图与阶段任务卡

## 6.0 总览

| 阶段 | 名称 | 核心目标 | 资料前置 | 预计工作量 | 关键验收 |
|---|---|---|---|---|---|
| R | 开发依据库（贯穿） | 盘点→定向收集→卡片化 | 附录 A/B | 每阶段 0.5h–0.5d | 清单状态更新、卡片入库 |
| A | 工程安全基线 | 备份/版本控制/构建/统一口径 | 无（现有资料够） | 0.5–1d | 备份可读、git 存在、构建复现、用户决策拍板 |
| B | 隐私出网全链路 | 兑现核心承诺 | Tool Calling/闸门设计 1–2 份 | 1–2d | 假端点全请求体测试全绿 |
| C | 检索可靠性 | "找得到" | FTS5 官方机制 | 1–2d | 评测集双形态通过 |
| D | 提醒可靠性 | "不会忘" | Android 16 后台/闹钟 + 星云 OS + 测试工具 | 2–4d + 长测 | 置信度机制 + 12 项专项报告 |
| E | 数据安全【C】 | 加密/备份/迁移 | Keystore/SQLCipher | 3–5d | 副本演练 + 跨设备恢复 + 明文消失 |
| F | 采集完整性 | "不会漏" | NLS/Health Connect 等 | 2–3d | 漏收率数值 + 开关停采实证 |
| G | 交互体验 | 可持续使用 | 按需 | 按需 | 用户试用闭环 |
| H | 能力扩张 | 本地 AI/技能/保活 | 按立项定向（5.4） | 每项独立 | 入场条件 + 各项独立验收 |

> V1.0 的"阶段 1 需求 / 阶段 2 调研 / 阶段 3 架构"不重做：需求见需求文档_v1，架构沿用 core 分层与选型报告；仅阶段 H 引入新运行时时做专项调研 + ADR。阶段顺序调整须用户确认 + ADR。

## 6.A 阶段 A：工程安全基线（最高优先）

**目标**：让后续一切改动有备份、有版本、有统一事实口径。
**输入**：本文件第三/四部分；换机交接 §2/§6；设备已连接。
**资料前置**：无新增。R 工作流启动：建资料库目录、把附录 B 清单落地为 `10_架构与决策/资料收集清单.md`、发出 5.6 询问函（用户提交）。

| 任务 | 内容 | 验收 |
|---|---|---|
| T-A1 数据逃生备份 | `adb exec-out run-as com.zhuli.app cat databases/zhuli_inbox.db > zhuli_inbox_2026-11-20.db`；另拉 `shared_prefs/zhuli_secure.xml` 留档；PC 端 sqlite 打开核对 7 表行数与 v11 | 备份文件可读、行数记录入 CURRENT_STATE。**此步完成前禁止任何装机/卸载/改库** |
| T-A2 版本控制 | git init + .gitignore（build/、.gradle/、local.properties、*.db、keystore、密钥、备份）+ 初始提交（源码+文档） | git log 存在；敏感文件确认未入库 |
| T-A3 签名决策 | 用户拍板（§11-1）：a) 旧机找回 debug.keystore；b) 新正式 keystore + 卸载重装 + 数据回灌（先演练）。决策记 DECISIONS + ADR | 方案确认；b 方案须先完成回灌演练 |
| T-A4 构建复现 | 补 gradle wrapper 8.11.1；按换机交接 §2 配 JDK17/SDK35/build-tools 35.0.0；assembleDebug 成功；记录环境路径 | APK 产出；环境入 CURRENT_STATE |
| T-A5 统一版本说明 | 建 CURRENT_STATE.md（第十部分种子）；§2.2 所列文档逐份加修订注记；明确"源码 0.18 未装机未验收" | 五份状态文件就位；旧文档注记完成 |
| T-A6 源码差异盘点 | 梳理 v0.16→v0.18 改动面（VideoNote/ShareReceiver/无障碍/ReminderEngine 等 11-20 下午修订），列入 D/G 复验清单 | 差异清单入 NEXT_STEP 附件 |

**阶段验收**：T-A1–A6 全过 + 用户确认 §11 决策项。
**风险与回滚**：T-A1 失败（run-as 不可用等）→ 立即停止后续、报告用户，不得尝试卸载；git 初始化不影响任何现有文件。

## 6.B 阶段 B：隐私出网全链路修复（P0）

**目标**：任何出网内容（用户输入、工具返回、历史、system、语音）都过闸门、脱敏完备、留档一致。
**资料前置**：Tool Calling/结构化输出权威指南 1–2 份（附录 B 04 类）；现有 PrivacyRouter/SensitiveGuard 代码。
**引入 JUnit 依赖属 B 类改动，随本阶段一并报用户批准。**

| 任务 | 内容 | 验收 |
|---|---|---|
| T-B1 修 D3 | redact 补 ADDRESS/ORG+ROLE 占位符（ORG 组合判定防误伤）；判定-脱敏一致性单测 | 单测全绿：reason 命中的每一类，redact 后原文消失 |
| T-B2 修 D2 | chat() 改用 verdict.text，对齐 tools() 写法 | 单测 + 请求体断言 |
| T-B3 修 D1 | 出网消息统一闸门：system/history/tool 逐条 decide；敏感工具结果按 §11-8 决策处置；**闸门下沉到 postJson 前最后一道统一校验**（防未来新调用方绕过） | 多轮工具调用样本请求体零敏感原文 |
| T-B4 修 D4 | 语音默认关 + 每次转写前明示确认 + 留档补任务上下文（策略经 §11-3 确认） | 关闭态零请求；开启态留档完整 |
| T-B5 修 D6 | Keystore 失败 fail-closed（或用户选定策略） | 异常路径测试：失败时不落明文 |
| T-B6 假端点全请求体测试 | 重建 mock_openai.py（记录完整 body 到文件）+ adb reverse；敏感样本集（地址/公司+岗位/工具返回敏感/多轮历史/红线/语音关）；断言敏感原文零出现、红线零请求、cloud_log 与实际一致；脚本与样本（脱敏）入库 fixtures/privacy/ | **本阶段核心验收：脚本全绿且可重复执行** |
| T-B7 selfTest 升级 | 判定 + 脱敏覆盖 + 请求体三层自检，壳内面板可见 | 面板演示三层结果 |

**阶段验收**：T-B6 全绿；v0.15 隐私验收结论以新证据重新签发（A- → A）；出网留档面板抽查一致。
**风险**：工具结果全拦截会降低可用性 → 处置策略必须用户拍板，不得自行放宽；修复本身属局部改动（A/B 类），但策略变更属隐私策略变更须确认。

## 6.C 阶段 C：检索可靠性——"找得到"

**资料前置**：SQLite FTS5 官方文档（外部内容表同步：触发器/rebuild 机制，sqlite.org/fts5.html）。

| 任务 | 内容 | 验收 |
|---|---|---|
| T-C1 修 D5 | inbox 增删改触发器同步 FTS + 存量 rebuild + "FTS 空结果且库非空时复核 LIKE"兜底；PC 端 sqlite 模拟 FTS 可用/不可用双形态 | 双形态下检索均返回正确结果；rebuild 有幂等保护 |
| T-C2 检索评测集 | 30–50 条脱敏真实查询：精确事实/时间范围/多条汇总/同名消歧/新旧冲突/纠正后查询/不存在信息/低置信推断；记录基线正确率、召回率、来源可追溯率、"没有找到"正确率 | 评测集入库可重复跑；基线数值记录 |
| T-C3 哈希向量正名 | 代码注释/面板/文档统一改"关键词加权 + 哈希近似召回"，不得称语义检索 | 全文搜索无"语义向量"误导表述 |
| T-C4 复验冲突检测/待办跟踪 | B 类接线项真机复验 | 验收记录入矩阵 |

**阶段验收**：评测集通过率 ≥ 基线且 D5 关闭；"不存在信息"必须答"没有找到"，不得编造。

## 6.D 阶段 D：提醒可靠性——"不会忘"

**资料前置**：Android 16 行为变更（后台/闹钟/通知）、FGS 类型与限制、Doze/白名单官方指南、星云 OS 后台管理说明（询问函答复）、Macrobenchmark/Perfetto/电池分析入门。
**涉及重启/新授权的操作先列风险获用户批准（§11-9）。**

| 任务 | 内容 | 验收 |
|---|---|---|
| T-D1 置信度三级 | 附录 C 机制落地：高→自动提醒；中→待确认事项（简报/面板一键确认）；低→只存不提醒；加 confidence 与依据字段（迁移随 T-E1 统筹先加列）；阈值经 §11-4 确认 | 中置信样本不再直接打扰；待确认超期自动归档 |
| T-D2 存量误报回顾 | 拉库统计已建提醒来源与命中率，清理明显误报，记录误报率基线 | 误报率基线数值 + 清理记录 |
| T-D3 星云 OS 12 项专项 | 7.5 清单逐项执行（存活 1/4/8/24h、闹钟误差、重启恢复、FGS 摸底、通知完整性、无障碍、Health Connect、MediaProjection、FCM、16KB、厂商 SDK）；产出《后台可靠性实测报告》 | 逐项"通过/有条件通过/失败/不适用"+ 原始数据 |
| T-D4 B 类提醒项复验 | 周期顺延/勿扰/撤回/断网补跑/简报/小组件/快捷指令，用 v0.18 源码 APK（签名按 T-A3） | 逐项验收记录 |
| T-D5 地理围栏/无障碍复验 | 按设备报告 B/C 级预期；ROM 拦截则降级方案（Intent/分享/通知操作替代）入文档 | 复验记录 + 降级路径 |

**阶段验收**：准点率与误差分布有数值；误报进待确认而非打扰；12 项逐项结论；长测覆盖四档时长，单次成功不宣布可靠。
**并行**：长测占用真实时间，可与阶段 E 并行推进。

## 6.E 阶段 E：数据安全与备份【C 类专项】

**前置**：T-A1 备份在案；专项方案 + 风险评估经用户批准；一切迁移先副本演练。
**资料前置**：Keystore 官方、SQLCipher 设计/迁移/性能文档、Android 备份机制、16KB 页指南（native 引入时）。

| 任务 | 内容 | 验收 |
|---|---|---|
| T-E1 修 D9 | 迁移事务化 + 失败保护模式（只读 + 提示）+ 迁移前自动备份 + v9 分支顺序修正；v10→v11 副本演练成功/失败/回滚三路径 | 三路径演练记录；失败不毁库 |
| T-E2 修 D7 | 备份格式升 v11 全表；新增口令派生密钥（PBKDF2/Argon2）可迁移加密包，与 Keystore 本地备份并存；**两次安装/两台设备间恢复演练**；纠正换机交接 §8 | 跨设备恢复演练通过；口令丢失不可恢复已明示用户 |
| T-E3 修 D12/D13 | allowBackup 关闭（或排除 databases）；PIN 随机盐 + 迭代哈希 | 备份通道验证；PIN 迁移兼容 |
| T-E4 SQLCipher【C】 | 独立 Demo（副本库）验证迁移/性能/断电恢复/密钥丢失 → 用户批准 → 真库迁移（先 T-E2 可迁移备份）→ 验收 | 明文文件消失；错误密钥不毁原数据；性能回退可接受；备份恢复链路仍通 |
| T-E5 16KB 验证 | SQLCipher native 引入时同步验证 arm64 + 16KB 对齐（设备专项 11） | 真机安装运行通过 |

**阶段验收**：未授权敏感数据不能出网（B 阶段成果回归）；Key 不明文落盘/入日志；备份可跨设备恢复；错误密钥不破坏原数据；删除/导出/撤权符合约定；云端调用可追溯。

## 6.F 阶段 F：采集完整性——"不会漏"

**资料前置**：NLS 官方（含敏感通知限制）、Health Connect（若接入）、Calendar Provider。

任务：通知完整性对照（系统通知栏 vs 入库，量化 `RECEIVE_SENSITIVE_NOTIFICATIONS: ignore` 影响）；每 App 通知开关；NoiseGuard 熔断阈值复核（防误杀）；群聊摘要重新评估（用户定可做可不做）；来源管理面板；权限撤销后停采验证。
**验收**：主流 App 抽样漏收率有数值；开关/撤权后确证停采；升级重启后数据状态正确。

## 6.G 阶段 G：交互体验（P2，按需）

收件箱/对话体验优化；B 类剩余复验收尾（视频/App 锁/导出/设置面板）；MainActivity 拆分仅在阻碍改动时做（B 类，说明范围与回滚，不顺手重构）。
**验收**：用户日常试用反馈闭环；用户能演示理解：助理知道什么/来源/哪些是推断/任务是否运行/权限状态/哪些数据出网/提醒原因/如何纠正删除记忆。

## 6.H 阶段 H：能力扩张（P3）

**入场条件**：A–E 验收通过 + 用户确认解冻。每项独立立项 + 资料定向收集 + ADR + Demo 先行：

1. **端侧语音**：sherpa-onnx 接 SenseVoice（已能下载）；先验 arm64/16KB/内存/耗时/中文准确率；解锁后语音默认本地，D4 根治。
2. **真语义 Embedding**：EmbeddingGemma（LiteRT/MediaPipe）或经闸门云端 embedding；替换哈希伪向量、重建 embeddings 表、评测集对比提升；记录模型版本入记忆元数据。
3. **skills 参数化技能**：先定"规则冲突/优先级/误伤"验收口径（README 论证：A 参数化✅ / B 组合⚠️ / C 生成代码❌），再接线；否则删除死代码。
4. **FGS + FCM 保活**：基于 T-D3 数据定 FGS 类型；FCM 离线唤醒兜底（GMS 已确认）；不承诺永不冻结。
5. **本地 LLM Demo**：Qwen3-0.6B-int4.litertlm 最小验证（加载/首 token/生成速度/内存峰值/发热/耗电/恢复），不凭规格表认定可用。
6. **Agent 工具扩展**：按 1.3 三层权限 + 附录 E 任务链范式扩展工具；每工具定义名称/参数与返回 Schema/权限层/确认要求/超时/幂等/错误码/审计/测试。

---

# 第七部分 测试与验证体系

## 7.1 测试分层

| 层 | 内容 | 工具/方法 | 起点阶段 |
|---|---|---|---|
| JVM 单元 | SensitiveGuard 判定+脱敏一致性、PrivacyRouter 全分支、ExtractPipeline 时间/金额/周期、ReminderEngine 去重/顺延状态机、Backup 判重 upsert | JUnit（core 纯 Kotlin 可直接测） | B |
| 数据层 | CRUD/事务/索引/迁移三路径/并发/损坏恢复 | PC sqlite + 副本库演练 | C/E |
| 集成 | 输入→存储、模型→工具→出网、任务→提醒 | 假端点 + 真机 | B |
| Android 组件 | Service/Receiver/通知/权限/FGS | 真机 + dumpsys | D |
| 真机功能 | 每项功能正常/边界/异常/拒权/断网/进程死亡/重复数据/恢复 | 亮屏解锁态手工 + 读库断言 | 各阶段 |
| 长稳 | 1/4/8/24h 存活、多日任务、耗电、泄漏 | dumpsys + 电池统计 | D |
| 安全 | 越权/敏感出网/日志/备份/密钥/导出 | 假端点请求体 + 文件检查 | B 起持续 |
| 性能 | 启动/检索延迟/模型加载/内存 | Macrobenchmark/Perfetto（按需） | G/H |

## 7.2 假端点全请求体测试（隐私验收唯一可信方式）

- mock_openai.py 记录每个请求完整 body → 样本集驱动（用户输入敏感/工具返回敏感/历史敏感/红线/语音关）→ 断言：敏感原文零出现、红线零请求、cloud_log 与实际出网一致；
- 样本脱敏入库 fixtures/privacy/，可重复回归；
- **判定层自检不能替代请求体测试**——D1/D2/D3 全部是判定通过但请求体漏的，这是本项目实证教训。

## 7.3 检索评测集

30–50 条脱敏查询（精确事实/时间范围/多条汇总/同名消歧/新旧冲突/纠正后/不存在/低置信推断）。指标：正确率、召回率、来源可追溯率、错误记忆率、无依据回答率、"没有找到"正确率。FTS 修复前后、真 Embedding 前后各跑一次留基线。没有依据时必须允许答"不确定/没有找到"。

## 7.4 提醒验收

准点率与误差分布（多闹钟、息屏、长周期）；误报率（用户取消且标"不该提醒"）逐周下降；周期顺延/撤回/勿扰/断网补跑状态一致；重启恢复实证（广播被拦时 sweep 兜底有效）；重复触发不重复提醒。

## 7.5 真机 12 项专项（设备报告 §10，阶段 D 执行）

1. 后台存活 1/4/8/24h（ps + dumpsys activity processes，记 adj）
2. 后台定位连续性与耗电（移动熄屏轨迹断点）
3. 精确闹钟误差分布（多 RTC_WAKEUP，设定 vs 实际）
4. 重启后 BootReceiver 与任务恢复（需批准重启）
5. 无障碍点击/滑动/窗口读取（ROM 二次拦截？）
6. 通知监听完整性（对照系统通知栏，敏感通知缺失？）
7. Health Connect 写入读回 + 努比亚健康/华为健康同步
8. MediaProjection 授权后捕获 10s 校验
9. 各 FGS 类型逐项启动（尤其 SPECIAL_USE/SYSTEM_EXEMPTED）
10. FCM 深睡到达率与时延
11. 16KB/64 位 native APK 安装运行
12. 厂商 SDK 开放确认（询问函答复）

结果标记：未测试/测试中/通过/有条件通过/失败/不适用。**星云 OS 每次升级后重跑关键项，不默认升级前能用升级后也能用。**

## 7.6 质量指标基线（建立项目自己的数值）

核心流程成功率、提醒准点率/误差分布、解析准确率、检索正确率/召回率、工具调用成功率、错误操作率、敏感数据误出网数（目标恒 0）、崩溃率、后台完成率、内存峰值、耗电、响应时间。
**一票否决**：未经授权敏感数据出网、未经确认执行高风险操作 = 阻断发布，不被平均分掩盖。

## 7.7 回归纪律

每阶段结束必跑：隐私请求体测试 + 检索评测集 + 提醒核心链路真机冒烟 + 构建。修改任何出网/检索/提醒代码后加跑对应专项。

---

# 第八部分 中途修改与迭代机制

## 8.1 修改分级

**A 类 局部修复**（解析 Bug/UI 文案/模块内校验）：分析 → 修改 → 测试 → 记录。
**B 类 模块级调整**（换模型/调度方式/适配器/检索策略/加工具/引入测试依赖）：先说明现状问题与证据 → 比较方案 → 评估模块/数据/权限/性能/测试影响 → 说明迁移与回滚 → 用户确认 → 实施 → 回归。
**C 类 系统级调整**（整库加密/大规模迁移/核心重构/换推理运行时/改出网策略/引入高敏感权限）：专项方案 + 风险评估 + 用户明确批准 + 独立 Demo 或副本演练先行；**禁止在唯一真实数据上试错**。本项目已标 C 的：T-E2 备份密钥体系、T-E4 SQLCipher、阶段 H 各运行时引入。

## 8.2 每次修改必须回答（十问）

为什么改（证据）？不改会怎样？解决什么？影响哪些模块？影响数据库/用户数据吗？增加权限/网络/耗电/内存吗？引入新依赖吗？如何测试？如何回滚？需要用户确认吗？

## 8.3 ADR 决策记录（存 10_架构与决策/ADR/）

```text
编号 / 日期 / 问题背景 / 当前方案 / 候选方案 / 比较依据（附证据与限制）/
最终选择 / 理由 / 放弃其他方案的原因 / 影响模块 / 风险 / 验证方式 /
回滚方案 / 状态（提议·已确认·已实施·已废弃）
```
首批 ADR：签名方案、工具结果出网策略、语音上云策略、备份密钥体系、SQLCipher 时机、厂商能力不依赖原则。

## 8.4 需求变化处理

用户改想法时：判断局部调整还是方向变化 → 保留仍有效的已确认需求 → 标记被替代的旧决策（ADR 状态改"已废弃"）→ 评估对已完成模块影响 → 重大变化形成新版需求与路线图供确认。不为旧计划拒绝合理变化，也不把临时想法自动当正式需求。

## 8.5 Bug 迭代闭环

```text
真实使用 → 收集问题（用户反馈/读库/壳内面板/测试）
→ 分类（Bug/体验/性能/安全/新需求）→ 评估严重度
→ 复现（注意 ROM 坑：亮屏解锁、reverse 重建、exec-out）
→ 根因分析 → 最小修改方案 → 回归风险评估
→ 必要时用户确认 → 实施 → 单元/集成/真机回归
→ 更新版本与文档 → 观察结果
```
不为修一个问题顺手重构整个模块；确需重构说明收益/风险/范围/回滚。

## 8.6 外部评审与审计意见处理

用户转来的评审意见（如本次 ChatGPT 三条）：先逐条用代码/实测核验真伪 → 证实的入缺陷清单定级 → 证伪的记证据回复用户 → 不得未核验就照单全收或照单全驳。

---

# 第九部分 发布、观察与持续迭代

## 9.1 发布前检查（每次装机/发版）

版本号与 versionCode 递增、变更日志、构建可复现（wrapper）、签名一致（T-A3 决策）、权限说明、数据库迁移演练、备份可恢复（含跨设备）、核心回归全绿（7.7）、真机冒烟、隐私请求体测试、出网留档抽查、回滚方案（旧 APK + 旧库备份保留）。最终用户确认。

## 9.2 发布后观察（本地诊断为主，不默认上传个人数据）

崩溃（壳内诊断面板）、后台失败、提醒误差、通知漏收、重复数据、工具调用失败、云端失败、本地模型性能、耗电、纠错频率、检索失败、权限撤销。用户主动提供日志为辅。

## 9.3 版本与回滚

版本号 ↔ 数据库版本对应表入 CURRENT_STATE；每次发版保留上一版 APK 与迁移前库备份；回滚 = 装旧 APK + 恢复旧库（先验证备份可读）。ROM 升级视为外部变更事件：重跑 7.5 关键项 + 核心回归。

---

# 第十部分 状态管理与会话接续

## 10.1 五个状态文件（阶段 A 建立，每步更新，存工程根目录）

**CURRENT_STATE.md**（唯一权威状态）种子：
```text
源码版本：0.18-video / 18（未装机未验收）
装机版本：0.16-full / 16 ｜ 数据库：v11
设备：NX733J / Nebula AIOS 2.0.14MR2 / Android 16 / arm64+16KB / GMS 完整
当前阶段：A ｜ 下一步：T-A1 明文拉库备份
已知问题：D1–D16（见计划第四部分）；隐私验收结论已作废待重验（A-）
最近备份：（T-A1 后填路径 + 行数核对）
构建环境：（T-A4 后填 JDK/Gradle/SDK 路径 + 签名方案）
资料库状态：清单已建 / 询问函已发（待答复）
```
**NEXT_STEP.md**：步骤编号、目标、输入文件、执行任务、验收标准、是否需确认。
**DECISIONS.md**：已确认决策（首批 = §11 各项拍板结果）。
**BLOCKERS.md**：阻塞 + 责任方 + 解除条件。首批：旧机 debug.keystore 未迁移（用户）；厂商 SDK 开放性待官方答复；外部评审对话尾部未复制完整（如有补充结论需并入）。
**SESSION_LOG.md**：每次会话工作/修改/测试/结果/问题/接续位置。

## 10.2 新会话启动协议

1. 读 CURRENT_STATE + NEXT_STEP + BLOCKERS；
2. 向用户说明：当前阶段、当前步骤、上一步结论、本次目标；
3. 按需读当前步骤涉及的代码与资料卡片，不重读全部文档；
4. 发现状态文件与代码/设备实际不符 → 先核实更新状态，再继续。

## 10.3 步骤模板

**开始新步骤先输出**：
```text
当前阶段 / 当前步骤 / 步骤目标
已有依据（代码·文档·测试·已确认事实·尚不确定内容）
本步骤输入（需读文件 / 需研究资料）
本步骤工作（1. 2. 3.）
本步骤暂不处理
预期输出 / 验收标准
风险与需用户确认事项
```
**步骤完成报告**：
```text
步骤名称 / 完成状态（通过·部分通过·未通过·阻塞）
实际完成 / 修改文件 / 新增文件 / 执行命令
测试结果（分层标注）/ 真机验证
发现的问题 / 尚未解决 / 风险
是否影响后续步骤 / 文档更新 / 下一步建议 / 需用户确认
```

---

# 第十一部分 需要用户决策的事项（阶段 A 汇报时一次性提出）

| # | 决策 | 选项与倾向 |
|---|---|---|
| 1 | 签名方案 | a) 旧机找回 debug.keystore（最稳，倾向）；b) 新正式 keystore + 卸载重装 + 回灌（须先备份 + 演练） |
| 2 | 功能冻结线 | 确认"A–E 验收前不新增功能域" |
| 3 | 语音上云策略 | a) 维持开关；b) 默认关 + 每次明示确认（倾向 b，H 本地 ASR 根治） |
| 4 | 提醒置信度阈值 | 附录 C 三级划分与中置信规则是否认可；待确认事项展示位置 |
| 5 | SQLCipher 时机 | a) 阶段 E 串行（倾向）；b) 与 C/D 并行 |
| 6 | 备份可迁移方案 | 口令派生密钥加密包是否采纳；口令自管、丢失不可恢复需明示 |
| 7 | Keystore 失败策略 | a) fail-closed 拒绝存 Key（倾向）；b) 明文降级 + 显著告警 |
| 8 | 工具返回值出网策略 | a) 可脱敏的脱敏回灌；b) 敏感改本地摘要/占位；c) 命中即终止云端改本地。倾向 a+b 混合：可脱敏脱敏，红线本地兜底 |
| 9 | 重启/长测授权 | T-D3 涉及重启、可能新授权（FGS/后台定位），批准范围与时间窗口 |
| 10 | 资料库与询问函 | 确认 5.2 目录结构；5.6 询问函由用户经努比亚官方渠道提交 |

---

# 第十二部分 完成的定义

**功能稳定完成**须同时满足：需求明确；架构与权限边界明确；实现完成；单元测试通过；集成测试通过；适用时目标设备验证通过；异常路径通过；隐私安全检查通过；日志诊断可用；文档更新；用户确认体验符合预期。

**整体稳定**还要求：核心场景连续运行；提醒可靠且误报受控；记忆可追溯且不编造；云端边界清晰（请求体级证据）；加密备份跨设备恢复可靠；权限可控；异常可定位；升级可迁移且失败可回滚；ROM 升级有回归机制。

**最终目标**：让 zhuli 在真实手机、真实生活和长期使用中，持续、稳定、可信地做到——**重要信息不会漏、该提醒的时候不会忘、问过去的事真的找得到。**

---

# 附录 A 现有资料盘点表

| 资料 | 位置 | 强度 | 缺口/动作 |
|---|---|---|---|
| 设备摸底报告 + 28 份 ADB 证据 | Desktop/zhuli' | 🟢 强 | 12 项动态测试未做 → 阶段 D |
| 需求文档_v1 | 交接_2026-11-19 | 中 | 进度注记过时 → T-A5 修订 |
| 选型报告 | 交接_2026-11-19 / zhuli | 中 | 含历史取舍（RikkaHub/MLC/OCR/快递降级）；设备描述过时 → 修订 |
| 开发环境搭建指南 | 同上 | 中 | 与换机交接 §2 对照更新 |
| step2 README（39KB） | zhuli-assistant-step2 | 强（验收日志） | 止于部分版本；状态以 CURRENT_STATE 为准 |
| build README（30KB） | zhuli-assistant-step2-build | 中 | 止于 v0.12 → 加历史注记 |
| 交接说明（33KB） | 交接_2026-11-19 | 中 | "库 v4"等过时 → 修订注记 |
| 换机交接 + 项目介绍 | 交接_2026-11-20 | 中 | 设备/版本/备份指导错误 → T-A5 纠正 |
| 源码本身 | core/ + 壳 | 🟢 最权威 | 本计划第三/四部分已审计 |
| mock_openai.py | 旧机 D:/zhuli-dev/（未随包） | — | 阶段 B 重建 |
| 旧机 debug.keystore | 旧机 ~/.android/（未随包） | — | §11-1 决策 |

# 附录 B zhuli 技术资料收集清单（种子，阶段 A 落地为 10_架构与决策/资料收集清单.md）

> 状态：待收集 / 收集中 / 已卡片化 / 已验证 / 不适用。收集后必须填资料卡片（5.3），"影响的判断"必填。URL 为官方入口；标 ⚠ 的需先搜索核实再访问。

## 02_Android开发（P0，阶段 D 前）
| 资料 | 官方来源 | 优先级 | 服务阶段 |
|---|---|---|---|
| Android 16 行为变更（全部应用） | developer.android.com/about/versions/16/behavior-changes-16 | P0 | D |
| Android 16 概览与 targeting 变更 | developer.android.com/about/versions/16 | P0 | D |
| 后台工作总览（Doze/待机桶/限制） | developer.android.com/develop/background-work | P0 | D |
| FGS 类型与权限要求 | developer.android.com/develop/background-work/services/fgs/service-types | P0 | D/H |
| Doze 与电池优化 | developer.android.com/training/monitoring-device-state/doze-standby | P0 | D |
| 精确闹钟调度 | developer.android.com/develop/background-work/services/alarms/schedule | P0 | D |
| NotificationListenerService | developer.android.com/reference/android/service/notification/NotificationListenerService | P0 | D/F |
| AccessibilityService | developer.android.com/guide/topics/ui/accessibility/service | P1 | D/H |
| 权限模型与运行时权限 | developer.android.com/guide/topics/permissions/overview | P0 | B/E |
| 16KB 页大小兼容指南 | developer.android.com/guide/practices/page-sizes | P0 | E/H |
| SAF / MediaStore | developer.android.com/guide/topics/providers/document-provider | P1 | F |
| MediaProjection | developer.android.com/media/grow/media-projection | P1 | D 专项 |
| UsageStats | developer.android.com/reference/android/app/usage/UsageStatsManager | P2 | 按需 |

## 03_厂商与芯片（P0 询问函 + P1 推理）
| 资料 | 来源 | 优先级 | 服务阶段 |
|---|---|---|---|
| 努比亚/星云 OS 开发者文档与开放能力 | ⚠ 搜索"努比亚开发者平台 / nubia developer / 星云OS SDK"，经 5.6 询问函官方确认 | P0 | A 发出/D 汇总 |
| 星云 OS 后台/自启动/闹钟调度说明 | ⚠ 官方渠道 + 社区实测佐证（佐证只作 🟡） | P0 | D |
| Qualcomm AI Hub（模型与设备支持） | ai-hub.qualcomm.com | P1 | H |
| QNN / SNPE SDK 文档 | ⚠ 搜索"Qualcomm QNN SDK documentation"（docs.qualcomm.com） | P1 | H |
| Intel OpenVINO（PC Arc A770） | docs.openvino.ai | P2 | PC 侧需要时 |
| NVIDIA 部署资料 | 仅在使用 N 卡服务器时收集 | P2 | 按需 |

## 04_AI与Agent（P0）
| 资料 | 来源 | 优先级 | 服务阶段 |
|---|---|---|---|
| Tool Calling / Function Calling 指南 | platform.openai.com/docs/guides/function-calling（OpenAI 兼容口径） | P0 | B/H |
| 结构化输出 / JSON Schema | platform.openai.com/docs/guides/structured-outputs | P0 | B/H |
| Agent 工作流与工具安全（权限中间层/确认/失败恢复） | ⚠ 搜索 Anthropic "building effective agents"、OpenAI agents 指南等权威工程文章 | P0 | B/H |
| 长期记忆系统（类型/置信度/冲突/来源追溯） | 附录 D 为项目起点；⚠ 补充 MemGPT/Letta 等公开论文与文档作对照 | P0 | C/H |
| RAG / 混合检索实践 | ⚠ 搜索权威 RAG 评测与混合检索资料 | P1 | H |
| 基元律动 API 文档（当前云服务商） | 服务商官方（用户持有）；服务清单必录 | P0 | B |

## 05_本地推理（P1，阶段 H 立项时定向）
| 资料 | 来源 | 优先级 | 服务阶段 |
|---|---|---|---|
| LiteRT（原 TFLite） | ai.google.dev/edge/litert | P1 | H |
| LiteRT-LM / MediaPipe LLM Inference | github.com/google-ai-edge/LiteRT-LM ；ai.google.dev/edge/mediapipe/solutions/genai/llm_inference | P1 | H |
| llama.cpp（GGUF/ARM64/Android 构建） | github.com/ggml-org/llama.cpp | P1 | H |
| ONNX Runtime Mobile | onnxruntime.ai/docs/tutorials/mobile/ | P1 | H |
| sherpa-onnx（ASR/VAD/TTS/唤醒词，Android） | github.com/k2-fsa/sherpa-onnx ；k2-fsa.github.io/sherpa-onnx/ | P1 | H 语音 |
| SenseVoice 模型卡 | github.com/FunAudioLLM/SenseVoice | P1 | H 语音 |
| EmbeddingGemma | ai.google.dev/gemma/docs/embeddinggemma ；huggingface.co/google/embeddinggemma-300m | P1 | H 检索 |
| Qwen3-0.6B（GGUF/litertlm 候选） | github.com/QwenLM/Qwen3 ；huggingface.co/Qwen/Qwen3-0.6B | P1 | H |

## 06_数据与记忆（P0 部分）
| 资料 | 来源 | 优先级 | 服务阶段 |
|---|---|---|---|
| FTS5 官方（外部内容表/触发器/rebuild） | sqlite.org/fts5.html | **P0** | **C（D5 修复直接需要）** |
| SQLite 官方（事务/UPSERT/ WAL） | sqlite.org/docs.html | P0 | C/E |
| Room（是否引入的对照依据） | developer.android.com/training/data-storage/room | P1 | E/H |
| SQLCipher 设计与 Android 集成 | zetetic.net/sqlcipher ；github.com/sqlcipher/sqlcipher | P0 | E |
| sqlite-vec（向量扩展候选） | github.com/asg017/sqlite-vec | P1 | H |
| 记忆治理原则 | 附录 D（项目自有起点） | P0 | C/H |

## 07_第三方接口（P1/P2）
| 资料 | 来源 | 优先级 | 服务阶段 |
|---|---|---|---|
| Health Connect | developer.android.com/health-and-fitness/health-connect | P1 | D 专项/F |
| Calendar Provider | developer.android.com/guide/topics/providers/calendar-provider | P1 | F |
| Fused Location / Geofencing | developers.google.com/android/reference/com/google/android/gms/location/FusedLocationProviderClient | P1 | D/F |
| FCM（送达限制/优先级/深睡） | firebase.google.com/docs/cloud-messaging | P1 | D 专项/H |
| 华为健康 Health Kit（中国区限制） | ⚠ developer.huawei.com 搜索 Health Kit；先确认第三方读取可行性 | P2 | 按需 |
| Gmail API / IMAP | developers.google.com/gmail/api | P2 | 扩展 |
| Google Calendar API | developers.google.com/calendar/api | P2 | 扩展 |
| 地图 Intent / Maps Platform | developers.google.com/maps | P2 | 扩展 |
| 天气（Open-Meteo / 和风） | open-meteo.com ；dev.qweather.com | P2 | 扩展 |

## 08_安全与隐私（P0）
| 资料 | 来源 | 优先级 | 服务阶段 |
|---|---|---|---|
| Android Keystore | developer.android.com/privacy-and-security/keystore | P0 | B/E |
| 加密最佳实践 / EncryptedSharedPreferences 对照 | developer.android.com/security-best-practices（⚠ 核实当前路径） | P0 | E |
| OAuth 2.0 / PKCE | datatracker.ietf.org/doc/html/rfc7636 | P1 | 外部服务接入时 |
| 云端模型数据隐私政策 | 各服务商官方（服务清单必录字段） | P0 | B |
| 数据分级/删除/导出/撤权设计 | V1.0 §10 + 本计划 1.3（项目自有） | P0 | B/E |

## 09_测试与性能（P1）
| 资料 | 来源 | 优先级 | 服务阶段 |
|---|---|---|---|
| AndroidX Test / Espresso | developer.android.com/training/testing | P1 | B/D |
| UI Automator | developer.android.com/training/testing/other-components/ui-automator | P1 | D |
| Macrobenchmark / Baseline Profiles | developer.android.com/topic/performance/benchmarking/macrobenchmark-overview | P1 | G |
| Perfetto | perfetto.dev | P1 | D/G |
| 电池与耗电分析（Battery Historian） | github.com/google/battery-historian | P1 | D |
| 后台稳定性测试方法 | 7.5 项目自有清单 + 官方 Doze 文档 | P0 | D |

## 10_架构与决策（持续）
现有：选型报告、交接说明、需求文档_v1、本计划。待建：ADR/、服务清单.md、资料收集清单.md（本附录落地）、已验证结论台账（🟢 证据索引）。

# 附录 C 提醒置信度三级机制（阈值待 §11-4 确认）

```text
提取结果
├─ 高置信：明确日期+时刻+事件结构完整（"周三下午三点评审"→唯一未来时间）
│   → 自动建提醒（现有链路），通知保留来源追溯
├─ 中置信：时间可解析但语义含糊（"下周给我""明天左右""月底前"）、
│   分类置信度低、来源为群聊转述
│   → 写入"待确认事项"，晨间简报/面板列出，一键确认后才建提醒
└─ 低置信：仅疑似时间词、无法确定未来性、噪声源
    → 只入库保存，不提醒不打扰
```
配套：每条提醒存 confidence + 判定依据；用户纠正回写为样本定期回顾误报率；待确认超 N 天自动降级归档；验收口径 = 误报率逐周下降且高置信准点率不回退。

# 附录 D 记忆管理原则（项目起点，阶段 C/H 细化）

| 记忆类型 | 例子 | 管理方式 |
|---|---|---|
| 基础事实 | 用户长期使用的设备、工号 | 长期保存，用户纠正优先 |
| 偏好 | 喜欢的表达方式 | 持续修正，保留修正历史 |
| 习惯 | 通常几点开始工作 | 根据新数据更新，标注统计依据 |
| 事件 | 上周参加的会议 | 可检索、有时间范围、来源追溯 |
| 临时状态 | 今天要处理的事 | 过期后归档，不自动续期 |
| 推断 | 可能不喜欢某种安排 | 标置信度 + 推断标记，不得当事实；低置信不进长期记忆 |

冲突处理：用户今天说的与三个月前不一致时，先区分"偏好变化 / 临时情况 / 记忆错误"——不擅自覆盖旧记忆，新记录带时间与来源，冲突双方并存并标"待确认"，用户裁决后更新；检索返回来源证据，无依据必须答"没有找到"。不是每条输入都自动写长期记忆；敏感内容写入遵守分级。

# 附录 E Agent 任务链参考范式（工具扩展的验收模板）

用户说"明天下午提醒我去取快递"，完整链路必须逐步可验证：
```text
1 理解意图（模型/规则）→ 2 确认时间与必要信息（缺参追问，不臆造）
→ 3 创建提醒任务（Schema 校验 + 幂等判重）→ 4 注册系统闹钟（setAlarmClock）
→ 5 保存任务状态（库事务）→ 6 确认闹钟注册成功（读回校验，失败要告知）
→ 7 到点触发（误差记录）→ 8 记录执行结果（审计 + 用户可见状态）
```
每个新工具按此范式定义：名称/用途/参数与返回 Schema/权限层（1.3）/是否需确认/超时/重试/幂等/错误码/审计字段/测试方法。测试覆盖：工具选择、参数校验、缺参追问、失败解释、结果真实性、越权拦截、重复调用幂等、多工具顺序、失败恢复、异常参数拦截。**模型能返回文本 ≠ 任务链可靠；每一步都要有"确认真的完成了"的校验。**

---

*本计划由审计事实驱动，随阶段推进持续修订；修订走第八部分流程并更新版本号。执行 AI 收到本文件后：先读第十部分状态文件（若不存在则从 T-A1 开始建立），按第六部分顺序推进，首批动作 = T-A1 备份 → T-A2 git → 向用户汇报并请求第十一部分决策。*