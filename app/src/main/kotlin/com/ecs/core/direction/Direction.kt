package com.ecs.core.direction

import kotlinx.serialization.Serializable

/**
 * 方向：一棵考点树，由若干条分析汇总而来。
 *
 * 形态就是这样的：
 *
 *     名
 *     ├── 专有n
 *     └── 普通n
 *         ├── 可数n
 *         │   └── 可数单→复
 *         │       ├── 规则
 *         │       └── 不规则
 *         └── 不可数n
 *
 * 小方向的作用域是一个板块（`词法/名词`），大方向的作用域是全部。
 * 两级用的是同一个结构：大方向那一级读的输入，正是各小方向的这棵树。
 */
@Serializable
data class DirectionNode(
    val name: String,
    /** 末端才有：这一类空该填成什么形态，一句话。 */
    val rule: String? = null,
    val children: List<DirectionNode> = emptyList(),
) {
    val leaf: Boolean get() = children.isEmpty()
}

@Serializable
data class Direction(
    /** `词法/名词`，或大方向的 [ALL]。 */
    val scope: String,
    val nodes: List<DirectionNode>,
    /** 由多少条分析（大方向则是多少个小方向）汇总而来。 */
    val fromCount: Int,
    val generatedAt: Long,
) {
    val empty: Boolean get() = nodes.isEmpty()

    /** 末端的形态规则，按出现顺序去重。上一级要读的就是这个。 */
    fun rules(): List<String> = leaves().mapNotNull { it.rule?.takeIf { r -> r.isNotBlank() } }.distinct()

    fun leaves(): List<DirectionNode> = nodes.flatMap { leavesOf(it) }

    private fun leavesOf(node: DirectionNode): List<DirectionNode> =
        if (node.leaf) listOf(node) else node.children.flatMap { leavesOf(it) }

    fun size(): Int = nodes.sumOf { countOf(it) }

    private fun countOf(node: DirectionNode): Int = 1 + node.children.sumOf { countOf(it) }

    /** 渲染成上面那种树枝形态。喂给下一级、也直接显示给人看。 */
    fun render(): String = buildString {
        nodes.forEach { renderNode(it, indent = "", last = true, root = true, sb = this) }
    }.trimEnd()

    private fun renderNode(
        node: DirectionNode,
        indent: String,
        last: Boolean,
        root: Boolean,
        sb: StringBuilder,
    ) {
        val label = node.rule?.takeIf { it.isNotBlank() }?.let { "${node.name}　$it" } ?: node.name
        sb.appendLine(if (root) label else indent + (if (last) "└── " else "├── ") + label)
        // 顶层节点自己顶格，它的孩子从第 0 列开始画枝
        val childIndent = if (root) "" else indent + (if (last) "    " else "│   ")
        node.children.forEachIndexed { i, child ->
            renderNode(child, childIndent, i == node.children.lastIndex, root = false, sb = sb)
        }
    }

    companion object {
        const val ALL = "全部"
        const val MAX_DEPTH = 5

        /**
         * 模型的输出未必干净：空名字、深到没边的嵌套、只有一个空壳的分支都可能出现。
         * 清洗放在这里做，纯函数，可以单独测。
         */
        fun clean(nodes: List<DirectionNode>, depth: Int = 1): List<DirectionNode> {
            if (depth > MAX_DEPTH) return emptyList()
            return nodes.mapNotNull { node ->
                val name = node.name.trim()
                if (name.isEmpty()) return@mapNotNull null
                val children = clean(node.children, depth + 1)
                val rule = node.rule?.trim()?.takeIf { it.isNotEmpty() }
                // 既没有形态也没有子节点的节点是空壳，留着只会让树看起来更满。
                // 顶层也一样：清完孩子之后空了的容器节点没有存在的理由
                if (children.isEmpty() && rule == null) return@mapNotNull null
                DirectionNode(name = name, rule = rule, children = children)
            }
        }
    }
}
