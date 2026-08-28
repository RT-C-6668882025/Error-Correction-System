package com.ecs.core.model

import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable

/** 题型。缩写用于 id 前缀，满分用于加权失分。 */
@Serializable
enum class Section(val label: String, val abbr: String, val fullScore: Int) {
    @SerialName("语法填空") GF("语法填空", "gf", 20),
    @SerialName("完成句子") WC("完成句子", "wc", 18);

    companion object {
        fun fromLabel(s: String): Section? = entries.firstOrNull { it.label == s }
        fun fromAbbr(s: String): Section? = entries.firstOrNull { it.abbr == s }
    }
}

/** 2.6 只录错题会漏掉蒙对的题，蒙对按 0.5 权重计入。 */
@Serializable
enum class Confidence(val label: String, val weight: Double) {
    @SerialName("错") WRONG("错", 1.0),
    @SerialName("蒙对") LUCKY("蒙对", 0.5);

    companion object {
        fun fromLabel(s: String): Confidence? = entries.firstOrNull { it.label == s }
    }
}

@Serializable
enum class Difficulty(val label: String) {
    @SerialName("简单") EASY("简单"),
    @SerialName("中等") MEDIUM("中等"),
    @SerialName("难") HARD("难");

    companion object {
        fun fromLabel(s: String): Difficulty? = entries.firstOrNull { it.label == s }
    }
}

/**
 * 2.3 状态字段。只有 ACTIVE / DORMANT 参与统计数字（[countsInStats]）。
 * DORMANT 参与聚合但不出现在报告中（[showsInReport]）。
 */
@Serializable
enum class RecordStatus(val label: String, val countsInStats: Boolean, val showsInReport: Boolean) {
    @SerialName("活跃") ACTIVE("活跃", true, true),
    @SerialName("休眠") DORMANT("休眠", true, false),
    @SerialName("归档") ARCHIVED("归档", false, false),
    @SerialName("待补答案") PENDING_ANSWER("待补答案", false, false),
    @SerialName("不完整") INCOMPLETE("不完整", false, false);

    companion object {
        fun fromLabel(s: String): RecordStatus? = entries.firstOrNull { it.label == s }
    }
}

/** F8 抽检结果。只作为流程健康度指标，不影响单条数据可用性。 */
@Serializable
enum class Verified(val label: String) {
    @SerialName("未抽检") UNCHECKED("未抽检"),
    @SerialName("一致") CONSISTENT("一致"),
    @SerialName("冲突") CONFLICT("冲突"),
    @SerialName("人工确认") MANUAL("人工确认");

    companion object {
        fun fromLabel(s: String): Verified? = entries.firstOrNull { it.label == s }
    }
}

/** 原子字段中的题源部分。一次写入，永不修改。 */
@Serializable
data class Src(
    val paper: String,
    val section: Section,
    val no: Int,
    val slot: Int,
    val batch: String,
    /** 该卷该题型总空数，错误率分母，可后补（F1.4 同理：不阻断录入）。 */
    val totalInSection: Int? = null,
)

/**
 * L0：一个「空」= 一条记录，唯一真实数据源。
 *
 * id 形如 gf_034_1 / wc_012_2，按 PRD 2.2 只含题型缩写+题号+空序，
 * 因此跨卷会重复；[uid] = "{paper}#{id}" 才是全局主键。
 */
@Serializable
data class ErrorRecord(
    // ---- 原子字段 ----
    val id: String,
    val src: Src,
    val srcRef: String? = null,
    val given: String? = null,
    val answer: String? = null,
    val confidence: Confidence,
    val createdAt: Long,

    // ---- 派生字段 ----
    val eye: String? = null,
    val kaodian: String? = null,
    /** 由考点树节点带出，只读，Agent 不参与生成。 */
    val formRule: String? = null,
    val formContext: String? = null,
    val secondary: List<String> = emptyList(),
    val difficulty: Difficulty? = null,
    val coError: List<String> = emptyList(),
    val treeVersion: String? = null,
    val verified: Verified = Verified.UNCHECKED,
    val note: String? = null,

    // ---- 状态 ----
    val status: RecordStatus = RecordStatus.INCOMPLETE,
    /** 4.4 休眠计算用：进入休眠后下次检查时间。 */
    val nextCheck: Long? = null,
) {
    val uid: String get() = "${src.paper}#$id"
    val weight: Double get() = confidence.weight

    /** 派生字段是否齐备（决定能否从「不完整」转为可用状态）。 */
    fun annotationComplete(): Boolean =
        !eye.isNullOrBlank() && !kaodian.isNullOrBlank() &&
            !formRule.isNullOrBlank() && difficulty != null && !treeVersion.isNullOrBlank()
}
