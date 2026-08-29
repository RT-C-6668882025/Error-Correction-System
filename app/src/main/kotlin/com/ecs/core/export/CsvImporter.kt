package com.ecs.core.export

import com.ecs.core.model.Confidence
import com.ecs.core.model.ErrorRecord
import com.ecs.core.model.RecordStatus
import com.ecs.core.model.Src
import com.ecs.core.tree.Skeleton

/** 批量导入：读回 [Exporter.csv] 的格式。列缺失即视为空，不阻断。 */
object CsvImporter {

    fun parse(text: String, now: Long = System.currentTimeMillis()): List<ErrorRecord> {
        val rows = splitRows(text)
        if (rows.isEmpty()) return emptyList()
        val header = rows.first().map { it.trim() }
        return rows.drop(1).mapNotNull { cells ->
            fun col(name: String): String? {
                val i = header.indexOf(name)
                return if (i in cells.indices) cells[i].takeIf { it.isNotBlank() } else null
            }
            val id = col("id") ?: return@mapNotNull null
            val paper = col("paper") ?: return@mapNotNull null
            ErrorRecord(
                id = id,
                src = Src(
                    paper = paper,
                    no = col("no")?.toIntOrNull() ?: return@mapNotNull null,
                    slot = col("slot")?.toIntOrNull() ?: 1,
                    batch = col("batch") ?: "b01",
                ),
                stem = col("stem"),
                given = col("given"),
                answer = col("answer"),
                confidence = col("confidence")?.let { Confidence.fromLabel(it) } ?: Confidence.WRONG,
                createdAt = col("created_at")?.toLongOrNull() ?: now,
                // 老导出包用的是 kaodian / form_rule / eye，读得回来才叫兼容。
                // 老的 kaodian 是四层路径，截到板块那一层才对得上骨架
                branch = col("branch") ?: col("kaodian")?.let { Skeleton.branchOf(it)?.path },
                formShape = col("form_shape") ?: col("form_rule"),
                basis = col("basis") ?: col("eye"),
                formContext = col("form_context"),
                note = col("note"),
                status = col("status")?.let { RecordStatus.fromLabel(it) } ?: RecordStatus.PENDING,
            )
        }
    }

    /** 支持引号内的逗号与换行。 */
    private fun splitRows(text: String): List<List<String>> {
        val rows = mutableListOf<List<String>>()
        var cells = mutableListOf<String>()
        val cur = StringBuilder()
        var inQuotes = false
        var i = 0
        while (i < text.length) {
            val c = text[i]
            when {
                inQuotes && c == '"' && i + 1 < text.length && text[i + 1] == '"' -> { cur.append('"'); i++ }
                c == '"' -> inQuotes = !inQuotes
                !inQuotes && c == ',' -> { cells.add(cur.toString()); cur.clear() }
                !inQuotes && (c == '\n' || c == '\r') -> {
                    if (c == '\r' && i + 1 < text.length && text[i + 1] == '\n') i++
                    cells.add(cur.toString()); cur.clear()
                    if (cells.any { it.isNotBlank() }) rows.add(cells)
                    cells = mutableListOf()
                }
                else -> cur.append(c)
            }
            i++
        }
        cells.add(cur.toString())
        if (cells.any { it.isNotBlank() }) rows.add(cells)
        return rows
    }
}
