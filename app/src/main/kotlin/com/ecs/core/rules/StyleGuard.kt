package com.ecs.core.rules

/**
 * 5.3 文案禁止项。所有 AI 生成文本过这一道；
 * 删掉后结论不变的句子，一律删掉。
 */
object StyleGuard {

    val NARRATIVE = listOf("综上所述", "值得注意的是", "从数据中可以看出", "不难发现", "总体来看")
    val SELF_REF = listOf("本次分析", "本报告", "通过分析我们发现", "本次统计")
    val EMPTY_ADVICE = listOf("加强学习", "多加练习", "注意区分", "认真复习", "巩固基础")

    val ALL: List<String> = NARRATIVE + SELF_REF + EMPTY_ADVICE

    /** 报告层一律不出现题号。 */
    private val QUESTION_NO = Regex("(gf|wc)_\\d{3}_\\d+|第\\s*\\d+\\s*题")

    fun violations(text: String): List<String> {
        val hits = ALL.filter { text.contains(it) }.toMutableList()
        if (QUESTION_NO.containsMatchIn(text)) hits += "题号"
        return hits
    }

    /**
     * 空泛建议整句删除（删掉后结论不变）；叙事词与自我指涉只抹掉词本身，
     * 句子的信息量在后半句，不能连着删。
     */
    fun clean(text: String): String {
        val out = text.lineSequence().map { line -> cleanLine(line) }
            .joinToString("\n")
        return QUESTION_NO.replace(out, "")
            .lineSequence().joinToString("\n") { it.trimEnd() }
            .replace(Regex("\n{3,}"), "\n\n")
            .trim()
    }

    private val SENTENCE = Regex("(?<=[。！；])")

    private fun cleanLine(line: String): String {
        val kept = line.split(SENTENCE)
            .filterNot { s -> EMPTY_ADVICE.any { s.contains(it) } }
        var out = kept.joinToString("")
        (NARRATIVE + SELF_REF).forEach { phrase ->
            out = Regex(Regex.escape(phrase) + "[，、：:,]?\\s*").replace(out, "")
        }
        return if (out.isBlank() && line.isNotBlank()) "" else out
    }

    /** 提示词片段：拼进每次 Agent 调用，从源头压制。 */
    val PROMPT_RULE: String = buildString {
        appendLine("文案禁止项，违反即视为无效输出：")
        appendLine("- 禁止叙事词：${NARRATIVE.joinToString("、")}")
        appendLine("- 禁止自我指涉：${SELF_REF.joinToString("、")}")
        appendLine("- 禁止空泛建议：${EMPTY_ADVICE.joinToString("、")}")
        appendLine("- 禁止出现任何题号")
        append("删掉后结论不变的句子，一律删掉。")
    }
}
