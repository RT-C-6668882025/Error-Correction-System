package com.ecs.core.parse

import com.ecs.core.model.Confidence

/**
 * 记录 id 与批次号。
 *
 * 错题号不再靠手打——确认页把识别到的题逐条列出来，你在每条上标 错 / 蒙对，
 * 标了的才入库。所以这里只剩「一个标记怎么变成 id」这一件事。
 */
object SlotSpec {

    /** 确认页上的一条标记。 */
    data class Mark(val no: Int, val slot: Int, val confidence: Confidence) {
        val id: String get() = "%s%03d_%d".format(PREFIX, no, slot)
    }

    /** 中性前缀：不再区分语法填空 / 完成句子。 */
    const val PREFIX = "q_"

    /** 老库里是 gf_/wc_ 前缀，仍要认得，否则历史记录会被判成非法 id。 */
    private val ID = Regex("^(q|gf|wc)_(\\d{3})_(\\d+)$")

    fun isValidId(id: String): Boolean = ID.matches(id)

    /** 题号与空序。前缀是什么不重要，历史前缀照样解析。 */
    fun parseId(id: String): Pair<Int, Int>? {
        val m = ID.matchEntire(id) ?: return null
        return m.groupValues[2].toInt() to m.groupValues[3].toInt()
    }

    /** 录入批次 b{两位数}。 */
    fun batchLabel(n: Int): String = "b%02d".format(n)
}
