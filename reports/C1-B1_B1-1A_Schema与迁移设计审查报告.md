# C1-B1 / B1-1A　Schema 与数据库迁移设计审查报告

> 阶段：C1-B1 / B1-1A（只读审查、设计核对、迁移方案输出）
> 时间：2026-11-28
> 范围声明：**未修改任何生产 Kotlin 代码、未修改 `InboxDatabase.kt` / `MemoryRepo.kt`、未新建 `MemorySchema.kt` / `MemoryMigrator.kt`、未改 `DB_VERSION`、未改 Gradle、未增删测试、未执行任何迁移、未改动真实 v16 副本、未执行任何 Git 写操作。**
> 数据库副本：`Temp/c01/zhuli_inbox_v16_real.db`，以 `mode=ro&immutable=1` 只读打开；核验前后文件未变。

---

## 0. 结论摘要

| 维度 | 结果 | 说明 |
|---|---|---|
| 权威材料完整性 | **PASS** | C0.2、v4.2.1、B1-0、差异审计、A 类两份、A6/A7 证据、真实副本全部在位，版本可裁定（§1.3） |
| 当前 v16 结构盘点 | **PASS** | 代码与真实库双向核对一致，无隐藏表/触发器（§2） |
| 七张目标表 DDL | **PASS（有 7 处文档列数错误）** | 表名、列名、类型、默认值、约束逐项核对通过；§4.8.1 的描述性列数有 7 处与实测不符（D-1） |
| 15 个索引 | **PASS** | 15 条 `CREATE [UNIQUE] INDEX` 与 C0.2 逐字一致，3 个唯一索引确为 `CREATE UNIQUE INDEX`，无同名不同定义（§4） |
| v16→v17 迁移映射 | **PASS（1 项语义冲突待裁定）** | 16 列映射闭合、行数恒等式成立；`ref_table='zhuli_fact'` 与 `ref_id=inbox id` 语义冲突（§5.4 / B-2） |
| 三条路径（新装/升级/onOpen） | **BLOCKED** | `ensureAll` 只建 FTS 对象、不建 6 张基表与索引，且签名需 4 个参数，`onOpen(db)` 单参调用不可成立（§6.4 / B-1） |
| 备份与事务边界 | **PASS（实现须改）** | 设计已明确"备份是前置条件、WAL 直接拒绝"；现有实现是"尽力而为、失败继续"，两者冲突已定位（§7） |
| 失败与回滚 | **PASS（1 项边界待定）** | 事务回滚可保证库停在 v16；`invalidated_at` 在 `valid_to=0` 时会产生 0，与不变量 #4 冲突（§8 / B-3） |
| 真实副本身份 | **PASS** | 274,432 B、SHA-256 `5936d12e…f75660` 完全匹配（§9.1） |

**总裁定：BLOCKED**（不是 DDL 设计错，而是 3 个必须在编码前定稿的决策/规格缺口）。

| 编号 | 阻断项 | 性质 | 影响 |
|---|---|---|---|
| **B-1** | `onOpen` 的建表入口未定义：`ensureAll` 不含基表/索引，且需要 `prefs/cache/requested` 三参，`MemorySchema.ensureAll(db)` 无法编译 | 规格矛盾 | 直接决定 `InboxDatabase.onOpen` 怎么写；写错会让 v17 库缺表或旧表复活 |
| **B-2** | `zhuli_memory_source.ref_table='zhuli_fact'` 与 `ref_id=source_id`（实际是 inbox 行号）语义不符，且 `zhuli_fact` 迁移后不存在 | 语义冲突 | 写进唯一索引 `idx_ms_dedupe` 与 `independent_key`，事后修改要重算全表键 |
| **B-3** | `invalidated_at = valid_to ?: updated_at` 在 `valid_to=0` 时得 0，违反不变量 #4 | 规则边界 | 决定脏数据是中止迁移还是修数据；不能靠"理论上不会有" |

另有 **8 项文档级修正**（D-1…D-8，不阻断编码，但必须在同一轮文档修改中改掉）。其中 4 项最容易导致实现者照抄错误：`§4.8.1` 七处列数（D-1）、`C0.2 §8.10` 的 WAL checkpoint 结论已被 v4.2.1 §5.12 取代（D-2）、`C0.2 §8.1 #28` 的 sensitivity 只看 content（D-3）、`C0.2 §8.9` 的"5 个普通索引 / 只建 1 个唯一索引"（D-4）。

---

## 1. 基线与材料版本

### 1.1 仓库事实（本次实测）

| 项 | 结果 |
|---|---|
| 仓库根 | `C:/Users/a/Desktop/zhuli`（`git rev-parse --show-toplevel` 一致） |
| 分支 | `master` |
| HEAD | `08b17c19f6f41cc80653d4da1949634cafd77092`（= 任务书基线） |
| `origin/master` | `08b17c19f6f41cc80653d4da1949634cafd77092`（本次同步后已一致，领先/落后 0/0） |
| 工作区 | 6 份未跟踪报告 + `Temp/`，无已跟踪文件改动；`git diff --stat HEAD` 为空 |

> **对 B1-0 报告 §6 的纠正**：B1-0 与 A 类阶段二报告把远端 `c44314b9` 描述为"无父节点的孤儿快照、与本地不同源"，该表述已被 `C1-B1_本地与远端基线差异审计报告.md` §0 用 `git merge-base` 证伪——`c44314b9` 是本地 `08b17c1` 的**祖先**，本地领先 16 个提交。2026-11-28 的快进推送完成后，两边 HEAD 相同，B1-0 §6 的"待确认项"已自然消解。本报告以本地工作区文件为唯一实现基线。

### 1.2 材料清单与采用结论

| # | 材料 | 路径 | 规模 | 版本/标识 | 采用 |
|---|---|---|---|---|---|
| 1 | C0.2 最终架构 | `zhuli/lastchat/docs/批次C0.2_记忆系统架构设计最终版.md` | 179,685 B / 2,475 行 | 最终版（§0 声明） | **数据模型唯一权威** |
| 2 | C1-B 实施方案 | `zhuli/lastchat/docs/批次C1-B_记忆系统基础设施实施方案.md` | 421,560 B / 5,223 行 | **v4.2.1**（§20.1） | **工程落地唯一权威** |
| 3 | v4.2→v4.2.1 修订 | `.../批次C1-B_方案v4.2到v4.2.1修订记录.md` | 5,977 B | v4.2.1 | 变更依据 |
| 4 | v4.1→v4.2 / v4→v4.1 / v3→v4 修订 | 同目录三份 | 6,674 / 7,601 / 13,173 B | 对应版本 | 变更依据 |
| 5 | B1-0 基线核验 | `C1-B1_开工基线核验报告.md` | 8,453 B | 2026-11-27 | 采用（§6 结论已被 §1.1 纠正） |
| 6 | 基线差异审计 | `C1-B1_本地与远端基线差异审计报告.md` | 25,155 B | 2026-11-27 | 采用 |
| 7 | 差异原始清单 | `C1-B1_基线差异原始清单.md` | 178,167 B | 2026-11-27 | 采用（只作证据） |
| 8 | A 类阶段二 | `C1-B_A类核对第二阶段报告.md` | 43,920 B | 2026-11-27 | 采用（A-3/A-7 PASS） |
| 9 | A 类门槛修订 | `C1-B_A类核对门槛修订与证据补齐报告.md` | 31,737 B | 2026-11-27 | 采用（七项门槛 PASS） |
| 10 | A6/A7 原始证据 | `C1-B_A6_A7原始数据库证据.md` | 7,218 B | 2026-11-27 | 采用（与本次实测逐项一致） |
| 11 | 真实 v16 副本 | `Temp/c01/zhuli_inbox_v16_real.db` | 274,432 B | SHA-256 `5936d12e…f75660` | **采用（只读）** |
| 12 | 边界样本库 | `Temp/c01/v16_edge.db` | 274,432 B | v16 / 11 行 fact | 采用（仅验证用） |

**明确不采用**：C0（98,653 B）、C0.1（122,719 B）作为历史版本，仅在 C0.2 引用其差异时旁证；v2/v3/v4/v4.1/v4.2 的正文不参与裁定，只读其"修订记录"确认变更理由。

### 1.3 版本冲突的裁定规则

三份文档在同一处给出不同写法时，按以下顺序裁定，本报告后续全部遵循：

1. **v4.2.1 明确写"取代/撤销/修正 C0.2 某处"的** → 以 v4.2.1 为准（例：WAL 处置、sensitivity 检查范围、索引数量口径）；
2. **v4.2.1 未提及、C0.2 有 DDL 的** → 以 C0.2 为准（表结构、索引定义只有 C0.2 给全文；v4.2.1 §4.3 明确"本文不复制 DDL，唯一真相是 `MemorySchema.kt` 常量"）；
3. **两者都沉默的** → 记为"待裁定"（B-1/B-2/B-3 属于此类）。

---

## 2. 当前 v16 数据库结构（代码 + 真实库双向核对）

### 2.1 代码事实

| 项 | 值 | 位置 |
|---|---|---|
| `DB_VERSION` | **16** | `InboxDatabase.kt:50` |
| 数据库文件名 | `zhuli_inbox.db` | `:32` |
| `onCreate` | 建 `inbox` 最新 schema → FTS5(trigram, 可失败) → 4 个 inbox 索引 → 6 张业务表 → `createCoreTables` → `createV16Tables` | `:269-319` |
| `onOpen` | `ensureColumns` → `createSourceTable` → `createCoreTables` → `createV16Tables` → 孤儿向量清理（每进程一次）→ FTS 同步（每进程一次） | `:332-348` |
| `onUpgrade` | 备份（尽力）→ 嵌套 `beginTransaction`（尽力）→ `upgradeSteps` → `setTransactionSuccessful` | `:673-692` |
| `upgradeSteps` | v1→v16 共 15 个 `if (oldVersion < N)` 分支 | `:715-794` |
| `createCoreTables` | `zhuli_entity` / `zhuli_fact`(+3 索引) / `zhuli_agent_task`(+2) / `zhuli_agent_step`(+1) / `zhuli_fact_vec` | `:501-594` |
| `createV16Tables` | `zhuli_vec_queue`(+1) / `zhuli_tool_exec`(+2，含 1 唯一) / `ALTER agent_task ADD local_only` | `:602-636` |
| 备份 | `backupBeforeMigration`：直接 `copyTo`，`catch` 后**继续迁移** | `:698-712` |

**调用关系（实测 grep，共 3 处 `createCoreTables`）**：

```
onCreate  :310-318  createChatTable / createReminderTable / createLedgerTable / createCloudLogTable
                    / createSkillTable / createEmbeddingTable / createSourceTable
                    / createCoreTables(:317) / createV16Tables(:318)
onOpen    :338-340  createSourceTable / createCoreTables(:339) / createV16Tables(:340)
onUpgrade :787      createCoreTables        （oldVersion<15 分支）
          :792      createV16Tables         （oldVersion<16 分支）
```

> `onOpen` **不**补建 `chat_log` / `reminders` / `ledger` / `cloud_log` / `skills` / `embeddings`——自愈覆盖本来就不对称。这是既有事实，记录在 §6.4（E-4），不属 v17 引入。

### 2.2 真实库实测结构（`Temp/c01/zhuli_inbox_v16_real.db`）

| 项 | 值 |
|---|---|
| 字节数 / SHA-256 | 274,432 / `5936d12efcb1bd99f10aea15fa5d24ecd56d294825156ce630fbbec4b9f75660` |
| `user_version` / `journal_mode` / `page_size` | 16 / `delete` / 4096 |
| `integrity_check` | ok |
| 表数 | 17（含 `android_metadata`、`sqlite_sequence`） |
| 触发器 | **0**（`inbox_fts` 在本机不存在，符合该 ROM FTS5 不可用的既有结论） |
| AUTOINCREMENT 表 | **9**：`chat_log` `cloud_log` `inbox` `ledger` `reminders` `skills` `zhuli_agent_step` `zhuli_entity` `zhuli_fact` |
| `sqlite_sequence` | 4 行（`cloud_log`=6 / `zhuli_agent_step`=186 / `zhuli_entity`=2 / `zhuli_fact`=2），**0 重复** |

各表实测列数 / 行数（`PRAGMA table_info` + `COUNT(*)`）：

