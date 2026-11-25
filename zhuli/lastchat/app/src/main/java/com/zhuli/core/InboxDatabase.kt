package com.zhuli.core

import android.content.ContentValues
import android.content.Context
import android.database.Cursor
import android.database.sqlite.SQLiteDatabase
import android.database.sqlite.SQLiteOpenHelper
import com.zhuli.core.ZhuliLog as Log

/**
 * 收件箱 + 提取结果数据库。
 *
 * 第 4 步起 v3：在收件箱行上补「分类 / 截止时间 / 金额 / 备注」四列，
 * 提取流水线（[ExtractPipeline]）原地写回，不另起表 —— 一条消息只有一行，查询不用 JOIN。
 *
 * 第 15 步安全加固时：把本类换成 SQLCipherOpenHelper（构造参数加 passphrase），
 * 其余调用方零改动 —— 这是唯一需要动的落库层。
 */
class InboxDatabase(context: Context) :
    SQLiteOpenHelper(
        context.applicationContext,
        DB_NAME,
        null,
        DB_VERSION
    ) {

    /** 迁移前备份要用（SQLiteOpenHelper 不替我们保存原始 context） */
    private val appCtx: Context = context.applicationContext

    companion object {
        private const val TAG = "zhuli.inbox"
        private const val DB_NAME = "zhuli_inbox.db"

        // v2：url / summary（第 3 步链接条目）
        // v3：category / deadline_at / amount / note / extracted_at（第 4 步提取流水线）
        // v4：reminders 表（第 5 步提醒引擎，一条消息最多一条提醒）
        // v5：media_path（第 6 步媒体存储策略，图片条目存缩略图路径，原图不留）
        // v6：ledger 记账表（第 7 步，双通道入账；UNIQUE(inbox_id) 防重复记）
        // v7：reminders.notified（v0.9.1 合并出通知：到点只标 fired，通知由合并器统一发）
        // v8：cloud_log 出网留档（第 9 步，需求 §3.3「走云的调用留一份本地脱敏摘要」）
        // v9：repeat_rule 周期规则（第 14 步，每天/每周X/每月N号）
        // v10：skills 技能表（对话里让用户自己加规则，第 16 步）
        // v11：embeddings 向量表（P0 混合检索）+ reminders.snooze_count（待办跟踪）
        // v12：reminders.confidence / basis（T-D1 置信度三级）
        // v13：source_rule 来源规则（阶段 F，每 App 静音）
        // v14：repeat_rule 迁 RFC5545
        // v15：记忆核心（entity/fact）+ Agent 任务核心（agent_task/agent_step）
        // v16：事实向量索引队列（写入即排队、后台建索引、失败可重试）
        //      + 工具执行记录（执行前落意图、成功后落结果，崩溃后可判定「结果未知」而不是盲目重跑）
        private const val DB_VERSION = 16

        const val TABLE = "inbox"
        const val FTS_TABLE = "inbox_fts"

        /**
         * 本进程内是否已经做过一次 FTS 同步检查（T-C1）。
         * 每建一个 InboxDatabase 实例都会走 onOpen，但触发器建一次、一致性检查一次就够。
         */
        private val ftsChecked = java.util.concurrent.atomic.AtomicBoolean(false)

        /**
         * 静音包名缓存。通知每来一条都要问一次「这个包静音了吗」，
         * 每次都开游标没必要；写操作时置空（见 [setSourceMuted]）。
         */
        @Volatile
        private var mutedCache: Set<String>? = null

        /** 孤儿向量清理每进程只跑一次就够了（见 [purgeOrphanEmbeddings]） */
        private val orphanChecked = java.util.concurrent.atomic.AtomicBoolean(false)

        /** T-E1：最近一次迁移失败的原因（null = 没失败过）。UI 拿它给用户一句明确提示。 */
        @Volatile
        var lastMigrationError: String? = null
            private set

        /** 提醒表（第 5 步） */
        const val TABLE_REMINDER = "reminders"
        const val COL_REM_ID = "id"
        const val COL_REM_INBOX = "inbox_id"
        const val COL_REM_DUE = "due_at"
        const val COL_REM_TITLE = "title"
        const val COL_REM_BODY = "body"
        const val COL_REM_STATUS = "status"
        const val COL_REM_FIRED = "fired_at"
        const val COL_REM_CAL = "calendar_event_id"
        /** v7：0=还没弹过通知，1=已弹（合并出通知的幂等标记） */
        const val COL_REM_NOTIFIED = "notified"

        /** 记账表（第 7 步，文字/截图双通道同表入账） */
        const val TABLE_LEDGER = "ledger"
        const val COL_L_ID = "id"
        const val COL_L_INBOX = "inbox_id"
        const val COL_L_DIR = "dir"           // 收 | 支
        const val COL_L_CAT = "category"      // 餐饮|交通|购物|日用|人情|通讯|医疗|数码|收入|其他（只加不减）
        const val COL_L_AMOUNT = "amount"
        const val COL_L_AT = "occurred_at"
        const val COL_L_NOTE = "note"

        /** 聊天记录分区（需求 §2.5：与记忆库同一个 SQLite，独立表，默认持久保留） */
        const val TABLE_CHAT = "chat_log"
        const val COL_CHAT_ID = "id"
        const val COL_CHAT_ROLE = "role"
        const val COL_CHAT_CONTENT = "content"
        const val COL_CHAT_AT = "created_at"

        /** 向量（v11）：本地嵌入，混合检索用；vec 是小端 float32 数组 */
        const val TABLE_EMB = "embeddings"
        const val COL_E_INBOX = "inbox_id"
        const val COL_E_VEC = "vec"
        const val COL_E_AT = "at"
        const val COL_REM_SNOOZE = "snooze_count"
        /** T-D1：置信度与判定依据 */
        const val COL_REM_CONF = "confidence"
        const val COL_REM_BASIS = "basis"

        /** 技能/自动化（v10）：用户在对话里「以后收到 X 就 Y」攒下来的规则 */
        const val TABLE_SKILL = "skills"
        const val COL_S_ID = "id"
        const val COL_S_KIND = "kind"        // remind | categorize | drop
        const val COL_S_PATTERN = "pattern"  // 关键词或包名
        const val COL_S_ARG = "arg"          // 分类名 / 提前分钟
        const val COL_S_ENABLED = "enabled"
        const val COL_S_HITS = "hits"
        const val COL_S_AT = "created_at"

        /**
         * 来源规则（v13，阶段 F）：每个 App 一条。静音后该包的通知**直接不收**。
         * 为什么不是"收下来再隐藏"：用户的诉求是"别再让它进我的收件箱"，
         * 收了再藏等于把噪声留在检索与提醒的候选集里（真实数据里 83% 的行就来自两个推送 App）。
         */
        const val TABLE_SOURCE = "source_rule"
        const val COL_SRC_PKG = "pkg"
        const val COL_SRC_MUTED = "muted"
        const val COL_SRC_AT = "updated_at"

        /** 出网留档（第 9 步，v8）：只存脱敏后的摘要，任何原始敏感串都不落这里 */
        const val TABLE_CLOUD = "cloud_log"
        const val COL_C_ID = "id"
        const val COL_C_AT = "at"
        const val COL_C_TASK = "task"
        const val COL_C_HOST = "host"
        const val COL_C_MODEL = "model"
        const val COL_C_REQ = "request_redacted"
        const val COL_C_OK = "ok"
        const val COL_C_NOTE = "note"

        // ---- v15：记忆核心（事实是最小单位，实体表只是索引）----
        const val TABLE_ENTITY = "zhuli_entity"
        const val TABLE_FACT = "zhuli_fact"
        const val COL_ENT_ID = "id"
        const val COL_ENT_KIND = "kind"
        const val COL_ENT_NAME = "name"
        const val COL_ENT_NORM = "norm"
        const val COL_ENT_ALIASES = "aliases"
        const val COL_ENT_CREATED = "created_at"
        const val COL_ENT_UPDATED = "updated_at"

        const val COL_F_ID = "id"
        const val COL_F_SUBJECT = "subject"
        const val COL_F_PRED = "predicate"
        const val COL_F_VALUE = "value"
        const val COL_F_KIND = "kind"
        const val COL_F_CONF = "confidence"
        const val COL_F_STYPE = "source_type"
        const val COL_F_SID = "source_id"
        const val COL_F_STEXT = "source_text"
        const val COL_F_OBSERVED = "observed_at"
        const val COL_F_VALID_FROM = "valid_from"
        const val COL_F_VALID_TO = "valid_to"
        const val COL_F_STATUS = "status"
        const val COL_F_SUPERSEDED = "superseded_by"
        const val COL_F_CREATED = "created_at"
        const val COL_F_UPDATED = "updated_at"

        // ---- v15：Agent 任务核心（跨进程存活、可审计、可恢复）----
        const val TABLE_TASK = "zhuli_agent_task"
        const val TABLE_STEP = "zhuli_agent_step"
        const val COL_T_ID = "id"
        const val COL_T_GOAL = "goal"
        const val COL_T_STATUS = "status"
        const val COL_T_CREATED = "created_at"
        const val COL_T_UPDATED = "updated_at"
        const val COL_T_FINISHED = "finished_at"
        const val COL_T_RESULT = "result"
        const val COL_T_ERROR = "error"
        const val COL_T_PENDING = "pending_call"
        const val COL_T_RISK = "pending_risk"
        const val COL_T_DEDUPE = "dedupe_key"
        const val COL_T_ORIGIN = "origin"
        const val COL_T_STEPS = "step_count"
        /** v16：该任务是否只允许本机推理（隐私策略要能跨进程存活） */
        const val COL_T_LOCAL = "local_only"

        // 注意：skills 表已经占用了 COL_S_* 前缀，Agent 步骤统一用 COL_AS_*，否则同 object 内冲突
        const val COL_AS_ID = "id"
        const val COL_AS_TASK = "task_id"
        const val COL_AS_SEQ = "seq"
        const val COL_AS_KIND = "kind"
        const val COL_AS_CONTENT = "content"
        const val COL_AS_TOOL = "tool_name"
        const val COL_AS_ARGS = "tool_args"
        const val COL_AS_CALLID = "tool_call_id"
        const val COL_AS_CALLS = "calls_json"
        const val COL_AS_OK = "ok"
        const val COL_AS_AT = "at"
        const val COL_AS_LATENCY = "latency_ms"
        const val COL_AS_PROVIDER = "provider_id"

        const val TABLE_FACT_VEC = "zhuli_fact_vec"
        const val COL_FV_FACT = "fact_id"
        const val COL_FV_SPACE = "space"
        const val COL_FV_VEC = "vec"
        const val COL_FV_AT = "at"

        // ---- v16：事实向量索引队列 ----
        // 一条事实一行；建好索引就删行。attempts 用来避免坏样本无限重试。
        const val TABLE_VEC_QUEUE = "zhuli_vec_queue"
        const val COL_VQ_FACT = "fact_id"
        const val COL_VQ_ATTEMPTS = "attempts"
        const val COL_VQ_ERROR = "last_error"
        const val COL_VQ_AT = "at"

        // ---- v16：工具执行记录（幂等 + 崩溃恢复）----
        const val TABLE_TOOL_EXEC = "zhuli_tool_exec"
        const val COL_TX_ID = "execution_id"
        const val COL_TX_TASK = "task_id"
        const val COL_TX_TOOL = "tool"
        const val COL_TX_ARGS = "args"
        const val COL_TX_KEY = "idem_key"
        const val COL_TX_STATE = "state"
        const val COL_TX_RESULT = "result"
        const val COL_TX_ERROR = "error"
        const val COL_TX_CREATED = "created_at"
        const val COL_TX_UPDATED = "updated_at"
        const val COL_TX_ATTEMPTS = "attempts"

        const val COL_ID = "id"
        const val COL_SOURCE = "source"
        const val COL_PACKAGE = "package_name"
        const val COL_LABEL = "app_label"
        const val COL_TITLE = "title"
        const val COL_TEXT = "text"
        const val COL_RECEIVED = "received_at"
        const val COL_PENDING = "pending_extract"
        const val COL_URL = "url"            // 第3步列
        const val COL_SUMMARY = "summary"    // 第3步列
        const val COL_CATEGORY = "category"  // 第4步列：快递|金融|工作|生活|链接|其他
        const val COL_DEADLINE = "deadline_at"
        const val COL_AMOUNT = "amount"
        const val COL_NOTE = "note"
        const val COL_EXTRACTED = "extracted_at"
        const val COL_REPEAT = "repeat_rule"  // 第14步列：daily / weekly:N / monthly:N；''=一次性
        const val COL_MEDIA = "media_path"   // 第6步列：图片条目缩略图绝对路径；空串=非图片条目

        /** v3 起新增的四列 + 落地时间，供 onCreate / onUpgrade 复用，避免两处写法漂移 */
        private val V3_COLUMNS = listOf(
            "$COL_CATEGORY TEXT NOT NULL DEFAULT ''",
            "$COL_DEADLINE INTEGER NOT NULL DEFAULT 0",
            "$COL_AMOUNT REAL NOT NULL DEFAULT 0",
            "$COL_NOTE TEXT NOT NULL DEFAULT ''",
            "$COL_EXTRACTED INTEGER NOT NULL DEFAULT 0"
        )
        /** v5：第 6 步媒体存储策略（D3），图片条目只留缩略图路径 */
        private val V5_MEDIA_COLUMN = "$COL_MEDIA TEXT NOT NULL DEFAULT ''"
        /** v9：周期规则。onCreate / onUpgrade / onOpen 三处必须用同一份定义 */
        private val V9_REPEAT_COLUMN = "$COL_REPEAT TEXT NOT NULL DEFAULT ''"
    }

    override fun onCreate(db: SQLiteDatabase) {
        // 建表必须是**最新** schema：迁移链只服务老库。2026-11-21 的 P0 就是这里漏了 v9 的 repeat_rule，
        // 新装库没有这一列，而 insert() 的 ContentValues 带着它 → 第一条通知入库即抛 no column named repeat_rule。
        val cols = (V3_COLUMNS + listOf(V5_MEDIA_COLUMN, V9_REPEAT_COLUMN)).joinToString(",\n                ")
        db.execSQL(
            """
            CREATE TABLE $TABLE (
                $COL_ID INTEGER PRIMARY KEY AUTOINCREMENT,
                $COL_SOURCE TEXT NOT NULL,
                $COL_PACKAGE TEXT NOT NULL,
                $COL_LABEL TEXT NOT NULL DEFAULT '',
                $COL_TITLE TEXT NOT NULL DEFAULT '',
                $COL_TEXT TEXT NOT NULL DEFAULT '',
                $COL_RECEIVED INTEGER NOT NULL DEFAULT (strftime('%s','now')*1000),
                $COL_PENDING INTEGER NOT NULL DEFAULT 1,
                $COL_URL TEXT NOT NULL DEFAULT '',
                $COL_SUMMARY TEXT NOT NULL DEFAULT '',
                $cols
            )
            """
        )
        // trigram 分词器按字符三元组切中文，无需词典；但部分定制 ROM（如努比亚 MyOS）
        // 内置 SQLite 可能不支持 —— 建不上就静默放弃 FTS，检索自动走 betweenKeyword 的 LIKE 兜底，
        // 主流程永不因此崩溃。此处绝不能让 onCreate 抛异常。
        try {
            db.execSQL(
                """
                CREATE VIRTUAL TABLE $FTS_TABLE USING fts5(
                    $COL_TITLE, $COL_TEXT,
                    content='$TABLE', content_rowid='$COL_ID',
                    tokenize='trigram'
                )
                """
            )
        } catch (t: Throwable) {
            Log.w(TAG, "FTS5/trigram unavailable — LIKE-only mode", t)
        }
        db.execSQL("CREATE INDEX idx_${TABLE}_received ON $TABLE($COL_RECEIVED)")
        db.execSQL("CREATE INDEX idx_${TABLE}_pending ON $TABLE($COL_PENDING)")
        db.execSQL("CREATE INDEX idx_${TABLE}_category ON $TABLE($COL_CATEGORY)")
        db.execSQL("CREATE INDEX idx_${TABLE}_deadline ON $TABLE($COL_DEADLINE)")
        createChatTable(db)
        createReminderTable(db)
        createLedgerTable(db)
        createCloudLogTable(db)
        createSkillTable(db)
        createEmbeddingTable(db)
        createSourceTable(db)
        createCoreTables(db)
        createV16Tables(db)
    }

    /**
     * D5 修复（T-C1）：进程内首次打开库时，把 FTS 外部内容表的同步补齐。
     *
     * 三件事：
     * 1) 建三个触发器（INSERT/DELETE/UPDATE）—— 外部内容表**不会**自动同步，这是官方要求自己做的；
     * 2) 用 `integrity-check`（rank=1 会比对内容表）判断索引是否已失配；
     * 3) 失配才 `rebuild`，一致则什么都不做 —— 这就是幂等保护（触发器创建不会回溯填充存量行）。
     *
     * 该 ROM 的 SQLite 建不出 trigram FTS 表（实测 `inbox_fts` 不存在）时直接返回，
     * 检索走 betweenKeyword 的 LIKE 兜底，不报错也不影响主流程。
     */
    override fun onOpen(db: SQLiteDatabase) {
        super.onOpen(db)
        // 自愈排在 FTS 之前：触发器要引用最新列，且漂移库可能连列都没有
        if (!db.isReadOnly) {
            ensureColumns(db)
            // 新表也走同一套「缺就补」，避免再出现"升级有、新装没有"的漂移
            createSourceTable(db)
            createCoreTables(db)
            createV16Tables(db)
            if (orphanChecked.compareAndSet(false, true)) {
                val n = purgeOrphanEmbeddings(db)
                if (n > 0) Log.i(TAG, "清掉孤儿向量 $n 条")
            }
        }
        if (!ftsChecked.compareAndSet(false, true)) return
        ensureFtsSync(db)
    }

    private fun ensureFtsSync(db: SQLiteDatabase) {
        try {
            val exists = db.rawQuery(
                "SELECT 1 FROM sqlite_master WHERE type='table' AND name=? LIMIT 1",
                arrayOf(FTS_TABLE)
            ).use { it.moveToFirst() }
            if (!exists) {
                Log.i(TAG, "no $FTS_TABLE on this ROM — LIKE-only mode")
                return
            }
            val ti = COL_TITLE
            val tx = COL_TEXT
            val id = COL_ID
            db.execSQL(
                "CREATE TRIGGER IF NOT EXISTS ${FTS_TABLE}_ai AFTER INSERT ON $TABLE BEGIN " +
                    "INSERT INTO $FTS_TABLE(rowid, $ti, $tx) VALUES (new.$id, new.$ti, new.$tx); END"
            )
            db.execSQL(
                "CREATE TRIGGER IF NOT EXISTS ${FTS_TABLE}_ad AFTER DELETE ON $TABLE BEGIN " +
                    "INSERT INTO $FTS_TABLE($FTS_TABLE, rowid, $ti, $tx) " +
                    "VALUES ('delete', old.$id, old.$ti, old.$tx); END"
            )
            db.execSQL(
                "CREATE TRIGGER IF NOT EXISTS ${FTS_TABLE}_au AFTER UPDATE ON $TABLE BEGIN " +
                    "INSERT INTO $FTS_TABLE($FTS_TABLE, rowid, $ti, $tx) " +
                    "VALUES ('delete', old.$id, old.$ti, old.$tx); " +
                    "INSERT INTO $FTS_TABLE(rowid, $ti, $tx) VALUES (new.$id, new.$ti, new.$tx); END"
            )
            val consistent = try {
                db.execSQL("INSERT INTO $FTS_TABLE($FTS_TABLE, rank) VALUES ('integrity-check', 1)")
                true
            } catch (t: Throwable) {
                false
            }
            if (!consistent) {
                db.execSQL("INSERT INTO $FTS_TABLE($FTS_TABLE) VALUES ('rebuild')")
                Log.i(TAG, "fts rebuilt（存量补齐）")
            }
        } catch (t: Throwable) {
            Log.w(TAG, "ensureFtsSync failed", t)
        }
    }

    /** 供检索兜底判断「库非空但 FTS 返回空」（D5）。 */
    fun inboxCount(): Int =
        readableDatabase.rawQuery("SELECT COUNT(*) FROM $TABLE", null).use {
            if (it.moveToFirst()) it.getInt(0) else 0
        }

    private fun createReminderTable(db: SQLiteDatabase) {
        db.execSQL(
            """
            CREATE TABLE IF NOT EXISTS $TABLE_REMINDER (
                $COL_REM_ID INTEGER PRIMARY KEY AUTOINCREMENT,
                $COL_REM_INBOX INTEGER NOT NULL,
                $COL_REM_DUE INTEGER NOT NULL,
                $COL_REM_TITLE TEXT NOT NULL DEFAULT '',
                $COL_REM_BODY TEXT NOT NULL DEFAULT '',
                $COL_REM_STATUS TEXT NOT NULL DEFAULT 'scheduled',
                $COL_REM_FIRED INTEGER NOT NULL DEFAULT 0,
                $COL_REM_CAL INTEGER NOT NULL DEFAULT 0,
                $COL_REM_NOTIFIED INTEGER NOT NULL DEFAULT 0,
                $COL_REPEAT TEXT NOT NULL DEFAULT '',
                $COL_REM_SNOOZE INTEGER NOT NULL DEFAULT 0,
                $COL_REM_CONF TEXT NOT NULL DEFAULT 'high',
                $COL_REM_BASIS TEXT NOT NULL DEFAULT ''
            )
            """
        )
        // 一条消息只排一条提醒：重复扫描不会刷出重复闹钟
        db.execSQL(
            "CREATE UNIQUE INDEX IF NOT EXISTS idx_${TABLE_REMINDER}_inbox " +
                "ON $TABLE_REMINDER($COL_REM_INBOX)"
        )
        db.execSQL(
            "CREATE INDEX IF NOT EXISTS idx_${TABLE_REMINDER}_due " +
                "ON $TABLE_REMINDER($COL_REM_DUE, $COL_REM_STATUS)"
        )
    }

    private fun createLedgerTable(db: SQLiteDatabase) {
        db.execSQL(
            """
            CREATE TABLE IF NOT EXISTS $TABLE_LEDGER (
                $COL_L_ID INTEGER PRIMARY KEY AUTOINCREMENT,
                $COL_L_INBOX INTEGER NOT NULL,
                $COL_L_DIR TEXT NOT NULL DEFAULT '支',
                $COL_L_CAT TEXT NOT NULL DEFAULT '其他',
                $COL_L_AMOUNT REAL NOT NULL DEFAULT 0,
                $COL_L_AT INTEGER NOT NULL,
                $COL_L_NOTE TEXT NOT NULL DEFAULT ''
            )
            """
        )
        // 一行消息最多入一次账：提取流水线与 OCR 回调两条路径都调 ingest()，必须防重
        db.execSQL(
            "CREATE UNIQUE INDEX IF NOT EXISTS idx_${TABLE_LEDGER}_inbox " +
                "ON $TABLE_LEDGER($COL_L_INBOX)"
        )
    }

    private fun createChatTable(db: SQLiteDatabase) {
        db.execSQL(
            """
            CREATE TABLE IF NOT EXISTS $TABLE_CHAT (
                $COL_CHAT_ID INTEGER PRIMARY KEY AUTOINCREMENT,
                $COL_CHAT_ROLE TEXT NOT NULL,
                $COL_CHAT_CONTENT TEXT NOT NULL,
                $COL_CHAT_AT INTEGER NOT NULL DEFAULT (strftime('%s','now')*1000)
            )
            """
        )
        db.execSQL("CREATE INDEX IF NOT EXISTS idx_${TABLE_CHAT}_at ON $TABLE_CHAT($COL_CHAT_AT)")
    }

    /** v11：向量表（只存归一化后的小端 float32）。 */
    private fun createEmbeddingTable(db: SQLiteDatabase) {
        db.execSQL(
            """
            CREATE TABLE IF NOT EXISTS $TABLE_EMB (
                $COL_E_INBOX INTEGER PRIMARY KEY,
                $COL_E_VEC BLOB NOT NULL,
                $COL_E_AT INTEGER NOT NULL
            )
            """
        )
    }

    /** v10：技能表。规则是用户在对话里说的，属于他们自己，删改随便。 */
    /**
     * 来源规则表（v13）。一条规则一个包：静音=1 就不收它的通知。
     * `CREATE TABLE IF NOT EXISTS` —— onCreate / onUpgrade / onOpen 三处都调，重复调用无副作用。
     */
    private fun createSourceTable(db: SQLiteDatabase) {
        db.execSQL(
            """
            CREATE TABLE IF NOT EXISTS $TABLE_SOURCE (
                $COL_SRC_PKG TEXT PRIMARY KEY,
                $COL_SRC_MUTED INTEGER NOT NULL DEFAULT 0,
                $COL_SRC_AT INTEGER NOT NULL DEFAULT 0
            )
            """
        )
    }

    /**
     * v15：记忆与任务核心。
     * `CREATE TABLE IF NOT EXISTS` + 三处调用（onCreate / onUpgrade / onOpen）——
     * 与 source_rule 同一套打法，避免「升级有、新装没有」的漂移。
     * 事实历史不物理删除：纠错时把旧行标 SUPERSEDED，新行写 superseded_by 指回去。
     */
    private fun createCoreTables(db: SQLiteDatabase) {
        db.execSQL(
            """
            CREATE TABLE IF NOT EXISTS $TABLE_ENTITY (
                $COL_ENT_ID INTEGER PRIMARY KEY AUTOINCREMENT,
                $COL_ENT_KIND TEXT NOT NULL,
                $COL_ENT_NAME TEXT NOT NULL,
                $COL_ENT_NORM TEXT NOT NULL,
                $COL_ENT_ALIASES TEXT NOT NULL DEFAULT '',
                $COL_ENT_CREATED INTEGER NOT NULL,
                $COL_ENT_UPDATED INTEGER NOT NULL,
                UNIQUE($COL_ENT_KIND, $COL_ENT_NORM)
            )
            """
        )
        db.execSQL(
            """
            CREATE TABLE IF NOT EXISTS $TABLE_FACT (
                $COL_F_ID INTEGER PRIMARY KEY AUTOINCREMENT,
                $COL_F_SUBJECT TEXT NOT NULL,
                $COL_F_PRED TEXT NOT NULL,
                $COL_F_VALUE TEXT NOT NULL,
                $COL_F_KIND TEXT NOT NULL DEFAULT 'OTHER',
                $COL_F_CONF REAL NOT NULL DEFAULT 0.6,
                $COL_F_STYPE TEXT NOT NULL DEFAULT 'INBOX',
                $COL_F_SID INTEGER NOT NULL DEFAULT 0,
                $COL_F_STEXT TEXT NOT NULL DEFAULT '',
                $COL_F_OBSERVED INTEGER NOT NULL DEFAULT 0,
                $COL_F_VALID_FROM INTEGER NOT NULL DEFAULT 0,
                $COL_F_VALID_TO INTEGER NOT NULL DEFAULT 0,
                $COL_F_STATUS TEXT NOT NULL DEFAULT 'ACTIVE',
                $COL_F_SUPERSEDED INTEGER NOT NULL DEFAULT 0,
                $COL_F_CREATED INTEGER NOT NULL,
                $COL_F_UPDATED INTEGER NOT NULL
            )
            """
        )
        db.execSQL("CREATE INDEX IF NOT EXISTS idx_fact_slot ON $TABLE_FACT($COL_F_SUBJECT, $COL_F_PRED)")
        db.execSQL("CREATE INDEX IF NOT EXISTS idx_fact_source ON $TABLE_FACT($COL_F_STYPE, $COL_F_SID)")
        db.execSQL("CREATE INDEX IF NOT EXISTS idx_fact_status ON $TABLE_FACT($COL_F_STATUS, $COL_F_OBSERVED)")
        db.execSQL(
            """
            CREATE TABLE IF NOT EXISTS $TABLE_TASK (
                $COL_T_ID TEXT PRIMARY KEY,
                $COL_T_GOAL TEXT NOT NULL,
                $COL_T_STATUS TEXT NOT NULL,
                $COL_T_CREATED INTEGER NOT NULL,
                $COL_T_UPDATED INTEGER NOT NULL,
                $COL_T_FINISHED INTEGER NOT NULL DEFAULT 0,
                $COL_T_RESULT TEXT NOT NULL DEFAULT '',
                $COL_T_ERROR TEXT NOT NULL DEFAULT '',
                $COL_T_PENDING TEXT NOT NULL DEFAULT '',
                $COL_T_RISK TEXT NOT NULL DEFAULT '',
                $COL_T_DEDUPE TEXT NOT NULL DEFAULT '',
                $COL_T_ORIGIN TEXT NOT NULL DEFAULT 'manual',
                $COL_T_STEPS INTEGER NOT NULL DEFAULT 0
            )
            """
        )
        db.execSQL("CREATE INDEX IF NOT EXISTS idx_task_status ON $TABLE_TASK($COL_T_STATUS, $COL_T_CREATED)")
        db.execSQL("CREATE INDEX IF NOT EXISTS idx_task_dedupe ON $TABLE_TASK($COL_T_DEDUPE, $COL_T_CREATED)")
        db.execSQL(
            """
            CREATE TABLE IF NOT EXISTS $TABLE_STEP (
                $COL_AS_ID INTEGER PRIMARY KEY AUTOINCREMENT,
                $COL_AS_TASK TEXT NOT NULL,
                $COL_AS_SEQ INTEGER NOT NULL,
                $COL_AS_KIND TEXT NOT NULL,
                $COL_AS_CONTENT TEXT NOT NULL DEFAULT '',
                $COL_AS_TOOL TEXT NOT NULL DEFAULT '',
                $COL_AS_ARGS TEXT NOT NULL DEFAULT '',
                $COL_AS_CALLID TEXT NOT NULL DEFAULT '',
                $COL_AS_CALLS TEXT NOT NULL DEFAULT '',
                $COL_AS_OK INTEGER NOT NULL DEFAULT 1,
                $COL_AS_AT INTEGER NOT NULL,
                $COL_AS_LATENCY INTEGER NOT NULL DEFAULT 0,
                $COL_AS_PROVIDER TEXT NOT NULL DEFAULT '',
                UNIQUE($COL_AS_TASK, $COL_AS_SEQ)
            )
            """
        )
        db.execSQL("CREATE INDEX IF NOT EXISTS idx_step_task ON $TABLE_STEP($COL_AS_TASK, $COL_AS_SEQ)")
        // 事实向量：space = "providerId|dim"，绝不允许跨 space 比较（见 MemoryEmbedding 的说明）
        db.execSQL(
            """
            CREATE TABLE IF NOT EXISTS $TABLE_FACT_VEC (
                fact_id INTEGER PRIMARY KEY,
                space TEXT NOT NULL,
                vec BLOB NOT NULL,
                at INTEGER NOT NULL
            )
            """
        )
    }

    /**
     * v16：向量索引队列 + 工具执行记录。
     *
     * 两张表都是**纯增量**：不碰任何存量行，老库升级只多两张空表。
     * 和 createCoreTables 一样 `IF NOT EXISTS`，onCreate / onUpgrade / onOpen 三处共用。
     */
    private fun createV16Tables(db: SQLiteDatabase) {
        db.execSQL(
            """
            CREATE TABLE IF NOT EXISTS $TABLE_VEC_QUEUE (
                $COL_VQ_FACT INTEGER PRIMARY KEY,
                $COL_VQ_ATTEMPTS INTEGER NOT NULL DEFAULT 0,
                $COL_VQ_ERROR TEXT NOT NULL DEFAULT '',
                $COL_VQ_AT INTEGER NOT NULL DEFAULT 0
            )
            """
        )
        db.execSQL("CREATE INDEX IF NOT EXISTS idx_vecq_at ON $TABLE_VEC_QUEUE($COL_VQ_AT)")
        db.execSQL(
            """
            CREATE TABLE IF NOT EXISTS $TABLE_TOOL_EXEC (
                $COL_TX_ID TEXT PRIMARY KEY,
                $COL_TX_TASK TEXT NOT NULL,
                $COL_TX_TOOL TEXT NOT NULL,
                $COL_TX_ARGS TEXT NOT NULL DEFAULT '',
                $COL_TX_KEY TEXT NOT NULL DEFAULT '',
                $COL_TX_STATE TEXT NOT NULL,
                $COL_TX_RESULT TEXT NOT NULL DEFAULT '',
                $COL_TX_ERROR TEXT NOT NULL DEFAULT '',
                $COL_TX_CREATED INTEGER NOT NULL,
                $COL_TX_UPDATED INTEGER NOT NULL,
                $COL_TX_ATTEMPTS INTEGER NOT NULL DEFAULT 0
            )
            """
        )
        db.execSQL("CREATE INDEX IF NOT EXISTS idx_tx_task ON $TABLE_TOOL_EXEC($COL_TX_TASK, $COL_TX_CREATED)")
        // 幂等键唯一：同一任务 + 同一工具 + 同一参数，全库只允许一行成功记录
        db.execSQL("CREATE UNIQUE INDEX IF NOT EXISTS idx_tx_key ON $TABLE_TOOL_EXEC($COL_TX_KEY)")
        // v16 同时给任务表补「只许本机推理」标记（隐私策略必须跨进程存活，不能只存在内存里）
        addColumnIfMissing(db, TABLE_TASK, "$COL_T_LOCAL INTEGER NOT NULL DEFAULT 0")
    }

    private fun createSkillTable(db: SQLiteDatabase) {        db.execSQL(
            """
            CREATE TABLE IF NOT EXISTS $TABLE_SKILL (
                $COL_S_ID INTEGER PRIMARY KEY AUTOINCREMENT,
                $COL_S_KIND TEXT NOT NULL,
                $COL_S_PATTERN TEXT NOT NULL,
                $COL_S_ARG TEXT NOT NULL DEFAULT '',
                $COL_S_ENABLED INTEGER NOT NULL DEFAULT 1,
                $COL_S_HITS INTEGER NOT NULL DEFAULT 0,
                $COL_S_AT INTEGER NOT NULL
            )
            """
        )
        db.execSQL("CREATE UNIQUE INDEX IF NOT EXISTS idx_${TABLE_SKILL}_k ON $TABLE_SKILL($COL_S_KIND, $COL_S_PATTERN)")
    }

    /** v8：出网留档表。只存「脱敏后的请求摘要」，原始内容不进这张表（需求 §3.3）。 */
    private fun createCloudLogTable(db: SQLiteDatabase) {
        db.execSQL(
            """
            CREATE TABLE IF NOT EXISTS $TABLE_CLOUD (
                $COL_C_ID INTEGER PRIMARY KEY AUTOINCREMENT,
                $COL_C_AT INTEGER NOT NULL,
                $COL_C_TASK TEXT NOT NULL DEFAULT '',
                $COL_C_HOST TEXT NOT NULL DEFAULT '',
                $COL_C_MODEL TEXT NOT NULL DEFAULT '',
                $COL_C_REQ TEXT NOT NULL DEFAULT '',
                $COL_C_OK INTEGER NOT NULL DEFAULT 1,
                $COL_C_NOTE TEXT NOT NULL DEFAULT ''
            )
            """
        )
        db.execSQL("CREATE INDEX IF NOT EXISTS idx_${TABLE_CLOUD}_at ON $TABLE_CLOUD($COL_C_AT)")
    }

    override fun onUpgrade(db: SQLiteDatabase, oldVersion: Int, newVersion: Int) {
        // T-E1 / D9：整个升级过程放在**一个事务**里。任何一步失败（ALTER 失败、DDL 不支持…）
        // 就整体回滚，库停在旧版本；宁可这次打不开、也不能留一个"半套字段"的库给后面慢慢崩。
        var began = false
        try {
            // 迁移前先留一份原库：即使事务回滚，也给用户留个"升之前长什么样"的对照
            backupBeforeMigration(oldVersion)
            began = try { db.beginTransaction(); true } catch (t: Throwable) {
                Log.w(TAG, "beginTransaction failed, upgrade runs without transaction", t); false
            }
            upgradeSteps(db, oldVersion, newVersion)
            if (began) db.setTransactionSuccessful()
        } catch (t: Throwable) {
            lastMigrationError = "v$oldVersion→v$newVersion：${t.message ?: t.javaClass.simpleName}"
            Log.e(TAG, "迁移失败，已回滚（库仍为 v$oldVersion）", t)
            throw t
        } finally {
            if (began) try { db.endTransaction() } catch (t: Throwable) { Log.w(TAG, "endTransaction", t) }
        }
    }

    /**
     * 迁移前自动备份（T-E1）。放 `databases/pre_migration/`，只留最近 3 份。
     * 失败不影响迁移主流程 —— 备份是"多一层保险"，不是前置条件。
     */
    private fun backupBeforeMigration(oldVersion: Int) {
        try {
            val src = appCtx.getDatabasePath(DB_NAME)
            if (!src.exists()) return
            val dir = java.io.File(src.parentFile, "pre_migration").apply { mkdirs() }
            val ts = java.text.SimpleDateFormat("yyyyMMdd_HHmmss", java.util.Locale.US)
                .format(java.util.Date())
            val dst = java.io.File(dir, "v${oldVersion}_$ts.db")
            src.inputStream().use { i -> dst.outputStream().use { o -> i.copyTo(o) } }
            dir.listFiles()?.sortedBy { it.name }?.dropLast(3)?.forEach { it.delete() }
            Log.i(TAG, "pre-migration backup: ${dst.name} (${dst.length() / 1024}KB)")
        } catch (t: Throwable) {
            Log.w(TAG, "pre-migration backup failed (继续迁移)", t)
        }
    }

    /** 实际的迁移步骤。拆出来是为了让事务包裹保持简单，不改变原有分支逻辑。 */
    private fun upgradeSteps(db: SQLiteDatabase, oldVersion: Int, newVersion: Int) {
        // v1→v2：给旧库补链接列，老行自动填默认值 ''
        if (oldVersion < 2) {
            addColumnIfMissing(db, "$COL_URL TEXT NOT NULL DEFAULT ''")
            addColumnIfMissing(db, "$COL_SUMMARY TEXT NOT NULL DEFAULT ''")
        }
        // v2→v3：补提取结果列。老行 category='' 且 pending 保持原值，
        // 第 4 步打开 App 时流水线会把它们补跑一遍（pending=1 的行都算）。
        if (oldVersion < 3) {
            for (def in V3_COLUMNS) addColumnIfMissing(db, def)
            try { db.execSQL("CREATE INDEX IF NOT EXISTS idx_${TABLE}_category ON $TABLE($COL_CATEGORY)") }
            catch (t: Throwable) { Log.w(TAG, "create category index skipped", t) }
            try { db.execSQL("CREATE INDEX IF NOT EXISTS idx_${TABLE}_deadline ON $TABLE($COL_DEADLINE)") }
            catch (t: Throwable) { Log.w(TAG, "create deadline index skipped", t) }
            createChatTable(db)
            // 老行的 category 是刚补的空串：重新标成待提取，让流水线把历史消息也补跑一遍
            try { db.execSQL("UPDATE $TABLE SET $COL_PENDING = 1 WHERE $COL_CATEGORY = ''") }
            catch (t: Throwable) { Log.w(TAG, "backfill pending skipped", t) }
        }
        // v3→v4：建提醒表。已有的带期限历史行不在这里补排，
        // 由 ReminderEngine.scan() 首次运行时统一补（那里能同时处理系统日历权限）
        if (oldVersion < 4) {
            createReminderTable(db)
        }
        // v4→v5：补媒体列。老行一律空串，非图片条目不受影响
        if (oldVersion < 5) {
            addColumnIfMissing(db, V5_MEDIA_COLUMN)
        }
        // v5→v6：建记账表。老数据不进账（历史行没有过 Ledger.ingest，不臆造；新消息起自然入账）
        if (oldVersion < 6) {
            createLedgerTable(db)
        }
        // v6→v7：提醒加 notified 标记。**列默认必须是 0**（否则新建的提醒会被当成「已弹过」而不弹）；
        // 老行随后统一置 1，免得升级后开 App 把历史提醒再炸一遍
        if (oldVersion < 7) {
            addColumnIfMissing(db, TABLE_REMINDER, "$COL_REM_NOTIFIED INTEGER NOT NULL DEFAULT 0")
            try { db.execSQL("UPDATE $TABLE_REMINDER SET $COL_REM_NOTIFIED = 1") }
            catch (t: Throwable) { Log.w(TAG, "backfill notified skipped", t) }
        }
        // v7→v8：建出网留档表。历史调用无从追溯，从这版起逐条留（脱敏后）
        if (oldVersion < 8) {
            createCloudLogTable(db)
        }
        // v8→v9：周期事项。两张表都补 repeat_rule，老行一律空串（一次性）
        if (oldVersion < 9) {
            addColumnIfMissing(db, V9_REPEAT_COLUMN)
            addColumnIfMissing(db, TABLE_REMINDER, V9_REPEAT_COLUMN)
        }
        if (oldVersion < 10) {
            createSkillTable(db)
        }
        if (oldVersion < 11) {
            createEmbeddingTable(db)
            addColumnIfMissing(db, TABLE_REMINDER, "$COL_REM_SNOOZE INTEGER NOT NULL DEFAULT 0")
        }
        // v11→v12（T-D1）：提醒加置信度与判定依据。老行一律 high —— 它们已经排过闹钟、
        // 用户也见过了，不能因为升级就把存量提醒降级成"待确认"
        if (oldVersion < 12) {
            addColumnIfMissing(db, TABLE_REMINDER, "$COL_REM_CONF TEXT NOT NULL DEFAULT 'high'")
            addColumnIfMissing(db, TABLE_REMINDER, "$COL_REM_BASIS TEXT NOT NULL DEFAULT ''")
        }
        // v12→v13：来源规则表。必须排在迁移事务内，失败整体回滚
        if (oldVersion < 13) {
            createSourceTable(db)
        }
        // v13→v14（V3.1 T-D6）：周期规则从自研串迁到 RFC5545。
        // 只改值不改结构；`Repeat.next()` 内部也会 migrate，所以这步是"清理存量"而不是正确性前提。
        if (oldVersion < 14) {
            migrateRepeatRules(db)
        }
        // v14→v15：记忆与 Agent 任务表。只加表、不动任何存量行，纯增量迁移。
        if (oldVersion < 15) {
            createCoreTables(db)
        }
        // v15→v16：向量索引队列 + 工具执行记录。同样是纯增量；存量事实会在首次打开时
        // 由 MemoryIndexer 补建索引（见 MemoryRepo.enqueueAllActive），不在这里做模型推理。
        if (oldVersion < 16) {
            createV16Tables(db)
        }
    }

    /**
     * v14：把 `repeat_rule` 里的 `daily / weekly:N / monthly:N` 翻成 RFC5545。
     * 两张表都过一遍（`inbox` 的周期会被 ReminderEngine 带进 `reminders`，只迁一张会漏）。
     * 幂等：已是 `FREQ=` 开头的行原样跳过。
     */
    private fun migrateRepeatRules(db: SQLiteDatabase) {
        for (table in listOf(TABLE, TABLE_REMINDER)) {
            if (!tableExists(db, table)) continue
            val todo = ArrayList<Pair<Long, String>>()
            try {
                db.rawQuery(
                    "SELECT rowid, $COL_REPEAT FROM $table WHERE $COL_REPEAT IS NOT NULL AND $COL_REPEAT != ''",
                    null
                ).use { c ->
                    while (c.moveToNext()) {
                        val id = c.getLong(0)
                        val old = c.getString(1) ?: ""
                        val migrated = Repeat.migrate(old)
                        if (migrated != old) todo.add(id to migrated)
                    }
                }
            } catch (t: Throwable) {
                Log.w(TAG, "读取 $table.repeat_rule 失败，跳过该表", t)
                continue
            }
            for ((id, rule) in todo) {
                try {
                    db.execSQL("UPDATE $table SET $COL_REPEAT = ? WHERE rowid = ?", arrayOf<Any>(rule, id))
                } catch (t: Throwable) {
                    Log.w(TAG, "更新 $table#$id 失败", t)
                }
            }
            if (todo.isNotEmpty()) Log.i(TAG, "v14 迁移 $table.repeat_rule：${todo.size} 条")
        }
    }

    /**
     * 打开时自愈 schema（2026-11-21，P0 回归）。
     *
     * 事故：`onCreate` 只建到 v5 的列，`repeat_rule` 只写在 `onUpgrade` 里 ——
     * **新装/清数据后的库没有这一列**，而 `insert()` 的 ContentValues 带着它，
     * 第一条通知入库就抛 `no column named repeat_rule`；升级上来的库却完全正常，
     * 所以既有测试全绿、只有新装路径炸。
     *
     * 两道防线：① `onCreate` 直接建最新 schema（见上）；② 这里按已知列清单补齐，
     * 任何来源的漂移（历史 beta 包、恢复回来的旧结构）都能自愈。
     * 依据：SQLiteOpenHelper 的约定是 onCreate 建全量、onUpgrade 只服务老库
     * <https://developer.android.com/reference/android/database/sqlite/SQLiteOpenHelper>
     */
    /** 已知的「后加列」清单。新增列时这里、onCreate、onUpgrade 三处一起改（README 有约定）。 */
    private fun ensureColumns(db: SQLiteDatabase) {
        if (!tableExists(db, TABLE)) return
        for (def in V3_COLUMNS) addColumnIfMissing(db, def)
        addColumnIfMissing(db, V5_MEDIA_COLUMN)
        addColumnIfMissing(db, V9_REPEAT_COLUMN)
        if (tableExists(db, TABLE_REMINDER)) {
            addColumnIfMissing(db, TABLE_REMINDER, "$COL_REM_NOTIFIED INTEGER NOT NULL DEFAULT 0")
            addColumnIfMissing(db, TABLE_REMINDER, V9_REPEAT_COLUMN)
            addColumnIfMissing(db, TABLE_REMINDER, "$COL_REM_SNOOZE INTEGER NOT NULL DEFAULT 0")
            addColumnIfMissing(db, TABLE_REMINDER, "$COL_REM_CONF TEXT NOT NULL DEFAULT 'high'")
            addColumnIfMissing(db, TABLE_REMINDER, "$COL_REM_BASIS TEXT NOT NULL DEFAULT ''")
        }
    }

    private fun tableExists(db: SQLiteDatabase, table: String): Boolean = try {
        db.rawQuery("SELECT 1 FROM sqlite_master WHERE type='table' AND name=? LIMIT 1", arrayOf(table))
            .use { it.moveToFirst() }
    } catch (t: Throwable) {
        false
    }

    /**
     * 补列。**失败必须抛出**（T-E1 / D9）——
     * 以前这里 catch 掉只打日志，结果是"迁移做了一半，App 带着半套字段继续跑"，
     * 后面查询直接 NoSuchColumnException，现场还看不出是迁移的锅。
     * 现在的约定：整个 onUpgrade 在一个事务里，任何一步失败 → 全部回滚 → 抛出去，让上层明确失败。
     */
    private fun addColumnIfMissing(db: SQLiteDatabase, table: String, columnDef: String) {
        val name = columnDef.substringBefore(' ')
        if (hasColumn(db, table, name)) return
        try {
            db.execSQL("ALTER TABLE $table ADD COLUMN $columnDef")
        } catch (t: Throwable) {
            Log.e(TAG, "alter $table.$name failed — 整个迁移将回滚", t)
            throw t
        }
    }

    private fun addColumnIfMissing(db: SQLiteDatabase, columnDef: String) =
        addColumnIfMissing(db, TABLE, columnDef)

    private fun hasColumn(db: SQLiteDatabase, table: String, column: String): Boolean =
        db.rawQuery("PRAGMA table_info($table)", null).use { c ->
            val nameIdx = c.getColumnIndex("name")
            while (c.moveToNext()) if (nameIdx >= 0 && c.getString(nameIdx) == column) return true
            false
        }

    /** 写入一条收件箱记录，返回新行号。 */
    fun insert(rec: InboxRecord): Long {
        val db = writableDatabase
        val now = System.currentTimeMillis()
        val r = rec.copy(receivedAt = rec.receivedAt.takeIf { it > 0 } ?: now)
        return db.insert(TABLE, null,
            ContentValues().apply {
                put(COL_SOURCE, r.source)
                put(COL_PACKAGE, r.packageName)
                put(COL_LABEL, r.appLabel)
                put(COL_TITLE, r.title)
                put(COL_TEXT, r.text)
                put(COL_RECEIVED, r.receivedAt)
                put(COL_PENDING, r.pendingExtract)
                put(COL_URL, r.url)
                put(COL_SUMMARY, r.summary)
                put(COL_CATEGORY, r.category)
                put(COL_DEADLINE, r.deadlineAt)
                put(COL_AMOUNT, r.amount)
                put(COL_NOTE, r.note)
                put(COL_REPEAT, r.repeatRule)
            }
        )
    }

    /** 待提取流水线的条数（第 4 步轮询用）。 */
    fun pendingCount(): Int {
        readableDatabase.rawQuery(
            "SELECT COUNT(*) FROM $TABLE WHERE $COL_PENDING = 1", null
        ).use { c -> return c.moveToFirst().let { if (it) c.getInt(0) else 0 } }
    }

    /** 全库条数（状态栏显示，不拉行）。 */
    fun totalCount(): Int =
        readableDatabase.rawQuery("SELECT COUNT(*) FROM $TABLE", null).use { c ->
            if (c.moveToFirst()) c.getInt(0) else 0
        }

    /** 自检面板：不重复来源数（空包名是历史脏数据，不算一个"App"）。 */
    fun distinctSourceCount(): Int =
        readableDatabase.rawQuery(
            "SELECT COUNT(DISTINCT $COL_PACKAGE) FROM $TABLE WHERE $COL_PACKAGE <> ''", null
        ).use { c -> if (c.moveToFirst()) c.getInt(0) else 0 }

    /** 自检面板：最后一条消息的接收时间，0 = 从没收到过。 */
    fun lastIngestAt(): Long =
        readableDatabase.rawQuery("SELECT IFNULL(MAX($COL_RECEIVED), 0) FROM $TABLE", null).use { c ->
            if (c.moveToFirst()) c.getLong(0) else 0L
        }

    /** 自检面板：某时刻之后入库的条数（今天收了多少）。 */
    fun countSince(ts: Long): Int =
        readableDatabase.rawQuery(
            "SELECT COUNT(*) FROM $TABLE WHERE $COL_RECEIVED >= ?", arrayOf(ts.toString())
        ).use { c -> if (c.moveToFirst()) c.getInt(0) else 0 }

    /** 自检面板：被静音的来源数（用户可能忘了自己关过几个）。 */
    fun mutedSourceCount(): Int = try {
        readableDatabase.rawQuery(
            "SELECT COUNT(*) FROM $TABLE_SOURCE WHERE $COL_SRC_MUTED = 1", null
        ).use { c -> if (c.moveToFirst()) c.getInt(0) else 0 }
    } catch (t: Throwable) {
        0
    }

    /**
     * 待提取的行，按接收时间升序（先来先处理），单批限量防一次性卡死。
     * 顺带捞起「分类还是空串」的历史行：v2 及更早写进来的条目 pending 早被清 0，
     * 但那时还没有分类字段，不补跑就永远留在「未分类」。
     */
    fun pendingRows(limit: Int = 50): List<InboxRecord> =
        readableDatabase.rawQuery(
            "SELECT * FROM $TABLE WHERE $COL_PENDING = 1 OR $COL_CATEGORY = '' " +
                "ORDER BY $COL_RECEIVED ASC LIMIT $limit",
            null
        ).use { c -> buildList { while (c.moveToNext()) add(readRow(c)) } }

    /** 提取完成：写回四列并清 pending。 */
    fun applyExtract(id: Long, f: ExtractFields) {
        writableDatabase.update(
            TABLE,
            ContentValues().apply {
                put(COL_CATEGORY, f.category)
                put(COL_DEADLINE, f.deadlineTs)
                put(COL_AMOUNT, f.amount)
                put(COL_NOTE, f.note)
                put(COL_REPEAT, f.repeatRule)
                put(COL_EXTRACTED, System.currentTimeMillis())
                put(COL_PENDING, 0)
            },
            "$COL_ID = ?", arrayOf(id.toString())
        )
    }

    /** OCR 回写（第 6 步）：正文换成识别结果，一并写提取字段、清 pending。 */
    fun applyBill(id: Long, text: String, f: ExtractFields) {
        writableDatabase.update(
            TABLE,
            ContentValues().apply {
                put(COL_TEXT, text)
                put(COL_CATEGORY, f.category)
                put(COL_DEADLINE, f.deadlineTs)
                put(COL_AMOUNT, f.amount)
                put(COL_NOTE, f.note)
                put(COL_REPEAT, f.repeatRule)
                put(COL_EXTRACTED, System.currentTimeMillis())
                put(COL_PENDING, 0)
            },
            "$COL_ID = ?", arrayOf(id.toString())
        )
    }

    /**
     * 语音转写回写（第 8 步）：正文换成转写文本，写提取字段 + 音频路径，清 pending。
     * 转写成功后音频文件已被删，audioPath 传空串即可（库里只留文字）。
     */
    fun applyVoice(id: Long, text: String, f: ExtractFields, audioPath: String) {
        writableDatabase.update(
            TABLE,
            ContentValues().apply {
                put(COL_TEXT, text)
                put(COL_CATEGORY, f.category)
                put(COL_DEADLINE, f.deadlineTs)
                put(COL_AMOUNT, f.amount)
                put(COL_NOTE, f.note)
                put(COL_REPEAT, f.repeatRule)
                put(COL_EXTRACTED, System.currentTimeMillis())
                put(COL_PENDING, 0)
                put(COL_MEDIA, audioPath)
            },
            "$COL_ID = ?", arrayOf(id.toString())
        )
    }

    /** 图片条目的缩略图落位（D3：原图不留，路径随第 15 步加密备份带走）。 */
    fun setMediaPath(id: Long, path: String) {
        writableDatabase.update(
            TABLE,
            ContentValues().apply { put(COL_MEDIA, path) },
            "$COL_ID = ?", arrayOf(id.toString())
        )
    }

    // ==================== 记账（第 7 步） ====================

    /** 双通道同入口入账（文字/截图共用）。UNIQUE(inbox_id)：重复调用不重复记。 */
    fun insertLedger(
        inboxId: Long,
        dir: String,
        category: String,
        amount: Double,
        occurredAt: Long,
        note: String
    ) {
        writableDatabase.insert(TABLE_LEDGER, null,
            ContentValues().apply {
                put(COL_L_INBOX, inboxId)
                put(COL_L_DIR, dir)
                put(COL_L_CAT, category)
                put(COL_L_AMOUNT, amount)
                put(COL_L_AT, occurredAt)
                put(COL_L_NOTE, note)
            }
        )
    }

    /**
     * 时间窗内按 (方向, 类别) 的笔数与金额合计——对话式周/月小结的数据源。
     * 返回 List<(dir, 类别, (笔数, 合计)>)，按支出合计降序、收入收尾。
     */
    fun ledgerCategoryBreakdown(fromTs: Long, toTs: Long): List<Triple<String, String, Pair<Int, Double>>> {
        val out = mutableListOf<Triple<String, String, Pair<Int, Double>>>()
        readableDatabase.rawQuery(
            "SELECT $COL_L_DIR, $COL_L_CAT, COUNT(*), IFNULL(SUM($COL_L_AMOUNT), 0) " +
                "FROM $TABLE_LEDGER WHERE $COL_L_AT BETWEEN ? AND ? " +
                "GROUP BY $COL_L_DIR, $COL_L_CAT ORDER BY SUM($COL_L_AMOUNT) DESC",
            arrayOf(fromTs.toString(), toTs.toString())
        ).use { c ->
            while (c.moveToNext()) {
                out.add(Triple(
                    c.getString(0), c.getString(1),
                    Pair(c.getInt(2), c.getDouble(3))
                ))
            }
        }
        out.sortWith(compareByDescending<Triple<String, String, Pair<Int, Double>>> { it.third.second }
            .thenBy { if (it.first == Ledger.DIR_INCOME) 1 else 0 })
        return out
    }

    /** 最近 N 条（验收壳「最近 10 条」用）。 */
    fun recent(limit: Int = 10): List<InboxRecord> =
        readableDatabase.rawQuery(
            // limit 为 Int 后直接内插，无注入面；也避开 rawQuery 参数数组的类型限制
            "SELECT * FROM $TABLE ORDER BY $COL_RECEIVED DESC LIMIT $limit", null
        ).use { c -> buildList { while (c.moveToNext()) add(readRow(c)) } }

    /** 时间窗内的条目（对话式查询用：昨天说过啥、今天都收了些啥）。 */
    fun between(fromTs: Long, toTs: Long, limit: Int = 50): List<InboxRecord> =
        readableDatabase.rawQuery(
            "SELECT * FROM $TABLE WHERE $COL_RECEIVED BETWEEN ? AND ? " +
                "ORDER BY $COL_RECEIVED DESC LIMIT $limit",
            arrayOf(fromTs.toString(), toTs.toString())
        ).use { c -> buildList { while (c.moveToNext()) add(readRow(c)) } }

    /**
     * 历史回填用分页：按 id 递增取一页（游标 = 上一页最后一条 id），
     * 避免 OFFSET 在大库上越翻越慢，也避免翻页过程中新通知插进来导致漏读。
     */
    fun rowsInRangeAfter(fromTs: Long, toTs: Long, afterId: Long, limit: Int = 100): List<InboxRecord> =
        readableDatabase.rawQuery(
            "SELECT * FROM $TABLE WHERE $COL_RECEIVED BETWEEN ? AND ? AND $COL_ID > ? " +
                "ORDER BY $COL_ID ASC LIMIT $limit",
            arrayOf(fromTs.toString(), toTs.toString(), afterId.toString())
        ).use { c -> buildList { while (c.moveToNext()) add(readRow(c)) } }

    /** 时间窗内命中关键字的条目（关键字在标题或正文里）。 */    fun betweenKeyword(fromTs: Long, toTs: Long, keyword: String, limit: Int = 50): List<InboxRecord> =
        readableDatabase.rawQuery(
            "SELECT * FROM $TABLE WHERE $COL_RECEIVED BETWEEN ? AND ? " +
                "AND ($COL_TITLE LIKE ? OR $COL_TEXT LIKE ? OR $COL_NOTE LIKE ?) " +
                "ORDER BY $COL_RECEIVED DESC LIMIT $limit",
            arrayOf(fromTs.toString(), toTs.toString(), "%$keyword%", "%$keyword%", "%$keyword%")
        ).use { c -> buildList { while (c.moveToNext()) add(readRow(c)) } }

    /** 分类汇总：某时间窗内 [category] 的条数与金额合计。 */
    fun categoryTotal(category: String, fromTs: Long, toTs: Long): Pair<Int, Double> =
        readableDatabase.rawQuery(
            "SELECT COUNT(*), IFNULL(SUM($COL_AMOUNT), 0) FROM $TABLE " +
                "WHERE $COL_CATEGORY = ? AND $COL_RECEIVED BETWEEN ? AND ?",
            arrayOf(category, fromTs.toString(), toTs.toString())
        ).use { c ->
            if (c.moveToFirst()) Pair(c.getInt(0), c.getDouble(1)) else Pair(0, 0.0)
        }

    /** 时间窗内所有金额合计（含条数），记账小结用。 */
    fun amountTotal(fromTs: Long, toTs: Long): Pair<Int, Double> =
        readableDatabase.rawQuery(
            "SELECT COUNT(*), IFNULL(SUM($COL_AMOUNT), 0) FROM $TABLE " +
                "WHERE $COL_AMOUNT > 0 AND $COL_RECEIVED BETWEEN ? AND ?",
            arrayOf(fromTs.toString(), toTs.toString())
        ).use { c ->
            if (c.moveToFirst()) Pair(c.getInt(0), c.getDouble(1)) else Pair(0, 0.0)
        }

    /** 时间窗内有金额的条目，按时间倒序（记账小结明细用）。 */
    fun amountRows(fromTs: Long, toTs: Long, limit: Int = 20): List<InboxRecord> =
        readableDatabase.rawQuery(
            "SELECT * FROM $TABLE WHERE $COL_AMOUNT > 0 AND $COL_RECEIVED BETWEEN ? AND ? " +
                "ORDER BY $COL_RECEIVED DESC LIMIT $limit",
            arrayOf(fromTs.toString(), toTs.toString())
        ).use { c -> buildList { while (c.moveToNext()) add(readRow(c)) } }

    /** 已识别出的未来待办（截止时间 > 现在），第 4 步先供对话查询，第 5 步接提醒引擎。 */
    fun upcoming(limit: Int = 20): List<InboxRecord> =
        readableDatabase.rawQuery(
            "SELECT * FROM $TABLE WHERE $COL_DEADLINE > ? " +
                "ORDER BY $COL_DEADLINE ASC LIMIT $limit",
            arrayOf(System.currentTimeMillis().toString())
        ).use { c -> buildList { while (c.moveToNext()) add(readRow(c)) } }

    // ==================== 去重 / 熔断（v0.9.1 噪声闸门） ====================

    /** 同包同标题同正文，在 since 之后是否已经收过（通知被重复上报时判重）。 */
    fun hasSameSince(pkg: String, title: String, text: String, since: Long): Boolean =
        readableDatabase.rawQuery(
            "SELECT 1 FROM $TABLE WHERE $COL_PACKAGE = ? AND $COL_TITLE = ? AND $COL_TEXT = ? " +
                "AND $COL_RECEIVED >= ? LIMIT 1",
            arrayOf(pkg, title, text, since.toString())
        ).use { it.moveToFirst() }

    /**
     * since 之后该包有没有「标题为本条前缀」的记录 —— 通知被反复更新时正文会越滚越长
     * （真机事故里标题从「…传软件 07:30」一直滚到 60 字），这种只该算一条。
     */
    fun hasTitlePrefixSince(pkg: String, title: String, since: Long): Boolean {
        val t = title.trim()
        if (t.length < 8) return false
        readableDatabase.rawQuery(
            "SELECT $COL_TITLE FROM $TABLE WHERE $COL_PACKAGE = ? AND $COL_RECEIVED >= ? " +
                "ORDER BY $COL_RECEIVED DESC LIMIT 30",
            arrayOf(pkg, since.toString())
        ).use { c ->
            while (c.moveToNext()) {
                val old = (c.getString(0) ?: "").trim()
                if (old.length < 8) continue
                // 老的更短且是新标题的开头 → 同一条通知被更新了
                if (t.startsWith(old) || old.startsWith(t)) return true
            }
        }
        return false
    }

    /** since 之后该包进了多少条（频率熔断用）。 */
    fun countFromPackageSince(pkg: String, since: Long): Int =
        readableDatabase.rawQuery(
            "SELECT COUNT(*) FROM $TABLE WHERE $COL_PACKAGE = ? AND $COL_RECEIVED >= ?",
            arrayOf(pkg, since.toString())
        ).use { if (it.moveToFirst()) it.getInt(0) else 0 }

    /** 同一时刻同一正文是否已存在（恢复备份时判重，避免整库翻倍）。 */
    fun hasSameTextSince(text: String, from: Long, to: Long): Boolean {
        if (text.isBlank()) return false
        return readableDatabase.rawQuery(
            "SELECT 1 FROM $TABLE WHERE $COL_TEXT = ? AND $COL_RECEIVED BETWEEN ? AND ? LIMIT 1",
            arrayOf(text, from.toString(), to.toString())
        ).use { it.moveToFirst() }
    }

    /** 同一条摘要是否已经收过（快递短信与 App 推送常常描述同一件事，靠它去重）。 */
    fun hasSameNoteSince(note: String, since: Long): Boolean {
        if (note.isBlank()) return false
        return readableDatabase.rawQuery(
            "SELECT 1 FROM $TABLE WHERE $COL_NOTE = ? AND $COL_RECEIVED >= ? LIMIT 1",
            arrayOf(note, since.toString())
        ).use { it.moveToFirst() }
    }

    /** 最近某分类的条目（快递面板用）。 */
    fun recentByCategory(category: String, limit: Int = 15): List<InboxRecord> =
        readableDatabase.rawQuery(
            "SELECT * FROM $TABLE WHERE $COL_CATEGORY = ? ORDER BY $COL_RECEIVED DESC LIMIT $limit",
            arrayOf(category)
        ).use { c -> buildList { while (c.moveToNext()) add(readRow(c)) } }

    // ==================== 清理（噪声自愈） ====================

    /** 按「精确包名 + 包名含关键词 + 精确包名」三组条件捞出行号。 */
    fun idsByPackageRules(
        exact1: Collection<String>,
        keywords: Collection<String>,
        exact2: Collection<String>
    ): List<Long> {
        val where = StringBuilder("1=0")
        val args = ArrayList<String>()
        for (p in exact1 + exact2) {
            where.append(" OR $COL_PACKAGE = ?"); args.add(p)
        }
        for (k in keywords) {
            where.append(" OR $COL_PACKAGE LIKE ?"); args.add("%$k%")
        }
        return readableDatabase.rawQuery(
            "SELECT $COL_ID FROM $TABLE WHERE $where", args.toTypedArray()
        ).use { c -> buildList { while (c.moveToNext()) add(c.getLong(0)) } }
    }

    fun remindersByInboxIds(ids: List<Long>): List<Reminder> {
        if (ids.isEmpty()) return emptyList()
        val out = mutableListOf<Reminder>()
        // SQLite 变量上限默认 999，脏数据可能上千条 —— 按 400 一批查
        for (chunk in ids.chunked(400)) {
            val marks = chunk.joinToString(",") { "?" }
            readableDatabase.rawQuery(
                "SELECT * FROM $TABLE_REMINDER WHERE $COL_REM_INBOX IN ($marks)",
                chunk.map { it.toString() }.toTypedArray()
            ).use { c -> while (c.moveToNext()) out.add(readReminder(c)) }
        }
        return out
    }

    fun deleteRemindersByInboxIds(ids: List<Long>): Int {
        if (ids.isEmpty()) return 0
        var n = 0
        for (chunk in ids.chunked(400)) {
            val marks = chunk.joinToString(",") { "?" }
            n += writableDatabase.delete(
                TABLE_REMINDER, "$COL_REM_INBOX IN ($marks)", chunk.map { it.toString() }.toTypedArray()
            )
        }
        return n
    }

    fun deleteInboxByIds(ids: List<Long>): Int {
        if (ids.isEmpty()) return 0
        val db = writableDatabase
        var n = 0
        db.beginTransaction()
        try {
            for (chunk in ids.chunked(400)) {
                val marks = chunk.joinToString(",") { "?" }
                val args = chunk.map { it.toString() }.toTypedArray()
                // 向量必须跟着走：只删消息会留下"指向不存在消息"的孤儿向量，
                // 语义检索会拿它当候选、再按 id 查正文时查空（2026-11-21 实测清 169 条留下 51 条孤儿）
                db.delete(TABLE_EMB, "$COL_E_INBOX IN ($marks)", args)
                n += db.delete(TABLE, "$COL_ID IN ($marks)", args)
            }
            db.setTransactionSuccessful()
        } finally {
            db.endTransaction()
        }
        return n
    }

    /** 清掉历史遗留的孤儿向量（删消息时向量没跟走的旧数据）。返回清掉的条数。 */
    fun purgeOrphanEmbeddings(): Int = purgeOrphanEmbeddings(writableDatabase)

    /**
     * 同上，但用调用方给的句柄。
     * **onOpen 里只能用这个版本**：那里再调 `writableDatabase` 会递归进 getDatabaseLocked，
     * 直接抛 "getWritableDatabase called recursively"（2026-11-21 实测踩到）。
     */
    fun purgeOrphanEmbeddings(db: SQLiteDatabase): Int = try {
        db.delete(
            TABLE_EMB,
            "$COL_E_INBOX NOT IN (SELECT $COL_ID FROM $TABLE)",
            null
        )
    } catch (t: Throwable) {
        Log.w(TAG, "purgeOrphanEmbeddings failed", t)
        0
    }

    // ==================== 提醒（第 5 步） ====================

    /** 该消息已有提醒则返回它（幂等扫描用）。 */
    fun reminderOfInbox(inboxId: Long): Reminder? =
        readableDatabase.rawQuery(
            "SELECT * FROM $TABLE_REMINDER WHERE $COL_REM_INBOX = ? LIMIT 1",
            arrayOf(inboxId.toString())
        ).use { c -> if (c.moveToNext()) readReminder(c) else null }

    /** 新建提醒；同一消息重复调用直接返回已有 id（不做重复插入）。 */
    fun upsertReminder(
        inboxId: Long,
        dueAt: Long,
        title: String,
        body: String,
        repeatRule: String = "",
        confidence: String = ReminderConfidence.HIGH,
        basis: String = "",
        status: String = Reminder.STATUS_SCHEDULED
    ): Long {
        reminderOfInbox(inboxId)?.let { return it.id }
        val cv = ContentValues().apply {
            put(COL_REM_INBOX, inboxId)
            put(COL_REM_DUE, dueAt)
            put(COL_REM_TITLE, title)
            put(COL_REM_BODY, body)
            put(COL_REM_STATUS, status)
            put(COL_REM_NOTIFIED, 0)
            put(COL_REPEAT, repeatRule)
            put(COL_REM_CONF, confidence)
            put(COL_REM_BASIS, basis)
        }
        return writableDatabase.insert(TABLE_REMINDER, null, cv)
    }

    /**
     * 同刻（±[toleranceMs]）且标题前 12 字相同的待响提醒 = 同一件事被重复推进来。
     * 真机事故里同一条「小刻…19:30」被排了 7 条闹钟，靠这条兜住。
     */
    fun similarScheduledReminder(dueAt: Long, title: String, toleranceMs: Long = 120_000L): Reminder? {
        val key = title.replace(Regex("\\s+"), "").take(12)
        if (key.length < 4) return null
        return scheduledReminders(300).firstOrNull { rem ->
            kotlin.math.abs(rem.dueAt - dueAt) <= toleranceMs &&
                rem.title.replace(Regex("\\s+"), "").take(12) == key
        }
    }

    /** 改期（稍后提醒 / 手工修正），顺手把状态拉回 scheduled。 */
    fun rescheduleReminder(id: Long, dueAt: Long, countSnooze: Boolean = false) {
        writableDatabase.update(
            TABLE_REMINDER,
            ContentValues().apply {
                put(COL_REM_DUE, dueAt)
                put(COL_REM_STATUS, Reminder.STATUS_SCHEDULED)
                put(COL_REM_FIRED, 0)
                // 重排之后要能重新出通知：不清 notified 会被合批器当成"已发过"而跳过（T-C4 实测）
                put(COL_REM_NOTIFIED, 0)
            },
            "$COL_REM_ID = ?", arrayOf(id.toString())
        )
        if (countSnooze) {
            // 「稍后」要累计次数，否则通知里那句「已推迟 N 次」永远不会出现（T-C4 实测：count 恒为 0）
            writableDatabase.execSQL(
                "UPDATE $TABLE_REMINDER SET $COL_REM_SNOOZE = $COL_REM_SNOOZE + 1 WHERE $COL_REM_ID = ?",
                arrayOf(id)
            )
        }
    }

    /** 到点落库（v7）：status=fired + fired_at，并把 notified 复位成 0，等合并器来弹。 */
    fun markReminderFired(id: Long, firedAt: Long) {
        writableDatabase.update(
            TABLE_REMINDER,
            ContentValues().apply {
                put(COL_REM_STATUS, Reminder.STATUS_FIRED)
                put(COL_REM_FIRED, firedAt)
                put(COL_REM_NOTIFIED, 0)
            },
            "$COL_REM_ID = ?", arrayOf(id.toString())
        )
    }

    fun setReminderStatus(id: Long, status: String, firedAt: Long = 0L) {
        writableDatabase.update(
            TABLE_REMINDER,
            ContentValues().apply {
                put(COL_REM_STATUS, status)
                if (firedAt > 0) put(COL_REM_FIRED, firedAt)
            },
            "$COL_REM_ID = ?", arrayOf(id.toString())
        )
    }

    fun setReminderCalendarEvent(id: Long, eventId: Long) {
        writableDatabase.update(
            TABLE_REMINDER, ContentValues().apply { put(COL_REM_CAL, eventId) },
            "$COL_REM_ID = ?", arrayOf(id.toString())
        )
    }

    /** v7：标记「通知已经弹过了」，合并出通知时用，避免重复弹。 */
    fun setReminderNotified(id: Long, notified: Boolean = true) {
        try {
            writableDatabase.update(
                TABLE_REMINDER,
                ContentValues().apply { put(COL_REM_NOTIFIED, if (notified) 1 else 0) },
                "$COL_REM_ID = ?", arrayOf(id.toString())
            )
        } catch (t: Throwable) {
            Log.w(TAG, "setReminderNotified failed id=$id", t)
        }
    }

    /** 周期事项响过之后排下一次（只改 due_at，其余状态复位）。 */
    fun rescheduleNextOccurrence(id: Long, nextDue: Long) {
        writableDatabase.update(
            TABLE_REMINDER,
            ContentValues().apply {
                put(COL_REM_DUE, nextDue)
                put(COL_REM_STATUS, Reminder.STATUS_SCHEDULED)
                put(COL_REM_FIRED, 0)
                put(COL_REM_NOTIFIED, 0)
            },
            "$COL_REM_ID = ?", arrayOf(id.toString())
        )
    }

    /** 最近完成/取消的提醒（回收站：误点「完成」还能捞回来）。 */
    fun recentlyClosed(limit: Int = 10): List<Reminder> =
        readableDatabase.rawQuery(
            "SELECT * FROM $TABLE_REMINDER WHERE $COL_REM_STATUS IN " +
                "('${Reminder.STATUS_DONE}','${Reminder.STATUS_CANCELED}') " +
                "ORDER BY $COL_REM_FIRED DESC, $COL_REM_ID DESC LIMIT $limit",
            null
        ).use { c -> buildList { while (c.moveToNext()) add(readReminder(c)) } }

    /** 推迟计数 +1（待办跟踪：老是被推迟的事该换个时间）。 */
    fun bumpSnooze(id: Long): Int {
        try {
            writableDatabase.execSQL(
                "UPDATE $TABLE_REMINDER SET $COL_REM_SNOOZE = $COL_REM_SNOOZE + 1 WHERE $COL_REM_ID = ?",
                arrayOf<Any>(id)
            )
            if (reminderById(id) != null) return reminderById(id)!!.snoozeCount
        } catch (_: Throwable) {}
        return 0
    }

    /** 已到点但还没弹过通知的（合并出通知的取数口）。 */
    fun firedUnnotified(limit: Int = 100): List<Reminder> =
        readableDatabase.rawQuery(
            "SELECT * FROM $TABLE_REMINDER WHERE $COL_REM_STATUS = '${Reminder.STATUS_FIRED}' " +
                "AND $COL_REM_NOTIFIED = 0 ORDER BY $COL_REM_DUE ASC LIMIT $limit",
            null
        ).use { c -> buildList { while (c.moveToNext()) add(readReminder(c)) } }

    /** 某时刻之后响过的提醒（一分钟内连响多条时用来折叠成一条汇总）。 */
    fun firedSince(since: Long, limit: Int = 100): List<Reminder> =
        readableDatabase.rawQuery(
            "SELECT * FROM $TABLE_REMINDER WHERE $COL_REM_STATUS = '${Reminder.STATUS_FIRED}' " +
                "AND $COL_REM_FIRED >= ? ORDER BY $COL_REM_DUE ASC LIMIT $limit",
            arrayOf(since.toString())
        ).use { c -> buildList { while (c.moveToNext()) add(readReminder(c)) } }

    /** 所有待响提醒（含已过时未响的，供开机/重装后补排）。 */
    fun scheduledReminders(limit: Int = 100): List<Reminder> =
        readableDatabase.rawQuery(
            "SELECT * FROM $TABLE_REMINDER WHERE $COL_REM_STATUS = '${Reminder.STATUS_SCHEDULED}' " +
                "ORDER BY $COL_REM_DUE ASC LIMIT $limit",
            null
        ).use { c -> buildList { while (c.moveToNext()) add(readReminder(c)) } }

    /** 已响但还没处理的（通知栏里等用户点「完成」那批）。 */
    fun firedReminders(limit: Int = 20): List<Reminder> =
        readableDatabase.rawQuery(
            "SELECT * FROM $TABLE_REMINDER WHERE $COL_REM_STATUS = '${Reminder.STATUS_FIRED}' " +
                "ORDER BY $COL_REM_DUE DESC LIMIT $limit",
            null
        ).use { c -> buildList { while (c.moveToNext()) add(readReminder(c)) } }

    fun reminderById(id: Long): Reminder? =
        readableDatabase.rawQuery(
            "SELECT * FROM $TABLE_REMINDER WHERE $COL_REM_ID = ? LIMIT 1",
            arrayOf(id.toString())
        ).use { c -> if (c.moveToNext()) readReminder(c) else null }

    /** T-D2：同一时刻、同一标题的提醒曾被取消过（说明用户不想要它）。 */
    fun hasCanceledSame(dueAt: Long, title: String): Boolean =
        readableDatabase.rawQuery(
            "SELECT 1 FROM $TABLE_REMINDER WHERE $COL_REM_STATUS = ? AND $COL_REM_DUE = ? AND $COL_REM_TITLE = ? LIMIT 1",
            arrayOf(Reminder.STATUS_CANCELED, dueAt.toString(), title)
        ).use { it.moveToFirst() }

    /** T-D1：待确认提醒（中置信，没排闹钟）。 */
    fun pendingReminders(limit: Int = 20): List<Reminder> =
        readableDatabase.rawQuery(
            "SELECT * FROM $TABLE_REMINDER WHERE $COL_REM_STATUS = ? ORDER BY $COL_REM_DUE ASC LIMIT $limit",
            arrayOf(Reminder.STATUS_PENDING)
        ).use { c -> buildList { while (c.moveToNext()) add(readReminder(c)) } }

    /**
     * T-D1 验收：待确认**超期自动归档**。
     *
     * 中置信事项放了一整天用户都没点确认，那个时刻早就过去了 —— 再留在「待确认」里
     * 只是把列表越堆越长（面板只显示 20 条，真过期货会把新货挤掉）。
     * 归档成 `expired` 而不是删除：误报统计还要用，用户也不会因为一次没确认就找不到痕迹。
     *
     * @param before 早于这个时刻到期的 pending 全部归档（调用方传 now - 宽限期）
     * @return 归档条数
     */
    fun expirePendingBefore(before: Long): Int {
        if (before <= 0) return 0
        return try {
            val cv = ContentValues().apply { put(COL_REM_STATUS, Reminder.STATUS_EXPIRED) }
            writableDatabase.update(
                TABLE_REMINDER, cv,
                "$COL_REM_STATUS = ? AND $COL_REM_DUE > 0 AND $COL_REM_DUE < ?",
                arrayOf(Reminder.STATUS_PENDING, before.toString())
            )
        } catch (t: Throwable) {
            Log.w(TAG, "expirePendingBefore failed", t)
            0
        }
    }

    /** 列名容错读取：老库可能还没补列 */
    private fun idxString(c: Cursor, name: String): String? {
        val i = c.getColumnIndex(name)
        return if (i >= 0) c.getString(i) else null
    }

    fun activeReminderCount(): Int =
        readableDatabase.rawQuery(
            "SELECT COUNT(*) FROM $TABLE_REMINDER WHERE $COL_REM_STATUS = '${Reminder.STATUS_SCHEDULED}'",
            null
        ).use { c -> if (c.moveToFirst()) c.getInt(0) else 0 }

    private fun readReminder(c: Cursor): Reminder =
        Reminder(
            id = c.getLong(c.getColumnIndexOrThrow(COL_REM_ID)),
            inboxId = c.getLong(c.getColumnIndexOrThrow(COL_REM_INBOX)),
            dueAt = c.getLong(c.getColumnIndexOrThrow(COL_REM_DUE)),
            title = c.getString(c.getColumnIndexOrThrow(COL_REM_TITLE)),
            body = c.getString(c.getColumnIndexOrThrow(COL_REM_BODY)),
            status = c.getString(c.getColumnIndexOrThrow(COL_REM_STATUS)),
            firedAt = c.getLong(c.getColumnIndexOrThrow(COL_REM_FIRED)),
            calendarEventId = c.getLong(c.getColumnIndexOrThrow(COL_REM_CAL)),
            confidence = idxString(c, COL_REM_CONF) ?: ReminderConfidence.HIGH,
            basis = idxString(c, COL_REM_BASIS) ?: "",
            notified = try {
                c.getInt(c.getColumnIndexOrThrow(COL_REM_NOTIFIED)) != 0
            } catch (t: Throwable) { true },  // 老库没有这列时按「已弹过」处理，别补炸
            repeatRule = try { c.getString(c.getColumnIndexOrThrow(COL_REPEAT)) ?: "" }
            catch (t: Throwable) { "" },
            snoozeCount = try { c.getInt(c.getColumnIndexOrThrow(COL_REM_SNOOZE)) }
            catch (t: Throwable) { 0 }
        )

    // ==================== 向量（v11） ====================

    fun putEmbedding(inboxId: Long, vec: FloatArray) {
        val bb = java.nio.ByteBuffer.allocate(vec.size * 4).order(java.nio.ByteOrder.LITTLE_ENDIAN)
        for (f in vec) bb.putFloat(f)
        try {
            writableDatabase.insertWithOnConflict(
                TABLE_EMB, null,
                ContentValues().apply {
                    put(COL_E_INBOX, inboxId); put(COL_E_VEC, bb.array())
                    put(COL_E_AT, System.currentTimeMillis())
                },
                SQLiteDatabase.CONFLICT_REPLACE
            )
        } catch (_: Throwable) {}
    }

    /** 取某时间窗内的 (inboxId, 向量)，最多 [limit] 条（新的优先）。 */
    fun embeddingsInWindow(from: Long, to: Long, limit: Int = 400): List<Pair<Long, FloatArray>> =
        readableDatabase.rawQuery(
            "SELECT e.$COL_E_INBOX, e.$COL_E_VEC FROM $TABLE_EMB e " +
                "JOIN $TABLE i ON i.$COL_ID = e.$COL_E_INBOX " +
                "WHERE i.$COL_RECEIVED BETWEEN ? AND ? ORDER BY i.$COL_RECEIVED DESC LIMIT $limit",
            arrayOf(from.toString(), to.toString())
        ).use { c ->
            buildList {
                while (c.moveToNext()) {
                    val blob = c.getBlob(1) ?: continue
                    val bb = java.nio.ByteBuffer.wrap(blob).order(java.nio.ByteOrder.LITTLE_ENDIAN)
                    val arr = FloatArray(blob.size / 4)
                    for (k in arr.indices) arr[k] = bb.float
                    add(c.getLong(0) to arr)
                }
            }
        }

    fun missingEmbeddingRows(limit: Int = 60): List<InboxRecord> =
        readableDatabase.rawQuery(
            "SELECT i.* FROM $TABLE i LEFT JOIN $TABLE_EMB e ON e.$COL_E_INBOX = i.$COL_ID " +
                "WHERE e.$COL_E_INBOX IS NULL ORDER BY i.$COL_ID DESC LIMIT $limit",
            null
        ).use { c -> buildList { while (c.moveToNext()) add(readRow(c)) } }

    fun recordById(id: Long): InboxRecord? =
        readableDatabase.rawQuery("SELECT * FROM $TABLE WHERE $COL_ID = ?", arrayOf(id.toString()))
            .use { if (it.moveToNext()) readRow(it) else null }

    // ==================== 技能（v10） ====================

    fun upsertSkill(kind: String, pattern: String, arg: String = ""): Long {
        val cv = ContentValues().apply {
            put(COL_S_KIND, kind)
            put(COL_S_PATTERN, pattern)
            put(COL_S_ARG, arg)
            put(COL_S_ENABLED, 1)
            put(COL_S_AT, System.currentTimeMillis())
        }
        // 同 kind + pattern 视为同一条规则，覆盖 arg 并重新启用
        return writableDatabase.insertWithOnConflict(
            TABLE_SKILL, null, cv, SQLiteDatabase.CONFLICT_REPLACE
        )
    }

    fun listSkills(): List<Triple<String, String, String>> =
        readableDatabase.rawQuery(
            "SELECT $COL_S_KIND, $COL_S_PATTERN, $COL_S_ARG FROM $TABLE_SKILL " +
                "WHERE $COL_S_ENABLED = 1 ORDER BY $COL_S_ID ASC",
            null
        ).use { c -> buildList { while (c.moveToNext()) add(Triple(c.getString(0), c.getString(1), c.getString(2))) } }

    fun bumpSkillHits(kind: String, pattern: String) {
        try {
            writableDatabase.execSQL(
                "UPDATE $TABLE_SKILL SET $COL_S_HITS = $COL_S_HITS + 1 WHERE $COL_S_KIND = ? AND $COL_S_PATTERN = ?",
                arrayOf<Any>(kind, pattern)
            )
        } catch (_: Throwable) {}
    }

    fun deleteSkill(kind: String, pattern: String): Int =
        writableDatabase.delete(TABLE_SKILL, "$COL_S_KIND = ? AND $COL_S_PATTERN = ?", arrayOf(kind, pattern))

    // ==================== 出网留档（第 9 步） ====================

    /** 记一次出网：只写脱敏摘要，原始内容不进来。 */
    fun appendCloudLog(
        task: String,
        host: String,
        model: String,
        requestRedacted: String,
        ok: Boolean,
        note: String = ""
    ) {
        try {
            writableDatabase.insert(TABLE_CLOUD, null, ContentValues().apply {
                put(COL_C_AT, System.currentTimeMillis())
                put(COL_C_TASK, task.take(20))
                put(COL_C_HOST, host.take(60))
                put(COL_C_MODEL, model.take(40))
                put(COL_C_REQ, requestRedacted.take(300))
                put(COL_C_OK, if (ok) 1 else 0)
                put(COL_C_NOTE, note.take(80))
            })
        } catch (t: Throwable) {
            Log.w(TAG, "appendCloudLog failed", t)
        }
    }

    /** 最近 N 条出网记录（新→旧）。 */
    fun recentCloudLog(limit: Int = 20): List<Array<String>> =
        readableDatabase.rawQuery(
            "SELECT $COL_C_AT, $COL_C_TASK, $COL_C_HOST, $COL_C_MODEL, $COL_C_REQ, $COL_C_OK, $COL_C_NOTE " +
                "FROM $TABLE_CLOUD ORDER BY $COL_C_AT DESC LIMIT $limit",
            null
        ).use { c ->
            buildList {
                while (c.moveToNext()) {
                    add(arrayOf(
                        c.getLong(0).toString(), c.getString(1) ?: "", c.getString(2) ?: "",
                        c.getString(3) ?: "", c.getString(4) ?: "",
                        if (c.getInt(5) != 0) "成功" else "失败", c.getString(6) ?: ""
                    ))
                }
            }
        }

    fun cloudLogCount(): Int =
        readableDatabase.rawQuery("SELECT COUNT(*) FROM $TABLE_CLOUD", null).use {
            if (it.moveToFirst()) it.getInt(0) else 0
        }

    // ==================== 聊天分区（第 4 步闲聊初版） ====================

    /** 记一轮对话。role: user | assistant */
    fun appendChat(role: String, content: String) {
        if (content.isBlank()) return
        try {
            writableDatabase.insert(TABLE_CHAT, null, ContentValues().apply {
                put(COL_CHAT_ROLE, role)
                put(COL_CHAT_CONTENT, content.take(2000))
                put(COL_CHAT_AT, System.currentTimeMillis())
            })
        } catch (t: Throwable) {
            Log.w(TAG, "appendChat failed", t)
        }
    }

    /** 最近 N 轮，按时间升序返回 (role, content)。 */
    fun recentChat(limit: Int = 6): List<Pair<String, String>> {
        val out = mutableListOf<Pair<String, String>>()
        try {
            readableDatabase.rawQuery(
                "SELECT $COL_CHAT_ROLE, $COL_CHAT_CONTENT FROM $TABLE_CHAT " +
                    "ORDER BY $COL_CHAT_AT DESC LIMIT $limit",
                null
            ).use { c ->
                while (c.moveToNext()) {
                    out.add(Pair(c.getString(0) ?: "", c.getString(1) ?: ""))
                }
            }
        } catch (t: Throwable) {
            Log.w(TAG, "recentChat failed", t)
        }
        out.reverse()
        return out
    }

    /** 公开给同包只读方复用，避免两份 readRow 漂移。 */
    // ==================== 来源规则（v13，阶段 F） ====================

    /**
     * 一条来源的统计，给「来源管理」面板用。
     *
     * [label] 取库里最后一条通知记录的 app_label（采集时记的）。真机实测：
     * Activity 里 `PackageManager.getApplicationInfo(第三方包, 0)` 在 Android 11+ 的
     * 包可见性限制下抛 NameNotFoundException，面板只好回退成包名尾巴
     * （B 站显示成 "in"、微信显示成 "mm"）。采集侧能拿到名字，就别在这儿重新查。
     */
    data class SourceStat(val pkg: String, val count: Int, val lastAt: Long, val label: String = "")

    /** 当前被静音的包。 */
    fun mutedPackages(): Set<String> {
        mutedCache?.let { return it }
        val out = HashSet<String>()
        try {
            readableDatabase.rawQuery(
                "SELECT $COL_SRC_PKG FROM $TABLE_SOURCE WHERE $COL_SRC_MUTED = 1", null
            ).use { c -> while (c.moveToNext()) out.add(c.getString(0)) }
        } catch (t: Throwable) {
            Log.w(TAG, "mutedPackages failed", t)
        }
        return out.also { mutedCache = it }
    }

    fun isSourceMuted(pkg: String): Boolean = mutedPackages().contains(pkg)

    fun setSourceMuted(pkg: String, muted: Boolean) {
        // INSERT OR REPLACE 而不是 UPSERT：minSdk 24 的 SQLite 3.9 没有 ON CONFLICT DO UPDATE
        writableDatabase.execSQL(
            "INSERT OR REPLACE INTO $TABLE_SOURCE($COL_SRC_PKG,$COL_SRC_MUTED,$COL_SRC_AT) VALUES(?,?,?)",
            arrayOf<Any>(pkg, if (muted) 1 else 0, System.currentTimeMillis())
        )
        mutedCache = null
    }

    /** 收件箱里条数最多的来源（含包名，不含已删除的）。 */
    fun topSources(limit: Int = 30): List<SourceStat> =
        readableDatabase.rawQuery(
            "SELECT $COL_PACKAGE, COUNT(*), MAX($COL_RECEIVED), MAX($COL_LABEL) FROM $TABLE " +
                "WHERE $COL_PACKAGE <> '' GROUP BY $COL_PACKAGE ORDER BY COUNT(*) DESC LIMIT $limit", null
        ).use { c ->
            buildList {
                while (c.moveToNext()) {
                    add(SourceStat(c.getString(0), c.getInt(1), c.getLong(2), c.getString(3).orEmpty()))
                }
            }
        }

    fun inboxIdsOfPackage(pkg: String): List<Long> =
        readableDatabase.rawQuery(
            "SELECT $COL_ID FROM $TABLE WHERE $COL_PACKAGE = ?", arrayOf(pkg)
        ).use { c -> buildList { while (c.moveToNext()) add(c.getLong(0)) } }

    /**
     * 删掉某来源的历史行（收件箱 + 挂在它们上面的提醒与向量；**账本保留**）。
     * **不撤闹钟**：闹钟要 Context，调用方先按 [inboxIdsOfPackage] 撤，再来删（见 SourceRules.purge）。
     */
    fun deleteRowsOfPackage(pkg: String): Int {
        val db = writableDatabase
        db.beginTransaction()
        try {
            // 账本**不删**：金额/分类/发生时间是自包含的财务记录，不靠 inbox 外键活着。
            // 用户静音一个刷屏 App 不该顺带抹掉自己的收支（静音点错了也可能）。
            db.delete(TABLE_EMB, "$COL_E_INBOX IN (SELECT $COL_ID FROM $TABLE WHERE $COL_PACKAGE = ?)", arrayOf(pkg))
            db.delete(TABLE_REMINDER, "$COL_REM_INBOX IN (SELECT $COL_ID FROM $TABLE WHERE $COL_PACKAGE = ?)", arrayOf(pkg))
            val n = db.delete(TABLE, "$COL_PACKAGE = ?", arrayOf(pkg))
            db.setTransactionSuccessful()
            return n
        } finally {
            db.endTransaction()
        }
    }

    fun readRow(c: Cursor): InboxRecord =
        InboxRecord(
            id = c.getLong(c.getColumnIndexOrThrow(COL_ID)),
            source = c.getString(c.getColumnIndexOrThrow(COL_SOURCE)),
            packageName = c.getString(c.getColumnIndexOrThrow(COL_PACKAGE)),
            appLabel = c.getString(c.getColumnIndexOrThrow(COL_LABEL)),
            title = c.getString(c.getColumnIndexOrThrow(COL_TITLE)),
            text = c.getString(c.getColumnIndexOrThrow(COL_TEXT)),
            receivedAt = c.getLong(c.getColumnIndexOrThrow(COL_RECEIVED)),
            pendingExtract = c.getInt(c.getColumnIndexOrThrow(COL_PENDING)),
            url = c.getString(c.getColumnIndexOrThrow(COL_URL)),
            summary = c.getString(c.getColumnIndexOrThrow(COL_SUMMARY)),
            category = c.getString(c.getColumnIndexOrThrow(COL_CATEGORY)),
            deadlineAt = c.getLong(c.getColumnIndexOrThrow(COL_DEADLINE)),
            amount = c.getDouble(c.getColumnIndexOrThrow(COL_AMOUNT)),
            note = c.getString(c.getColumnIndexOrThrow(COL_NOTE)),
            repeatRule = try { c.getString(c.getColumnIndexOrThrow(COL_REPEAT)) ?: "" }
            catch (t: Throwable) { "" }
        )
}
