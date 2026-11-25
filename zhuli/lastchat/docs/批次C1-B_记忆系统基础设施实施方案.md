# 批次 C1-B：记忆系统基础设施实施方案

> 依据：`docs/批次C0.2_记忆系统架构设计最终版.md`（下称 **C0.2**）与
> `docs/批次C1-A_FTS4兼容性验证报告.md`（下称 **C1-A**）。
> 本文**只制定实施方案**，不含任何生产代码改动。所有"将要做"都写成计划，不是既成事实。
>
> **修订 v2（2026-11-25）**：项目负责人已对 §18 的 D-6（`FactStatus` 状态投影）与 D-7
> （`createCoreTables` 拆分）作出正式决定，本文据此修订。决策原文、落实映射与差异清单见
> `docs/批次C1-B_方案决策修订记录.md`（下称 **修订记录**）。修订涉及的数字变化：
> 修改生产文件 **2 → 4** 个（`InboxDatabase.kt` / `MemoryRepo.kt` / `MemoryTypes.kt` /
> `MemoryRanker.kt`）、C1-B 新增用例 **47 → 65** 条、目标用例数 **378 → 396**。
> §3.4 的 X-1 / X-2 / X-3 从"待确认"转为"已决定"。

---

## 0. 结论摘要与阅读指引

### 0.1 一句话

C1-B 用 **7 个新文件 + 4 个改动文件**（生产），把 C0.2 的 7 张表、v16→v17 迁移、三档 FTS 与唯一写入口落地，并用 **65 条新用例 + 10 项设备验收**兜住；`MemoryRepo` 的 27 个公开签名一个不改。

> v1 的"7 + 2"是 D-6/D-7 决定前的口径。D-6 要求扩展 `FactStatus`（`MemoryTypes.kt`）并让 `MemoryRanker.conflicts` 支持 CANDIDATE，故生产改动文件由 2 个增至 4 个：`InboxDatabase.kt`（D-7 拆分）、`MemoryRepo.kt`（投影重写）、`MemoryTypes.kt`（状态扩展）、`MemoryRanker.kt`（冲突过滤）。65 = 原 47 + D-6 的 12 + D-7 的 6。


### 0.2 五个子阶段与依赖

```
C1-B1 结构  ──┬─→ C1-B2 迁移 ──┐
              │                 ├─→ C1-B5 回归
              └─→ C1-B3 FTS ────┤
                      │         │
                      └─→ C1-B4 Store ──┘
```

| 阶段 | 内容 | 可否与前序并行 | 关键产物 |
|---|---|---|---|
| C1-B1 | 7 张表 DDL + `MemorySchema` + `MemoryText` + `FactStatus` 扩展 + 建表拆分 | — | 建表可跑、纯函数全绿 |
| C1-B2 | `MemoryMigrator` + 事务接入 | 与 B3 并行（互不依赖） | 真实副本迁移 10/10 不变量 |
| C1-B3 | `detectFtsMode` 功能性探测 + 两条同步路径 | 与 B2 并行 | 三档结论可复现 |
| C1-B4 | `MemoryStore` + `MemoryRepo` 投影重写 + `conflicts` 新语义 | 依赖 B1/B3 | 27 个签名不变、测试全绿 |
| C1-B5 | 回归、设备验收、报告 | 最后 | D1–D10 + 退出条件核对表 |

**B2 与 B3 可以并行**，因为迁移走"先灌数据、后建触发器"的一次性路径（C0.2 §2.6），不依赖运行时 `ftsMode`；B3 只在探测时用临时对象。B4 必须等 B3，因为它要按 `ftsMode` 决定写路径分支。

**D-6 的状态扩展必须在 B1 就位**：`MemoryItem.kt` 的 `MemoryStatus`（六态）与 `MemoryTypes.kt` 的 `FactStatus`（六态 + `UNKNOWN`）是两套模型，投影函数只存在于 `MemoryRepo`，但两者都要在 B1 编译通过——B2 的迁移映射与 B4 的投影都依赖它们。D-7 的建表拆分同属 B1，因为它改的是 `InboxDatabase.onCreate/onOpen/onUpgrade`，B2 的迁移入口挂在这条链上。

### 0.3 本方案与 C0.2 的关系

- **不改架构**：7 张表、40 列、六态、R0–R9、半开区间、`LB`/`UB`、恢复状态机、四阶段 SECRET，全部照抄。
- **只补实现细节**：C0.2 是"设计级"文档，有些地方到不了代码（例如 `MemorySchema` 怎么拿到 `SharedPreferences`）。凡是补细节的地方，本文用 **`[实现补充]`** 明确标注。
- **冲突已定**：C0.2 与现有生产代码有 10 处对不上（§3.4），其中 X-1 / X-2 / X-3 已由项目负责人决定（D-7 / D-6 / D-6 第 7 条），落实方案分别在 §4.8、§7.12、§7.12.5。其余 7 处仍按本文建议执行，它们不影响架构。


### 0.4 本文的四种陈述类型（沿用 C0.2 §0.4，务必区分）

| 标记 | 含义 | 例子 |
|---|---|---|
| **已核查** | 本轮在仓库/设备上直接读出来或跑出来的 | `DB_VERSION = 16`（`InboxDatabase.kt:50`） |
| **沿用** | 来自 C0.2/C1-A 已验证的结论，本轮未复跑 | 半开区间在 X 点不双命中（C0.2 探针） |
| **计划** | 尚未执行，C1-B 要做的 | `MemoryStore.softDelete()` 的签名 |
| **未验证** | 明确没有证据 | Android 16 真机上的 `trusted_schema` 默认值 |

**本文不把"计划"写成"已通过"。** §16 的退出条件表里，每一项的证据列要么指到一个具体命令，要么写"未执行"。

---

## 1. 当前仓库真实基线（已核查）

### 1.1 Git

| 项 | 值 | 核查方式 |
|---|---|---|
| 分支 | `master` | `git rev-parse --abbrev-ref HEAD` |
| HEAD | **`6a66c1e`**（C1-A 交付） | `git log --oneline -1` |
| C0.2 提交 | `a0e1d52` | — |
| C1-A 提交 | `6a66c1e` | — |
| B1 提交 | `3597904` | C0.2 §11.4 的回归基线 |
| `batch-a` tag | 指向 `9555bab`（annotated tag 对象 `202f2c5`） | `git rev-parse batch-a` / `git log -1 batch-a` |
| `v16-baseline-20261124` tag | 存在 | `git tag --list` |
| 工作区 | 干净，只有未跟踪的 `Temp/` | `git status --porcelain` → `?? Temp/` |

提交链（近 5 个）：

```
6a66c1e test(c1a): verify FTS4 + trigger sync on Android SQLiteDatabase
a0e1d52 docs(c0.2): fix four data-lying defects in memory design; add three engineering gates
ace7cbc docs(c0.1): add memory architecture design revision with rehearsal-verified migration
3106be9 docs(c0): fix dedupe_key bucketing for STATE memory type
b18e254 docs: add batch C0 memory architecture design report
```

> `batch-a` 未被移动。C0/C0.1/C0.2/C1-A 全部是 `batch-a` 之后的**新增提交**，没有改写历史。

### 1.2 数据库

| 项 | 值 | 位置 |
|---|---|---|
| `DB_VERSION` | **16**（未改） | `InboxDatabase.kt:50` |
| 数据库名 | `zhuli_inbox.db` | `InboxDatabase.kt` |
| `journal_mode` | `delete`（应用库，C0.2 实测）；C1-A 的新建临时库默认 `truncate`。两者都是回滚日志模式，**都不是 WAL** | C0.2 §8.10 / C1-A §2.1 |
| `secure_delete` | 1 | C0.2 §6.3（V12） |
| SQLite（模拟器） | 3.42.0（进程内 `sqlite_version()` 与设备 CLI 一致） | C1-A §2.1 |
| minSdk | 28 | `app/build.gradle.kts:80` |
| compileSdk / targetSdk | 36 / 36 | 同上 |

现有与记忆相关的表（v16）：

| 表 | 列数 | 说明 |
|---|---|---|
| `zhuli_fact` | 16 | 旧记忆主体，v17 要迁走并改名为 `zhuli_fact_v1` |
| `zhuli_entity` | 7 | 实体索引（`UNIQUE(kind,norm)`） |
| `zhuli_fact_vec` | 4 | 向量，`fact_id` 主键 |
| `zhuli_vec_queue` | 4 | 向量索引队列 |

> `zhuli_fact_vec` 与 `zhuli_vec_queue` **不改结构**，只改 `fact_id` 的指代（旧 `fact.id` → 新 `item.id`）。ID 保真使这一步是零成本的（C0.2 §8.6）。

真实库副本（`Temp/c02/zhuli_inbox_v16_real.db`，274,432 字节）：

```
user_version = 16
zhuli_fact   = 2 行（ACTIVE 1 / SUPERSEDED 1）
zhuli_fact_vec = 1 行
```

预演库规模（供 C1-B2 复跑对照）：

| 库 | 行数 | 体积 | 状态分布 |
|---|---|---|---|
| `v16_edge.db` | 11 | 0.26 MB | ACTIVE 7 / DELETED 1 / SUPERSEDED 2 / UNCONFIRMED 1 |
| `v16_5k.db` | 5,002 | 1.12 MB | ACTIVE 3,682 / DELETED 453 / SUPERSEDED 391 / UNCONFIRMED 476 |
| `v16_20k.db` | 20,002 | 3.72 MB | ACTIVE 14,670 / DELETED 1,763 / SUPERSEDED 1,759 / UNCONFIRMED 1,810 |

### 1.3 生产代码现状

`com/zhuli/memory/` **8 个文件**、1,556 行（v1 误写"7 个文件"，逐行相加 485+154+241+231+148+124+115+58 = 1,556，故为 8 个）：

| 文件 | 行数 | 在 C1-B 的角色 |
|---|---|---|
| `MemoryRepo.kt` | **485** | **重写为投影层**（唯一的大 diff） |
| `MemoryTypes.kt` | 154 | **修改**（D-6）：`FactStatus` 加 `EXPIRED` / `REJECTED` / `UNKNOWN`，`of()` 不再 fallback 到 `ACTIVE` |
| `MemoryExtractor.kt` | 241 | 不动（纯函数） |
| `MemoryIndexer.kt` | 231 | 不动（只用 `MemoryRepo` 的向量方法） |
| `MemoryBackfill.kt` | 148 | 不动（只用 `ingest`；KDoc 里"低置信度进 UNCONFIRMED"仍成立） |
| `MemoryRanker.kt` | 124 | **修改一处**（D-6 第 7 条）：`conflicts` 的过滤集合从 `ACTIVE` 扩到 `ACTIVE` + `UNCONFIRMED` |
| `MemoryEmbedding.kt` | 115 | 不动 |
| `GemmaEmbedding.kt` | 58 | 不动 |

> `MemoryRanker.kt` 的改动是**一个过滤条件 + KDoc**（约 +8 / −3 行），不新增函数、不改签名。它是否算"修改生产文件"：本文按**改动落地的文件**计数，故生产文件从 2 个变 3 个（`InboxDatabase.kt` / `MemoryRepo.kt` / `MemoryTypes.kt`），`MemoryRanker.kt` 是**第 4 个落地文件**。§8.2 的表格按"必须改的文件"列 4 行，`git diff --name-only` 的期望值同步改为 4。


`MemoryRepo` 的公开成员共 **27 个**（1 个 `@Volatile var` + 26 个方法），是 C1-B 必须原样保留的契约面：

```
onIndexRequested / ingest / correct / deleteFact / byId / unconfirmed / confirmFact /
activeOf / active / activeCount / history / search / conflicts / resolveConflict /
putVector / markVectorIndexed / markVectorFailed / dropVector / pendingVectorFacts /
pendingVectorCount / waitingVectorCount / failedVectorCount / indexedSpaces /
vectorCount / enqueueAllActive / vectorText / hybridSearch(suspend)
```

调用方（已核查，`grep "MemoryRepo("`）：

| 调用点 | 用途 |
|---|---|
| `agent/ZhuliAgentRuntime.kt:53,58` | 构造实例、注入 `MemoryIndexer` |
| `agent/ZhuliAgentTools.kt:78,166,186,203` | `hybridSearch` / `ingest` / `correct` |
| `core/ExtractPipeline.kt:105` | `ingest`（通知流水线） |
| `memory/MemoryBackfill.kt:106` | `ingest(activateThreshold=…)` |
| `memory/MemoryIndexer.kt` | 向量 7 个方法 + `activeCount` |
| `test/java/com/zhuli/memory/MemorySqliteTest.kt:43,383` | 单测 |

`bridge/ZhuliMemoryPage.kt` 只用 `Fact` / `FactHit` / `MemoryBackfill` / `MemoryIndexer`，不直接构造 `MemoryRepo`。

### 1.4 测试基建现状

| 项 | 值 |
|---|---|
| `app/src/test` 文件数 | 40 |
| `app/src/test` 中 `@Test` 注解数 | 322 |
| **实际运行用例数（本轮实跑）** | **331**，0 失败 / 0 错误 / 0 跳过，40 个套件 |
| `app/src/androidTest` 文件数 | 12（含 C1-A 新增的 `MemoryFtsInstrumentedTest.kt`） |
| 记忆相关单测 | `MemorySqliteTest`（15 例）、`MemoryEmbeddingTest`、`MemoryExtractorTest`、`MemoryRankerTest`、`MemoryFtsRobolectricTest` |
| Robolectric 版本 | 4.15.1 |
| Robolectric 测试应用 | `com.zhuli.testutil.PlainTestApp`（`@Config(application=…)`） |
| 真实 SQLite | Robolectric `NATIVE` 模式 = SQLite **3.32.2**；`LEGACY` = 3.8.7（C1-A §2.2） |
| 真实库副本测试能力 | `MemorySqliteTest` 用 `ctx.deleteDatabase` + `InboxDatabase(ctx)` 建**新库**；**没有**加载 `.db` 副本的现成机制（C1-B2 要新建） |
| androidTest runner | `androidx.test.runner.AndroidJUnitRunner`，包名 `lastchat.rikkafork.cocolal.dev.test` |

**结论**：L2（真实 SQLite）走 Robolectric 的 `NATIVE` 模式是可行的，但 C1-A 已证明它**不复现** `trusted_schema=OFF` 的失败（C1-A §5）。所以：
- 迁移、状态机、唯一索引、scope 这类**纯 SQL 语义** → Robolectric 可信；
- FTS 触发器这类**依赖 ROM 配置**的行为 → 只认 `androidTest`；
- 迁移耗时 → 只认设备（C0.2 §9.6，**禁止**把桌面 Python 的耗时当设备耗时）。

### 1.5 基线数字汇总（后面反复引用）

| 名字 | 值 |
|---|---|
| `BASE_TESTS` | **331**（本轮 `:app:testDevDebugUnitTest` 实跑：331 例 / 0 失败 / 0 跳过） |
| `BASE_TESTS_B1` | 313（C0.2 §11.4 记录）；`331 − 313 = 18`，正好是 C1-A 新增的 9 例 × 2 个 `SQLiteMode` |
| `BASE_B1_COMMIT` | `3597904` |
| `C0_CASES_TOTAL` | 57（C0.2 §11.2 的架构用例） |
| `C1_CASES_IN_C1` | 54（扣 `T-D32` / `T-A34` / `T-A35`） |
| `C1_CASES_DONE_IN_C1A` | 7（`T-F50`–`T-F56`） |
| `C1B_ARCH_CASES` | **47** = 54 − 7（架构用例增量） |
| `C1B_D6_CASES` | **12**（`T-X01`–`T-X12`，落实 D-6） |
| `C1B_D7_CASES` | **6**（`T-M40`–`T-M45`，落实 D-7） |
| `C1B_NEW_CASES` | **65** = 47 + 12 + 6 |
| `C1B_TARGET_TESTS` | ≥ 331 + 65 = **396** |

> 为什么不用 C0.2 的 `313 + 54 = 367` 当目标：313 是 C1-A **之前**的数。C1-A 已经交付了 18 例（G 组 7 条 × 2 模式 + 环境诊断 + 负向对照），把它重复计入会虚增要求。
>
> 为什么从 378 涨到 396：D-6 与 D-7 不是"原方案里已有但没编号"的用例，而是**新决策带来的新验收面**——状态扩展要测序列化/反序列化/未知值/六态语义区分/冲突展示（12 条），建表拆分要测新装结构/重开不复活/旧写路径 fail-loud/幂等/数据完整/缺表回滚（6 条）。这 18 条不能塞进原有 47 条的任何一条里：原有 47 条里没有一条会失败于"`onOpen` 重建了旧表"或"`of()` 把未知值读成 ACTIVE"。


---

## 2. C0.2 架构要求摘录与实现映射

这一节的作用：**每一条已冻结的规则，指到具体文件与函数**。C0.2 有的条款本文不复述全文，只摘"实现时必须照着做的那一句"。

### 2.1 七张表 → 落在哪个文件

| 表 | DDL 定义 | 建表入口 | 首次创建时机 |
|---|---|---|---|
| `zhuli_memory_item` | `MemorySchema.kt` 的 `DDL_ITEM` | `MemorySchema.createAll(db, withUnique=false)` | `onCreate` / `onUpgrade(v17)` |
| `zhuli_memory_source` | `DDL_SOURCE` | 同上 | 同上 |
| `zhuli_memory_evidence` | `DDL_EVIDENCE` | 同上 | 同上 |
| `zhuli_memory_relation` | `DDL_RELATION` | 同上 | 同上 |
| `zhuli_memory_revision` | `DDL_REVISION` | 同上 | 同上 |
| `zhuli_memory_entity` | `DDL_ENTITY` | 同上 | 同上 |
| `zhuli_memory_fts` | `DDL_FTS` + 3 个 `DDL_TRIGGER_*` | `MemorySchema.createFts(db)` | **探测通过后**（`ftsMode != NONE`） |

`zhuli_memory_tombstone` / `usage` / `ingest` **不在 C1 建**（C0.2 §2.1）。

### 2.2 40 列 item 的六组语义（实现时按组处理，不要逐列散着写）

| 组 | 列 | 写入方 | 不变量 |
|---|---|---|---|
| 身份 | `id` | `AUTOINCREMENT` | 迁移保真（§8.6） |
| 分类 | `memory_type` / `subject` / `subject_key` / `predicate` / `qualifier_key` / `cardinality` | `MemoryStore` | `subject_key = norm(subject)`；`qualifier_key` 默认 `''` |
| 内容 | `value` / `content` / `normalized_content` / `dedupe_key` / `slot_hash` / `search_text` / `legacy_kind` / `payload_json` | `MemoryText` + `MemoryStore` | `dedupe_key` 32 hex；`slot_hash` 与 value 无关（C0.2 §2.2） |
| 状态 | `status` / `confidence` / `confidence_basis` / `superseded_by` / `revision` / `extractor` | `MemoryPolicy` + `MemoryStore` | 六态；`revision` 每次写 +1 |
| **时间** | `event_start` `event_end` `valid_from` `valid_until` `observed_at` `recorded_at` `updated_at` `last_confirmed_at` `invalidated_at` `deleted_at` `time_confidence` | `MemoryStore`（唯一） | 见 §2.3 |
| 权限与治理 | `scope_type` / `scope_id` / `sensitivity` / `source_id` / `times_*` / `last_used_at` / `pre_delete_status` | `MemoryGuard` + `MemoryStore` | scope 合法性（§2.6）；`source_id` = PRIMARY 冗余（§2.7） |

### 2.3 时间规则的三处硬约束（任何一处写错都会让数据说谎）

C0.2 §4.9 给了 `LB` / `UB` 两个**唯一**的派生表达式。C1-B 把它们写成 `MemoryText` 里的常量，**全仓库只此一份**：

```kotlin
// MemoryText.kt —— 唯一来源，任何地方都不许就地另写一个
const val LB_SQL = "CASE WHEN valid_from > 0 AND time_confidence <> 'NONE' " +
                   "THEN valid_from ELSE observed_at END"
const val UB_SQL = "valid_until"          // 不设 time_confidence 门槛
```

| # | 约束 | 落地 | 违反后果 |
|---|---|---|---|
| 1 | `valid_from` 绝不因"不知道"写 `now` | `MemoryStore.insert` 的 `now` 参数**不参与** `valid_from`；只有显式 `worldFrom` 才写 | 伪造事实起点 |
| 2 | 纠正**继承** `valid_from` 与 `time_confidence` | `MemoryStore.correct()` 从 `old` 复制，**不**用 `now` | 一次纠正把系统时间洗成用户时间（C0.2 §4.2 注） |
| 3 | 替代/过期**必须**同时写 `invalidated_at`，且此后不再改 | `MemoryStore.supersede()` / `expire()`；`softDelete` 只在 `=0` 时写 | 信念线与世界线混用 |
| 4 | 迁移行 `time_confidence` **一律 `NONE`**、`valid_until` **一律 0** | `MemoryMigrator` 的常量，不按条件分支 | 伪造时间精度（C0.2 缺陷 #1） |

`CURRENT` / `WORLD_ASOF` / `BELIEF_ASOF` 三个谓词（C0.2 §4.3，逐字实现）：

```sql
-- CURRENT
status='ACTIVE' AND (LB) <= :now AND ((UB)=0 OR :now < (UB))

-- WORLD_ASOF(:at)
status IN ('ACTIVE','SUPERSEDED','EXPIRED')
  AND (LB) <= :at AND ((UB)=0 OR :at < (UB))
  AND NOT EXISTS (SELECT 1 FROM zhuli_memory_relation r
                  WHERE r.to_id = i.id AND r.relation='CORRECTS')

-- BELIEF_ASOF(:at)
status IN ('ACTIVE','SUPERSEDED','EXPIRED')
  AND observed_at <= :at AND (invalidated_at=0 OR :at < invalidated_at)
```

`WORLD_ASOF` **不得**加 `confidence_basis` 或 `sensitivity` 之外的状态过滤——`DELETED`/`REJECTED` 不是历史值，是"用户不要了"。

### 2.4 冲突决策树 R0–R9 的 C1 落点

| 规则 | C1 是否实现 | 落在 | 说明 |
|---|---|---|---|
| R0 合法性 | ✅ | `MemoryGuard.check()` → `MemoryPolicy` | scope 合法、非 SECRET、非空、长度、**时间自洽（清零 + 强制 CANDIDATE）** |
| R1 墓碑 | ❌ | — | 依赖 C3 的 `tombstone` 表；C1 无硬删入口 |
| R2 `dedupe_key` 命中 ACTIVE/CANDIDATE | ✅ | `MemoryStore.insert()` | 追加 `SUPPORTING` 证据 + 强化，不新增行 |
| R3 命中 DELETED/REJECTED | ✅ | 同上 | 用户显式陈述 → 新建 ACTIVE + `RESTORES`；否则抑制 |
| R4 / R4a / R4b / R4c | ✅ | `MemoryPolicy.decide()` | R4a 用 `UB > 0 AND UB <= now`（**不是** `valid_until>0 AND valid_until<now` 的自写版） |
| R5 `cardinality=MULTI` | ✅ | 同上 | 短路，直接 ACTIVE |
| R6 `intent=CORRECT` | ✅ | `MemoryStore.correct()` 直接走 §4.4 | C1 只有 `correct_fact` 工具这一条入口；句式识别在 C2 |
| R7 / R8 / R9 | ✅ | `MemoryPolicy.decide()` | 见下表 |
| 事务性约束 | ✅ | `MemoryStore` 的每个写方法单事务 | C0.2 §6.5 |

`R7`/`R8`/`R9` 的实现必须逐字照抄 C0.2 §4.5 的分支顺序（rank 相等时先看"双方 USER_STATED + 双方 `time_confidence≠NONE` + Δt>24h"）：

```kotlin
// MemoryPolicy.decide() —— 伪码，实际实现见 C1-B4
when {
    newRank > oldRank && old.basis == USER_CONFIRMED -> Candidate(conflictWith = old)
    newRank > oldRank                               -> Supersede(old)
    newRank == oldRank &&
        new.basis == USER_STATED && old.basis == USER_STATED &&
        new.timeConfidence != NONE && old.timeConfidence != NONE &&
        new.eventStart - old.eventStart > DAY_MS      -> Supersede(old)
    newRank == oldRank                              -> Candidate(conflictWith = old)
    else                                            -> Candidate(conflictWith = old)  // R9 永不替代
}
```

> `Δt` 用的是**世界时间**（`valid_from`），不是 `observed_at`。C0.2 §4.5 的"Δt>24h"写在"双方 `time_confidence≠NONE`"后面，语义是"两个可信的世界时间点相隔超过一天"。实现时若拿 `observed_at` 算，取件码那个反例（两条相隔 3 天的包裹）会被判成"变更"——因为 `observed_at` 也是相隔 3 天。**这一处必须写测试盯住**（复用 `T-S15`）。

### 2.5 删除与恢复

| 动作 | 入口 | 事务 | 关键点 |
|---|---|---|---|
| 软删 | `MemoryStore.softDelete(id, now): DeleteResult` | 单 | `pre_delete_status` **仅首次**记录；`invalidated_at` 仅在 `=0` 时写；清 FTS/向量/队列；写 `DELETE` revision |
| 恢复 | `MemoryStore.restore(id, now): RestoreResult` | 单 | 回 `pre_delete_status`；`''` → `CANDIDATE`；重查 `dedupe_key`（含 `CANDIDATE`）与槽位；写 `RESTORE` revision |
| 彻底删除 | — | — | **C3**，不在 C1-B |

`RestoreResult` 五态（C0.2 §6.9）：`Restored(status)` / `Demoted(status, conflictId)` / `AlreadyPresent(occupantId)` / `NotFound` / `NotDeleted(status)`。

`DeleteResult` **`[实现补充]`**：C0.2 只写了"返回 NotDeleted / NotFound / 成功"三种语义但没给类型。C1-B 定为 `Deleted(preStatus)` / `NotDeleted(status)` / `NotFound`，与 `RestoreResult` 同构，避免调用方用 `Int` 猜。

### 2.6 Scope

| 规则 | 实现位置 |
|---|---|
| 只写 `GLOBAL` / `ASSISTANT("zhuli")` / `TASK(taskId)` | `MemoryStore.validateScope()`，其余抛 `IllegalArgumentException` |
| `GLOBAL` 的 `scope_id` 必须 `''`；其它非空、≤64、匹配 `[A-Za-z0-9._:-]+` | 同上 |
| 检索 `scopes` 为空 → 抛异常（不是返回空） | `MemoryQuery` 的 `init {}` + `MemoryStore.query()` 双检 |
| SQL 用显式 OR 列表 | `MemoryStore.scopeClause()`，**永不**写 `scope_type != 'X'` |
| 记忆页例外（可看全部 scope） | C1 不改 UI，仅保留 `MemoryQuery.forUi()` 的能力 |

### 2.7 SECRET 与来源

| 规则 | 实现 |
|---|---|
| SECRET 拒绝写入（唯一一处拒绝） | `MemoryGuard.classify(content) == SECRET` → `MemoryStore.insert` 返回 `Rejected(SECRET)`，不写任何表 |
| 分级只看 `content` | `MemoryGuard.classify()` 内部调 `SensitiveGuard.reason(content)`，不额外扩正则（避免出现第二套敏感词表） |
| 来源摘录二次脱敏 | `MemoryGuard.excerpt(text)` = `SensitiveGuard.redact(text).take(120)` |
| `item.source_id` = PRIMARY 冗余 | 写 `evidence` 的同一事务里回填；`selfCheck` 有对应 SQL |
| 来源 GC 只在硬删路径 | C1 不做（硬删在 C3） |

### 2.8 FTS 三档（C1-A 已定 `TRIGGER`，但**不许硬编码**）

| `ftsMode` | 判定 | 写入路径 | 检索 |
|---|---|---|---|
| `TRIGGER` | 七步功能性探测全过 | `MemoryStore` **不碰** FTS | `MATCH` 子查询 |
| `APP_SYNC` | 触发器建不出，或建了但索引不动 | 每次 insert/update/status 变更后显式 `syncFts(id)` | `MATCH` 子查询 |
| `NONE` | FTS4 虚拟表都建不出 | 不管 FTS | `search_text LIKE` 拼接通配（`"%" + kw + "%"`） |

C1-A 的结论（`TRIGGER`）只说明**当前测试机**是这档。C0.2 §14.1 与 A-6 要求运行时探测，C1-A §7.2 把它写成三条硬约束。C1-B3 的实现见 §6。

### 2.9 迁移映射

全部照 C0.2 §8.1–§8.9，逐字段表在本文 §5.3 复述并加"代码位置"列。三条最容易被写错的：

1. `time_confidence` **一律 `NONE`**（不是 `if (valid_from>0) DAY`）；
2. `valid_until` **一律 `0`**，旧 `valid_to` 进 `invalidated_at`；
3. 唯一索引 `idx_mi_dedupe` **最后建**（先建会在第 2 步遇到历史重复就整批失败）。

### 2.10 不变量与测试编号

| 类别 | 数量 | 出处 |
|---|---|---|
| 残留检查 | 10 组 / 11 条 SQL | C0.2 §6.6 |
| 迁移时间不变量 | 5 条 | C0.2 §8.1 |
| 架构用例 | 57 条（54 在 C1，7 已在 C1-A） | C0.2 §11.2 |
| **决策落实用例** | **18 条**（D-6 的 12 + D-7 的 6） | 本文 §10.2 / §10.9 |
| 设备验收 | D1–D10 | C0.2 §11.3 |

> 18 条**不属于** C0.2 的 57 条编号体系：57 条是架构冻结时定义的"系统行为"用例，18 条是 D-6/D-7 两项决策带来的"兼容层与建表时机"用例。两套编号不交叉（`T-X*` 是新前缀，`T-M40`–`T-M45` 接在 A 组已有编号之后），避免把架构用例的数量口径搞乱。


