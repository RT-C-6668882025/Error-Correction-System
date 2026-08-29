package com.ecs.core.prompt

import com.ecs.core.rules.StyleGuard
import com.ecs.core.rules.Validation

/**
 * 提示词槽位。每段拆成两半：
 *
 * - [body]     可改正文：判断标准、语气、你自己想加的规则
 * - [contract] 只读契约：输出结构与硬约束，始终自动追加在末尾
 *
 * 这么拆是因为判断得准不准一半取决于提示词，你理应能改；
 * 但输出结构是解析的前提，改坏了整条链路会静默失败——页面照出，只是全空。
 *
 * 三个阶段各占一段：分析 → 小方向 → 大方向。
 * 后一段的输入永远是前一段的输出，所以改前一段会顺着往下影响，这是设计如此。
 */
enum class PromptSlot(
    val title: String,
    val description: String,
    val body: String,
    val contract: String,
) {

    ANALYZE(
        title = "分析",
        description = "每道题走一次。答案形式是这里推出来的——改这段等于改整套判断标准。",
        body = """
            你在分析一道英语填空题的一个空。结合上下文推断这个空的答案形式，
            不管它考的是词性还是固定搭配。

            答案形式说的是「这个空该填成什么」，它同时是一道排除题：
            考点是名词，答案就不可能是动词；考点是介词搭配，答案就只能是那个介词。
            把范围收到最窄的那一层，别停在「填一个合适的词」。

            判断依据只写看得见的东西——位置、词形、标点、搭配、前后成分。
            合格：空前有 the，空后接 of 短语 / as ... as 之间，修饰的是动词
            不合格：考查名词后缀转换（这是结论）/ 因为这里要用名词（这是解释）

            语境限定只写本句独有的收窄，比如「单数，谓语 has 限定」「被动，主语是承受者」。
            没有就留空，不要为了填满而写。
        """.trimIndent(),
        contract = """
            以下为固定约束，不可违反：
            1. branch 必须原样抄自给定的板块清单，一字不差；实在归不进去就输出 "unmatched"
            2. form：答案形式，一句话，说清「填成什么」，不要写成解题步骤
            3. basis：${Validation.BASIS_MIN} 到 ${Validation.BASIS_MAX} 字，
               不得出现这些词：${Validation.BASIS_BANNED.joinToString("、")}
            4. context：最多 ${Validation.FORM_CONTEXT_MAX} 字，没有就给空字符串
            5. 只输出 JSON，不要有其他文字：
               {"branch":"词法/名词","form":"...","basis":"...","context":"..."}
        """.trimIndent(),
    ),

    ANALYZE_CHOICE(
        title = "分析 · 选择题追加段",
        description = "只在分析剥离过选项的单选题时追加到上一段之后。",
        body = """
            这道题原本是选择题，选项已经剥掉。选项内容只给你做判断用，不要写进输出。
        """.trimIndent(),
        contract = """
            以下为固定约束，不可违反：
            1. basis 只描述题干里看得见的特征，不得提到选项，不得写「排除」「A项」这类词
            2. 如果剥掉选项后这道题就不成立了（答案不是某个形态，而是要读懂选项才能选），
               branch 输出 "unmatched"
        """.trimIndent(),
    ),

    DIRECTION_MINOR(
        title = "小方向（板块汇总）",
        description = "一个板块一次。输入是这一支下所有题的分析，输出是这一支的考点树。",
        body = """
            你在把一个板块下的若干条分析合并成这个板块的考点树。

            合并同类项：说的是同一件事的分析并成一个末端，形态相近的归到同一个父节点下。
            树要能一眼看出层级关系，比如名词这一支应该长成：
            专有名词与普通名词分开，普通名词下分可数与不可数，可数下再分单复数变化的规则与不规则。

            末端要细到能对应一个可执行动作——看到题就知道该做什么，不用再判断一次范围。
            每个末端给一句形态规则，可直接背。中间节点不用给规则。

            不要为了树好看而补上分析里没有出现过的考点。这棵树是这些题长出来的，
            题没覆盖到的地方就应该是空的。
        """.trimIndent(),
        contract = """
            以下为固定约束，不可违反：
            1. 只输出 JSON，不要有其他文字，结构为嵌套的节点数组：
               [{"name":"普通名词","children":[
                  {"name":"可数名词","children":[
                     {"name":"单数变复数·规则","rule":"词尾加 -s/-es"}]}]}]
            2. name 必填且不得为空；rule 只有末端节点才给
            3. 嵌套不超过 ${com.ecs.core.direction.Direction.MAX_DEPTH} 层
            4. 不出现题号、卷名、次数、错误率一类的东西——这棵树答的是「该填成什么形态」
        """.trimIndent(),
    ),

    DIRECTION_MAJOR(
        title = "大方向（全局汇总）",
        description = "输入只有各小方向的输出，不再回看原题。上一阶段的输出就是这一阶段的输入。",
        body = """
            你在把各个板块的小方向合并成一张全局的图。

            输入是每个板块已经汇总好的考点树，不是原题。你要做的是再上一层的合并同类项：
            哪些板块其实在考同一种判断（比如时态与语态都落在谓语动词的形式上），
            哪些形态彼此对立、最容易互相顶替。

            输出仍然是一棵树：顶层是词法 / 句法 / 语法，下面挂各板块，
            板块下只保留那些跨题反复出现、值得当作主线来练的形态。
            细枝末节留在小方向里，不要在这一层重复一遍。
        """.trimIndent(),
        contract = StyleGuard.PROMPT_RULE + "\n" + """
            另外：
            1. 只输出 JSON 嵌套节点数组，结构与小方向相同
            2. 顶层节点只能是 词法 / 句法 / 语法
            3. 嵌套不超过 ${com.ecs.core.direction.Direction.MAX_DEPTH} 层
        """.trimIndent(),
    ),

    SCAN(
        title = "试卷识别",
        description = "拍照录入的第一步。判据只有一条：句子 ≥1、句中空 ≥1。",
        body = """
            你在识别一张英语试卷的照片，只提取印刷体内容。

            提取每一个「空」：题号、该题内第几个空（一题一空时为 1）、含空的完整句子、
            括号里的提示词或中文提示。

            不区分题型。只要是一个句子、句子里有空，就算一条。
        """.trimIndent(),
        contract = """
            以下为固定约束，不可违反：
            1. 绝对不要识别手写内容，看到手写就当它不存在
            2. 看不清或不确定的，把 confidence 调低，不要猜。
               confidence 是 0 到 1 的数：完全清晰 1.0，有遮挡或模糊按把握给
            3. 如果这道题是四选一的单选题：
               - stem 里要原样保留 A/B/C/D 选项块，不要自己删掉，后续由程序剥离
               - 若印刷体上标出了正确答案的字母，写进 correct_letter；没标就留空
            4. 只输出 JSON 数组，不要有其他文字
        """.trimIndent(),
    ),

    SCAN_ANSWERS(
        title = "答案页识别",
        description = "批量回填答案时用。",
        body = """
            你在识别一张英语试卷的参考答案页，只提取印刷体的题号与答案。
        """.trimIndent(),
        contract = """
            以下为固定约束，不可违反：
            1. 看不清就把 confidence 调低，不要猜
            2. 选择题只有字母时，写进 correct_letter，answer 留空
            3. 只输出 JSON 数组，不要有其他文字
        """.trimIndent(),
    );

    /**
     * 生效全文。正文被清空时只剩契约——任务依旧能跑通，只是没了额外指引。
     */
    fun render(override: String? = null): String {
        val text = (override ?: body).trim()
        return if (text.isEmpty()) contract.trim() else "$text\n\n${contract.trim()}"
    }

    /** 用户是否改过这一段。 */
    fun modified(override: String?): Boolean =
        override != null && override.trim() != body.trim()

    companion object {
        fun fromName(name: String?): PromptSlot? = entries.firstOrNull { it.name == name }
    }
}