| 表 | 列数 | 行数 | 索引数 | 备注 |
|---|---:|---:|---:|---|
| `inbox` | 17 | 0 | 4 | 4 个 `idx_inbox_*` |
| `chat_log` | 4 | 0 | 1 | |
| `reminders` | 13 | 0 | 2 | 1 唯一（`inbox_id`） |
| `ledger` | 7 | 0 | 1 | 1 唯一（`inbox_id`） |
| `cloud_log` | 8 | 6 | 1 | |
| `skills` | 7 | 0 | 1 | 1 唯一（`kind,pattern`） |
| `source_rule` | 3 | 0 | 1 | PK 自动索引 |
| `embeddings` | 3 | 0 | 0 | PK `inbox_id` |
| `zhuli_entity` | 7 | 0 | 1 | `UNIQUE(kind,norm)` 自动索引 |
| `zhuli_fact` | 16 | **2** | 3 | `ACTIVE`=1 / `SUPERSEDED`=1；`source_type` 全 `USER`；`kind` 全 `OTHER` |
| `zhuli_fact_vec` | 4 | **1** | 0 | 1 条 `hash-2gram-v1\|256` / 1024 B |
| `zhuli_vec_queue` | 4 | 0 | 1 | |
| `zhuli_agent_task` | **14** | 28 | 3 | 13 列 + v16 补的 `local_only` |
| `zhuli_agent_step` | 13 | 186 | 2 | 含 `UNIQUE(task_id,seq)` |
| `zhuli_tool_exec` | **11** | 51 | 3 | 含 1 唯一 `idx_tx_key` |

### 2.3 v15 新增表 DDL（现状原文，迁移源表以它为准）

`createCoreTables` 实际执行的 `zhuli_fact` DDL（`InboxDatabase.kt:516-540`）：

```sql
CREATE TABLE IF NOT EXISTS zhuli_fact (
    id            INTEGER PRIMARY KEY AUTOINCREMENT,
    subject       TEXT NOT NULL,
    predicate     TEXT NOT NULL,
    value         TEXT NOT NULL,
    kind          TEXT NOT NULL DEFAULT 'OTHER',
    confidence    REAL NOT NULL DEFAULT 0.6,
    source_type   TEXT NOT NULL DEFAULT 'INBOX',
    source_id     INTEGER NOT NULL DEFAULT 0,
    source_text   TEXT NOT NULL DEFAULT '',
    observed_at   INTEGER NOT NULL DEFAULT 0,
    valid_from    INTEGER NOT NULL DEFAULT 0,
    valid_to      INTEGER NOT NULL DEFAULT 0,
    status        TEXT NOT NULL DEFAULT 'ACTIVE',
    superseded_by INTEGER NOT NULL DEFAULT 0,
    created_at    INTEGER NOT NULL,
    updated_at    INTEGER NOT NULL
);
CREATE INDEX IF NOT EXISTS idx_fact_slot   ON zhuli_fact(subject, predicate);
CREATE INDEX IF NOT EXISTS idx_fact_source ON zhuli_fact(source_type, source_id);
CREATE INDEX IF NOT EXISTS idx_fact_status ON zhuli_fact(status, observed_at);
```

`zhuli_entity`：7 列 + `UNIQUE(kind,norm)`；`zhuli_agent_task`：13 列 + v16 `local_only`；`zhuli_agent_step`：13 列 + `UNIQUE(task_id,seq)`；`zhuli_fact_vec`：`fact_id` 主键 + `space/vec/at`。

`createV16Tables`（`:602-636`）：`zhuli_vec_queue(fact_id PK, attempts, last_error, at)` + `idx_vecq_at`；`zhuli_tool_exec(execution_id PK, task_id, tool, args, idem_key, state, result, error, created_at, updated_at, attempts)` + `idx_tx_task` + `UNIQUE idx_tx_key`；`ALTER TABLE zhuli_agent_task ADD COLUMN local_only INTEGER NOT NULL DEFAULT 0`。

### 2.4 旧记忆表的索引、约束、触发器

| 对象 | 定义 | 迁移后归属 |
|---|---|---|
| `idx_fact_slot` | `zhuli_fact(subject, predicate)` | 随 `ALTER TABLE RENAME` 挂到 `zhuli_fact_v1`，**名字不变** |
| `idx_fact_source` | `zhuli_fact(source_type, source_id)` | 同上 |
| `idx_fact_status` | `zhuli_fact(status, observed_at)` | 同上 |
| `zhuli_entity` 自动索引 | `sqlite_autoindex_zhuli_entity_1`（`UNIQUE(kind,norm)`） | 留在 `zhuli_entity`（表不迁） |
| `zhuli_fact_vec` / `zhuli_vec_queue` | 主键自动索引（rowid 别名） | 结构不变 |
| 触发器 | **无**（v16 全库 0 个） | 不涉及 |

> 结论：v16 没有任何"旧记忆触发器"需要退役；`inbox_fts` 的 3 个触发器是收件箱的，与记忆无关，且本机根本没建出来。

### 2.5 `MemoryRepo` 对旧表的读写入口（27 个公开成员逐个落表）

| 公开成员 | 读/写 | 涉及旧表 |
|---|---|---|
| `onIndexRequested` | 回调 | — |
| `ingest` | 写 | `zhuli_fact` / `zhuli_entity` / `zhuli_fact_vec` / `zhuli_vec_queue` |
| `correct` | 写 | `zhuli_fact` / `zhuli_vec_queue` |
| `deleteFact` | 写 | `zhuli_fact` / `zhuli_fact_vec` / `zhuli_vec_queue` |
| `byId` / `unconfirmed` / `activeOf` / `active` / `activeCount` / `history` / `search` / `conflicts` / `vectorText` | 读 | `zhuli_fact` |
| `confirmFact` / `resolveConflict` | 写 | `zhuli_fact` / `zhuli_fact_vec` / `zhuli_vec_queue` |
| `putVector` / `markVectorIndexed` / `markVectorFailed` / `dropVector` / `pendingVectorFacts` / `pendingVectorCount` / `waitingVectorCount` / `failedVectorCount` / `indexedSpaces` / `vectorCount` / `enqueueAllActive` | 读写 | `zhuli_fact_vec` / `zhuli_vec_queue`（`pendingVectorFacts` 额外 JOIN `zhuli_fact`） |
| `hybridSearch` | 读 | `zhuli_fact` / `zhuli_fact_vec` |

**27 个成员签名与 B1-0 报告 §2 的表完全一致，本次复核无变化。** 重写后必须保留的向量 7 方法 + `enqueueVector` 属于 B 组表，不在迁移范围（v4.2.1 §4.8.4）。

### 2.6 与方案描述的偏差（实测驱动）

`DB_VERSION`、`onCreate`/`onOpen`/`onUpgrade` 结构、3 个 `createCoreTables` 调用点行号（317/339/787）、`backupBeforeMigration` 位置（698）与 v4.2.1 §4.8.2 / §5.12 的描述**逐项吻合**。唯一偏差在 `§4.8.1` 的列数，见 D-1。

---

## 3. 七张目标表完整 DDL

### 3.1 口径

C0.2 §2.1 明确："C1 建 7 张表（含 FTS）"，`zhuli_memory_tombstone` 故意不在 C1 建（硬删入口在 C3）。因此七张 = 6 张普通表 + 1 张 FTS4 虚拟表：

| # | 表 | 列数 | 设计来源 |
|---|---|---:|---|
| 1 | `zhuli_memory_item` | 40 | C0.2 §2.2（L141-204）；v4.2.1 §4.2/§4.5 |
| 2 | `zhuli_memory_source` | 12 | C0.2 §2.3（L241-262） |
| 3 | `zhuli_memory_evidence` | 6 | C0.2 §2.4（L275-288） |
| 4 | `zhuli_memory_relation` | 6 | C0.2 §2.5（L305-315） |
| 5 | `zhuli_memory_revision` | 10 | C0.2 §2.5（L317-331） |
| 6 | `zhuli_memory_entity` | 3 | C0.2 §2.5（L333-339） |
| 7 | `zhuli_memory_fts` | 1（+5 影子表） | C0.2 §2.6（L352-371） |

**全部 7 张表无 `FOREIGN KEY`**（C0.2 有意为之；v4.2.1 §4.4 说明：`ON DELETE CASCADE` 会跳过 revision 记录，且 Android 默认关闭 `foreign_keys`）。一致性靠应用层顺序 + `selfCheck`。

### 3.2 `zhuli_memory_item`（40 列）

```sql
CREATE TABLE IF NOT EXISTS zhuli_memory_item (
  id                 INTEGER PRIMARY KEY AUTOINCREMENT,
  memory_type        TEXT    NOT NULL,
  subject            TEXT    NOT NULL,
  subject_key        TEXT    NOT NULL,
  predicate          TEXT    NOT NULL,
  qualifier_key      TEXT    NOT NULL DEFAULT '',
  value              TEXT    NOT NULL,
  content            TEXT    NOT NULL,
  normalized_content TEXT    NOT NULL,
  dedupe_key         TEXT    NOT NULL,
  slot_hash          TEXT    NOT NULL DEFAULT '',
  search_text        TEXT    NOT NULL DEFAULT '',
  status             TEXT    NOT NULL,
  cardinality        TEXT    NOT NULL DEFAULT 'SINGLE',
  confidence         REAL    NOT NULL DEFAULT 0.5,
  confidence_basis   TEXT    NOT NULL,
  source_id          INTEGER NOT NULL DEFAULT 0,
  scope_type         TEXT    NOT NULL,
  scope_id           TEXT    NOT NULL DEFAULT '',
  sensitivity        INTEGER NOT NULL DEFAULT 0,
  event_start        INTEGER NOT NULL DEFAULT 0,
  event_end          INTEGER NOT NULL DEFAULT 0,
  valid_from         INTEGER NOT NULL DEFAULT 0,
  valid_until        INTEGER NOT NULL DEFAULT 0,
  observed_at        INTEGER NOT NULL,
  recorded_at        INTEGER NOT NULL,
  updated_at         INTEGER NOT NULL,
  last_confirmed_at  INTEGER NOT NULL DEFAULT 0,
  invalidated_at     INTEGER NOT NULL DEFAULT 0,
  time_confidence    TEXT    NOT NULL DEFAULT 'NONE',
  superseded_by      INTEGER NOT NULL DEFAULT 0,
  revision           INTEGER NOT NULL DEFAULT 1,
  extractor          TEXT    NOT NULL DEFAULT '',
  legacy_kind        TEXT    NOT NULL DEFAULT '',
  payload_json       TEXT    NOT NULL DEFAULT '',
  deleted_at         INTEGER NOT NULL DEFAULT 0,
  pre_delete_status  TEXT    NOT NULL DEFAULT '',
  times_reinforced   INTEGER NOT NULL DEFAULT 0,
  times_retrieved    INTEGER NOT NULL DEFAULT 0,
  last_used_at       INTEGER NOT NULL DEFAULT 0
);
```

| 字段 | 类型 | NOT NULL | 默认值 | 主键 | 外键 | 唯一约束 | 语义来源 |
|---|---|---|---|---|---|---|---|
| `id` | INTEGER | 是（PK 隐含） | — | **PK AUTOINCREMENT** | 无 | — | §2.2 L142 |
| `memory_type` | TEXT | 是 | — | — | 无 | — | `FACT\|EVENT\|STATE\|PREFERENCE\|OBSERVATION` |
| `subject` / `subject_key` | TEXT | 是 | — | — | 无 | — | 可读主体 / `norm(subject)` |
| `predicate` | TEXT | 是 | — | — | 无 | — | 槽位字段名 |
| `qualifier_key` | TEXT | 是 | `''` | — | 无 | — | 语境（迁移一律空） |
| `value` | TEXT | 是 | — | — | 无 | — | 规范化值 |
| `content` / `normalized_content` | TEXT | 是 | — | — | 无 | — | `"主体｜谓词｜值"` 及其 norm |
| `dedupe_key` | TEXT | 是 | — | — | 无 | **由 `idx_mi_dedupe` 部分唯一承担** | 32 hex，算法见 §2.2 |
| `slot_hash` | TEXT | 是 | `''` | — | 无 | — | 与 value 无关（C3 墓碑用） |
| `search_text` | TEXT | 是 | `''` | — | 无 | — | 2 字滑窗，写入时算 |
| `status` | TEXT | 是 | — | — | 无 | — | 六态（无 CHECK 约束，见 D-5） |
| `cardinality` | TEXT | 是 | `'SINGLE'` | — | 无 | — | `SINGLE\|MULTI` |
| `confidence` | REAL | 是 | `0.5` | — | 无 | — | 迁移按 basis clamp |
| `confidence_basis` | TEXT | 是 | — | — | 无 | — | 六种 basis |
| `source_id` | INTEGER | 是 | `0` | — | 无（逻辑引用） | — | PRIMARY evidence 的冗余投影 |
| `scope_type` / `scope_id` | TEXT | 是 | — / `''` | — | 无 | — | 迁移恒 `GLOBAL`/`''` |
| `sensitivity` | INTEGER | 是 | `0` | — | 无 | — | 0 普通 / 1 敏感；2=SECRET **不落库** |
| `event_start` / `event_end` | INTEGER | 是 | `0` | — | 无 | — | 旧表无，迁移恒 0 |
| `valid_from` / `valid_until` | INTEGER | 是 | `0` | — | 无 | — | 世界时间；迁移 `valid_until` 恒 0 |
| `observed_at` / `recorded_at` / `updated_at` | INTEGER | 是 | — | — | 无 | — | 毫秒，无单位转换 |
| `last_confirmed_at` | INTEGER | 是 | `0` | — | 无 | — | `USER_CONFIRMED` 时取 `updated_at` |
| `invalidated_at` | INTEGER | 是 | `0` | — | 无 | — | 信念时间（旧 `valid_to` 的去处） |
| `time_confidence` | TEXT | 是 | `'NONE'` | — | 无 | — | 迁移恒 `NONE` |
| `superseded_by` | INTEGER | 是 | `0` | — | 无（逻辑引用） | — | id 保真后可直接拷 |
| `revision` | INTEGER | 是 | `1` | — | 无 | — | 迁移恒 1 |
| `extractor` | TEXT | 是 | `''` | — | 无 | — | 迁移恒 `import:legacy` |
| `legacy_kind` | TEXT | 是 | `''` | — | 无 | — | `FactKind.name` 保真 |
| `payload_json` | TEXT | 是 | `''` | — | 无 | — | `{"migrated_from":N}` |
| `deleted_at` | INTEGER | 是 | `0` | — | 无 | — | `DELETED` 时取 `updated_at` |
| `pre_delete_status` | TEXT | 是 | `''` | — | 无 | — | 迁移**不写**（禁止 4） |
| `times_reinforced` | INTEGER | 是 | `0` | — | 无 | — | 派生，迁移第 5 步重算 |
| `times_retrieved` / `last_used_at` | INTEGER | 是 | `0` | — | 无 | — | 迁移恒 0 |

