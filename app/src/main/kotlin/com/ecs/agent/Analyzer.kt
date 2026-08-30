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
        /** 模型原样给的 branch。归不进去时要把它引回去，否则它多半原样再给一遍。 */
        val rawBranch: String = "",
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
        var failure: Exception? = null
        repeat(2) { attempt ->
            // 解析炸了也值得再要一次：推理型模型第一次常常把话说在 JSON 外面，
            // 原来这里直接抛出去，整条题就废了，而它其实只是没按格式说话
            val out = try {
                val raw = client.complete(
                    system = systemPrompt(input.options.isNotEmpty()) + retryHint(last, failure),
                    user = user,
                    maxTokens = MAX_TOKENS,
                    temperature = if (attempt == 0) 0.0 else 0.3,
                )
                parse(raw)
            } catch (e: Exception) {
                failure = e
                return@repeat
            }
            failure = null
            last = out
            // 归不进板块的这条题在复习页无处可去，等于白分析——值得再要一次。
            // 依据写砸了同理：整条数据就没法用了。
            if (!out.unmatched &&
                Validation.checkBasis(out.basis).isEmpty() &&
                Validation.checkFormContext(out.formContext).isEmpty()
            ) {
                return out
            }
        }
        return last ?: throw failure ?: EmptyResult("模型没有给出可用的分析")
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
            rawBranch = branchRaw,
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

    /** 重试时说清上一次错在哪，否则它多半会原样再给一遍。 */
    private fun retryHint(last: Output?, failure: Exception?): String {
        if (failure != null) {
            return "\n\n上一次没能解析出结果（${failure.message?.take(80)}）。" +
                "这一次只输出那一个 JSON 对象，前后不要有任何解释或思考过程。"
        }
        if (last == null) return ""
        val problems = buildList {
            if (last.unmatched) {
                add("branch「${last.rawBranch.ifBlank { "空" }}」不在板块清单里。必须从清单中原样抄一条，一字不差")
            }
            if (Validation.checkBasis(last.basis).isNotEmpty()) {
                add("basis 不合格（长度越界或含禁用词），严格 ${Validation.BASIS_MIN}-${Validation.BASIS_MAX} 字，只写客观特征")
            }
            if (Validation.checkFormContext(last.formContext).isNotEmpty()) {
                add("context 超过 ${Validation.FORM_CONTEXT_MAX} 字，写不下就留空")
            }
        }
        if (problems.isEmpty()) return ""
        return "\n\n上一次的输出有问题，重写：\n" + problems.joinToString("\n") { "- $it" }
    }

    companion object {
        /**
         * 分析的输出很短（一个四字段 JSON），但推理型模型会先烧掉一大截 token 想事情，
         * 1024 常常不够——JSON 还没开头就被截断，报出来是一句「响应中没有 JSON」。
         * 给足余量，成本上的差别可以忽略。
         */
        const val MAX_TOKENS = 3072
    }
}