C1-B 把前两类做成 `MemoryStore.selfCheck(): List<String>`（运行时）+ `MemoryMigrator.verify(): MigrationReport`（迁移末尾），**两套 SQL 不复用**——C0.2 §8.1 的注已经说明为什么（`extractor='import:legacy'` 的范围收窄只在迁移期成立）。

### 2.11 文件范围与退出条件

C0.2 §10.1/§10.2 给了 7 新增 + 2 修改；§11.4 给了 9 条退出标准。本文 §8 把它们展开成"路径 + 行数估算 + 归属子阶段"，§16 逐条给"怎么证明"。

---

## 3. 现有代码审查结果

### 3.1 旧记忆链路全图（已核查）

```
通知/短信 ─→ ExtractPipeline:105 ─┐
Agent 工具 ─→ ZhuliAgentTools:166,186 ─┤
回填     ─→ MemoryBackfill:106 ────────┴─→ MemoryRepo.ingest(FactDraft, SourceType, sourceId, now)
                                                     │
                                                     ├─→ MemoryExtractor.norm / FactDraft
                                                     ├─→ activeOf(subject, predicate)   ← 读 zhuli_fact
                                                     ├─→ insert(Fact)                   ← 写 zhuli_fact
                                                     ├─→ supersede(old, new, now)       ← valid_to = now
                                                     └─→ enqueueVector(id, now)         ← zhuli_vec_queue

用户纠错 ─→ ZhuliAgentTools:203 ─→ MemoryRepo.correct(id, value, now)
                                      ├─ copy(old) 且 validFrom = now        ← 缺陷 #1
                                      └─ supersede(old, new, now)

检索     ─→ ZhuliAgentTools:78 ─→ MemoryRepo.hybridSearch(text, provider, now, 8)
                                      ├─ search() → MemoryRanker.search(active(300))   ← 只吃 ACTIVE
                                      └─ 向量：zhuli_fact_vec WHERE space=?

索引     ─→ MemoryIndexer.drainOnce()
                                      ├─ repo.indexedSpaces() / activeCount()
                                      ├─ repo.enqueueAllActive(now)
                                      ├─ repo.pendingVectorFacts(BATCH) → vectorText(id)
                                      └─ repo.putVector / markVectorIndexed / markVectorFailed

UI       ─→ ZhuliMemoryPage ─→ MemoryRepo.active/byId/unconfirmed/history/conflicts
```

### 3.2 `MemoryRepo` 的 27 个公开成员 → 新实现

这一列是 C1-B4 的施工图。**"投影"= 只读新表并映射成 `Fact`；"转 Store"= 调 `MemoryStore` 并映射返回值。**

| # | 成员 | 新实现 | 备注 |
|---|---|---|---|
| 1 | `onIndexRequested` | 保留原样 | 写入完成后触发索引器 |
| 2 | `ingest(drafts, source, sourceId, now, activateThreshold)` | 转 `MemoryStore.insert(draft, …)` 逐条 | 返回"新建行数" |
| 3 | `correct(factId, newValue, now)` | 转 `MemoryStore.correct()` | 返回新 id 或 null |
| 4 | `deleteFact(id, now)` | 转 `MemoryStore.softDelete()` | `Deleted`→1，其余→0 |
| 5 | `byId(id)` | 投影 | — |
| 6 | `unconfirmed(limit)` | 投影 `status='CANDIDATE'` | — |
| 7 | `confirmFact(id, now)` | 转 `MemoryStore.confirm()` | CANDIDATE→ACTIVE + 入队 |
| 8 | `activeOf(subject, predicate)` | 投影 `CURRENT` + slot 子句 | 语义收紧（X-8） |
| 9 | `active(limit)` | 投影 `CURRENT` 按 `observed_at` 倒序 | 同上 |
| 10 | `activeCount()` | 投影 `COUNT(CURRENT)` | 同上 |
| 11 | `history(subject, predicate)` | 投影全部状态（含 DELETED / EXPIRED / REJECTED） | 六态一一投影，`UNKNOWN` 兜底（D-6） |
| 12 | `search(text, now, limit)` | `MemoryStore.search()` → `FactHit` | 内部走 FTS/LIKE |
| 13 | `conflicts()` | **重写**：`MemoryStore.conflictFacts()` 取 SINGLE 槽的 ACTIVE + CANDIDATE → `MemoryRanker.conflicts()` 配对 | **改 `MemoryRanker` 的一处过滤**（D-6 第 7 条，X-3 已决） |
| 14 | `resolveConflict(keep, drop, now)` | 转 `MemoryStore.resolveConflict()` | 写 `SUPERSEDED` + 关系 |
| 15 | `putVector` | 保持直写 `zhuli_fact_vec` | 向量不是记忆语义，不值得进 Store |
| 16 | `markVectorIndexed` | 同上 | — |
| 17 | `markVectorFailed` | 同上 | — |
| 18 | `dropVector` | 同上 | — |
| 19 | `pendingVectorFacts` | 同上，JOIN 改新表 | 见下 |
| 20 | `pendingVectorCount` | 同上 | — |
| 21 | `waitingVectorCount` | 同上 | — |
| 22 | `failedVectorCount` | 同上 | — |
| 23 | `indexedSpaces` | 同上 | — |
| 24 | `vectorCount` | 同上 | — |
| 25 | `enqueueAllActive` | 同上，`SELECT id FROM 新表 WHERE CURRENT` | — |
| 26 | `vectorText(id)` | 投影 + `display` | 只对 ACTIVE 返回（保持原语义） |
| 27 | `hybridSearch(text, provider, now, limit)` | `search()` + 向量混合，逻辑不变 | `suspend` 保持 |

**`[实现补充]` 向量方法与 `MemoryStore` 的边界**：C0.2 §10.6 说 `MemoryStore` 是"所有运行时写入、所有查询"的唯一入口，但向量表 `zhuli_fact_vec` / `zhuli_vec_queue` 在 C0.2 里被归为"索引"而不是"记忆"。C1-B 决定：

- **向量读写留在 `MemoryRepo`**，因为它已经是"索引器 ↔ 存储"的适配层，且 13.2 明确要求只从 `MemoryRepo` 读；
- `MemoryStore.softDelete()` 仍**负责**删向量与队列（C0.2 §6.2 第 3、4 行），因为那是删除语义的一部分，不能漏；
- 这条边界写进 KDoc，避免以后有人把 `putVector` 搬进 `MemoryStore` 又搬回来。

### 3.3 三个必须保真的约束

| # | 约束 | 为什么 | 怎么验证 |
|---|---|---|---|
| 1 | **27 个公开签名一个不改** | 5 个生产调用方 + 现有 15 个单测都按签名编译 | `git diff` 里 `MemoryRepo.kt` 的 `fun ` 行逐条对照（§8.5 review checklist） |
| 2 | **`ingest` 返回"新建行数"** | 调用方拿它判断"这条通知有没有产生新记忆"（`ExtractPipeline` 日志、工具返回文案） | `T-S11`：同值两来源 → 返回 0 |
| 3 | **`hybridSearch` 保持 `suspend`** | `ZhuliAgentTools:78` 在协程里调 | 编译即验证 |

### 3.4 与 C0.2 的冲突清单（**10 处，X-1 / X-2 / X-3 已决定**）

按影响排序。X-1 / X-2 / X-3 已由项目负责人决定（D-7 / D-6 / D-6 第 7 条），本节保留**原始证据**与**决定后的施工口径**；完整落实方案在 §4.8（X-1）、§7.12（X-2、X-3）。其余 7 处仍按本文建议执行。

#### X-1（高·**已决定：方案 A**）`createCoreTables` 会在 v17 库里重建 `zhuli_fact`

**证据**：`InboxDatabase.kt`

```
269  override fun onCreate(...)      { …; createCoreTables(db); createV16Tables(db) }
335  if (!db.isReadOnly) { ensureColumns(db); createSourceTable(db); createCoreTables(db); … }
510  private fun createCoreTables(db) { … CREATE TABLE IF NOT EXISTS zhuli_fact … }
```

**冲突**：C0.2 §8.9 第 12 步把 `zhuli_fact` 改名为 `zhuli_fact_v1`，而 `onOpen` **每次开库**都会 `CREATE TABLE IF NOT EXISTS zhuli_fact`。于是：

1. 迁移后重启一次，`zhuli_fact` 以空表形式重新出现 → `T-M09`（"`zhuli_fact` 不存在"）**必然失败**；
2. C0.2 §13.7 的 fail-loud 设计失效——残留的写旧表代码不会报 `no such table`，而是**悄悄写进空表**；
3. 新装（`onCreate`）路径也会建一张永远为空的 `zhuli_fact`，与"v17 只有新表"矛盾。

**决定**：选方案 A——拆分 `createCoreTables`，v17 起不再创建 `zhuli_fact`。三条硬要求（来自 D-7，逐条对应见 §4.8）：

| D-7 要求 | 落实 |
|---|---|
| ① 新库 `onCreate` 创建完整的当前版本初始结构 | `createCoreTablesV15` + `createV16Tables` + `MemorySchema.createAll(withUnique=true)`，**不建** `zhuli_fact` |
| ② 旧库升级必须通过明确的版本迁移 | `onUpgrade`：`if (oldVersion<15) { createCoreTablesV15; createFactTableV15 }`，`if (oldVersion<17) MemoryMigrator.migrate` |
| ③ `onOpen` 不得无条件重建已迁移/废弃的旧记忆表 | `onOpen`：`createCoreTablesV15` + `MemorySchema.ensureAll`，**不建** `zhuli_fact` |
| ④ 不影响其他核心表 | `createCoreTablesV15` 保留 entity / task / step / fact_vec 与 3 个索引，一个不删 |
| ⑤ v17 及以后不得自动重建 `zhuli_fact` | 三个 CREATE 路径全部移除；`T-M40`/`T-M41`/`T-M43` 盯住 |
| ⑥ 检查所有 `createCoreTables` 调用点 | 3 处（`onCreate:317` / `onOpen:339` / `onUpgrade:787`），逐处替换见 §4.8.2 |
| ⑦ 明确长期核心结构 vs 旧记忆兼容结构 | §4.8.1 的 20 张表分类 |
| ⑧ 迁移后旧 Repository 不得静默写入空表 | 表不存在 → `no such table`；`MemoryRepo` 重写后零写旧表；`T-M42` 负向验证 |
| ⑨ 不得删除其他核心表的初始化逻辑 | `createCoreTablesV15` 原样保留 4 张表 + 3 索引，只把 `zhuli_fact` 与它的 3 个索引移出 |

代价：`InboxDatabase.kt` 的 diff 从 C0.2 估的 `+15 行` 变成约 `+34 / −10 行`（比 v1 的 +32/−8 多 2 行，因为要加 `createFactTableV15` 的调用与注释）。仍在允许范围内。

#### X-2（高·**已决定：方案 B + `UNKNOWN`**）`EXPIRED` 在 `FactStatus` 里没有对应值

**证据**：`MemoryTypes.kt:56-71`

```kotlin
enum class FactStatus { ACTIVE, UNCONFIRMED, SUPERSEDED, DELETED;
    companion object { fun of(raw: String) = entries.firstOrNull { it.name == raw } ?: ACTIVE }
}
```

**冲突**：C0.2 六态含 `EXPIRED` / `REJECTED`；`FactStatus` 只有四态。而 `FactStatus.of()` **未知值 fallback 到 `ACTIVE`**。于是：

- `byId()` 读到一条 `EXPIRED`（任务到期、STATE 关闭）→ 投影成 `ACTIVE` → **记忆页把过期值显示成当前值**，正是 C0.2 缺陷 #2 在投影层的翻版；
- `history()` 同理。

**决定**：扩展 `FactStatus`，加 `EXPIRED` / `REJECTED` / `UNKNOWN`，`of()` 未知值返回 `UNKNOWN`（不 fallback）。D-6 的 9 条要求逐条落实：

| D-6 要求 | 落实位置 | 验证 |
|---|---|---|
| ① 明确支持 `EXPIRED` / `REJECTED` | `MemoryTypes.kt` 枚举加 2 个值 | `T-X01` / `T-X06` |
| ② 必要时增加 `UNKNOWN` | 加 `UNKNOWN`，作为反序列化兜底 | `T-X02` |
| ③ `of()` 未知值不得 fallback 到 `ACTIVE` | `?: UNKNOWN` | `T-X02` |
| ④ 未知状态按非当前、不可自动激活 | `isCurrent` / `isActionable` 均为 false；`confirm` 的 `WHERE status='CANDIDATE'` 挡住 | `T-X04` |
| ⑤ 四态语义保持不同 | 数据库字符串、投影、查询可见性三层都不合并 | `T-X07` |
| ⑥ 检查所有使用方 | §7.12.3 的 15 行使用方清单 | 编译 + `T-X05` |
| ⑦ `conflicts` 支持新模型 | `MemoryRanker.conflicts` 过滤集合扩到 `ACTIVE` + `UNCONFIRMED` | `T-X09`–`T-X12` |
| ⑧ 旧 `Fact` 只做兼容投影 | `MemoryRepo` 零写旧表；`zhuli_fact` 不存在 | `T-M42` + `grep` |
| ⑨ 序列化/反序列化/DB 兼容入测 | 新增 `MemoryFactStatusTest` | 12 条 |

#### X-3（中·**已决定：改 `MemoryRanker.conflicts`**）`conflicts()` 只看 ACTIVE，C0.2 之后冲突方是 CANDIDATE

**证据**：`MemoryRanker.kt:111-123`

```kotlin
fun conflicts(facts: List<Fact>) = facts.filter { it.status == FactStatus.ACTIVE }
    .groupBy { it.slot } … // 同槽两条以上 ACTIVE 才算冲突
```

**冲突**：C0.2 §4.5 的 R8/R9 把"打不平的新值"放进 `CANDIDATE`，槽位里只留一条 ACTIVE。于是 `MemoryRanker.conflicts(active(300))` **永远返回空**，记忆页的"待裁决冲突"区域会静默消失。

**决定**：按 D-6 第 7 条，**修改 `MemoryRanker.conflicts` 本身**（而不是像 v1 建议的那样在 `MemoryRepo` 里另算一套）：

1. 过滤集合改为 `ACTIVE` + `UNCONFIRMED`（`FactStatus.isActionable`）；
2. 配对规则改为"ACTIVE × 每个不同值的 CANDIDATE"优先，全候选槽位退化为相邻配对；
3. `MULTI` 槽位的排除放在 `MemoryStore.conflictFacts()` 的 SQL 里（`cardinality='SINGLE'`），因为 `MemoryRanker` 拿不到 `cardinality`，纯函数不该猜存储语义；
4. 签名 `List<Pair<Fact, Fact>>` 不变，UI 零改动。

细节与伪码见 §7.12.5；验收 `T-X09`–`T-X12`。


#### X-4（中）`ingest(activateThreshold)` 与 R4b 缺少衔接规则

**证据**：`MemoryRepo.kt:33-38` 的 `activateThreshold` 参数；`MemoryBackfill.kt:124` 传非 0 值。现有实现：`confidence < threshold` → `UNCONFIRMED`，并且因为 `if (status == ACTIVE)` 守卫，**不参与冲突判定**。

**冲突**：C0.2 §4.5 的 R4b 说"新行按自身 basis 决定 ACTIVE/CANDIDATE（C2 的自动写入矩阵）"，没提这个阈值参数。两个"是否候选"的判据并存，谁优先没写。

**建议**（保持现有行为，不改架构）：`activateThreshold > 0 && confidence < threshold` → **强制 `CANDIDATE` 并跳过 R4–R9**，但仍参与 R2（同值则强化）。这与现有代码完全一致（`UNCONFIRMED` 也走 `activeOf` + `same` 判定）。写进 `MemoryStore.insert` 的 KDoc 与 `T-S11` 的变体。

#### X-5（低）`MemorySchema` 拿不到 `SharedPreferences`

C0.2 §14.1 要求"探测结果存 `SharedPreferences`，带探测版本"，但 §10.1 把 `MemorySchema` 设计成无状态 `object`（无 `Context`）。

**`[实现补充]`**：`MemorySchema.attach(prefs: SharedPreferences)` 在 `InboxDatabase` 的 `init` 里调一次（`InboxDatabase` 已有 `appCtx`）；未 attach 时退化为"每次开库都探测"，并在 debug 下 `Log.w` 提醒。**不改** `MemorySchema` 的纯函数性质。

#### X-6（低）`PRAGMA user_version = 17` 与 `SQLiteOpenHelper` 重复

C0.2 §8.9 第 13 步要求迁移末尾写 `PRAGMA user_version = 17`。但 `SQLiteOpenHelper` 在 `onUpgrade` 返回后会自己 `db.setVersion(mNewVersion)`（`InboxDatabase.kt` 的 `onUpgrade` 只是被它调用）。

**建议**：**不写**这条 PRAGMA，依赖 helper。理由：在 `onUpgrade` 内改 `user_version` 若与 helper 的顺序不符，会出现"版本号到了 17 但迁移没提交"的窗口。预演脚本（纯 Python、无 helper）**要**写，那是它自己的事。这条差异写进 `MemoryMigrator` 的 KDoc。

#### X-7（低）`deleteFact` 返回 `Int`，恢复没有对应 API

现有 `deleteFact(id, now): Int`（影响行数）；C0.2 的 `RestoreResult` 五态无处安放。且 `MemoryRepo` 没有 `restore` 方法——C1 也没有 UI 调它。

**建议**：`MemoryRepo.deleteFact` 保持 `Int`（`Deleted`→1，其余→0）；`restore()` **只**暴露在 `MemoryStore`（C0.2 §6.9 的"任务边界"已这么定），C1 的 `MemoryRepo` 不转发。测试直接测 `MemoryStore`。

#### X-8（低）`active(limit)` 收紧为 `CURRENT`

旧语义 `status='ACTIVE'`；新语义 `CURRENT`（多一个 `LB <= now`）。一条"明年起生效"的 ACTIVE 行会从记忆页消失。

**评估**：C1 不产生这种行（R0 清零 + 强制 CANDIDATE；T-S39/T-T40 盯住），迁移也不产生（`valid_from` 都是过去的 `now` 副本）。所以**行为差异为 0**，但要在 `active()` 的 KDoc 里写明，防止 C2 引入"未来生效"后有人以为是 bug。

#### X-9（低）`zhuli_fact_vec` 表名保留但语义变了

`fact_id` 从"`zhuli_fact.id`"变成"`zhuli_memory_item.id`"。改表名会波及 `MemoryEmbedding.kt` 以外的常量（`Db.COL_FV_*`）与 4 处现有 SQL。

**建议**：**不改名**，只改注释与 KDoc。ID 保真（§8.6）保证旧向量仍然指向正确的行。

#### X-10（低）`backupBeforeMigration` 在 `onUpgrade` 事务内复制主库

`SQLiteOpenHelper` 在调 `onUpgrade` **之前**就开了事务，所以 `backupBeforeMigration` 复制的是"事务已开、尚未写入"的文件。当前 `journal_mode=delete` 下可用（C0.2 §8.10 已记录），但若哪天改 WAL 就会缺内容。

**建议**：C1-B2 在迁移入口加 `PRAGMA journal_mode` 断言（`wal` → 先 `wal_checkpoint(FULL)`），不改 `backupBeforeMigration` 本身（那是既有机制，动它超出 C1-B 范围）。

### 3.5 旧表/旧字段的下游引用（已核查，无隐藏依赖）

| 检查 | 结果 |
|---|---|
| grep 查 `.validFrom` 与 `.validTo` | 只命中 `MemoryRepo.kt:405-406`（写入），**无读路径**（C0.2 A-7 复核通过） |
| `grep "TABLE_FACT" app/src/main` | 只在 `InboxDatabase.kt`（DDL）与 `MemoryRepo.kt`（将被重写）→ 重写后只剩 `InboxDatabase` |
| `grep "com.zhuli.memory" app/src/main` | 无反射、无 `Class.forName`、无 Gson 序列化 `Fact` |
| `Fact` 的序列化 | 只在进程内传递；`AssistantExportImport` 用的是宿主 `MemoryEntity`，与 `Fact` 无关 |
| 宿主 `MemoryRepository` | 与 `com.zhuli.memory.MemoryRepo` **同名不同物**，两套并存，C1 不合并（C0 已定） |

结论：C0.2 的 A-7（"`Fact` 字段无下游反射依赖"）**成立**，`MemoryRepo` 重写不会引起 `NoSuchField`。

---

## 4. 新旧数据结构对照

### 4.1 相关表总览（v16 / v17 对照）

| 表 | v16 | v17 | C1-B 动作 |
|---|---|---|---|
| `inbox` | 有 | 有 | 不动 |
| `inbox_fts` | 有（可能建不出） | 有 | 不动（与记忆 FTS 是两套） |
| `chat` / `reminder` / `ledger` / `cloud_log` / `skill` / `embedding` / `source_rule` / `tool_exec` | 有 | 有 | 不动 |
| `zhuli_fact` | **16 列** | **不存在** | 迁移后改名 `zhuli_fact_v1` |
| `zhuli_fact_v1` | — | 16 列（只读） | 迁移产出 |
| `zhuli_entity` | 7 列 | 7 列 | **不动**（新表 `zhuli_memory_entity` 是另一张） |
| `zhuli_fact_vec` | 4 列 | 4 列 | 结构不动，`fact_id` 语义改为 `item.id` |
| `zhuli_vec_queue` | 4 列 | 4 列 | 不动 |
| `zhuli_memory_item` | — | **40 列** | 新增 |
| `zhuli_memory_source` | — | 12 列 | 新增 |
| `zhuli_memory_evidence` | — | 6 列 | 新增 |
| `zhuli_memory_relation` | — | 6 列 | 新增 |
| `zhuli_memory_revision` | — | 10 列 | 新增 |
| `zhuli_memory_entity` | — | 3 列 | 新增 |
| `zhuli_memory_fts` | — | FTS4 虚拟表（1 列 + 5 影子表） | 新增，`ftsMode != NONE` 时 |

> **`zhuli_entity` 与新表并存**：C0.2 没有把旧 `zhuli_entity` 迁进 `zhuli_memory_entity`（前者是 `kind+norm` 去重表，后者是 `memory↔entity` 关联表，语义不同）。C1 保留旧表不动，`zhuli_memory_entity` 在 C1 **只建不写**（写入方在 C2 的模型抽取）。这是 C0.2 §2.5 的既定设计，不是遗漏。
>
> **每张表的"结构归属"（长期核心 / 旧记忆兼容）见 §4.8.1**。这个分类是 D-7 的直接产物：它决定 `onCreate` / `onOpen` / `onUpgrade` 各自能建哪些表，不能凭"表名里有 zhuli 就都建"。


### 4.2 `zhuli_fact`（16 列）→ `zhuli_memory_item`（40 列）逐列对照

| # | 旧列（v16） | 新列（v17） | 变换 | 丢失信息？ |
|---|---|---|---|---|
| 1 | `id` | `id` | 原值 | 否 |
| 2 | `subject` | `subject` | `trim()`，空则跳过 | 空主体行丢弃（C0.2 #2） |
| 3 | `predicate` | `predicate` | `trim()` | 否 |
| 4 | `value` | `value` | `trim()`，空则跳过 | 空值行丢弃（C0.2 #5） |
| 5 | `kind` | `legacy_kind` | `FactKind.name` 原样 | 否 |
| 6 | `kind` | `memory_type` | `PREFERENCE`→`PREFERENCE`，其余→`FACT` | 类型变粗（C0.2 §8.2 有意为之） |
| 7 | `confidence` | `confidence` | 按 basis 上限 clamp（只降不升） | 高估的置信度被降（C0.2 §8.3） |
| 8 | `source_type` | `confidence_basis` | 5 条映射（§5.3） | 否 |
| 9 | `source_type` + `source_id` + `source_text` | `zhuli_memory_source` 行 + `evidence(PRIMARY)` | 见 §5.5 | 否（`source_text` 截 120 并脱敏，原文还在 `inbox`） |
| 10 | `observed_at` | `observed_at` | 原值；0→`created_at`；仍 0→`now` | 否 |
| 11 | `valid_from` | `valid_from` | **原值保留**（0 保留 0） | 否，但**不可信**（`time_confidence=NONE`） |
| 12 | `valid_to` | `valid_until` | **一律 0** | 旧值转记 `invalidated_at`（#23），不丢 |
| 13 | `status` | `status` | 4→6（§5.3） | 否 |
| 14 | `superseded_by` | `superseded_by` | 原值；目标不存在则清 0 | 断链只清指针，保 `invalidated_at` |
| 15 | `created_at` | `recorded_at` | 原值 | 否 |
| 16 | `updated_at` | `updated_at` | 原值 | 否 |

新增的 **29 列**（旧表 16 列中 11 列同名对应，其余 5 列拆分或丢弃——见上表）分五组：

| 组 | 列（共 29） | 值 | 依据 |
|---|---|---|---|
| 内容与检索（7） | `subject_key` `content` `normalized_content` `dedupe_key` `slot_hash` `search_text` `qualifier_key` | `qualifier_key=''`，其余见 §5.3 | C0.2 §2.2 / §2.7 |
| 分类与来源（4） | `memory_type` `cardinality` `confidence_basis` `legacy_kind` | 见 §5.3 的映射表 | C0.2 §8.2 / §8.3 |
| 时间（9） | `event_start` `event_end` `valid_until` `recorded_at` `last_confirmed_at` `invalidated_at` `time_confidence` `deleted_at` `pre_delete_status` | `event_start/end=0`、`valid_until=0`、`time_confidence='NONE'`、`pre_delete_status=''` | C0.2 §4.1 / §8.1 |
| 权限与治理（4） | `scope_type` `scope_id` `sensitivity` `extractor` | `'GLOBAL'` / `''` / 分级结果 / `'import:legacy'` | C0.2 §7.1 / §6.7 / §8.1 |
| 版本与统计（5） | `revision` `payload_json` `times_reinforced` `times_retrieved` `last_used_at` | `1` / `{"migrated_from":N}` / `0`（前者第 5 步重算） | C0.2 §8.1 |

> 7 + 4 + 9 + 4 + 5 = **29**，与 40 − 11 = 29 相符。

### 4.3 DDL 落地物与一致性校验

C0.2 §2.2–§2.6 已给出 7 段 DDL，本文**不复制粘贴**（复制会产生两份真相）。落地方式是：

| 项 | 做法 |
|---|---|
| 唯一真相 | `MemorySchema.kt` 的 `DDL_*` 常量 |
| 机器可验的一致性 | `MemorySchemaTest.schema_matches_expected()`：建库后读 `PRAGMA table_info(zhuli_memory_item)` 与 `sqlite_master`，与测试里的 `EXPECTED_COLUMNS`（40 个列名，有序）/ `EXPECTED_INDEXES`（15 个索引名 + 唯一性）逐项比对。**这能抓住 DDL 拼写错、漏列、索引少建** |
| 人工对照 | C1-B1 完成时，把 `DDL_*` 与 C0.2 §2.2–§2.6 逐段对照一遍，结论写进 C1-B 报告（"逐段一致 / 差异 N 处及理由"） |
| 不做的检查 | 不在单测里解析 markdown（路径脆弱、且 C0.2 是文档不是契约源）；不做 SQL 语法树 fuzz |

> **为什么值得写这个测试**：C0.2 是"冻结的架构"，实现漂移是最难发现的缺陷。一个 20 行的 `table_info` 比对能永久防住"漏一列 / 拼错名 / 少建索引"，成本远低于每次人工 review 40 列。

### 4.4 建表顺序

顺序不是随意的，每一步都有理由：

```
S1  zhuli_memory_item            （无外键，可先建）
S2  zhuli_memory_source          （被 evidence 引用）
S3  zhuli_memory_evidence        （依赖 S1/S2 语义，但**不建外键**）
S4  zhuli_memory_relation        （自引用，无外键）
S5  zhuli_memory_revision        （无外键）
S6  zhuli_memory_entity          （无外键）
S7  普通索引 × 12                （见 §4.5）
S8  zhuli_memory_fts（虚拟表）    ← 仅 ftsMode != NONE
S9  触发器 × 3                    ← 仅 ftsMode == TRIGGER
S10 UNIQUE INDEX idx_mi_dedupe    ← **迁移时最后建**；运行时建表可随 S7
S11 UNIQUE INDEX idx_mev_primary  ← 随 S7
S12 UNIQUE INDEX idx_ms_dedupe    ← 随 S7
```

**为什么不用外键**：C0.2 的 DDL 全部没有 `FOREIGN KEY`。这不是省事：`ON DELETE CASCADE` 会在硬删时跳过 revision 记录（审计丢失），而 `PRAGMA foreign_keys` 在 Android 上默认关闭、需要每连接开。C1 用**应用层顺序 + `selfCheck`** 保证一致性（C0.2 §6.3 的子→父顺序）。

**为什么 `idx_mi_dedupe` 在迁移时最后建**：C0.2 §8.9 第 10 步。先建会在逐行插入时撞上历史重复，整批失败。运行时（`createAll` 的 `withUnique=true` 分支）可以一次建好，因为那时表是空的。

### 4.5 索引与约束清单（完整 15 项）