**无外键、无表内 CHECK、唯一性只由 `idx_mi_dedupe` 承担**——这是与 C0.2 逐字一致的结论，不是遗漏。

### 3.3 `zhuli_memory_source`（12 列）

```sql
CREATE TABLE IF NOT EXISTS zhuli_memory_source (
  id              INTEGER PRIMARY KEY AUTOINCREMENT,
  source_type     TEXT    NOT NULL,
  ref_table       TEXT    NOT NULL DEFAULT '',
  ref_id          TEXT    NOT NULL DEFAULT '',
  ref_sub_id      TEXT    NOT NULL DEFAULT '',
  independent_key TEXT    NOT NULL,
  excerpt         TEXT    NOT NULL DEFAULT '',
  content_hash    TEXT    NOT NULL DEFAULT '',
  trust           INTEGER NOT NULL DEFAULT 1,
  captured_at     INTEGER NOT NULL,
  created_at      INTEGER NOT NULL,
  purged_at       INTEGER NOT NULL DEFAULT 0
);
```

| 字段 | 类型 | NOT NULL | 默认值 | 主键 | 外键 | 唯一约束 |
|---|---|---|---|---|---|---|
| `id` | INTEGER | 是 | — | **PK AUTOINCREMENT** | 无 | — |
| `source_type` | TEXT | 是 | — | — | 无 | 参与 `idx_ms_dedupe` |
| `ref_table` / `ref_id` / `ref_sub_id` | TEXT | 是 | `''` | — | 无 | 同上（**语义见 B-2**） |
| `independent_key` | TEXT | 是 | — | — | 无 | 无（C0.2 删了 `idx_ms_indep`） |
| `excerpt` | TEXT | 是 | `''` | — | 无 | —（≤120 字，已 redact） |
| `content_hash` | TEXT | 是 | `''` | — | 无 | 参与 `idx_ms_dedupe` |
| `trust` | INTEGER | 是 | `1` | — | 无 | — |
| `captured_at` / `created_at` | INTEGER | 是 | — | — | 无 | — |
| `purged_at` | INTEGER | 是 | `0` | — | 无 | — |

### 3.4 `zhuli_memory_evidence`（6 列）

```sql
CREATE TABLE IF NOT EXISTS zhuli_memory_evidence (
  memory_id     INTEGER NOT NULL,
  source_id     INTEGER NOT NULL,
  role          TEXT    NOT NULL DEFAULT 'SUPPORTING',
  first_seen_at INTEGER NOT NULL,
  last_seen_at  INTEGER NOT NULL,
  seen_count    INTEGER NOT NULL DEFAULT 1,
  PRIMARY KEY(memory_id, source_id)
);
```

| 字段 | 类型 | NOT NULL | 默认值 | 主键 | 外键 | 唯一约束 |
|---|---|---|---|---|---|---|
| `memory_id` / `source_id` | INTEGER | 是 | — | **复合 PK** | 无（逻辑引用） | 复合 PK 自动索引 |
| `role` | TEXT | 是 | `'SUPPORTING'` | — | 无 | 由 `idx_mev_primary` 保证"一条记忆至多一个 PRIMARY" |
| `first_seen_at` / `last_seen_at` | INTEGER | 是 | — | — | 无 | — |
| `seen_count` | INTEGER | 是 | `1` | — | 无 | — |

### 3.5 `zhuli_memory_relation`（6 列）

```sql
CREATE TABLE IF NOT EXISTS zhuli_memory_relation (
  id         INTEGER PRIMARY KEY AUTOINCREMENT,
  from_id    INTEGER NOT NULL,
  to_id      INTEGER NOT NULL,
  relation   TEXT    NOT NULL,
  note       TEXT    NOT NULL DEFAULT '',
  created_at INTEGER NOT NULL,
  UNIQUE(from_id, to_id, relation)
);
```

| 字段 | 类型 | NOT NULL | 默认值 | 主键 | 外键 | 唯一约束 |
|---|---|---|---|---|---|---|
| `id` | INTEGER | 是 | — | **PK AUTOINCREMENT** | 无 | — |
| `from_id` / `to_id` | INTEGER | 是 | — | — | 无（逻辑引用 item） | 表内 `UNIQUE(from_id,to_id,relation)`（自动索引 16/19 之一） |
| `relation` | TEXT | 是 | — | — | 无 | 同上；8 种取值 |
| `note` | TEXT | 是 | `''` | — | 无 | — |
| `created_at` | INTEGER | 是 | — | — | 无 | — |

### 3.6 `zhuli_memory_revision`（10 列）

```sql
CREATE TABLE IF NOT EXISTS zhuli_memory_revision (
  id          INTEGER PRIMARY KEY AUTOINCREMENT,
  memory_id   INTEGER NOT NULL,
  revision_no INTEGER NOT NULL,
  change_kind TEXT    NOT NULL,
  before_json TEXT    NOT NULL DEFAULT '',
  after_json  TEXT    NOT NULL DEFAULT '',
  reason      TEXT    NOT NULL DEFAULT '',
  source_id   INTEGER NOT NULL DEFAULT 0,
  actor       TEXT    NOT NULL,
  created_at  INTEGER NOT NULL,
  UNIQUE(memory_id, revision_no)
);
```

| 字段 | 类型 | NOT NULL | 默认值 | 主键 | 外键 | 唯一约束 |
|---|---|---|---|---|---|---|
| `id` | INTEGER | 是 | — | **PK AUTOINCREMENT** | 无 | — |
| `memory_id` / `revision_no` | INTEGER | 是 | — | — | 无 | 表内 `UNIQUE(memory_id,revision_no)` |
| `change_kind` / `actor` | TEXT | 是 | — | — | 无 | —（9 种 / 4 种取值） |
| `before_json` / `after_json` / `reason` | TEXT | 是 | `''` | — | 无 | — |
| `source_id` | INTEGER | 是 | `0` | — | 无 | — |
| `created_at` | INTEGER | 是 | — | — | 无 | — |

> 迁移**不**为每行写 CREATE revision（C0.2 §8.1 注：2 万行会产生 1.16MB 零信息量记录）；只有合并写 `MERGE`。

### 3.7 `zhuli_memory_entity`（3 列）

```sql
CREATE TABLE IF NOT EXISTS zhuli_memory_entity (
  memory_id INTEGER NOT NULL,
  entity_id INTEGER NOT NULL,
  role      TEXT    NOT NULL DEFAULT 'SUBJECT',
  PRIMARY KEY(memory_id, entity_id, role)
);
```

| 字段 | 类型 | NOT NULL | 默认值 | 主键 | 外键 | 唯一约束 |
|---|---|---|---|---|---|---|
| `memory_id` / `entity_id` / `role` | INTEGER / INTEGER / TEXT | 是 | — / — / `'SUBJECT'` | **复合 PK** | 无 | 复合 PK 自动索引 |

> C1 **只建不写**（写入方在 C2）。旧 `zhuli_entity` 是 `kind+norm` 去重表，语义不同，**不迁、不删、不合并**。

### 3.8 `zhuli_memory_fts`（FTS4，1 列 + 5 影子表）

```sql
CREATE VIRTUAL TABLE IF NOT EXISTS zhuli_memory_fts
  USING fts4(search_text, tokenize=simple);
```

| 项 | 值 |
|---|---|
| 列 | `search_text`（唯一业务列） |
| rowid | = `zhuli_memory_item.id` |
| 影子表 | `_content` / `_docsize` / `_segdir` / `_segments` / `_stat`（SQLite 自动建，实测 v17 库 5 张齐全） |
| 触发器 | 3 个（`_ai` / `_au` / `_ad`），**`ftsMode == TRIGGER` 时**才建 |
| `_au` 关键点 | `AFTER UPDATE OF search_text, status`（不是 `AFTER UPDATE`），且 `WHERE new.status <> 'DELETED'` |
| `_ad` / `_au` 的删除方式 | `DELETE FROM fts WHERE rowid=?`，**不用** `'delete'` 特殊命令（实测报 `SQL logic error`） |
| 不可用时 | `ftsMode=NONE`，不建表不建触发器，检索走 LIKE；**不中止迁移** |

> FTS4 的 `PRIMARY KEY`/`UNIQUE` 都不存在；它自带的 `sqlite_autoindex_zhuli_memory_fts_segdir_1` 是 SQLite 内部结构，**不计入 4 个业务自动索引**（§4.3）。

---

## 4. 15 个显式索引核对

### 4.1 逐项清单（与 C0.2 原文逐字比对，0 处差异）

| # | 索引名称 | 表 | 字段顺序 | UNIQUE | WHERE 条件 | 类型 | 设计来源 | 创建时机 |
|---:|---|---|---|---|---|---|---|---|
| 1 | `idx_mi_slot` | item | `(subject_key, predicate, qualifier_key, status)` | 否 | — | 普通 | C0.2 L188-189 | S7 随建表 |
| 2 | `idx_mi_scope` | item | `(scope_type, scope_id, status, memory_type)` | 否 | — | 普通 | C0.2 L190-191 | S7 |
| 3 | `idx_mi_type_time` | item | `(memory_type, status, valid_until, event_start)` | 否 | — | 普通 | C0.2 L192-193 | S7 |
| 4 | `idx_mi_observed` | item | `(status, observed_at)` | 否 | — | 普通 | C0.2 L194-195 | S7 |
| 5 | `idx_mi_source` | item | `(source_id)` | 否 | — | 普通 | C0.2 L196-197 | S7 |
| 6 | **`idx_mi_dedupe`** | item | `(dedupe_key)` | **是** | `status IN ('ACTIVE','CANDIDATE')` | **唯一（部分）** | C0.2 L201-203 | 运行时 S10；**迁移第 10 步最后建** |
| 7 | `idx_ms_ref` | source | `(source_type, ref_table, ref_id)` | 否 | — | 普通 | C0.2 L258-259 | S7 |
| 8 | **`idx_ms_dedupe`** | source | `(source_type, ref_table, ref_id, ref_sub_id, content_hash)` | **是** | — | **唯一** | C0.2 L260-261 | 运行时 S10；迁移第 10 步（顺序 1） |
| 9 | `idx_mev_source` | evidence | `(source_id)` | 否 | — | 普通 | C0.2 L284 | S7 |
| 10 | **`idx_mev_primary`** | evidence | `(memory_id)` | **是** | `role='PRIMARY'` | **唯一（部分）** | C0.2 L286-287 | 运行时 S10；迁移第 10 步（顺序 2） |
| 11 | `idx_mr_from` | relation | `(from_id, relation)` | 否 | — | 普通 | C0.2 L314 | S7 |
| 12 | `idx_mr_to` | relation | `(to_id, relation)` | 否 | — | 普通 | C0.2 L315 | S7 |
| 13 | `idx_mrev_mem` | revision | `(memory_id, revision_no)` | 否 | — | 普通 | C0.2 L330 | S7 |
| 14 | `idx_mrev_at` | revision | `(created_at)` | 否 | — | 普通 | C0.2 L331 | S7 |
| 15 | `idx_me_entity` | entity | `(entity_id)` | 否 | — | 普通 | C0.2 L339 | S7 |

计数闭合：**12 普通 + 3 唯一 = 15**；按表分布 item 6（5+1）、source 2、evidence 2、relation 2、revision 2、entity 1。

### 4.2 三个唯一索引的原文（必须逐字落地）

