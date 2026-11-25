# 批次 C1-A：Android SQLite / FTS4 兼容性验证报告

> 任务：只回答一个问题——**记忆系统的 FTS4 索引同步该走 `TRIGGER`、`APP_SYNC` 还是 `NONE`**。
> 不实现 `MemoryStore`，不改生产代码，不动 `DB_VERSION`。
>
> 本轮结论：**`TRIGGER`**。七项验证在真实 Android `SQLiteDatabase`（SQLite 3.42.0）上逐条通过，
> 但附带一条**硬约束**写进 C1-B：`detectFtsMode` 必须做**功能性探测**（写一行再核对索引），
> 不能只看 `CREATE TRIGGER` 是否抛错 —— 见第 5 节，这条是实测逼出来的，不是保守估计。

---

## 0. 结论摘要

| 环境 | SQLite | 七项验证 | 触发器 | 判定 |
|---|---|---|---|---|
| **真实 Android**（REDMI 25060RK16C / API 34 / x86_64） | **3.42.0** | **7/7 通过** | 3/3 创建成功 | **`TRIGGER`** |
| Robolectric 4.15.1 · `NATIVE` | 3.32.2 | 7/7 通过 | 3/3 | 与设备**同向**，但版本不同，**不作证据** |
| Robolectric 4.15.1 · `LEGACY` | 3.8.7 | 7/7 通过 | 3/3 | 与设备**同向**，但版本差 4 个大版本，**不作证据** |

三档方案里选 `TRIGGER` 的依据只有第一行。第二、三行是**反证材料**：它们证明"Robolectric 过了"这件事
本身不构成兼容性证据（第 5 节有一个具体的、可复现的反例）。

---

## 1. 基线与范围

| 项 | 值 | 核查方式 |
|---|---|---|
| 分支 | `master` | `git rev-parse --abbrev-ref HEAD` |
| 基线 commit | `a0e1d52`（C0.2 架构文档定稿） | `git log --oneline -3` |
| 本轮开始前工作区 | 干净，仅 `Temp/` 未跟踪 | `git status --porcelain` |
| 架构依据 | `docs/批次C0.2_记忆系统架构设计最终版.md` §14.1 / §2.6 / §12.2 | 逐字对照 DDL |
| `DB_VERSION` | **16**（未改） | `InboxDatabase.kt:50` |
| `app/` 生产代码改动 | **0 个文件** | `git diff --name-only HEAD -- app/src/main` |

**范围**：只新增两个测试文件 + 本报告。未提交 `Temp/`（含 30MB 库副本与本轮日志）。

---

## 2. 验证环境

### 2.1 真实 Android（权威口径）

| 项 | 值 |
|---|---|
| 设备 | REDMI `25060RK16C`（模拟器镜像），`x86_64` |
| Android | **14 / API 34** |
| `sqlite_version()`（进程内 `SQLiteDatabase`） | **3.42.0** |
| `sqlite3 --version`（设备 CLI，仅作交叉核对） | 3.42.0 |
| `trusted_schema` | **1**（默认） |
| `secure_delete` | 1 |
| `journal_mode` | `truncate`（新建库默认） |
| `PRAGMA defensive` | 不可读（不是合法 pragma，读取无结果） |
| `sqlite_compileoption_used()` | **不存在**（Android 构建裁剪了该函数） |

两条值得记住的环境事实：

1. **不能靠编译选项判断能力**。`SELECT sqlite_compileoption_used('ENABLE_FTS4')` 在 Android 上直接报
   `no such function`。所以 `detectFtsMode` 只能"试着建一张表"，不能"查一下编译标志"。
2. **不能靠 `PRAGMA defensive` 读状态**。它读不出来（无结果），说明"当前连接是不是防御模式"没有可观测入口 ——
   这也解释了为什么必须功能性探测，而不是读配置。

### 2.2 Robolectric（快速预检，非证据）

| 模式 | `sqlite_version()` | `secure_delete` | `trusted_schema` | 备注 |
|---|---|---|---|---|
| `NATIVE` | **3.32.2** | 1 | 1 | AOSP 构建的原生库；与设备的 3.42.0 **不是同一个二进制** |
| `LEGACY` | **3.8.7** | 0 | 不可读（无此 pragma） | sqlite4java；比目标设备老 4 个大版本 |

