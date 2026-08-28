package com.ecs.core.tree

import kotlinx.serialization.Serializable
import kotlin.math.sqrt

/** 顶层三分，不可增删（PRD 2.7）。 */
object TopLevel {
    const val LEXICAL = "词法"
    const val SYNTAX = "句法"
    const val GRAMMAR = "语法"
    val all = listOf(LEXICAL, SYNTAX, GRAMMAR)
    fun isValidRoot(path: String) = path.substringBefore("/") in all
}

/** 考点树末端节点。每个末端必须带 form_rule 与 embedding。 */
@Serializable
data class TreeNode(
    val path: String,
    val formRule: String,
    val embedding: List<Float> = emptyList(),
    val createdIn: String = "v1",
    /** 由 F6 合并操作写入：本节点已被合并到哪个节点。 */
    val mergedInto: String? = null,
) {
    val depth: Int get() = path.split("/").size
    val leaf: String get() = path.substringAfterLast("/")
    val root: String get() = path.substringBefore("/")
    val alive: Boolean get() = mergedInto == null
}

@Serializable
data class KaodianTree(
    val version: String,
    val nodes: List<TreeNode>,
) {
    private val byPath: Map<String, TreeNode> by lazy { nodes.associateBy { it.path } }

    val liveNodes: List<TreeNode> get() = nodes.filter { it.alive }

    fun node(path: String): TreeNode? = byPath[path]

    fun contains(path: String): Boolean = byPath[path]?.alive == true

    fun formRuleOf(path: String): String? = byPath[path]?.takeIf { it.alive }?.formRule

    /** 合并后的重定向终点；防环。 */
    fun resolve(path: String): String? {
        var cur = byPath[path] ?: return null
        var hops = 0
        while (cur.mergedInto != null && hops < 16) {
            cur = byPath[cur.mergedInto!!] ?: return null
            hops++
        }
        return if (cur.alive) cur.path else null
    }

    /** F7.2：向量检索 Top-K，Agent 只能在这 K 个里选或输出 unmatched。 */
    fun topK(query: List<Float>, k: Int = 5): List<Scored> =
        liveNodes.asSequence()
            .filter { it.embedding.isNotEmpty() && it.embedding.size == query.size }
            .map { Scored(it, cosine(query, it.embedding)) }
            .sortedByDescending { it.score }
            .take(k)
            .toList()

    @Serializable
    data class Scored(val node: TreeNode, val score: Double)

    companion object {
        /** 版本号形如 v7，递增。 */
        fun bumpVersion(v: String): String {
            val n = v.removePrefix("v").toIntOrNull() ?: return "v1"
            return "v${n + 1}"
        }

        fun cosine(a: List<Float>, b: List<Float>): Double {
            if (a.isEmpty() || a.size != b.size) return 0.0
            var dot = 0.0; var na = 0.0; var nb = 0.0
            for (i in a.indices) {
                dot += a[i] * b[i].toDouble()
                na += a[i] * a[i].toDouble()
                nb += b[i] * b[i].toDouble()
            }
            if (na == 0.0 || nb == 0.0) return 0.0
            return dot / (sqrt(na) * sqrt(nb))
        }
    }
}

/** 4.7 聚合截断。 */
fun truncate(kaodian: String, depth: Int): String =
    kaodian.split("/").take(depth.coerceAtLeast(1)).joinToString("/")