```sql
CREATE UNIQUE INDEX IF NOT EXISTS idx_ms_dedupe
  ON zhuli_memory_source(source_type, ref_table, ref_id, ref_sub_id, content_hash);
CREATE UNIQUE INDEX IF NOT EXISTS idx_mev_primary
  ON zhuli_memory_evidence(memory_id) WHERE role='PRIMARY';
CREATE UNIQUE INDEX IF NOT EXISTS idx_mi_dedupe
  ON zhuli_memory_item(dedupe_key) WHERE status IN ('ACTIVE','CANDIDATE');
```

- 三者都是 `CREATE UNIQUE INDEX`，**不是**表内 `UNIQUE(...)`：部分唯一索引只能用前者表达。
- 迁移路径必须**三个全建**（v4.2.1 §4.5.1 的 P0-1 修正）；v3 只补 `idx_mi_dedupe` 是已修缺陷。
- 建前逐个预检（§5.2.1 的 P1/P2/P3），有重复即抛 `MigrationConflictException`，**不自动删重复行**。

### 4.3 4 个自动索引（不可删，不混入 15）

| # | 名称 | 表 | 来源 |
|---:|---|---|---|
| 16 | `sqlite_autoindex_zhuli_memory_evidence_1` | evidence | `PRIMARY KEY(memory_id, source_id)` |
| 17 | `sqlite_autoindex_zhuli_memory_relation_1` | relation | `UNIQUE(from_id, to_id, relation)` |
| 18 | `sqlite_autoindex_zhuli_memory_revision_1` | revision | `UNIQUE(memory_id, revision_no)` |
| 19 | `sqlite_autoindex_zhuli_memory_entity_1` | entity | `PRIMARY KEY(memory_id, entity_id, role)` |

**不做的断言**：索引总数 == 19。原因有二：`sqlite_sequence` 这类内部表也在 `sqlite_master`；FTS4 还会额外产生 `sqlite_autoindex_zhuli_memory_fts_segdir_1`（实测 v17 库存在）。因此 `EXPECTED_INDEXES` 只断言 15 个显式项（存在性 + 唯一性 + 列顺序 + WHERE），`EXPECTED_AUTO_INDEXES` 只断言 4 个业务自动索引存在。

### 4.4 同名不同定义 / 与旧索引冲突检查

| 检查 | 结果 |
|---|---|
| 15 个名字是否重复出现于 C0.2 | 否（各出现一次） |
| 15 个名字是否与 v16 现有索引同名 | **否**（现有为 `idx_inbox_*`/`idx_chat_log_at`/`idx_cloud_log_at`/`idx_ledger_inbox`/`idx_reminders_*`/`idx_skills_k`/`idx_step_task`/`idx_task_*`/`idx_fact_*`/`idx_tx_*`/`idx_vecq_at`，无交集） |
| 15 个定义在 C0.2 与 v4.2.1 §4.5.1 之间是否一致 | **完全一致**（名称、表、列序、唯一性、WHERE 五要素逐项相同） |
| 是否存在旧索引需要退役 | `idx_fact_slot` / `idx_fact_source` / `idx_fact_status` 随表改名挂到 `zhuli_fact_v1`，**C1 不删**（只读遗留）；`idx_ms_hash` / `idx_ms_indep` 在 C0.2 已被删除，C1 不得再建 |

### 4.5 需要保留的旧索引

| 旧索引 | 处置 | 理由 |
|---|---|---|
| `idx_fact_slot` / `idx_fact_source` / `idx_fact_status` | **保留**（随 `RENAME` 自动跟随） | `zhuli_fact_v1` 在 C1 仍是只读事实源，C3 才随表清理；现在删会让"回看旧表"变慢，且删除不在 C1 范围 |
| `zhuli_fact_vec` 主键 | 保留 | 结构不变，语义改指 `item.id` |
| `zhuli_vec_queue` 主键 + `idx_vecq_at` | 保留 | 同上 |
| `indexedSpaces` 依赖的 `idx_?` | 无（`space` 上本无索引） | v4.2.1 未新增，符合"不加看起来有用的索引" |

---

## 5. v16 → v17 数据迁移映射

### 5.1 逐表映射总表

| 旧表 | 旧字段 | 新表 | 新字段 | 转换规则 | 丢弃规则 | 保真验证 |
|---|---|---|---|---|---|---|
| `zhuli_fact`（16 列，2 行） | `id, subject, predicate, value, kind, confidence, source_type, source_id, source_text, observed_at, valid_from, valid_to, status, superseded_by, created_at, updated_at` | `zhuli_memory_item`（40） + `zhuli_memory_source`（12） + `zhuli_memory_evidence`（6） | 见 §5.2 | 逐列，见 §5.2/§5.3 | 空 `subject` / 空 `value` 跳过；SECRET 整行隔离；无其它丢弃 | 显式 `id` + 11 个直接列逐行比对 + 行数恒等式 |
| `zhuli_entity`（7 列，0 行） | 全部 | **不迁**，表保留 | — | 不映射（`zhuli_memory_entity` 是"记忆↔实体"关联表，语义不同） | 无 | 迁移前后 `COUNT(*)` 与 `sqlite_master.sql` 不变；`zhuli_memory_entity` 为 0 行 |
| `zhuli_fact_vec`（4 列，1 行） | `fact_id, space, vec, at` | 同名表，**结构不变** | 同名 | `fact_id` 语义改为 `item.id`；id 保真使其无需改写 | 校验不通过的行删除 | `vec_kept=1`、`vec_dropped=0`；孤儿查询返回 0 |
| `zhuli_vec_queue`（4 列，0 行） | 全部 | 同名表，结构不变 | 同名 | 不搬；只为"被删向量"补 `(fact_id, attempts=0, at=now)` | 无 | 迁移后 `COUNT(*)` = 0（本库）；有删向量时 = 删除数 |
| `zhuli_agent_task`（14 列，28 行） | 全部 | 不变 | — | 不动 | 无 | 行数与 `local_only` 分布不变 |
| `zhuli_agent_step`（13 列，186 行） | 全部 | 不变 | — | 不动 | 无 | 行数 + `MAX(seq)` 不变 |
| `zhuli_tool_exec`（11 列，51 行） | 全部 | 不变 | — | 不动 | 无 | 行数 + `idx_tx_key` 无冲突 |
| `inbox`（17 列，0 行） | `id` 等 | 不变 | — | 作为 `source.ref_id` 指向的对象（见 B-2） | 无 | `source` 中 `source_type='INBOX'` 的 `ref_id` 必须能在 `inbox` 命中的比例 = 100%（本库 0 行，边界样本另测） |
| `reminders` / `ledger` / `chat_log` / `cloud_log` / `skills` / `source_rule` / `embeddings` | — | 不变 | — | 不动 | 无 | 行数与结构不变；`embeddings` 是 inbox 向量，**不是** fact 向量，不参与迁移 |

### 5.2 `zhuli_fact` 16 列 → 新结构逐列

| # | 旧列 | 新列/新表 | 规则 | 丢弃 | 设计来源 |
|---:|---|---|---|---|---|
| 1 | `id` | `item.id` | **显式写入原值**，`INSERT ... (id, …) VALUES(?, …)` | 否 | C0.2 §8.1 #1 / §8.6 |
| 2 | `subject` | `item.subject` | `trim()`；空 → `SkippedNoSubject` | 是（整行） | §8.1 #2 |
| 2b | 计算 | `item.subject_key` | `norm(subject)`；全标点 → `lowercase()` 兜底，仍空则跳过 | 是 | §8.1 #2b / §5.3 |
| 3 | `predicate` | `item.predicate` | `trim()`；**空保留**（仍是"属性名缺失"的事实） | 否 | §8.1 #3 |
| 4 | 常量 | `item.qualifier_key` | `''` | — | §8.1 #4 |
| 5 | `value` | `item.value` | `trim()`；空 → `SkippedNoValue` | 是（整行） | §8.1 #5 |
| 6 | 计算 | `item.content` | `"$subject｜$predicate｜$value"`（与 `Fact.display` 逐字一致） | — | §8.1 #6 |
| 7 | 计算 | `item.normalized_content` | `norm(content)` | — | §8.1 #7 |
| 8 | 计算 | `item.dedupe_key` | `sha256Hex32(scope␟id␟type␟subjectKey␟predicate␟qualifierKey␟norm(value)␟bucket)`；迁移只有 FACT/PREFERENCE → `bucket=0` | — | §2.2 算法 / §8.1 #8 |
| 9 | 计算 | `item.slot_hash` | 同上但**不含 value** | — | §2.2 / §8.1 #9 |
| 10 | 计算 | `item.search_text` | `searchText(content)`：CJK 2 字滑窗 + 字母数字整体小写 | — | §2.7 / §8.1 #10 |
| 11 | `kind` | `item.legacy_kind` | `FactKind.name` 原样 | 否 | §8.1 #11 |
| 12 | `kind` | `item.memory_type` + `item.cardinality` | `PREFERENCE→PREFERENCE`，其余 `→FACT`；`EXPRESS/ORDER/MONEY/SCHEDULE→MULTI`，其余 `SINGLE` | 类型变粗（有意） | §8.1 #12 / §8.2 |
| 13 | `confidence` | `item.confidence` | 按 basis 上限 clamp，**只降不升**；`clamped++` | 否 | §8.1 #13 / §8.3 |
| 14 | `source_type`(+`source_text`) | `item.confidence_basis` | 5 条映射（§5.3） | 否 | §8.1 #14 / §8.3 |
| 15 | `source_type`+`source_id`+`source_text` | `zhuli_memory_source` 1 行 + `evidence(PRIMARY)` | 见 §5.4 | `source_text` 截 120 并脱敏（原文仍在旧表） | §8.1 #15 / §8.4 |
| 16 | `observed_at` | `item.observed_at` | 原值；0 → `created_at`；仍 0 → `now`（**绝不用 recorded_at 兜底**） | 否 | §8.1 #16 |
| 17 | `valid_from` | `item.valid_from` | 原值，**0 保留 0**；**不设精度** | 否 | §8.1 #17 |
| 18 | `valid_to` | `item.valid_until` = **0**；值转 `item.invalidated_at` | `valid_to` 是"我们何时不再相信"，不是世界终点 | 信息不丢（转到 #23） | §8.1 #18 / 禁止 2 |
| 19 | `status` | `item.status` | 4→6 映射；未知/`NULL`/空 → `CANDIDATE` + `unknown_status++` | 否 | §8.3 / §5.3.1 |
| 20 | `superseded_by` | `item.superseded_by` + `relation` | 原值；目标不存在 → 清 0（**只清指针**） | 否 | §8.1 #20 / §8.5 |
| 21 | `created_at` | `item.recorded_at` | 原值 | 否 | §8.1 #21 |
| 22 | `updated_at` | `item.updated_at` | 原值；0 → `created_at` | 否 | §8.1 #22 |
| 23 | 计算 | `item.invalidated_at` | `SUPERSEDED → valid_to ?: updated_at`；`DELETED → updated_at`；其它 0 | — | §8.1 #23（**边界见 B-3**） |
| 24 | 计算 | `item.last_confirmed_at` | `basis==USER_CONFIRMED ? updated_at : 0` | — | §8.1 #24 |
| 25 | 计算 | `item.time_confidence` | **恒 `'NONE'`**，不按条件分支 | — | §8.1 #25 / 禁止 1 |
| 26 | 计算 | `item.cardinality` | 见 #12；`payload_json.cardinality` 存在则覆盖 | — | §5.3 |
| 27 | 常量 | `item.scope_type` / `scope_id` | `'GLOBAL'` / `''` | — | §8.1 #27 / §7.1 |
| 28 | 计算 | `item.sensitivity` | 四字段任一 SECRET → 整行隔离；任一敏感 → 1；否则 0 | SECRET 整行不写 | §5.3.2（**与 C0.2 #28 不同，见 D-3**） |
| 29 | 常量 | `item.revision` | `1` | — | §8.1 #29 |
| 30 | 常量 | `item.extractor` | `'import:legacy'` | — | §8.1 #30 |
| 31 | 计算 | `item.payload_json` | `{"migrated_from":<id>}`；仅未知状态追加 `"legacy_status":"<redact+截32>"` | — | §8.1 #31 / §5.3.1 |
| 32 | 计算 | `item.deleted_at` | `status=DELETED ? updated_at : 0` | — | §8.1 #32 |
| 33 | 常量 | `times_*` / `last_used_at` | `0`；`times_reinforced` 第 5 步重算 | — | §8.1 #33 / §5.2 第 5 步 |
| 34 | — | `event_start` / `event_end` | `0`（旧表没有事件时间，不臆造） | — | §8.1 #34 / §8.2 |

### 5.3 状态、basis、置信度

**状态映射（必须有 `NULL`/空串/未知值三个分支）**

