# 批次 C1-B：方案决策修订记录（D-6 / D-7）

> 本文件记录项目负责人对 `docs/批次C1-B_记忆系统基础设施实施方案.md`（下称 **方案**）中
> D-6、D-7 两项待决事项的正式决定，以及决定在方案中的逐条落实位置。
> **本文件与方案 v2 同时生效；方案 v1 的 D-6/D-7 章节作废。**
> 生成日期：2026-11-25。基线 HEAD：`90088f8`（方案 v1 的提交）。
> 本文件**不含**任何生产代码改动、数据库结构改动或测试代码改动。

---

## 0. 一句话

两项决策都是"把原本靠人守的规矩变成结构上不可能违反"：D-6 让未知状态无法伪装成当前值，D-7 让旧表无法在 v17 库里复活。代价是生产改动文件从 2 个增至 4 个、用例从 47 条增至 65 条，**五阶段顺序与架构设计不变**。

---

## 1. 决策来源与效力

| 项 | 内容 |
|---|---|
| 决策人 | 项目负责人 |
| 决策时间 | 2026-11-25 |
| 决策形式 | 书面指令（D-6 九条、D-7 九条 + 五项回归测试） |
| 效力范围 | `docs/批次C1-B_记忆系统基础设施实施方案.md` 的 v2 修订 |
| 是否改架构 | **否**。7 张表、40 列、六态、R0–R9、半开区间、`LB`/`UB`、恢复状态机、SECRET 四阶段全部不动 |
| 是否改 C0.2 | **否**。C0.2 是冻结的架构依据，D-6 对 C0.2 §10.3 的覆盖只记在本文件 §9 |
| 是否可开工 | 方案 v2 交付后，等负责人批准即可进入 C1-B1；本文件不构成开工许可 |

---

## 2. D-6：`FactStatus` 状态投影

### 2.1 决定原文（逐条）

1. 扩展旧 `FactStatus` 兼容状态模型，明确支持 `EXPIRED`、`REJECTED`。
2. 必要时增加 `UNKNOWN` 状态。
3. `FactStatus.of()` 遇到未知值不得 fallback 到 `ACTIVE`。
4. 未知状态必须按非当前、不可自动激活处理。
5. `ACTIVE`、`EXPIRED`、`REJECTED`、`DELETED` 必须保持不同语义。
6. 检查所有 `FactStatus` 使用方，明确每个调用方如何处理新状态。
7. `MemoryRanker.conflicts` 必须支持新记忆冲突模型，不能因为冲突败方处于 `CANDIDATE` 而静默丢失冲突展示。
8. 旧 `Fact` 只能承担兼容投影职责，不能成为第二套权威记忆存储。
9. 新状态的序列化、反序列化、数据库兼容性必须纳入测试。

### 2.2 决定的实质

方案 v1 曾建议"有损投影"（`EXPIRED`→`SUPERSEDED`、`REJECTED`→`DELETED`，`MemoryTypes.kt` 零改动）。D-6 否决了这个方案，理由可以还原为三条：

| 理由 | 说明 |
|---|---|
| 有损投影会制造新的"数据说谎" | "过期"与"被替代"在 UI 上不可区分，等于把 C0.2 缺陷 #2 从存储层挪到展示层 |
| `MemoryTypes.kt` 不动只是"省 diff"，不是"保兼容" | 加枚举值是**源兼容**的（现有 `when` 都有 else 或不用 `when`），真正的兼容风险在 `of()` 的 fallback，而它必须改 |
| `conflicts` 的静默失败不能靠绕 | 在 `MemoryRepo` 另写一套配对，会让 `MemoryRanker.conflicts` 继续对 `CANDIDATE` 视而不见，任何其他调用方都会踩坑 |

### 2.3 九条要求的落实位置

| # | 要求 | 落实 | 验收 |
|---|---|---|---|
| 1 | 支持 `EXPIRED` / `REJECTED` | 方案 §7.12.2：`MemoryTypes.kt` 枚举加 2 值 + KDoc 写"与相邻状态的区别" | `T-X01` / `T-X06` |
| 2 | 必要时加 `UNKNOWN` | 方案 §7.12.2：加 `UNKNOWN`，作为反序列化兜底 | `T-X02` |
| 3 | `of()` 不得 fallback `ACTIVE` | 方案 §7.12.2：`fun of(raw: String?) = entries.firstOrNull { it.name == raw } ?: UNKNOWN` | `T-X02` |
| 4 | 未知状态非当前、不可自动激活 | 方案 §7.12.2：`isCurrent` / `isActionable` 均不含 `UNKNOWN`；`MemoryStore.confirm` 的 `WHERE status='CANDIDATE'` 挡住 | `T-X04` |
| 5 | 四态语义保持不同 | 方案 §7.12.4：数据库字符串、投影、查询可见性三层都不合并；KDoc 写四组区别 | `T-X07`（30 格可见性矩阵） |
| 6 | 检查所有使用方 | 方案 §7.12.3：15 行使用方清单（含"要改/不改"与理由）+ 5 个文件"0 命中"核查 | 编译 + `T-X05` |
| 7 | `conflicts` 支持新模型 | 方案 §7.12.5：过滤改 `isActionable`；配对改"ACTIVE × 每个候选"；MULTI 排除移到 `MemoryStore.conflictFacts()` | `T-X09`–`T-X12` |
| 8 | 旧 `Fact` 只做兼容投影 | 方案 §7.12.1 + §4.8.4：`MemoryRepo` 零写旧表；`zhuli_fact` 不存在；`Fact` 是视图模型不是实体 | `T-M42` + `grep` |
| 9 | 序列化/反序列化/DB 兼容入测 | 方案 §7.12.6 的 9 行规则表 + 新增 `MemoryFactStatusTest` | `T-X01`–`T-X03`、`T-X05`、`T-X08` |

