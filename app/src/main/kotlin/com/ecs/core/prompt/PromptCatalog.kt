package com.ecs.core.prompt

import com.ecs.core.rules.StyleGuard
import com.ecs.core.rules.Validation

/**
 * 提示词槽位。每段拆成两半：
 *
 * - [body]     可改正文：语气、判断标准、你自己想加的规则
 * - [contract] 只读契约：输出结构、文案禁止项、题眼禁用词，始终自动追加在末尾
 *
 * 这么拆是因为模型判断得准不准，一半取决于提示词，用户理应能改；
 * 但输出结构是解析的前提，改坏了整条链路会静默失败——报告照出，只是全空。
 * 契约锁死，正文随便改，两头都能顾上。
 */
enum class PromptSlot(
    val title: String,
    val description: String,
    val body: String,
    val contract: String,
) {

    TREE_GENERATE(
        title = "考点树生成",
        description = "首次启动时生成 120-180 个末端节点。改这里等于改整棵树的形状。",
        body = """
            你在为专升本英语备考构建考点树，服务对象只有两种题型：语法填空（20分）与完成句子（18分）。
            两者共享同一件事——给定语境，填出正确形态。

            顶层三分固定，不可增删：
            词法/   词该长什么样：词性、词形变化、时态形式、语态形式、非谓语形式、比较级、单复数、词根词缀、固定搭配、词义辨析
            句法/   词怎么排怎么连：句子成分、语序、倒装、从句结构、并列从属、省略、强调、主谓一致、平行结构
            语法/   这样表达什么意思：语气虚拟、情态、时态语义、冠词、代词指代、连词逻辑、介词逻辑、语篇衔接

            只收「答案是一个形态」的考点。阅读理解式的理解题不要。
            总数 120 到 180 个末端节点，三类都要覆盖，不要堆在词法。
        """.trimIndent(),
        contract = """
            以下为固定约束，不可违反：
            1. 每条路径深度 3 到 4 层，末端必须细到能对应一个可执行动作。
               合格：词法/名词/后缀转换/-tion   不合格：词法/名词/后缀转换（范围太大）
            2. 每个末端节点必须给出 form_rule：这一类空该填成什么形态，一句话，可直接背。
               form_rule 描述规则本身，不描述某一句的语境。
               合格：名词，动词加 -tion 后缀   不合格：根据句子结构判断词形
            3. 路径不得重复，同一父节点下的末端要互斥。
            4. 顶层只能是 词法 / 句法 / 语法。
        """.trimIndent(),
    ),

    ANNOTATE(
        title = "标注",
        description = "每条记录都会走一次。决定题眼写成什么样，进而决定倒推表好不好用。",
        body = """
            你在给一条英语错题的「空」做标注。你只做判断，不做记忆：考点必须从给定候选里选。

            题眼要写触发正确判断的客观语言特征。只写看得见的东西——位置、词形、标点、搭配。
            合格：空前有 the，空后无宾语 / as ... as 之间，修饰的是动词 / 中文提示「两小时的」，后接名词
            不合格：考查名词后缀转换（这是考点）/ 因为这里要用名词形式（这是解释）

            语境形态只写本句独有的限定，例如「单数，谓语 has 限定」「被动，主语是承受者」。
            不要重复候选自带的规则形态。
        """.trimIndent(),
        contract = """
            以下为固定约束，不可违反：
            1. choice：候选编号 1-5；五个都不对时用 "unmatched"；
               这道题的答案根本不是「某个词该长什么形态」时用 "not_form"
            2. eye：长度 10 到 25 字，不得出现这些词：${Validation.EYE_BANNED.joinToString("、")}
            3. form_context：最多 20 字，没有就给空字符串
            4. difficulty：简单 / 中等 / 难
            5. 不要输出 form_rule，它由考点节点带出，与你无关
            6. 只输出 JSON，不要有其他文字：
               {"choice":1,"eye":"...","form_context":"...","difficulty":"中等"}
        """.trimIndent(),
    ),

    ANNOTATE_CHOICE(
        title = "标注 · 选择题追加段",
        description = "只在标注剥离过选项的单选题时追加到上一段之后。",
        body = """
            这道题原本是选择题，选项已经剥掉。选项内容只给你做判断用，不要写进输出。
        """.trimIndent(),
        contract = """
            以下为固定约束，不可违反：
            1. eye 只描述题干里看得见的特征，不得提到选项，不得写「排除」「A项」这类词
            2. 如果剥掉选项后这道题就不成立了（答案不是某个形态，而是要读懂选项才能选），
               choice 输出 "not_form"
        """.trimIndent(),
    ),

    VERIFY(
        title = "标注抽检",
        description = "第二次独立调用，不给它第一次的结果。用来量标注流程本身健不健康。",
        body = """
            判断下面这个英语填空的空考的是什么，从给定路径里挑一条最贴切的。
            不要解释，不要复述题干。
        """.trimIndent(),
        contract = """
            以下为固定约束，不可违反：
            只输出 JSON：{"path":"..."}；都不贴切时输出 {"path":"unmatched"}。
        """.trimIndent(),
    ),

    REPORT_MICRO(
        title = "小方向报告",
        description = "按考点写判断句。数字已由本地算好，模型只补叙述。",
        body = """
            你在写一份英语错题诊断报告的「小方向」部分。数字已经算好，不要重算，也不要复述。
            只写判断：看到什么该往哪走、什么和什么容易混、下一步具体做什么。
            每句话都要能直接落到一个动作上。
        """.trimIndent(),
        contract = StyleGuard.PROMPT_RULE,
    ),

    REPORT_MACRO(
        title = "大方向报告",
        description = "全局格局与优先级排序的叙述部分。",
        body = """
            你在写一份英语错题诊断报告的「大方向」部分。数字已经算好，不要重算，也不要复述。
            只写判断：主战场在哪、什么和什么一起崩、下一步先修哪个。
            每句话都要能直接落到一个动作上。
        """.trimIndent(),
        contract = StyleGuard.PROMPT_RULE,
    ),

    TREE_MAINTAIN(
        title = "考点树维护",
        description = "命名归一、拆分、待归位的提议都由这段驱动。新建节点只发生在这里。",
        body = """
            你在维护一棵专升本英语考点树。顶层三分固定：词法 / 句法 / 语法。
            判断要保守：拿不准就不动，宁可留着重复也不要把两个不同的考点合成一个。
        """.trimIndent(),
        contract = """
            以下为固定约束，不可违反：
            1. 末端深度 3-4 层，必须细到能对应一个可执行动作
            2. 每个末端必须带 form_rule（一句话，可直接背）
            3. 顶层只能是 词法 / 句法 / 语法
            4. 只输出 JSON，不要解释
        """.trimIndent(),
    ),

    SCAN(
        title = "试卷识别",
        description = "拍照录入的第一步。改这里会影响识别出的题干与选项版式。",
        body = """
            你在识别一张专升本英语试卷的照片，只提取印刷体内容。

            提取每一个「空」：题号、该题内第几个空（一题一空时为 1）、含空的完整句子、
            括号里的提示词或中文提示。
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