| 旧值 | 新值 | `unknown_status` | `payload_json` |
|---|---|---|---|
| `ACTIVE` | `ACTIVE` | 否 | 仅 `migrated_from` |
| `UNCONFIRMED` | `CANDIDATE` | 否 | 仅 `migrated_from` |
| `SUPERSEDED` | `SUPERSEDED` | 否 | 仅 `migrated_from` |
| `DELETED` | `DELETED` | 否 | 仅 `migrated_from` |
| `EXPIRED` / `REJECTED`（v16 不该有） | 原样 | 否（合法状态） | 仅 `migrated_from` |
| `NULL` | **`CANDIDATE`** | **+1** | `legacy_status:"<NULL>"` |
| `''` / 纯空白 | **`CANDIDATE`** | **+1** | `legacy_status:"<EMPTY>"` |
| 其它字符串 | **`CANDIDATE`** | **+1** | `legacy_status:"<redact 后截 32>"` |

> 硬约束：**未知状态绝不映射成 `ACTIVE`**（D-6 第 3/4 条）。计数 SQL 与映射函数必须共用同一谓词（`IS NULL` + `TRIM` + `= ''` 三条件），否则 `T-X08` 会出现"计数 1、SQL 查 2"。

**basis 与置信度上限**

| `source_type` | 条件 | `confidence_basis` | 上限 |
|---|---|---|---|
| `USER` | `source_text` 以 `"用户纠正："` 开头 | `USER_CONFIRMED` | 1.00 |
| `USER` | 其它 | `USER_STATED` | 0.95 |
| `INBOX` | — | `RULE_EXTRACTED` | 0.85 |
| `INFERRED` | — | `MODEL_INFERRED` | **0.65** |
| `SYSTEM` | — | `EXTERNAL_VERIFIED` | 0.90 |

真实库 2 行实测（只读，未输出原文）：`confidence` 均为 **0.900**、`kind=OTHER`、`source_type=USER`、`source_id=0`、`source_text` 均**不以**"用户纠正："开头 → 映射为 `USER_STATED`，上限 0.95；`0.9 ≤ 0.95` 故 **clamp 计数为 0**。`source_id=0` 使本库的 `independent_key` 走 `"USER|-|<hash>"` 分支，B-2 的 `ref_table` 问题在本库不显形——但真实用户库里 `INBOX` 事实的 `source_id` 是 inbox 行号，问题一定会出现。

### 5.4 来源与证据（B-2 冲突所在）

目标流程（C0.2 §8.4 / v4.2.1 §5.5）：

```
sensitivity = classifyForMigration(subject, predicate, value, sourceText)   // 四字段，先判后写
  SECRET → Quarantined，零写入
excerpt = redact(sourceText.ifBlank { content }).take(120)
hash    = sha256Hex(norm(sourceText.ifBlank { content }))
refId   = source_id
key     = (source_type, ref_table="zhuli_fact", ref_id=refId, ref_sub_id="", content_hash=hash)
sourceId = sourceCache.getOrPut(key) { INSERT OR IGNORE → SELECT id }
independent_key = refId != 0 ? "$source_type|zhuli_fact|$refId" : "$source_type|-|$hash"
INSERT evidence(memory_id, source_id, role='PRIMARY', first_seen_at=observed_at, last_seen_at=observed_at, seen_count=1)
UPDATE item SET source_id = sourceId
trust: USER→3，其它→1；captured_at = observed_at
```

**冲突（B-2）**：`source_id` 的真实语义是 **inbox 行号**（`ExtractPipeline.kt:105-107` 传 `rec.id`；`MemoryBackfill.kt:124-127` 同；`ZhuliAgentTools.kt:166` 的"记住"甚至用 `SourceType.USER` + inbox id）。而 C0.2 §2.3 对 `ref_table` 的取值说明是 `inbox / zhuli_agent_step / ConversationEntity / MemoryEntity`。三处相互矛盾：

1. `ref_table='zhuli_fact'` + `ref_id=<inbox id>` → 溯源会去查 `zhuli_fact` 的第 N 行，查到的是一条**不相干的事实**；迁移后该表还不存在。
2. 该三元组进了 `idx_ms_dedupe` 唯一键与 `independent_key`，**事后修改要重算全表**。
3. `ConversationEntity` / `MemoryEntity` 是宿主 Room 实体名，本库根本没有这些表——这套词表本身也需要收敛。

**建议（需负责人裁定，不在本报告自行选择）**：

| 选项 | 写法 | 代价 |
|---|---|---|
| A（推荐） | `ref_table` 按来源真实载体：INBOX→`'inbox'`，USER→`''`（无行级引用），SYSTEM/INFERRED→`''`；`ref_id` 保留 inbox id | 与 C0.2 §2.3 词表一致；`independent_key` 变为 `INBOX|inbox|123`，需同步 §5.5 与 §8.4 两处文字 |
| B | 保持 `'zhuli_fact'`，但在 C0.2/v4.2.1 写明"这是**迁移期标签**，`ref_id` 是 inbox id，不用于动态查表" | 零代码改动；但溯源 UI 永远不能按 `ref_table` 解析，且与 §2.3 词表冲突 |
| C | 新增 `ref_kind` 列区分"迁移标签/真实载体" | 改 40 列之外的表结构，超出 C1 范围 |

### 5.5 替代链、断链、重复合并

| 场景 | 规则 | 依据 |
|---|---|---|
| 正常替代 | 拷 `superseded_by`；写 `relation(from=superseded_by, to=id, 'SUPERSEDES', note='migrate')` | §8.5 |
| 断链（目标不存在） | `superseded_by=0`，**保留 `invalidated_at`** | §8.5 第 3 条；预演实测 id=108→999999 |
| 真重复（同 `dedupe_key` 且 `ACTIVE/CANDIDATE`） | 选保留行：`rank(basis)` → `last_confirmed_at` → `observed_at` → `id`；其余标 `SUPERSEDED` + `SAME_AS`；evidence 复制为 `SUPPORTING` 到保留行，**被合并行证据原样保留** | §8.8 |
| 不合并的三种边界 | 不同 scope / 不同 memory_type / 不同 value（两个取件码） | §8.8 |
| `valid_from` 合并 | 全为正取 `min`；任一为 0 则 0 | §8.8 |

### 5.6 向量

| 项 | 规则 |
|---|---|
| 结构 | `zhuli_fact_vec` 4 列不变，`fact_id` 语义改为 `item.id` |
| 保留条件 | `space` 可解析出 dim（`provider|dim`）且 `LENGTH(vec)=dim*4` 且 `item` 存在该 id |
| 不满足 | `DELETE` 该行 + `INSERT OR IGNORE INTO zhuli_vec_queue(fact_id, attempts=0, at=now)` |
| 不重算 | 迁移不做模型推理；换 space 由 `MemoryIndexer.drainOnce` 处理 |
| 本库实测 | 1 条 `hash-2gram-v1|256` / 1024 B → 保留；孤儿 0 |

### 5.7 行数闭合与保真验证方法

**主恒等式**（`merged` 是 `inserted` 的子集，不单独计入）：

```
src == inserted + quarantined + skippedNoSubject + skippedNoValue + failed
且 merged <= inserted
且 failed == 0            ← 提交的附加条件
```

本库预期：`src=2 → inserted=2, quarantined=0, skippedNoSubject=0, skippedNoValue=0, failed=0, merged=0`；`sources=1, evidence=2, relations=1, items=2`。

**逐字段保真（不抽样）**：对 11 个直接映射列做全量比对（一条 SQL 即可，行数小）：

| 比对列 | 断言 |
|---|---|
| `id` | `item.id` 集合 == `old.id` 集合（除丢弃/隔离行） |
| `subject` / `predicate` / `value` | `trim()` 后与旧值相等 |
| `confidence` | `item.confidence == min(old.confidence, 上限)` |
| `status` | 按映射表逐行相等 |
| `observed_at` | `old.observed_at>0 ? 相等 : (== created_at 或 == now)` |
| `valid_from` | 相等（0 也相等） |
| `valid_until` | **恒 0** |
| `recorded_at` | `== old.created_at` |
| `updated_at` | `old.updated_at>0 ? 相等 : created_at` |
| `superseded_by` | 目标存在则相等；断链则 0 |
| `legacy_kind` | `== old.kind` |

**计算列**用不变量代替逐字比对：`content == subject+"｜"+predicate+"｜"+value`；`dedupe_key` 匹配 `^[0-9a-f]{32}$` 且 `WHERE status IN ('ACTIVE','CANDIDATE')` 无重复；`search_text` 在 `content` 非空时非空；`extractor='import:legacy'` 行数 == `inserted`。

### 5.8 丢弃与隔离

| 类别 | 处置 | 数据在哪 | 可恢复 |
|---|---|---|---|
| 空 `subject` | 不迁（`SkippedNoSubject`） | `zhuli_fact_v1` 原行 | 是（只读表仍在） |
| 空 `value` | 不迁（`SkippedNoValue`） | 同上 | 是 |
| SECRET（四字段任一命中） | 整行不迁（`Quarantined`），零写入 | 同上 | 是（但按 C0.2 D-1 选项 A，C3 删表时需二次确认） |
| 未知状态 | **迁**，但降为 `CANDIDATE` + 记 `legacy_status` | 新表 | 是（可人工确认） |
| 断链 `superseded_by` | 迁，指针清零、`invalidated_at` 保留 | 新表 | 语义可查 |

---

## 6. 新装、升级、onOpen 三条路径

### 6.1 现状调用图（实测）

```
新装 version=0
  onCreate ── inbox(17 列)+4 索引 / FTS5(best-effort) / chat / reminders / ledger / cloud_log
             / skills / embeddings / source_rule / createCoreTables(317) / createV16Tables(318)
  onOpen   ── ensureColumns / createSourceTable / createCoreTables(339) / createV16Tables(340)
             / purgeOrphanEmbeddings(once) / ensureFtsSync(once)

v16 升级 16→17（DB_VERSION 改 17 后）
  helper 开 T0 → onUpgrade(673)
    ├ backupBeforeMigration(679→698)   ← 当前是"尽力而为"
    ├ db.beginTransaction(680)         ← 嵌套，不产生新 BEGIN
    ├ upgradeSteps(683→715)            ← 15 个 if 分支，末尾将挂 v17
    ├ setTransactionSuccessful(684)
    └ endTransaction(690)              ← 真正 COMMIT
  helper setVersion(17)（同一事务）
  onOpen(332) ── 同上调用链
```

### 6.2 目标路径矩阵

| 路径 | 允许创建 | 禁止创建 | 依据 |
|---|---|---|---|
| `onCreate`（新装） | A 组 8 张实表（`inbox_fts` 若可用）+ B 组 5 张 + C 组 6 张普通表 + `createAll(withUnique=true)` 的 15 索引 + 记忆 FTS（若可用） | `zhuli_fact` / `zhuli_fact_v1` | v4.2.1 §4.7 / §4.8.1 D-7 ① |
| `onUpgrade`（16→17） | `createAll(withUnique=false)`（6 表 + 12 普通索引）→ 迁移 → FTS → 触发器 → 3 唯一索引 → 改名 | 除 `oldVersion<15` 外不得建 `zhuli_fact` | §4.7 / §5.2 |
| `onUpgrade`（<15） | `createCoreTablesV15` + `createFactTableV15` | — | §4.8.3 |
| `onOpen`（每次） | 长期核心表 + 新表结构自愈 | **`zhuli_fact` / `zhuli_fact_v1`** | §4.7 / D-7 ③ |

### 6.3 `InboxDatabase.kt` 的 6 处改动（行号已核对；5 处结构接入 + 1 处备份闸门）

| # | 位置 | 现状 | 改为 |
|---|---|---|---|
| 1 | `:50` | `DB_VERSION = 16` | `17` |
| 2 | `:317` | `createCoreTables(db)` | `createCoreTablesV15(db)` + `MemorySchema.attach(prefs)` + `MemorySchema.createAll(db, withUnique = true)` |
| 3 | `:339` | `createCoreTables(db)` | `createCoreTablesV15(db)` + `MemorySchema.ensureXxx(db, …)`（**函数名待 B-1 裁定**） |
| 4 | `:787` | `createCoreTables(db)` | `createCoreTablesV15(db)` + `createFactTableV15(db)` |
| 5 | `upgradeSteps` 末尾（`:793` 之后） | — | `if (oldVersion < 17) { MemorySchema.createAll(db, withUnique=false); MemoryMigrator.migrate(db, now) }` |
| 6 | `:679` | `backupBeforeMigration(oldVersion)` | `MigrationBackup.checkAndBackup(db, oldVersion, backupDir)`（**模式闸门在拷贝前**） |

> 第 3 项是 B-1 的核心：按 v4.2.1 §4.8.2 原文写 `MemorySchema.ensureAll(db)` **不可编译**（缺 3 个参数），且 `ensureAll` 在 `requested == NONE` 时**第一行就返回**，不会补建任何基表。

### 6.4 onOpen 旧表复活风险与三个缺口

**风险（X-1）成立且是必然的**：只要 `onOpen` 仍调 `createCoreTables`，v17 库每次打开都会 `CREATE TABLE IF NOT EXISTS zhuli_fact`，把已改名的旧表**以空表形式造回来**。后果：`T-M09` 必失败；残留写旧表的代码不会 `no such table`，而是静默写进空表（fail-loud 失效）。

