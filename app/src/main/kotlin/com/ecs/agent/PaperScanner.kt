package com.ecs.agent

import com.ecs.core.model.Section
import com.ecs.core.parse.ChoiceStripper
import com.ecs.core.prompt.PromptSlot
import kotlinx.serialization.Serializable
import kotlinx.serialization.builtins.ListSerializer

/**
 * F1.1 识别：题号 + 题干 + 括号提示词 + 空位，并识别选择题版式。
 *
 * 明确不识别手写作答（附录 B）：识别错 user 会连带污染 eye 和后续全部分析，
 * 而这种噪声在聚合后看不出来。作答由用户直接输入错题号。
 *
 * 选择题的选项只走到这一层：剥离后把正确选项内容写进 answer，选项本身即丢弃。
 */
class PaperScanner(
    private val client: AgentClient,
    private val prompts: PromptProvider = PromptProvider.DEFAULT,
) {

    @Serializable
    data class RawQuestion(
        val no: Int = 0,
        val slot: Int = 1,
        val stem: String = "",
        val given: String? = null,
        val answer: String? = null,
        val correct_letter: String? = null,
        val confidence: Double = 0.0,
    )

    /**
     * [options] 与 [isChoice] 只服务于确认页展示，入库时被丢弃——
     * 数据库里不存在 options 这个字段。
     */
    data class Question(
        val no: Int,
        val slot: Int,
        val stem: String,
        val given: String?,
        val answer: String?,
        val confidence: Double,
        val isChoice: Boolean = false,
        val options: List<ChoiceStripper.Option> = emptyList(),
    )

    suspend fun scan(images: List<AgentClient.Image>, section: Section): List<Question> {
        val user = """
            题型：${section.label}
            输出 JSON 数组，不要有其他文字：
            [{"no":3,"slot":1,"stem":"The ___ (develop) of AI has changed everything.","given":"develop","answer":null,"correct_letter":null,"confidence":0.97}]
        """.trimIndent()
        val raw = client.complete(
            system = prompts.text(PromptSlot.SCAN),
            user = user,
            images = images,
            maxTokens = 8000,
            role = AgentClient.Role.VISION,
        )
        return client.decode(raw, ListSerializer(RawQuestion.serializer()))
            .filter { it.no > 0 && it.stem.isNotBlank() }
            .map { toQuestion(it) }
    }

    /** 剥离在本地做：可复现，也可以单独测。 */
    fun toQuestion(raw: RawQuestion): Question {
        val letter = raw.correct_letter?.trim()?.firstOrNull()
        val stripped = ChoiceStripper.strip(raw.stem, letter)
        return Question(
            no = raw.no,
            slot = raw.slot.coerceAtLeast(1),
            stem = stripped.stem,
            given = raw.given?.takeIf { it.isNotBlank() } ?: stripped.given,
            answer = raw.answer?.takeIf { it.isNotBlank() } ?: stripped.answer,
            confidence = raw.confidence,
            isChoice = stripped.isChoice,
            options = stripped.options,
        )
    }

    /** 识别答案页：题号 → 答案。选择题答案页给的是字母，交给确认页配对。 */
    suspend fun scanAnswers(images: List<AgentClient.Image>): List<RawQuestion> {
        val user = """
            这是答案页。输出 JSON 数组，只要题号、空序与答案：
            [{"no":3,"slot":1,"stem":"","answer":"development","correct_letter":"B","confidence":0.95}]
            选择题只有字母时，写进 correct_letter，answer 留空。
        """.trimIndent()
        val raw = client.complete(
            system = prompts.text(PromptSlot.SCAN_ANSWERS),
            user = user,
            images = images,
            maxTokens = 4000,
            role = AgentClient.Role.VISION,
        )
        return client.decode(raw, ListSerializer(RawQuestion.serializer())).filter { it.no > 0 }
    }
}
