package com.ecs.core.agg

/**
 * 同一层级内的去冗余。
 *
 * 一个板块下 42 道题原来是 42 行分析逐条送给模型，而这 42 行里往往只有几种不同的坑——
 * 「名词复数，词尾加 -s」这一种可能重复十几遍，依据也翻来覆去是同几句话。
 * 重复的部分既占上下文，又不带来任何新信息：模型要判断的是「这一支下有哪几种形态」，
 * 不是「哪一句话出现过几次」。
 *
 * 所以这里按坑归并、组内去重，只把频次带上去。压缩后的体积随**形态种类数**增长，
 * 而不再随题数增长——这就是递归总结能收敛的前提。
 *
 * 三条底线：
 * - 形态原文一字不改。归并的是重复，不是把考点抽象成更笼统的说法。
 * - 只做字面归一化，不做语义归并（近义词、编辑距离一律不碰）——那会真的丢东西。
 * - 结果确定：同一批输入永远压出同一个结果，跑几遍都一样，所以可以钉成测试。
 */
object Compressor {

    /** 一条证据（依据或语境），连同它重复出现的次数。 */
    data class Evidence(val text: String, val count: Int)

    /** 一个「坑」：一种答案形式，以及支撑它的证据。 */
    data class Pit(
        /** 组内首次出现的原文，一字不改。 */
        val shape: String,
        /** 几道题落在这个坑里。 */
        val count: Int,
        val bases: List<Evidence>,
        val contexts: List<Evidence>,
    )

    /**
     * 压缩结果。[tail] 是超出上限之后只留形态名的那些——覆盖不丢，但不带证据，
     * 这样即便某个板块攒出几百种形态，送出去的体积仍然有界。
     */
    data class Compressed(
        val pits: List<Pit>,
        val tail: List<Pit>,
        /** 压缩前的题数。 */
        val total: Int,
    ) {
        /** 形态种类总数。 */
        val kinds: Int get() = pits.size + tail.size
    }

    /**
     * [bases] / [contexts]：每个坑保留几条证据。取的是频次最高的那几条——
     * 反复出现的依据才是这个坑的共性，只出现一次的多半是那道题独有的细节。
     */
    data class Limits(val bases: Int = 3, val contexts: Int = 2, val pits: Int = 40)

    fun compress(block: Aggregator.Block, limits: Limits = Limits()): Compressed {
        val groups = LinkedHashMap<String, MutableList<Aggregator.Analysis>>()
        block.analyses.forEach { groups.getOrPut(key(it.formShape)) { mutableListOf() }.add(it) }

        // 频次降序；同频保持首次出现顺序，结果才可复现
        val pits = groups.values
            .map { group -> pit(group, limits) }
            .sortedByDescending { it.count }

        return Compressed(
            pits = pits.take(limits.pits),
            tail = pits.drop(limits.pits),
            total = block.size,
        )
    }

    private fun pit(group: List<Aggregator.Analysis>, limits: Limits) = Pit(
        shape = group.first().formShape,
        count = group.size,
        bases = evidence(group.map { it.basis }, limits.bases),
        contexts = evidence(group.map { it.formContext }, limits.contexts),
    )

    /** 一组证据去重计数，频次降序取前 [keep]；同频保持首次出现顺序。 */
    private fun evidence(values: List<String?>, keep: Int): List<Evidence> {
        val counts = LinkedHashMap<String, Pair<String, Int>>()
        values.forEach { raw ->
            val text = raw?.trim().orEmpty()
            if (text.isEmpty()) return@forEach
            val k = key(text)
            val hit = counts[k]
            // 保留首次出现的原文写法，后面的只加数
            counts[k] = if (hit == null) text to 1 else hit.first to hit.second + 1
        }
        return counts.values
            .map { (text, count) -> Evidence(text, count) }
            .sortedByDescending { it.count }
            .take(keep)
    }

    /**
     * 归并键。只抹掉写法上的差别：空白、全角标点、句末句号、英文大小写。
     * 意思不同的两句话在这里必须仍然不同——宁可多送一条，也不能把两个坑并成一个。
     */
    fun key(text: String): String {
        val half = text.map { FULL_WIDTH.indexOf(it).let { i -> if (i >= 0) HALF_WIDTH[i] else it } }
            .joinToString("")
        return half
            .replace(WHITESPACE, "")
            .trimEnd('.', ',', ';')
            .lowercase()
    }

    private val WHITESPACE = Regex("\\s+")
    private const val FULL_WIDTH = "，。；：（）／　"
    private const val HALF_WIDTH = ",.;:()/ "
}
