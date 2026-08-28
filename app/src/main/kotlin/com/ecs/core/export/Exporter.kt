package com.ecs.core.export

import com.ecs.core.agg.Aggregator
import com.ecs.core.model.ErrorRecord
import com.ecs.core.model.Section
import com.ecs.core.tree.KaodianTree
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json

/**
 * F5 导出。硬指标（验收 #4）：导出包交给全新模型会话，不加任何提示词，
 * 能产出与 F3/F4 同质量的报告。README 是唯一的说明书，必须自洽且 ≤120 行。
 */
object Exporter {

    const val README_MAX_LINES = 120

    private val json = Json {
        prettyPrint = true
        encodeDefaults = true
        explicitNulls = false
    }

    data class Package(
        val dirName: String,
        val readme: String,
        val dataJson: String,
        val dataCsv: String,
        val reverseTable: String,
    )

    /** 有效 L0 记录：进入统计口径的那些（活跃 / 休眠）。 */
    fun validRecords(records: List<ErrorRecord>): List<ErrorRecord> =
        records.filter { it.status.countsInStats }

    fun build(records: List<ErrorRecord>, tree: KaodianTree?, dateStamp: String): Package {
        val valid = validRecords(records)
        return Package(
            dirName = "export_$dateStamp",
            readme = readme(valid, tree),
            dataJson = json.encodeToString(valid),
            dataCsv = csv(valid),
            reverseTable = reverseTable(valid),
        )
    }

    // ---------------- data.csv ----------------

    private val HEADERS = listOf(
        "id", "paper", "section", "no", "slot", "batch", "total_in_section",
        "given", "answer", "confidence", "created_at",
        "eye", "kaodian", "form_rule", "form_context", "secondary",
        "difficulty", "co_error", "tree_version", "verified", "status", "note",
    )

    fun csv(records: List<ErrorRecord>): String = buildString {
        appendLine(HEADERS.joinToString(","))
        records.forEach { r ->
            appendLine(
                listOf(
                    r.id, r.src.paper, r.src.section.label, r.src.no.toString(), r.src.slot.toString(),
                    r.src.batch, r.src.totalInSection?.toString() ?: "",
                    r.given ?: "", r.answer ?: "", r.confidence.label, r.createdAt.toString(),
                    r.eye ?: "", r.kaodian ?: "", r.formRule ?: "", r.formContext ?: "",
                    r.secondary.joinToString("|"), r.difficulty?.label ?: "",
                    r.coError.joinToString("|"), r.treeVersion ?: "", r.verified.label,
                    r.status.label, r.note ?: "",
                ).joinToString(",") { esc(it) }
            )
        }
    }