Robolectric 版本：`4.15.1`（`gradle/libs.versions.toml:13`）。两者 `journal_mode` 都报 `memory`，
与设备的 `truncate` 不同 —— 这是 Robolectric 的测试隔离策略，不是设备行为。

---

## 3. 七项验证逐项结果

测试方法：每个用例**自建临时库**（`cacheDir` 下，测完连 `-journal/-wal/-shm` 一起删）、
**自带全部前置步骤**（建表 → 建 FTS → 建触发器 → 造数据），因此可单独运行、不依赖顺序。

| # | 验证项 | 真实 Android | Robolectric NATIVE | Robolectric LEGACY | 证据等级 |
|---|---|---|---|---|---|
| ① | FTS4 虚拟表 + 5 个影子表 | **通过**：`sqlite_master` 为 `{zhuli_memory_fts, _content, _docsize, _segdir, _segments, _stat}`，直接写入后 `MATCH '"会议" "提醒"'` 命中 | 通过 | 通过 | **已通过真实 Android 验证** |
| ② | 三个触发器创建 | **通过**：`ai`/`au`/`ad` 全成功，`sqlite_master` 恰好三条 | 通过 | 通过 | **已通过真实 Android 验证** |
| ③ | INSERT 后 MATCH | **通过**：`item id=1, 命中=[1], fts 行数=1, content=1` | 通过 | 通过 | **已通过真实 Android 验证** |
| ④ | UPDATE `search_text` 同步 | **通过**：旧词 `[]`、新词 `[1]`、`fts 行数=1`、无重复 rowid | 通过 | 通过 | **已通过真实 Android 验证** |
| ⑤ | `status=DELETED` 软删 | **通过**：`命中=[]`、`fts 行数=0`、`content=0`、原表行仍在（`status=DELETED`） | 通过 | 通过 | **已通过真实 Android 验证** |
| ⑥ | 恢复 `ACTIVE` | **通过**：连续两轮"删→恢复"，每轮 `命中=[1]`、`fts 行数=1`、无重复 | 通过 | 通过 | **已通过真实 Android 验证** |
| ⑦ | 物理 `DELETE` | **通过**：`命中=[]`、`fts 行数=0`、`content=0`、孤儿 `0` | 通过 | 通过 | **已通过真实 Android 验证** |

每项的具体断言（不是"打印日志"，是 `assertEquals` / `assertTrue`）：

- ① 影子表**集合完全相等**（不是"至少存在"）；虚拟表可直接读写并命中。
- ② 触发器集合**恰好等于**三条（多一条少一条都失败）。
- ③ `命中` 必须**恰好等于** `[id]`；FTS 行数必须为 1；`content` 影子表必须同为 1。
- ④ 旧词必须 **0 命中**；新词必须命中该 id；FTS 行数仍为 1；`GROUP BY rowid HAVING COUNT(*)>1` 必须为 0。
- ⑤ 必须验 **MATCH 结果**（不是只读原表 status）；同时断言原表行仍存在（软删不是物理删）。
- ⑥ 两轮循环，每轮都断言 `fts 行数=1` 且无重复 —— 直接排除"恢复一次多一条索引"。
- ⑦ 必须 `fts 行数=0` **且** 孤儿查询 `0` **且** 原表 `0`。

### 3.1 执行耗时（供 C1-B 参考，非性能结论）

| 环境 | 用例数 | 套件总耗时 |
|---|---|---|
| 真实 Android | 9（7 项 + 诊断 + 负向对照） | **1.451 s** |
| Robolectric NATIVE | 9 | 2.173 s |
| Robolectric LEGACY | 9 | 3.159 s |

设备上单项在 11–50 ms 之间。这些是**空库**耗时，不含 2 万行迁移（那是 D2 的事）。

---

## 4. 触发器创建与执行

### 4.1 创建（三环境均 3/3 成功）

用的 DDL 与 C0.2 §2.6 **逐字一致**（含 `AFTER UPDATE OF search_text, status` 与
`DELETE FROM … WHERE rowid = old.id`，即 C0.1 实测修正后的写法）。设备侧输出：

