package com.ecs.agent

import com.ecs.core.model.ErrorRecord
import com.ecs.core.tree.Embedder
import com.ecs.core.tree.KaodianTree
import com.ecs.core.tree.TreeNode
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive

/**
 * F7.3 树维护 + F6 提议队列。新建节点只在这里发生，不在标注流程中。
 * 全部是「Agent 提议 + 人一键确认」，除向上合并外不自动改结构。
 */
class TreeMaintainer(private val client: AgentClient) {

    sealed interface Proposal {
        val summary: String

        /** 命名归一：语义相似的两个节点合成一个。 */
        data class Merge(val from: String, val into: String, val reason: String) : Proposal {
            override val summary get() = "合并「$from」→「$into」：$reason"
        }

        /** 向上合并：末端 < 3 条且总量 > 100，自动执行，记日志。 */
        data class RollUp(val from: String, val into: String, val slots: Int) : Proposal {
            override val summary get() = "向上合并「$from」（仅 $slots 条）→「$into」"
        }

        /** 向下拆分：末端占比 > 15%。 */
        data class Split(val path: String, val children: List<TreeNode>, val reason: String) : Proposal {
            override val summary get() = "拆分「$path」为 ${children.size} 个子节点：$reason"
        }

        /** unmatched 归位：攒够 10 条后归入现有节点或新建。 */
        data class Adopt(val into: String, val count: Int) : Proposal {
            override val summary get() = "$count 条待归位记录归入「$into」"
        }

        data class CreateNode(val node: TreeNode, val count: Int) : Proposal {
            override val summary get() = "新建节点「${node.path}」（$count 条待归位）· 规则形态：${node.formRule}"
        }
    }

    // ---------- 本地可判定的提议：不花一次调用 ----------

    /** 末端 < 3 条且总量 > 100 → 向上合并。 */
    fun rollUpCandidates(records: List<ErrorRecord>, tree: KaodianTree): List<Proposal.RollUp> {
        val counts = records.mapNotNull { it.kaodian }.groupingBy { it }.eachCount()
        if (records.size <= 100) return emptyList()
        return tree.liveNodes.mapNotNull { node ->
            val n = counts[node.path] ?: 0
            val parent = node.path.substringBeforeLast("/")
            if (n in 1 until 3 && parent.contains("/")) {
                Proposal.RollUp(node.path, parent, n)
            } else null
        }
    }

    /** 末端占比 > 15% → 拆分候选。 */
    fun splitCandidates(records: List<ErrorRecord>, threshold: Double = 0.15): List<String> {
        val used = records.mapNotNull { it.kaodian }
        if (used.isEmpty()) return emptyList()
        return used.groupingBy { it }.eachCount()
            .filterValues { it.toDouble() / used.size > threshold }
            .keys.toList()
    }

    /** 命名归一候选：先用 embedding 粗筛，再交给 Agent 判定，避免全量两两送模型。 */
    fun similarPairs(tree: KaodianTree, threshold: Double = 0.92): List<Pair<String, String>> {
        val nodes = tree.liveNodes
        val out = mutableListOf<Pair<String, String>>()
        for (i in nodes.indices) for (j in i + 1 until nodes.size) {
            val a = nodes[i]; val b = nodes[j]
            if (a.embedding.isEmpty() || b.embedding.isEmpty()) continue
            if (KaodianTree.cosine(a.embedding, b.embedding) >= threshold) out += a.path to b.path
        }
        return out
    }

    // ---------- 需要判断的提议 ----------

    suspend fun proposeMerges(pairs: List<Pair<String, String>>): List<Proposal.Merge> {
        if (pairs.isEmpty()) return emptyList()
        val user = buildString {
            appendLine("下面是考点树里字面相近的节点对。只有当两者本质是同一个考点时才建议合并；")
            appendLine("规则形态不同的一律不合并。")
            pairs.take(40).forEachIndexed { i, (a, b) -> appendLine("${i + 1}. $a ｜ $b") }
            appendLine()
            appendLine("""输出 JSON：[{"from":"被合并的路径","into":"保留的路径","reason":"一句话"}]""")
        }
        val arr = client.arr(client.complete(SYSTEM, user, maxTokens = 2000))
        return arr.mapNotNull {
            val o = it.jsonObject
            val from = o["from"]?.jsonPrimitive?.content ?: return@mapNotNull null
            val into = o["into"]?.jsonPrimitive?.content ?: return@mapNotNull null
            if (from == into) null else Proposal.Merge(from, into, o["reason"]?.jsonPrimitive?.content.orEmpty())
        }
    }