### 2.4 具体文件修改清单

| 文件 | 改动 | 行数（估） |
|---|---|---|
| `app/src/main/java/com/zhuli/memory/MemoryTypes.kt` | 枚举 4 → 7 值（6 业务 + `UNKNOWN`）；`of()` 参数改 `String?`、fallback 改 `UNKNOWN`；新增 `isCurrent` / `isActionable`；KDoc 逐值说明 | 154 → ~172（+18/−6） |
| `app/src/main/java/com/zhuli/memory/MemoryRanker.kt` | `conflicts` 过滤 `ACTIVE` → `isActionable`；配对规则改"ACTIVE × 每个候选"优先；KDoc 说明 MULTI 排除在 Store 层 | 124 → ~132（+8/−3） |
| `app/src/main/java/com/zhuli/memory/MemoryRepo.kt` | 新增 `projectStatus(raw)` 显式七分支；`read()` 不再用 `FactStatus.of()`；`active`/`activeCount`/`activeOf`/`pendingVectorFacts`/`vectorText`/`enqueueAllActive` 改 `CURRENT`；`conflicts()` 改走 `MemoryStore.conflictFacts()` | 含在 485 → ~175 的重写内 |
| `app/src/main/java/com/zhuli/memory/MemoryStore.kt`（新增） | 新增查询 `conflictFacts()`；`confirm()` 的乐观锁 `WHERE status='CANDIDATE'` | 含在 ~520 行内 |
| `app/src/test/java/com/zhuli/memory/MemoryFactStatusTest.kt`（新增） | `T-X01`–`T-X12` 共 12 条 | ~280 |

**不做的改动**：`Fact` 的 16 个字段、`FactKind`、`SourceType`、`EntityKind`、`FactDraft`、`FactHit` 全部不动；`Fact.status` 默认值仍是 `ACTIVE`；`MemoryBackfill` 只改 KDoc。

### 2.5 测试要求（12 条）

| # | 用例 | 层 | 一句话 |
|---|---|---|---|
| `T-X01` | 六态解析 | L1 | 6 个业务名 + `UNKNOWN` 全部命中 |
| `T-X02` | 未知值一律 `UNKNOWN` | L1 | `null` / `""` / 小写 / `CANDIDATE` / 垃圾串 / 尾空格，**没有一个变成 `ACTIVE`** |
| `T-X03` | 序列化往返 | L1 | 7 值 `name` → `of(name)` 回原值；禁止按 `ordinal` 存 |
| `T-X04` | `UNKNOWN` 非当前不可激活 | L1+L2 | 三个查询不返回；`confirmFact` 返回 false |
| `T-X05` | v16 旧四态兼容 | L2 | 迁移后 4 态投影不变；`activeCount` 与旧 `ACTIVE` 行数一致 |
| `T-X06` | 六态投影一一对应 | L2 | 6 个数据库字符串 → 6 个互不相等的 `FactStatus`；`CANDIDATE`→`UNCONFIRMED` |
| `T-X07` | 四态语义区分 | L2 | 6 态 × 5 查询 = 30 格逐格断言 |
| `T-X08` | 迁移遇未知状态值 | L2 | → `CANDIDATE` + `unknown_status=1` + `payload_json.legacy_status`，**不落 `ACTIVE`** |
| `T-X09` | ACTIVE + CANDIDATE 同槽 | L1+L2 | 返回 1 对（旧实现 0 对，**回归锚点**） |
| `T-X10` | 两条 CANDIDATE 同槽 | L1 | 返回 1 对（相邻配对兜底） |
| `T-X11` | MULTI 槽位不误报 | L2 | `MemoryRepo.conflicts()` 返回空 |
| `T-X12` | 非行动态不参与冲突 | L1+L2 | `EXPIRED`/`REJECTED`/`DELETED`/`UNKNOWN` 各与 `ACTIVE` 同槽 → 空 |