| # | 名称 | 表 | 类型 | 列 / 条件 | 服务于 |
|---|---|---|---|---|---|
| 1 | `idx_mi_slot` | item | 普通 | `(subject_key, predicate, qualifier_key, status)` | R4 取同槽 ACTIVE |
| 2 | `idx_mi_scope` | item | 普通 | `(scope_type, scope_id, status, memory_type)` | scope 过滤 + CURRENT |
| 3 | `idx_mi_type_time` | item | 普通 | `(memory_type, status, valid_until, event_start)` | as-of / 过期扫描 |
| 4 | `idx_mi_observed` | item | 普通 | `(status, observed_at)` | `active()` 倒序、BELIEF_ASOF |
| 5 | `idx_mi_source` | item | 普通 | `(source_id)` | 主来源反查 |
| 6 | **`idx_mi_dedupe`** | item | **唯一（部分）** | `(dedupe_key) WHERE status IN ('ACTIVE','CANDIDATE')` | 防重复；**恢复时的主要冲突源** |
| 7 | `idx_ms_ref` | source | 普通 | `(source_type, ref_table, ref_id)` | 回溯源 |
| 8 | **`idx_ms_dedupe`** | source | **唯一** | `(source_type, ref_table, ref_id, ref_sub_id, content_hash)` | 同一证据幂等 |
| 9 | `idx_mev_source` | evidence | 普通 | `(source_id)` | 来源引用计数 / GC |
| 10 | **`idx_mev_primary`** | evidence | **唯一（部分）** | `(memory_id) WHERE role='PRIMARY'` | 一条记忆最多一个主来源 |
| 11 | `idx_mr_from` | relation | 普通 | `(from_id, relation)` | 查"我替代了谁" |
| 12 | `idx_mr_to` | relation | 普通 | `(to_id, relation)` | WORLD_ASOF 的 `CORRECTS` 排除 |
| 13 | `idx_mrev_mem` | revision | 普通 | `(memory_id, revision_no)` | 历史 |
| 14 | `idx_mrev_at` | revision | 普通 | `(created_at)` | 按时间清理 |
| 15 | `idx_me_entity` | entity | 普通 | `(entity_id)` | 反查 |

另有**表内**约束：`relation.UNIQUE(from_id,to_id,relation)`、`revision.UNIQUE(memory_id,revision_no)`、`entity.PRIMARY KEY(memory_id,entity_id,role)`、`evidence.PRIMARY KEY(memory_id,source_id)`、`item.id AUTOINCREMENT`。

**索引数量的取舍**：C0.2 删掉了 `idx_ms_hash` / `idx_ms_indep`（2 万行省 2.3MB），因为 5 元组唯一索引的前缀已覆盖。C1-B **不加**任何"看起来有用"的索引——每加一个都要在 20k 预演库里量体积（§11.4）。

### 4.6 表关系图

```
                    ┌──────────────────────────┐
                    │    zhuli_memory_item     │ 40 列
                    │  id (PK, AUTOINCREMENT)  │
                    │  status / memory_type    │
                    │  scope_type + scope_id   │
                    │  dedupe_key (uq, partial)│
                    │  slot_hash / search_text │
                    │  pre_delete_status       │
                    └───┬───────┬───────┬──────┘
                        │       │       │
        source_id (冗余) │       │       │ id
                        │       │       └──────────────┐
                        ▼       ▼                      ▼
   ┌────────────────────────┐  ┌──────────────────┐  ┌─────────────────────┐
   │ zhuli_memory_evidence  │  │  relation        │  │  revision           │
   │ PK(memory_id,source_id)│  │ from_id → item   │  │ memory_id → item    │
   │ role: PRIMARY/SUP/DRV  │  │ to_id   → item   │  │ UNIQUE(mem, rev_no) │
   │ uq(memory_id) WHERE    │  │ UNIQUE(f,t,rel)  │  │ change_kind 9 种    │
   │    role='PRIMARY'      │  └──────────────────┘  └─────────────────────┘
   └───────────┬────────────┘
               │ source_id
               ▼
   ┌────────────────────────┐        ┌──────────────────────────┐
   │ zhuli_memory_source    │        │ zhuli_memory_entity      │
   │ id (PK)                │        │ PK(memory_id,entity_id,  │
   │ independent_key        │        │    role)                 │
   │ uq(5 元组)              │        │ C1 只建不写              │
   └────────────────────────┘        └──────────────────────────┘

   ┌────────────────────────┐        ┌──────────────────────────┐
   │ zhuli_memory_fts (FTS4)│        │ zhuli_memory_item         │
   │ rowid = item.id        │◀── 3 个触发器同步 ──│ search_text / status │
   └────────────────────────┘        └──────────────────────────┘

   外部共享（结构不改，语义改）：
   zhuli_fact_vec.fact_id ──→ item.id
   zhuli_vec_queue.fact_id ──→ item.id
```

**没有画进图的**：`zhuli_memory_tombstone` / `usage` / `ingest`（C3/C4），`zhuli_fact_v1`（只读遗留表，无引用关系）。

### 4.7 数据库版本升级策略

| 起点 | 走哪条路 | 关键动作 |
|---|---|---|
| 新装（version=0） | `onCreate` | 建 v16 全部长期核心表（**不含** `zhuli_fact`，见 X-1 / §4.8）+ `MemorySchema.createAll(db, withUnique=true)` + `detectFtsMode` |
| v16 | `onUpgrade(16,17)` → `upgradeSteps` 末尾 | `MemorySchema.createAll(withUnique=false)` → `MemoryMigrator.migrate()` → 建 FTS → 建触发器 → 建唯一索引 → 改名 |
| v15 及更早 | `upgradeSteps` 逐版补齐 → 再走 v17 | `if (oldVersion<15)` 先 `createCoreTablesV15` + `createFactTableV15`（老库确实有这张表），其余同 v16 |
| v15/v16 但 `zhuli_fact` 缺失（损坏库） | `onUpgrade` | **不建空表**。迁移前置断言抛 → 回滚 → 报错（`T-M45`）。这不是"v17 重造旧表"，而是"拒绝在缺源表的库上假装迁移成功" |
| v17 再次打开 | `onOpen` | `createCoreTablesV15` + `MemorySchema.ensureAll`（缺表补表、缺触发器补触发器，**不重建** `zhuli_fact`） |
| v17 → 任意低版本 | `onDowngrade` | **未实现**。SQLiteOpenHelper 默认抛 `SQLiteException`。C1-B 不实现降级（§12.3 说明为什么） |
| 迁移中途失败 | 事务回滚 | 版本仍 16；`lastMigrationError` 有原因；下次打开重试 |

**版本号只 +1**（16→17），`DB_VERSION` 只在 `InboxDatabase.kt` 改一处。**不写 `PRAGMA user_version`**（X-6）。

**三个建表入口的职责边界（D-7 的核心，任何一处写反都会让旧表复活或让核心表缺失）**：

| 入口 | 允许建 | 禁止建 | 理由 |
|---|---|---|---|
| `onCreate`（新装） | 长期核心表（§4.8.1 的 A 组 + C 组） | `zhuli_fact` / `zhuli_fact_v1` / 任何 `zhuli_memory_*` 之外的记忆表 | 新装库没有旧数据，建旧表只会造出"永远为空的假表" |
| `onOpen`（每次开库） | 长期核心表 + `MemorySchema.ensureAll`（7 张新表的 `IF NOT EXISTS`） | `zhuli_fact` / `zhuli_fact_v1` | 自愈只能修"结构缺失"，不能把已迁移/已废弃的表造回来 |
| `onUpgrade`（v17 迁移） | `createFactTableV15`（**仅 `oldVersion<15`**）+ `MemorySchema.createAll` + 迁移产出 | 其它 | 老库要先有源表才能迁；v15/v16 库的源表本应存在，缺失即损坏 |

---

### 4.8 表分类与建表职责（D-7 落实）

D-7 的第 7 条要求"明确哪些表属于长期核心结构，哪些属于旧记忆兼容结构"。这个分类不是文档洁癖：它直接决定 `onCreate` / `onOpen` / `onUpgrade` 三个入口的建表清单，也是 `T-M40`–`T-M45` 的断言依据。

> 计数口径：**24 张表** = A 组 9 + B 组 5 + C 组 7 + D 组 3。不含 `inbox_fts` / `zhuli_memory_fts` 的 5 张影子表（由 SQLite 自动创建）与 2 组共 6 个触发器（不是表）。

#### 4.8.1 全库表的归属（24 张表，按"v17 起还要不要创建"分类）

**A 组｜长期核心·采集与提醒（v17 前后完全一致，D-7 不动它们）**

| 表 | 列/形态 | 创建函数 | 调用点 |
|---|---|---|---|
| `inbox` | 21 列 + 4 索引 | `onCreate` 内联 | `onCreate` |
| `inbox_fts` | FTS5 外部内容表（可能建不出） | `onCreate` 内联 `try/catch` | `onCreate` |
| `inbox_fts` 的 3 个触发器 | — | `ensureFtsSync` | `onOpen`（一次性） |
| `chat` | 7 列 | `createChatTable` | `onCreate` / `onUpgrade(<3)` |
| `reminders` | 13 列 | `createReminderTable` | `onCreate` / `onUpgrade(<4)` |
| `ledger` | 6 列 | `createLedgerTable` | `onCreate` / `onUpgrade(<6)` |
| `cloud_log` | 10 列 + 1 索引 | `createCloudLogTable` | `onCreate` / `onUpgrade(<8)` |
| `skill` | 7 列 | `createSkillTable` | `onCreate` / `onUpgrade(<10)` |
| `embedding` | 6 列 | `createEmbeddingTable` | `onCreate` / `onUpgrade(<11)` |
| `source_rule` | 3 列 | `createSourceTable` | `onCreate` / `onOpen` / `onUpgrade(<13)` |

**B 组｜长期核心·Agent 与索引（原 `createCoreTables` 的一部分，v17 起继续创建）**

| 表 | 列/形态 | 归属函数 | 说明 |
|---|---|---|---|
| `zhuli_agent_task` | 13 列 + 2 索引 | `createCoreTablesV15` | Agent 状态机，与记忆无关，**绝不能动** |
| `zhuli_agent_step` | 13 列 + 1 索引 | `createCoreTablesV15` | 同上 |
| `zhuli_fact_vec` | 4 列 | `createCoreTablesV15` | 向量索引；v17 后 `fact_id` 指 `zhuli_memory_item.id`，**表结构不改**（X-9） |
| `zhuli_vec_queue` | 4 列 | `createV16Tables` | 索引队列，同上 |
| `zhuli_tool_exec` | 6 列 | `createV16Tables` | 工具执行留档 |

**C 组｜长期核心·新记忆（v17 新增，`MemorySchema` 负责）**

| 表 | 形态 | 归属函数 |
|---|---|---|
| `zhuli_memory_item` | 40 列 + 12 普通索引 + 3 唯一索引 | `MemorySchema.createAll` / `ensureAll` |
| `zhuli_memory_source` | 12 列 + 2 索引 | 同上 |
| `zhuli_memory_evidence` | 6 列 + 1 索引 + 1 唯一索引 | 同上 |
| `zhuli_memory_relation` | 6 列 + 2 索引 + 1 表内唯一 | 同上 |
| `zhuli_memory_revision` | 10 列 + 2 索引 + 1 表内唯一 | 同上 |
| `zhuli_memory_entity` | 3 列 + 1 索引 + 主键 | 同上 |
| `zhuli_memory_fts` | FTS4 虚拟表 + 5 影子表 + 3 触发器 | `MemorySchema.createFts*`（`ftsMode != NONE`） |

**D 组｜旧记忆兼容（v17 起不再创建；只读或只做迁移源）**

| 表 | v17 状态 | 谁创建 | 谁还能写 | 处置 |
|---|---|---|---|---|
| `zhuli_fact` | **不存在** | 仅 `createFactTableV15`（**只在 `oldVersion<15` 的升级路径**） | 无人 | 迁移第 12 步改名 |
| `zhuli_fact_v1` | 存在（迁移后） | **无人**（只由 `ALTER TABLE RENAME` 产生） | 无人（只读） | C3 清理（§17 第 8 项） |
| `zhuli_entity` | 存在（7 列，只读） | `createCoreTablesV15`（保留，见 §4.8.5） | **C1-B 后无写入方** | C2 评估是否并入 `zhuli_memory_entity` |

> **`zhuli_entity` 为什么留在 `createCoreTablesV15` 而不是也移出去**：D-7 第 9 条明确"不得为了修复 X-1 而直接删除其他核心表的初始化逻辑"。`zhuli_entity` 不是废弃表（C0.2 §2.5 说"保留不动"），它只是**暂时没有写入方**。移出创建逻辑会让"老库有、新库没有"，制造新的结构漂移；而保留创建的代价是 7 列的空表 + 1 个唯一索引，可以忽略。

#### 4.8.2 三个调用点（已核查，逐处替换）

`grep -n "createCoreTables" InboxDatabase.kt` 的完整结果只有 3 处：

| # | 行 | 现状 | v17 改成 | 为什么 |
|---|---|---|---|---|
| 1 | `onCreate:317` | `createCoreTables(db)` | `createCoreTablesV15(db)`<br>`MemorySchema.attach(prefs)`<br>`MemorySchema.createAll(db, withUnique = true)` | 新装库要完整当前结构，不要旧表 |
| 2 | `onOpen:339` | `createCoreTables(db)` | `createCoreTablesV15(db)`<br>`MemorySchema.ensureAll(db)` | 自愈不重建旧表 |
| 3 | `onUpgrade:787`（`oldVersion<15` 内） | `createCoreTables(db)` | `createCoreTablesV15(db)`<br>`createFactTableV15(db)` | 老库要补齐**全部** v15 表（含源表），然后才能迁 |

`onCreate` 的 `createV16Tables(db)`（318 行）与 `onOpen` 的（340 行）**保持原样**——D-7 第 4 条。

#### 4.8.3 拆分后的两个函数（签名与内容）

```kotlin
/**
 * v15 核心表（**不含** zhuli_fact）。
 * onCreate / onOpen / onUpgrade(<15) 三处共用；全部 IF NOT EXISTS。
 */
private fun createCoreTablesV15(db: SQLiteDatabase) {
    // 原样保留：zhuli_entity（7 列 + UNIQUE(kind,norm)）
    // 原样保留：zhuli_agent_task（13 列）+ idx_task_status + idx_task_dedupe
    // 原样保留：zhuli_agent_step（13 列 + UNIQUE(task,seq)）+ idx_step_task
    // 原样保留：zhuli_fact_vec（4 列，fact_id 主键）
}

/**
 * v15 的旧记忆主体表 + 3 个索引。
 * **只允许**在 onUpgrade 且 oldVersion < 15 时调用 —— 那是"老库还没有这张表"的唯一合法场景。
 * v17 及以后任何路径都不得调用（D-7 第 5 条）。
 */
private fun createFactTableV15(db: SQLiteDatabase) {
    // CREATE TABLE IF NOT EXISTS zhuli_fact（16 列，与 v16 逐字一致）
    // CREATE INDEX IF NOT EXISTS idx_fact_slot / idx_fact_source / idx_fact_status
    // KDoc 必须写明："这是迁移源表的兜底创建，不是 v17 的运行时表"
}
```

**拆分的技术要求**：

1. `createFactTableV15` 的 DDL 必须与 v16 现状**逐字一致**（16 列、3 个索引名、`AUTOINCREMENT`），否则 `MemoryMigrator` 的 `SELECT *` 列序假设会失效；
2. `createCoreTablesV15` 的内容必须是原 `createCoreTables` 的**真子集**（删掉 fact 表与 3 个 fact 索引），不许顺手改别的表；
3. 两个函数都保持 `private`，不新增 public API；
4. 函数名带 `V15` 是为了让后来者一眼看出"这是历史版本的结构"，而不是"当前版本的核心表"。

#### 4.8.4 "旧 Repository 不得静默写入空表"的两层保证

D-7 第 8 条要求的不是一个断言，而是**两条独立的防线**：

| 层 | 机制 | 违反时的表现 | 验证 |
|---|---|---|---|
| 结构层 | v17 库里 `zhuli_fact` **不存在** | 任何 `INSERT INTO zhuli_fact` 抛 `SQLiteException: no such table: zhuli_fact`（fail-loud） | `T-M42` |
| 代码层 | `MemoryRepo` 重写后不含 `TABLE_FACT` 的写语句；`Db.TABLE_FACT` 常量只被 `InboxDatabase` 的 `createFactTableV15` 与 `MemoryMigrator` 引用 | `grep -rn "TABLE_FACT" app/src/main` 只剩 2 个文件；多一个就是回归 | §8.5 自检第 5 条 |

为什么两层都要：
- 只有结构层：`onOpen` 一旦被改回"补建旧表"，fail-loud 就静默失效（这正是 X-1 的成因）；
- 只有代码层：`grep` 防不住字符串拼接（`"zhuli_" + "fact"`）或反射，表不存在才是最终的运行时保证。

**`MemoryRepo` 重写后必须删掉的方法**：`insert` / `reinforce` / `supersede`（三者**写** `zhuli_fact`）、`upsertEntity`（写 `zhuli_entity`，见 §4.8.5）、以及 `activeOf` / `active` / `activeCount` / `history` 里对 `TABLE_FACT` 的查询（改写新表）。**保留**的：向量 7 个方法（`putVector` / `markVectorIndexed` / `markVectorFailed` / `dropVector` / `pendingVectorFacts` / `enqueueAllActive` / `vectorText`）与私有的 `enqueueVector`——它们读写的是 `zhuli_fact_vec` / `zhuli_vec_queue`，属于 B 组长表，只是 JOIN 的源表从 `zhuli_fact` 换成 `zhuli_memory_item`。

#### 4.8.5 `zhuli_entity` 的写入停止（显式记录，不静默）

`MemoryRepo.ingest()` 第 50 行有 `d.entityKind?.let { upsertEntity(it, subject, now) }`，写的是 `zhuli_entity`。重写后 `MemoryRepo` 不再写旧表，这行随之删除。影响评估：

| 项 | 结论 |
|---|---|
| 有读方吗 | **没有**。`grep -rn "TABLE_ENTITY" app/src/main` 只有 `InboxDatabase` 的 DDL 与 `MemoryRepo` 的写入 |
| 有 UI 依赖吗 | 没有。`ZhuliMemoryPage` 不读实体表 |
| 会丢数据吗 | 存量行保留（表不删、行不动）；新数据不再进旧实体表 |
| 新实体去哪 | C1 不写 `zhuli_memory_entity`（C0.2 §2.5：只建不写，写入方在 C2）。`FactDraft.entityKind` 字段保留，C2 接手 |
| 怎么防止"以为还在写" | 本表 + §4.8.4 的 `grep` 自检 + `T-M40` 的结构断言（表存在但 0 行不是失败，**有行也不失败**，因为它可能有历史数据） |

> 这是 C1-B 唯一一处"功能静默减弱"：实体索引停止更新。它必须写进 C1-B5 报告，不能等用户发现"实体表怎么不长了"。C0.2 已把实体写入排到 C2，所以这不是范围外改动，而是按设计执行。

#### 4.8.6 D-7 的 6 条回归测试（编号与归属）

| # | 用例 | 归属文件 | 断言要点 |
|---|---|---|---|
| `T-M40` | 新装库的初始结构 | `MemorySchemaTest` | `zhuli_fact` 与 `zhuli_fact_v1` **都不存在**；7 张新表 + 15 索引齐全；`user_version=17` |
| `T-M41` | v16→v17 后关闭并重开 | `MemoryMigratorTest` | 连开 3 次；`zhuli_fact` 始终不存在；`zhuli_fact_v1` 行数不变；`user_version` 始终 17 |
| `T-M42` | 旧写路径 fail-loud | `MemoryMigratorTest` | 迁移后直写 `INSERT INTO zhuli_fact` 抛 `SQLiteException`（消息含 `no such table`），不静默成功 |
| `T-M43` | `onOpen` 幂等 | `MemoryMigratorTest` | 连开 3 次；`sqlite_master` 的对象数与名字集合不变；`COUNT(*)` 不变；无异常 |
| `T-M44` | 重开后新记忆数据完整 | `MemoryMigratorTest` | 重开后逐列抽检 12 个关键列（id/subject/value/status/valid_from/valid_until/time_confidence/invalidated_at/dedupe_key/scope_type/extractor/revision）+ 向量行 + evidence 行数 |
| `T-M45` | v16 库缺 `zhuli_fact` 时升级失败且不建空表 | `MemoryMigratorTest` | 抛异常 → 回滚 → `user_version=16` → `zhuli_fact` 仍不存在（**没有**被补建成空表） |

> `T-M40` 放在 `MemorySchemaTest` 而不是 `MemoryMigratorTest`：它测的是 `onCreate`（新装）路径，与迁移无关；`T-M41`–`T-M45` 都要先造 v16 库，归 `MemoryMigratorTest`。



---

## 5. v16 → v17 迁移详细方案

### 5.1 触发点与事务边界

```
SQLiteOpenHelper.getDatabaseLocked()
  └─ 已有事务 T0（helper 自己开的）
      └─ InboxDatabase.onUpgrade(db, old, new)
          ├─ backupBeforeMigration(old)        ← 在 T0 内，但尚未写入（X-10）
          ├─ db.beginTransaction()             ← 嵌套事务 T1（Android 用 savepoint 实现）
          ├─ upgradeSteps(db, old, new)        ← 现有 v2..v16 渐进补列
          │    └─ if (old < 17) MemoryMigrator.migrate(db, now)
          ├─ db.setTransactionSuccessful()      ← 提交 T1
          └─ finally db.endTransaction()
      └─ helper: db.setVersion(17)             ← 在 T0 提交前
```

`MemoryMigrator.migrate()` **不自己开事务**，它假定被 `onUpgrade` 的事务包住。理由：C0.2 §8.9 的第 12 步（改名）与第 2 步（插数据）必须在同一事务，否则会出现"数据插了但表没改名"的半迁移。若它自己开，嵌套三层，回滚语义会变复杂。

### 5.2 十三个步骤（照 C0.2 §8.9，加落地细节）

| 步 | 动作 | 实现 | 失败行为 |
|---|---|---|---|
| 0 | 前置断言 | `PRAGMA journal_mode` 不是 `wal`；`zhuli_fact` 存在；`user_version=16`。**表缺失时抛异常，不调用 `createFactTableV15` 补建**（D-7 第 5 条：v17 不重造旧表；损坏库应该报错而不是假装迁移成功） | 抛 → 回滚 |
| 1 | 建 7 张表 + 12 个普通索引（**不含** 3 个唯一索引） | `MemorySchema.createAll(db, withUnique=false)` | 抛 → 回滚 |
| 2 | 逐行迁移 item / source / evidence | `migrateRows()`，见 §5.3 | 单行失败记录并继续；末尾数量断言兜底 |
| 3 | 替代链 → relation；断链清理 | `migrateSupersedeChain()` | 抛 → 回滚 |
| 4 | 重复组合并 | `mergeDuplicates()`，见 §5.6 | 抛 → 回滚 |
| 5 | 重算 `times_reinforced` | 一条 UPDATE（见下） | 抛 → 回滚 |
| 6 | 向量校验与清理 | `verifyVectors()`，见 §5.8 | 逐条删无效项，不抛 |
| 7 | 建 FTS4 虚拟表 | `MemorySchema.createFtsTable(db)` | 失败 → `ftsMode=NONE`，**不中止迁移** |
| 8 | 一次性灌 FTS | `INSERT INTO … SELECT … WHERE status<>'DELETED'` | 失败 → `ftsMode=NONE` |
| 9 | 建 3 个触发器 | `MemorySchema.createFtsTriggers(db)` | 失败 → `ftsMode=APP_SYNC`（探测决定） |
| 10 | 建 `idx_mi_dedupe` | 建失败时打印前 5 个重复键 | 抛 → 回滚 |
| 11 | 对齐 `sqlite_sequence` | `UPDATE sqlite_sequence SET seq=(SELECT MAX(id) …)` | 抛 → 回滚 |
| 12 | `ALTER TABLE zhuli_fact RENAME TO zhuli_fact_v1` | — | 抛 → 回滚 |
| 13 | （不写 `user_version`） | 交给 helper（X-6） | — |

第 5 步的 SQL（C0.2 §5.2 的派生定义）：

```sql
UPDATE zhuli_memory_item SET times_reinforced = (
  SELECT MAX(0, COUNT(DISTINCT s.independent_key) - 1)
  FROM zhuli_memory_evidence e JOIN zhuli_memory_source s ON s.id = e.source_id
  WHERE e.memory_id = zhuli_memory_item.id
);
```

### 5.3 逐字段迁移（代码级）

`MemoryMigrator.migrateRow(c: Cursor): Row?` 的核心，一列不漏：

| 目标列 | 取值 | 失败/边界 |
|---|---|---|
| `id` | `c.getLong(id)` | 显式写入，保真 |
| `memory_type` | `if (legacyKind == "PREFERENCE") PREFERENCE else FACT` | — |
| `subject` | `c.str(subject).trim()` | 空 → `skipped++`，返回 null |
| `subject_key` | `MemoryText.norm(subject)` | 空（全标点）→ 用 `subject.lowercase()` 兜底，仍空则跳过 |
| `predicate` | `trim()` | 空 → 保留空串（C0.2 不要求跳过；空谓词仍是"属性名缺失"的事实，但**必须**参与 dedupe，不能丢） |
| `qualifier_key` | `""` | 常量 |
| `value` | `trim()` | 空 → `skipped++` |
| `content` | `"$subject｜$predicate｜$value"` | 与 `Fact.display` 逐字一致 |
| `normalized_content` | `norm(content)` | — |
| `dedupe_key` | `MemoryText.dedupeKey(GLOBAL, "", memoryType, subjectKey, predicate, "", norm(value), bucket)` | `bucket` 见 §5.4 |
| `slot_hash` | `MemoryText.slotHash(GLOBAL, "", memoryType, subjectKey, predicate, "")` | 与 value 无关 |
| `search_text` | `MemoryText.searchText(content)` | 2 字滑窗 |
| `status` | 4→6 映射 | **未知值 → `CANDIDATE` + `unknown_status++`**（D-6 第 3/4 条：不得 fallback `ACTIVE`） |
| `cardinality` | `legacyKind in (EXPRESS,ORDER,MONEY,SCHEDULE)` → `MULTI` else `SINGLE` | 另：`payload_json.cardinality` 存在则覆盖 |
| `confidence` | `min(旧值, basis 上限)` | 被降则 `clamped++` |
| `confidence_basis` | 5 条映射（见下表） | — |
| `source_id` | `evidence` 的 PRIMARY 的 source id | — |
| `scope_type` / `scope_id` | `'GLOBAL'` / `''` | 常量 |
| `sensitivity` | `MemoryGuard.classify(content)`：SECRET → 整行隔离；SENSITIVE → 1；否则 0 | SECRET → `quarantined++`，**不插** |
| `event_start` / `event_end` | `0` | 旧表没有 |
| `valid_from` | 原值（0 保留 0） | **不设 `time_confidence`** |
| `valid_until` | **`0`** | 常量，不读 `valid_to` |
| `observed_at` | 原值；0→`created_at`；仍 0→`now` | 三个来源按序 |
| `recorded_at` | `created_at` | — |
| `updated_at` | 原值；0→`created_at` | — |
| `last_confirmed_at` | `basis==USER_CONFIRMED ? updated_at : 0` | — |
| `invalidated_at` | `SUPERSEDED → (valid_to ?: updated_at)`；`DELETED → updated_at`；其它 0 | 旧 `valid_to` 的**唯一**去处 |
| `time_confidence` | **`'NONE'`** | 常量，不按条件分支 |
| `superseded_by` | 原值（目标不存在则清 0，§5.7） | — |
| `revision` | `1` | 常量 |
| `extractor` | `'import:legacy'` | 常量，自检全靠它定位迁移行 |
| `legacy_kind` | `FactKind.name` | 保真 |
| `payload_json` | `{"migrated_from":<id>}`；**仅未知状态行**追加 `"legacy_status":"<redact 后截 32 字>"` | 便于回溯；**不放任何原文** |
| `deleted_at` | `status=DELETED ? updated_at : 0` | — |
| `pre_delete_status` | `''` | 迁移不产生（§5.4 禁止 4） |
| `times_reinforced` / `times_retrieved` / `last_used_at` | `0`（前者第 5 步重算） | — |

**basis 映射（逐字照 C0.2 §8.3）**

| `source_type` | 条件 | `confidence_basis` | 上限 |
|---|---|---|---|
| `USER` | `source_text` 以 `"用户纠正："` 开头 | `USER_CONFIRMED` | 1.00 |
| `USER` | 其它 | `USER_STATED` | 0.95 |
| `INBOX` | — | `RULE_EXTRACTED` | 0.85 |
| `INFERRED` | — | `MODEL_INFERRED` | **0.65** |
| `SYSTEM` | — | `EXTERNAL_VERIFIED` | 0.90 |

### 5.4 四条禁止（写错任何一条就等于让数据说谎）

| # | 禁止 | 为什么 | 谁盯 |
|---|---|---|---|
| 1 | **不得**把 `valid_from>0` 标成 `DAY`/`MONTH`/`YEAR` | 旧 `valid_from` 全部由 `ingest()`/`correct()` 写成 `now`（`MemoryRepo.kt:79/114`），标精度等于伪造 | `T-M36` |
| 2 | **不得**把 `valid_to` 写进 `valid_until` | 旧 `valid_to` 是"我们何时不再相信"（`MemoryRepo.kt:431` 写 `now`），是世界线的伪终点 | `T-M37` |
| 3 | **不得**因 `valid_from` 被清零就让旧事实变成"当前值" | `LB` 退回 `observed_at` 后，一条本已过期的值会重新生效。规则：清零只发生在 R0（运行时），迁移**不清零**（保留原值 + `NONE`），两者不冲突 | `T-M36` / `T-T40` |
| 4 | **不得**为迁移行写 `pre_delete_status` | 迁移行从没被运行时删过；写了会让"恢复回原状态"误以为知道原状态 | `T-M36` 的伴生断言 / 6.6 #10 |

