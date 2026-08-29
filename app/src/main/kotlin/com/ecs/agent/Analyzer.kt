package com.ecs.agent

import com.ecs.core.model.ErrorRecord
import com.ecs.core.prompt.PromptSlot
import com.ecs.core.rules.Validation
import com.ecs.core.tree.Skeleton
import kotlinx.serialization.json.jsonPrimitive

/**
 * 功能一：分析。每道题一次。
 *
 * 它推断的是「这个空该填成什么形态」，顺带判定这道题归哪一支。
 * 归类只是为了后面能按板块汇总，不是分析的目的——所以归不进去也不硬塞，
 * 标成未归类留在原题页，比塞进一个看着最像的板块要好：
 * 汇总那一步读的是板块里的全部分析，混进一条不属于这里的会把整棵树带偏。
 *
 * 选择题的选项可以进输入（判断需要），但不许进输出。
 */
class Analyzer(
    private val client: AgentClient,
    private val prompts: PromptProvider = PromptProvider.DEFAULT,
) {

    data class Input(
        val stem: String,
        val given: String?,
        val answer: String?,
        /** 仅作判断依据，不得出现在输出里；不落库。 */
        val options: List<String> = emptyList(),
    )

    data class Output(
        /** 命中骨架时是 `词法/名词`，否则为 null。 */
        val branch: String?,
        val formShape: String,
        val basis: String,
        val formContext: String?,
        val unmatched: Boolean,
    )

    class EmptyResult(message: String) : Exception(message)

    suspend fun analyze(input: Input): Output {
        val user = """
            题干：${input.stem}
            括号提示词：${input.given ?: "无"}
            正确答案：${input.answer ?: "暂缺"}
            ${if (input.options.isEmpty()) "" else "原题选项（仅供判断，禁止写进 basis）：${input.options.joinToString(" / ")}"}

            板块清单（branch 只能从中原样抄一条）：
            ${Skeleton.listing()}
        """.trimIndent()

        var last: Output? = null
        repeat(2) { attempt ->
            val raw = client.complete(
                system = systemPrompt(input.options.isNotEmpty()) + retryHint(attempt),
                user = user,
                maxTokens = 1024,
                temperature = if (attempt == 0) 0.0 else 0.3,
            )
            val out = parse(raw)
            last = out
            // 依据写砸了整条数据就没法用，值得再要一次；归不进板块则不必重试
            if (Validation.checkBasis(out.basis).isEmpty() &&
                Validation.checkFormContext(out.formContext).isEmpty()
            ) {
                return out
            }
        }
        return last!!
    }

    /** 解析与校验分开做：可复现，也可以脱离网络单独测。 */
    fun parse(raw: String): Output {
        val o = client.obj(raw)
        fun str(k: String) = o[k]?.jsonPrimitive?.content?.trim().orEmpty()

        val branchRaw = str("branch")
        val branch = Skeleton.branchOf(branchRaw)
        val form = str("form")
        if (form.isEmpty()) throw EmptyResult("模型没有给出答案形式")
        return Output(
            branch = branch?.path,
            formShape = form,
            basis = str("basis"),
            formContext = str("context").takeIf { it.isNotBlank() },
            unmatched = branch == null,
        )
    }

    /** 把分析结果落到记录上。原始数据那一半一个字都不动。 */
    fun apply(record: ErrorRecord, out: Output): ErrorRecord = record.copy(
        branch = out.branch,
        formShape = out.formShape,
        basis = out.basis.ifBlank { null },
        formContext = out.formContext,
    )

    private suspend fun systemPrompt(hasOptions: Boolean): String {
        val base = prompts.text(PromptSlot.ANALYZE)
        return if (hasOptions) base + "\n\n" + prompts.text(PromptSlot.ANALYZE_CHOICE) else base
    }

    private fun retryHint(attempt: Int) =
        if (attempt == 0) "" else
            "\n\n上一次的 basis 不合格（长度越界或含禁用词）。重写，严格 " +
                "${Validation.BASIS_MIN}-${Validation.BASIS_MAX} 字，只写客观特征。"
}