    private fun esc(v: String): String =
        if (v.any { it == ',' || it == '"' || it == '\n' }) "\"${v.replace("\"", "\"\"")}\"" else v

    // ---------------- F5.2 倒推表 ----------------

    /** 纯背诵材料，不含分析。按 kaodian 分组，遮住 answer 列即可自测。 */
    fun reverseTable(records: List<ErrorRecord>): String = buildString {
        appendLine("# 答案倒推表")
        appendLine()
        records.filter { !it.kaodian.isNullOrBlank() && !it.eye.isNullOrBlank() }
            .groupBy { it.kaodian!! }
            .toSortedMap()
            .forEach { (kaodian, group) ->
                appendLine("## $kaodian")
                group.firstOrNull { !it.formRule.isNullOrBlank() }?.formRule?.let {
                    appendLine("规则形态：$it")
                }
                appendLine()
                appendLine("| eye | form_context | answer |")
                appendLine("|---|---|---|")
                group.forEach {
                    appendLine("| ${it.eye} | ${it.formContext ?: "—"} | ${it.answer ?: "—"} |")
                }
                appendLine()
            }
    }

    // ---------------- F5.1 README ----------------

    fun readme(records: List<ErrorRecord>, tree: KaodianTree?): String {
        val head = head(records)
        val tail = tail(records)
        val budget = README_MAX_LINES - head.size - tail.size - 2
        val nodes = usedNodes(records, tree)
        val nodeLines = mutableListOf<String>()
        nodeLines += "## 3. 考点树（仅本数据集用到的节点）"
        nodeLines += ""
        val room = (budget - 2).coerceAtLeast(0)
        nodes.entries.take(room).forEach { (path, rule) -> nodeLines += "- `$path` → $rule" }
        if (nodes.size > room) nodeLines += "- …另有 ${nodes.size - room} 个节点，完整取值见 data.json 的 kaodian/form_rule"
        nodeLines += ""

        val all = head + nodeLines + tail
        val trimmed = if (all.size > README_MAX_LINES) all.take(README_MAX_LINES) else all
        return trimmed.joinToString("\n")
    }

    /** 只导出实际用到的末端节点：整棵树写进去会超 120 行上限。 */
    fun usedNodes(records: List<ErrorRecord>, tree: KaodianTree?): Map<String, String> =
        records.mapNotNull { it.kaodian }.distinct().sorted().associateWith { path ->
            tree?.formRuleOf(path)
                ?: records.firstOrNull { it.kaodian == path && !it.formRule.isNullOrBlank() }?.formRule
                ?: ""
        }

    private fun head(records: List<ErrorRecord>): List<String> {
        val gf = records.count { it.src.section == Section.GF }
        val wc = records.count { it.src.section == Section.WC }
        return """
# 错题元数据（语法填空 + 完成句子）

专升本英语备考的错题字段库。一条记录 = 一个「空」，共 ${records.size} 条（语法填空 $gf / 完成句子 $wc）。
每条记录记的是「什么特征触发了什么形态」，不含原题。仅凭这些字段即可产出诊断报告与背诵材料。

## 1. 数据文件

- `data.json` 全部有效记录，结构化
- `data.csv` 同一批数据的扁平化版本
- `倒推表.md` eye → form_context → answer，按考点分组

## 2. 字段词典

| 字段 | 类型 | 含义 / 取值 |
|---|---|---|
| `id` | string | `{gf\|wc}_{题号}_{空序}`，卷内唯一 |
| `src.paper` | string | 题源卷名 |
| `src.section` | enum | 语法填空(满分20) / 完成句子(满分18) |
| `src.no` / `src.slot` | int | 卷内题号 / 该题内第几个空 |
| `src.batch` | string | 录入批次 |
| `src.total_in_section` | int? | 该卷该题型总空数，错误率分母；缺失则该卷不计入分子分母 |
| `given` | string? | 括号提示词或中文提示 |
| `answer` | string? | 正确答案 |
| `confidence` | enum | 错(权重1.0) / 蒙对(权重0.5) |
| `eye` | string | 题眼：触发正确判断的客观语言特征，10-25字 |
| `kaodian` | string | 路径式考点名，取自考点树，形如 `词法/名词/后缀转换/-tion` |
| `form_rule` | string | 规则形态，由考点树节点带出，同一考点下完全一致，可迁移 |
| `form_context` | string? | 语境形态，本句独有的限定，一次性，不参与聚合 |
| `secondary` | string[] | 副考点 |
| `difficulty` | enum | 简单 / 中等 / 难 |
| `co_error` | string[] | 关联错误的记录 id |
| `tree_version` | string | 标注时的考点树版本 |
| `verified` | enum | 抽检结果：未抽检 / 一致 / 冲突 / 人工确认 |
| `status` | enum | 活跃 / 休眠（两者参与统计）；归档 / 待补答案 / 不完整（不参与） |
        """.trimIndent().lines()
    }

    private fun tail(records: List<ErrorRecord>): List<String> {
        val sample = records.firstOrNull { !it.eye.isNullOrBlank() && !it.formRule.isNullOrBlank() }
        val eye = sample?.eye ?: "空前有 the，空后接介词短语"
        val rule = sample?.formRule ?: "名词，动词加 -tion 后缀"
        val ctx = sample?.formContext ?: "单数，谓语 has 限定"
        val ans = sample?.answer ?: "development"
        val kd = sample?.kaodian ?: "词法/名词/后缀转换/-tion"
        return """
## 4. 分析范例

给定一条记录：

    kaodian      $kd
    eye          $eye
    form_rule    $rule
    form_context $ctx
    answer       $ans

应得结论：看到 eye 描述的特征，就往 form_rule 指的形态走；form_context 只决定这一句的
最终写法，不进入对该考点的判断。同一 kaodian 下的全部 eye 放在一起，若指向同一特征，
该考点是「识别问题」；若分成几组，说明有几种触发场景，要分别练。

聚合口径：
- 计数 `count = Σ(confidence == 错 ? 1.0 : 0.5)`
- 跨卷次数 `hit_papers` = 该考点出现过的不同 `src.paper` 数
- 优先级用 `hit_papers` 排序，不用总次数：同卷错 5 次可能是该卷偏，五卷各错 1 次才是真薄弱
- 错误率 = 该题型加权错空数 / Σ `total_in_section`；加权失分 = 错误率 × 满分
- 只统计 `status` 为 活跃 / 休眠 的记录
- 关联：同 `paper` 同 `no` 不同 `slot` 为强关联，`no` 差值 ≤2 为弱关联，出现 ≥3 次才成立

## 5. 分析禁止项

- 不写叙事词：综上所述、值得注意的是、从数据中可以看出、不难发现、总体来看
- 不写自我指涉：本次分析、本报告、通过分析我们发现
- 不写空泛建议：加强学习、多加练习、注意区分、认真复习、巩固基础
- 报告层不出现题号
- 不推测「为什么会错」的心理层级：数据里没有，推出来的都是假信号
- 删掉后结论不变的句子，一律删掉
        """.trimIndent().lines()
    }
}
