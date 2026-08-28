package com.ecs.core.agg

import com.ecs.core.model.ErrorRecord
import com.ecs.core.tree.KaodianTree
import com.ecs.core.tree.truncate

/**
 * 自下而上的递归聚合。
 *
 * 只做一件事：把原题按考点树的某个层级归堆，交出这一堆的「答案形式」。
 * 层级从末端（depth 4）往上走到大类（depth 1），上一层的输入就是下一层的输出——
 * 末端交出一条 form_rule，三层视图收到的就是这些 form_rule 的集合。
 *
 * 统计口径不再存在：错误率、加权失分、跨卷次数、成熟度门槛这些都答的是
 * 「你错得怎么样」，而这个应用要答的是「这一类空该填成什么形态」。
 */
object Aggregator {

    /** 复习层级。数字对应 [truncate] 的 depth。 */
    enum class Level(val depth: Int, val label: String, val hint: String) {
        LEAF(4, "末端", "一个考点对应一个可执行动作"),
        THIRD(3, "三层", "把相邻末端并成一组"),
        SECOND(2, "两层", "看词类/结构层面的共性"),
        ROOT(1, "大类", "词法 / 句法 / 语法");

        companion object {
            val DEFAULT = LEAF
            fun ofDepth(depth: Int): Level = entries.firstOrNull { it.depth == depth } ?: DEFAULT
        }
    }

    /** 一道题在复习视图里露出的东西：看到什么特征 → 填成什么。 */
    data class Row(
        val uid: String,
        val eye: String,
        val formContext: String?,
        val answer: String?,
        val stem: String?,
    )

    /**
     * 一个层级上的一组。
     *
     * [formRules] 就是这一组的输出：末端层是它自己的规则形态，
     * 往上则是下层各组规则形态的并集——「上一阶段的输出变成这一阶段的输入」。
     */
    data class Group(
        val kaodian: String,
        val root: String,
        val formRules: List<String>,
        val children: List<String>,
        val rows: List<Row>,
    ) {
        val leafName: String get() = kaodian.substringAfterLast('/')
        val size: Int get() = rows.size
    }

    /** 有考点、有形态的记录才进复习；缺标注的留在原题页等着补。 */
    fun countable(records: List<ErrorRecord>): List<ErrorRecord> =
        records.filter { !it.kaodian.isNullOrBlank() }

    fun groups(
        records: List<ErrorRecord>,
        tree: KaodianTree? = null,
        level: Level = Level.DEFAULT,
    ): List<Group> {
        val usable = countable(records)
        return usable.groupBy { truncate(it.kaodian!!, level.depth) }
            .map { (path, members) ->
                Group(
                    kaodian = path,
                    root = path.substringBefore('/'),
                    formRules = formRulesOf(path, members, tree),
                    // 这一组下面还压着哪些更细的考点，用来说明它是由什么汇总来的
                    children = members.mapNotNull { it.kaodian }.distinct().sorted()
                        .filter { it != path },
                    rows = members.filter { !it.eye.isNullOrBlank() }
                        .map { Row(it.uid, it.eye!!, it.formContext, it.answer, it.stem) },
                )
            }
            .sortedWith(compareBy({ it.root }, { it.kaodian }))
    }

    /**
     * 这一组的答案形式。末端层直接取节点自带的规则形态；
     * 往上则收集下层各条，去重后保序——那正是下一层要读的输入。
     */
    private fun formRulesOf(
        path: String,
        members: List<ErrorRecord>,
        tree: KaodianTree?,
    ): List<String> {
        tree?.formRuleOf(path)?.let { return listOf(it) }
        return members.mapNotNull { it.formRule?.takeIf { rule -> rule.isNotBlank() } }
            .distinct()
    }
}