第 3 条的澄清（容易被误解）：迁移**保留** `valid_from` 原值（不清零），因为清零后 `LB` 同样退回 `observed_at`，行为完全一致，但保留原值可让 UI 显示"系统记录于…"。真正危险的是"保留原值 + 标 `DAY`"，那会让 `LB` 直接取 `valid_from` 并宣称它是事实起点。所以 #1 是主约束，#3 是它的防护性说明。

### 5.5 来源与证据（伪码）

```kotlin
// 对每一行 fact（status 不为 SECRET 被隔离的行）
val excerpt = MemoryGuard.excerpt(c.str(sourceText).ifBlank { content })   // redact + ≤120
val hash    = MemoryText.sha256Hex(MemoryText.norm(c.str(sourceText).ifBlank { content }))
val refId   = c.getLong(sourceId)
val key     = SourceKey(sourceType, refTable = "zhuli_fact", refId = refId.toString(),
                        refSubId = "", contentHash = hash)
val sourceId = sourceCache.getOrPut(key) {
    // INSERT OR IGNORE 后 SELECT id（唯一索引 idx_ms_dedupe 保证幂等）
}
val independent = if (refId != 0L) "$sourceType|zhuli_fact|$refId" else "$sourceType|-|$hash"
INSERT evidence(memory_id = factId, source_id = sourceId, role = 'PRIMARY',
                first_seen_at = observedAt, last_seen_at = observedAt, seen_count = 1)
UPDATE item SET source_id = sourceId WHERE id = factId
```

三条约束：

1. **同一 `source_id` 抽出的多条事实共享 `independent_key`，但各自有 source 行**（`content_hash` 不同）——这是"一条来源产生多条记忆"的正确表达，C0.2 §8.4 已说明。
2. `source_text` 为空时用 `content` 兜底，保证 `excerpt` 不为空（UI 要显示"凭什么"）。
3. `trust`：`USER → 3`，其它 `1`。

### 5.6 重复合并（只合并真重复）

触发条件（建唯一索引**之前**执行）：

```sql
SELECT dedupe_key, COUNT(*) FROM zhuli_memory_item
WHERE status IN ('ACTIVE','CANDIDATE') GROUP BY dedupe_key HAVING COUNT(*)>1
```

每组按 C0.2 §8.8 处理。实现要点：

| 项 | 实现 | 容易写错的地方 |
|---|---|---|
| 保留行 | `rank(basis)` desc → `last_confirmed_at` desc → `observed_at` asc → `id` asc | 别用 `id desc`（会让通知盖过用户陈述） |
| `valid_from` | 组内**全为正**取 `min`；**任一为 0** 取 `0` | "用已知起点代表未知起点"是错的 |
| `valid_until` | `0` | 迁移行本来就全是 0，别"合并区间" |
| `time_confidence` | `NONE` | 合并不产生新时间知识 |
| `observed_at` | `min` | — |
| `last_confirmed_at` | `max` | — |
| 被合并行 | `status=SUPERSEDED`, `superseded_by=保留行`, `invalidated_at=now` | 别写成 `DELETED` |
| 关系 | `relation(保留, 被合并, 'SAME_AS')` | 方向：from 是保留行 |
| 证据 | 复制被合并行的 evidence 指向保留行，`role='SUPPORTING'`；**被合并行的 evidence 原样保留** | C0.2 §9.7：初稿"搬走再删"会让 6.6 #7 不成立 |
| revision | 保留行写一条 `MERGE` | 迁移里唯一需要 revision 的地方 |

### 5.7 ID 保真与断链

```kotlin
// 插入用显式 id，绝不让 AUTOINCREMENT 分配
db.execSQL("INSERT INTO zhuli_memory_item(id, …) VALUES(?, …)", args)
```

`sqlite_sequence` 的处理（C0.2 §8.6）：显式插大 id 时 SQLite 会自动跟进，但迁移末尾仍显式对齐一次——因为"逐行插入失败被跳过"时 `MAX(id)` 可能小于某个已插入的 id（不会，但防御性对齐成本为 0）。

断链：

```kotlin
// superseded_by > 0 但目标行不存在（被 SECRET 隔离或空值跳过）
UPDATE zhuli_memory_item SET superseded_by = 0 WHERE id = ?   // 只清指针
// 不动 invalidated_at：清了会让一条已被替代的记忆在信念线里复活
```

### 5.8 向量校验

```
for each row in zhuli_fact_vec:
    space 能解析出 dim（"provider|dim"）
    且 LENGTH(vec) == dim*4
    且 zhuli_memory_item 存在该 fact_id
  → 保留
否则:
    DELETE FROM zhuli_fact_vec WHERE fact_id = ?
    INSERT OR IGNORE INTO zhuli_vec_queue(fact_id, attempts=0, at=now)
```

**不做重新 embed**：迁移不跑模型（C0.2 §8.7）。换 space 的重建由 `MemoryIndexer.drainOnce` 的 existing 逻辑处理（`MemoryIndexer.kt:169-174`）。

### 5.9 数量闭合与断言

迁移末尾必须自证：

```kotlin
val src        = count("SELECT COUNT(*) FROM zhuli_fact_v1")        // 改名后
val inserted   = count("SELECT COUNT(*) FROM zhuli_memory_item WHERE extractor='import:legacy'")
val quarantined= report.quarantined    // SECRET
val skipped    = report.skipped        // 空 subject / 空 value
check(src == inserted + quarantined + skipped) { "数量不闭合：$src != $inserted+$quarantined+$skipped" }
```

5 条时间不变量（C0.2 §8.1，**必须带 `extractor`**）：

| # | 断言 SQL | 期望 |
|---|---|---|
| 1 | `COUNT(*) WHERE extractor='import:legacy' AND time_confidence<>'NONE'` | 0 |
| 2 | `COUNT(*) WHERE extractor='import:legacy' AND valid_until<>0` | 0 |
| 3 | `COUNT(*) WHERE extractor='import:legacy' AND valid_from<>0 AND valid_from<>observed_at` | **告警不中止** |
| 4 | `COUNT(*) WHERE status='SUPERSEDED' AND invalidated_at=0` | 0 |
| 5 | `COUNT(*) WHERE extractor='import:legacy' AND status='DELETED' AND pre_delete_status<>''` | 0 |

第 6 条（**D-6 新增**，状态不变量）：

| # | 断言 SQL | 期望 |
|---|---|---|
| 6 | `COUNT(*) WHERE extractor='import:legacy' AND payload_json LIKE '%"legacy_status"%' AND status<>'CANDIDATE'` | 0 |
| 6b | `report.unknownStatus` 与旧库 `status NOT IN ('ACTIVE','UNCONFIRMED','SUPERSEDED','DELETED')` 的行数 | 相等 |

第 6 条的含义：迁移遇到无法识别的状态值时，**只能落到 `CANDIDATE`**（非当前、不可自动激活），任何落到 `ACTIVE` / `SUPERSEDED` / `EXPIRED` / `REJECTED` / `DELETED` 的未知状态行都是缺陷。`legacy_status` 是唯一的定位标记，C3 可以据此人工核对这批行。

第 3 条为什么"告警不中止"：它检测的是"有没有未知写入方动过 `valid_from`"（C0.2 §8.1 的注）。中止会让一个**未来**的良性改动（有人加了用户时间入口）变成升级失败；告警能让它被发现，又不会拦住用户。


### 5.10 日志规格（不含敏感原文）

迁移日志是一行 JSON（`Log.i(TAG, ...)`），字段固定：

```json
{"phase":"v16->v17","src":2195,"inserted":2190,"quarantined":3,"skipped":2,
 "merged":7,"sources":2180,"evidence":2190,"vec_kept":1,"vec_dropped":0,
 "clamped":12,"vf_mismatch":0,"unknown_status":0,"fts_mode":"TRIGGER","ms":1840}
```

**禁止出现的字段**：`subject` / `value` / `content` / `source_text` / 行 `id` / `dedupe_key`。
理由：`id` 可反查旧表；内容本身就是敏感数据。计数足够回答"有没有、有几条"（C0.2 §14.2）。

失败时额外一条：`{"phase":"row","error":"<异常类名>: <message 前 80 字>"}`，且 message 必须过 `SensitiveGuard.redact`——SQLite 的异常消息会带参数值。

### 5.11 失败与回滚

| 失败点 | 行为 | 用户可见 |
|---|---|---|
| 前置断言（WAL / 表缺失） | 抛 → 回滚 | 升级失败，App 回退到"数据库打不开"的既有处理 |
| 建表失败（磁盘满/DDL 不支持） | 抛 → 回滚 | 同上 |
| 单行插入失败 | 记 `report.rowErrors++`，继续 | 无（末尾数量断言会兜住） |
| 数量不闭合 | 抛 → 回滚 | 升级失败 + `lastMigrationError` |
| 唯一索引建不起来 | 打印前 5 个重复键 → 抛 → 回滚 | 同上（说明 §5.6 有遗漏） |
| FTS 建表/触发器失败 | **不中止** → `ftsMode` 降档 | 检索变慢但正确 |
| 改名失败 | 抛 → 回滚（数据已在新表，但事务回滚会一起撤） | 升级失败 |

### 5.12 WAL 前置断言（C0.2 §8.10 的落地）

```kotlin
val mode = db.rawQuery("PRAGMA journal_mode", null).use { if (it.moveToFirst()) it.getString(0) else "" }
if (mode.equals("wal", ignoreCase = true)) {
    db.execSQL("PRAGMA wal_checkpoint(FULL)")   // 把 WAL 并回主库，保证后续文件复制完整
}
```

C1-B 只做"断言 + checkpoint"，**不改** `backupBeforeMigration` 的复制方式（X-10）。

### 5.13 Android 设备耗时测试设计（**禁止用桌面 Python 的数**）

C0.2 的预演是 host Python（0.17–0.21 ms/行），C0.2 A-3 明确说"设备 CPU/IO 与 Kotlin 实现都不同"。所以 C1-B2 必须自己测：

| 项 | 设计 |
|---|---|
| 载体 | `app/src/androidTest/java/com/zhuli/memory/MemoryMigrationTimingTest.kt`（新增） |
| 数据 | 用 `MemoryTestFixture.seedLegacy(db, n)` 在设备上**现场生成** n 行 v16 库（n ∈ {1_000, 5_000, 20_000}），不依赖 PC 上的 `.db` 文件 |
| 计时 | `SystemClock.elapsedRealtime()` 包住整段 `onUpgrade`；另计时 `migrateRows` 单独一段 |
| 资源 | `Debug.getMemoryInfo` 取 `totalPss` 峰值差；`StatFs` 取库文件前后体积 |
| 记录 | 每条一个 `Log.i`，字段同 §5.10 + `device` / `sdk` / `sqlite` / `rows` / `ms` / `pss_kb` |
| 门槛 | 5k ≤ 3s（C0.2 A-3）；20k ≤ 12s（线性外推 4×）；超门槛 → 启用 C0.2 §9.6 的分批方案（C1-B 内实现，不推迟） |
| 不算数的 | Robolectric 的耗时（JVM + 不同 SQLite，只用于相对比较） |

---

## 6. FTS 功能性探测与两条同步路径

### 6.1 C1-A 的结论如何变成代码

C1-A 的结论是 `TRIGGER`，但附了三条硬约束（C1-A §7.2）：

| C1-A 的约束 | C1-B 的落地 |
|---|---|
| 必须功能性探测，不是 DDL 探测 | `detectFtsMode` 第 5 步插哨兵行并核对 FTS 行数 +1（§6.2） |
| 探测与使用**同一连接** | `detectFtsMode(db)` 接收的就是 `onCreate`/`onOpen` 的 `db`，不另开 |
| 结论落盘 + 探测版本 | `SharedPreferences` 键 `mem_fts_mode_v1` / `mem_fts_probe_at` / `mem_fts_probe_sqlite` |

C1-A 还实测了一条**必须写进代码**的事实：`trusted_schema=OFF` 时触发器**建得成、写才炸**（`SQLiteException: unsafe use of virtual table`）。所以第 2 步（建触发器）通过**不能**作为 `TRIGGER` 的依据。

### 6.2 `detectFtsMode` 的七步

```kotlin
// MemorySchema.kt
enum class FtsMode { TRIGGER, APP_SYNC, NONE }

fun detectFtsMode(db: SQLiteDatabase, cache: FtsModeCache?): FtsMode {
    cache?.get()?.let { return it }                       // 命中且版本一致 → 直接用

    val tmp = "zhuli_fts_probe_${System.nanoTime()}"      // 独立表名，绝不碰用户表
    try {
        // 1) 建 FTS4 虚拟表（这一步失败 → NONE）
        try { db.execSQL("CREATE VIRTUAL TABLE $tmp USING fts4(search_text, tokenize=simple)") }
        catch (t: Throwable) { return finish(NONE, t) }

        // 2) 建"探测用 item 表 + 3 个触发器"（触发器体与真实 DDL 逐字一致，只换表名）
        db.execSQL("CREATE TABLE ${tmp}_item(id INTEGER PRIMARY KEY, search_text TEXT, status TEXT)")
        val triggers = listOf(
            "CREATE TRIGGER ${tmp}_ai AFTER INSERT ON ${tmp}_item BEGIN " +
                "INSERT INTO $tmp(rowid, search_text) VALUES (new.id, new.search_text); END",
            "CREATE TRIGGER ${tmp}_au AFTER UPDATE OF search_text, status ON ${tmp}_item BEGIN " +
                "DELETE FROM $tmp WHERE rowid = old.id; " +
                "INSERT INTO $tmp(rowid, search_text) SELECT new.id, new.search_text WHERE new.status <> 'DELETED'; END",
            "CREATE TRIGGER ${tmp}_ad AFTER DELETE ON ${tmp}_item BEGIN " +
                "DELETE FROM $tmp WHERE rowid = old.id; END",
        )
        var triggerOk = 0
        for (sql in triggers) {
            try { db.execSQL(sql); triggerOk++ } catch (t: Throwable) { /* 记录，不抛 */ }
        }
        // 2 失败 → APP_SYNC（虚拟表能用，只是触发器不行）
        if (triggerOk < 3) return finish(APP_SYNC, lastError)

        // 3–6) 功能性验证：插哨兵 → 索引 +1 → 改词 → 旧词消失 → 删 → 索引 -1
        val before = ftsCount(db, tmp)                    // 0
        db.execSQL("INSERT INTO ${tmp}_item(id, search_text, status) VALUES (1, '会议 议提 提醒', 'ACTIVE')")
        if (ftsCount(db, tmp) != before + 1) return finish(APP_SYNC, null)   // 建成功但没同步
        if (!matches(db, tmp, "\"会议\" \"提醒\"")) return finish(APP_SYNC, null)
        db.execSQL("UPDATE ${tmp}_item SET search_text='快递 递到 到了' WHERE id=1")
        if (matches(db, tmp, "\"会议\"")) return finish(APP_SYNC, null)      // 旧词还在
        if (!matches(db, tmp, "\"快递\"")) return finish(APP_SYNC, null)     // 新词没进
        db.execSQL("UPDATE ${tmp}_item SET status='DELETED' WHERE id=1")
        if (ftsCount(db, tmp) != before) return finish(APP_SYNC, null)
        db.execSQL("UPDATE ${tmp}_item SET status='ACTIVE' WHERE id=1")
        if (ftsCount(db, tmp) != before + 1) return finish(APP_SYNC, null)   // 恢复没重挂
        db.execSQL("DELETE FROM ${tmp}_item WHERE id=1")
        if (ftsCount(db, tmp) != before) return finish(APP_SYNC, null)

        return finish(TRIGGER, null)
    } catch (t: Throwable) {
        return finish(APP_SYNC, t)                        // 任何未预期异常 → 不赌 TRIGGER
    } finally {
        // 清理：触发器 → item 表 → 虚拟表（顺序不能反）
        runCatching { db.execSQL("DROP TRIGGER IF EXISTS ${tmp}_ai") }
        runCatching { db.execSQL("DROP TRIGGER IF EXISTS ${tmp}_au") }
        runCatching { db.execSQL("DROP TRIGGER IF EXISTS ${tmp}_ad") }
        runCatching { db.execSQL("DROP TABLE IF EXISTS ${tmp}_item") }
        runCatching { db.execSQL("DROP TABLE IF EXISTS $tmp") }
    }
}
```

四点必须照做：

1. **`finish()` 里写缓存**（模式 + 时间 + `sqlite_version()`），只在 `finally` 之前调用一次；
2. **任何异常都落 `APP_SYNC`，绝不落 `TRIGGER`**——这正是 C1-A 的负向对照教的事；
3. 哨兵行用 `id=1`，探测表是新建的，不会与真实数据冲突；
4. 探测在 `onCreate`/`onOpen` 里只跑一次（缓存命中则跳过），且**不在主线程之外另起连接**。

### 6.3 三档下的写路径差异

| 操作 | `TRIGGER` | `APP_SYNC` | `NONE` |
|---|---|---|---|
| insert | 不碰 FTS | `syncFts(id)` | 不碰 |
| update（`search_text`/`status`） | 不碰 | `syncFts(id)` | 不碰 |
| 软删 | 不碰（触发器按 `status` 删索引） | `deleteFts(id)` | 不碰 |
| 恢复 | 不碰 | `syncFts(id)` | 不碰 |
| 硬删 | — | — | — （C3） |
| 迁移灌数据 | 先 `INSERT…SELECT` 再建触发器 | 同左（灌完再靠 `APP_SYNC` 维护增量） | 跳过 |
| 检索 | `MATCH` | `MATCH` | `LIKE` |

**唯一的写入职责**：`MemoryStore` 里 `if (ftsMode == APP_SYNC) syncFts(id)`，出现在 4 个地方（insert / update / softDelete / restore）。**不许**散落在 `MemoryRepo` 或调用方。

`syncFts` 的实现（幂等，可重复调）：

```sql
DELETE FROM zhuli_memory_fts WHERE rowid = ?;
INSERT INTO zhuli_memory_fts(rowid, search_text)
  SELECT id, search_text FROM zhuli_memory_item WHERE id = ? AND status <> 'DELETED';
```

### 6.4 探测结果的持久化与失效

| 键 | 值 | 失效条件 |
|---|---|---|
| `mem_fts_mode_v1` | `TRIGGER` / `APP_SYNC` / `NONE` | 见下 |
| `mem_fts_probe_at` | 探测时间（ms） | — |
| `mem_fts_probe_sqlite` | `sqlite_version()` | **与当前不一致 → 重探**（系统升级换了 SQLite） |

强制重探的三个时机：① 首次安装；② `DB_VERSION` 变化（升级）；③ `sqlite_version()` 变了。
另有一个"手动重探"入口供 `selfCheck` 用（C1 不暴露 UI，只在 debug 菜单）。

### 6.5 索引不一致的兜底

`MemoryStore.selfCheck()` 里加两条（C0.2 §6.6 的第 1、2 条，已在清单里）：

```sql
-- FTS 有、item 没有
SELECT rowid FROM zhuli_memory_fts
WHERE rowid NOT IN (SELECT id FROM zhuli_memory_item WHERE status<>'DELETED');
-- item 活着、FTS 没有（ftsMode=NONE 时跳过，那是已知降级）
SELECT id FROM zhuli_memory_item i WHERE i.status<>'DELETED'
  AND NOT EXISTS (SELECT 1 FROM zhuli_memory_fts f WHERE f.rowid=i.id);
```

第 2 条在 `APP_SYNC` 下**就是漏同步的检测器**。C1 的 `selfCheck` 只报告不自动修（自动修在 C3 的维护任务里），但提供 `MemoryStore.rebuildFts()` 供 `MemoryIndexer` 之外的手动入口调用。

### 6.6 不允许做的事

| # | 禁止 | 理由 |
|---|---|---|
| 1 | 把 `ftsMode` 写成 `const val TRIGGER` | C1-A 只在**一台**机器上验证；A-6 未关闭 |
| 2 | 用 `CREATE TRIGGER` 是否抛错当唯一依据 | C1-A §5 实测"建成功、写才炸" |
| 3 | 探测开新连接 | 结果依赖连接级 pragma（C1-A §7.2） |
| 4 | 探测失败后静默继续 | 索引不一致会让检索"看起来正常但漏结果" |
| 5 | 用 Robolectric 的结果决定模式 | A-2 已证伪（Robolectric 复现不出失败，方向都相反） |
| 6 | `ftsMode=NONE` 时跳过 `search_text` 的写入 | 那是 LIKE 兜底的唯一依据 |

---

## 7. Store / Manager 设计

### 7.1 四个类的职责（C0.2 §10.6 的展开）

```
┌──────────────────────────────────────────────────────────────┐
│ MemoryRepo（适配层，485 → ~150 行）                            │
│   · 27 个公开签名不变                                          │
│   · 只做：MemoryDraft ↔ FactDraft 转换、MemoryItem → Fact 投影  │
│   · 向量 7 个方法保持直写（§3.2 的边界）                         │
│   · 禁止：写 SQL、开事务、判冲突、碰 pre_delete_status           │
└───────────────────────┬──────────────────────────────────────┘
                        │ 调用
┌───────────────────────▼──────────────────────────────────────┐
│ MemoryStore（唯一写入口 + 全部查询，~520 行）                    │
│   写：insert / correct / confirm / resolveConflict /           │
│       softDelete / restore / touchUsed                         │
│   查：byId / current / asOfWorld / asOfBelief / search /       │
│       candidates / history / counts                            │
│   维护：selfCheck / rebuildFts / syncFts                       │
│   · 单事务边界（§7.3）                                          │
│   · 禁止：读 zhuli_fact_v1、判 FactKind、构造 Fact              │
└──────┬──────────────────────────────────┬────────────────────┘
       │ 用                                │ 用
┌──────▼───────────────┐        ┌─────────▼────────────────────┐
│ MemoryPolicy（~130）  │        │ MemoryGuard（~90）            │
│  rank / cardinalityOf│        │  classify / excerpt /        │
│  decide(R0–R9)       │        │  validateScope / 长度上限     │
│  纯函数，无 IO        │        │  纯函数，无 IO                │
└──────────────────────┘        └──────────────────────────────┘

旁路（不在 Store 内）：
MemoryMigrator（~250）  只在 onUpgrade 内被调一次
MemorySchema（~150）    只在 onCreate / onOpen / Migrator 内被调
MemoryText（~90）       纯函数，被所有类依赖
```

`MemoryManager` **不新建**：C0.2 §4.8 提到的 `MemoryManager.correct()` 在 C1 由 `MemoryStore.correct()` 承担；"Manager"这层等到 C2 有"提取→判定→写入"的编排需求时再加，C1 提前建会变成空壳。

### 7.2 写入 API（签名与语义）

```kotlin
class MemoryStore(private val db: InboxDatabase) {

    /** 唯一写入口。返回结果携带"落到哪个状态"，调用方不需要猜。 */
    fun insert(draft: MemoryDraft, now: Long): InsertResult

    /** 用户纠错：继承 valid_from / time_confidence；旧行 valid_until=新起点 + CORRECTS。 */
    fun correct(id: Long, newValue: String, now: Long): Long?

    /** 用户确认候选（无新值）。 */
    fun confirm(id: Long, now: Long): Boolean

    /** 裁决冲突：keep 保持 ACTIVE，drop → SUPERSEDED + 关系。 */
    fun resolveConflict(keepId: Long, dropId: Long, now: Long)

    /** 软删：任何状态都可删；记录 pre_delete_status。 */
    fun softDelete(id: Long, now: Long): DeleteResult

    /** 恢复：回到 pre_delete_status；冲突时降级或 AlreadyPresent。 */
    fun restore(id: Long, now: Long): RestoreResult

    /** 检索一次就 +1（不触发 FTS，因为 UPDATE 不含 search_text/status）。 */
    fun touchUsed(ids: List<Long>, now: Long)
}
```

`MemoryDraft` 是 `[实现补充]`——C0.2 用 `draft` 这个词但没定义。字段：

```kotlin
data class MemoryDraft(
    val memoryType: MemoryType,
    val subject: String,
    val predicate: String,
    val value: String,
    val qualifierKey: String = "",
    val cardinality: Cardinality? = null,   // null → 由 MemoryPolicy 推
    val basis: ConfidenceBasis,
    val confidence: Double,
    val scope: MemoryScope,
    val worldFrom: Long = 0,                // 用户明确给的起点；0 = 不知道
    val timeConfidence: TimeConfidence = NONE,
    val worldUntil: Long = 0,
    val eventStart: Long = 0,
    val eventEnd: Long = 0,
    val source: SourceRef,                  // type + 可选 ref
    val excerpt: String,                    // 原文片段，MemoryGuard 会再脱敏
    val intent: WriteIntent = WriteIntent.NEW,   // NEW | CORRECT | CHANGE
    val forceCandidate: Boolean = false,    // 对应 activateThreshold 命中（X-4）
    val legacyKind: String = "",
)

sealed class InsertResult {
    data class Created(val id: Long, val status: MemoryStatus) : InsertResult()
    data class Reinforced(val id: Long, val independentSources: Int) : InsertResult()
    data class Suppressed(val reason: String) : InsertResult()      // R1/R3 抑制
    data class Rejected(val reason: RejectReason) : InsertResult()  // R0（含 SECRET）
    data object Duplicate : InsertResult()
}
```

`InsertResult` 的四种结果直接对应 R0–R9 的四个出口，`MemoryRepo.ingest` 用它算"新建行数"（只有 `Created` 计数）。

### 7.3 事务边界（每个写方法一个事务，不嵌套）

| 方法 | 事务内做的事 | 提交后做的事 |
|---|---|---|
| `insert` | R0 校验 → R2/R3 查重 → R4 取槽 → R5/R7/R8/R9 决策 → 写 item + source + evidence + relation + revision → 回填 `source_id` | `onIndexRequested`（由 `MemoryRepo` 触发） |
| `correct` | 读旧行 → 写新行（继承）→ 旧行 `SUPERSEDED` + `valid_until` → `CORRECTS` → 两条 revision | 入向量队列 |
| `confirm` | 读 → 校验 CANDIDATE → 改 ACTIVE + `last_confirmed_at` → revision(`CONFIRM`) | 入队 |
| `resolveConflict` | 读两行 → `drop` 改 SUPERSEDED + `invalidated_at` → `SUPERSEDES` → revision | 删 drop 的向量 |
| `softDelete` | 读 `pre` → 改 DELETED（`pre_delete_status` 仅首次、`invalidated_at` 仅 0）→ 删 FTS/向量/队列 → revision(`DELETE`) | `optimize`（C3 才做） |
| `restore` | 读 → 定 `target` → 查 `dedupe_key` → 查槽位 → 改状态 → 关系 → revision(`RESTORE`) | 入队（仅 ACTIVE） |
| `touchUsed` | 单条 UPDATE（不含 `search_text`/`status`，所以不触发 FTS） | — |

**禁止跨方法复用事务**：`insert` 内部**不调** `correct`，`restore` 内部**不调** `insert`。需要组合的场景（如"删除后重新写入同值"）由调用方顺序调两个方法，各自成事务——因为中间状态是合法的（旧行 DELETED、新行还没建）。

### 7.4 并发与线程

| 项 | 设计 | 理由 |
|---|---|---|
| 并发模型 | 依赖 SQLite 的单写者模型 + Android 的 `SQLiteDatabase` 连接池 | 不自建锁 |
| 写串行化 | 所有写方法在 `db.writableDatabase` 上开事务；SQLite 会在库级加写锁 | 不需要应用层 `synchronized` |
| 读 | `db.readableDatabase`，可与写并行（`journal_mode=delete` 下读会等写提交） | — |
| 长事务 | **禁止**在事务内做 IO（网络、文件、embed） | 会持有写锁 |
| 调用线程 | 允许任意线程；`hybridSearch` 是 `suspend`，但内部不切线程（保持现有行为） | 现有调用方已在协程/工作线程 |
| 主线程 | `onCreate`/`onOpen` 里的 `createAll`/`detectFtsMode`/`migrate` 都在打开库的线程上；C1-B 加**线程断言**（debug 下 `Looper.myLooper() == Looper.getMainLooper()` 时 `Log.w`） | A-4 未验证；不能假设不在主线程 |
| 死锁 | 不在事务内调 `db.writableDatabase`（会递归） | `InboxDatabase.kt:1290` 的注释已踩过 |

**`[实现补充]` 并发测试**：`T-D31` 的变体——两个线程同时对同一 `dedupe_key` 调 `insert`，断言最终只有 1 条 ACTIVE、另一条走 `Reinforced` 或被 SQLite 的写锁串行化后命中 R2。这条加进 `MemoryStoreTest`，不占 57 条的编号（属于"实现质量"而非"架构用例"）。

### 7.5 状态迁移矩阵（谁能到谁）

| from \ to | ACTIVE | CANDIDATE | SUPERSEDED | EXPIRED | DELETED | REJECTED |
|---|---|---|---|---|---|---|
| （新建） | `insert` R4b/R5/R7 | `insert` R8/R9/force | — | — | — | — |
| ACTIVE | `confirm`(自) | `restore` 的 Demoted | `correct` / `resolveConflict` / R7/R8 | R4a / TASK 到期 | `softDelete` | — |
| CANDIDATE | `confirm` | `restore` | R7(替代候选? 否) | — | `softDelete` | （C2） |
| SUPERSEDED | **禁止**（只能经 `restore` 且回 SUPERSEDED） | 禁止 | `restore` | — | `softDelete` | — |
| EXPIRED | 禁止 | 禁止 | 禁止 | `restore` | `softDelete` | — |
| DELETED | 仅 `restore`（且需重查索引） | 仅 `restore` | 仅 `restore` | 仅 `restore` | — | — |
| REJECTED | 禁止 | 禁止 | 禁止 | 禁止 | `softDelete` | `restore` |

