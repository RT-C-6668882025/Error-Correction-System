package com.ecs.core.agg

import com.ecs.core.model.Confidence
import com.ecs.core.model.Difficulty
import com.ecs.core.model.ErrorRecord
import com.ecs.core.model.Section
import com.ecs.core.tree.KaodianTree
import com.ecs.core.tree.truncate
import kotlin.math.abs
import kotlin.math.roundToInt

/**
 * L1 / L2 实时计算。不落库，改了 L0 报告自动更新。
 *
 * 统计口径（PRD 2.3）：只有 status.countsInStats 为真的记录（活跃 / 休眠）进入任何数字；
 * 休眠记录参与聚合但不出现在报告里，由 [KaodianStat.reportable] 控制。
 */
object Aggregator {

    // ---------- L1 ----------

    data class ReverseRow(
        val eye: String,
        val formContext: String?,
        val answer: String?,
    )

    data class KaodianStat(
        val kaodian: String,
        val root: String,
        val formRule: String,
        val slots: Int,
        val wrong: Int,
        val lucky: Int,
        /** 4.1 count = Σ(错 1.0 / 蒙对 0.5) */
        val weighted: Double,
        /** 4.1 该考点出现过的不同 src.paper 数量。优先级排序用这个。 */
        val hitPapers: Int,
        val papers: List<String>,
        val byDifficulty: Map<Difficulty, Int>,
        val bySection: Map<Section, Int>,
        val eyes: List<String>,
        val rows: List<ReverseRow>,
        val reportable: Boolean,
        val lastSeen: Long,
    )

    /** 参与统计的记录：活跃 + 休眠，且已标注考点。 */
    fun countable(records: List<ErrorRecord>): List<ErrorRecord> =
        records.filter { it.status.countsInStats && !it.kaodian.isNullOrBlank() }

    fun kaodianStats(
        records: List<ErrorRecord>,
        tree: KaodianTree?,
        depth: Int = 3,
    ): List<KaodianStat> {
        val usable = countable(records)
        return usable.groupBy { truncate(it.kaodian!!, depth) }
            .map { (path, group) ->
                val papers = group.map { it.src.paper }.distinct()
                KaodianStat(
                    kaodian = path,
                    root = path.substringBefore("/"),
                    formRule = resolveFormRule(path, group, tree),
                    slots = group.size,
                    wrong = group.count { it.confidence == Confidence.WRONG },
                    lucky = group.count { it.confidence == Confidence.LUCKY },
                    weighted = group.sumOf { it.weight },
                    hitPapers = papers.size,
                    papers = papers,
                    byDifficulty = group.mapNotNull { it.difficulty }
                        .groupingBy { it }.eachCount(),
                    bySection = group.groupingBy { it.src.section }.eachCount(),
                    eyes = group.mapNotNull { it.eye }.distinct(),
                    rows = group.filter { !it.eye.isNullOrBlank() }
                        .map { ReverseRow(it.eye!!, it.formContext, it.answer) },
                    reportable = group.any { it.status.showsInReport },
                    lastSeen = group.maxOf { it.createdAt },
                )
            }
            .sortedWith(compareByDescending<KaodianStat> { it.hitPapers }.thenByDescending { it.weighted })
    }

    /**
     * 截断到某层后，form_rule 只在「该层就是末端节点」时唯一。
     * 截断层高于末端时给出成员节点规则的合并展示。
     */
    private fun resolveFormRule(path: String, group: List<ErrorRecord>, tree: KaodianTree?): String {
        tree?.formRuleOf(path)?.let { return it }
        val rules = group.mapNotNull { it.formRule }.distinct()
        return when {
            rules.isEmpty() -> ""
            rules.size == 1 -> rules.first()
            else -> rules.joinToString("；")
        }
    }

    // ---------- L2 ----------

    data class SectionRate(
        val section: Section,
        /** 分子：计入分母的卷子里的加权错空数。 */
        val weightedWrong: Double,
        /** 分母：Σ src.total_in_section，缺失的卷子既不计分子也不计分母（4.2）。 */
        val totalSlots: Int,
        val papersCounted: Int,
        val papersSkipped: Int,
    ) {
        val rate: Double? get() = if (totalSlots > 0) weightedWrong / totalSlots else null
        /** 4.3 加权失分。 */
        val weightedLoss: Double? get() = rate?.times(section.fullScore)
    }

    fun sectionRates(records: List<ErrorRecord>): List<SectionRate> {
        val usable = records.filter { it.status.countsInStats }
        return Section.entries.map { section ->
            val inSection = usable.filter { it.src.section == section }
            // total_in_section 属于 (卷, 题型) 维度，取该维度上任一非空值
            val totals = inSection.groupBy { it.src.paper }
                .mapValues { (_, rs) -> rs.firstNotNullOfOrNull { it.src.totalInSection } }
            val counted = totals.filterValues { it != null }
            val denom = counted.values.filterNotNull().sum()
            val numer = inSection.filter { it.src.paper in counted.keys }.sumOf { it.weight }
            SectionRate(
                section = section,
                weightedWrong = numer,
                totalSlots = denom,
                papersCounted = counted.size,
                papersSkipped = totals.size - counted.size,
            )
        }
    }

    // ---------- 4.6 跨考点关联 ----------

    data class Correlation(
        val a: String,
        val b: String,
        val strong: Int,
        val weak: Int,
    ) {
        val times: Int get() = strong + weak
    }

