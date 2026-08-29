package com.ecs.core.export

import com.ecs.core.direction.Direction
import com.ecs.core.model.ErrorRecord
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json

/**
 * 导出。硬指标：导出包交给全新模型会话，不加任何提示词，就能读懂并接着干活。
 * README 是唯一的说明书，必须自洽且 ≤120 行。
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
        /** 两级汇总的产物，缺了就只有原始数据 + 分析。 */
        val directionsJson: String,
    )

    /**
     * 导出全部原题。这份包就是本地的源数据快照，
     * 还没分析的也要在里面，否则换个模型重跑时缺原料。
     */
    fun validRecords(records: List<ErrorRecord>): List<ErrorRecord> = records

    fun build(
        records: List<ErrorRecord>,
        directions: List<Direction>,
        dateStamp: String,
    ): Package {
        val valid = validRecords(records)
        return Package(
            dirName = "export_$dateStamp",
            readme = readme(valid, directions),
            dataJson = json.encodeToString(valid),
            dataCsv = csv(valid),
            directionsJson = json.encodeToString(directions),
        )
    }

    // ---------------- data.csv ----------------

    private val HEADERS = listOf(
        "id", "paper", "no", "slot", "batch",
        "stem", "given", "answer", "confidence", "created_at",
        "branch", "form_shape", "basis", "form_context", "status", "note",
    )

    fun csv(records: List<ErrorRecord>): String = buildString {
        appendLine(HEADERS.joinToString(","))
        records.forEach { r ->
            appendLine(
                listOf(
                    r.id, r.src.paper, r.src.no.toString(), r.src.slot.toString(), r.src.batch,
                    r.stem ?: "", r.given ?: "", r.answer ?: "",
                    r.confidence.label, r.createdAt.toString(),
                    r.branch ?: "", r.formShape ?: "", r.basis ?: "", r.formContext ?: "",
                    r.status.label, r.note ?: "",
                ).joinToString(",") { esc(it) }
            )
        }
    }

    private fun esc(v: String): String =
        if (v.any { it == ',' || it == '"' || it == '\n' }) "\"${v.replace("\"", "\"\"")}\"" else v

    // ---------------- README ----------------

    fun readme(records: List<ErrorRecord>, directions: List<Direction>): String {
        val head = head(records)
        val tail = tail(records)
        val budget = README_MAX_LINES - head.size - tail.size - 2

        val minors = directions.filter { it.scope != Direction.ALL && !it.empty }
        val dirLines = mutableListOf<String>()
        dirLines += "## 3. 已汇总的方向"
        dirLines += ""
        val room = (budget - 3).coerceAtLeast(0)
        minors.take(room).forEach { dirLines += "- `${it.scope}`　${it.size()} 个节点，由 ${it.fromCount} 条分析汇总" }
        if (minors.size > room) dirLines += "- …另有 ${minors.size - room} 个板块，完整的树见 directions.json"
        if (directions.any { it.scope == Direction.ALL }) dirLines += "- `全部`　大方向，输入是上面各板块的输出"
        dirLines += ""

        val all = head + dirLines + tail
        return (if (all.size > README_MAX_LINES) all.take(README_MAX_LINES) else all).joinToString("\n")
    }

    private fun head(records: List<ErrorRecord>): List<String> {
        val analyzed = records.count { it.analyzed() }
        return """
# 错题元数据

英语填空题的字段库。一条记录 = 一个「空」，共 ${records.size} 条，其中 $analyzed 条已分析。
不区分题型：收录判据只有一条——一个句子、句中至少一个空。

数据分三级，后一级的输入是前一级的输出：

    原始数据（题干 + 答案）→ 分析（每题一条）→ 小方向（每板块一棵树）→ 大方向

## 1. 数据文件

- `data.json` 全部记录，结构化
- `data.csv` 同一批数据的扁平化版本
- `directions.json` 两级汇总的产物，`scope` 为板块路径或「全部」
- `README.md` 字段词典与口径，交给别的 AI 时先给它这个

## 2. 字段词典

| 字段 | 类型 | 含义 / 取值 |
|---|---|---|
| `id` | string | `q_{三位题号}_{空序}`，卷内唯一；历史数据可能是 `gf_`/`wc_` 前缀 |
| `src.paper` | string | 题源卷名 |
| `src.no` / `src.slot` | int | 卷内题号 / 该题内第几个空 |
| `src.batch` | string | 录入批次 |
| `stem` | string? | 题干全文，选择题为剥掉选项后的句子 |
| `given` | string? | 括号提示词或中文提示 |
| `answer` | string? | 正确答案 |
| `confidence` | enum | 错 / 蒙对。逐题标记，没标的不会入库 |
| `branch` | string? | 板块，形如 `词法/名词`，取自固定的十九支骨架；null = 未归类 |
| `form_shape` | string? | 答案形式：这个空该填成什么。由分析结合上下文推断 |
| `basis` | string? | 判断依据：看到什么客观特征才推出上面那个形态，10-25 字 |
| `form_context` | string? | 语境限定，本句独有，一次性，不参与汇总 |
| `status` | enum | 已分析 / 待补答案 / 待分析 |
        """.trimIndent().lines() + ""
    }

    private fun tail(records: List<ErrorRecord>): List<String> {
        val sample = records.firstOrNull { it.analyzed() }
        val basis = sample?.basis ?: "空前有 the，空后接介词短语"
        val shape = sample?.formShape ?: "名词，动词加 -tion 后缀"
        val ctx = sample?.formContext ?: "单数，谓语 has 限定"
        val ans = sample?.answer ?: "development"
        val branch = sample?.branch ?: "词法/名词"
        return """
## 4. 分析范例

给定一条记录：

    branch       $branch
    basis        $basis
    form_shape   $shape
    form_context $ctx
    answer       $ans

应得结论：看到 basis 描述的特征，就往 form_shape 指的形态走；form_context 只决定这一句的
最终写法，不进入对该板块的判断。同一 branch 下的全部 basis 放在一起，若指向同一特征，
这一支是「识别问题」；若分成几组，说明有几种触发场景，要分别练。

## 5. 怎么往上汇总

- **小方向**：取一个 branch 下的全部 `form_shape` / `basis` / `form_context`，
  合并同类项，输出一棵考点树；末端要细到对应一个可执行动作，每个末端给一句形态规则
- **大方向**：输入**只有**各小方向的那些树，不要回头读原题。
  再上一层合并：哪些板块其实在考同一种判断，哪些形态彼此对立最容易互相顶替
- 不要算错误率、次数、难度分布这类统计——这份数据要答的是「该填成什么形态」，
  不是「错得怎么样」

## 6. 分析禁止项

- 不写叙事词：综上所述、值得注意的是、从数据中可以看出、不难发现、总体来看
- 不写自我指涉：本次分析、本报告、通过分析我们发现
- 不写空泛建议：加强学习、多加练习、注意区分、认真复习、巩固基础
- 汇总层不出现题号
- 不推测「为什么会错」的心理层级：数据里没有，推出来的都是假信号
- 部分记录由单选题剥离选项后录入，与填空记录完全同构，不要区分对待，也不要推测干扰项
- 删掉后结论不变的句子，一律删掉
        """.trimIndent().lines()
    }
}
