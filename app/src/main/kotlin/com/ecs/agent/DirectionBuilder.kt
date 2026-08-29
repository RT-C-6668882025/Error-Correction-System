package com.ecs.agent

import com.ecs.core.agg.Aggregator
import com.ecs.core.direction.Direction
import com.ecs.core.direction.DirectionNode
import com.ecs.core.prompt.PromptSlot
import com.ecs.core.tree.TopLevel
import kotlinx.serialization.builtins.ListSerializer

/**
 * 功能二：递归式复习。两级，每一级的输入都是上一级的输出。
 *
 *   一道题的分析  ──┐
 *   一道题的分析  ──┼─→ 小方向（一个板块一棵树）──┐
 *   一道题的分析  ──┘                             ├─→ 大方向
 *                       另一个板块的小方向 ───────┘
 *
 * [major] 只读各小方向的输出，绝不回头看原题——否则「上一阶段的输出成为
 * 这一阶段的输入」就成了一句空话，两级会各自从原始数据重算，结论也就对不上。
 */
class DirectionBuilder(
    private val client: AgentClient,
    private val prompts: PromptProvider = PromptProvider.DEFAULT,
) {

    /** 小方向：输入是这一支下每道题的分析。 */
    suspend fun minor(block: Aggregator.Block, now: Long = System.currentTimeMillis()): Direction {
        if (block.analyses.isEmpty()) {
            throw AgentClient.AgentException("${block.path} 下还没有分析好的题")
        }
        val raw = client.complete(
            system = prompts.text(PromptSlot.DIRECTION_MINOR),
            user = Aggregator.facts(block),
            maxTokens = 3000,
            temperature = 0.2,
        )
        return Direction(
            scope = block.path,
            nodes = parseNodes(raw),
            fromCount = block.size,
            generatedAt = now,
        )
    }

    /** 大方向：输入只有各小方向的输出。 */
    suspend fun major(minors: List<Direction>, now: Long = System.currentTimeMillis()): Direction {
        val usable = minors.filterNot { it.empty }
        if (usable.isEmpty()) throw AgentClient.AgentException("还没有任何小方向可以汇总")
        val raw = client.complete(
            system = prompts.text(PromptSlot.DIRECTION_MAJOR),
            user = majorInput(usable),
            maxTokens = 4000,
            temperature = 0.2,
        )
        return Direction(
            scope = Direction.ALL,
            nodes = parseNodes(raw),
            fromCount = usable.size,
            generatedAt = now,
        )
    }

    /**
     * 大方向的输入负载。这里只允许出现小方向的 scope 与它那棵树——
     * 一旦掺进原题字段，这一级就不再是「读上一级的输出」了。
     */
    fun majorInput(minors: List<Direction>): String = buildString {
        appendLine("以下是每个板块已经汇总好的小方向，按大类排列。")
        appendLine("你的输入只有这些树，没有原题。")
        appendLine()
        TopLevel.all.forEach { root ->
            val inRoot = minors.filter { it.scope.substringBefore('/') == root }
            if (inRoot.isEmpty()) return@forEach
            appendLine("# $root")
            inRoot.forEach {
                appendLine("## ${it.scope}")
                appendLine(it.render())
                appendLine()
            }
        }
    }.trimEnd()

    /** 解析与清洗分开做，可脱离网络单独测。 */
    fun parseNodes(raw: String): List<DirectionNode> =
        Direction.clean(client.decode(raw, ListSerializer(DirectionNode.serializer())))
}