**"禁止"的实现方式**：`MemoryStore` 的 UPDATE 语句带 `WHERE id=? AND status=?`（乐观锁），`changes()==0` 时抛 `IllegalStateException`。**不靠调用方守规矩。**

### 7.6 删除与恢复的时序

**软删**（`softDelete`，单事务）：

```
0  SELECT status, pre_delete_status, invalidated_at FROM item WHERE id=:id
   ├─ 无行 → NotFound
   └─ status='DELETED' → NotDeleted('DELETED')      // 幂等
1  UPDATE item SET
     pre_delete_status = CASE WHEN status='DELETED' THEN pre_delete_status ELSE status END,
     status='DELETED', deleted_at=:now,
     invalidated_at = CASE WHEN invalidated_at=0 THEN :now ELSE invalidated_at END,
     updated_at=:now
   WHERE id=:id AND status<>'DELETED'
2  DELETE FROM zhuli_memory_fts WHERE rowid=:id       // TRIGGER 模式下触发器也做，幂等
3  DELETE FROM zhuli_fact_vec WHERE fact_id=:id
4  DELETE FROM zhuli_vec_queue WHERE fact_id=:id
5  INSERT revision(change_kind='DELETE', actor='USER',
        before_json='{"status":"<pre>"}', after_json='{"status":"DELETED"}')
```

**恢复**（`restore`，单事务，8 步照 C0.2 §6.9）：

```
0  取行；不存在 → NotFound；status<>'DELETED' → NotDeleted(status)
1  target = pre_delete_status ∈ {ACTIVE,CANDIDATE,SUPERSEDED,EXPIRED,REJECTED} ? 它 : 'CANDIDATE'
2  target ∈ {ACTIVE,CANDIDATE}：
     查同 dedupe_key 的其它行（status IN ('ACTIVE','CANDIDATE') 且 id<>:id）
       命中 → 复制 evidence 给占用行（role='SUPPORTING'）→ AlreadyPresent(occupantId)
3  target=ACTIVE 且 cardinality=SINGLE：
     查同槽位不同值的 ACTIVE（slot_hash 相同、value 不同）
       命中 → target='CANDIDATE'，记 conflict
4  UPDATE status=target, deleted_at=0, pre_delete_status='', updated_at=:now,
          invalidated_at = CASE WHEN :target IN ('ACTIVE','CANDIDATE') THEN 0 ELSE invalidated_at END
5  若有 conflict → relation(min,max,'CONFLICTS_WITH')
6  revision('RESTORE', before='{"status":"DELETED"}', after='{"status":"<target>"}')
7  target=ACTIVE → 入向量队列；FTS 由触发器/APP_SYNC 重挂
8  返回 Restored / Demoted / AlreadyPresent
```

第 2 步的 SQL 必须**同时**含 `ACTIVE` 和 `CANDIDATE`——C0.2 §6.9 的探针实测证明 `CANDIDATE` 也占唯一索引。

### 7.7 来源关联与 GC

| 场景 | 处理 |
|---|---|
| 同值新来源（R2） | `INSERT OR IGNORE evidence(memory_id, source_id, role='SUPPORTING')`；`seen_count++`、`last_seen_at=now`；`times_reinforced` 由独立来源数派生 |
| 同一来源重复到达 | `idx_ms_dedupe` 命中 → 复用 source 行，`seen_count++` |
| 删除一条记忆 | evidence **保留**（审计；`excerpt` 已脱敏）；source 保留 |
| 硬删（C3） | 删 evidence → 无引用 source 才 GC |
| 来源无引用（C1 可见） | `selfCheck` 第 6 条只**报告**，不自动删（C1 没有硬删，自动删会删掉"将来可能被引用"的行） |

**引用计数不用列**：`COUNT(*) FROM evidence WHERE source_id=?` 走 `idx_mev_source`，比维护一个计数器更难出错。

### 7.8 查询 API

| 方法 | 语义 | SQL 要点 |
|---|---|---|
| `byId(id)` | 任意状态单条 | 不过滤状态（UI 要看历史） |
| `current(limit, scopes, maxSensitivity)` | `CURRENT` | `status='ACTIVE' AND LB<=now AND (UB=0 OR now<UB)` + scope OR 列表 |
| `asOfWorld(at, …)` | 世界线 | §2.3 的三段谓词 + `CORRECTS` 排除 |
| `asOfBelief(at, …)` | 信念线 | `observed_at<=at AND (invalidated_at=0 OR at<invalidated_at)` |
| `candidates(limit)` | 待确认列表 | `status='CANDIDATE'` |
| `history(slot)` | 同槽全部（含 DELETED） | 按 `recorded_at` 升序 |
| `search(text, now, limit, …)` | 检索 | 候选 200 → `MemoryRanker` 排序（复用现有纯函数） |
| `conflictFacts()` | **冲突候选行**（D-6 第 7 条） | `status IN ('ACTIVE','CANDIDATE') AND cardinality='SINGLE'`，且 `slot_hash` 在"同槽多 dedupe_key"集合里；按 `slot_hash, observed_at DESC` 排序 |
| `counts()` | 各状态计数 | 一条 `GROUP BY status` |
| `selfCheck()` | 10 组残留检查 | C0.2 §6.6，返回 `List<String>`（空 = 干净） |

**`ambiguous` / `boundary_unknown`**（C0.2 §4.10）在 `search`/`asOfWorld` 的返回类型上表达：

```kotlin
data class MemoryQueryResult(
    val items: List<MemoryItem>,
    val ambiguous: Boolean,          // 同 slot_hash + 不同 value + 至少一条 boundary_unknown
    val truncated: Boolean,          // token 预算截断
)
// 单条的 boundary_unknown 由 MemoryItem.boundaryUnknown 计算属性给出：
val boundaryUnknown get() = validFrom == 0L || timeConfidence == NONE || validUntil == 0L
```

### 7.9 Scope 与敏感级过滤（唯一一处拼接）

```kotlin
private fun scopeClause(q: MemoryQuery): Pair<String, Array<String>> {
    require(q.scopes.isNotEmpty()) { "scopes 不能为空：拒绝检索，而不是返回全部" }
    val parts = mutableListOf("(i.scope_type='GLOBAL')")
    val args = mutableListOf<String>()
    for (s in q.scopes) {
        require(MemoryGuard.isValidScope(s)) { "非法 scope: $s" }
        if (s.type == ScopeType.GLOBAL) continue
        parts += "(i.scope_type=? AND i.scope_id=?)"
        args += s.type.name; args += s.id
    }
    return "(" + parts.joinToString(" OR ") + ")" to args.toTypedArray()
}
```

四个约束缺一不可（C0.2 §13.6）：`status` / 生效区间 / `sensitivity <= max` / scope OR 列表。**降级到 LIKE 时这四条一个都不许少**。

### 7.10 错误模型

| 错误 | 类型 | 调用方怎么处理 |
|---|---|---|
| scope 非法 / 为空 | `IllegalArgumentException` | 编程错误，直接崩（debug）/ 记日志丢弃（release） |
| SECRET 写入 | **不抛**，返回 `Rejected(SECRET)` | 调用方按"没写进去"处理 |
| 状态迁移非法 | `IllegalStateException` | 编程错误 |
| 恢复时同值已存在 | **不抛**，返回 `AlreadyPresent` | UI 提示"已存在"（C3） |
| 迁移失败 | 抛 → `onUpgrade` 回滚 | 既有机制 |
| FTS 不可用 | **不抛**，`ftsMode` 降档 | 检索走 LIKE |
| 数据库只读 | 写方法抛 `SQLiteReadOnlyDatabaseException`（底层） | 不额外包装 |

**原则**：只有"编程错误"抛异常；"用户/环境导致的可预期状况"返回结果类型。这样调用方可以穷举 `when`，不会漏分支。

### 7.11 明确不做的事（C2 及以后）

| 不做 | 归属 |
|---|---|
| 从通知/对话自动抽取记忆（`MemoryExtractor` 已有，C1 只沿用其输出） | C2（编排） |
| 用户确认/否决的 UI 与批量流程 | C2 |
| `MemoryContextBuilder`（检索结果注入 prompt） | C3 |
| 自动冲突裁决（"选一个"） | C2 |
| 定期整理/合并/衰减 | C4 |
| 历史聊天回填 | C4 |
| 墓碑、硬删、`usage` 表、`ingest` 游标 | C3/C4 |
| PROJECT / CONVERSATION 的写入 | C2/C4 |

---

### 7.12 `Fact` 投影层与 `FactStatus` 兼容状态模型（D-6 落实）

#### 7.12.1 两套状态模型的分工（不可合并）

| | 权威模型 | 兼容模型 |
|---|---|---|
| 类型 | `MemoryStatus`（`MemoryItem.kt`，新增） | `FactStatus`（`MemoryTypes.kt`，修改） |
| 取值 | `ACTIVE` / `CANDIDATE` / `SUPERSEDED` / `EXPIRED` / `REJECTED` / `DELETED` | `ACTIVE` / `UNCONFIRMED` / `SUPERSEDED` / `EXPIRED` / `REJECTED` / `DELETED` / `UNKNOWN` |
| 存储 | `zhuli_memory_item.status`（6 个字符串，唯一权威） | **不存储**；运行时由投影函数从权威模型算出 |
| 谁写 | `MemoryStore` | 无人写 |
| 谁读 | `MemoryStore` / `MemoryQuery` / `MemoryMigrator` | `MemoryRepo` 的投影、`MemoryRanker`、UI 的 `Fact` 列表 |
| 生命周期 | 永久 | **C1-B 之后只减不增**：C4 删除 `MemoryRepo` 时一并消失 |

**为什么不能合并成一套**：`MemoryStatus` 的 `CANDIDATE` 与 `FactStatus` 的 `UNCONFIRMED` 是同一个概念的两个历史名字。合并意味着改 5 个生产调用方 + 15 个现有单测（§8.3 已拒绝）。兼容模型的价值就是"让旧调用方一行不改"，代价是必须有一层**显式、可测试**的投影。

**为什么不能"多加两个值就算完"**：如果只是加 `EXPIRED` / `REJECTED` 而不改 `of()`，未知值仍会变成 `ACTIVE`——那是 D-6 第 3 条明令禁止的。所以本次改动是**三个值 + 一个语义修正 + 一个冲突函数修正**，不是简单扩枚举。

#### 7.12.2 `MemoryTypes.kt` 的目标形态

```kotlin
enum class FactStatus {
    ACTIVE,

    /** 历史回填/低置信度：存下来但不参与检索、不覆盖任何现有记忆。对应 MemoryStatus.CANDIDATE。 */
    UNCONFIRMED,

    /** 被新值替代：曾经为真，现在不是当前值，但仍是历史。 */
    SUPERSEDED,

    /** 自然过期：世界时间越过 valid_until（任务到期、STATE 关闭），或时效耗尽。
     *  与 SUPERSEDED 的区别：没有"被谁替代"，是时间自己走到了尽头。 */
    EXPIRED,

    /** 用户/策略明确否决：不是"以前是、现在不是"，而是"这条本来就不该记"。
     *  与 DELETED 的区别：DELETED 是"删掉一条曾经接受的记忆"，REJECTED 是"从源头就不接受"。 */
    REJECTED,

    /** 用户删除（软删）。pre_delete_status 记录删除前的状态。 */
    DELETED,

    /** 兼容投影兜底：数据库里出现无法识别的状态值（手工改库、未来版本写入、数据损坏）。
     *  **非当前、不可自动激活、不参与检索与冲突配对**。绝不 fallback 到 ACTIVE。 */
    UNKNOWN;

    /** 只有 ACTIVE 是"当前为真"。EXPIRED / REJECTED / DELETED / UNKNOWN 全部为 false。 */
    val isCurrent: Boolean get() = this == ACTIVE

    /** 可以参与冲突配对的状态：当前值 + 待裁决候选。 */
    val isActionable: Boolean get() = this == ACTIVE || this == UNCONFIRMED

    companion object {
        /** 未知值（含 null、空串、大小写不符、未来新增值）一律 UNKNOWN。
         *  **禁止**写成 `?: ACTIVE` —— 那会把"过期/否决/未来状态"显示成当前值。 */
        fun of(raw: String?): FactStatus =
            entries.firstOrNull { it.name == raw } ?: UNKNOWN
    }
}
```

**改动清单（相对现状）**：

| 行 | 现状 | 改为 |
|---|---|---|
| 56–66 | 4 个枚举值 | 6 个业务值 + `UNKNOWN` |
| 68–70 | `fun of(raw: String) = entries.firstOrNull { … } ?: ACTIVE` | `fun of(raw: String?) = entries.firstOrNull { … } ?: UNKNOWN` |
| 新增 | — | `val isCurrent` / `val isActionable` 两个派生属性 |
| KDoc | 只描述 4 态 | 每个新值写"与相邻值的区别"（上面代码里的注释） |

**不做的改动**：`Fact` 的 16 个字段、`FactKind`、`SourceType`、`EntityKind`、`FactDraft`、`FactHit` 全部不动。`Fact.status` 的默认值仍是 `ACTIVE`。

#### 7.12.3 所有 `FactStatus` 使用方与处理方式（已核查，15 处）

`grep -rn "FactStatus" app/src` 的完整结果，逐处给出新状态下的行为。**这张表就是 D-6 第 6 条的答案**：

| # | 位置 | 现在怎么用 | 新状态下的处理 | 要改吗 |
|---|---|---|---|---|
| 1 | `MemoryTypes.kt:120` | `Fact.status` 默认 `ACTIVE` | 不变（新建 `Fact` 默认当前值） | 否 |
| 2 | `MemoryTypes.kt:69` | `of()` fallback `ACTIVE` | 改 `UNKNOWN` | **是** |
| 3 | `MemoryRepo.kt:478` | `read()` 用 `FactStatus.of(raw)` | **删掉 `of()`，改显式投影函数**（`CANDIDATE` → `UNCONFIRMED` 必须靠它） | **是** |
| 4 | `MemoryRepo.kt:116` | `correct()` 新行 `status = ACTIVE` | 转 `MemoryStore.correct()`，状态由 Store 定 | 随重写 |
| 5 | `MemoryRepo.kt:136` | `deleteFact` 写 `DELETED` | 转 `MemoryStore.softDelete()` | 随重写 |
| 6 | `MemoryRepo.kt:150` | `unconfirmed()` 查 `UNCONFIRMED` | 查新表 `status='CANDIDATE'` | 随重写 |
| 7 | `MemoryRepo.kt:157,162` | `confirmFact` 只认 `UNCONFIRMED` | 转 `MemoryStore.confirm()`；`UNKNOWN` 不放行（`WHERE status='CANDIDATE'`） | 随重写 |
| 8 | `MemoryRepo.kt:177,183,186` | `activeOf`/`active`/`activeCount` 查 `ACTIVE` | 改 `CURRENT`（多 `LB<=now` 与 `UB` 两个条件） | 随重写 |
| 9 | `MemoryRepo.kt:208,429` | `supersede`/`resolveConflict` 写 `SUPERSEDED` | 转 `MemoryStore`；`EXPIRED` 与 `SUPERSEDED` 不混用 | 随重写 |
| 10 | `MemoryRepo.kt:271-272` | `pendingVectorFacts` 的 JOIN 过滤 `ACTIVE` | 改 `CURRENT` | 随重写 |
| 11 | `MemoryRepo.kt:326` | `enqueueAllActive` 查 `ACTIVE` | 改 `CURRENT` | 随重写 |
| 12 | `MemoryRepo.kt:353` | `vectorText` 只对 `ACTIVE` 返回 | 改 `CURRENT`（与 10/11 一致，避免"入队了但取不到文本"） | 随重写 |
| 13 | `MemoryRanker.kt:97` | `search` 过滤 `ACTIVE` | **不变**：`UNKNOWN` / `EXPIRED` / `REJECTED` / `DELETED` 自动排除，正是要的 | 否 |
| 14 | `MemoryRanker.kt:112` | `conflicts` 过滤 `ACTIVE` | 改 `isActionable`（`ACTIVE` + `UNCONFIRMED`） | **是** |
| 15 | `MemoryBackfill.kt:20` | KDoc 提 `UNCONFIRMED` | 文案保留（`FactStatus.UNCONFIRMED` 仍是有效值），补一句"对应 `MemoryStatus.CANDIDATE`" | KDoc |

**不在这张表里的"使用方"**（已逐个核查，确认无依赖）：

| 文件 | 检查结果 |
|---|---|
| `bridge/ZhuliMemoryPage.kt` | grep 查 `UNCONFIRMED` / `SUPERSEDED` / `DELETED` / `FactStat` 四个字面量 = **0 命中**。UI 只消费 `active()` / `unconfirmed()` / `conflicts()` 的列表，不判状态字面量 → 新状态**零影响** |
| `agent/ZhuliAgentTools.kt` | 同上，0 命中（`status` 只出现在任务工具的 `it.status`，与 `FactStatus` 无关） |
| `memory/MemoryIndexer.kt` | 0 命中；只用 `activeCount()` 与向量方法 |
| `memory/MemoryExtractor.kt` / `MemoryEmbedding.kt` / `GemmaEmbedding.kt` | 0 命中 |
| `MemoryRankerTest.kt` | 用 `FactStatus.ACTIVE/DELETED/SUPERSEDED` 构造数据；新增值不破坏现有断言 |
| `MemorySqliteTest.kt:77,102,114` | 断言 `SUPERSEDED` / `DELETED`；语义未变，继续成立 |

**结论**：新状态对 UI 与 Agent 的运行时行为**没有任何影响**；唯一的行为变化在 `MemoryRanker.conflicts`（从"永远空"变成"真的有冲突"），那是修复而不是回归。

#### 7.12.4 投影矩阵与查询可见性（D-6 第 5 条）

**投影函数**（`MemoryRepo` 私有，唯一一处）：

```kotlin
/**
 * 权威状态 → 兼容状态。**禁止**用 FactStatus.of()：CANDIDATE 需要映射成 UNCONFIRMED，
 * 而 of("CANDIDATE") 会得到 UNKNOWN（正确但无用）。
 */
private fun projectStatus(raw: String): FactStatus = when (raw) {
    "ACTIVE"     -> FactStatus.ACTIVE
    "CANDIDATE"  -> FactStatus.UNCONFIRMED
    "SUPERSEDED" -> FactStatus.SUPERSEDED
    "EXPIRED"    -> FactStatus.EXPIRED
    "REJECTED"   -> FactStatus.REJECTED
    "DELETED"    -> FactStatus.DELETED
    else         -> FactStatus.UNKNOWN
}
```

**六态投影一一对应，不做任何合并**（这是 D-6 第 5 条的核心）：

| `zhuli_memory_item.status` | `FactStatus` | 在 `active()` | 在 `unconfirmed()` | 在 `history()` | 在 `search()` | 在 `conflicts()` |
|---|---|---|---|---|---|---|
| `ACTIVE` | `ACTIVE` | ✅ | ❌ | ✅ | ✅ | ✅ |
| `CANDIDATE` | `UNCONFIRMED` | ❌ | ✅ | ✅ | ❌ | ✅ |
| `SUPERSEDED` | `SUPERSEDED` | ❌ | ❌ | ✅ | ❌ | ❌ |
| `EXPIRED` | `EXPIRED` | ❌ | ❌ | ✅ | ❌ | ❌ |
| `REJECTED` | `REJECTED` | ❌ | ❌ | ✅ | ❌ | ❌ |
| `DELETED` | `DELETED` | ❌ | ❌ | ✅ | ❌ | ❌ |
| 其它 / 空 | `UNKNOWN` | ❌ | ❌ | ✅ | ❌ | ❌ |

**四组语义的区别（写进 KDoc 与 UI 文案依据）**：

| 状态 | 一句话 | 与相邻状态的区别 | 用户能看到吗 |
|---|---|---|---|
| `ACTIVE` | 现在为真 | — | 记忆页主列表 |
| `EXPIRED` | 曾经为真，时间到了 | 不是"被谁替代"（`superseded_by=0`），是 `valid_until` 走到了 | 历史列表 |
| `REJECTED` | 从来没被接受 | 不是"删掉一条接受过的"（`deleted_at=0`），是源头否决 | 历史列表（C2 才产生） |
| `DELETED` | 接受过，用户删了 | `pre_delete_status` 记录删除前是什么 | 历史列表（默认折叠） |

> **C1 不产生 `REJECTED`**（没有 reject 入口，C2 才有）。但枚举值必须先有：否则 C2 一上线，`FactStatus.of("REJECTED")` 会把否决的记忆读成 `ACTIVE`——这正是 D-6 要提前堵住的坑。

#### 7.12.5 `MemoryRanker.conflicts` 的新语义（D-6 第 7 条）

**问题回顾**：R8/R9 把打不平的新值放进 `CANDIDATE`，槽内只留 1 条 `ACTIVE`。旧实现 `filter { status == ACTIVE }` 在槽内只剩一条时永远配不出对 → 记忆页的"需要你裁决"区域**静默消失**。

**新实现**（签名不变，纯函数）：

```kotlin
/**
 * 矛盾检测：同槽内"当前值"与"待裁决候选"值不同 → 需要用户裁决。
 *
 * v2 起（D-6 第 7 条）：
 * - 参与配对的状态是 ACTIVE + UNCONFIRMED（isActionable），不再只取 ACTIVE；
 * - 槽内有 ACTIVE 时，每个不同值的 UNCONFIRMED 都与它各成一对（UI 要逐条裁决）；
 * - 槽内全是 UNCONFIRMED（异常/连续 R9）时，退化为相邻两两配对；
 * - MULTI 槽位的排除不在这里做 —— 本函数拿不到 cardinality，
 *   由 MemoryStore.conflictFacts() 的 SQL 先过滤（见 §7.8）。
 */
fun conflicts(facts: List<Fact>): List<Pair<Fact, Fact>> {
    val actionable = facts.filter { it.status.isActionable }
    val out = ArrayList<Pair<Fact, Fact>>()
    for ((_, group) in actionable.groupBy { it.slot }) {
        val distinct = group
            .sortedByDescending { it.observedAt }
            .distinctBy { MemoryExtractor.norm(it.value) }
        if (distinct.size < 2) continue
        val actives = distinct.filter { it.status == FactStatus.ACTIVE }
        val candidates = distinct.filter { it.status == FactStatus.UNCONFIRMED }
        if (actives.isNotEmpty() && candidates.isNotEmpty()) {
            for (a in actives) for (c in candidates) out.add(a to c)
        } else {
            for (i in 0 until distinct.size - 1) out.add(distinct[i] to distinct[i + 1])
        }
    }
    return out
}
```

**配对数量示例**：槽内 1 ACTIVE + 3 个不同值的 CANDIDATE → **3 对**（旧实现 0 对）。UI 显示"需要你裁决（3 组）"，每组是"当前值 vs 某个候选"，用户可以逐条确认或删除。

**`MULTI` 槽位不误报**：`MemoryStore.conflictFacts()` 的 SQL 带 `cardinality='SINGLE'`，MULTI 槽（取件码、多笔账）根本不会进入 `conflicts()` 的输入。这是"存储语义留在 Store、纯函数只做配对"的分工。

**`EXPIRED` / `REJECTED` / `DELETED` / `UNKNOWN` 不参与**：`isActionable` 只含两个值，其余自动排除。特别是 `EXPIRED`——一条过期任务与当前值同槽不是"冲突"，是"历史"。

**与 `activateThreshold` 的交互（必须知道，不是缺陷）**：`MemoryBackfill` 用 `activateThreshold` 回填历史，会产生大量 `CANDIDATE`（预演库 20k 规模下约 1,810 条 `UNCONFIRMED`）。按本节语义，这些候选只要与同槽 ACTIVE 值不同，就会进入 `conflicts()`。后果：

| 后果 | 评估 |
|---|---|
| 记忆页的"需要你裁决"数量可能从 0 跳到几十上百组 | 符合"不静默丢失"的要求，但 UI 会拥挤 |
| 是否应该只展示 `R8`/`R9` 产生的（有 `CONFLICTS_WITH` 关系的）候选 | **不采纳**：① 依赖关系表会让"关系写失败"变成"冲突消失"，多一个失败点；② 回填候选与当前值同槽不同值，确实是两个值打架，用户有权知道；③ C1 的 `activateThreshold` 只被一次性回填使用，C2 的自动写入不走它 |
| 怎么缓解 | 不是隐藏冲突，而是 C2 的批量裁决 UI（多选确认/多选删除）+ 按"证据强度"排序。**这是 U-11** |

**`qualifier_key` 的已知限制**：`Fact.slot` = `subject|predicate`，不含 `qualifier_key`。C1 的 `qualifier_key` 恒为 `''`（迁移与运行时都不产生非空值），所以无差异；C2 引入 qualifier 后，`MemoryRanker` 需要改用 `slot_hash` 或给 `Fact` 加字段。**这条写进 §19.3 的遗留问题 U-7。**

#### 7.12.6 序列化 / 反序列化 / 数据库兼容规则（D-6 第 9 条）

| 方向 | 规则 | 测试 |
|---|---|---|
| 序列化（写库） | 只用 `status.name`；不写序号、不写自定义编码。`UNKNOWN` **不写回**（它只产生于读取） | `T-X03` |
| 反序列化（读库） | 权威表走 `projectStatus(raw)`（显式 7 分支）；旧 `zhuli_fact_v1` 若被读取则走 `FactStatus.of(raw)`（6 态 + UNKNOWN） | `T-X01` / `T-X02` / `T-X06` |
| 大小写 | 严格匹配 `name`。`"active"` → `UNKNOWN`（不静默纠正，避免"看起来对其实是脏数据"） | `T-X02` |
| 空值 | `null` / `""` → `UNKNOWN` | `T-X02` |
| 未知值 | → `UNKNOWN`；**禁止**映射到 `ACTIVE` | `T-X02` / `T-X04` |
| v16 库兼容 | 旧 4 个值（`ACTIVE`/`UNCONFIRMED`/`SUPERSEDED`/`DELETED`）迁移后投影不变 | `T-X05` |
| v16 库出现新值（手工改库） | 迁移按值映射：`EXPIRED`→`EXPIRED`，`REJECTED`→`REJECTED`，其它未知→`CANDIDATE` + 计数 | `T-X08` |
| 写回旧列 | C1-B 后**没有**任何写旧列的路径；若未来有人加，`UNKNOWN` 会以字符串 `"UNKNOWN"` 落库，再读仍是 `UNKNOWN`（不漂移） | `T-X03` |
| 序列化稳定性 | 枚举值顺序可读但不参与任何逻辑；**禁止**按 `ordinal` 存库 | review checklist |

#### 7.12.7 本节的禁止事项

| # | 禁止 | 理由 |
|---|---|---|
| 1 | `FactStatus.of()` 出现 `?: ACTIVE` | D-6 第 3 条；这正是 C0.2 缺陷 #2 的投影层翻版 |
| 2 | 用 `FactStatus.valueOf(raw)` | 未知值抛 `IllegalArgumentException`，让"一条脏数据"变成"记忆页崩溃" |
| 3 | 在投影里把 `EXPIRED` 合并成 `SUPERSEDED`、把 `REJECTED` 合并成 `DELETED` | D-6 第 5 条；合并后 UI 无法区分"过期"与"被替代"、"否决"与"删除" |
| 4 | 给 `FactStatus` 加 `CANDIDATE`（与 `UNCONFIRMED` 同义） | 两个同义值会让 `when` 出现两个分支做同一件事，且旧数据无法判断该用哪个 |
| 5 | 把 `UNKNOWN` 当作"待确认"放进 `unconfirmed()` | D-6 第 4 条；未知状态不可自动激活，也不该出现在"请用户确认"的列表里（用户没有可确认的对象） |
| 6 | 在 `MemoryRanker` 里判断 `cardinality` | 它拿不到；MULTI 排除是存储层职责（§7.8 的 `conflictFacts`） |
| 7 | 让 `MemoryRepo` 之外的类构造 `Fact` | `Fact` 是兼容视图，只能由投影函数产生；多一处构造就多一处状态映射漏判 |

---

## 8. 文件修改清单（具体到路径）

### 8.1 新增生产文件（7 个，全部在 `com/zhuli/memory/`）

| # | 路径 | 行数（估） | 归属阶段 | 内容 |
|---|---|---|---|---|
| 1 | `app/src/main/java/com/zhuli/memory/MemoryItem.kt` | 190 | B1 | `MemoryType` / `MemoryStatus` / `ConfidenceBasis` / `ScopeType` / `Cardinality` / `TimeConfidence` / `MemoryScope` / `MemoryItem` / `MemorySource` / `MemoryEvidence` / `MemoryDraft` / `InsertResult` / `DeleteResult` / `RestoreResult` |
| 2 | `app/src/main/java/com/zhuli/memory/MemoryText.kt` | 110 | B1 | `norm` / `searchText` / `queryExpr` / `dedupeKey` / `slotHash` / `sha256Hex` / `LB_SQL` / `UB_SQL` / `MS_PER_DAY` |
| 3 | `app/src/main/java/com/zhuli/memory/MemorySchema.kt` | 190 | B1+B3 | 7 段 DDL 常量 + `createAll` / `ensureAll` / `createFtsTable` / `createFtsTriggers` / `detectFtsMode` / `FtsModeCache` / `tableExists` |
| 4 | `app/src/main/java/com/zhuli/memory/MemoryGuard.kt` | 90 | B1 | `classify` / `excerpt` / `isValidScope` / `checkLength`（镜像 `SensitiveGuard`，不新建词表） |
| 5 | `app/src/main/java/com/zhuli/memory/MemoryPolicy.kt` | 140 | B4 | `rank` / `cardinalityOf` / `decide`（R4–R9）/ `R0` 的时间自洽修正 |
| 6 | `app/src/main/java/com/zhuli/memory/MemoryStore.kt` | 520 | B4 | 7 个写方法 + 9 个查询 + `selfCheck` + `syncFts` + `rebuildFts` |
| 7 | `app/src/main/java/com/zhuli/memory/MemoryMigrator.kt` | 250 | B2 | 13 步迁移 + 5 条时间断言 + 数量闭合 + 断链 + 合并 + 向量校验 |