**跑法**：`T-X04`/`T-X05`/`T-X06`/`T-X07`/`T-X08`/`T-X11`/`T-X12` 走真实 SQLite，需在 `TRIGGER` 与 `APP_SYNC` 两种 `ftsMode` 下各跑一遍；`T-X01`–`T-X03`、`T-X10` 纯函数只跑一遍。**用例数仍按 12 计**（按 `@Test` 方法数，不按参数化展开）。

---

## 3. D-7：`createCoreTables` 拆分

### 3.1 决定原文（逐条）

1. 必须拆分核心表初始化和旧记忆表初始化。
2. 新数据库 `onCreate` 可以创建完整的当前版本初始结构。
3. 旧数据库升级必须通过明确的版本迁移完成。
4. `onOpen` 不得无条件重建已迁移或废弃的旧记忆表。
5. 不得影响其他核心表的正常初始化。
6. v17 及以后不得自动重新创建旧 `zhuli_fact`。
7. 检查 `InboxDatabase.kt` 中所有 `createCoreTables` 调用点。
8. 明确哪些表属于长期核心结构，哪些属于旧记忆兼容结构。
9. 迁移完成后，旧 Repository 不得静默写入空表。
10. 不得为了修复 X-1 而直接删除其他核心表的初始化逻辑。

（原文的"必须拆分核心表初始化和旧记忆表初始化"是前置要求，编号 0；下面 1–9 严格按原文编号，便于逐条核对。本文件不改变任何一条的语义。）

**必须增加的 5 项回归测试**：

- v16→v17 迁移。
- 关闭并重新打开数据库。
- 检查旧 `zhuli_fact` 不会复活。
- 检查新记忆数据完整。
- 检查旧调用方不能静默写入重新创建的空表。

### 3.2 决定的实质

方案 v1 的 X-1 已经指出问题（`onOpen` 每次开库都会 `CREATE TABLE IF NOT EXISTS zhuli_fact`），但只给了"建议"，把"拆不拆"留给负责人。D-7 的实质是把这件事从"建议"升级为"必须"：

| v1 的状态 | v2 的状态 |
|---|---|
| `zhuli_fact` 是否在 v17 存在，取决于是否采纳建议 | **结构上保证不存在**：三个 CREATE 路径全部移除 |
| `T-M09`（"`zhuli_fact` 不存在"）可能失败 | `T-M09` 必然通过，且有 4 条测试从不同角度盯住 |
| fail-loud 可能失效 | 旧写路径抛 `no such table`，`T-M42` 负向验证 |
| 拆分范围不明确 | 20 表分类 + 3 个调用点逐行替换表 + 两个函数的职责边界 |

### 3.3 十条要求的落实位置

| # | 要求 | 落实 | 验收 |
|---|---|---|---|
| 0 | 必须拆分（前置要求） | 方案 §4.8.3：`createCoreTablesV15` + `createFactTableV15` 两个 `private fun` | `grep "fun createCoreTables\b"` = 0 |
| 1 | 新库 `onCreate` 完整当前结构 | 方案 §4.8.2 第 1 行：`createCoreTablesV15` + `createV16Tables` + `MemorySchema.createAll(withUnique=true)` | `T-M40` |
| 2 | 旧库升级走明确迁移 | 方案 §4.7 + §5.1：`onUpgrade` 的 `upgradeSteps` 末尾挂 `MemoryMigrator.migrate` | `T-M01` / `T-M41` |
| 3 | `onOpen` 不重建旧记忆表 | 方案 §4.8.2 第 2 行：只调 `createCoreTablesV15` + `MemorySchema.ensureAll` | `T-M41` / `T-M43` |
| 4 | 不影响其他核心表 | 方案 §4.8.1 的 A/B 组 14 张表逐张列出创建函数；`createCoreTablesV15` 保留 entity/task/step/fact_vec 与 3 个索引 | `T-M01`（存量任务不丢）/ `T-M43` |
| 5 | v17 起不自动重建 `zhuli_fact` | 方案 §4.8.3 的 KDoc 强制约束 + `T-M40`/`T-M41`/`T-M45` | 3 条测试 |
| 6 | 检查所有调用点 | 方案 §4.8.2：`onCreate:317` / `onOpen:339` / `onUpgrade:787`，已核查只有 3 处 | `grep` 复核 |
| 7 | 明确长期 vs 兼容 | 方案 §4.8.1：24 张表分 A/B/C/D 四组，D 组标"v17 起不再创建" | 人工核对 |
| 8 | 旧 Repository 不得静默写空表 | 方案 §4.8.4：结构层（表不存在）+ 代码层（`MemoryRepo` 零写旧表）双防线 | `T-M42` + `grep` |
| 9 | 不得删其他核心表初始化 | 方案 §4.8.1 的 B 组 5 张表 + §4.8.3 的"真子集"要求 | `T-M01` / `T-M43` |

### 3.4 文件修改清单

