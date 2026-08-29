package com.ecs.core.model

import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable

/**
 * 只录错题会漏掉蒙对的题，所以蒙对单独标一档。
 * 确认页逐题标记，不标的既不入库也不分析。
 */
@Serializable
enum class Confidence(val label: String) {
    @SerialName("错") WRONG("错"),
    @SerialName("蒙对") LUCKY("蒙对");

    companion object {
        fun fromLabel(s: String): Confidence? = entries.firstOrNull { it.label == s }
    }
}

/** 一条记录处在流程的哪一步。只有这三档：其余状态随统计层一起废弃了。 */
@Serializable
enum class RecordStatus(val label: String) {
    @SerialName("已分析") ANALYZED("已分析"),
    @SerialName("待补答案") PENDING_ANSWER("待补答案"),
    @SerialName("待分析") PENDING("待分析");

    companion object {
        fun fromLabel(s: String): RecordStatus? = entries.firstOrNull { it.label == s }
    }
}

/**
 * 题源。一次写入，永不修改。
 *
 * 不再区分语法填空 / 完成句子——录入的判据只有一条：句子 ≥1、句中空 ≥1。
 */
@Serializable
data class Src(
    val paper: String,
    val no: Int,
    val slot: Int,
    val batch: String,
)

/**
 * 原始数据：一个「空」= 一条记录。
 *
 * 分成两半：上半是识别与你确认过的原始事实，下半是「分析」这一步推出来的。
 * 分析可以重跑、可以换模型、可以改提示词，原始事实不动——所以两半必须分得清。
 *
 * id 形如 q_034_1（题号 + 空序），跨卷会重复；[uid] = "{paper}#{id}" 才是主键。
 */
@Serializable
data class ErrorRecord(
    // ---- 原始数据 ----
    val id: String,
    val src: Src,
    val srcRef: String? = null,
    /** 题干全文。一切的来源，识别错了要能改回来。 */
    val stem: String? = null,
    val given: String? = null,
    val answer: String? = null,
    val confidence: Confidence,
    val createdAt: Long,

    // ---- 分析产出 ----
    /** 所属板块，形如 `词法/名词`，取自骨架 19 支。 */
    val branch: String? = null,
    /** 答案形式：这个空该填成什么。由分析结合上下文推断，不是查表查来的。 */
    val formShape: String? = null,
    /** 判断依据：看到什么客观特征才推出上面那个形态。 */
    val basis: String? = null,
    /** 语境限定：本句独有，一次性，不参与汇总。 */
    val formContext: String? = null,
    val note: String? = null,

    val status: RecordStatus = RecordStatus.PENDING,
) {
    val uid: String get() = "${src.paper}#$id"

    /** 分析是否跑完并且产出可用。 */
    fun analyzed(): Boolean = !branch.isNullOrBlank() && !formShape.isNullOrBlank()
}
