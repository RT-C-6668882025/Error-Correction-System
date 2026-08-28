package com.ecs.core.tree

/**
 * 考点树的前两层骨架：三个大类、十九个中类，写死在代码里。
 *
 * 为什么不让模型生成整棵树：
 *
 * 1. 稳定。中类是固定词表，重新生成一次也不会换名字。复习页自下而上合并同类项
 *    靠的是 [truncate] 按层截断，模型每次自由发挥的中层名会让上层分组直接裂开。
 * 2. 可靠。一个分支一次调用，输出小、快、失败只损失这一个分支；
 *    原来一次要 16000 token 生成整棵树，中途截断等于全部白跑。
 * 3. 可筛。[Branch.scope] 是给模型的范围约束，也是给人看的「这个分支管什么」。
 *
 * 模型只负责第三、四层——把中类细分到一个可执行动作。
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

    /** 大类 → 该类下的中类。设置页按这个分组展示。 */
    fun branchesOf(root: String): List<Branch> = BRANCHES.filter { it.root == root }

    /** 按前两层匹配所属分支；对不上返回 null。 */
    fun branchOf(path: String): Branch? {
        val parts = path.split("/")
        if (parts.size < 2) return null
        return BRANCHES.firstOrNull { it.root == parts[0] && it.mid == parts[1] }
    }

    /**
     * 合格的末端路径：前两层命中骨架，总深度 3–4 层，每一层都不为空。
     *
     * 深度 2 不算合格——那是中类本身，范围太大，对应不到一个可执行动作。
     */
    fun isValidPath(path: String): Boolean {
        val parts = path.split("/")
        if (parts.size !in 3..4) return false
        if (parts.any { it.isBlank() }) return false
        return branchOf(path) != null
    }
}