| 文件 | 改动 | 行数（估） |
|---|---|---|
| `app/src/main/java/com/zhuli/core/InboxDatabase.kt` | `DB_VERSION = 17`；`createCoreTables` 拆成两个函数；`onCreate:317` / `onOpen:339` / `onUpgrade:787` 三处替换；`onUpgrade` 末尾挂 `MemoryMigrator` | +34 / −10 |
| `app/src/main/java/com/zhuli/memory/MemoryRepo.kt` | 删 4 处写旧表的方法/查询（`insert` / `reinforce` / `supersede` / `upsertEntity`）与 4 处读旧表的查询（`activeOf` / `active` / `activeCount` / `history`）；向量方法与 `enqueueVector` 保留 | 含在重写内 |
| `app/src/main/java/com/zhuli/memory/MemoryMigrator.kt`（新增） | 第 0 步前置断言：`zhuli_fact` 缺失时抛异常，**不补建** | 含在 ~250 行内 |
| `app/src/main/java/com/zhuli/memory/MemorySchema.kt`（新增） | `createAll` / `ensureAll` 的 `IF NOT EXISTS` 语义 | 含在 ~190 行内 |
| `app/src/test/java/com/zhuli/memory/MemorySchemaTest.kt`（新增） | `T-M40` | ~180 |
| `app/src/test/java/com/zhuli/memory/MemoryMigratorTest.kt`（新增） | `T-M41`–`T-M45` | ~540 |

### 3.5 测试要求（6 条，覆盖用户点名的 5 项）

| 用户点名的回归项 | 对应用例 | 断言 |
|---|---|---|
| v16→v17 迁移 | `T-M41`（+ 原有 `T-M01`–`T-M10`） | 数量闭合、替代链、向量、FTS、表名 |
| 关闭并重新打开数据库 | `T-M41` / `T-M43` | 连开 3 次，对象集合与行数不变 |
| 旧 `zhuli_fact` 不会复活 | `T-M41` + `T-M40` | 升级库与新装库都不存在 `zhuli_fact` |
| 新记忆数据完整 | `T-M44` | 12 个关键列抽检 + 向量行 + evidence 行数 |
| 旧调用方不能静默写入空表 | `T-M42` | `INSERT INTO zhuli_fact` 抛 `no such table` |
| （补充）缺源表不补建 | `T-M45` | v16 缺 `zhuli_fact` → 回滚到 16，表仍不存在 |

### 3.6 表分类（D-7 第 8 条的答案）

**长期核心（v17 起继续创建与维护）**：

| 组 | 表 |
|---|---|
| A 采集与提醒 | `inbox` / `inbox_fts` + 3 触发器 / `chat` / `reminders` / `ledger` / `cloud_log` / `skill` / `embedding` / `source_rule` |
| B Agent 与索引 | `zhuli_agent_task` / `zhuli_agent_step` / `zhuli_fact_vec` / `zhuli_vec_queue` / `zhuli_tool_exec` |
| C 新记忆 | `zhuli_memory_item` / `source` / `evidence` / `relation` / `revision` / `entity` / `fts` |

**旧记忆兼容（v17 起不再创建）**：

| 表 | 状态 | 说明 |
|---|---|---|
| `zhuli_fact` | 不存在 | 仅 `createFactTableV15` 在 `oldVersion<15` 时兜底创建 |
| `zhuli_fact_v1` | 只读 | 只由 `ALTER TABLE RENAME` 产生，无 DDL |
| `zhuli_entity` | 只读 | **保留创建**（D-7 第 10 条），但 C1-B 后无写入方（见方案 §4.8.5） |

> `zhuli_entity` 的处理是这次拆分最容易做错的地方：它是"旧记忆的索引表"，但不是"废弃表"。删掉它的建表 = 违反 D-7 第 10 条；保留它 = 多一张 7 列空表。方案选保留，并显式记录"停止写入"这个功能变化。

---

## 4. 与原方案的差异清单

### 4.1 数字变化

| 项 | v1 | v2 | 差异原因 |
|---|---|---|---|
| 修改生产文件 | 2 个 | **4 个** | D-6 要改 `MemoryTypes.kt` 与 `MemoryRanker.kt` |
| C1-B 新增用例 | 47 | **65** | +12（D-6）+6（D-7） |
| 目标用例总数 | 378 | **396** | 同上 |
| 新增测试文件 | 8 个 + 1 修改 | **9 个 + 1 修改** | 新增 `MemoryFactStatusTest.kt` |
| 测试代码行数（含 fixture） | 1,900（v1 误写 1,990） | **2,340** | 逐行相加重算 + 18 条新用例 |
| 生产修改行数 | +40/−323 | **+60/−329** | 4 个文件的净变化 |
| 风险项 | 12 | **14** | K-13（状态误判）/ K-14（拆分不彻底） |
| 退出条件 | 12 条 | **15 条** | #13 X-1、#14 X-2、#15 X-3 |
| 文档行数 | 2,224 | 2,816 | +592（新增 §4.8、§7.12、§10.9 与风险展开） |

