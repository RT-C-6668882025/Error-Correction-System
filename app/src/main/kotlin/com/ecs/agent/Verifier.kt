package com.ecs.agent

import com.ecs.core.model.ErrorRecord
import com.ecs.core.model.Verified
import com.ecs.core.tree.Embedder
import com.ecs.core.tree.KaodianTree
import com.ecs.core.tree.truncate
import kotlinx.serialization.json.jsonPrimitive
import kotlin.random.Random

/**
 * F8 标注验证。系统只有输入端和输出端，没有这一环就无法知道标注对不对；
 * 错标数据静默进入聚合，报告依然体面，结论完全跑偏。
 *
 * 第二次调用与 [Annotator] 用不同提示词，且不给第一次的结果。
 */
class Verifier(private val client: AgentClient) {

    /** 抽检比例 5%，不足一条时至少抽一条。 */
    fun sample(records: List<ErrorRecord>, rate: Double = 0.05, random: Random = Random.Default): List<ErrorRecord> {
        val pool = records.filter { !it.kaodian.isNullOrBlank() }
        if (pool.isEmpty()) return emptyList()
        val n = Math.max(1, Math.round(pool.size * rate).toInt())
        return pool.shuffled(random).take(n)
    }

    private val system = """
        判断下面这个英语填空的空考的是什么，从给定路径里挑一条最贴切的。
        只输出 JSON：{"path":"..."}；都不贴切时输出 {"path":"unmatched"}。
        不要解释。
    """.trimIndent()

    /** 比对截断到 depth=3 的路径。 */
    suspend fun verify(
        record: ErrorRecord,
        stem: String,
        tree: KaodianTree,
        depth: Int = 3,
    ): Verified {
        val first = record.kaodian ?: return Verified.UNCHECKED
        val query = Embedder.embed(stem + record.given.orEmpty() + record.answer.orEmpty())
        val hits = tree.topK(query, 5)
        val paths = (hits.map { it.node.path } + first).distinct()

        val user = buildString {
            appendLine("句子：$stem")
            appendLine("括号提示：${record.given ?: "无"}")
            appendLine("正确答案：${record.answer ?: "暂缺"}")
            appendLine("可选路径：")
            paths.forEach { appendLine("- $it") }
        }
        val raw = client.complete(system = system, user = user, maxTokens = 256, temperature = 0.0)
        val second = client.obj(raw)["path"]?.jsonPrimitive?.content?.trim().orEmpty()
        if (second.isBlank() || second == "unmatched") return Verified.CONFLICT
        return if (truncate(second, depth) == truncate(first, depth)) Verified.CONSISTENT else Verified.CONFLICT
    }
}
