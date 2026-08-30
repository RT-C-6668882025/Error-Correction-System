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
     *
     * 送的是每棵树的压缩视图（骨架 + 去重后的末端清单），不是整棵树的全文渲染：
     * 十九个板块的树全文拼起来足以把上下文占满，而其中大量是重复的中间层。
     * 这一级要判断的是「哪些板块在考同一种判断」，骨架与末端清单就够了，
     * 细枝末节本来就该留在小方向里。
     */
    fun majorInput(minors: List<Direction>): String = buildString {
        appendLine("以下是每个板块已经汇总好的小方向，按大类排列。")
        appendLine("每个板块给的是它那棵树的骨架与去重后的末端，没有原题。")
        appendLine()
        TopLevel.all.forEach { root ->
            val inRoot = minors.filter { it.scope.substringBefore('/') == root }
            if (inRoot.isEmpty()) return@forEach
            appendLine("# $root")
            inRoot.forEach { direction ->
                appendLine("## ${direction.scope}（由 ${direction.fromCount} 条分析汇总）")
                direction.skeleton().takeIf { it.isNotEmpty() }?.let {
                    appendLine("骨架：${it.joinToString("／")}")
                }
                val leaves = direction.leafLines()
                appendLine("末端（${leaves.size} 条，已去重）：")
                leaves.take(LEAF_CAP).forEach { appendLine("- $it") }
                // 超出的不是丢掉，是留在小方向那一层——这一级本来就不该重复细节
                if (leaves.size > LEAF_CAP) {
                    appendLine("（另有 ${leaves.size - LEAF_CAP} 条更细的留在这个板块的小方向里）")
                }
                appendLine()
            }
        }
    }.trimEnd()

    /** 解析与清洗分开做，可脱离网络单独测。 */
    fun parseNodes(raw: String): List<DirectionNode> =
        Direction.clean(client.decode(raw, ListSerializer(DirectionNode.serializer())))

    companion object {
        /** 单个小方向往上送的末端条数上限。输入侧的界，输出侧的 maxTokens 与深度不动。 */
        const val LEAF_CAP = 30
    }
}
