package com.ecs.agent

import com.ecs.core.agg.Aggregator
import com.ecs.core.prompt.PromptSlot
import com.ecs.core.report.ReportBuilder
import com.ecs.core.rules.StyleGuard
import kotlinx.serialization.json.jsonPrimitive

/**
 * 复习视图的判断句。答案形式由本地聚合给出，模型只补「怎么认、怎么练」。
 * 反过来做（把原题丢给模型让它自己归纳形态）会得到读着顺但对不上号的东西。
 */
class Reporter(
    private val client: AgentClient,
    private val prompts: PromptProvider = PromptProvider.DEFAULT,
) {

    suspend fun narrate(group: Aggregator.Group): ReportBuilder.Narrative {
        val user = """
            ${ReportBuilder.facts(group)}

            输出 JSON：
            {
              "decisive_point": "一句话：看到什么特征就往哪个方向走",
              "confusable": "最容易和什么搞混，本质区别是什么",
              "fix_path": "专练什么，盯什么特征，练到看见什么条件反射想到什么"
            }
        """.trimIndent()
        val o = client.obj(
            client.complete(prompts.text(PromptSlot.REVIEW), user, maxTokens = 1500)
        )
        fun s(k: String) = StyleGuard.clean(o[k]?.jsonPrimitive?.content.orEmpty())
        return ReportBuilder.Narrative(
            decisivePoint = s("decisive_point"),
            confusable = s("confusable"),
            fixPath = s("fix_path"),
        )
    }
}
