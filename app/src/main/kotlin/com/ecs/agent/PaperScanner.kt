package com.ecs.agent

import com.ecs.core.model.Section
import kotlinx.serialization.Serializable
import kotlinx.serialization.builtins.ListSerializer

/**
 * F1.1 识别：题号 + 题干 + 括号提示词 + 空位。全印刷体。
 *
 * 明确不识别手写作答（附录 B）：识别错 user 会连带污染 eye 和后续全部分析，
 * 而这种噪声在聚合后看不出来。作答由用户直接输入错题号。
 */
class PaperScanner(private val client: AgentClient) {

    @Serializable
    data class Question(
        val no: Int = 0,
        val slot: Int = 1,
        val stem: String = "",
        val given: String? = null,
        val answer: String? = null,
        val confidence: Double = 0.0,
    )

    private val system = """
        你在识别一张专升本英语试卷的照片，只提取印刷体内容。

        提取每一个「空」：题号、该题内第几个空（一题一空时为 1）、含空的完整句子、
        括号里的提示词或中文提示。绝对不要识别手写内容，看到手写就当它不存在。
        看不清或不确定的，把 confidence 调低，不要猜。

        confidence 是 0 到 1 的数：完全清晰 1.0，有遮挡或模糊按把握给。
    """.trimIndent()

    suspend fun scan(images: List<AgentClient.Image>, section: Section): List<Question> {
        val user = """
            题型：${section.label}
            输出 JSON 数组，不要有其他文字：
            [{"no":3,"slot":1,"stem":"The ___ (develop) of AI has changed everything.","given":"develop","answer":null,"confidence":0.97}]
        """.trimIndent()
        val raw = client.complete(system, user, images = images, maxTokens = 8000)
        return client.decode(raw, ListSerializer(Question.serializer()))
            .filter { it.no > 0 && it.stem.isNotBlank() }
            .map { it.copy(slot = it.slot.coerceAtLeast(1)) }
    }

    /** 识别答案页：题号 → 答案。 */
    suspend fun scanAnswers(images: List<AgentClient.Image>): List<Question> {
        val user = """
            这是答案页。输出 JSON 数组，只要题号、空序与答案：
            [{"no":3,"slot":1,"stem":"","answer":"development","confidence":0.95}]
        """.trimIndent()
        val raw = client.complete(
            system = "你在识别一张英语试卷的参考答案页，只提取印刷体的题号与答案。看不清就把 confidence 调低。",
            user = user,
            images = images,
            maxTokens = 4000,
        )
        return client.decode(raw, ListSerializer(Question.serializer())).filter { it.no > 0 }
    }
}
