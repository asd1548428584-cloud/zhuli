package com.zhuli.core

/** 提取流水线产出（规则版字段；第 9 步起模型版只换实现不换契约）。 */
data class ExtractFields(
    val category: String,     // 快递 | 金融 | 工作 | 生活 | 其他
    val deadlineTs: Long,     // 0 = 未识别或周期性事项（周期型归提醒引擎）
    val amount: Double,       // 0 = 未识别金额
    val note: String,         // 待办/备注的简述（原文截断，供提醒用）
    /** 第 14 步：周期规则（daily / weekly:1-7 / monthly:1-31）；空 = 一次性 */
    val repeatRule: String = ""
)