package com.ecs.core.parse

import com.ecs.core.model.Confidence
import com.ecs.core.model.Section

/**
 * F1.3 错题号输入格式解析。
 *
 *   语法填空： 3, 5, ?7
 *   完成句子： 12-1, 12-2, ?15-1
 *   `?` 前缀 = 蒙对
 *
 * 分隔符宽松处理：中英文逗号、顿号、空格、分号均可；中文问号等价于 `?`。
 * 语法填空天然一题一空，省略空序时补 1。
 */
object SlotSpec {

    data class Entry(val no: Int, val slot: Int, val confidence: Confidence) {
        fun id(section: Section): String = "%s_%03d_%d".format(section.abbr, no, slot)
    }

    data class Result(
        val entries: List<Entry>,
        val errors: List<String>,
    ) {
        val ok: Boolean get() = errors.isEmpty()
        /** F1.3 实时解析预览：显示将生成几条记录。 */
        val preview: String
            get() = buildString {
                append("将生成 ${entries.size} 条记录")
                val lucky = entries.count { it.confidence == Confidence.LUCKY }
                if (lucky > 0) append("（其中蒙对 $lucky 条）")
                if (errors.isNotEmpty()) append(" · ${errors.size} 处无法解析")
            }
    }

    private val SPLIT = Regex("[,，、;；\\s]+")
    private val TOKEN = Regex("^([?？]?)(\\d{1,3})(?:[-－—](\\d{1,2}))?$")

    fun parse(input: String, section: Section): Result {
        val entries = LinkedHashMap<Pair<Int, Int>, Entry>()
        val errors = mutableListOf<String>()

        input.trim().split(SPLIT).filter { it.isNotBlank() }.forEach { raw ->
            val token = raw.trim()
            val m = TOKEN.matchEntire(token)
            if (m == null) {
                errors += token
                return@forEach
            }
            val lucky = m.groupValues[1].isNotEmpty()
            val no = m.groupValues[2].toInt()
            val slot = m.groupValues[3].takeIf { it.isNotEmpty() }?.toInt() ?: 1
            if (no <= 0 || slot <= 0) {
                errors += token
                return@forEach
            }
            val key = no to slot
            // 重复输入同一空：保留更严重的一次（错 > 蒙对）
            val conf = if (lucky) Confidence.LUCKY else Confidence.WRONG
            val existing = entries[key]
            entries[key] = if (existing != null && existing.confidence == Confidence.WRONG) {
                existing
            } else {
                Entry(no, slot, conf)
            }
        }
        return Result(entries.values.toList(), errors)
    }

    /** id 格式校验（F1.6）。 */
    private val ID = Regex("^(gf|wc)_(\\d{3})_(\\d+)$")

    fun isValidId(id: String): Boolean = ID.matches(id)

    fun parseId(id: String): Triple<Section, Int, Int>? {
        val m = ID.matchEntire(id) ?: return null
        val section = Section.fromAbbr(m.groupValues[1]) ?: return null
        return Triple(section, m.groupValues[2].toInt(), m.groupValues[3].toInt())
    }

    /** 录入批次 b{两位数}。 */
    fun batchLabel(n: Int): String = "b%02d".format(n)
}