**已解决的部分**：`createCoreTablesV15` / `createFactTableV15` 的拆分方案正确、调用点覆盖完整（3 处），`zhuli_entity` 保留在 V15 组也正确（避免"老库有、新库没有"的新漂移）。

**三个缺口**：

| 编号 | 缺口 | 证据 | 后果 |
|---|---|---|---|
| **B-1a** | `ensureAll` 只建 FTS 表与触发器，**不建 6 张基表、不建 15 个索引** | §6.4.2 `ensureAll` 代码（L2758-2830）只操作 `zhuli_memory_fts`；§4.8.1 C 组却写"`createAll` / `ensureAll`" | `onOpen` 没有"补建缺失基表"的能力；用户清库/部分恢复后结构不自愈 |
| **B-1b** | `ensureAll(db, prefs, cache, requested)` 四参，`onOpen` 只有 `db` | §6.4.2 签名 vs §4.8.2 第 2 行 | 按文档写即编译失败；强行补参需在 `onOpen` 里做一次 FTS 探测（80ms）或读缓存 |
| **B-1c** | `ensureAll` 首行 `if (requested == NONE) return None` | L2761 | 在没有 FTS4 的 ROM 上，`onOpen` 的"7 张表 IF NOT EXISTS"**完全不会执行** |
| E-4 | `onOpen` 本就不补建 chat/reminders/ledger/cloud/skills/embeddings | `:335-340` | 既有不对称，v17 不扩大即可；但不要宣称"onOpen 能自愈全部结构" |

**建议（三选一，需负责人裁定）**：

| 选项 | 做法 | 评价 |
|---|---|---|
| A（推荐） | 拆两个入口：`MemorySchema.ensureStructure(db)`（6 表 + 15 索引，`IF NOT EXISTS`，索引失败只记日志不抛）+ `MemorySchema.ensureFts(db, prefs, cache, requested)`（现 `ensureAll` 改名）。`onOpen` 只调前者；FTS 自愈放在 `MemoryStore` 初始化（B1-3）里做 | 职责清晰；`onOpen` 不再需要 prefs/cache；NONE 档也能补表 |
| B | `ensureAll` 增加"先建结构"的前置段，并把 `requested` 改成可空（null=只建结构不碰 FTS），签名变为 `ensureAll(db, prefs?, cache?, requested?)` | 改动小，但把两种职责塞进一个函数，后续易漂移 |
| C | `onOpen` 直接调 `createAll(db, withUnique = true)`，`ensureAll` 只留给 Store | 最省事；但 `withUnique=true` 在已有重复行的库上会抛 → `onOpen` 崩溃循环，必须包 try/catch 并降级 |

### 6.5 防止三处定义漂移的机制

| 机制 | 内容 | 覆盖 |
|---|---|---|
| 单一真相 | 7 段 DDL 只在 `MemorySchema` 常量里；`onCreate` / `onUpgrade` / `onOpen` 都引用常量，不写字面 SQL | 结构漂移 |
| 对象集断言 | `T-M40`（新装）/ `T-M47`（升级）/ `T-M49`（两路径 `(name,unique,columns,where)` 四元组集合相等） | 路径不一致 |
| 旧表复活断言 | `T-M41`（连开 3 次 `zhuli_fact` 不存在）/ `T-M42`（直写旧表抛 `no such table`）/ `T-M45`（v16 缺源表 → 抛且不补建） | X-1 回归 |
| 静态自检 | `grep -rn "TABLE_FACT" app/src/main` 只剩 `InboxDatabase`（`createFactTableV15`）与 `MemoryMigrator` 两个文件 | 代码层双写 |
| 列数断言 | `EXPECTED_COLUMNS`（40 个，有序）+ `PRAGMA table_info` | DDL 拼写/漏列 |

---

## 7. 备份与事务边界

### 7.1 现状逐条核对（任务书 F 的 10 点）

| # | 要求 | 现状 | 判定 |
|---:|---|---|---|
| 1 | 读取并检查 `journal_mode` | **没有** | 缺 |
| 2 | 确认状态符合备份前置条件 | 只判 `src.exists()` | 缺 |
| 3 | 备份写临时文件 | **直接写正式文件** `v${old}_$ts.db` | 缺 |
| 4 | 检查大小 / `integrity_check` / `user_version` | **全无** | 缺 |
| 5 | 校验后原子改名 | 无（`copyTo` 直接落正式名） | 缺 |
| 6 | 备份任一步失败 → 迁移中止 | **catch 后继续**（`:709-711`） | **相反** |
| 7 | 迁移有明确事务边界 | helper 的 T0 + 嵌套 `beginTransaction`（`began`） | 有，但表述误导 |
| 8 | 任一步失败不留半迁移 | 依赖 T0 回滚 | 有（需验证） |
| 9 | 不确认事务状态不继续 | `began=false` 时仍继续（日志写"without transaction"） | 需修表述 |
| 10 | 不把嵌套 `beginTransaction` 当 savepoint | 代码未当 savepoint 用，但日志暗示"降级为无事务" | 需修表述 |

### 7.2 `SQLiteOpenHelper.onUpgrade` 的事务语义（AOSP 事实）

```
getDatabaseLocked()
  ├ db.beginTransaction()          ← BEGIN EXCLUSIVE，T0 真正开始
  ├ onConfigure / onCreate|onUpgrade
  ├ db.setVersion(mNewVersion)     ← 版本号在同一事务内
  ├ db.setTransactionSuccessful()
  └ db.endTransaction()            ← 只有这里 COMMIT
```

因此：

1. `onUpgrade` 里的 `db.beginTransaction()` 是**纯嵌套**——`SQLiteSession.beginTransactionUnchecked()` 只在 `mTransactionStack == null` 时执行 `BEGIN`，嵌套只做链表记账，**不产生 SAVEPOINT**。
2. 嵌套层 `setTransactionSuccessful()` 不会提交；只有最外层 `endTransaction()` 才 COMMIT；任一子层未标记成功 → `mChildFailed` 传给外层 → **整体 ROLLBACK**。
3. 结论：`began` 分支在真实调用路径下**永远为 true**；`began=false` 的"无事务降级"不会发生。现有日志 `"upgrade runs without transaction"` 是**错误表述**，应删除或改成"nested transaction（T0 已由 helper 持有）"。
4. 备份位于 T0 内、**任何写语句之前**——此时 `journal_mode=delete` 下主库文件等于"上次提交后的完整状态"，拷贝有效；WAL 下无效，这正是必须前置闸门的原因。

### 7.3 目标备份流程（v4.2.1 §5.12 契约）

| 步 | 动作 | 失败行为 |
|---:|---|---|
| 0 | 读 `PRAGMA journal_mode`，不在 `{delete,truncate,persist}` → `UnsupportedJournalModeException` | **中止，0 字节拷贝** |
| 1 | 建目录 `databases/pre_migration/`，检查可写 | `BackupFailedException("mkdir")` → 中止 |
| 2 | 复制到 `.v16_<ts>.db.tmp` | `"copy"` → 中止，删 tmp |
| 3 | 大小校验（非 0 且 == 源大小） | `"size"` → 中止 |
| 4 | 只读打开副本：`integrity_check=='ok'` 且 `user_version==oldVersion` | `"integrity"` → 中止 |
| 5 | `renameTo(v16_<ts>.db)` 原子转正 | `"rename"` → 中止 |
| 6 | 只保留最近 3 份 `v*.db` | 失败只 `Log.w`，不中止 |

**关键差异**：从"备份是额外保险"变成"**备份是迁移的前置条件**"。`journal_mode` 不合法时连一个字节都不拷——因为拷出来的是一份"缺 WAL 页"的假备份，而它正是回滚方案 L2 的唯一依靠。

### 7.4 生命周期

| 对象 | 开始 | 结束 | 失败时 |
|---|---|---|---|
| 事务 T0 | helper `beginTransaction` | helper `endTransaction` | 异常 → ROLLBACK，`user_version` 仍 16 |
| 备份 `.tmp` | 第 2 步 | 第 5 步改名 | 删除（`catch` 里 `tmp.delete()`） |
| 正式备份 `v16_*.db` | 第 5 步 | 保留 3 份 | 已生成则可用于人工恢复，但迁移已中止 |
| `lastMigrationError` | `onUpgrade` catch | 下次成功迁移时清空 | 供 UI 提示 |
| `lastBackupError` | `MigrationBackup.fail` | 下次成功备份 | `unsupported_journal_mode:*` / `backup_failed:*` |

### 7.5 必须改的实现点

| # | 现状 | 改为 | 风险 |
|---:|---|---|---|
| 1 | `backupBeforeMigration` 吞异常 | 抛 `BackupFailedException` | 低（迁移本就该失败即停） |
| 2 | 直接写正式文件 | `.tmp` + 校验 + `renameTo` | 低 |
| 3 | 无 `journal_mode` 闸门 | 第一行断言 | 低（当前实测 `delete`） |
| 4 | 无 `integrity_check` / `user_version` 复核 | 只读连接复核 | 中（`openDatabase` 在 T0 内打开另一连接，需确认不锁冲突；只读 + 短生命周期可接受） |
| 5 | `began` 日志误导 | 改文案或删除分支 | 无 |

> 第 4 项有一个实现细节必须在 B1-2 验证：T0 持有 `EXCLUSIVE` 锁时，**同一进程**再以只读打开副本文件是安全的（副本是另一个文件，不是主库），不会 `SQLITE_BUSY`。这一点与"事务内 checkpoint 自己的库"不同，后者才会 BUSY。

---

## 8. 失败与回滚

### 8.1 失败矩阵

| 失败点 | 异常 | 是否中止 | 库状态 | 备份 | 用户可见 |
|---|---|---|---|---|---|
| `journal_mode` 不支持 | `UnsupportedJournalModeException` | 是 | v16，未改 | 0 个 | 升级失败 |
| 备份目录/复制/大小/完整性 | `BackupFailedException(stage)` | 是 | v16，未改 | 0 个（tmp 已删） | 升级失败 + `lastBackupError` |
| 前置断言（`oldVersion` 越界 / v15+ 缺源表） | `IllegalStateException` | 是 | v16 | 有 | 升级失败 |
| 建表 / 建普通索引失败 | `SQLiteException` | 是 | v16（DDL 回滚） | 有 | 升级失败 |
| 单行插入失败 | 记 `failed++` 继续 | 否（但末尾必抛） | 事务内 | 有 | 无 |
| 数量不闭合 / `failed>0` | `MigrationCountException` | 是 | v16 | 有 | 升级失败 |
| 唯一索引预检有重复 | `MigrationConflictException` | 是 | v16 | 有 | 升级失败 + 表名/组数/键前缀 |
| 唯一索引建失败 | `SQLiteException` | 是 | v16 | 有 | 同上 |
| FTS 建表/触发器失败 | 不抛，降 `ftsMode` | **否** | v17 | 有 | 检索变慢 |
| `sqlite_sequence` 不存在 | 跳过 | 否 | v17 | 有 | 无 |
| `sqlite_sequence` 收敛后 ≠1 行或 `seq<MAX(id)` | `IllegalStateException` | 是 | v16 | 有 | 升级失败 |
| `ALTER TABLE RENAME` 失败 | `SQLiteException` | 是 | v16 | 有 | 升级失败 |
| 进程崩溃（任意时刻） | — | — | rollback journal 回滚 → v16 | 有 | 下次重试 |

### 8.2 三级恢复路径

| 级别 | 手段 | 能恢复到 | 前提 |
|---|---|---|---|
| L1 | T0 回滚 | 迁移前一刻的 v16 | 事务未提交（绝大多数失败） |
| L2 | `pre_migration/v16_*.db` 备份 | 升级前完整库 | 备份通过校验；用户/开发手动替换 |
| L3 | 从系统备份/导出恢复 | 更早 | 有外部备份（不在 C1 范围） |

### 8.3 迁移后验收失败的处理（提交之后）

迁移**已提交**（`user_version=17`）后才发现问题（例如 `onOpen` 的 `ensureAll` 失败、`selfCheck` 返回非空），此时**不能**回滚——事务已结束。处置分级：

| 失败 | 处置 | 不允许 |
|---|---|---|
| FTS 结构/数据异常 | 降 `ftsMode=APP_SYNC`/`NONE`，`cache.invalidate()`，下次开库重建；`selfCheck` 报告 | 判 `TRIGGER` 却不同步 |
| 索引缺失 | `ensureStructure` 补建（失败只记日志），`selfCheck` 报"索引缺失" | 在 `onOpen` 里抛异常导致开库崩溃循环 |
| 数据不变量异常 | `MemoryStore.selfCheck()` 返回具体 SQL 名；不自动修 | 自动删行"修数据" |
| 旧表被复活 | `T-M41`/`T-M42` 失败即视为回归，回滚版本重新发版 | 静默接受 |
| 需要真正回退 | 用 L2 备份人工替换 + 把 `DB_VERSION` 改回 16 的**新版本**（不能在原版本上 `onDowngrade`，helper 默认抛） | `onDowngrade` / 覆盖用户数据 |