小计 **1,490 行**（C0.2 估 1,420；增量来自 `InsertResult`/`DeleteResult` 等结果类型 ~40 行与 `FtsModeCache` ~30 行）。

> 比 C0.2 的 1,450 行预算多 40 行，仍在 §8.5 的容忍范围内（见该节的核算）。

### 8.2 修改生产文件（**恰好 4 个**，D-6/D-7 后由 2 个增至 4 个）

| # | 路径 | 改动 | 行数（估） |
|---|---|---|---|
| 1 | `app/src/main/java/com/zhuli/core/InboxDatabase.kt` | ① `DB_VERSION = 17`；② `createCoreTables` 拆成 `createCoreTablesV15` + `createFactTableV15`（**X-1 / D-7**，§4.8.3）；③ `onCreate:317` 换成 `createCoreTablesV15` + `MemorySchema.attach(prefs)` + `MemorySchema.createAll(db, withUnique=true)`；④ `onOpen:339` 换成 `createCoreTablesV15` + `MemorySchema.ensureAll(db)`；⑤ `onUpgrade:787`（`oldVersion<15`）换成 `createCoreTablesV15` + `createFactTableV15`；⑥ `upgradeSteps` 末尾加 `if (oldVersion < 17) { MemorySchema.createAll(db, withUnique=false); MemoryMigrator.migrate(db, now) }` | **+34 / −10** |
| 2 | `app/src/main/java/com/zhuli/memory/MemoryRepo.kt` | 全量重写为投影层（27 个签名不变）；删掉 `insert`/`reinforce`/`supersede`/`upsertEntity` 等 5 个写旧表的私有方法（§4.8.4）；新增 `projectStatus()` 显式七分支（§7.12.4） | 485 → **~175**（净减 ~310） |
| 3 | `app/src/main/java/com/zhuli/memory/MemoryTypes.kt` | **D-6**：`FactStatus` 加 `EXPIRED` / `REJECTED` / `UNKNOWN`；`of(raw: String?)` 改为 `?: UNKNOWN`；新增 `isCurrent` / `isActionable`；KDoc 写清四组语义区别（§7.12.2） | 154 → **~172**（+18 / −6） |
| 4 | `app/src/main/java/com/zhuli/memory/MemoryRanker.kt` | **D-6 第 7 条**：`conflicts` 的过滤集合改为 `isActionable`；配对规则改为"ACTIVE × 每个候选"优先；KDoc 说明 MULTI 排除在 Store 层（§7.12.5） | 124 → **~132**（+8 / −3） |

改动总量：**+60 / −329**（净减 269 行，主要是 `MemoryRepo` 从 485 行缩到 175 行）。

> 为什么 `MemoryRanker.kt` 必须改而不能绕：D-6 第 7 条的原话是"`MemoryRanker.conflicts` 必须支持新记忆冲突模型，不能因为冲突败方处于 `CANDIDATE` 而静默丢失冲突展示"。绕到 `MemoryRepo` 里另写一套配对，等于让 `MemoryRanker.conflicts` 继续对 `CANDIDATE` 视而不见——任何其他调用方（或未来的 C2 编排）用它都会踩同一个坑。


### 8.3 删除文件

**0 个。**

理由：C0.2 没有要求删除任何文件；`zhuli_fact` 的"删除"是**运行时改名**，不是删源码。`MemoryTypes.kt` / `MemoryExtractor.kt` / `MemoryRanker.kt` 全部保留（§10.3 禁止清单）。

唯一可以讨论的是 `MemoryRepo.kt` 是否该整体删除、由调用方直连 `MemoryStore`——**不删**，因为 5 个生产调用方 + 15 个现有单测都按 `Fact` 模型编写，删了等于把 C1 扩成"改 5 个调用方 + 重写 15 个测试"，直接违反 §10.5 的 diff 预算。

### 8.4 新增/修改测试文件

| # | 路径 | 类型 | 行数（估） | 覆盖 |
|---|---|---|---|---|
| 1 | `app/src/test/java/com/zhuli/memory/MemoryTextTest.kt` | 新增 | 140 | L1 纯函数：`norm` / `searchText` / `queryExpr` / `dedupeKey` / `slotHash`（含 CJK、全角、标点、单字） |
| 2 | `app/src/test/java/com/zhuli/memory/MemorySchemaTest.kt` | 新增 | 180 | DDL 与 C0.2 一致性、建表幂等、索引存在性、`detectFtsMode` 三档（Robolectric）、**`T-M40` 新装库结构（D-7）** |
| 3 | `app/src/test/java/com/zhuli/memory/MemoryMigratorTest.kt` | 新增 | 540 | `T-M01`–`T-M10`、`T-M36`–`T-M38`（13 条）+ **`T-M41`–`T-M45`（5 条，D-7）** |
| 4 | `app/src/test/java/com/zhuli/memory/MemoryStoreTest.kt` | 新增 | 480 | `T-S11`–`T-S20`、`T-S39`、`T-D31`、`T-D33`、`T-D44`–`T-D49`（20 条） |
| 5 | `app/src/test/java/com/zhuli/memory/MemoryTimeQueryTest.kt` | 新增 | 230 | `T-T21`–`T-T24`、`T-T40`–`T-T43`（8 条） |
| 6 | `app/src/test/java/com/zhuli/memory/MemoryScopeTest.kt` | 新增 | 200 | `T-C25`–`T-C30`、`T-C57`（7 条） |
| 7 | `app/src/test/java/com/zhuli/memory/MemoryFactStatusTest.kt` | **新增（D-6）** | **280** | `T-X01`–`T-X12`（12 条）：状态解析、未知值、序列化往返、六态投影、语义区分、冲突展示、MULTI 排除 |
| 8 | `app/src/test/java/com/zhuli/memory/MemoryTestFixture.kt` | 新增（**非测试类**） | 130 | `insertRaw` / `seedLegacy` / `seedItem`（C0.2 §14.3） |
| 9 | `app/src/androidTest/java/com/zhuli/memory/MemoryMigrationTimingTest.kt` | 新增 | 160 | D2 设备耗时（1k/5k/20k）+ 迁移后不变量 |
| 10 | `app/src/test/java/com/zhuli/memory/MemorySqliteTest.kt` | **修改** | +20 / −5 | `v15 库升级` 用例断言 v17；新增 `zhuli_fact` 不存在的断言；`activeCount` 仍为 1 |

**小计（本次修订重算）**：

| 口径 | 行数 |
|---|---|
| 新增测试代码（不含 fixture） | 140 + 180 + 540 + 480 + 230 + 200 + 280 + 160 = **2,210** |
| 含 fixture | **2,340** |
| 修改 | 1 个文件（`MemorySqliteTest.kt`，+20/−5） |

> **v1 的小计算错了 90 行**：v1 写"小计新增 1,990 行"，但表内逐行相加为 1,900（140+150+420+480+230+200+120+160）。本次修订按逐行相加重算，并把 D-6/D-7 的增量算进去：`MemorySchemaTest` +30、`MemoryMigratorTest` +120、新增 `MemoryFactStatusTest` 280、`MemoryTestFixture` +10，得 2,340（含 fixture）。
>
> **测试预算相应调整**：C0.2 §10.5 的"≤1,400 行"与实际差得太远（v1 就已 1,900），本次把上限定为 **≤2,400 行**（含 fixture）。理由不是"范围扩张"，而是"用例数从 47 涨到 65，且每条用例平均 34 行"：65 × 34 ≈ 2,210，加 fixture 130 正好 2,340。C1-B5 用 `git diff --stat` 核对，超 2,400 必须解释。


### 8.5 diff 预算核算与自检

| 项 | 上限 | 本方案估算 | 是否超 |
|---|---|---|---|
| 生产新增 | ≤1,500（v2 调整，v1 为 1,450） | 1,490 | 否（余 10） |
| 生产修改 | **恰好 4 个文件** | 4 个（`InboxDatabase` +34/−10、`MemoryRepo` 485→175、`MemoryTypes` +18/−6、`MemoryRanker` +8/−3） | 否 |
| 测试新增 | ≤2,400（v2 调整，v1 为 2,000） | ~2,340（含 fixture） | 否（余 60） |
| 测试修改 | — | 1 个文件（`MemorySqliteTest.kt`，+20/−5） | — |
| 触碰 C0.2 §10.3 禁止清单 | 0，除 D-6 明确放行的 `MemoryTypes.kt` | 0（`MemoryTypes.kt` / `MemoryRanker.kt` 是 D-6 的授权改动） | 否 |
| 数据库版本 | 只 +1 | 16→17 | 否 |

> **`MemoryTypes.kt` 的"禁止清单"冲突怎么处理**：C0.2 §10.3 写的是"`MemoryTypes.kt` 不动"，D-6 正式决定扩展 `FactStatus`。D-6 是**后出的、更具体的决策**，按"后出优先 + 具体优先"覆盖该条；C0.2 文档本身不改（它是冻结的架构依据），差异记在修订记录 §9。`MemoryRanker.kt` 同理（D-6 第 7 条）。
>
> **自检命令的前提**：新增文件在 `git add -N`（intent-to-add）之后才会出现在 `git diff` 里。C1-B5 的第一步是 `git add -N app/src`，否则 7 个新文件对 `git diff --stat` 不可见，核算会漏 1,490 行。

自检命令（C1-B5 必须逐条跑并贴输出）：

```bash
git add -N app/src                                    # 让新增文件进入 diff 视野
git diff --name-only HEAD -- app/src/main             # 期望 4 行（4 个修改文件）
git diff --name-only HEAD -- app/src/main | wc -l     # 期望 4
git diff --stat HEAD -- app/src/main                  # 期望 ~+60/−329（不含未跟踪的新增）
git status --porcelain -- app/src/main                # 期望 7 个 ?? + 4 个 M
git diff --name-only HEAD | grep -c 'src/test\|src/androidTest'   # 期望 9（8 新增 + 1 修改）
git diff --name-only HEAD | grep -v 'src/test\|src/androidTest\|docs/'  # 期望 4 行
grep -rn "TABLE_FACT" app/src/main/java/com/zhuli     # 期望 2 个文件：InboxDatabase（createFactTableV15）+ MemoryMigrator
grep -rn "zhuli_fact_v1" app/src/main                 # 期望 ≤3 处（Migrator + InboxDatabase 注释）
grep -rn "MemoryTestFixture" app/src/main             # 期望 0
grep -n "DB_VERSION" app/src/main/java/com/zhuli/core/InboxDatabase.kt  # 期望 1 处 =17
grep -rn "fun createCoreTables\b" app/src/main        # 期望 0（旧函数名必须消失）
grep -rn "createCoreTablesV15\|createFactTableV15" app/src/main  # 期望 4 处（2 个定义 + onCreate/onOpen/onUpgrade 的调用）
grep -rn "?: ACTIVE" app/src/main/java/com/zhuli/memory           # 期望 0（D-6 第 3 条）
grep -rn "FactStatus.valueOf" app/src/main            # 期望 0
grep -rn "status==\"ACTIVE\"\|status == \"ACTIVE\"" app/src/main  # 期望 0（查询一律走 CURRENT）
```


### 8.6 文档

| 路径 | 动作 |
|---|---|
| `docs/批次C1-B_记忆系统基础设施实施方案.md` | **本文**（C1-B 开工前交付；v2 落实 D-6/D-7） |
| `docs/批次C1-B_方案决策修订记录.md` | **v2 新增**（D-6/D-7 决策原文、落实映射、差异清单、测试重算） |
| `docs/批次C1-B_实施与验收报告.md` | C1-B5 产出（不在本轮） |

`docs/批次C0.2_*.md` / `docs/批次C1-A_*.md` **不改**（C0.2 是冻结的架构依据；C1-A 是已完成的事实记录）。D-6 覆盖 C0.2 §10.3 的"`MemoryTypes.kt` 不动"这一条，差异记在修订记录，不回写 C0.2。


---

## 9. 五个子阶段的实施顺序

### 9.1 C1-B1｜数据库结构

| 步 | 动作 | 产物 |
|---|---|---|
| 1 | `MemoryTypes.kt`：**D-6** 加 `EXPIRED`/`REJECTED`/`UNKNOWN`，`of()` 改 `UNKNOWN`，加 `isCurrent`/`isActionable` | `MemoryFactStatusTest` 的解析与投影部分先绿 |
| 2 | `MemoryRanker.kt`：`conflicts` 过滤改 `isActionable` + ACTIVE×候选配对 | `MemoryRankerTest` 原有 6 例仍绿 + `T-X09`/`T-X10` |
| 3 | `MemoryItem.kt`：7 个枚举（含 `MemoryStatus` 六态）+ 6 个数据类 + 4 个结果类型 | 可编译 |
| 4 | `MemoryText.kt`：5 个纯函数 + `LB_SQL`/`UB_SQL` | `MemoryTextTest` 全绿 |
| 5 | `MemorySchema.kt`：7 段 DDL + `createAll(withUnique)` + `ensureAll` + `tableExists` | `MemorySchemaTest` 建表部分全绿 |
| 6 | `MemoryGuard.kt`：分级、摘录、scope 校验 | 与 `SensitiveGuardTest` 的判定对齐（复用同一批样本） |
| 7 | `InboxDatabase.kt`：`DB_VERSION=17`、**D-7 拆分** `createCoreTablesV15` + `createFactTableV15`、三个调用点改造（§4.8.2）、`onCreate` 接入 `MemorySchema.createAll` | 新装库能建出 7 张表、无 `zhuli_fact` |
| 8 | 验证 | `MemorySchemaTest` + `MemorySqliteTest` 的 v15 升级用例 + `T-M40` |

**完成条件**：
- 新装（`ctx.deleteDatabase` 后打开）有 7 张表 + 15 项索引，`user_version=17`，**无** `zhuli_fact`、**无** `zhuli_fact_v1`（`T-M40`）；
- 重复 `onOpen` 三次不报错、对象数不变（幂等）；
- `MemoryTextTest` 的 5 个函数在 CJK / 全角 / 标点 / 单字 / 超长 5 类样本上全绿；
- **`FactStatus` 的 7 个值（6 业务 + `UNKNOWN`）解析正确，未知输入一律 `UNKNOWN`，`isCurrent`/`isActionable` 的四个真值组合全对**（`T-X01`/`T-X02`/`T-X04`）；
- `git diff` 只含 **7 个文件**（4 新增：`MemoryItem`/`MemoryText`/`MemorySchema`/`MemoryGuard`；3 修改：`MemoryTypes`/`MemoryRanker`/`InboxDatabase`）。


### 9.2 C1-B2｜v16→v17 数据迁移

| 步 | 动作 | 产物 |
|---|---|---|
| 1 | `MemoryMigrator.kt` 骨架：前置断言 + 13 步空实现 + `MigrationReport` | 可编译，跑通空迁移 |
| 2 | 逐行映射（§5.3 的 40 列） | 真实副本 2 行迁完 |
| 3 | 来源与证据（§5.5） | `evidence` 行数 = `inserted` |
| 4 | 替代链 + 断链（§5.7） | edge 库的断链行被修 |
| 5 | 合并（§5.6） | 5k 库的重复组被合并 |
| 6 | 向量校验（§5.8） | 真实库 1 条保留 |
| 7 | FTS 灌数据 + 触发器 + 唯一索引 + 改名 | `T-M09` 通过 |
| 8 | 数量闭合 + 5 条时间断言 | 4 档库全 0（第 3 条允许告警） |
| 9 | `InboxDatabase.upgradeSteps` 接入 | v16→v17 自动发生 |

**完成条件**：
- 4 档库（2 / 11 / 5,002 / 20,002 行）全部 `inserted + quarantined + skipped = src`；
- C0.2 §6.6 的 10 组（11 条 SQL）在 4 档库上全 0；
- 5 条时间断言：1/2/4/5 为 0，第 3 条 ≤ 已知值且记入 `vf_mismatch`；
- `PRAGMA integrity_check` = `ok`；
- 故障注入 5 种全部回滚到 v16（§11.3）；
- 设备耗时 5k ≤ 3s（§5.13）。

### 9.3 C1-B3｜FTS 探测与同步

| 步 | 动作 | 产物 |
|---|---|---|
| 1 | `FtsModeCache`（`SharedPreferences` 三键） | 读写可测 |
| 2 | `detectFtsMode` 七步（§6.2） | 三档可复现 |
| 3 | `createFtsTable` / `createFtsTriggers` / `syncFts` / `rebuildFts` | 幂等可测 |
| 4 | `MemorySchema.attach(prefs)` 接入 `InboxDatabase` | 探测只跑一次 |
| 5 | 负向验证：在 `androidTest` 里人为把 `trusted_schema` 关掉（C1-A 的 step8 复用） | 探测返回 `APP_SYNC` 而不是 `TRIGGER` |

**完成条件**：
- 同一台设备连续打开 5 次，探测只执行 1 次（日志计数）；
- 人为制造坏环境（`PRAGMA trusted_schema=OFF`）时，探测返回 `APP_SYNC`（**这是最关键的一条**，它就是 C1-A 教训的落地）；
- `ftsMode=NONE` 时检索走 `LIKE` 且 `sensitive`/`scope`/`status` 三个约束仍在；
- `selfCheck` 的第 1、2 条在"人为删掉一条 FTS 行"后能报出来。

### 9.4 C1-B4｜Store / Manager

| 步 | 动作 | 产物 |
|---|---|---|
| 1 | `MemoryPolicy`（rank / cardinality / R4–R9） | 纯函数单测 |
| 2 | `MemoryStore.insert`（R0+R2+R3+R4+R5+R7+R8+R9 + 证据 + revision） | `T-S11`–`T-S20` |
| 3 | `correct` / `confirm` / `resolveConflict` | `T-S16` / `T-S18` |
| 4 | `softDelete` / `restore` | `T-D31` / `T-D44`–`T-D49` |
| 5 | 查询：`current` / `asOfWorld` / `asOfBelief` / `search` / `candidates` / `history` / **`conflictFacts`** | `T-T21`–`T-T43` / `T-X11` |
| 6 | `MemoryRepo` 重写为投影层（27 个签名）+ `projectStatus()` 显式七分支 | 现有 15 个 `MemorySqliteTest` 不动就绿 |
| 7 | `MemoryTestFixture` + scope 用例 | `T-C25`–`T-C30` / `T-C57` |
| 8 | `selfCheck` 10 组 | 空库返回空列表 |
| 9 | **D-6 六态投影与冲突用例** | `T-X05`–`T-X08`、`T-X12` |

**完成条件**：
- `MemoryRepo` 的 27 个签名逐条对照无变化（§8.5 的 review checklist）；
- 现有 `MemorySqliteTest` 的 15 例**只改 1 处**（v15 升级用例的版本断言 + `zhuli_fact` 不存在）就全绿——这是"兼容层有效"的最强证据；
- `T-C57` 通过：`PROJECT`/`CONVERSATION` 写入抛 `IllegalArgumentException`；
- `selfCheck()` 在干净库返回 `emptyList()`，在人为破坏的 4 种场景各返回 1 条；
- **六态投影一一对应、无合并**（`T-X06`）；`active()`/`unconfirmed()`/`history()`/`search()`/`conflicts()` 的可见性矩阵与 §7.12.4 逐格一致（`T-X07`）；
- **`conflicts()` 在"1 ACTIVE + 1 CANDIDATE"下返回 1 对**（旧实现返回 0 对，`T-X09`）。


### 9.5 C1-B5｜回归与验收

| 步 | 动作 | 产物 |
|---|---|---|
| 1 | 全量 `:app:testDevDebugUnitTest` | ≥ 396 例全绿 |
| 2 | `:app:connectedDevDebugAndroidTest` | D1–D4、D6、D7、D9、D10 |
| 3 | B1 的 12 个验收场景 | D8 |
| 4 | 飞行模式手测 | D5 |
| 5 | `git diff --stat` / `--check` / `--name-only` | §8.5 的自检清单 |
| 6 | 写 `docs/批次C1-B_实施与验收报告.md` | 含 D2 实测耗时 |

**完成条件**：§16 的 9 条退出标准全部满足，且每条有证据。

### 9.6 依赖与不可并行项

| 关系 | 说明 |
|---|---|
| B1 → B2/B3/B4 | 都要 `MemoryItem` / `MemorySchema` / `MemoryText` |
| B2 ∥ B3 | 互不引用（迁移自己建 FTS，不复用探测结果） |
| B3 → B4 | `MemoryStore` 要按 `ftsMode` 分支 |
| B2 → B4 | `MemoryRepo` 的 `byId` 等要读迁移后的数据（测试数据由 fixture 造，但表结构来自 B2） |
| B4 → B5 | 回归要跑完整实现 |

**唯一可以完全并行的是 B2 与 B3**。B2 的开发者不需要知道 `detectFtsMode` 怎么写，B3 的开发者不需要知道迁移怎么映射——两条线在 `MemorySchema` 的 DDL 常量上汇合（B1 已定）。

---

## 10. 测试矩阵

### 10.1 分层与文件分配

| 层 | 手段 | 文件 | 条数 |
|---|---|---|---|
| L0 能力门槛 | Robolectric + androidTest | `MemoryFtsRobolectricTest` / `MemoryFtsInstrumentedTest`（C1-A 已交付） | 7（已完成） |
| L1 纯函数 | JVM 单测 | `MemoryTextTest` + `MemoryFactStatusTest` 的解析部分 | 5 组 + `T-X01`/`T-X02`（不单独计） |
| L2 真实 SQLite | Robolectric `NATIVE` | `MemoryMigratorTest` / `MemoryStoreTest` / `MemoryTimeQueryTest` / `MemoryScopeTest` / `MemorySchemaTest` / `MemoryFactStatusTest` | **65** |
| L3 设备 | 模拟器 | `MemoryMigrationTimingTest` + 手工 | D1–D10 |
| L4 回归 | 现有套件 | `MemorySqliteTest` + B1 的 12 场景 | 331（基线） |

> 65 = 架构用例 47（A/B/C/D/E 组）+ D-6 的 12（`T-X01`–`T-X12`）+ D-7 的 6（`T-M40`–`T-M45`）。`T-X01`/`T-X02` 是纯函数，但为了"一个决策一个文件"的追溯性，放在 `MemoryFactStatusTest` 里统一维护，不拆到 `MemoryTextTest`。


### 10.2 A 组｜迁移（13 条，`MemoryMigratorTest`）

| # | 用例 | 断言（逐字照 C0.2 §11.2） | 造数据方式 |
|---|---|---|---|
| T-M01 | v15 库直接打开 | 事实不丢、`activeCount=1`、value 一致 | `MemorySqliteTest` 现有用例（改造） |
| T-M02 | 真实副本迁移 | 行数一致、替代链 1→2、向量指向 2、FTS 2 行 | `seedLegacy(2)` 复刻真实库形态 |
| T-M03 | 断链 `superseded_by` | 清 0，`invalidated_at` 保留 | 造 `superseded_by=999999` |
| T-M04 | SECRET 行 | 不迁入，`quarantined=1`，总数闭合 | 造 16 位卡号 |
| T-M05 | 空 subject / 空 value | 跳过，`skipped` 计数 | 造 2 行空值 |
| T-M06 | 同键重复合并 | 保留强证据、区间取最早起点、保留行得 SUPPORTING **且**被合并行 PRIMARY 仍在、`SAME_AS` | 造 USER_STATED + INBOX 同值 |
| T-M07 | MULTI 槽位两个值 | 都 ACTIVE，不合并 | 造两个取件码 |
| T-M08 | 唯一索引建立失败 | 抛 → 回滚 → `user_version=16` | 人为造重复且跳过合并（直接插） |
| T-M09 | 迁移后表名 | `zhuli_fact` 不存在，`zhuli_fact_v1` 存在且 0 写入 | 迁移后查 `sqlite_master` |
| T-M10 | 二次打开 | `onOpen` 不重复建表、不重复插数据 | 连开两次 |
| T-M36 | 旧 `valid_from` 不得升级为确定时间 | **每一行** `time_confidence='NONE'`，含 `valid_from>0` 的行 | `seedLegacy` |
| T-M37 | 旧 `valid_to` 的去向 | SUPERSEDED 行 `valid_until=0`、`invalidated_at=原 valid_to`；DELETED 行 `valid_until=0`、`invalidated_at=原 updated_at` | 造两组 |
| T-M38 | 未知写入方的告警 | 人造 `valid_from≠observed_at`：不中止、仍 `NONE`、`vf_mismatch+1`、原值保留 | 直接 UPDATE 造 |

**D-7 建表与升级回归（5 条，追加在 A 组之后）**：

| # | 用例 | 断言 | 造数据方式 |
|---|---|---|---|
| T-M41 | v16→v17 后关闭并重开（3 次） | `zhuli_fact` 始终不存在；`zhuli_fact_v1` 行数不变；`user_version` 始终 17；无异常 | `seedLegacy(11)` + 连续 `InboxDatabase(ctx)` 3 次 |
| T-M42 | 旧写路径 fail-loud | 迁移后 `INSERT INTO zhuli_fact(...)` 抛 `SQLiteException`，消息含 `no such table`；**不**静默成功 | `db.writableDatabase.execSQL` 直写 |
| T-M43 | `onOpen` 幂等 | 连开 3 次：`sqlite_master` 的对象名集合与数量不变；`COUNT(*)` 三张主表不变 | 同 `T-M41`，比较三次快照 |
| T-M44 | 重开后新记忆数据完整 | 逐列抽检 12 列 + 向量行数 + evidence 行数；与重开前一致 | `seedLegacy` 覆盖 ACTIVE/DELETED/SUPERSEDED/UNCONFIRMED 各一 |
| T-M45 | v16 库缺 `zhuli_fact` 时升级失败且不建空表 | 抛异常 → 回滚 → `user_version=16`；`zhuli_fact` 仍不存在（**没有**被补建） | 手工造只含 `inbox` 的 v16 库 |

> `T-M40`（新装库结构）不在本表：它测 `onCreate`，归 `MemorySchemaTest`（§4.8.6 已说明）。


### 10.3 B 组｜存储策略（11 条，`MemoryStoreTest`）

| # | 用例 | 断言 |
|---|---|---|
| T-S11 | 同值两来源 | 1 item、2 evidence（1 PRIMARY + 1 SUPPORTING）、`times_reinforced=1`、`ingest` 返回 0（第二来源不新建） |
| T-S12 | rank 更高 | 旧 SUPERSEDED + `SUPERSEDES` + `invalidated_at=now` |
| T-S13 | 旧 USER_CONFIRMED，新 RULE_EXTRACTED | 旧保持 ACTIVE，新 CANDIDATE + `CONFLICTS_WITH` |
| T-S14 | MULTI 槽位 | 两条 ACTIVE，`superseded_by=0` |
| T-S15 | 同 rank × 间隔 3 天 | 不替代，新 CANDIDATE + 冲突（**且用世界时间算 Δt**，见 §2.4 注） |
| T-S16 | `correct()` | 新行继承 `valid_from` 与 `time_confidence`；旧行 `valid_until=新 valid_from`；`CORRECTS` |
| T-S17 | STATE 变更 | 旧 EXPIRED（`valid_until=now`），新 ACTIVE，`WORLD_ASOF` 无重叠 |
| T-S18 | EVENT 纠错 | 旧 `event_start/event_end` 一字不变，`SUPERSEDED`，`CORRECTS` |
| T-S19 | 删除后重新写入同值 | 部分唯一索引允许插入 |
| T-S20 | DELETED/REJECTED 同值再提取 | 抑制；用户显式陈述允许复活（`RESTORES`） |
| T-S39 | R0 时间自洽 | `valid_from=now+30d` + `NONE` → 清零**并强制 CANDIDATE**；同条件 `DAY` → 保留 |

### 10.4 C 组｜时间与 as-of（8 条，`MemoryTimeQueryTest`）

| # | 用例 | 断言 |
|---|---|---|
| T-T21 | `WORLD_ASOF` 历史点 | `at<X` 命中旧值、不命中新值（有置信度的数据） |
| T-T22 | `BELIEF_ASOF` | 失效前命中、失效后不命中 |
| T-T23 | `valid_from=0` | CURRENT 可见；WORLD 以 `observed_at` 为下界；`boundary_unknown=true` |
| T-T24 | 纠正后 `WORLD_ASOF` | 旧值不出现（`CORRECTS` 排除），`BELIEF_ASOF` 仍可见 |
| T-T40 | CURRENT 排除未来生效 | ① `DAY` + `now+1d` → 0 命中；`now` 推后 → 命中。② `NONE` + 未来 → 清零且 CANDIDATE → **0 命中** |
| T-T41 | 半开区间精确边界 | `at=X-1` 只旧、`at=X` 只新、`at=X+1` 只新；任何时刻不出现两条 |
| T-T42 | 未知边界 → ambiguous | 迁移行在 WORLD 下与后续值并存 → `ambiguous=true` 且每条 `boundary_unknown=true`；BELIEF 同一时刻只 1 条 |
| T-T43 | 任务到期不被起点置信度挡住 | `valid_from=0 & NONE` + `valid_until=finishedAt+7d`：到期前命中、到期后 0 |

### 10.5 D 组｜Scope（7 条，`MemoryScopeTest`）