```
[OK]   trigger ai 创建成功
[OK]   trigger au 创建成功
[OK]   trigger ad 创建成功
sqlite_master 里的触发器: [zhuli_memory_fts_ad, zhuli_memory_fts_ai, zhuli_memory_fts_au]
```

没有任何一条出现 `trusted_schema` 相关拒绝，也没有 `unsafe use of virtual table`（默认配置下）。

### 4.2 执行（四条写路径全部正确）

| 路径 | 触发条件 | 结果 |
|---|---|---|
| INSERT | `ai` | 索引 +1，立即可 MATCH |
| UPDATE `search_text` | `au` 先删后插 | 旧词失效、新词命中、行数不变 |
| UPDATE `status` → `DELETED` | `au`（`status` 在 `OF` 列表里） | 索引 -1，MATCH 0 命中 |
| UPDATE `status` → `ACTIVE` | `au` 重插 | 索引 +1，重新命中，**两轮循环不累积** |
| DELETE（物理） | `ad` | 索引 -1，无孤儿 |

**"创建成功但执行失败"的情况确实存在**，但**不在默认配置下** —— 见下一节。

---

## 5. 关键失败路径：负向对照（`trusted_schema=OFF`）

这是本轮**最有价值的发现**，也是唯一一条会改变 C1-B 写法的结论。

做法：在**建表之前**对连接执行 `PRAGMA trusted_schema=OFF`（不是建完再改，避免命中已编译的触发器程序），
然后照常建表、建 FTS、建三个触发器、写一行。设备（SQLite 3.42.0）实测：

```
已设置 trusted_schema=OFF
[OK]   trigger ai 创建成功
[OK]   trigger au 创建成功
[OK]   trigger ad 创建成功
A| 触发器失败=0, 写入=抛错(SQLiteException: unsafe use of virtual table "zhuli_memory_fts"
   (code 1 SQLITE_ERROR), while compiling:
   INSERT INTO zhuli_memory_item(search_text, status) VALUES (?, ?)), FTS 增量=0
B| 恢复 trusted_schema=ON 后重建: 触发器失败=0, 写入=未抛错, FTS 增量=1
结论：坏环境在**写入**阶段失败（fail-loud，探测需写一行）
```

三个必须记住的点：

1. **`CREATE TRIGGER` 在坏环境下照样成功**。只看 DDL 返回值会把坏环境误判成 `TRIGGER`。
2. **失败发生在第一次写入**，错误是 `unsafe use of virtual table`（`SQLITE_ERROR`）。
   也就是说：如果 `detectFtsMode` 判断错了，用户第一次存记忆就会崩 —— 或者更糟，
   如果写路径吞掉异常，就会得到一个永远搜不到东西的库。
3. **恢复 pragma 后连接仍可用**（B 组：重建后 `FTS 增量=1`），说明这个失败是可逆的配置问题，
   不是库损坏。这一点支持"探测失败就降级到 `APP_SYNC`，不需要重建库"。

Robolectric 两个模式**都没能复现**这个失败：同样的 pragma 下 `写入=未抛错, FTS 增量=1`。
原因未查明（可能与其构建的连接配置或 pragma 生效范围有关）。**这条差异本身就是 A-2 的答案**：
Robolectric 通过 ≠ 设备通过，方向都可能相反。

---

## 6. 风险判断

### A-1｜`SQLiteDatabase` 是否允许触发器 `DELETE FROM` FTS4 虚拟表

**已证实允许**（在默认配置的设备上）。证据等级：**已通过真实 Android 环境验证**。
`ai`/`au`/`ad` 三条触发器内对虚拟表的 `INSERT` / `DELETE` 全部成功执行，七项结果全对。
CLI 上"必须 `PRAGMA trusted_schema=ON`"的说法在 Android 上**不成立**——默认就是开的（实测 `=1`）。

但注意：这条"允许"是**有条件的**，条件就是 `trusted_schema=1`。见 A-6。

### A-2｜Robolectric 的 SQLite 是否支持 FTS4、是否与设备一致

