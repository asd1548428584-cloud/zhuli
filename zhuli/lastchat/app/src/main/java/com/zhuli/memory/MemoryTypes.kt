package com.zhuli.memory

/**
 * 记忆核心的数据模型（v1）。
 *
 * 设计口径（与 CURRENT_STATE 的 M1 对应）：
 * - **事实（Fact）是记忆的最小单位**，不是聊天记录。一条事实 = 主体 + 谓词 + 值。
 * - 每条事实必须能**追到来源**（sourceType/sourceId/sourceText），否则不配叫记忆。
 * - 事实有生命周期：ACTIVE → SUPERSEDED（被新值取代）→ 或被用户删成 DELETED。
 *   历史不物理删除，才能回答「以前是什么」以及「谁改的」。
 * - 置信度区分「原文里明确写的」与「我猜的」，UI 必须能分开显示。
 *
 * 本文件**不 import android.*** —— 抽取与排序全部能在 JVM 单测里跑。
 */

enum class EntityKind {
    PERSON, ORG, PLACE, PROJECT, PRODUCT, OTHER;

    companion object {
        fun of(raw: String): EntityKind = entries.firstOrNull { it.name == raw } ?: OTHER
    }
}

enum class FactKind {
    /** 取件码 / 快递单号 */
    EXPRESS,

    /** 金额：支出、收入、余额 */
    MONEY,

    /** 订单号、流水号、单号 */
    ORDER,

    /** 时间承诺：会议、截止、出发 */
    SCHEDULE,

    /** 地址 */
    ADDRESS,

    /** 电话 */
    PHONE,

    /** 待办（用户自己说要做的） */
    TODO,

    /** 用户偏好 / 长期设定 */
    PREFERENCE,

    OTHER;

    companion object {
        fun of(raw: String): FactKind = entries.firstOrNull { it.name == raw } ?: OTHER
    }
}

enum class FactStatus {
    ACTIVE,

    /**
     * 历史回填时置信度不够的事实：先存下来但不参与检索、不覆盖任何现有记忆，
     * 等用户在记忆页确认后再转 ACTIVE。存在它的唯一理由是"宁可多留一条待确认，
     * 也不要让低置信度的推断悄悄变成用户的事实"。
     */
    UNCONFIRMED,

    SUPERSEDED, DELETED;

    companion object {
        fun of(raw: String): FactStatus = entries.firstOrNull { it.name == raw } ?: ACTIVE
    }
}

/** 事实的来源类型。数字是 id 的命名空间，别混用。 */
enum class SourceType {
    /** inbox 表的一行（通知 / 短信 / 分享） */
    INBOX,

    /** 用户当面说的（对话里「记住…」） */
    USER,

    /** 助理从已有事实推出来的（必须降置信度） */
    INFERRED,

    /** 系统能力写入（日历、提醒） */
    SYSTEM
}

data class Entity(
    val id: Long = 0L,
    val kind: EntityKind,
    val name: String,
    /** 归一化名字（去空格、全角转半角、小写），查重用 */
    val norm: String,
    val aliases: String = "",
    val createdAt: Long = 0L,
    val updatedAt: Long = 0L,
)

/**
 * 一条结构化事实。
 *
 * [subject] 用可读名字而不是外键：实体表可能还没有这一行（离线抽取），
 * 强行外键会让「抽到一半失败」变成丢数据。实体表是**索引**，不是唯一真源。
 */
data class Fact(
    val id: Long = 0L,
    val subject: String,
    val predicate: String,
    val value: String,
    val kind: FactKind = FactKind.OTHER,
    /** 0..1。≥0.8 = 原文明确；0.5~0.8 = 需要推断；<0.5 = 仅供参考 */
    val confidence: Double = 0.6,
    val sourceType: SourceType = SourceType.INBOX,
    val sourceId: Long = 0L,
    /** 来源原文片段（截断），用于 UI 上「凭什么」 */
    val sourceText: String = "",
    val observedAt: Long = 0L,
    val validFrom: Long = 0L,
    val validTo: Long = 0L,
    val status: FactStatus = FactStatus.ACTIVE,
    val supersededBy: Long = 0L,
    val createdAt: Long = 0L,
    val updatedAt: Long = 0L,
) {
    /** 给人看的一行：张三｜电话｜138xxxx */
    val display: String get() = "$subject｜$predicate｜$value"

    /** 同主体同谓词 —— 用来判「是不是在说同一件事」。 */
    val slot: String get() = "$subject|$predicate"
}

/** 抽取器的输出：还没落库的事实草稿。 */
data class FactDraft(
    val subject: String,
    val predicate: String,
    val value: String,
    val kind: FactKind,
    val confidence: Double,
    val sourceText: String,
    val entityKind: EntityKind? = null,
)

/** 检索命中：事实 + 排序分。 */
data class FactHit(
    val fact: Fact,
    val score: Double,
    /** 哪几路命中：字面 / 实体 / 语义 / 近期… */
    val reasons: List<String> = emptyList(),
    /**
     * 与查询的真实相关性信号（字面 / 实体 / 类型意图）。
     * 时效与置信度只能**加权**，不能单独把一条毫不相干的事实送进结果里。
     */
    val match: Double = 0.0,
)
