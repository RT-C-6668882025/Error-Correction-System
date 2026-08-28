package com.ecs.core.report

import com.ecs.core.agg.Aggregator
import com.ecs.core.model.Difficulty
import com.ecs.core.model.ErrorRecord
import com.ecs.core.model.Section
import com.ecs.core.rules.StyleGuard
import com.ecs.core.tree.KaodianTree
import kotlin.math.roundToInt

/**
 * F3 / F4 报告。
 *
 * 数字部分本地算，叙述部分由 Agent 填（[MicroNarrative] / [MacroNarrative]）。
 * 分工的理由：数字必须可复算，叙述不必；把数字交给模型必然出现算错但读着顺的报告。
 */
object ReportBuilder {

    data class MicroNarrative(
        val eyeCommonality: String = "",
        val decisivePoint: String = "",
        val confusable: String = "",
        val fixPath: String = "",
    )

    data class Priority(
        val kaodian: String,
        val root: String,
        val slots: Int,
        val share: Double,
        val hitPapers: Int,
        val formRule: String,
        val why: String,
        val action: String,
        val verify: String,
    )

    data class MacroNarrative(
        val weakness: String = "",
        val repeated: String = "",
        val correlation: String = "",
        val priorities: List<Priority> = emptyList(),
        val ignore: String = "",
        val conclusion: String = "",
    )

    // ---------------- F3 小方向 ----------------

    fun micro(stat: Aggregator.KaodianStat, n: MicroNarrative = MicroNarrative()): String = StyleGuard.clean(
        buildString {
            appendLine("## ◆ ${stat.kaodian}")
            appendLine()
            appendLine("**归类** ${stat.root} · **空数** ${stat.slots}（错${stat.wrong} / 蒙对${stat.lucky}）")
            appendLine("**加权计数** ${fmt(stat.weighted)} · **跨卷次数** ${stat.hitPapers}")
            appendLine(
                "**难度** 简单${stat.byDifficulty[Difficulty.EASY] ?: 0}/" +
                    "中等${stat.byDifficulty[Difficulty.MEDIUM] ?: 0}/" +
                    "难${stat.byDifficulty[Difficulty.HARD] ?: 0}"
            )
            appendLine(
                "**题型分布** 语法填空${stat.bySection[Section.GF] ?: 0} / " +
                    "完成句子${stat.bySection[Section.WC] ?: 0}"
            )
            appendLine()
            appendLine("**规则形态**")
            appendLine(stat.formRule)
            appendLine()
            appendLine("**答案倒推表**")
            appendLine("| 题眼 | 语境限定 | 答案 |")
            appendLine("|---|---|---|")
            stat.rows.forEach {
                appendLine("| ${it.eye} | ${it.formContext ?: "—"} | ${it.answer ?: "—"} |")
            }
            appendLine()
            appendLine("**题眼共性**")
            appendLine(n.eyeCommonality.ifBlank { "—" })
            appendLine()
            appendLine("**决定性判断点**")
            appendLine(n.decisivePoint.ifBlank { "—" })
            appendLine()
            appendLine("**最易混淆的对象**")
            appendLine(n.confusable.ifBlank { "—" })
            appendLine()
            appendLine("**修复路径**")
            appendLine(n.fixPath.ifBlank { "—" })
        }
    )

    /** 交给 Agent 的事实块：只给它需要判断的东西，不给题号。 */
    fun microFacts(stat: Aggregator.KaodianStat): String = buildString {
        appendLine("考点：${stat.kaodian}")
        appendLine("规则形态：${stat.formRule}")
        appendLine("空数：${stat.slots}，跨卷次数：${stat.hitPapers}")
        appendLine("全部题眼：")
        stat.eyes.forEach { appendLine("- $it") }
        appendLine("语境限定与答案：")
        stat.rows.forEach { appendLine("- ${it.formContext ?: "无"} → ${it.answer ?: "缺"}") }
    }

    // ---------------- F4 大方向 ----------------

