package com.ecs.core.rules

import com.ecs.core.model.ErrorRecord
import com.ecs.core.model.RecordStatus
import com.ecs.core.parse.SlotSpec
import com.ecs.core.tree.Skeleton

/**
 * 录入校验 + 判断依据的质量红线。
 *
 * 设计铁律：录入永不阻断。校验的产物是 status 与提示，不是拒绝写入——
 * 唯一会真正拒绝的是 id 非法/重复，因为那会破坏主键。
 */
object Validation {

    const val BASIS_MIN = 10
    const val BASIS_MAX = 25
    const val FORM_CONTEXT_MAX = 20
    const val IDENTIFY_CONF_FLOOR = 0.8

    /** 出现即说明写的是结论或解释，不是看得见的特征。 */
    val BASIS_BANNED_FIELD = listOf("考查", "需要", "应该", "主要", "判断")

    /**
     * 选择题剥离后的禁用词。选项不落库，判断依据里引用选项等于引用了不存在的东西——
     * 这条记录换个模型重跑就失效了。
     */
    val BASIS_BANNED_CHOICE = listOf("选项", "排除", "A项", "B项", "C项", "D项")

    val BASIS_BANNED: List<String> = BASIS_BANNED_FIELD + BASIS_BANNED_CHOICE

    data class Issue(val field: String, val message: String, val blocking: Boolean = false)

    fun checkBasis(basis: String?): List<Issue> {
        if (basis.isNullOrBlank()) return listOf(Issue("basis", "判断依据缺失"))
        val issues = mutableListOf<Issue>()
        val len = basis.trim().length
        if (len < BASIS_MIN) issues += Issue("basis", "判断依据过短（$len 字，需 $BASIS_MIN-$BASIS_MAX）")
        if (len > BASIS_MAX) issues += Issue("basis", "判断依据过长（$len 字，需 $BASIS_MIN-$BASIS_MAX）")
        BASIS_BANNED_FIELD.filter { basis.contains(it) }.forEach {
            issues += Issue("basis", "含禁用词「$it」：写客观特征，不写结论或解释")
        }
        BASIS_BANNED_CHOICE.filter { basis.contains(it) }.forEach {
            issues += Issue("basis", "含禁用词「$it」：选项不落库，依据只能描述题干")
        }
        return issues
    }

    fun checkFormContext(fc: String?): List<Issue> {
        if (fc.isNullOrBlank()) return emptyList()
        return if (fc.trim().length > FORM_CONTEXT_MAX) {
            listOf(Issue("form_context", "语境限定过长（上限 $FORM_CONTEXT_MAX 字）"))
        } else emptyList()
    }

    /** 板块必须命中骨架十九支之一，否则这条题在复习页无处可去。 */
    fun checkBranch(branch: String?): List<Issue> {
        if (branch.isNullOrBlank()) return listOf(Issue("branch", "未归类"))
        if (!Skeleton.isBranch(branch)) {
            return listOf(Issue("branch", "「$branch」不在骨架十九支里"))
        }
        return emptyList()
    }

    fun validate(
        record: ErrorRecord,
        identifyConf: Double? = null,
        existingUids: Set<String> = emptySet(),
    ): List<Issue> {
        val issues = mutableListOf<Issue>()

        if (!SlotSpec.isValidId(record.id)) {
            issues += Issue("id", "id 格式不匹配 q_{三位题号}_{空序}", blocking = true)
        }
        if (record.uid in existingUids) {
            issues += Issue("id", "同卷内 id 重复：${record.id}", blocking = true)
        }
        if (record.src.paper.isBlank()) issues += Issue("src.paper", "卷名缺失", blocking = true)

        // 还没分析过的记录不该被挑毛病：它本来就该是空的
        if (record.branch != null || record.formShape != null || record.basis != null) {
            issues += checkBranch(record.branch)
            issues += checkBasis(record.basis)
            issues += checkFormContext(record.formContext)
        }
        if (identifyConf != null && identifyConf < IDENTIFY_CONF_FLOOR) {
            issues += Issue("identify_conf", "识别置信度 %.2f < %.2f".format(identifyConf, IDENTIFY_CONF_FLOOR))
        }
        return issues
    }

    /** 由字段现状推导 status。 */
    fun deriveStatus(record: ErrorRecord, identifyConf: Double? = null): RecordStatus {
        if (identifyConf != null && identifyConf < IDENTIFY_CONF_FLOOR) return RecordStatus.PENDING
        if (!record.analyzed()) return RecordStatus.PENDING
        if (!Skeleton.isBranch(record.branch)) return RecordStatus.PENDING
        if (record.answer.isNullOrBlank()) return RecordStatus.PENDING_ANSWER
        return RecordStatus.ANALYZED
    }
}
