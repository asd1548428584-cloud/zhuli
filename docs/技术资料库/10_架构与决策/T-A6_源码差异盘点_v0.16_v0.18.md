# T-A6 源码差异盘点：v0.16 → v0.18

> 阶段 A / T-A6 产出。附于 `NEXT_STEP.md`，作为阶段 D/G 复验清单的来源。
> 基线：`交接_2026-11-19/zhuli-源码.zip`（2026-11-19 22:26 快照，core 23 个 .kt）
> 对端：当前 `zhuli/zhuli-assistant-step2/core/`（41 个 .kt，8132 行）
> 方法：文件级 diff + 新增行按函数/表/关键字归类；不做逐行语义评审。

## 1. 规模变化

| 项 | 11-19 快照 | 当前 | 差 |
|---|---|---|---|
| core 文件数 | 23 | 41 | **+18** |
| 新增代码量 | — | — | 18 个新文件合计约 3,100 行 |

## 2. 新增文件（18）

| 文件 | 行数 | 职责 |
|---|---|---|
| `CloudClient.kt` | 334 | 唯一出网口（chat / tool-calling / 语音转写 / 留档） |
| `PrivacyRouter.kt` | 100 | 出网闸门（三级隐私策略） |
| `SecureKeyStore.kt` | 148 | Key 的 Keystore AES-GCM 加密存储 |
| `Backup.kt` | 322 | 加密备份 / 恢复 / CSV·JSON 导出 |
| `AppLock.kt` | 42 | PIN 锁 |
| `Tools.kt` | 182 | 给模型的 6 个工具（查记忆/待办/账本/快递、建提醒、存备忘） |
| `VectorSearch.kt` | 119 | 混合检索（256 维哈希近似 + 关键词加权） |
| `ModelStore.kt` | 164 | 模型下载/管理（**无运行时，名不副实**） |
| `Express.kt` | 222 | 快递识别（短信/推送） |
| `CourierIntake.kt` | 137 | 快递入库链路 |
| `NoiseGuard.kt` | 216 | 通知噪声闸门（回声源/去重/熔断） |
| `DailyBrief.kt` | 173 | 晨间简报 |
| `BriefWidget.kt` | 91 | 桌面小组件 |
| `Repeat.kt` | 79 | 周期规则解析（daily / weekly:N / monthly:N） |
| `GeoFence.kt` | 387 | 地理围栏取件提醒 |
| `UiBridge.kt` | 209 | 读屏与单步点击桥 |
| `ZhuliA11yService.kt` | 77 | 无障碍服务 |
| `VideoNote.kt` | 270 | 视频抽帧 + 关键帧 OCR |

## 3. 改动文件（17）

| 文件 | 增/删 | 主要内容 |
|---|---|---|
| `InboxDatabase.kt` | +493/-10 | 新增 v9 `repeat_rule`、v10、v11 `embeddings` / `skills` / `cloud_log` / `snooze_count` 列与表 |
| `ReminderNotifier.kt` | +163/-2 | 勿扰时段 `inQuietHours`、批量合并 `showBatch`/`flush`、推迟≥2 次提示 |
| `AssistantChat.kt` | +148/-62 | 接入工具调用 `chatCloud`/`buildSystemPrompt`；删掉本地 `json`/`unescape`（迁到 CloudClient） |
| `ReminderEngine.kt` | +98/-11 | 周期顺延 `advanceRepeats`、撤回 `restore`、冲突检测 `findConflict` |
| `LinkExtractor.kt` | +73/-35 | 正文抓取改写 |
| `AssistantPrefs.kt` | +58/-2 | 新增云通道/隐私模式/提醒等偏好项 |
| `ShareReceiverActivity.kt` | +53/-0 | 新增 `video/*` 分享入口（第 11 步） |
| `SensitiveGuard.kt` | +47/-7 | 新增 `redact()`（**脱敏本身是新加的，也是 D3 的根源**）；地址/公司+岗位判定 |
| `ExtractPipeline.kt` | +44/-3 | 分类/时间/金额规则扩充 |
| `NotificationsListenerService.kt` | +33/-1 | 噪声闸门与提取流水线接线 |
| `AlarmReceiver.kt` | +17/-2 | 到点触发的批量/汇总分支 |
| `VoiceNote.kt` | +8/-53 | 删掉自带的 `cloudPost`，转写统一走 `CloudClient.transcribe` |
| `CalendarWriter.kt` | +6/-1 | 日历双写细节 |
| `Reminder.kt` | +7/-1 | 新增 snooze/notification 字段 |
| `ExtractFields.kt` | +3/-1 | 字段微调 |
| `InboxRecord.kt` | +2/-1 | 新增 media 字段 |
| `BootReceiver.kt` | +1/-0 | 开机补扫描 |

> 结论：**v0.16→v0.18 是一次大跨度重构**（出网收口 + 工具调用 + 5 张新表 + 视频/无障碍/围栏/简报），不是小修。0.16 时期的任何验收结论都不能顺延。

## 4. 必须在后续阶段复验的功能面

| # | 功能面 | 相关文件 | 复验阶段 | 风险点 |
|---|---|---|---|---|
| 1 | 出网闸门与脱敏 | CloudClient / PrivacyRouter / SensitiveGuard | **B** | D1/D2/D3 已确认存在；redact 覆盖不全 |
| 2 | 工具调用（6 工具） | Tools / AssistantChat / CloudClient | **B/D** | 工具返回值不过闸门（D1）；400 降级路径 |
| 3 | 数据库迁移 v9→v11 | InboxDatabase | **E** | 逐条 ALTER 各吞异常（D9）；迁移三路径未演练 |
| 4 | 提醒：周期顺延/冲突/静音/合并/推迟 | ReminderEngine / ReminderNotifier / Repeat | **D** | 无置信度分级（D10）；合并出通知是否漏提醒 |
| 5 | 采集：视频/分享/通知/快递/噪声 | ShareReceiverActivity / VideoNote / NoiseGuard / Express / CourierIntake | **F** | 视频抽帧在 16KB 页设备上的 OCR 表现 |
| 6 | 无障碍读屏与单步点击 | UiBridge / ZhuliA11yService | **D** | 星云 OS 是否二次拦截 |
| 7 | 地理围栏 | GeoFence | **D** | 后台定位受 superkiller 影响 |
| 8 | 备份/恢复/导出/App 锁 | Backup / AppLock / SecureKeyStore | **E** | 备份 version=9 缺 3 表（D7）；PIN 固定盐（D13） |
| 9 | 检索 | InboxSearch / VectorSearch | **C** | FTS 表在本机**不存在**（实测），只走 LIKE；哈希向量非语义 |
| 10 | 简报/小组件/快捷指令 | DailyBrief / BriefWidget / shortcuts.xml | **D** | 未真机复验 |
| 11 | 模型管理 | ModelStore | **H** | 无运行时、无 sha256 校验（D14） |

## 5. 与计划缺陷清单的对应

- **D1/D2/D3 的成因全部来自本轮重构**：`redact()` 是新加的、`chatWithTools` 是新加的、`CloudClient` 是新加的统一出口。0.16 时代"敏感不出机"之所以成立，是因为当时几乎没有出网路径。
- **D5 的成因**：`inbox_fts` 建表在此轮引入，且未配触发器。
- 因此阶段 B/C 的修复对象就是这批新代码，不存在"回退到 0.16 更安全"的选项（0.16 没有这些功能）。
