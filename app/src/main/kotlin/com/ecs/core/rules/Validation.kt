package com.ecs.core.rules

import com.ecs.core.model.ErrorRecord
import com.ecs.core.model.RecordStatus
import com.ecs.core.parse.SlotSpec
import com.ecs.core.tree.KaodianTree

/**
 * F1.6 录入校验 + 2.4 题眼质量红线。
 *
 * 设计铁律：录入永不阻断。校验的产物是 status 与提示，不是拒绝写入——
 * 唯一会真正拒绝的是 id 非法/重复，因为那会破坏主键。
 */
object Validation {

    const val EYE_MIN = 10
    const val EYE_MAX = 25
    const val FORM_CONTEXT_MAX = 20
    const val IDENTIFY_CONF_FLOOR = 0.8

    /** 2.4 禁用词：出现即说明写的是考点/解释，不是客观特征。 */
    val EYE_BANNED = listOf("考查", "需要", "应该", "主要", "判断")

    data class Issue(val field: String, val message: String, val blocking: Boolean = false)

    fun checkEye(eye: String?): List<Issue> {
        if (eye.isNullOrBlank()) return listOf(Issue("eye", "题眼缺失"))
        val issues = mutableListOf<Issue>()
        val len = eye.trim().length
        if (len < EYE_MIN) issues += Issue("eye", "题眼过短（$len 字，需 $EYE_MIN-$EYE_MAX）")
        if (len > EYE_MAX) issues += Issue("eye", "题眼过长（$len 字，需 $EYE_MIN-$EYE_MAX）")
        EYE_BANNED.filter { eye.contains(it) }.forEach {
            issues += Issue("eye", "题眼含禁用词「$it」：写客观特征，不写考点或解释")
        }
        return issues
    }

    fun checkFormContext(fc: String?): List<Issue> {
        if (fc.isNullOrBlank()) return emptyList()
        return if (fc.trim().length > FORM_CONTEXT_MAX) {
            listOf(Issue("form_context", "语境形态过长（上限 $FORM_CONTEXT_MAX 字）"))
        } else emptyList()
    }

    fun checkKaodian(kaodian: String?, tree: KaodianTree): List<Issue> {
        if (kaodian.isNullOrBlank()) return listOf(Issue("kaodian", "考点缺失"))
        if (!tree.contains(kaodian)) {
            return listOf(Issue("kaodian", "考点不存在于当前树 ${tree.version}（可能已合并）"))
        }
        return emptyList()
    }

    /**
     * 全量校验。form_rule 不校验：由系统从考点树填充，一致性由结构保证。
     */
    fun validate(
        record: ErrorRecord,
        tree: KaodianTree?,
        identifyConf: Double? = null,
        existingUids: Set<String> = emptySet(),
    ): List<Issue> {
        val issues = mutableListOf<Issue>()

        if (!SlotSpec.isValidId(record.id)) {
            issues += Issue("id", "id 格式不匹配 {gf|wc}_{三位题号}_{空序}", blocking = true)
        }
        if (record.uid in existingUids) {
            issues += Issue("id", "同卷内 id 重复：${record.id}", blocking = true)
        }
        if (record.src.paper.isBlank()) issues += Issue("src.paper", "卷名缺失", blocking = true)

        if (record.eye != null || record.kaodian != null) {
            issues += checkEye(record.eye)
            issues += checkFormContext(record.formContext)
            if (tree != null) issues += checkKaodian(record.kaodian, tree)
        }
        if (identifyConf != null && identifyConf < IDENTIFY_CONF_FLOOR) {
            issues += Issue("identify_conf", "识别置信度 %.2f < %.2f".format(identifyConf, IDENTIFY_CONF_FLOOR))
        }
        return issues
    }

    /**
     * 由字段现状推导 status（F1.6）。已归档/休眠的记录不被自动改写。
     */
    fun deriveStatus(
        record: ErrorRecord,
        tree: KaodianTree?,
        identifyConf: Double? = null,
    ): RecordStatus {
        if (record.status == RecordStatus.ARCHIVED || record.status == RecordStatus.DORMANT) return record.status
        if (identifyConf != null && identifyConf < IDENTIFY_CONF_FLOOR) return RecordStatus.INCOMPLETE
        if (!record.annotationComplete()) return RecordStatus.INCOMPLETE
        if (tree != null && !tree.contains(record.kaodian ?: "")) return RecordStatus.INCOMPLETE
        val eyeBad = checkEye(record.eye).isNotEmpty()
        if (eyeBad) return RecordStatus.INCOMPLETE
        if (record.answer.isNullOrBlank()) return RecordStatus.PENDING_ANSWER
        return RecordStatus.ACTIVE
    }
}