| # | 用例 | 断言 | 造数据 |
|---|---|---|---|
| T-C25 | 项目 A / B | 互不命中 | `MemoryTestFixture.insertRaw(PROJECT,"pA")` |
| T-C26 | 助手 A / B（含切换） | 互不命中，`GLOBAL` 仍可见 | fixture |
| T-C27 | 普通聊天读项目私有 | 0 命中 | fixture |
| T-C28 | TASK 过期 | `valid_until` 过后 0 命中 | fixture + 显式 `valid_until` |
| T-C29 | GLOBAL 进入项目任务 | 命中 | fixture |
| T-C30 | 非法 scope（`PROJECT` + 空 id） | 写入抛异常；检索抛异常 | `MemoryStore.insert` |
| T-C57 | 生产写入对 PROJECT/CONVERSATION 关闭 | 两者均抛 `IllegalArgumentException` | 直接调 `MemoryStore.insert` |

**14.3 的约束必须遵守**：`T-C25`–`T-C29` 的数据**只经** `MemoryTestFixture.insertRaw` 构造；`T-C30`/`T-C57` 测的正是"生产 API 拒绝"，两者不矛盾——前者绕过写入层直接造行测检索层，后者验证写入层不放行。

### 10.6 E 组｜删除与恢复（9 条，`MemoryStoreTest`）

| # | 用例 | 断言 |
|---|---|---|
| T-D31 | 软删 | 行在（DELETED）；`pre_delete_status` 正确；FTS/向量/队列/检索四路 0 |
| T-D32 | 硬删（store 级，C3 预演） | **C1 不实现**，只留设计（§17） |
| T-D33 | 共享来源 | 删 A 后 source 仍在（B 引用）；删 B 后 source 可 GC（C1 只断言"仍存在"，GC 在 C3） |
| T-D44 | 删 ACTIVE 再恢复 | `ACTIVE`、`deleted_at=0`、`invalidated_at=0`、`pre_delete_status=''`、向量重新入队、FTS 重命中 |
| T-D45 | 删 SUPERSEDED 再恢复 | `SUPERSEDED`（不是 ACTIVE）、`invalidated_at` 仍是替代时间、检索 0 |
| T-D46 | 删 CANDIDATE 再恢复 | `CANDIDATE`（不是 ACTIVE）、不进 CURRENT、在待确认列表 |
| T-D47 | 恢复时槽位被占 | 同槽不同值 ACTIVE → `Demoted(CANDIDATE)` + `CONFLICTS_WITH`；MULTI → `Restored(ACTIVE)` |
| T-D48 | 恢复时同值已占用 | `AlreadyPresent(id)`、不抛 UNIQUE、本行仍 DELETED、证据复制到占用行 |
| T-D49 | 删除/恢复的 revision | 两条（`DELETE`→`RESTORE`），`before_json`/`after_json` 成对，`actor='USER'` |

### 10.7 F 组｜Agent（2 条，**C3**）

`T-A34`（注入记忆块不改状态机轨迹）、`T-A35`（prompt injection 不改工具选择）——**C1 不实现**，因为 C1 不改 `AgentLoop`、不做记忆注入。

### 10.8 G 组｜FTS（7 条，**C1-A 已完成**）

`T-F50`–`T-F56` 在 C1-A 的 `MemoryFtsInstrumentedTest` / `MemoryFtsRobolectricTest` 中已全部通过（设备端 9/9）。C1-B **不重写**，只在 B5 回归时重跑一次确认没被后续改动破坏。

### 10.9 H 组｜兼容状态与建表回归（18 条，**D-6 / D-7 新增**）

这一组不属于 C0.2 的 57 条编号体系，编号独立：`T-X*` 是状态兼容，`T-M4x` 是建表回归。

#### 10.9.1 D-6｜状态兼容（12 条，`MemoryFactStatusTest`）

| # | 用例 | 断言 | 层 |
|---|---|---|---|
| T-X01 | 六个业务状态名解析 | `of("ACTIVE")`=ACTIVE、`of("UNCONFIRMED")`=UNCONFIRMED、`of("SUPERSEDED")`、`of("EXPIRED")`、`of("REJECTED")`、`of("DELETED")` 全部命中；`of("UNKNOWN")`=UNKNOWN | L1 |
| T-X02 | 未知值一律 UNKNOWN | `null` / `""` / `"active"` / `"CANDIDATE"` / `"garbage"` / `"EXPIRED "`（尾空格）→ 全部 UNKNOWN；**没有一个是 ACTIVE** | L1 |
| T-X03 | 序列化往返 | 7 个值 `name` → `of(name)` 回到原值；`ordinal` 变化不影响（禁止按序号存） | L1 |
| T-X04 | UNKNOWN 非当前、不可激活 | `UNKNOWN.isCurrent=false`、`isActionable=false`；`search`/`active`/`conflicts` 均不返回；`confirmFact` 对 UNKNOWN 行返回 false（`WHERE status='CANDIDATE'` 挡住） | L1+L2 |
| T-X05 | v16 旧四态兼容 | 迁移后 `ACTIVE`→ACTIVE、`UNCONFIRMED`→UNCONFIRMED、`SUPERSEDED`→SUPERSEDED、`DELETED`→DELETED；`activeCount` 与旧 `status='ACTIVE'` 行数一致 | L2 |
| T-X06 | 六态投影一一对应 | 新表 6 个字符串 → `FactStatus` 6 个不同值，两两不相等；`CANDIDATE`→`UNCONFIRMED`（不是 UNKNOWN） | L2 |
| T-X07 | 四态语义区分（可见性矩阵） | 按 §7.12.4 的表逐格断言 6 态 × 5 查询 = 30 格；`EXPIRED`/`REJECTED`/`DELETED` 三者在 `history` 里可见且互不相等 | L2 |
| T-X08 | 迁移遇未知状态值 | v16 库手工写 `status='garbage'` → 迁移后 `status='CANDIDATE'`、`unknown_status=1`、`payload_json` 含 `legacy_status`；**不落 ACTIVE** | L2 |
| T-X09 | ACTIVE + CANDIDATE 同槽冲突 | 1 ACTIVE + 1 CANDIDATE（值不同）→ `conflicts()` 返回 1 对；旧实现返回 0 对 | L1+L2 |
| T-X10 | 两条 CANDIDATE 同槽 | 0 ACTIVE + 2 CANDIDATE（值不同）→ 返回 1 对（相邻配对兜底） | L1 |
| T-X11 | MULTI 槽位不误报 | `cardinality='MULTI'` 的同槽两条 ACTIVE → `MemoryRepo.conflicts()` 返回空 | L2 |
| T-X12 | 非行动态不参与冲突 | `EXPIRED`/`REJECTED`/`DELETED`/`UNKNOWN` 各与 ACTIVE 同槽 → `conflicts()` 返回空 | L1+L2 |

> `T-X09` 是这一组的"回归锚点"：它断言的正是 v1 方案里 X-3 描述的那个 bug（旧实现 0 对）。C1-B4 完成后如果这条失败，说明 `MemoryRanker.conflicts` 的过滤没改对。

#### 10.9.2 D-7｜建表回归（6 条，`MemorySchemaTest` + `MemoryMigratorTest`）

已在 §4.8.6 与 §10.2 列出：`T-M40`（Schema）、`T-M41`–`T-M45`（Migrator）。此处不重复。

#### 10.9.3 两组用例的"双模式"跑法

`T-X04`/`T-X05`/`T-X06`/`T-X07`/`T-X08`/`T-X11`/`T-X12` 走真实 SQLite，**必须在 `TRIGGER` 与 `APP_SYNC` 两种 `ftsMode` 下各跑一遍**（K-7 的要求）。`T-X01`–`T-X03`、`T-X10` 是纯函数，不涉及 `ftsMode`，只跑一遍。

因此实际执行次数 = 12 条中 7 条 ×2 + 5 条 ×1 = **19 次**，但**用例数仍按 12 计**（测试报告里的 `tests` 计数按 `@Test` 方法算，不按参数化展开算——这点与 §10.11 的口径一致）。

### 10.10 设备验收 D1–D10

| # | 项 | 通过标准 | 证据 |
|---|---|---|---|
| D1 | 覆盖升级 v16→v17 | 首启不崩；记忆页显示迁移后的条目 | `adb install -r` + 截图 + 迁移日志 |
| D2 | 迁移耗时 | 5k ≤3s；记录实测 | `MemoryMigrationTimingTest` 的 `Log.i` |
| D3 | FTS4 建表 | `sqlite_master` 有 `zhuli_memory_fts` + 3 触发器（或 `APP_SYNC` 时无触发器但检索正确） | `adb shell` 查询 |
| D4 | 设备检索 | 搜"验收"命中迁移来的两条 | 手工 + 截图 |
| D5 | 本地模式 | 飞行模式完整可用，无网络调用 | 飞行模式手测 + `cloud_log` 无新行 |
| D6 | 严格模式/隐私 | `save_fact` 传手机号 → SENSITIVE；传 16 位卡号 → 拒绝 | 日志 + 库查询 |
| D7 | StrictMode | 无主线程磁盘读写告警 | logcat |
| D8 | B1 回归 | 12 个场景全过 | B1 的验收脚本 |
| D9 | 时间语义端到端 | 不显示伪造的"何时开始"；CURRENT 不返回未来生效项 | 截图 + 库查询 |
| D10 | 恢复端到端 | 删 SUPERSEDED → 恢复 → 没变成当前值 | 截图 |

### 10.11 数量核算

| 组 | 条数 | C1 实现 | 已在 C1-A | C1-B 新增 |
|---|---|---|---|---|
| A 迁移 | 13 | 13 | 0 | **13** |
| B 存储 | 11 | 11 | 0 | **11** |
| C 时间 | 8 | 8 | 0 | **8** |
| D Scope | 7 | 7 | 0 | **7** |
| E 删除恢复 | 9 | 8（`T-D32` 归 C3） | 0 | **8** |
| F Agent | 2 | 0（归 C3） | 0 | **0** |
| G FTS | 7 | 7 | **7** | 0 |
| **架构用例小计** | **57** | **54** | **7** | **47** |
| H-1 D-6 状态兼容 | 12 | 12 | 0 | **12** |
| H-2 D-7 建表回归 | 6 | 6 | 0 | **6** |
| **决策用例小计** | **18** | **18** | **0** | **18** |
| **合计** | **75** | **72** | **7** | **65** |

```
C1-B 结束时预期用例数 = 331（本轮实测基线）+ 65 = 396
其中 架构用例 47（L2）+ 决策用例 18（L1/L2），L1 的 5 组纯函数不计入编号（属实现质量）
```

**三个数字的关系（防止后面看混）**：

| 数字 | 含义 | 谁在用 |
|---|---|---|
| `57` | C0.2 §11.2 定义的**架构用例**总数 | C0.2 的验收口径，不变 |
| `75` | C1-B 的**全口径**用例数（57 + 18） | 本文 §15 的阶段验收 |
| `65` | C1-B **新增**（47 架构 + 18 决策） | 测试总数目标 396 的加数 |

> `T-D32` / `T-A34` / `T-A35` 仍归 C3（3 条），所以"架构用例 C1 实现 54"不变。D-6/D-7 的 18 条**没有一条**可以推到 C3：它们测的是 C1-B 自己引入的兼容层与建表逻辑，C3 之前不测就等于不测。


---

## 11. 数据迁移预演计划

### 11.1 四档规模（沿用 C0.2 §9 的库，不新造）

| 档 | 库 | 行数 | 用途 | 判定 |
|---|---|---|---|---|
| S0 | `Temp/c02/zhuli_inbox_v16_real.db` | 2 | 真实形态（1 ACTIVE + 1 SUPERSEDED + 1 向量） | 必须全绿 |
| S1 | `Temp/c02/v16_edge.db` | 11 | 边界样本（空值、断链、SECRET、重复、MULTI） | 必须全绿 |
| S2 | `Temp/c02/v16_5k.db` | 5,002 | 中等规模 + 耗时长 | 必须全绿 |
| S3 | `Temp/c02/v16_20k.db` | 20,002 | 大规模 + 体积 | 必须全绿 |

**C1-B2 的做法**：不直接改这 4 个文件（C0.2 的预演产物要保留原样），而是 `cp` 到 `Temp/c1b/` 再跑。产物命名 `v17_b1_{s0,s1,s2,s3}.db`。

### 11.2 边界样本清单（S1 必须覆盖）

| # | 形态 | 期望 |
|---|---|---|
| 1 | 空 `subject` | `skipped` |
| 2 | 空 `value` | `skipped` |
| 3 | 16 位卡号 | `quarantined`，不进新表 |
| 4 | `superseded_by=999999`（断链） | 清 0，`invalidated_at` 保留 |
| 5 | 两条同值不同来源 | 合并，保留强者 |
| 6 | 两条取件码 | 不合并，都 ACTIVE（MULTI） |
| 7 | `valid_from != observed_at`（人为） | `vf_mismatch+1`，不中止 |
| 8 | `status='UNCONFIRMED'` | → `CANDIDATE` |
| 9 | `status='DELETED'` | `deleted_at=updated_at`，`pre_delete_status=''` |

### 11.3 故障注入（5 种，全部要回滚到 v16）

| # | 注入方式 | 期望 |
|---|---|---|
| F1 | 人为造重复 + 跳过合并 → 建唯一索引失败 | 抛 → 回滚 → `user_version=16`，`zhuli_fact` 仍叫原名 |
| F2 | 单行插入失败（用一条超长 `value` 触发约束或人为 mock） | `rowErrors+1` 继续 → 数量不闭合 → 回滚 |
| F3 | 建 FTS 前抛异常（`DROP` 掉临时能力） | 回滚（FTS 阶段不在"可降级"范围，只有"建不出来"才降级） |
| F4 | 改名失败（人为先建一张 `zhuli_fact_v1`） | 抛 → 回滚 → 数据仍在事务里撤销 |
| F5 | 迁移中途 `kill -9`（设备上 `am force-stop`） | 下次打开重跑；无半迁移状态（SQLite 回滚日志） |

> F5 无法在 Robolectric 里模拟，只能在设备上做（`MemoryMigrationTimingTest` 的 `@Test` 里不能真杀进程，所以 F5 走**手工步骤**，写进 C1-B5 报告的"手工验证"小节）。

### 11.4 不变量校验（迁移末尾 + 预演脚本各跑一遍）

```
① 数量闭合：src == inserted + quarantined + skipped
② C0.2 §6.6 的 10 组（11 条 SQL）全部返回 0 行
③ 5 条时间断言：1/2/4/5 = 0；3 允许 >0 但必须记入 vf_mismatch
④ PRAGMA integrity_check = ok
⑤ PRAGMA foreign_key_check = 空（虽然没建外键，仍跑一次防将来加）
⑥ sqlite_sequence.seq == MAX(id)
⑦ zhuli_fact 不存在 / zhuli_fact_v1 存在
⑧ FTS 行数 == COUNT(item WHERE status<>'DELETED')（TRIGGER 模式下）
```

### 11.5 设备耗时测试

见 §5.13。要点重复一次：**桌面 Python 的 0.17–0.21 ms/行不能当设备数据**，必须 `MemoryMigrationTimingTest` 在模拟器/真机上跑 1k/5k/20k 三档，记录 `ms` 与 `pss_kb`。

### 11.6 产物与路径

| 产物 | 路径 | 入库 |
|---|---|---|
| 迁移后的 4 个库 | `Temp/c1b/v17_b1_s{0..3}.db` | **否** |
| 耗时日志 | `Temp/c1b/timing_*.txt` | 否 |
| 故障注入日志 | `Temp/c1b/fault_*.txt` | 否 |
| 汇总结论 | C1-B5 报告的一节 | 是（报告里） |

`Temp/c02/` 与 `Temp/c1a/` **一律不动**（本轮明令禁止清理）。

---

## 12. 回滚方案

### 12.1 三个层次

| 层 | 触发 | 动作 | 数据影响 |
|---|---|---|---|
| **L1 事务回滚** | 迁移中任何未预期异常 | `onUpgrade` 的 `finally` 自动 `endTransaction()` 未提交 → SQLite 回滚 | 库仍是 v16，`zhuli_fact` 原样 |
| **L2 备份恢复** | L1 之后仍打不开（极端：回滚日志损坏） | 用 `databases/pre_migration/v16_*.db` 覆盖主库 | 回到升级前 |
| **L3 APK 降级** | 已升级到 v17，想退回旧版本 App | **不支持**（`onDowngrade` 未实现，`SQLiteOpenHelper` 抛异常） | 需要卸载重装（数据丢失） |

### 12.2 迁移失败的完整路径

```
migrate() 抛异常
  └─ onUpgrade catch → lastMigrationError = "v16→v17：<原因>"
      └─ Log.e（message 已 redact）
          └─ rethrow
              └─ SQLiteOpenHelper: T0 未 setSuccessful → 回滚
                  └─ 抛给 getWritableDatabase 调用方
                      └─ 现有机制：App 显示"数据库打不开"的降级页（B 批次已实现）
```

**关键**：`lastMigrationError` 是**静态字段**（`InboxDatabase.lastMigrationError`，已存在），下次启动时自检页能读到——用户不需要连电脑就能看到"上次升级失败，原因 X"。

### 12.3 为什么不做 `onDowngrade`

| 理由 | 说明 |
|---|---|
| 数据不可逆 | v17 的新表在降级后会变成"未知表"，`zhuli_fact` 已被改名，旧代码会 `no such table` 直接崩 |
| 迁移是可选的单行道 | 一旦写入新表，回退意味着丢新数据 |
| 替代方案 | 卸载重装（用户明确知道会清空）；或从 L2 备份恢复（需要 root/adb） |

C1-B 在 `onDowngrade` 里**不做任何事**（保持 `SQLiteOpenHelper` 默认抛异常），但把异常消息改成可读的中文提示 `[实现补充]`。这条改动在 `InboxDatabase.kt` 内，不新增文件。

### 12.4 回滚演练（C1-B5 必做）

| # | 演练 | 判据 |
|---|---|---|
| R1 | 用 §11.3 的 F1 注入，确认回滚到 v16 | `user_version=16`，`zhuli_fact` 有全部行 |
| R2 | 用 L2 备份覆盖后打开 | App 正常启动，记忆页显示 v16 的数据（旧代码路径） |
| R3 | 迁移成功后主动 `DROP` 新表再打开 | `onOpen` 的 `ensureAll` 能把表补回来（数据不回来，但结构自愈） |

---

## 13. 风险清单（14 项）

### 13.1 总表

| # | 风险 | 等级 | 主要防线 | 验收证据 |
|---|---|---|---|---|
| K-1 | 迁移时旧数据被错误解释为当前事实 | **高** | `time_confidence='NONE'` + `LB` 退回 `observed_at` | `T-M36` / D9 |
| K-2 | 迁移后未来生效事实提前生效 | **高** | R0 时间自洽（清零 + 强制 CANDIDATE） | `T-S39` / `T-T40` |
| K-3 | 多来源共享导致误删 | 中 | C1 无硬删；软删保留 evidence/source | `T-D33` |
| K-4 | SUPERSEDED 恢复后重新成为当前值 | **高** | `pre_delete_status` + 恢复状态表 | `T-D45` / D10 |
| K-5 | CANDIDATE 状态与唯一索引冲突 | **高** | 恢复第 2 步同时查 ACTIVE **和** CANDIDATE | `T-D48` |
| K-6 | 软删/恢复与 FTS 索引不一致 | 中 | 触发器 + `APP_SYNC` + `selfCheck` 第 1/2 条 | `T-D31` / `T-D44` |
| K-7 | TRIGGER 与 APP_SYNC 两条路径行为不一致 | **高** | 同一套用例双跑（`ftsModeOverride`） | §10 的全部 L2 用例 ×2 |
| K-8 | SQLite 版本差异导致迁移失败 | 中 | 只用 3.22 起可用的语法；两版本实测 | `MemorySchemaTest`（3.32）+ 设备（3.42） |
| K-9 | SECRET 在备份/临时文件/日志中残留 | **高** | 迁移不迁入 + 日志无原文 + 备份清理在 C3 明确 | `T-M04` + 日志 grep + 14.2 阶段表 |
| K-10 | Scope 过滤遗漏导致跨项目读取 | **高** | `scopeClause()` 单点 + 空列表抛异常 | `T-C25`–`T-C30` / `T-C57` |
| K-11 | 旧 `Fact` 字段变更导致下游崩溃 | 中 | 27 个签名不变 + 投影显式 `when` | 现有 15 例全绿 + grep |
| K-12 | 迁移与 Agent 正在执行的任务冲突 | 中 | 单事务 + 不改 `zhuli_agent_task` + 耗时上界 | D2 / D8 |
| K-13 | `FactStatus` 扩展后，未知状态被当作当前值 | **高** | `of()` 返回 `UNKNOWN` + 投影显式七分支 + `isCurrent`/`isActionable` | `T-X01`–`T-X07` |
| K-14 | 建表拆分不彻底：旧表复活或核心表缺失 | **高** | 三入口职责表（§4.7）+ `createCoreTablesV15` 保留全部非 fact 表 | `T-M40`–`T-M45` |

### 13.2 逐项展开

#### K-1｜旧数据被错误解释为当前事实

| 项 | 内容 |
|---|---|
| **发生条件** | 迁移时按"`valid_from>0` 就说明知道起点"处理，或把 `valid_to` 当世界时间终点 |
| **影响范围** | 所有 v16 存量行（20k 规模下 100%）；记忆页显示"2026-11-25 起使用的手机"，而用户 2024 年就换了 |
| **代码位置** | `MemoryMigrator.migrateRow()` 的 `time_confidence` 与 `valid_until` 两列 |
| **预防** | ① `time_confidence = "NONE"` 写成常量，**不放在 `if` 里**；② `valid_until = 0` 写成常量；③ 旧 `valid_to` 只进 `invalidated_at`；④ 查询端只认 `LB`（`MemoryText.LB_SQL` 唯一来源） |
| **验收** | `T-M36`（每一行 `NONE`，含 `valid_from>0` 的行）；`T-M37`（`valid_to` 的去向）；D9（记忆页不显示伪造的"何时开始"）；迁移日志 `vf_mismatch` |

#### K-2｜未来生效事实提前生效

| 项 | 内容 |
|---|---|
| **发生条件** | ① `CURRENT` 只看 `valid_until`；② R0 清零 `valid_from` 但没改状态（清零后 `LB` 退回 `observed_at`，立刻变成当前值） |
| **影响范围** | 任何 `valid_from > now` 的行；C1 里只有"用户说'明年起'"这一条路径，但 C2 的抽取会大量产生 |
| **代码位置** | `MemoryStore.current()` 的谓词；`MemoryPolicy` 的 R0 |
| **预防** | ① `CURRENT` 含 `LB <= now`；② R0 清零**并强制 `CANDIDATE`**（C0.2 §4.5 的注）；③ `WB` 不设 `time_confidence` 门槛（否则任务永不过期） |
| **验收** | `T-T40` 的两段（`DAY` 保留 + 排除；`NONE` 清零 + CANDIDATE + 0 命中）；`T-T43`（任务到期不被起点置信度挡住） |

#### K-3｜多来源共享导致误删

| 项 | 内容 |
|---|---|
| **发生条件** | 删除记忆 A 时连带删掉 source，而记忆 B 的 evidence 还引用它 → B 的"凭什么"变空 |
| **影响范围** | `zhuli_memory_source` 与 `evidence`；C1 只有软删，风险有限，但 C3 接硬删后会变高 |
| **代码位置** | `MemoryStore.softDelete()`（**不删** source/evidence）；C3 的 `purgeSources()`（不在 C1） |
| **预防** | ① C1 软删只删 FTS/向量/队列，**保留** evidence 与 source；② 硬删的 GC 用 `NOT EXISTS` 引用计数（C0.2 §5.5），不用计数器；③ `selfCheck` 第 6 条报告无引用来源，但**不自动删** |
| **验收** | `T-D33`（删 A 后 source 仍在） |

#### K-4｜SUPERSEDED 恢复后重新成为当前值

| 项 | 内容 |
|---|---|
| **发生条件** | 恢复一律写 `status='ACTIVE'`（C0.1 的写法） |
| **影响范围** | 一个槽位两个 ACTIVE → `CURRENT` 返回两个矛盾值 → 模型静默出错；这是 C0.2 缺陷 #3 |
| **代码位置** | `MemoryStore.restore()` 第 1 步（`target = pre_delete_status`） |
| **预防** | ① `pre_delete_status` 仅首次记录；② `target` 按状态表（C0.2 §6.9）；③ `''` → `CANDIDATE`（不猜）；④ 第 3 步查槽位，占用则 `Demoted` |
| **验收** | `T-D45`（`SUPERSEDED` 恢复后仍是 `SUPERSEDED`，检索 0 命中）；`T-D46`（`CANDIDATE` 不进 CURRENT）；D10 |

#### K-5｜CANDIDATE 状态与唯一索引冲突

| 项 | 内容 |
|---|---|
| **发生条件** | 以为"降级成 CANDIDATE 就不会撞唯一索引"，于是恢复时直接 UPDATE |
| **影响范围** | `idx_mi_dedupe` 是 `WHERE status IN ('ACTIVE','CANDIDATE')` 的**部分**唯一索引，两态都占索引 → `SQLiteConstraintException` |
| **代码位置** | `MemoryStore.restore()` 第 2 步的查询条件 |
| **预防** | ① 第 2 步查询**同时**含 `ACTIVE` 与 `CANDIDATE`；② 命中时返回 `AlreadyPresent` 并把 evidence 复制给占用行（不丢"这条值被说过"）；③ **不**用 `INSERT OR REPLACE`（会静默覆盖占用行） |
| **验收** | `T-D48`（`AlreadyPresent`，不抛 UNIQUE，本行仍 DELETED，证据已复制）；C0.2 §6.9 的探针结果作为设计依据 |

#### K-6｜软删/恢复与 FTS 索引不一致

| 项 | 内容 |
|---|---|
| **发生条件** | ① `ftsMode=APP_SYNC` 时漏调 `syncFts`；② 软删后索引行没删（`au` 触发器没覆盖 `status`）；③ 恢复后没重挂 |
| **影响范围** | 检索"看起来正常但漏结果"或"删了还能搜到"——后者是隐私问题 |
| **代码位置** | `MemoryStore` 的 4 个写路径（insert/update/softDelete/restore）；`MemorySchema` 的触发器 DDL |
| **预防** | ① 触发器用 `AFTER UPDATE OF search_text, status`（含 status）；② `APP_SYNC` 下 4 处显式 `syncFts`，**集中在 `MemoryStore`**，不散落；③ `selfCheck` 第 1/2 条兜底；④ `rebuildFts()` 提供修复入口 |
| **验收** | `T-D31`（四路 0 命中）；`T-D44`（恢复后重命中）；`T-F54`/`T-F55`（C1-A 已验）；`selfCheck` 在人为破坏后能报出 |

#### K-7｜TRIGGER 与 APP_SYNC 两条路径行为不一致

| 项 | 内容 |
|---|---|
| **发生条件** | 只在 `TRIGGER` 下跑测试（C1-A 的结论是 `TRIGGER`，很容易忘了另一条路） |
| **影响范围** | 换一台 ROM 落到 `APP_SYNC` 时行为不同 → 用户侧"有的机器能搜到、有的搜不到" |
| **代码位置** | `MemoryStore` 的构造参数 `ftsModeOverride`；`MemorySchema.detectFtsMode` |
| **预防** | ① `MemoryStore(db, ftsModeOverride: FtsMode? = null)`——测试传 `APP_SYNC`，生产不传；② **全部 L2 用例跑两遍**（TRIGGER / APP_SYNC）；③ `NONE` 单独一组 3 条（检索降级但权限不降） |
| **验收** | §10 的 47 条 × 2 模式全绿；`grep "ftsModeOverride" app/src/main` 只命中构造参数与 `if` 分支（无生产调用方传值） |

> `[实现补充]` 这是 C0.2 没写、但必须有的测试夹层。没有它，"两种模式行为一致"只是一句口号。

#### K-8｜SQLite 版本差异导致迁移失败

| 项 | 内容 |
|---|---|
| **发生条件** | 用了高于 minSdk 对应 SQLite 版本的语法；或 ROM 裁掉了 FTS4 |
| **影响范围** | 迁移整体回滚（不会半迁移），但用户升级失败 |
| **代码位置** | `MemorySchema` 的全部 DDL；`MemoryMigrator` 的 13 步 |
| **预防** | ① 只用 SQLite **3.22**（API 28）起可用的语法：`CREATE TABLE` / 部分索引（3.8+）/ `ALTER TABLE RENAME`（3.1+）/ FTS4（3.x）/ `DROP TABLE IF EXISTS`；**不用** `UPSERT`（3.24+）、`RETURNING`（3.35+）、窗口函数（3.25+）；② FTS 建不出不中止（降 `NONE`）；③ 迁移前断言能建临时表 |
| **验收** | `MemorySchemaTest` 在 Robolectric `NATIVE`（3.32.2）跑一遍；设备（3.42.0）跑一遍；**API 28（3.22）未验证**，列进 §19 的遗留风险 |

#### K-9｜SECRET 在备份/临时文件/日志中残留

| 项 | 内容 |
|---|---|
| **发生条件** | ① 迁移把 SECRET 降级成 SENSITIVE 迁入；② 日志打了 `subject`/`value`；③ 备份目录 `databases/pre_migration/v16_*.db` 里还有一份完整旧库；④ 开发机的 `Temp/` 副本 |
| **影响范围** | 用户设备上的明文敏感数据；`adb backup` / 云备份 / 换机迁移会带走 |
| **代码位置** | `MemoryMigrator` 的 `quarantined` 分支与日志语句；`backupBeforeMigration`（既有） |
| **预防** | ① `MemoryGuard.classify(content)==SECRET` → 跳过 + 计数，**禁止降级**；② 日志只写计数（§5.10 的字段清单），`message` 过 `redact`；③ `selfCheck` 报 `zhuli_fact_v1` 剩余行数（只报数）；④ C0.2 §14.2 的阶段 3 把"清理备份"列为验收条件（C3 执行，但 C1 要**告知**） |
| **验收** | `T-M04`（不迁入 + 计数闭合）；`grep -n "quarantined" MemoryMigrator.kt` 的语句里无 `content`/`subject`/`value`；C1-B5 报告里明确写"迁移后有 N 条敏感旧数据留在 `zhuli_fact_v1`，将在 C3 清理时一并删除" |

