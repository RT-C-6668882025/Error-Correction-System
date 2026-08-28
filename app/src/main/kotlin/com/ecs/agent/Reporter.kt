package com.ecs.agent

import com.ecs.core.agg.Aggregator
import com.ecs.core.model.ErrorRecord
import com.ecs.core.report.ReportBuilder
import com.ecs.core.rules.StyleGuard
import com.ecs.core.tree.KaodianTree
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive

/**
 * F7.4 报告生成。数字由本地聚合给出，模型只写判断句。
 * 反过来做（把原始数据丢给模型让它自己数）必然出现算错但读着顺的报告。
 */
class Reporter(private val client: AgentClient) {

    private fun system(kind: String) = """
        你在写一份英语错题诊断报告的「$kind」部分。数字已经算好，不要重算，也不要复述。
        只写判断：看到什么该往哪走、什么和什么容易混、下一步具体做什么。
        每句话都要能直接落到一个动作上。

        ${StyleGuard.PROMPT_RULE}
    """.trimIndent()

    suspend fun micro(stat: Aggregator.KaodianStat): ReportBuilder.MicroNarrative {
        val user = """
            ${ReportBuilder.microFacts(stat)}

            输出 JSON：
            {
              "eye_commonality": "把全部题眼放在一起看，是否指向同一特征；若否，分组说明有几种触发场景",
              "decisive_point": "一句话：看到什么特征就往哪个方向走",
              "confusable": "最容易和什么搞混，本质区别是什么",
              "fix_path": "专练什么，盯什么特征，练到看见什么条件反射想到什么"
            }
        """.trimIndent()
        val o = client.obj(client.complete(system("小方向"), user, maxTokens = 1500))
        fun s(k: String) = StyleGuard.clean(o[k]?.jsonPrimitive?.content.orEmpty())
        return ReportBuilder.MicroNarrative(
            eyeCommonality = s("eye_commonality"),
            decisivePoint = s("decisive_point"),
            confusable = s("confusable"),
            fixPath = s("fix_path"),
        )
    }

    suspend fun macro(
        records: List<ErrorRecord>,
        tree: KaodianTree?,
        depth: Int,
    ): ReportBuilder.MacroNarrative {
        val stats = Aggregator.kaodianStats(records, tree, depth).filter { it.reportable }
        val total = Aggregator.countable(records).size.coerceAtLeast(1)
        val user = """
            ${ReportBuilder.macroFacts(records, tree, depth)}

            优先级最多 5 项，按「修复收益 ÷ 修复成本」排；候选顺序已按跨卷次数给出，
            同卷反复出错说明可能是该卷偏，跨卷反复才是真薄弱。

            输出 JSON：
            {
              "weakness": "主战场在哪一类；集中还是分散；难度分布说明什么；两个题型错误率的差异说明什么",
              "repeated": "跨卷反复出错的是哪些，同卷集中的是哪些",
              "correlation": "哪些考点总是一起崩，背后是不是同一个根源",
              "priorities": [
                {"kaodian":"...","why":"为什么优先","action":"具体行动","verify":"验证方式"}
              ],
              "ignore": "错误少、难度高、性价比低的，说明理由",
              "conclusion": "一句话结论"
            }
        """.trimIndent()
        val o = client.obj(client.complete(system("大方向"), user, maxTokens = 4000))
        fun s(k: String) = StyleGuard.clean(o[k]?.jsonPrimitive?.content.orEmpty())

        val priorities = o["priorities"]?.jsonArray.orEmpty().mapNotNull { el ->
            val p = el.jsonObject
            val kaodian = p["kaodian"]?.jsonPrimitive?.content?.trim() ?: return@mapNotNull null
            val stat = stats.firstOrNull { it.kaodian == kaodian }
            ReportBuilder.Priority(
                kaodian = kaodian,
                root = stat?.root ?: kaodian.substringBefore("/"),
                slots = stat?.slots ?: 0,
                share = (stat?.slots ?: 0).toDouble() / total,
                hitPapers = stat?.hitPapers ?: 0,
                formRule = stat?.formRule.orEmpty(),
                why = StyleGuard.clean(p["why"]?.jsonPrimitive?.content.orEmpty()),
                action = StyleGuard.clean(p["action"]?.jsonPrimitive?.content.orEmpty()),
                verify = StyleGuard.clean(p["verify"]?.jsonPrimitive?.content.orEmpty()),
            )
        }
        return ReportBuilder.MacroNarrative(
            weakness = s("weakness"),
            repeated = s("repeated"),
            correlation = s("correlation"),
            priorities = priorities,
            ignore = s("ignore"),
            conclusion = s("conclusion"),
        )
    }
}
