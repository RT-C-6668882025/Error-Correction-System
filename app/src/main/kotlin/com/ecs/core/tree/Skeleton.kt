package com.ecs.core.tree

/** 顶层三分，不可增删。 */
object TopLevel {
    const val LEXICAL = "词法"
    const val SYNTAX = "句法"
    const val GRAMMAR = "语法"
    val all = listOf(LEXICAL, SYNTAX, GRAMMAR)
}

/**
 * 板块骨架：三个大类、十九个中类，写死在代码里。
 *
 * 板块是「小方向」的作用域——一个板块下所有题的分析汇总成一棵考点树，
 * 十九棵树再汇总成大方向。写死而不是让模型每次自己分组，是因为：
 *
 * 1. 名字固定。汇总跑两次不会一次叫「名词」一次叫「名词用法」，历史能对比。
 * 2. 范围清楚。[Branch.scope] 进提示词限定模型别跑题，也告诉你这一支管什么。
 * 3. 归属可校验。分析输出的 branch 必须命中这十九支之一，对不上就是未归类，
 *    留在原题页等你处理，而不是硬塞进一个看起来最像的。
 */
object Skeleton {

    data class Branch(val root: String, val mid: String, val scope: String) {
        val path: String get() = "$root/$mid"
    }

    val BRANCHES: List<Branch> = listOf(
        // 词法：词该长什么样
        Branch(TopLevel.LEXICAL, "名词", "单复数、可数不可数、所有格、名词化后缀、抽象名词与具体名词"),
        Branch(TopLevel.LEXICAL, "代词", "人称与格、物主、反身、指示、不定代词、it 的用法"),
        Branch(TopLevel.LEXICAL, "形容词", "比较级最高级、构词后缀、-ed 与 -ing 之分、修饰位置"),
        Branch(TopLevel.LEXICAL, "副词", "由形容词构词、比较级、程度与频度、修饰对象"),
        Branch(TopLevel.LEXICAL, "动词", "词形变化、及物不及物、动词短语、系动词、构词后缀"),
        Branch(TopLevel.LEXICAL, "数词", "基数词与序数词、分数与倍数、年代与时刻的表达"),
        Branch(TopLevel.LEXICAL, "介词", "时间地点方位、固定搭配中的介词、介词后接形式"),
        Branch(TopLevel.LEXICAL, "连词", "并列与转折、因果与条件、连接副词、关联连词成对出现"),
        Branch(TopLevel.LEXICAL, "冠词", "定冠词与不定冠词、零冠词、a 与 an 的选择、固定搭配"),

        // 句法：词怎么排怎么连
        Branch(TopLevel.SYNTAX, "句子成分", "主语宾语表语、定语状语补语、同位语、成分缺失时该补什么"),
        Branch(TopLevel.SYNTAX, "句子结构", "简单句五种基本句型、并列句、复合句、语序与倒装"),
        Branch(TopLevel.SYNTAX, "句子种类", "陈述疑问祈使感叹、反义疑问句、省略与强调"),

        // 语法：这样表达什么意思
        Branch(TopLevel.GRAMMAR, "主谓一致", "语法一致与意义一致、就近原则、集合名词与不定代词作主语"),
        Branch(TopLevel.GRAMMAR, "时态", "各时态的构成与触发信号、时态呼应、时间状语的指示作用"),
        Branch(TopLevel.GRAMMAR, "语态", "被动语态构成、各时态的被动形式、主动表被动、不可被动的动词"),
        Branch(TopLevel.GRAMMAR, "主从复合句", "定语从句、名词性从句、状语从句的引导词与从句形式"),
        Branch(TopLevel.GRAMMAR, "特殊句型", "there be、强调句、it 作形式主语宾语、比较句型、固定句式"),
        Branch(TopLevel.GRAMMAR, "虚拟语气", "条件句三种时态错位、名词性从句中的虚拟、含蓄虚拟条件"),
        Branch(TopLevel.GRAMMAR, "非谓语动词", "不定式、动名词、现在分词、过去分词各自的触发条件与形式"),
    )

    /** 大类 → 该类下的中类。 */
    fun branchesOf(root: String): List<Branch> = BRANCHES.filter { it.root == root }

    /**
     * 匹配 `大类/中类`。
     *
     * 逐字相等太脆：模型给「词法 / 名词」「词法／名词」「　词法/名词　」的时候，
     * 它其实答对了，判成未归类是我们的问题不是它的。所以每一段单独 trim、
     * 全角斜杠归一；只给了中类（「名词」）也认——十九个中类互不重名，
     * 唯一匹配是安全的。真的对不上才返回 null，不猜一个最像的。
     */
    fun branchOf(path: String?): Branch? {
        val parts = segments(path) ?: return null
        // 前两段命中骨架：正常情况
        if (parts.size >= 2) {
            BRANCHES.firstOrNull { it.root == parts[0] && it.mid == parts[1] }?.let { return it }
        }
        // 只给了中类，或大类写歪了：按中类唯一匹配兜一次
        return parts.firstNotNullOfOrNull { seg -> BRANCHES.filter { it.mid == seg }.singleOrNull() }
    }

    /** 归一化后的路径分段：全角斜杠归一、逐段 trim、丢掉空段。 */
    private fun segments(path: String?): List<String>? {
        if (path.isNullOrBlank()) return null
        val parts = path.replace('／', '/').split("/").map { it.trim() }.filter { it.isNotEmpty() }
        return parts.ifEmpty { null }
    }

    fun isBranch(path: String?): Boolean = branchOf(path) != null

    /** 给模型看的清单：它只能从这里面选一个。 */
    fun listing(): String = BRANCHES.joinToString("\n") { "- ${it.path}｜${it.scope}" }
}