    /** 拆分方案读 src_ref 之外的可用信息：题眼与语境限定就是天然的分组依据。 */
    suspend fun proposeSplit(path: String, records: List<ErrorRecord>): Proposal.Split? {
        val members = records.filter { it.kaodian == path }
        if (members.size < 6) return null
        val user = buildString {
            appendLine("考点「$path」占比过高，需要拆细到「每个末端对应一个可执行动作」。")
            appendLine("它下面的题眼与语境限定：")
            members.forEach { appendLine("- ${it.eye}｜${it.formContext ?: "无"}｜${it.answer ?: "缺"}") }
            appendLine()
            appendLine("""输出 JSON：{"reason":"一句话","children":[{"path":"$path/xxx","form_rule":"..."}]}""")
            appendLine("children 至少 2 个，路径必须以「$path/」开头，深度不超过 4 层。")
        }
        val o = client.obj(client.complete(SYSTEM, user, maxTokens = 2000))
        val children = o["children"]?.jsonArray.orEmpty().mapNotNull {
            val c = it.jsonObject
            val p = c["path"]?.jsonPrimitive?.content?.trim() ?: return@mapNotNull null
            val rule = c["form_rule"]?.jsonPrimitive?.content?.trim().orEmpty()
            if (!p.startsWith("$path/") || p.split("/").size > 4 || rule.isBlank()) return@mapNotNull null
            TreeNode(p, rule, Embedder.embed(Embedder.nodeText(p, rule)))
        }
        if (children.size < 2) return null
        return Proposal.Split(path, children, o["reason"]?.jsonPrimitive?.content.orEmpty())
    }

    /** unmatched 攒够 10 条后归位：归入现有节点，或新建一个（含 form_rule）。 */
    suspend fun proposeAdoption(unmatched: List<ErrorRecord>, tree: KaodianTree): Proposal? {
        if (unmatched.size < ADOPT_THRESHOLD) return null
        val query = Embedder.embed(unmatched.joinToString("") { it.eye.orEmpty() + it.answer.orEmpty() })
        val hits = tree.topK(query, 5)
        val user = buildString {
            appendLine("下面这些空当前没有匹配到考点：")
            unmatched.take(30).forEach { appendLine("- 题眼：${it.eye}｜答案：${it.answer ?: "缺"}") }
            appendLine()
            appendLine("最接近的现有节点：")
            hits.forEach { appendLine("- ${it.node.path}｜${it.node.formRule}") }
            appendLine()
            appendLine("要么归入其中一个，要么新建一个末端节点（深度 3-4 层，顶层只能是 词法/句法/语法）。")
            appendLine("""输出 JSON：{"action":"adopt"|"create","path":"...","form_rule":"新建时必填"}""")
        }
        val o = client.obj(client.complete(SYSTEM, user, maxTokens = 800))
        val path = o["path"]?.jsonPrimitive?.content?.trim() ?: return null
        return if (o["action"]?.jsonPrimitive?.content == "create") {
            val rule = o["form_rule"]?.jsonPrimitive?.content?.trim().orEmpty()
            if (rule.isBlank() || path.split("/").size !in 3..4) null
            else Proposal.CreateNode(TreeNode(path, rule, Embedder.embed(Embedder.nodeText(path, rule)), tree.version), unmatched.size)
        } else {
            if (tree.contains(path)) Proposal.Adopt(path, unmatched.size) else null
        }
    }

    // ---------- 应用 ----------

    /** 结构变更后版本必然递增，调用方随即用 RecordRepository.remap 把旧记录搬过去。 */
    fun apply(nodes: List<TreeNode>, proposal: Proposal, version: String): List<TreeNode> = when (proposal) {
        is Proposal.Merge -> nodes.map {
            if (it.path == proposal.from) it.copy(mergedInto = proposal.into) else it
        }
        is Proposal.RollUp -> {
            val parentExists = nodes.any { it.path == proposal.into }
            val withParent = if (parentExists) nodes else {
                val child = nodes.first { it.path == proposal.from }
                nodes + TreeNode(proposal.into, child.formRule, Embedder.embed(Embedder.nodeText(proposal.into, child.formRule)), version)
            }
            withParent.map { if (it.path == proposal.from) it.copy(mergedInto = proposal.into) else it }
        }
        is Proposal.Split ->
            nodes.map { if (it.path == proposal.path) it.copy(mergedInto = proposal.children.first().path) else it } +
                proposal.children.map { it.copy(createdIn = version) }
        is Proposal.CreateNode -> nodes + proposal.node.copy(createdIn = version)
        is Proposal.Adopt -> nodes
    }

    companion object {
        const val ADOPT_THRESHOLD = 10
        private val SYSTEM = """
            你在维护一棵专升本英语考点树。顶层三分固定：词法 / 句法 / 语法。
            末端深度 3-4 层，必须细到能对应一个可执行动作，且必须带 form_rule（一句话，可直接背）。
            只输出 JSON，不要解释。
        """.trimIndent()
    }
}
