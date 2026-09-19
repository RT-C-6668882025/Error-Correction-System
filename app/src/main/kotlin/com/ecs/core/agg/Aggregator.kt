package com.ecs.core.agg

import com.ecs.core.model.ErrorRecord
import com.ecs.core.dup.Duplicates
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
        Duplicates.canonical(records).filter { it.analyzed() && Skeleton.isBranch(it.branch) }

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

    /**
     * 交给模型的事实块：只给分析，不给题号。
     *
     * 送出去之前先按坑归并（[Compressor]）：同一种答案形式重复几十遍对判断没有任何帮助，
     * 只会把上下文占满。压缩后体积随形态种类数增长，不再随题数增长。
     *
     * 括号里的数字是频次——它答的是「哪几种形态是这一支的主线」，
     * 和被砍掉的那套统计层（错误率、加权失分、难度）不是一回事，后者答的是「你错得怎么样」。
     */
    fun facts(block: Block, limits: Compressor.Limits = Compressor.Limits()): String {
        val out = Compressor.compress(block, limits)
        return buildString {
            appendLine("板块：${block.path}")
            appendLine("范围：${block.branch.scope}")
            appendLine("这一支下 ${out.total} 道题，去重后 ${out.kinds} 种答案形式。")
            appendLine("括号里的数字是支撑它的题数，只用来判断哪些形态是主线，不要写进输出。")
            appendLine()
            out.pits.forEachIndexed { i, pit ->
                appendLine("${i + 1}. ${pit.shape}（${pit.count} 题）")
                if (pit.bases.isNotEmpty()) appendLine("   依据：${evidenceLine(pit.bases)}")
                if (pit.contexts.isNotEmpty()) appendLine("   语境：${evidenceLine(pit.contexts)}")
            }
            if (out.tail.isNotEmpty()) {
                // 尾巴只留形态名：覆盖不丢，体积有界
                val most = out.tail.first().count
                val note = if (most <= 1) "各 1 题" else "每种不超过 $most 题"
                appendLine("其余 ${out.tail.size} 种形态（$note）：" + out.tail.joinToString("；") { it.shape })
            }
        }
    }

    private fun evidenceLine(items: List<Compressor.Evidence>): String =
        items.joinToString("／") { if (it.count > 1) "${it.text}（${it.count}）" else it.text }
}
