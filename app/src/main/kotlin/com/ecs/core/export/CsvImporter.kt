package com.ecs.core.export

import com.ecs.core.model.Confidence
import com.ecs.core.model.Difficulty
import com.ecs.core.model.ErrorRecord
import com.ecs.core.model.RecordStatus
import com.ecs.core.model.Section
import com.ecs.core.model.Src
import com.ecs.core.model.Verified

/** F1.5 批量导入：读回 [Exporter.csv] 的格式。列缺失即视为空，不阻断。 */
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
            val section = col("section")?.let { Section.fromLabel(it) } ?: return@mapNotNull null
            ErrorRecord(
                id = id,
                src = Src(
                    paper = paper,
                    section = section,
                    no = col("no")?.toIntOrNull() ?: return@mapNotNull null,
                    slot = col("slot")?.toIntOrNull() ?: 1,
                    batch = col("batch") ?: "b01",
                    totalInSection = col("total_in_section")?.toIntOrNull(),
                ),
                given = col("given"),
                answer = col("answer"),
                confidence = col("confidence")?.let { Confidence.fromLabel(it) } ?: Confidence.WRONG,
                createdAt = col("created_at")?.toLongOrNull() ?: now,
                eye = col("eye"),
                kaodian = col("kaodian"),
                formRule = col("form_rule"),
                formContext = col("form_context"),
                secondary = col("secondary")?.split("|")?.filter { it.isNotBlank() }.orEmpty(),
                difficulty = col("difficulty")?.let { Difficulty.fromLabel(it) },
                coError = col("co_error")?.split("|")?.filter { it.isNotBlank() }.orEmpty(),
                treeVersion = col("tree_version"),
                verified = col("verified")?.let { Verified.fromLabel(it) } ?: Verified.UNCHECKED,
                note = col("note"),
                status = col("status")?.let { RecordStatus.fromLabel(it) } ?: RecordStatus.INCOMPLETE,
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
