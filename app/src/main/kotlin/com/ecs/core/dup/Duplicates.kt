package com.ecs.core.dup

import com.ecs.core.model.ErrorRecord

/**
 * 找出「同一道题被录了两遍」。
 *
 * 入库时按 uid（卷名#题号_空序）去重，可那只挡得住同一份卷子重复提交。
 * 换个卷名再拍一次、导入一份别处导出的备份、同一张卷子分两次录——
 * uid 都不一样，于是同一道题在原题页里出现两条，分析各跑一次，
 * 汇总时还会被当成两次证据，把某一种形态的权重顶上去。
 *
 * 判重只看原始事实（题干、空序、答案），不看分析产出：
 * 分析是可重跑的派生数据，拿它判重等于让结论决定输入。
 *
 * 全是纯计算，删除动作由调用方发起——这里只回答「哪些是重复的、该留哪条」。
 */
object Duplicates {

    data class Group(
        /** 留下的那条：已分析的优先，其次有答案的，再其次录得早的。 */
        val keep: ErrorRecord,
        /** 可以删掉的那些。 */
        val drop: List<ErrorRecord>,
    ) {
        val size: Int get() = drop.size + 1
    }

    /**
     * 判重的键。题干为空的一律不参与——没有题干就没法判断它跟谁重复，
     * 与其猜一个，不如把它留在原题页等人补。
     */
    fun signature(record: ErrorRecord): String? {
        val stem = normalize(record.stem) ?: return null
        return "$stem|${record.src.slot}"
    }

    /**
     * 归一到「看起来是同一句话」的程度：大小写、空白、下划线长度、标点写法
     * 都不该让两条一样的题分开。空的位置要保留——同一句话里三个空是三道题，
     * 把下划线整个抹掉会把它们并成一条。
     */
    fun normalize(text: String?): String? {
        val raw = text?.trim().orEmpty()
        if (raw.isEmpty()) return null
        val out = StringBuilder()
        var lastBlank = false
        raw.lowercase().forEach { c ->
            when {
                c == '_' || c == '＿' -> if (!lastBlank) { out.append('_'); lastBlank = true }
                c.isLetterOrDigit() -> { out.append(c); lastBlank = false }
                // 空白与标点一律丢掉：全角半角、多一个逗号少一个句号都不算区别
                else -> lastBlank = false
            }
        }
        return out.toString().ifEmpty { null }
    }

    /**
     * 分组。同题干同空序算一簇，簇里再按答案分开——
     * 答案没填的那条并进唯一的那个答案里（多半就是它，只是还没补上），
     * 但簇里出现两个不同答案时不并：那是两道不同的题，或者有一条抄错了，
     * 两种情况都不该由这里替人做主。
     */
    fun groups(records: List<ErrorRecord>): List<Group> =
        records.filter { signature(it) != null }
            .groupBy { signature(it)!! }
            .values
            .flatMap { cluster(it) }
            .filter { it.drop.isNotEmpty() }

    /** 可以删掉的全部记录，按原顺序。 */
    fun redundant(records: List<ErrorRecord>): List<ErrorRecord> =
        groups(records).flatMap { it.drop }

    /**
     * 参与计算的唯一记录，保持原列表顺序。
     *
     * 重复项仍然留在原题页，删除权仍在人；但在删除之前，它们不能被重复分析、
     * 也不能在汇总时冒充多份证据。每组只让 [Group.keep] 通过这道门。
     */
    fun canonical(records: List<ErrorRecord>): List<ErrorRecord> {
        val dropped = redundant(records).mapTo(hashSetOf()) { it.uid }
        return records.filterNot { it.uid in dropped }
    }

    private fun cluster(same: List<ErrorRecord>): List<Group> {
        val byAnswer = same.groupBy { normalize(it.answer) }
        val answered = byAnswer.filterKeys { it != null }
        val blank = byAnswer[null].orEmpty()
        val buckets = when {
            // 只有一种答案：没填答案的那些多半就是它
            answered.size == 1 -> listOf(answered.values.first() + blank)
            answered.isEmpty() -> listOf(blank)
            else -> answered.values.toList() + if (blank.isEmpty()) emptyList() else listOf(blank)
        }
        return buckets.filter { it.size > 1 }.map { bucket ->
            val keep = bucket.minWithOrNull(PRIORITY)!!
            Group(keep = keep, drop = bucket.filter { it.uid != keep.uid })
        }
    }

    /**
     * 留哪一条：已分析的最值钱（删了要重跑一次），其次是有答案的，
     * 再其次录得早的。最后按 uid 兜底，保证同一批输入每次留下的都是同一条。
     */
    private val PRIORITY = compareBy<ErrorRecord>(
        { if (it.analyzed()) 0 else 1 },
        { if (!it.answer.isNullOrBlank()) 0 else 1 },
        { it.createdAt },
        { it.uid },
    )
}
