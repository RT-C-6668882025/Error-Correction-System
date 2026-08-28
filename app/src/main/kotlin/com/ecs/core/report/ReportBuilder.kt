package com.ecs.core.report

import com.ecs.core.agg.Aggregator
import com.ecs.core.rules.StyleGuard

/**
 * 复习视图的文本部分。
 *
 * 只写答案形式：这一类空该填成什么、看到什么特征往哪走、最容易和什么搞混。
 * 数字（错了几次、跨了几卷、难度分布）一概不出现——那是统计，不是答案。
 */
object ReportBuilder {

    data class Narrative(
        val decisivePoint: String = "",
        val confusable: String = "",
        val fixPath: String = "",
    )

    fun render(group: Aggregator.Group, n: Narrative = Narrative()): String = StyleGuard.clean(
        buildString {
            appendLine("## ${group.kaodian}")
            appendLine()
            appendLine("**答案形式**")
            if (group.formRules.isEmpty()) {
                appendLine("—")
            } else {
                group.formRules.forEach { appendLine("- $it") }
            }
            if (group.children.size > 1) {
                appendLine()
                appendLine("**由这些考点汇总而来**")
                group.children.forEach { appendLine("- $it") }
            }
            appendLine()
            appendLine("**看到什么 → 填什么**")
            appendLine("| 题眼 | 语境限定 | 答案 |")
            appendLine("|---|---|---|")
            group.rows.forEach {
                appendLine("| ${it.eye} | ${it.formContext ?: "—"} | ${it.answer ?: "—"} |")
            }
            appendLine()
            appendLine("**决定性判断点**")
            appendLine(n.decisivePoint.ifBlank { "—" })
            appendLine()
            appendLine("**最易混淆的对象**")
            appendLine(n.confusable.ifBlank { "—" })
            appendLine()
            appendLine("**怎么练**")
            appendLine(n.fixPath.ifBlank { "—" })
        }
    )

    /** 交给 Agent 的事实块：只给它答案形式与题眼，不给题号，也不给统计。 */
    fun facts(group: Aggregator.Group): String = buildString {
        appendLine("考点：${group.kaodian}")
        appendLine("答案形式：")
        group.formRules.forEach { appendLine("- $it") }
        appendLine("看到什么 → 填什么：")
        group.rows.forEach {
            appendLine("- ${it.eye}｜${it.formContext ?: "无"} → ${it.answer ?: "缺"}")
        }
    }
}