---

## 9. 真实 v16 副本验证方案

### 9.1 身份校验（已完成）

| 项 | 期望 | 实测 | 结论 |
|---|---|---|---|
| 文件 | `Temp/c01/zhuli_inbox_v16_real.db` | 存在 | ✅ |
| 字节数 | 274,432 | 274,432 | ✅ |
| SHA-256 | `5936d12efcb1bd99f10aea15fa5d24ecd56d294825156ce630fbbec4b9f75660` | 完全相同 | ✅ |
| `user_version` | 16 | 16 | ✅ |
| `integrity_check` | ok | ok | ✅ |
| `journal_mode` | delete（迁移允许） | delete | ✅ |

**只读纪律**：本次所有查询用 `file:…?mode=ro&immutable=1`，未复制、未 VACUUM、未 REINDEX、未建临时表。后续任何"要写入"的验证必须**先复制**到 `Temp/c1b/work/` 再操作，原件哈希保持不变。

### 9.2 迁移前快照（必须落盘）

| 快照 | SQL | 用途 |
|---|---|---|
| S1 结构 | `SELECT type,name,tbl_name,sql FROM sqlite_master ORDER BY type,name` | 迁移后 diff；证明只增不减（除 `zhuli_fact` 改名） |
| S2 列 | 对 17 张表逐个 `PRAGMA table_info(t)` | 列名/类型/默认值/NOTNULL 比对 |
| S3 索引 | `SELECT name,tbl_name,sql FROM sqlite_master WHERE type='index'` | 15 新索引 + 旧索引留存 |
| S4 计数 | 每表 `COUNT(*)` + `zhuli_fact` 状态分布 + `sqlite_sequence` 全量 | 行数闭合 |
| S5 内容指纹 | `zhuli_fact` 的 `id,subject,predicate,value,kind,confidence,status,observed_at,valid_from,valid_to,superseded_by,created_at,updated_at` 逐行导出（受控目录，不入库） | 逐字段保真比对 |
| S6 哈希 | 文件 SHA-256 | 证明原件未被改 |

### 9.3 迁移后核对（逐项，全部为硬门槛）

| # | 项 | 期望（本库） | SQL/方法 |
|---:|---|---|---|
| 1 | `user_version` | 17 | `PRAGMA user_version` |
| 2 | `integrity_check` | ok | `PRAGMA integrity_check` |
| 3 | 七张表 | 6 普通 + FTS + 5 影子表 | `sqlite_master` |
| 4 | 15 个显式索引 | 全部存在、`unique` 标志正确、`sql` 归一化后与 `EXPECTED_INDEXES` 全等 | `pragma_index_list` + `sqlite_master.sql` |
| 5 | 4 个业务自动索引 | 存在 | `sqlite_master` |
| 6 | `zhuli_fact` | **不存在** | `sqlite_master` |
| 7 | `zhuli_fact_v1` | 存在且 `COUNT(*)=2` | 同上 |
| 8 | item 行数 | 2，id 集合 `{1,2}` | `SELECT id FROM zhuli_memory_item ORDER BY id` |
| 9 | 状态 | id1=`SUPERSEDED` / id2=`ACTIVE` | 逐行 |
| 10 | 时间 | id1 `invalidated_at=1795596291064`、`valid_until=0`、`time_confidence='NONE'`；id2 `valid_until=0` | 逐行 |
| 11 | 替代链 | `superseded_by`: id1=2、id2=0；`relation(2,1,'SUPERSEDES')` 1 行 | 查询 |
| 12 | source / evidence | `source` 1 行、`evidence` 2 行且 role 全 `PRIMARY`；`source_id` 回填一致 | 查询 + 不变量 SQL |
| 13 | 向量 | `fact_vec` 1 行、space `hash-2gram-v1\|256`、`LENGTH(vec)=1024`；孤儿 0 | 查询 |
| 14 | 向量队列 | 0 行 | `COUNT(*)` |
| 15 | `sqlite_sequence` | `zhuli_memory_item` **恰好 1 行**、`seq=2`；全库同名重复 0 | `GROUP BY name HAVING COUNT(*)>1` |
| 16 | 后续 ID 不冲突 | 插入一条不指定 id 的新行 → 得 3；再插 → 4（测试库操作，不污染真实库） | `T-M46` |
| 17 | FTS | **按探测结果分支**：`TRIGGER` → `zhuli_memory_fts` 存在且 `COUNT(*)=2`、3 触发器齐全；`APP_SYNC`/`NONE` → FTS 对象可缺，但迁移**不得中止**，其余 19 项仍须全过。注意：本副本无 `inbox_fts` 只证明 FTS5/trigram 不可用，**不能推断 FTS4 一定不可用**（C1-A 的 TRIGGER 结论来自另一台设备） | 环境分支 |
| 18 | 10 组残留检查 | 全部 0 行（FTS 相关两组在 NONE 档跳过） | C0.2 §6.6 的 11 条 SQL |
| 19 | 5 条时间不变量 | 全部 0 | C0.2 §8.1 的 5 条 |
| 20 | 旧索引留存 | `idx_fact_slot/source/status` 挂在 `zhuli_fact_v1` 上 | `sqlite_master` |

### 9.4 失败注入（在**副本的副本**上做）

| 注入点 | 期望 |
|---|---|
| 第 1 行迁移抛异常 | T0 回滚：`user_version=16`、新表数 0、`zhuli_fact` 行数不变；`lastMigrationError` 非空 |
| 唯一索引预检前人为造 2 条同 `dedupe_key` 的 ACTIVE | `MigrationConflictException`，消息含表名/组数/键前缀（不含原文） |
| `journal_mode` 改为 `wal` 后打开 | `UnsupportedJournalModeException`，**0 个备份文件** |
| 备份目录不可写 | `BackupFailedException("mkdir")`，迁移不开始 |
| FTS 建表失败（人为占用同名表/注入异常） | 迁移**成功**，`ftsMode` 降档，其余 19 项仍成立 |
| `ALTER TABLE RENAME` 失败 | 整体回滚，`zhuli_fact` 仍是原名 |

> 预演已验证其中两条（`rehearsal_final.txt` §4：第 1 行抛错 → 回滚后 `user_version=16`、新表 0、旧表 11 行；以及真实库 2 行成功迁移）。但那是 Python 脚本，**不能替代 Kotlin 实现的失败注入**。

### 9.5 连续打开验证（防旧表复活）

```
迁移成功 → 关闭 → 打开 ×3
断言（每次）：
  zhuli_fact 不存在
  zhuli_fact_v1 COUNT(*) == 2
  sqlite_master 的对象名集合不变（`T-M41`/`T-M43`）
  new item COUNT(*) == 2
  user_version == 17
```

### 9.6 新装 vs 升级的结构一致性（`T-M49`）

| 步骤 | 做法 |
|---|---|
| 1 | 用 `onCreate` 建一个全新 v17 库（空库，`DB_VERSION=17`） |
| 2 | 用 `onUpgrade` 把真实副本迁到 v17 |
| 3 | 对两库分别取 `(name, unique, columns, where)` 四元组集合（15 显式索引）与 `PRAGMA table_info` |
| 4 | **集合全等**才算通过（不比数量：数量相等但列序不同仍是两种结构） |
| 5 | `sqlite_sequence` 内容**不参与**比对（新装库 item 表空，没有该行） |

### 9.7 规模与耗时（引用预演，仅作量级参考）

| 库 | 行数 | 预演耗时 | 产物体积 |
|---|---:|---:|---:|
| 真实副本 | 2 | 29 ms | 409,600 B |
| 边界样本 | 11 | 24 ms | 409,600 B |
| 5k | 5,002 | 1,058 ms | 6,041,600 B |
| 20k | 20,000 | 未在本次核对中复跑 | 23,052,288 B |

> 这些是**桌面 Python 预演**的数字，不能当作 Android 设备预算（v4.2.1 §5.13 明确禁止）。B1-2 必须在真机上测 `onUpgrade` 的 wall-clock，并写进报告。

---

## 10. 测试用例与验收条件

### 10.1 迁移与结构必测（沿用既有编号）

| 编号 | 场景 | 必须断言 |
|---|---|---|
| `T-M05` | 空 subject / 空 value | `skippedNoSubject=1`、`skippedNoValue=1`，两行都不在新表 |
| `T-M06` | 正常迁移行数 | `src == inserted + quarantined + skipped* + failed`，`failed=0` |
| `T-M08` | 迁移中途失败 | `user_version=16`、无新表 |
| `T-M09` | 迁移成功后结构 | `zhuli_fact` 不存在、`zhuli_fact_v1` 存在 |
| `T-M36` | 时间不变量 5 条 + 字段抽样 | `time_confidence` 全 `NONE`、`valid_until` 全 0、`pre_delete_status` 全空 |
| `T-M37` | `valid_to` 不得进 `valid_until` | 旧 `valid_to>0` 的行新 `valid_until=0`、`invalidated_at=valid_to` |
| `T-M40` | 新装库 | 7 表 + 15 索引；`zhuli_fact`/`zhuli_fact_v1` 都不存在；`user_version=17` |
| `T-M41` | 连开 3 次 | `zhuli_fact` 始终不存在；`zhuli_fact_v1` 行数不变 |
| `T-M42` | 直写旧表 | 抛 `SQLiteException`（含 `no such table`） |
| `T-M43` | `onOpen` 幂等 | 对象名集合与各表 `COUNT(*)` 不变 |
| `T-M44` | 重开后数据完整 | 12 个关键列抽检 + 向量行 + evidence 行数 |
| `T-M45` | v16 缺 `zhuli_fact` | 抛 → 回滚 → 不补建空表 |
| `T-M46` | 后续 ID 不冲突 | 新 id = `MAX+1`，sequence 恰好 1 行且 `seq>=MAX` |
| `T-M47` | 三个唯一索引 | 存在、`unique=1`、SQL 全等、试插重复三者都抛 `SQLiteConstraintException` |
| `T-M49` | 两路径结构一致 | 四元组集合全等 |
| `T-M52` | sequence 幂等 | 人为 3 行同名 → 跑两次 → 恰好 1 行、`seq` 不变 |
| `T-S48`/`T-S49`/`T-S62` | FTS 空表/部分缺失/快检异常 | 重建正确；异常 → `AppSync("quick_count_failed")` |
| `T-X02` | 未知状态反序列化 | `FactStatus.of("??") == UNKNOWN`，**不是 ACTIVE** |
| `T-X08` | 未知状态计数 | 映射计数 == SQL 计数（含 `NULL`） |

### 10.2 针对本报告三个缺口的**新增**用例（建议编号，待 B1-1B 确认）

| 编号 | 场景 | 断言 |
|---|---|---|
| `T-M53` | v17 库被 `DROP` 掉 `zhuli_memory_item` 后重开 | `onOpen` 把 6 张表 + 15 索引补回来（结构自愈；数据不回来） |
| `T-M54` | `ftsMode=NONE` 的设备上重开 v17 库 | 6 张基表仍被补建（证明基表自愈不依赖 FTS 分支） |
| `T-M55` | 唯一索引缺失（人为 `DROP`）且表内有重复 | `onOpen` 补建失败但**不崩溃**，`selfCheck` 报 `missing_index:idx_*` |
| `T-M56` | `invalidated_at` 边界：`status='SUPERSEDED'` 且 `valid_to=0` | 迁移结果 `invalidated_at=updated_at`（>0），不变量 #4 成立 |
| `T-M57` | `ref_table` 取值 | `source_type='INBOX'` 的行 `ref_table='inbox'`（若选 B-2 方案 A）；`independent_key` 前缀一致 |

### 10.3 验收条件（B1-1B 交付时必须全过）

1. `:app:testDevDebugUnitTest` 全绿，无新增 `@Ignore`。
2. `T-M40` / `T-M47` / `T-M49` 三条结构断言全过——**这是"两条路径结构一致"的唯一证据**。
3. 真实副本 + 边界样本（11 行）两库迁移：主恒等式成立、`failed=0`、10 组残留 0 行、5 条时间不变量 0 行、`integrity_check=ok`。
4. 连开 3 次：`zhuli_fact` 不存在、对象集不变。
5. 失败注入 6 项全过（§9.4）。
6. `grep -rn "TABLE_FACT" app/src/main` 只剩 2 个文件。
7. 备份：`journal_mode=wal` 时 0 文件 + 中止；正常时 `.tmp` 校验 + 改名 + 保留 3 份。
8. `DB_VERSION` 只改 1 处，`PRAGMA user_version` 不在迁移代码里写。

---

## 11. 设计与原文不一致项

> 分级：**B** = 阻断编码（须先裁定）；**D** = 文档级错误/过时（须同轮修正）；**O** = 观察项（记录，不修）。

