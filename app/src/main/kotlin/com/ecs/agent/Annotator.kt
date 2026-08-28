package com.ecs.agent

import com.ecs.core.model.Difficulty
import com.ecs.core.model.ErrorRecord
import com.ecs.core.prompt.PromptSlot
import com.ecs.core.rules.Validation
import com.ecs.core.tree.Embedder
import com.ecs.core.tree.KaodianTree
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive

/**
 * F7.2 标注。关键约束：Agent 负责判断，不负责记忆。
 *
 * 检索出 Top-5 之后，Agent 只能在这 5 个里选或输出 unmatched；标注环节禁止新建节点。
 * 全树塞进上下文会让它对边缘节点注意力衰减，第 50 条和第 300 条给同类题不同路径，聚合直接裂开。
 *
 * 选择题的选项可以进输入（判断需要），但不许进输出：题眼只描述题干里的客观特征。
 * 剥离后推不出 form_rule 的题——本质不是「填什么形态」——不强行标注，退回人工队列。
 * 这一条是筛子：真正属于填空类考点的选择题会顺利通过，不属于的会被挡下来。
 */
class Annotator(
    private val client: AgentClient,
    private val prompts: PromptProvider = PromptProvider.DEFAULT,
) {

    data class Input(
        val stem: String,
        val given: String?,
        val answer: String?,
        val sectionLabel: String,
        /** 仅作判断依据，不得出现在输出里；不落库。 */
        val options: List<String> = emptyList(),
    )

    data class Output(
        val kaodian: String?,
        val eye: String,
        val formContext: String?,
        val difficulty: Difficulty,
        val unmatched: Boolean,
        /** 剥离后推不出形态：这道题不属于填空类考点。 */
        val notFormType: Boolean,
        val candidates: List<String>,
    )

    suspend fun annotate(input: Input, tree: KaodianTree, k: Int = 5): Output {
        val query = Embedder.embed(
            listOf(input.stem, input.given.orEmpty(), input.answer.orEmpty()).joinToString("")
        )
        val hits = tree.topK(query, k)
        if (hits.isEmpty()) {
            throw AgentClient.AgentException("考点树为空或未计算 embedding")
        }
        val listing = hits.mapIndexed { i, h ->
            "${i + 1}. ${h.node.path}｜规则形态：${h.node.formRule}"
        }.joinToString("\n")

        val user = """
            题型：${input.sectionLabel}
            题干：${input.stem}
            提示词：${input.given ?: "无"}
            正确答案：${input.answer ?: "暂缺"}
            ${if (input.options.isEmpty()) "" else "原题选项（仅供你判断，禁止写进 eye）：${input.options.joinToString(" / ")}"}

            候选考点（只能从中选）：
            $listing

            输出 JSON，不要有其他文字：
            {"choice":1,"eye":"...","form_context":"...","difficulty":"中等"}
        """.trimIndent()

        var last: Output? = null
        repeat(2) { attempt ->
            val raw = client.complete(
                system = systemPrompt(input.options.isNotEmpty()) + retryHint(attempt),
                user = user,
                maxTokens = 1024,
                temperature = if (attempt == 0) 0.0 else 0.3,
            )
            val obj = client.obj(raw)
            val choice = obj["choice"]?.jsonPrimitive?.content?.trim()
            val eye = obj["eye"]?.jsonPrimitive?.content?.trim().orEmpty()
            val ctx = obj["form_context"]?.jsonPrimitive?.content?.trim()?.takeIf { it.isNotBlank() }
            val diff = obj["difficulty"]?.jsonPrimitive?.content?.trim()
                ?.let { Difficulty.fromLabel(it) } ?: Difficulty.MEDIUM

            val notForm = choice == NOT_FORM
            val index = choice?.toIntOrNull()
            val unmatched = notForm || index == null || index !in 1..hits.size
            val out = Output(
                kaodian = if (unmatched) null else hits[index!! - 1].node.path,
                eye = eye,
                formContext = ctx,
                difficulty = diff,
                unmatched = unmatched,
                notFormType = notForm,
                candidates = hits.map { it.node.path },
            )
            last = out
            // 推不出形态的题不重试，直接退回人工：重试只会逼它编一个
            if (notForm) return out
            // 题眼写砸整条数据作废，值得再要一次
            if (Validation.checkEye(eye).isEmpty() && Validation.checkFormContext(ctx).isEmpty()) {
                return out
            }
        }
        return last!!
    }

    /** 选择题多追加一段：选项可进输入，不可进输出。 */
    private suspend fun systemPrompt(hasOptions: Boolean): String {
        val base = prompts.text(PromptSlot.ANNOTATE)
        return if (hasOptions) base + "\n\n" + prompts.text(PromptSlot.ANNOTATE_CHOICE) else base
    }

    private fun retryHint(attempt: Int) =
        if (attempt == 0) "" else "\n\n上一次的 eye 不合格（长度越界或含禁用词）。重写，严格 10-25 字，只写客观特征。"

    /** 把标注结果落到记录上；form_rule 从树带出，Agent 不参与。 */
    fun apply(record: ErrorRecord, out: Output, tree: KaodianTree): ErrorRecord = record.copy(
        kaodian = out.kaodian,
        formRule = out.kaodian?.let { tree.formRuleOf(it) },
        eye = out.eye.ifBlank { null },
        formContext = out.formContext,
        difficulty = out.difficulty,
        treeVersion = tree.version,
    )

    companion object {
        const val NOT_FORM = "not_form"
    }
}