**支持，但不一致**。证据等级：**已通过真实 Android 验证（对比结论）**。

| 维度 | 设备 | Robolectric NATIVE | Robolectric LEGACY |
|---|---|---|---|
| 版本 | 3.42.0 | 3.32.2 | 3.8.7 |
| FTS4 建表 + 5 影子表 | 通过 | 通过 | 通过 |
| 触发器四条路径 | 通过 | 通过 | 通过 |
| `trusted_schema=OFF` 下的表现 | **写入抛错** | 无影响 | pragma 不存在 |
| `journal_mode` | truncate | memory | memory |

结论：Robolectric 可以用来**快速排除结构性错误**（比如触发器写法本身不合法），
但**不能**用来判定兼容性，尤其不能用来判定"在目标 ROM 上能不能跑"。
C0.2 §12.2 A-2 里写的"本版不假设它成立"，本轮用数据落实了。

### A-6｜`trusted_schema` / `DBCONFIG_DEFENSIVE` 跨 API 级别是否一致

**未验证，且本机无法制造坏环境来做对照**。证据等级：**尚未验证**。

已知：
- 设备 API 34 默认 `trusted_schema=1`；Robolectric NATIVE（同为 AOSP 构建）也是 `1`。
- `PRAGMA defensive` 在设备上读不出结果，**没有可观测入口**。
- 手工 `PRAGMA trusted_schema=OFF` 后，设备会以写入抛错的方式失败（第 5 节），
  说明"更严格的策略"确实是这套方案的真实威胁，不是理论风险。

因此：**`ftsMode` 不能是编译期常量，也不能只靠 `CREATE TRIGGER` 的返回值判断**。
这条从"设计建议"升级为"实现约束"，写进第 7 节。

---

## 7. 结论与它对 C1-B 的约束

### 7.1 最终结论

**`TRIGGER`**。

判定依据（严格按指令第六节的条件逐条对照）：

| 条件 | 结果 |
|---|---|
| 真实 Android `SQLiteDatabase` 七项全过 | 是（9/9 用例通过，含 7 项业务断言） |
| 三个触发器均成功创建 | 是（3/3，`sqlite_master` 恰好三条） |
| INSERT / UPDATE / 软删 / 恢复 / 物理删均正确维护索引 | 是（含两轮恢复不重复、无孤儿） |
| 不存在依赖未验证配置才能运行的隐患 | **有条件满足**：依赖 `trusted_schema=1`，而该值无法预先读取 → 必须运行时探测 |

最后一行不是否决，而是把风险从"架构不确定"降级为"实现时必须探测"。所以结论仍是 `TRIGGER`，
但 `detectFtsMode` 的实现方式被这一轮**限定**了。

### 7.2 写进 C1-B 的三条硬约束

1. **`detectFtsMode` 必须是功能性探测，不是 DDL 探测。**
   顺序：建表 → 建 FTS → 试建三个触发器 → **在事务里插一条哨兵行，核对 FTS 是否多了一行** →
   删掉哨兵行 → 回滚或提交 → 三档结论。
   只要"触发器建成功但索引没更新"或"插入抛 `unsafe use of virtual table`"，一律落 `APP_SYNC`。
2. **探测必须在 `onCreate`/`onOpen` 的同一连接上做**，不能另开连接测完再回来用
   （第 5 节已证明结果依赖连接级 pragma 状态）。
3. **结论落盘 `SharedPreferences`，并带一个"上次探测版本"**；升级或首次安装时重探一次，
   避免把某台设备的结论带到另一台。

### 7.3 `APP_SYNC` 的可行性（顺带确认，但本轮不实现）

第 5 节的 B 组已经证明：**索引同步失败不损坏数据**，只是索引落后；恢复配置后重建即可。
这说明"应用层显式同步"作为降级路径在数据层是安全的（写 item 成功、索引另写一次，
失败也不影响主数据）。**本轮没有实现任何同步逻辑**，也不创建 `MemoryStore`。

### 7.4 `NONE` 的适用性

本机不适用：FTS4 建表与检索都可用。保留 `NONE` 这一档仍然必要（老 ROM / 裁剪构建），
但它不是本轮的结论。

---

## 8. 命令、日志与异常原文

