package com.ecs.core.parse

/**
 * 选择题剥离。选项只作为识别阶段的输入，不落库。
 *
 * 单选题的考点与语法填空完全重合，只是包装成四选一。剥掉选项后按填空题录入，
 * 与现有数据完全兼容：不新增字段，不新增枚举值，section 仍写「语法填空」。
 *
 * 不存 options，不存用户选了哪个（理由同 v2.1 不存 user：那是概率信息，
 * 错一次会连带污染 eye 和后续全部分析，而聚合后看不出来）。
 */
object ChoiceStripper {

    data class Option(val letter: Char, val content: String)

    data class Stripped(
        /** 去掉选项后的题干，保留带空的句子。 */
        val stem: String,
        /** 仅识别阶段可见，写入 answer 后即丢弃。 */
        val options: List<Option>,
        val isChoice: Boolean,
        /** 正确选项的内容。给不出正确字母时为 null。 */
        val answer: String?,
        /** 选项是同一词根的不同形态时取原形，否则留空。 */
        val given: String?,
    )

    /**
     * A. / A、/ A) / （A）/ Ａ．都算，允许全角。至少要有 A 和 B 两个才算选择题。
     *
     * 用「前一个字符不是字母数字」而不是「前面有空白」来定界：中文卷面上
     * 「他的建议很___。A、value」和「since 2019.（A）live」里选项前都没有空格。
     */
    private val MARKER = Regex("""(?<![A-Za-z0-9])[（(]?([A-EＡ-Ｅ])[)）.．、，,:：]\s*""")

    private const val MIN_OPTIONS = 2

    fun strip(raw: String, correctLetter: Char? = null): Stripped {
        val text = raw.trim()
        val markers = MARKER.findAll(text)
            .map { it to normalizeLetter(it.groupValues[1]) }
            .toList()

        // 按 A B C D E 的顺序取第一次出现，且位置必须递增
        val ordered = mutableListOf<Pair<MatchResult, Char>>()
        var expected = 'A'
        for ((match, letter) in markers) {
            if (letter == expected) {
                ordered += match to letter
                expected++
            }
        }
        if (ordered.size < MIN_OPTIONS) {
            return Stripped(text, emptyList(), isChoice = false, answer = null, given = null)
        }

        val stem = text.substring(0, ordered.first().first.range.first).trim()
        if (stem.isBlank()) {
            // 整段都是选项，没有题干，剥离没有意义
            return Stripped(text, emptyList(), isChoice = false, answer = null, given = null)
        }

        val options = ordered.mapIndexed { i, (match, letter) ->
            val start = match.range.last + 1
            val end = if (i + 1 < ordered.size) ordered[i + 1].first.range.first else text.length
            Option(letter, text.substring(start, end).trim().trim('；', ';', '。'))
        }.filter { it.content.isNotBlank() }

        if (options.size < MIN_OPTIONS) {
            return Stripped(text, emptyList(), isChoice = false, answer = null, given = null)
        }

        val answer = correctLetter?.let { c ->
            options.firstOrNull { it.letter == normalizeLetter(c.toString()) }?.content
        }
        return Stripped(
            stem = stem,
            options = options,
            isChoice = true,
            answer = answer,
            given = commonRoot(options.map { it.content }),
        )
    }

    private fun normalizeLetter(s: String): Char {
        val c = s.first()
        return if (c in 'Ａ'..'Ｅ') 'A' + (c - 'Ａ') else c.uppercaseChar()
    }

    // ---------- given 推导 ----------

    /**
     * 选项若是同一词根的不同形态（develop / developing / development / developed），
     * 取原形；否则返回 null，given 留空。
     *
     * 做法是给每个选项算出「可能的原形」集合再取交集，比按最长后缀一次性剥离更稳：
     * invention 既可能剥成 inven（-tion）也可能剥成 invent（-ion），交集会挑对那个。
     * 交集为空就返回 null——宁可 given 留空，也不要写进一个错的原形。
     */
    fun commonRoot(rawOptions: List<String>): String? {
        val words = rawOptions.map { it.trim().lowercase() }.distinct()
        if (words.size < 2) return null
        if (words.any { it.isEmpty() || it.any { c -> !c.isLetter() } }) return null

        val candidateSets = words.map { candidates(it) }
        var shared = candidateSets.first()
        candidateSets.drop(1).forEach { shared = shared intersect it }
        if (shared.isEmpty()) return null

        // 优先选那个本身就是选项之一的（develop），否则取最长的（invent 而不是 inven）
        return shared.firstOrNull { it in words } ?: shared.maxByOrNull { it.length }
    }

    private val SUFFIXES = listOf(
        "ation", "ment", "ness", "tion", "sion", "ance", "ence", "able", "ible",
        "ing", "ion", "ity", "ive", "ful", "less", "est", "ed", "es", "er", "ly", "al", "s",
    )

    private const val MIN_ROOT = 3

    private fun candidates(word: String): Set<String> {
        val out = linkedSetOf(word)
        SUFFIXES.forEach { suffix ->
            if (word.length - suffix.length >= MIN_ROOT && word.endsWith(suffix)) {
                val base = word.dropLast(suffix.length)
                out += base
                restoreY(base)?.let { out += it }
                undouble(base)?.let {
                    out += it
                    restoreY(it)?.let { y -> out += y }
                }
            }
        }
        return out
    }

    /** applied → appli → apply */
    private fun restoreY(base: String): String? =
        if (base.endsWith("i") && base.length > MIN_ROOT) base.dropLast(1) + "y" else null

    /** planned → plann → plan */
    private fun undouble(base: String): String? {
        if (base.length <= MIN_ROOT) return null
        val last = base.last()
        return if (last == base[base.length - 2] && last !in "aeiou") base.dropLast(1) else null
    }
}
