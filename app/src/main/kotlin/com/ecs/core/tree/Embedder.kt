package com.ecs.core.tree

import kotlin.math.sqrt

/**
 * 本地 embedding：字符 unigram + bigram 哈希到固定维度，L2 归一化。
 *
 * 离线、零依赖、确定性——考点树节点与查询用同一套函数即可比较。
 * 它做的是字面重合度而非语义相似度，够用的原因是：检索只负责把 Top-5 候选交给
 * Agent，最终判断由 Agent 做（F7.2）。要换成语义模型，只需替换 [embed]。
 */
object Embedder {

    const val DIM = 256

    fun embed(text: String): List<Float> {
        val vec = FloatArray(DIM)
        val clean = text.lowercase().filter { !it.isWhitespace() }
        if (clean.isEmpty()) return vec.toList()
        for (i in clean.indices) {
            bump(vec, clean[i].toString(), 1.0f)
            if (i + 1 < clean.length) bump(vec, clean.substring(i, i + 2), 1.5f)
        }
        var norm = 0.0
        vec.forEach { norm += it * it }
        if (norm > 0) {
            val inv = (1.0 / sqrt(norm)).toFloat()
            for (i in vec.indices) vec[i] *= inv
        }
        return vec.toList()
    }

    private fun bump(vec: FloatArray, gram: String, weight: Float) {
        val h = gram.hashCode()
        val idx = ((h % DIM) + DIM) % DIM
        // 符号位打散，避免不同 gram 落到同一桶时无条件相加
        vec[idx] += if (h and 1 == 0) weight else -weight
    }

    /** 节点的可检索文本：路径末端权重最高，再拼上规则形态。 */
    fun nodeText(path: String, formRule: String): String {
        val parts = path.split("/")
        val leaf = parts.last()
        return buildString {
            append(leaf).append(leaf)
            parts.dropLast(1).forEach { append(it) }
            append(formRule)
        }
    }
}
