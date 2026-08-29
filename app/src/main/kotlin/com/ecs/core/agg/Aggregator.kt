package com.ecs.core.agg

import com.ecs.core.model.ErrorRecord
import com.ecs.core.tree.Skeleton

/**
 * 把分析好的记录按板块归堆。
 *
 * 只做分组和取字段，不做任何判断——判断在「小方向」那一步交给模型，
 * 而它读到的输入就是这里交出来的 [Block.analyses]。
 */
object Aggregator {

    /** 一道题的分析结果。这是小方向那一级唯一的输入。 */
    data class Analysis(
        val uid: String,
        val formShape: String,
        val basis: String?,
        val formContext: String?,
        val answer: String?,
        val stem: String?,
    )

    /** 一个板块。 */
    data class Block(
        val branch: Skeleton.Branch,
        val analyses: List<Analysis>,
    ) {
        val path: String get() = branch.path
        val size: Int get() = analyses.size
    }

    /** 分析完并且归了类的才进得了板块；其余留在原题页。 */
    fun analyzed(records: List<ErrorRecord>): List<ErrorRecord> =
        records.filter { it.analyzed() && Skeleton.isBranch(it.branch) }

    /**
     * 「还没跑过分析」和「跑过了但没归进板块」是两回事，页面上要分开说。
     *
     * 两者混成一个「待分析」，人会以为分析没跑；实际是跑了、答案形式也有，
     * 只是模型给的 branch 落在十九支之外，于是复习页一片空白而没人知道为什么。
     */
    fun notAnalyzed(records: List<ErrorRecord>): List<ErrorRecord> =
        records.filter { it.formShape.isNullOrBlank() }

    fun unclassified(records: List<ErrorRecord>): List<ErrorRecord> =
        records.filter { !it.formShape.isNullOrBlank() && !Skeleton.isBranch(it.branch) }

    /** 进不了板块的全部：待分析 + 未归类。 */
    fun pending(records: List<ErrorRecord>): List<ErrorRecord> =
        records.filterNot { it.analyzed() && Skeleton.isBranch(it.branch) }

    /**
     * 十九个板块，按骨架顺序。[includeEmpty] 为 false 时只给有题的那些。
     */
    fun blocks(records: List<ErrorRecord>, includeEmpty: Boolean = false): List<Block> {
        val byBranch = analyzed(records).groupBy { Skeleton.branchOf(it.branch)!! }
        return Skeleton.BRANCHES
            .map { branch -> Block(branch, byBranch[branch].orEmpty().map { it.toAnalysis() }) }
            .filter { includeEmpty || it.analyses.isNotEmpty() }
    }

    fun block(records: List<ErrorRecord>, branch: Skeleton.Branch): Block =
        Block(branch, analyzed(records).filter { Skeleton.branchOf(it.branch) == branch }.map { it.toAnalysis() })

    private fun ErrorRecord.toAnalysis() = Analysis(
        uid = uid,
        formShape = formShape.orEmpty(),
        basis = basis,
        formContext = formContext,
        answer = answer,
        stem = stem,
    )

    /** 交给模型的事实块：只给分析，不给题号，也不给任何统计。 */
    fun facts(block: Block): String = buildString {
        appendLine("板块：${block.path}")
        appendLine("范围：${block.branch.scope}")
        appendLine("这一支下 ${block.size} 道题的分析：")
        block.analyses.forEach {
            appendLine("- 答案形式：${it.formShape}｜依据：${it.basis ?: "无"}｜语境：${it.formContext ?: "无"}")
        }
    }
}