### 8.1 命令

```bash
# 编译两个测试源集（不跑）
JAVA_HOME="C:/Program Files/Android/Android Studio/jbr" ./gradlew --no-daemon \
  :app:compileDevDebugUnitTestKotlin :app:compileDevDebugAndroidTestKotlin \
  -x :app:buildWebUi -x :speech:downloadSherpaOnnxAar

# Robolectric：两种 SQLite 模式各跑九项
./gradlew --no-daemon :app:testDevDebugUnitTest \
  --tests "com.zhuli.memory.MemoryFtsRobolectric*" \
  -x :app:buildWebUi -x :speech:downloadSherpaOnnxAar

# 真实 Android：只跑这一个测试类
./gradlew --no-daemon :app:connectedDevDebugAndroidTest \
  -Pandroid.testInstrumentationRunnerArguments.class=com.zhuli.memory.MemoryFtsInstrumentedTest

# 需要 stdout 时（Gradle 的 connected 报告不保留 println）
adb -s emulator-5554 logcat -c
adb -s emulator-5554 shell am instrument -w -e class com.zhuli.memory.MemoryFtsInstrumentedTest \
  lastchat.rikkafork.cocolal.dev.test/androidx.test.runner.AndroidJUnitRunner
adb -s emulator-5554 logcat -d -v brief
```

### 8.2 原始产物（未入库，路径相对仓库根）

| 文件 | 内容 |
|---|---|
| `Temp/c1a/c1a_robolectric.log` | Robolectric 两次运行（8 项版 + 9 项版）的完整 Gradle 输出 |
| `Temp/c1a/c1a_instrumented.log` | connected 运行的完整 Gradle 输出 |
| `Temp/c1a/c1a_device_stdout.txt` | `am instrument` 原始输出（`OK (9 tests)`） |
| `Temp/c1a/c1a_device_logcat.txt` | 设备 logcat（含全部 `System.out`） |
| `Temp/c1a/c1a_device_println.txt` | 上面提取出的测试打印（82 行） |
| `app/build/outputs/androidTest-results/connected/debug/flavors/dev/TEST-emulator-5554 - 14-_app-dev.xml` | 设备端 JUnit XML：`tests=9 fail=0 err=0 time=1.451` |
| `app/build/test-results/testDevDebugUnitTest/TEST-com.zhuli.memory.MemoryFtsRobolectric*.xml` | Robolectric 两份 XML（各 `tests=9 fail=0`） |

### 8.3 本轮出现的异常（都不是测试失败）

| 位置 | 异常原文 | 性质 |
|---|---|---|
| 设备 step8 | `android.database.sqlite.SQLiteException: unsafe use of virtual table "zhuli_memory_fts" (code 1 SQLITE_ERROR)` | **预期行为**，负向对照的观测对象 |
| 设备/Robolectric step0 | `no such function: sqlite_compileoption_used` | Android 构建裁剪该函数；诊断项已捕获并记录，不影响判定 |
| 设备/Robolectric step0 | `java.lang.IllegalStateException: 查询无结果: PRAGMA defensive` | 同上是不可读 pragma，诊断项捕获 |
| 首次编译 | `Unresolved reference 'isWritable'` | 我写的诊断行用了框架不暴露的属性，已删掉该行改为打印库路径 |

**没有一条测试失败**（device 9/9、Robolectric 9/9 × 2）。所有失败路径都是被断言覆盖的**预期抛错**，
或被 try/catch 记录成诊断数据的**环境缺失**。

---

## 9. 修改文件清单

只有两个新增测试文件 + 本报告，**零生产代码改动**。

| 文件 | 行数 | 说明 |
|---|---|---|
| `app/src/androidTest/java/com/zhuli/memory/MemoryFtsInstrumentedTest.kt` | 新增 | 权威口径：真实 `SQLiteDatabase` 上 7 项 + 诊断 + 负向对照 |
| `app/src/test/java/com/zhuli/memory/MemoryFtsRobolectricTest.kt` | 新增 | 快速预检：同 7 项 + 诊断 + 负向对照，**两个类**分别跑 `NATIVE` / `LEGACY` |
| `docs/批次C1-A_FTS4兼容性验证报告.md` | 新增 | 本文件 |