#### K-10｜Scope 过滤遗漏导致跨项目读取

| 项 | 内容 |
|---|---|
| **发生条件** | ① 某个查询忘了带 scope 子句；② 用 `scope_type != 'PROJECT'` 这种白名单倒推（会把 `CONVERSATION` 放进来）；③ `scopes` 为空时"返回全部" |
| **影响范围** | 项目 A 的记忆出现在项目 B；这是隐私事故，不是功能 bug |
| **代码位置** | `MemoryStore.scopeClause()`（唯一拼接点）；`MemoryQuery.init` |
| **预防** | ① 所有查询走同一个 `scopeClause()`，**没有第二个入口**；② 空列表抛 `IllegalArgumentException`（不是返回空）；③ 显式 OR 列表，永不倒推；④ `LIKE` 降级时同样带这四个约束 |
| **验收** | `T-C25` / `T-C26` / `T-C27` / `T-C30`；`grep -c "scope_type" MemoryStore.kt` 只出现在 `scopeClause` 与 `selfCheck` |

#### K-11｜旧 `Fact` 字段变更导致下游崩溃

| 项 | 内容 |
|---|---|
| **发生条件** | 重写 `MemoryRepo` 时顺手把 `Fact.validTo` 删了，或把 `status` 换成新枚举 |
| **影响范围** | `ZhuliMemoryPage` / `ZhuliAgentTools` / `MemoryRanker` / `MemoryBackfill` 编译失败或运行时 `NoSuchField` |
| **代码位置** | `MemoryRepo` 的投影函数；`MemoryTypes.kt`（**不动**） |
| **预防** | ① `MemoryTypes.kt` 零改动（10.3）；② 27 个签名逐条对照（§8.5）；③ 投影用显式 `when(status)`，**禁止 `FactStatus.of(raw)`**（那个 fallback 会把 `EXPIRED` 变成 `ACTIVE`，见 X-2）；④ `validFrom`/`validTo` 保留字段（值可能恒 0） |
| **验收** | 现有 `MemorySqliteTest` 15 例只改 1 处版本断言即全绿；grep 查 `.validFrom` / `.validTo` 仍只命中写入点；编译通过 |

#### K-12｜迁移与 Agent 正在执行的任务冲突

| 项 | 内容 |
|---|---|
| **发生条件** | ① 迁移在后台 Worker 里触发，主线程同时在跑 Agent 任务，写 `zhuli_agent_task` 被写锁挡住；② 通知回调（~10s 预算）撞上 3s 迁移 → 丢通知；③ 迁移失败导致任务状态机读到"库打不开" |
| **影响范围** | 任务步骤延迟、通知丢失；**不会**损坏任务数据（迁移不碰 `zhuli_agent_task`） |
| **代码位置** | `InboxDatabase.onUpgrade`；`agent/SqlAgentStore`；`ZhuliAgentRuntime.init` 的 `markOrphansPaused` |
| **预防** | ① 迁移在**单个事务**内完成，失败整体回滚（不会半迁移）；② 迁移范围**不含** `zhuli_agent_task` / `zhuli_agent_step`（`git diff` 可证：`MemoryMigrator` 只碰 `zhuli_fact*` 与 `zhuli_memory_*`）；③ 耗时上界 3s（D2），超则启用 C0.2 §9.6 的分批方案；④ debug 下加线程断言，把"迁移发生在哪个线程"打进日志（A-4）；⑤ B1 的 12 场景回归（D8）验证状态机轨迹不变 |
| **验收** | D2（耗时）+ D8（12 场景）；`MemoryMigrationTimingTest` 打印 `thread=` 字段；迁移日志的 `ms` ≤ 3000 |

> 一个尚未验证的点：迁移期间若有 `NotificationListenerService` 回调，`getWritableDatabase` 会阻塞到迁移提交。C1-B5 的 D1 手工步骤里加一条"迁移进行中发一条通知"，观察是否丢失。**这是 §19 的遗留项 U-3。**

#### K-13｜`FactStatus` 扩展后，未知状态被当作当前值

| 项 | 内容 |
|---|---|
| **发生条件** | ① 某处仍用 `FactStatus.of()` 且它 fallback 到 `ACTIVE`；② 有人用 `FactStatus.valueOf(raw)`，未知值直接抛异常；③ `when(status)` 没有 `else`，新增枚举值后编译失败或走错分支；④ 投影时把 `EXPIRED` 合并进 `SUPERSEDED`、"反正都是过去" |
| **影响范围** | 记忆页把已过期/已否决/无法识别的行显示成**当前值**；模型拿到过期任务当成待办；这是 C0.2 缺陷 #2 在兼容层的翻版 |
| **代码位置** | `MemoryTypes.kt:56-71`（枚举与 `of()`）；`MemoryRepo.kt:478`（`read()`）；`MemoryRanker.kt:97/112`（过滤）；`MemoryRepo.projectStatus()`（新增） |
| **预防** | ① `of()` 改 `?: UNKNOWN`，KDoc 写明"禁止 fallback"；② 投影用**显式七分支 + else**，不用 `of()`（因为 `CANDIDATE` 必须映射成 `UNCONFIRMED`）；③ `isCurrent` / `isActionable` 只在枚举内部定义，调用方不许自己拼"等于 ACTIVE 或等于 UNCONFIRMED"的判断；④ 禁止 `valueOf`；⑤ review checklist 加一条"新增枚举值后，所有 `when(FactStatus)` 是否都有 else" |
| **验收** | `T-X01`（六态解析）；`T-X02`（未知值不落 ACTIVE）；`T-X04`（UNKNOWN 非当前、不可激活）；`T-X06`（六态一一对应）；`T-X07`（30 格可见性矩阵）；`grep "?: ACTIVE"` = 0；`grep "FactStatus.valueOf"` = 0 |

#### K-14｜建表拆分不彻底：旧表复活或核心表缺失

| 项 | 内容 |
|---|---|
| **发生条件** | ① 只改 `onCreate` 忘了 `onOpen`（或反之）；② 拆分时把 `createCoreTables` 整段删掉，连带丢了 `zhuli_agent_task` / `zhuli_agent_step` / `zhuli_fact_vec`；③ `onUpgrade` 的 `oldVersion<15` 分支漏了 `createFactTableV15`，v14 库升级时迁移前置断言失败；④ 为了"干净"顺手删掉 `zhuli_entity` 的建表 |
| **影响范围** | ②③④ 都是**灾难级**：Agent 状态机表缺失 → 任务系统崩；向量表缺失 → 索引器崩；`zhuli_entity` 缺失 → C2 的实体写入无处可去。①会让 `T-M09` 必然失败、fail-loud 失效 |
| **代码位置** | `InboxDatabase.kt:317`（onCreate）/ `:339`（onOpen）/ `:787`（onUpgrade 的 `<15`）/ `:501`（`createCoreTables` 定义） |
| **预防** | ① §4.8.1 的 20 表分类表，拆分前后逐表核对；② `createCoreTablesV15` 是原函数的**真子集**（只删 fact 表 + 3 个索引），不许动别的；③ 三个调用点用 §4.8.2 的对照表逐行替换；④ `grep "fun createCoreTables\b"` 必须为 0（旧函数名彻底消失，防止有人只改一半） |
| **验收** | `T-M40`（新装无旧表、有全部核心表）；`T-M41`（重开不复活）；`T-M42`（旧写 fail-loud）；`T-M43`（onOpen 幂等）；`T-M45`（缺表不补建）；`T-M01`（v15→v17 存量事实与任务都不丢） |

---


## 14. 性能验收标准

### 14.1 迁移

| 指标 | 门槛 | 测量方式 | 依据 |
|---|---|---|---|
| 5,000 行 | **≤ 3,000 ms** | `MemoryMigrationTimingTest`（设备） | C0.2 A-3 |
| 20,000 行 | **≤ 12,000 ms** | 同上（线性外推 4×） | 本方案 |
| 单行平均 | ≤ 0.60 ms | `ms / rows` | 本方案 |
| 峰值内存增量 | ≤ 40 MB | `Debug.MemoryInfo.totalPss` 前后差 | 本方案 |
| 失败率 | 0 | 4 档库各跑 3 次 | — |

超门槛的处置：启用 C0.2 §9.6 的分批方案（`MemoryMigrator` 支持 `batchSize` 参数 + `onUpgrade` 内的分批提交）。**C1-B 内实现，不推迟到 C2**——因为"用户升级后打不开"是不可接受的。

### 14.2 查询

| 场景 | 数据量 | 门槛 | 索引 |
|---|---|---|---|
| `CURRENT`（单 scope，limit 200） | 20k 行 | ≤ 15 ms | `idx_mi_scope` |
| `search`（FTS MATCH + 200 候选 + 排序） | 20k 行 | ≤ 40 ms | `zhuli_memory_fts` |
| `search`（`NONE` 档，LIKE 全表） | 20k 行 | ≤ 300 ms（可接受降级） | 无 |
| `byId` | — | ≤ 1 ms | 主键 |
| `asOfWorld`（单槽） | 20k 行 | ≤ 10 ms | `idx_mi_slot` |
| `selfCheck`（10 组） | 20k 行 | ≤ 500 ms（只在自检页跑） | — |

### 14.3 体积

| 数据量 | v16 | v17 上限 | C0.2 实测 |
|---|---|---|---|
| 2 行 | 0.26 MB | 0.40 MB | — |
| 5,000 行 | 1.12 MB | 7.0 MB | 5.14× |
| 20,000 行 | 3.72 MB | **24 MB** | 23.06 MB（5.91×） |

超上限的处置：核查新增的 5 个索引是否都被用到（`EXPLAIN QUERY PLAN`），删掉无人用的；**不许**为了体积删列（那是架构）。

### 14.4 启动

| 指标 | 门槛 | 说明 |
|---|---|---|
| 首次建库（`onCreate`） | ≤ 200 ms 额外 | 含 7 张表 + 15 索引 |
| `detectFtsMode` 首次 | ≤ 80 ms | 建临时表 + 3 触发器 + 6 次写入 |
| `detectFtsMode` 缓存命中 | ≤ 2 ms | 读 `SharedPreferences` |
| `onOpen`（`ensureAll`） | ≤ 30 ms | 都是 `IF NOT EXISTS` |
| 冷启动总增量 | ≤ 300 ms | 上述之和的上界 |

---

## 15. 各子阶段的完成条件（汇总）

| 阶段 | 完成条件（全部满足才算完成） | 证据 |
|---|---|---|
| **C1-B1** | ① 新装库有 7 表 + 15 索引、`user_version=17`、无 `zhuli_fact` 与 `zhuli_fact_v1`；② 连开 3 次幂等；③ `MemoryTextTest` 5 组全绿；④ `FactStatus` 七值解析 + 未知值兜底全绿（`T-X01`/`T-X02`/`T-X04`）；⑤ `git diff` 只含 7 个文件（4 新增 + 3 修改） | `MemorySchemaTest` 输出 + `sqlite_master` 查询 + `T-M40` |
| **C1-B2** | ① 4 档库数量闭合；② 10 组不变量 + 5 条时间断言 + **第 6 条状态断言**；③ `integrity_check=ok`；④ 5 种故障注入全回滚；⑤ 设备 5k ≤3s；⑥ **`T-M41`–`T-M45` 全绿** | `Temp/c1b/` 日志 + `MemoryMigrationTimingTest` + `MemoryMigratorTest` |
| **C1-B3** | ① 探测缓存命中（5 次打开只探 1 次）；② 坏环境（`trusted_schema=OFF`）返回 `APP_SYNC`；③ 三档检索都带全 4 个约束；④ `selfCheck` 第 1/2 条能报出人为破坏 | `MemorySchemaTest` + 设备日志 |
| **C1-B4** | ① 27 个签名不变；② 现有 15 例只改 1 处即绿；③ 47 条架构用例（×2 模式）全绿；④ `T-C57` 通过；⑤ `selfCheck` 干净库返回空；⑥ **六态投影与冲突 12 条全绿（`T-X05`–`T-X12`）** | `testDevDebugUnitTest` 报告 |
| **C1-B5** | ① 全量 ≥396 例全绿；② D1–D10 全过；③ §8.5 的 git 自检清单全过；④ 报告交付 | 报告 + 命令输出 |


---

## 16. C1-B 总体退出条件

逐条对应 C0.2 §11.4 的 9 条，并给出**证据形式**：

| # | C0.2 的条件 | 本方案的证据 | 现状 |
|---|---|---|---|
| 1 | `ftsMode` 结论给出，G 组 7 条在设备通过 | C1-A 报告 §7.1（`TRIGGER`）+ `MemoryFtsInstrumentedTest` 9/9 | **已完成**（C1-A） |
| 2 | `testDevDebugUnitTest` 全绿，总数 ≥ 331 + 65 = **396** | 测试报告 XML 的 `tests` 计数 | 未执行 |
| 3 | 无 `@Ignore`、无 `assumeTrue` 跳过 | grep 查 `@Ignore` 与 `assumeTrue` = 0 | 未执行 |
| 4 | `git diff --name-only 3597904..HEAD -- app/` ⊆ 10.1 ∪ 10.2 ∪ C1-A 的 2 个测试文件 ∪ D-6/D-7 的授权改动 | 命令输出逐行比对（共 4 个修改生产文件 + 10 个测试文件） | 未执行 |
| 5 | 真实副本迁移：数量闭合、10 组 + 5 条断言全 0、`integrity_check=ok` | `Temp/c1b/` 的 4 份日志 | 未执行 |
| 6 | `PRAGMA user_version=17`、`zhuli_fact` 不存在、`zhuli_fact_v1` 存在 | `MemoryMigratorTest` 的 `T-M09` | 未执行 |
| 7 | 不存在对 `zhuli_fact` 的写入路径（无双写） | `grep -rn "TABLE_FACT" app/src/main` 只剩 `createFactTableV15` 与 `MemoryMigrator` | 未执行 |
| 8 | 所有新增 public API 有 KDoc（说明"为什么"） | `grep -c "fun "` 与 `grep -c "/\*\*"` 粗核 + 人工 review | 未执行 |
| 9 | 设备 D1–D10 全过，D2 实测耗时写进报告 | C1-B5 报告 | 未执行 |

**额外增加 6 条（本方案 + D-6/D-7）**：

| # | 条件 | 理由 |
|---|---|---|
| 10 | 两种 `ftsMode` 下 47 条架构用例都绿（K-7） | C0.2 只要求"TRIGGER 可用"，没要求"两条路一致"，但 A-6 未关闭前这是必须的 |
| 11 | `git diff --check` 无空白错误 | 交付卫生 |
| 12 | 生产代码新增 ≤ 1,500 行（含 §8.1 的 40 行超支） | 防止"顺手重构" |
| **13** | **X-1 关闭（D-7）**：v17 库（新装与升级两条路径）都不存在 `zhuli_fact`；`onOpen` 连开 3 次不复活；旧写路径抛 `no such table` 而非静默写入；`createCoreTablesV15` 保留 entity/task/step/fact_vec 四张表与 3 个索引 | D-7 的 9 条要求与 5 项回归测试，逐条见 §4.8；证据 `T-M40`–`T-M45` |
| **14** | **X-2 关闭（D-6）**：`FactStatus` 含 6 个业务值 + `UNKNOWN`；`of()` 未知值不落 `ACTIVE`；六态投影一一对应不合并；`UNKNOWN` 非当前不可激活；序列化/反序列化/DB 兼容有测试 | D-6 的 9 条要求，逐条见 §7.12；证据 `T-X01`–`T-X08` |
| **15** | **X-3 关闭（D-6 第 7 条）**：冲突败方为 `CANDIDATE` 时 `conflicts()` 仍返回冲突对；MULTI 槽位不误报；`EXPIRED`/`REJECTED`/`DELETED`/`UNKNOWN` 不参与配对 | D-6 第 7 条；证据 `T-X09`–`T-X12` |

> 用户指令只点名"X-1、X-2 纳入退出条件"。第 15 条（X-3）是同一条决策（D-6 第 7 条）的一部分，单独列出来是为了让"冲突展示不再静默丢失"这件事有独立的验收锚点，而不是混在 X-2 的十二项里被忽略。


---

## 17. 不属于 C1-B 的后续工作

| # | 工作 | 归属 | 为什么不在 C1-B |
|---|---|---|---|
| 1 | 从通知/对话自动抽取记忆的**编排** | C2 | `MemoryExtractor` 已有抽取能力，C1 只沿用其输出；"什么时候抽、抽几条、如何合并"是编排问题 |
| 2 | 用户确认/否决的 UI 与批量流程 | C2 | C1 不改 UI（10.3） |
| 3 | 记忆注入（`MemoryContextBuilder`） | C3 | 要改 `AgentLoop`，C1 明确禁止 |
| 4 | 自动冲突裁决（"选一个"） | C2 | C1 只把冲突方放进 `CANDIDATE` 并建 `CONFLICTS_WITH`，裁决权在用户 |
| 5 | 定期整理 / 合并 / 衰减 | C4 | 需要"使用审计"（`usage` 表，C3） |
| 6 | 历史聊天回填 | C4 | 需要 `ingest` 游标表 |
| 7 | 墓碑、硬删、`usage` 表、`ingest` 游标 | C3/C4 | C0.2 §2.1 已把它们排除在 C1 之外 |
| 8 | `zhuli_fact_v1` 的清理（含备份清理） | C3 | 要先有用户二次确认的 UI |
| 9 | PROJECT / CONVERSATION 的写入 | C2/C4 | 没有可信的 id 来源（C0.2 §7.1） |
| 10 | `MemoryRepo` 的最终删除（调用方直连 Store） | C4 之后 | 现在删会把 C1 扩成"改 5 个调用方 + 重写 15 个测试" |
| 11 | 宿主 `MemoryRepository`（Room）与新表的合并 | 未排期 | 两套并存是既有决策，合并需要迁移宿主 v39 |
| 12 | `T-D32`（硬删）/ `T-A34` / `T-A35` | C3 | C0.2 已明确"C1 只留设计" |

---

## 18. 决策落定与遗留待决

### 18.1 已决定（D-6 / D-7，本轮落实）

| 决策 | 结论 | 落实位置 | 验收 |
|---|---|---|---|
| **D-6** `FactStatus` 状态投影 | 扩展为 6 个业务值 + `UNKNOWN`；`of()` 未知值不落 `ACTIVE`；`EXPIRED` / `REJECTED` / `DELETED` 语义不合并；`MemoryRanker.conflicts` 支持 `CANDIDATE`；旧 `Fact` 只做兼容投影 | §7.12（全部 9 条要求）、`MemoryTypes.kt`、`MemoryRanker.kt`、`MemoryRepo.projectStatus()` | `T-X01`–`T-X12`；退出条件 #14 / #15 |
| **D-7** `createCoreTables` 拆分 | 拆成 `createCoreTablesV15` + `createFactTableV15`；三入口职责分离；v17 起不再创建 `zhuli_fact`；其余核心表初始化一行不删 | §4.8（20 表分类 + 3 调用点 + 6 条回归）、`InboxDatabase.kt` | `T-M40`–`T-M45`；退出条件 #13 |

**决策对原方案的实质影响**（不是文字游戏，逐条可核对）：

| 项 | v1 | v2 | 原因 |
|---|---|---|---|
| 修改生产文件 | 2 个 | **4 个** | D-6 要改 `MemoryTypes.kt` 与 `MemoryRanker.kt` |
| C1-B 新增用例 | 47 | **65** | +12（D-6）+6（D-7） |
| 目标用例总数 | 378 | **396** | 同上 |
| `zhuli_fact` 在 v17 的存在性 | 计划"不存在"，但方案自相矛盾（onOpen 会重建） | **结构上保证不存在**，且有 4 条测试盯住 | D-7 |
| `EXPIRED` / `REJECTED` 的投影 | 有损合并（→ `SUPERSEDED` / `DELETED`） | **一一对应，不合并** | D-6 第 5 条 |
| 冲突展示 | 改 `MemoryRepo` 另算，`MemoryRanker` 不动 | **改 `MemoryRanker.conflicts`** | D-6 第 7 条 |
| 测试预算上限 | 2,000 行 | **2,400 行** | 用例数增加 18 条 |

### 18.2 仍然有效的 C0.2 待决（D-1–D-5）

C0.2 §12.1 的 D-1–D-5 与本文不冲突，仍然有效，需要在对应阶段前答复（不阻塞 C1-B1）：SECRET 处置的 UI 表现、旧表（`zhuli_fact_v1`）的删除时机、TASK 记忆的生命周期、物理擦除的触发方式、`AlreadyPresent` 的 UI 文案。

### 18.3 本方案不新增待决项

D-6 / D-7 落定后，C1-B **没有**新的"等你决定"事项。§19.3 的 U-1–U-11 是"未验证/解决不了"的技术事实，不是待决选择——它们不影响开工，只影响发布前的补测清单。


---

## 19. 附录

### 19.1 实施顺序一览（可直接当 checklist）

```
□ B1-0  MemoryTypes.kt（D-6 枚举 + of() + isCurrent/isActionable）
□ B1-1  MemoryItem.kt        □ B1-5  MemoryGuard.kt
□ B1-2  MemoryText.kt        □ B1-6  InboxDatabase.kt（DB_VERSION + D-7 拆分 + 三入口接入）
□ B1-3  MemorySchema.kt      □ B1-7  MemoryRanker.kt（conflicts 新语义）
□ B1-4  MemoryTextTest       □ B1-8  MemorySchemaTest / MemoryFactStatusTest（解析部分）全绿

□ B2-1  MemoryMigrator 骨架   □ B2-5  合并 + 向量
□ B2-2  逐行映射              □ B2-6  FTS + 唯一索引 + 改名
□ B2-3  来源与证据            □ B2-7  数量闭合 + 6 条断言
□ B2-4  替代链 + 断链         □ B2-8  MemoryMigratorTest 13 + 5 条（T-M41..T-M45）

□ B3-1  FtsModeCache          □ B3-4  attach 接入
□ B3-2  detectFtsMode 七步    □ B3-5  负向验证（trusted_schema=OFF）
□ B3-3  createFts/syncFts

□ B4-1  MemoryPolicy          □ B4-6  MemoryRepo 投影重写 + projectStatus()
□ B4-2  insert                □ B4-7  MemoryTestFixture + scope 用例
□ B4-3  correct/confirm/resolve□ B4-8  selfCheck
□ B4-4  softDelete/restore    □ B4-9  conflictFacts + 六态投影用例（T-X05..T-X12）
□ B4-5  查询 API（7 个）

□ B5-1  全量单测 ≥396         □ B5-5  git 9 条自检
□ B5-2  D1–D4/D6/D7/D9/D10    □ B5-6  C1-B 报告
□ B5-3  D8（B1 12 场景）
□ B5-4  D5（飞行模式）
```


### 19.2 关键命令

```bash
# 全量单测（不需要设备）
cd zhuli/lastchat
JAVA_HOME="C:/Program Files/Android/Android Studio/jbr" ./gradlew --no-daemon \
  :app:testDevDebugUnitTest -x :app:buildWebUi -x :speech:downloadSherpaOnnxAar

# 设备测试（迁移耗时 + 不变量）
./gradlew --no-daemon :app:connectedDevDebugAndroidTest \
  -Pandroid.testInstrumentationRunnerArguments.class=com.zhuli.memory.MemoryMigrationTimingTest

# 迁移前后的表结构对照
adb -s emulator-5554 shell "run-as lastchat.rikkafork.cocolal.dev \
  sqlite3 databases/zhuli_inbox.db 'SELECT name,type FROM sqlite_master ORDER BY type,name'"

# 双写检查（应为 0）
grep -rn "INSERT INTO zhuli_fact\|UPDATE zhuli_fact " app/src/main || echo "0 处"

# D-6：禁止 fallback 到 ACTIVE（应为 0）
grep -rn "?: ACTIVE" app/src/main/java/com/zhuli/memory || echo "0 处"
grep -rn "FactStatus.valueOf" app/src/main || echo "0 处"

# D-7：旧建表函数名必须彻底消失（应为 0）
grep -rn "fun createCoreTables\b" app/src/main || echo "0 处"

# D-7：迁移后旧表不该存在（设备上，期望只返回 zhuli_fact_v1）
adb -s emulator-5554 shell "run-as lastchat.rikkafork.cocolal.dev \
  sqlite3 databases/zhuli_inbox.db \"SELECT name FROM sqlite_master WHERE name IN ('zhuli_fact','zhuli_fact_v1')\""

# 敏感日志检查（应无 content/subject/value）
grep -n "quarantined" app/src/main/java/com/zhuli/memory/MemoryMigrator.kt
```

### 19.3 仍未解决的问题（C1-B 也解决不了的）

| # | 问题 | 为什么解决不了 | 谁来解决 |
|---|---|---|---|
| U-1 | `trusted_schema` 在 API 28/29 与 34+ 的默认值是否一致 | 手上没有 API 28/29 设备 | 有设备时补测；`detectFtsMode` 已能兜住 |
| U-2 | Robolectric 为何复现不出 `trusted_schema=OFF` 的失败 | 属 Robolectric/AOSP 构建差异，不影响生产 | 记录即可 |
| U-3 | 迁移期间的通知回调是否会被写锁阻塞到丢消息 | 需要真机 + 人为制造长迁移 | C1-B5 的 D1 手工步骤 |
| U-4 | API 28（SQLite 3.22）上的 DDL 兼容性 | 无设备 | 发布前用云真机补测 |
| U-5 | `PRAGMA defensive` 读不出值（C1-A §6 A-6） | SQLite 语义 | 只能"试"，已写进探测 |
| U-6 | 20k 行迁移在低端真机（非模拟器）的耗时 | 只有模拟器 | 云真机补测 |
| U-7 | `Fact.slot` 不含 `qualifier_key`，C2 引入 qualifier 后冲突配对可能误报 | C1 的 `qualifier_key` 恒为 `''`，无差异；改 `Fact` 会破兼容层 | C2 改用 `slot_hash` 或给 `Fact` 加字段 |
| U-8 | `FactStatus` 的六态区分只存在于数据层，UI 不显示状态标签 | C1 禁止改 UI（§7.11） | C2 的记忆页改版；在那之前用户看不出"过期"与"被替代" |
| U-9 | 回填产生的大量 `CANDIDATE` 会进入 `conflicts()`，"需要裁决"可能一次显示几十上百组 | C1 的 UI 只能列表展示，没有批量操作（§7.11） | C2 的批量裁决 UI + 按证据强度排序 |
| U-10 | `createFactTableV15` 的 DDL 与 v16 现状是两份代码，未来若改 v16 会漂移 | 迁移源表结构必须逐字一致 | `T-M02` 兜住；review 时逐列对照 |
| U-11 | `conflicts()` 返回 3 组以上时 UI 的排序未定义 | 只影响展示顺序，不影响正确性 | C2 的冲突面板设计 |

### 19.4 术语

| 词 | 含义 |
|---|---|
| `LB` | 世界时间下界：`CASE WHEN valid_from>0 AND time_confidence<>'NONE' THEN valid_from ELSE observed_at END` |
| `UB` | 世界时间上界：裸 `valid_until`（**不设**置信度门槛） |
| `CURRENT` | 现在什么为真：`status='ACTIVE' AND LB<=now AND (UB=0 OR now<UB)` |
| `WORLD_ASOF` | 某时刻世界上什么为真（半开区间 + `CORRECTS` 排除） |
| `BELIEF_ASOF` | 某时刻我们以为什么为真（`observed_at`/`invalidated_at`） |
| `ftsMode` | `TRIGGER` / `APP_SYNC` / `NONE`，运行时探测 |
| `pre_delete_status` | 软删前的状态，恢复回它（不一律回 ACTIVE） |
| `boundary_unknown` | 单条：起点靠推断/不可信/终点未知 |
| `ambiguous` | 整次结果：同槽多值且至少一条边界未知 |
| `MemoryStatus` | 权威六态：`ACTIVE` / `CANDIDATE` / `SUPERSEDED` / `EXPIRED` / `REJECTED` / `DELETED` |
| `FactStatus` | 兼容七值：上述六态的业务映射 + `UNKNOWN`；`CANDIDATE` 映射为 `UNCONFIRMED` |
| `isActionable` | `FactStatus` 的派生属性：`ACTIVE` 或 `UNCONFIRMED`，即"可以参与冲突配对" |
| `projectStatus()` | `MemoryRepo` 私有的权威→兼容映射函数；**禁止**用 `FactStatus.of()` 代替 |
| `createCoreTablesV15` | v15 核心表（entity/task/step/fact_vec），v17 起仍创建 |
| `createFactTableV15` | 旧 `zhuli_fact` 表，**只允许** `onUpgrade` 且 `oldVersion<15` 时调用 |

---

**本文件到此结束。它是实施方案，不是实施结果。C1-B 尚未开始，生产代码未改动，`DB_VERSION` 仍为 16。**

> v2 的边界：只落实 D-6 / D-7 两项已决事项，未新增任何架构设计，未修改任何生产代码。§19.1 的 checklist 已包含 D-6/D-7 的步骤；开工前请以本文件 + 修订记录为准，C0.2 只在本文未覆盖处作为依据。