    fun correlations(records: List<ErrorRecord>, depth: Int = 3, minTimes: Int = 3): List<Correlation> {
        val usable = countable(records)
        val strong = mutableMapOf<Pair<String, String>, Int>()
        val weak = mutableMapOf<Pair<String, String>, Int>()

        usable.groupBy { it.src.paper }.forEach { (_, inPaper) ->
            for (i in inPaper.indices) for (j in i + 1 until inPaper.size) {
                val x = inPaper[i]; val y = inPaper[j]
                val kx = truncate(x.kaodian!!, depth); val ky = truncate(y.kaodian!!, depth)
                if (kx == ky) continue
                val key = if (kx < ky) kx to ky else ky to kx
                val sameNo = x.src.no == y.src.no && x.src.slot != y.src.slot
                when {
                    sameNo -> strong.merge(key, 1, Int::plus)
                    abs(x.src.no - y.src.no) <= 2 -> weak.merge(key, 1, Int::plus)
                }
            }
        }
        // co_error 显式关联视为强关联
        val byUid = usable.associateBy { it.uid }
        val byId = usable.groupBy { it.id }
        usable.forEach { r ->
            r.coError.forEach { ref ->
                val other = byUid[ref] ?: byId[ref]?.firstOrNull() ?: return@forEach
                val kx = truncate(r.kaodian!!, depth)
                val ky = truncate(other.kaodian!!, depth)
                if (kx == ky) return@forEach
                val key = if (kx < ky) kx to ky else ky to kx
                strong.merge(key, 1, Int::plus)
            }
        }

        return (strong.keys + weak.keys).map { key ->
            Correlation(key.first, key.second, strong[key] ?: 0, weak[key] ?: 0)
        }.filter { it.times >= minTimes }
            .sortedWith(compareByDescending<Correlation> { it.strong }.thenByDescending { it.times })
    }

    // ---------- 4.5 循环终止条件 ----------

    data class Termination(
        /** 考点级：仍出现在报告中的活跃考点数。 */
        val activeKaodian: Int,
        /** 轮次级：本轮与上轮考点分布余弦相似度。 */
        val roundSimilarity: Double?,
        val sourceExhausted: Boolean,
        /** 整体级：考点树覆盖率。 */
        val coverage: Double,
        val warmMode: Boolean,
    )

    fun termination(
        records: List<ErrorRecord>,
        tree: KaodianTree?,
        depth: Int = 3,
    ): Termination {
        val usable = countable(records)
        val active = usable.filter { it.status.showsInReport }
            .map { truncate(it.kaodian!!, depth) }.distinct().size

        val batches = usable.map { it.src.batch }.distinct().sorted()
        val sim = if (batches.size >= 2) {
            val cur = distribution(usable.filter { it.src.batch == batches.last() }, depth)
            val prev = distribution(usable.filter { it.src.batch == batches[batches.size - 2] }, depth)
            cosineOfDistributions(cur, prev)
        } else null

        val leaves = tree?.liveNodes?.map { it.path }?.toSet().orEmpty()
        val used = usable.mapNotNull { it.kaodian }.toSet()
        val coverage = if (leaves.isEmpty()) 0.0 else used.count { it in leaves }.toDouble() / leaves.size

        return Termination(
            activeKaodian = active,
            roundSimilarity = sim,
            sourceExhausted = (sim ?: 0.0) > 0.8,
            coverage = coverage,
            warmMode = coverage > 0.95 && active < 5,
        )
    }

    fun distribution(records: List<ErrorRecord>, depth: Int): Map<String, Double> =
        records.groupBy { truncate(it.kaodian!!, depth) }
            .mapValues { (_, g) -> g.sumOf { it.weight } }

    fun cosineOfDistributions(a: Map<String, Double>, b: Map<String, Double>): Double {
        if (a.isEmpty() || b.isEmpty()) return 0.0
        val keys = a.keys + b.keys
        var dot = 0.0; var na = 0.0; var nb = 0.0
        keys.forEach { k ->
            val x = a[k] ?: 0.0; val y = b[k] ?: 0.0
            dot += x * y; na += x * x; nb += y * y
        }
        if (na == 0.0 || nb == 0.0) return 0.0
        return dot / (Math.sqrt(na) * Math.sqrt(nb))
    }

    // ---------- F9 数据成熟度 ----------

    enum class Maturity(val label: String) {
        SEED("样本不足"), PARTIAL("样本偏少"), READY("样本充足");
    }

    data class MaturityState(
        val countedSlots: Int,
        val level: Maturity,
        val microEnabled: Boolean,
        val macroEnabled: Boolean,
        val macroCaveat: Boolean,
        val hint: String,
    )

    fun maturity(records: List<ErrorRecord>): MaturityState {
        val n = countable(records).size
        return when {
            n < 50 -> MaturityState(n, Maturity.SEED, false, false, false, "继续录入 ${50 - n} 条后开放报告")
            n <= 200 -> MaturityState(n, Maturity.PARTIAL, true, true, true, "样本不足，大方向仅供参考")
            else -> MaturityState(n, Maturity.READY, true, true, false, "样本充足")
        }
    }

    // ---------- F8 标注一致率 ----------

    data class Consistency(val checked: Int, val consistent: Int) {
        val rate: Double? get() = if (checked == 0) null else consistent.toDouble() / checked
        val alarm: Boolean get() = rate?.let { it < 0.85 } ?: false
        val display: String get() = rate?.let { "${(it * 100).roundToInt()}%" } ?: "—"
    }

    /** 抽检只统计已出结论的记录；人工确认记为一致。 */
    fun consistency(records: List<ErrorRecord>): Consistency {
        val checked = records.filter {
            it.verified == com.ecs.core.model.Verified.CONSISTENT ||
                it.verified == com.ecs.core.model.Verified.CONFLICT ||
                it.verified == com.ecs.core.model.Verified.MANUAL
        }
        return Consistency(
            checked = checked.size,
            consistent = checked.count { it.verified != com.ecs.core.model.Verified.CONFLICT },
        )
    }
}