### 4.2 结论变化（逐条）

| # | 位置 | v1 | v2 | 性质 |
|---|---|---|---|---|
| 1 | §0.1 | "7 + 2" | "7 + 4" | 数字修正 |
| 2 | §1.3 `MemoryTypes.kt` | "不动" | "修改（D-6）" | 决策 |
| 3 | §1.3 `MemoryRanker.kt` | "不动" | "修改一处（D-6 第 7 条）" | 决策 |
| 4 | §3.4 X-1 | "待确认，建议方案 A" | "**已决定：方案 A**" + 9 条要求对照表 | 决策 |
| 5 | §3.4 X-2 | "待确认，建议有损投影" | "**已决定：方案 B + `UNKNOWN`**" + 9 条要求对照表 | 决策 |
| 6 | §3.4 X-3 | "不改 `MemoryRanker`，在 `MemoryRepo` 另算" | "**改 `MemoryRanker.conflicts`**" | 决策 |
| 7 | §4.7 | 只说"不含 `zhuli_fact`" | 三入口职责边界表（允许建/禁止建/理由） | 补强 |
| 8 | §4.8 | 不存在 | 20 表分类 + 3 调用点 + 拆分函数 + 双防线 + 6 条测试 | 新增 |
| 9 | §5.2 第 0 步 | "`zhuli_fact` 存在" | 补"缺失时抛异常，**不调用 `createFactTableV15` 补建**" | 补强 |
| 10 | §5.3 `status` 行 | "未知值 → `ACTIVE` + 计数" | "未知值 → `CANDIDATE` + `unknown_status++`" | **与 D-6 冲突的修正** |
| 11 | §5.9 | 5 条时间不变量 | 加第 6 条状态不变量（`legacy_status` 标记） | 补强 |
| 12 | §7.8 | 8 个查询 | 加 `conflictFacts()` | 新增 |
| 13 | §7.12 | 不存在 | 状态模型、投影矩阵、15 行使用方清单、conflicts 新语义、序列化规则、7 条禁止 | 新增 |
| 14 | §8.2 | "恰好 2 个" | "恰好 4 个" + 逐文件行数 | 数字修正 |
| 15 | §8.4 | 9 个测试文件 | 10 个（+`MemoryFactStatusTest`）；小计从错误的 1,990 改为 1,900，再按 v2 算 2,340 | 算术更正 |
| 16 | §8.5 | 2 个修改文件、8 条自检 | 4 个修改文件、自检加 `createCoreTables` / `?: ACTIVE` / `valueOf` / 设备查表 | 补强 |
| 17 | §9.1 | 6 步 | 8 步（+`MemoryTypes`、+`MemoryRanker`、+`T-M40`） | 顺序细化 |
| 18 | §9.4 | 8 步 | 9 步（+`conflictFacts`、+`projectStatus`、+六态用例） | 顺序细化 |
| 19 | §10.9 | 不存在 | H 组 18 条（D-6 的 12 + D-7 的 6） | 新增 |
| 20 | §10.11 | 47 / 378 | 65 / 396，加"57 / 75 / 65 三个数字的关系" | 数字修正 |
| 21 | §13 | 12 项 | 14 项（+K-13 / K-14） | 新增 |
| 22 | §15 | `≥378` | `≥396`；B1/B2/B4 的完成条件加 D-6/D-7 项 | 数字修正 |
| 23 | §16 | 12 条退出条件 | 15 条（+X-1 / X-2 / X-3） | 决策 |
| 24 | §18 | "待用户决定（两项）" | "决策落定与遗留待决" | 状态转换 |
| 25 | §19.3 | U-1–U-6 | U-1–U-8（+`qualifier_key`、+UI 不显示状态） | 新增 |

> 上表"位置"列**全部指方案章节**，不是本文件章节。本文件只做索引，不复述方案正文。

### 4.3 两处必须说明的"v1 自身错误"

这两处与 D-6/D-7 无关，是 v2 自检时发现的算术/计数错误，一并更正并留痕：

| # | 位置 | v1 写的 | 实际 | 处理 |
|---|---|---|---|---|
| 1 | 方案 §8.4 | "小计新增 **1,990 行**测试" | 表内逐行相加 = **1,900**（140+150+420+480+230+200+120+160） | v2 先改正到 1,900，再加上 D-6/D-7 的增量（`MemorySchemaTest` +30、`MemoryMigratorTest` +120、`MemoryFactStatusTest` 280、`MemoryTestFixture` +10），得 **2,340**（含 fixture） |
| 2 | 方案 §1.3 | "`com/zhuli/memory/` **7 个文件**、1,556 行" | 表格列了 8 行，行数相加 485+154+241+231+148+124+115+58 = **1,556**，故为 **8 个文件** | v2 改为"8 个文件"，并在括号里写出算式 |