自查命令与预期：

```bash
git status --porcelain                 # 只有上述三个文件（Temp/ 仍为未跟踪，不提交）
git diff --stat HEAD -- app/src/main   # 空
git diff --check                       # 无空白错误
grep -n "DB_VERSION" app/src/main/java/com/zhuli/core/InboxDatabase.kt   # 仍为 16
```

---

## 10. C1-B 的进入条件与未解决问题

### 10.1 进入条件（建议全部满足再开工）

1. C0.2 §10.1 的 7 个文件按 `TRIGGER` 方案实现，**并包含 7.2 的功能性探测**。
2. 探测结果三档都要有测试：`TRIGGER`（正常）、`APP_SYNC`（模拟触发器失效）、`NONE`（模拟建表失败）。
   其中"模拟触发器失效"可以直接复用本轮 step8 的手法（`trusted_schema=OFF`），
   这是目前唯一已知能稳定复现 `APP_SYNC` 场景的手段。
3. `selfCheck` 必须包含"item 活着但 FTS 没有"这一条（本轮 step8 的 A 组正是这种状态）。
4. 迁移脚本先灌数据后建触发器（C0.2 §2.6），迁移后跑一次"索引行数 = 非 DELETED 行数"的校验。

### 10.2 未解决问题

| # | 问题 | 影响 | 建议 |
|---|---|---|---|
| U-1 | 跨 API 级别 / 跨 ROM 的 `trusted_schema` 默认值未知（A-6） | 决定 `detectFtsMode` 是否会在某些设备上误判 | 功能性探测已在设计上覆盖；需要在 1–2 台真机上补验（本机是模拟器镜像） |
| U-2 | Robolectric 为何复现不出 `trusted_schema=OFF` 的失败 | 不影响结论，但影响"能否用 Robolectric 回归 FTS 行为" | 暂按"不能"处理；FTS 行为只在 `androidTest` 里回归 |
| U-3 | 真机（努比亚 Z70 Ultra / Android 16 / SQLite 版本未知）未验 | 目标机型可能有不同 SQLite | 建议在 C1-B 的 D3/D4 里补一次；本轮只有模拟器 |
| U-4 | 2 万行迁移时触发器的写入放大未测 | 影响迁移耗时（D2 ≤3s） | C0.2 §2.6 已定"迁移期先灌后建触发器"，本轮未做规模验证 |
| U-5 | `PRAGMA defensive` 无法读取 | 无法在日志里记录"当前是否防御模式" | 用功能性探测替代；日志里改为记录探测结论 |

### 10.3 明确没有做的事

- 没有创建 `MemoryStore` / `MemorySchema` / `MemoryMigrator`，没有新增任何生产文件。
- 没有改 `DB_VERSION`、没有写迁移、没有碰 Room/宿主/UI/Manifest。
- 没有改 C0.2 架构文档。
- 没有把 `Temp/` 里的预演产物或本轮日志提交入库。
- 没有推送远端、没有动 tag、没有 amend/rebase/reset。

---

## 附：证据分级（按指令要求区分四类）

| 结论 | 证据等级 |
|---|---|
| FTS4 在 Android 3.42.0 上可建表、5 个影子表齐全 | 已通过真实 Android 环境验证 |
| 三个触发器在默认配置下可创建并正确维护索引（5 条写路径） | 已通过真实 Android 环境验证 |
| `trusted_schema=OFF` 时"建得成、写才炸" | 已通过真实 Android 环境验证（负向对照） |
| Robolectric NATIVE(3.32.2) / LEGACY(3.8.7) 行为与设备**不一致** | 已通过真实 Android 环境验证（对比） + Robolectric 运行结果 |
| `ftsMode` 必须运行时探测 | 由上述实测推出（设计约束，非环境断言） |
| 跨 API / 跨 ROM 的默认值一致性（A-6） | **尚未验证** |
| 真机（Android 16）上的行为 | **尚未验证** |
| 2 万行迁移耗时与触发器写入放大 | **尚未验证**（仅有 C0.2 的 host 预演数据，属"仅静态/离线证据"） |
