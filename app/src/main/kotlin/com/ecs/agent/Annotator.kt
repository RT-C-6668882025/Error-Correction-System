package com.ecs.agent

import com.ecs.core.model.Difficulty
import com.ecs.core.model.ErrorRecord
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
 */
class Annotator(private val client: AgentClient) {

    data class Input(
        val stem: String,
        val given: String?,
        val answer: String?,
        val sectionLabel: String,
    )

    data class Output(
        val kaodian: String?,
        val eye: String,
        val formContext: String?,
        val difficulty: Difficulty,
        val unmatched: Boolean,
        val candidates: List<String>,
    )

    private fun system(banned: List<String>) = """
        你在给一条英语错题的「空」做标注。你只做判断，不做记忆：考点必须从给定候选里选。

        输出四件事：
        1. choice：候选编号 1-5，或字符串 "unmatched"（五个都不对时才用）
        2. eye 题眼：触发正确判断的客观语言特征。只写看得见的东西——位置、词形、标点、搭配。
           长度 10 到 25 字。不得出现这些词：${banned.joinToString("、")}。
           合格：空前有 the，空后无宾语 / as ... as 之间，修饰的是动词 / 中文提示「两小时的」，后接名词
           不合格：考查名词后缀转换（这是考点）/ 因为这里要用名词形式（这是解释）
        3. form_context 语境形态：本句独有的限定，最多 20 字，没有就给空字符串。
           只写这一句才成立的约束，例如「单数，谓语 has 限定」「被动，主语是承受者」。
           不要重复候选自带的规则形态。
        4. difficulty：简单 / 中等 / 难

        不要输出 form_rule，它由考点节点带出，与你无关。
    """.trimIndent()

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

            候选考点（只能从中选）：
            $listing

            输出 JSON，不要有其他文字：
            {"choice":1,"eye":"...","form_context":"...","difficulty":"中等"}
        """.trimIndent()

        var last: Output? = null
        repeat(2) { attempt ->
            val raw = client.complete(
                system = system(Validation.EYE_BANNED) + retryHint(attempt),
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

            val index = choice?.toIntOrNull()
            val unmatched = index == null || index !in 1..hits.size
            val out = Output(
                kaodian = if (unmatched) null else hits[index!! - 1].node.path,
                eye = eye,
                formContext = ctx,
                difficulty = diff,
                unmatched = unmatched,
                candidates = hits.map { it.node.path },
            )
            last = out
            // 题眼写砸整条数据作废，值得再要一次
            if (Validation.checkEye(eye).isEmpty() && Validation.checkFormContext(ctx).isEmpty()) {
                return out
            }
        }
        return last!!
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
}