> 为什么值得单列：这两个数字都会被 C1-B5 的 `git diff --stat` 直接对照。如果沿用 v1 的错数，验收时会出现"实际 8 个文件 vs 预算 7 个文件"的假告警，或者"测试只写了 1,900 行 vs 预算 1,990 行"的假超支。

---

## 5. 测试数量重算

### 5.1 331 的计算依据（不变）

```
331 = 313（B1 基线的用例数，C0.2 §11.4 记录）
    +  18（C1-A 新增：G 组 7 条 × 2 个 SQLiteMode = 14，+ 环境诊断 2 + 负向对照 2 = 18）
```

本轮实跑 `:app:testDevDebugUnitTest`：**331 例 / 0 失败 / 0 错误 / 0 跳过 / 40 个套件**。这个数字没有因为 D-6/D-7 改变——它们是新增用例，不是修改基线。

### 5.2 47 的计算依据（不变）

```
57（C0.2 §11.2 的架构用例总数）
− 3（T-D32 硬删归 C3、T-A34 / T-A35 归 C3）
= 54（C1 应实现的架构用例）
− 7（T-F50–T-F56 已在 C1-A 完成）
= 47（C1-B 的架构用例增量）
```

### 5.3 18 的计算依据（新增）

```
D-6：T-X01–T-X12 = 12 条
D-7：T-M40–T-M45 = 6 条
合计 18 条
```

为什么是 18 而不是"塞进原有 47 条"：原有 47 条里**没有一条**会在下列情况下失败——

| 场景 | 原有 47 条里有吗 | 为什么没有 |
|---|---|---|
| `of()` 把未知值读成 `ACTIVE` | 没有 | 原有用例只喂 4 个已知状态 |
| `EXPIRED` 与 `SUPERSEDED` 在投影层被合并 | 没有 | 迁移用例只断言"值不丢"，不断言"新状态投影" |
| `conflicts()` 对 `CANDIDATE` 返回空 | 没有 | `T-S13` 只断言"新行是 `CANDIDATE` + 关系存在"，不调用 `conflicts()` |
| `onOpen` 把 `zhuli_fact` 重建回来 | 没有 | `T-M10` 只断言"不重复建表、不重复插数据"，没有断言"旧表不存在" |
| v16 缺 `zhuli_fact` 时被补建成空表 | 没有 | `T-M09` 只在正常迁移后查表名 |

### 5.4 总数

```
396 = 331（基线）+ 65（C1-B 新增）
65  = 47（架构）+ 12（D-6）+ 6（D-7）
```

三个口径的关系（防止后面看混）：

| 数字 | 含义 |
|---|---|
| 57 | C0.2 定义的架构用例总数（不变） |
| 75 | C1-B 全口径（57 + 18） |
| 65 | C1-B 新增（47 + 18），是 396 的加数 |

---

## 6. 实施顺序是否需要调整

**结论：五个子阶段顺序不变，只有 B1 的内容变多。**

| 阶段 | v1 内容 | v2 变化 | 是否影响顺序 |
|---|---|---|---|
| C1-B1 | 4 新增 + 1 修改（5 文件） | +`MemoryTypes.kt`、+`MemoryRanker.kt`、`InboxDatabase` 加拆分（7 文件） | 否。三者都是"编译期依赖"，必须在 B2 之前 |
| C1-B2 | 迁移 | 第 0 步补"缺表不补建"断言；加 `T-M41`–`T-M45` | 否。仍与 B3 并行 |
| C1-B3 | FTS 探测 | 无变化 | 否 |
| C1-B4 | Store + 投影 | 加 `conflictFacts()` + `projectStatus()` + 9 条状态用例 | 否。仍依赖 B1/B3 |
| C1-B5 | 回归 | 目标 378 → 396；自检清单加 4 条 | 否 |

**为什么 D-6 的枚举必须在 B1 而不是 B4**：`MemoryMigrator` 在 B2 就要写 `status` 映射（6 个字符串），`MemoryStore` 在 B4 要读 `MemoryStatus`。如果 `FactStatus` 拖到 B4 才改，B1 的 `MemorySchemaTest` 无法断言"六态投影"（它需要 `FactStatus` 存在），B2 的 `T-X08` 也无法编译。

**为什么 `MemoryRanker.conflicts` 在 B1 而不是 B4**：它是纯函数，不依赖 `MemoryStore`；B1 改完可以立刻用 `T-X09`/`T-X10` 的单测锁定语义。拖到 B4 会让"改 Store 时顺手改 Ranker"变成一次大 diff，违反方案 §8.5 的核算。

---

## 7. 未改变的结论（明确列出，防止误读）

以下 v1 的结论**一字未改**，不因 D-6/D-7 而变：