    fun macro(
        records: List<ErrorRecord>,
        tree: KaodianTree?,
        depth: Int = 2,
        n: MacroNarrative = MacroNarrative(),
    ): String {
        val usable = Aggregator.countable(records)
        val stats = Aggregator.kaodianStats(records, tree, depth)
        val maturity = Aggregator.maturity(records)
        val rates = Aggregator.sectionRates(records)
        val byRoot = usable.groupingBy { it.kaodian!!.substringBefore("/") }.eachCount()

        return StyleGuard.clean(
            buildString {
                appendLine("# 全局分析")
                appendLine()
                if (maturity.macroCaveat) appendLine("> 样本不足，仅供参考")
                appendLine(
                    "**总空数** ${usable.size} · **考点数** ${stats.size} · " +
                        "**数据成熟度** ${maturity.level.label}（${maturity.countedSlots} 条）"
                )
                appendLine(
                    "**三类分布** 词法${byRoot["词法"] ?: 0} / 句法${byRoot["句法"] ?: 0} / 语法${byRoot["语法"] ?: 0}"
                )
                appendLine("**分题型错误率** " + rates.joinToString(" · ") { r ->
                    val v = r.rate?.let { "${(it * 100).roundToInt()}%（${fmt(r.weightedWrong)}/${r.totalSlots}）" }
                        ?: "分母缺失"
                    "${r.section.label} $v"
                })
                appendLine("**加权失分** " + rates.joinToString(" · ") { r ->
                    "${r.section.label} ${r.weightedLoss?.let { fmt(it) } ?: "—"}/${r.section.fullScore}"
                })
                appendLine()
                appendLine("## 薄弱格局")
                appendLine(n.weakness.ifBlank { "—" })
                appendLine()
                appendLine("## 反复出错")
                appendLine(n.repeated.ifBlank { "—" })
                appendLine()
                stats.filter { it.reportable }.sortedByDescending { it.hitPapers }.take(8).forEach {
                    appendLine("- ${it.kaodian} · 跨卷 ${it.hitPapers} · 空数 ${it.slots}")
                }
                appendLine()
                appendLine("## 跨考点关联")
                appendLine(n.correlation.ifBlank { "—" })
                appendLine()
                appendLine("## 优先级排序")
                if (n.priorities.isEmpty()) {
                    appendLine("—")
                } else {
                    n.priorities.take(5).forEachIndexed { i, p ->
                        appendLine()
                        appendLine("### 第 ${i + 1} 优先：${p.kaodian}")
                        appendLine(
                            "- 归类 ${p.root} / 影响空数 ${p.slots}（${(p.share * 100).roundToInt()}%）/ 跨卷 ${p.hitPapers}"
                        )
                        appendLine("- 规则形态：${p.formRule}")
                        appendLine("- 为什么优先：${p.why}")
                        appendLine("- 具体行动：${p.action}")
                        appendLine("- 验证方式：${p.verify}")
                    }
                }
                appendLine()
                appendLine("## 暂时不用管")
                appendLine(n.ignore.ifBlank { "—" })
                appendLine()
                appendLine("## 一句话结论")
                appendLine(n.conclusion.ifBlank { "—" })
            }
        )
    }

    /**
     * 交给 Agent 的全局事实块。优先级候选按 hit_papers 排序（4.1），
     * 不按总次数——同卷错 5 次可能是该卷偏。
     */
    fun macroFacts(records: List<ErrorRecord>, tree: KaodianTree?, depth: Int = 2): String {
        val stats = Aggregator.kaodianStats(records, tree, depth).filter { it.reportable }
        val total = Aggregator.countable(records).size.coerceAtLeast(1)
        val rates = Aggregator.sectionRates(records)
        val corr = Aggregator.correlations(records, depth)
        return buildString {
            appendLine("总空数：$total，考点数：${stats.size}")
            rates.forEach { r ->
                appendLine(
                    "${r.section.label}：加权错空 ${fmt(r.weightedWrong)}，分母 ${r.totalSlots}，" +
                        "错误率 ${r.rate?.let { "${(it * 100).roundToInt()}%" } ?: "分母缺失"}，" +
                        "加权失分 ${r.weightedLoss?.let { fmt(it) } ?: "—"}/${r.section.fullScore}"
                )
            }
            appendLine("考点（按跨卷次数排序）：")
            stats.sortedByDescending { it.hitPapers }.take(20).forEach {
                appendLine(
                    "- ${it.kaodian} | 跨卷 ${it.hitPapers} | 空数 ${it.slots} | 占比 " +
                        "${(it.slots * 100.0 / total).roundToInt()}% | 难度 简单${it.byDifficulty[Difficulty.EASY] ?: 0}" +
                        "/中等${it.byDifficulty[Difficulty.MEDIUM] ?: 0}/难${it.byDifficulty[Difficulty.HARD] ?: 0}" +
                        " | 题型 填空${it.bySection[Section.GF] ?: 0}/完句${it.bySection[Section.WC] ?: 0}" +
                        " | 规则形态 ${it.formRule}"
                )
            }
            if (corr.isNotEmpty()) {
                appendLine("总是一起错的考点对：")
                corr.take(10).forEach { appendLine("- ${it.a} + ${it.b}（强 ${it.strong} / 弱 ${it.weak}）") }
            }
        }
    }

    fun fmt(v: Double): String =
        if (v == v.roundToInt().toDouble()) v.roundToInt().toString() else "%.1f".format(v)
}
