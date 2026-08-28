package com.ecs.agent

import com.ecs.core.model.Section
import com.ecs.core.parse.ChoiceStripper
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
class PaperScanner(private val client: AgentClient) {

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

    private val system = """
        你在识别一张专升本英语试卷的照片，只提取印刷体内容。

        提取每一个「空」：题号、该题内第几个空（一题一空时为 1）、含空的完整句子、
        括号里的提示词或中文提示。绝对不要识别手写内容，看到手写就当它不存在。
        看不清或不确定的，把 confidence 调低，不要猜。

        如果这道题是四选一的单选题：
        - stem 里要原样保留 A/B/C/D 选项块，不要自己删掉，后续由程序剥离
        - 若印刷体上标出了正确答案的字母，写进 correct_letter；没标就留空

        confidence 是 0 到 1 的数：完全清晰 1.0，有遮挡或模糊按把握给。
    """.trimIndent()

    suspend fun scan(images: List<AgentClient.Image>, section: Section): List<Question> {
        val user = """
            题型：${section.label}
            输出 JSON 数组，不要有其他文字：
            [{"no":3,"slot":1,"stem":"The ___ (develop) of AI has changed everything.","given":"develop","answer":null,"correct_letter":null,"confidence":0.97}]
        """.trimIndent()
        val raw = client.complete(system, user, images = images, maxTokens = 8000, role = AgentClient.Role.VISION)
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
            system = "你在识别一张英语试卷的参考答案页，只提取印刷体的题号与答案。看不清就把 confidence 调低。",
            user = user,
            images = images,
            maxTokens = 4000,
            role = AgentClient.Role.VISION,
        )
        return client.decode(raw, ListSerializer(RawQuestion.serializer())).filter { it.no > 0 }
    }
}