| # | 结论 | 位置（均指**方案**章节） |
|---|---|---|
| 1 | 7 张新表、40 列、15 项索引 | 方案 §2.1 / §4.5 |
| 2 | `LB` / `UB` 的唯一表达式，半开区间 `[valid_from, valid_until)` | 方案 §2.3 |
| 3 | `CURRENT` / `WORLD_ASOF` / `BELIEF_ASOF` 三个谓词 | 方案 §2.3 |
| 4 | R0–R9 的决策树与 `R7`/`R8`/`R9` 的分支顺序 | 方案 §2.4 |
| 5 | 迁移 13 步、四条禁止、5 条时间不变量（+新增第 6 条状态不变量） | 方案 §5.2 / §5.4 / §5.9 |
| 6 | `time_confidence='NONE'` 一律、`valid_until=0` 一律 | 方案 §5.3 |
| 7 | FTS 三档与 `detectFtsMode` 七步（绝不硬编码 `TRIGGER`） | 方案 §6 |
| 8 | `MemoryStore` 是唯一写入口；`MemoryRepo` 是投影层 | 方案 §7.1 |
| 9 | 27 个公开签名一个不改 | 方案 §3.2 / §8.5 |
| 10 | 恢复状态机 8 步、`RestoreResult` 五态、`pre_delete_status` 仅首次 | 方案 §7.6 |
| 11 | Scope 四个约束、空列表抛异常、`scopeClause()` 单点 | 方案 §7.9 |
| 12 | SECRET 拒绝写入、日志不含原文、备份清理归 C3 | 方案 §2.7 / §5.10 |
| 13 | 回滚三层、不做 `onDowngrade` | 方案 §12 |
| 14 | 设备 D1–D10、`MemoryMigrationTimingTest` 的 1k/5k/20k | 方案 §10.10 |
| 15 | 不做 `MemoryManager`、不做硬删、不做墓碑、不改 UI | 方案 §7.11 / §17 |

---

## 8. 未落实 / 明确不做的事

| # | 事项 | 为什么不在这轮 |
|---|---|---|
| 1 | 改 C0.2 文档（把 §10.3 的禁止清单划掉 `MemoryTypes.kt`） | C0.2 是冻结的架构依据。覆盖关系记在本文件 §9，不改原文 |
| 2 | 给 `FactStatus` 加 `CANDIDATE`（与 `UNCONFIRMED` 同义） | 两个同义值会让 `when` 出现两个分支做同一件事，且旧数据无法判断该用哪个 |
| 3 | 在 `MemoryRanker` 里判 `cardinality` | 纯函数拿不到；MULTI 排除是 `MemoryStore.conflictFacts()` 的职责 |
| 4 | 给 `Fact` 加 `qualifierKey` 字段 | 会破"27 个签名不变"与 15 个现有单测；C1 的 `qualifier_key` 恒为 `''`，无差异（U-7） |
| 5 | UI 显示状态标签（过期/否决/删除） | C1 禁止改 UI；语义区分只在数据层（U-8） |
| 6 | `zhuli_entity` 的数据迁移到 `zhuli_memory_entity` | C0.2 §2.5 已定：C1 只建不写，C2 接手 |
| 7 | 恢复 `zhuli_fact` 的写入能力 | D-6 第 8 条明令禁止"第二套权威存储" |
| 8 | 删除 `zhuli_fact_v1` | C3 的清理任务；C1 只改名 |

---

## 9. 与 C0.2 的覆盖关系

D-6 覆盖 C0.2 的一条禁止，必须显式记录，否则未来 review 会认为实施方擅自改架构：

| C0.2 条款 | 原文 | D-6/D-7 后的口径 | 处理 |
|---|---|---|---|
| C0.2 §10.3 | "`MemoryTypes.kt` 不动" | 允许改（加 3 个枚举值 + 改 `of()` + 加 2 个派生属性） | 以 D-6 为准；C0.2 原文不改，差异记在此处 |
| C0.2 §10.3 | "`MemoryRanker.kt` 不动" | 允许改一处（`conflicts` 的过滤与配对） | 同上 |
| C0.2 §10.2 | "`onCreate` 末尾加一行" | 拆成两个函数 + 三个调用点改造 | 以 D-7 为准；这是建表时机修正，不是架构改动 |
| C0.2 §8.9 第 13 步 | 迁移末尾写 `PRAGMA user_version=17` | 不写，交给 `SQLiteOpenHelper` | v1 的 X-6 已定，未变 |
| C0.2 §8.1 的 `status` 映射 | 未知值 → `ACTIVE` | 未知值 → `CANDIDATE` | 以 D-6 第 3/4 条为准 |

> 这五条覆盖都有同一个特征：C0.2 写的时候**没有**"未知状态会有害"与"`onOpen` 会重建旧表"这两个事实。D-6/D-7 是基于新证据的修正，不是偏好变化。

---

## 10. 校验记录（本轮执行）

### 10.1 仓库基线