| 编号 | 级 | 不一致 | 原文位置 | 应以谁为准 / 建议 |
|---|---|---|---|---|
| **B-1** | **B** | `onOpen` 的建表入口未定义；`ensureAll` 不建基表、需 4 参、NONE 档首行返回 | v4.2.1 §4.7/§4.8.1 vs §6.4.2 | 需裁定：拆 `ensureStructure`（推荐）/ 合并 / `onOpen` 直调 `createAll` |
| **B-2** | **B** | `ref_table='zhuli_fact'` 与 `ref_id`=inbox id 不符，且 C0.2 §2.3 词表写的是 `inbox` | C0.2 §2.3 vs §8.4；v4.2.1 §5.5 | 需裁定 A/B/C（§5.4） |
| **B-3** | **B** | `invalidated_at = valid_to ?: updated_at` 在 `valid_to=0` 时得 0，违反不变量 #4 | C0.2 §8.1 #23；v4.2.1 §5.3 | 改为 `valid_to>0 ? valid_to : updated_at`（一行文案） |
| **D-1** | D | `§4.8.1` 七处列数与实测不符：`inbox` 21→**17**、`chat` 7→**4**、`ledger` 6→**7**、`cloud_log` 10→**8**、`embedding` 6→**3**、`agent_task` 13→**14**、`tool_exec` 6→**11** | v4.2.1 §4.8.1 | 以 `PRAGMA table_info` 实测为准（本报告 §2.2）；`reminders/skills/source_rule/entity/step/fact/vec/queue` 8 项一致 |
| **D-2** | D | C0.2 说 WAL 下先 `wal_checkpoint(FULL)` 再备份；v4.2.1 已改为**直接拒绝** | C0.2 §8.10 vs v4.2.1 §5.12 | **以 v4.2.1 为准**；C0.2 §8.10 末段应标注"已被取代"，否则实现者会去写 checkpoint |
| **D-3** | D | C0.2 说 sensitivity "只看 content"；v4.2.1 改为 `subject/predicate/value/source_text` 四字段 | C0.2 §8.1 #28 vs v4.2.1 §5.3.2 | **以 v4.2.1 为准**（只查 content 会漏掉短信原文里的卡号） |
| **D-4** | D | C0.2 §8.9 第 1 步"5 个普通索引"、第 10 步只建 `idx_mi_dedupe`；v4.2.1 是 12 个普通 + 3 个唯一 | C0.2 §8.9 vs v4.2.1 §4.4/§5.2 | **以 v4.2.1 为准**（P0-1 修复） |
| **D-5** | D | C0.2 §8.6 只写 `UPDATE sqlite_sequence`；v4.2.1 追加"收敛重复行 + `UPDATE`→`changes()`→`INSERT` + 断言" | C0.2 §8.6 vs v4.2.1 §5.7 | **以 v4.2.1 为准**（`INSERT OR REPLACE` 在无唯一约束的表上会加行） |
| **D-6** | D | C0.2 §8.9 第 13 步写 `PRAGMA user_version=17`；v4.2.1 X-6 取消，交给 helper | C0.2 §8.9 vs v4.2.1 §3.4 X-6 | **以 v4.2.1 为准**（事务内改版本号会造出"版本 17 但没提交"的窗口） |
| **D-7** | D | C0.2 §2.1 的"7 张表"含 FTS，但 `§4.8.1` 又按 24 张表统计，其中把 `inbox_fts` 也算一张；本机 `inbox_fts` 根本不存在 | C0.2 §2.1 / v4.2.1 §4.8.1 | 统一口径：**7 = 6 普通 + 1 FTS**；24 张是"逻辑表数"，不含影子表，且随 ROM 浮动 |
| **D-8** | D | `§2.10` "57 条（54 在 C1，7 已在 C1-A）"算术不自洽（54+7=61），与 §11.4 的"313+54"也对不上 | v4.2.1 §2.10 / §11.4 | B1-4 前重算基线（57−3=54；若 7 已实现，剩余 47），否则退出标准会算错 |
| **O-1** | O | 全部 7 张表**没有 CHECK 约束**：`status`/`memory_type`/`cardinality`/`role` 都靠 Kotlin 枚举保证 | C0.2 §2.2-§2.5 | 保持（与 C0.2 一致）；但 `UNKNOWN` 不得落库、`sensitivity=2` 不得落库这两条只能靠代码约束，测试必须覆盖 |
| **O-2** | O | `onOpen` 的自愈覆盖不对称（不补 chat/reminders/ledger/cloud/skills/embeddings） | `InboxDatabase.kt:335-340` | 既有问题；v17 不扩大范围，但不要宣称"onOpen 自愈全库" |
| **O-3** | O | `onUpgrade` 的 `began` 分支日志写 "upgrade runs without transaction"，实际永远在 T0 内 | `InboxDatabase.kt:680-682` | 改文档/文案，不改语义 |
| **O-4** | O | 迁移后 `zhuli_fact` 的 3 个索引随表改名留存，C0.2/v4.2.1 都未写"是否 drop" | 两者均未提 | C1 保留（只读表），C3 随表清理；写进 `MemoryMigrator` KDoc |

---

## 12. 未解决风险

| 编号 | 风险 | 影响 | 缓解 |
|---|---|---|---|
| R-1 | B-1/B-2/B-3 未裁定就开工 | 实现者只能猜；`ref_table` 猜错要重算全表键 | **本报告判 BLOCKED**，裁定后再进 B1-1B |
| R-2 | 真实副本只有 2 行，覆盖不了合并/隔离/未知状态/断链 | 迁移逻辑的边界分支没被真实数据验证 | 必须叠加 `v16_edge.db`（11 行）+ 合成夹具（§5.9 的 6 种终态各 1 条） |
| R-3 | `onOpen` 补建索引时若表内已有重复 → 抛异常 → 开库崩溃循环 | App 起不来 | 补建必须逐对象 `try/catch` + `selfCheck` 报告，不向上抛 |
| R-4 | 备份的 `integrity_check` 在 T0 内打开第二个只读连接 | 极端 ROM 上可能锁冲突 | 副本是独立文件，理论安全；B1-2 在真机验证；失败即中止（不会半迁移） |
| R-5 | 本机 FTS5 不可用、`inbox_fts` 不存在；FTS4 在部分 ROM 也可能不可用 | 迁移第 7-9 步必走降级；验收要覆盖 `NONE` 档 | 迁移把 FTS 失败设计为"不中止"；`T-M54` 专测 NONE 档 |
| R-6 | 迁移耗时未在真机测 | 2 万行可能 3-12s，启动期卡顿 | B1-2 真机测；超过阈值考虑分步/后台（不在 C1 改架构） |
| R-7 | `zhuli_fact_v1` 保留期内，任何残留写旧表代码会静默写空表（若 onOpen 被改回） | fail-loud 失效 | `T-M41`/`T-M42` + `grep TABLE_FACT` 双保险 |
| R-8 | `SensitiveGuard.redact` 对 SENSITIVE 行的 `excerpt` 不保证 100% | 摘录可能残留部分敏感信息 | 既定 U-14，与 C3 备份清理同批；C1 只承诺 SECRET 整行不迁 |
| R-9 | 将来切 WAL 会使备份时机与连接池假设失效 | 备份不完整 | 既定 U-13；当前用"拒绝 WAL"挡住，不在 C1 兼容 |
| R-10 | 迁移日志若误带 `dedupe_key` / `id` / 原文 | 敏感派生数据外泄 | §5.10 已禁止；测试断言日志字段白名单 |

---

## 13. 结论

### 13.1 判定：**BLOCKED**

- **设计主体是可靠的**：七张表、15 个索引、16 列映射、行数恒等式、事务与备份语义都已闭合，且与 C0.2/v4.2.1 原文逐条对得上（15/15 索引零差异，40/12/6/6/10/3 列数零差异）。
- **但不能开始编码**：`onOpen` 的建表入口（B-1）在文档里自相矛盾到"按原文写无法编译"的程度；`ref_table`（B-2）会写进唯一键；`invalidated_at`（B-3）的边界会让不变量 #4 在脏数据上变红。这三点都属于"必须选一个"的决策，不是实现细节。

### 13.2 建议的批准方式

| 步骤 | 内容 | 谁 |
|---|---|---|
| 1 | 裁定 B-1（推荐选项 A：拆 `ensureStructure` + `ensureFts`） | 负责人 |
| 2 | 裁定 B-2（推荐选项 A：`ref_table` 按真实载体） | 负责人 |
| 3 | 确认 B-3 改为 `valid_to>0 ? valid_to : updated_at` | 负责人 |
| 4 | 同轮修掉 D-1…D-8（纯文档，不动代码） | 文档修订 |
| 5 | 重新出 B1-1A 增补页（只列三项裁定与受影响段落） | 审查 |
| 6 | 批准进入 B1-1B（`MemorySchema.kt` + `MemoryMigrator.kt` + 5 处 `InboxDatabase` 改动） | 负责人 |

### 13.3 明确不建议现在做的事

- 不建议"先按最合理的猜法写代码，边写边改文档"——B-2 的键一旦写入，改起来要重算 `idx_ms_dedupe` 与全表 `independent_key`。
- 不建议在 B1-1B 同时改 FTS 探测（B1-2 范围），否则失败注入无法区分是迁移还是探测的问题。
- 不建议把 `zhuli_fact_v1` 的清理提前到 C1（C0.2 D-2 未决，且 C1 没有硬删入口）。

---

## 附录 A　真实库取证原始输出（摘要）

```
file: Temp/c01/zhuli_inbox_v16_real.db  274432 bytes
user_version: 16 | journal_mode: delete | page_size: 4096 | integrity_check: ok

tables(17): android_metadata, chat_log, cloud_log, embeddings, inbox, ledger,
            reminders, skills, source_rule, sqlite_sequence, zhuli_agent_step,
            zhuli_agent_task, zhuli_entity, zhuli_fact, zhuli_fact_vec,
            zhuli_tool_exec, zhuli_vec_queue
triggers: (none)
indexes on zhuli_fact: idx_fact_slot, idx_fact_source, idx_fact_status
autoindexes: source_rule_1, zhuli_agent_step_1, zhuli_agent_task_1,
             zhuli_entity_1, zhuli_tool_exec_1
AUTOINCREMENT tables(9): chat_log, cloud_log, inbox, ledger, reminders,
             skills, zhuli_agent_step, zhuli_entity, zhuli_fact

zhuli_fact rows=2  status: ACTIVE=1, SUPERSEDED=1
                   source_type: USER=2   kind: OTHER=2
zhuli_fact_vec rows=1   zhuli_vec_queue rows=0   zhuli_entity rows=0
zhuli_agent_task rows=28 (14 cols)  zhuli_agent_step rows=186 (13 cols)
zhuli_tool_exec rows=51 (11 cols)

sqlite_sequence: cloud_log=6, zhuli_agent_step=186, zhuli_entity=2, zhuli_fact=2
duplicates: none
```

## 附录 B　15 索引与 C0.2 逐条比对结果

| 比对项 | 结果 |
|---|---|
| C0.2 中 `CREATE [UNIQUE] INDEX` 语句数 | **15**（脚本提取，非人工清点） |
| 与 v4.2.1 §4.5.1 表格的数量 | 15 = 15 |
| 名称一致 | 15/15 |
| 表一致 | 15/15 |
| 列顺序一致 | 15/15 |
| 唯一性标志一致（3 个 UNIQUE） | 3/3 |
| `WHERE` 条件一致（2 个部分索引） | 2/2（`idx_mi_dedupe` / `idx_mev_primary`） |
| 同名不同定义 | **0** |
| 与 v16 现有索引重名 | **0** |

## 附录 C　待负责人裁定的三个问题（可直接勾选）

**B-1｜`onOpen` 用哪个函数建表？**

- [ ] A（推荐）拆 `MemorySchema.ensureStructure(db)`（6 表 + 15 索引，逐项 `try/catch`，不抛）+ 现 `ensureAll` 改名 `ensureFts(db, prefs, cache, requested)`；`onOpen` 只调前者
- [ ] B `ensureAll` 增加"先建结构"前置段，`requested` 改可空
- [ ] C `onOpen` 直调 `createAll(db, withUnique=true)` 并包 `try/catch`

**B-2｜`ref_table` 写什么？**

- [ ] A（推荐）按真实载体：`INBOX→'inbox'`，`USER/SYSTEM/INFERRED→''`；同步改 §5.5/§8.4 与 `independent_key`
- [ ] B 保留 `'zhuli_fact'`，但明文声明"迁移期标签，不用于动态查表"
- [ ] C 新增 `ref_kind` 列（超出 C1 范围）

**B-3｜`invalidated_at` 边界**

- [ ] 采纳 `valid_to>0 ? valid_to : updated_at`（推荐，且与不变量 #4 自洽）
- [ ] 维持 `valid_to ?: updated_at`，并把不变量 #4 放宽为"允许 0"

---

> **停机声明**：本报告完成后停止，不进入 B1-1B、不创建 `MemorySchema.kt` / `MemoryMigrator.kt`、不改 `DB_VERSION`、不执行迁移。等待负责人审查三项裁定后另行授权。