| 项 | 值 | 核查方式 |
|---|---|---|
| 分支 | `master` | `git rev-parse --abbrev-ref HEAD` |
| 修订前 HEAD | `90088f8`（方案 v1） | `git log --oneline -1` |
| `DB_VERSION` | 16（未改） | `InboxDatabase.kt:50` |
| `batch-a` tag | `202f2c5`（annotated，指向 `9555bab`） | `git tag -l --format` |
| 工作区 | 只有未跟踪的 `Temp/` | `git status --porcelain` |
| `app/src` 改动 | 0 个文件 | `git status --porcelain -- app/src` |

### 10.2 本轮改动的文件

| 文件 | 动作 | 说明 |
|---|---|---|
| `docs/批次C1-B_记忆系统基础设施实施方案.md` | 修改 | v1 → v2，2,224 → 2,816 行 |
| `docs/批次C1-B_方案决策修订记录.md` | 新增 | 本文件 |

**没有**修改：任何 `app/src` 下的文件、`DB_VERSION`、数据库结构、测试代码、C0.2、C1-A。

### 10.3 文档自检

| 检查 | 方法 | 结果 |
|---|---|---|
| 章节编号连续 | 脚本遍历 `##` / `###`，检查三级编号是否从 1 递增 | 无跳号、无重复 |
| 代码围栏平衡 | 只统计行首 ``` | 86 个，偶数 |
| 表格列数一致 | 按未转义 `\|` 分割，逐表比对列数 | 0 处不一致 |
| 交叉引用 | 提取全部 `§x.y`，与本文实际章节比对 | 缺失项全部是 `C0.2 §x.y` 形式的外部引用 |
| 用例编号 | 提取 `T-[A-Z]\d+`，检查首列定义是否重复 | 66 个定义（含 `T-D32` 留设计），无重复 |
| D-6/D-7 提及 | 计数 | D-6 75 次、D-7 52 次、X-1 16 次、X-2 10 次、X-3 10 次 |

### 10.4 与代码的对照（只读核查，未改动）

| 核查 | 结果 |
|---|---|
| `grep -rn "FactStatus" app/src` | 生产 20 处 / 测试 6 处，逐处列入方案 §7.12.3 |
| `FactStatus.of` 的调用点 | **只有 1 处**：`MemoryRepo.kt:478`（`read()`） |
| `FactStatus.valueOf` | 0 处 |
| `grep -n "createCoreTables" InboxDatabase.kt` | **只有 3 处调用 + 1 处定义**（317 / 339 / 787 / 501） |
| `zhuli_entity` 的写入方 | 只有 `MemoryRepo.upsertEntity`（`ingest` 内），无读方 |
| `ZhuliMemoryPage` 对 `FactStatus` 的依赖 | 0 命中（不判状态字面量） |
| `beginTransaction` 在 `onUpgrade` 内 | 已有（`InboxDatabase.kt:680`），迁移沿用 |

---

## 11. 遗留问题（不阻塞开工）

| # | 问题 | 影响 | 谁来解 |
|---|---|---|---|
| U-7 | `Fact.slot` 不含 `qualifier_key`，C2 引入 qualifier 后冲突配对可能误报 | C1 无差异（`qualifier_key` 恒 `''`） | C2 改用 `slot_hash` 或给 `Fact` 加字段 |
| U-8 | 六态区分只在数据层，UI 不显示状态标签 | 用户看不出"过期"与"被替代" | C2 记忆页改版 |
| U-9 | 回填产生的大量 `CANDIDATE` 会进入 `conflicts()` | 记忆页"需要裁决"可能一次几十上百组；C1 无批量操作 | C2 的批量裁决 UI + 按证据强度排序 |
| U-10 | `createFactTableV15` 的 DDL 与 v16 现状是两份代码 | 未来改 v16 会导致迁移源表漂移 | `T-M02` 兜住；review 逐列对照 |
| U-11 | `conflicts()` 返回 3 组以上时的排序未定义 | 只影响展示顺序 | C2 的冲突面板设计 |

> U-9 是 D-6 第 7 条的**已知副作用**，不是缺陷：把 CANDIDATE 纳入冲突展示之后，历史回填的低置信度候选也会被列出来。方案选择"不隐藏"（隐藏等于违反"不得静默丢失"），把缓解放在 C2 的批量操作上。这一条必须在 C1-B5 报告里向负责人说明，避免上线后被当成 bug。

---

## 12. 交付边界

- 本文件与方案 v2 **只做文档**，不构成开工。
- C1-B1 的启动需要负责人的**单独批准**；本文件不包含任何"已开始实施"的表述。
- 下一轮（若批准）的第一个动作是 `MemoryTypes.kt` 的三值扩展与 `MemoryRanker.conflicts` 的过滤修正，不是建表、不是迁移。

---

**本文件到此结束。D-6 / D-7 已落实为方案文本；生产代码、`DB_VERSION`、数据库结构、测试代码均未改动。**
