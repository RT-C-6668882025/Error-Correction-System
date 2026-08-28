package com.ecs.agent

import com.ecs.core.prompt.PromptSlot
import com.ecs.core.tree.Embedder
import com.ecs.core.tree.KaodianTree
import com.ecs.core.tree.Skeleton
import com.ecs.core.tree.TreeNode
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.sync.Semaphore
import kotlinx.coroutines.sync.withPermit
import kotlinx.serialization.Serializable
import kotlinx.serialization.builtins.ListSerializer

/**
 * F7.1 树生成。前两层由 [Skeleton] 写死，模型只填第三、四层。
 *
 * 一个分支一次调用：输出小、失败只损失这一个分支，其余照样落库。
 * 原来是一次 16000 token 生成整棵树，中途截断等于全部白跑，
 * 差几个节点还会把已经生成好的全部丢弃。
 */
open class TreeGenerator(
    private val client: AgentClient,
    private val prompts: PromptProvider = PromptProvider.DEFAULT,
) {

    @Serializable
    private data class RawNode(val path: String = "", val form_rule: String = "")

    /** 部分成功也是成功：[failed] 里的分支可以单独重试，其余照常落库。 */
    data class Outcome(
        val tree: KaodianTree,
        val failed: List<Failure>,
    ) {
        data class Failure(val branch: Skeleton.Branch, val reason: String)
    }

    /**
     * 生成一个分支下的末端节点。前两层是给定的，模型改写或跑题的结果一律丢弃——
     * 否则一个分支的跑题会污染另一个分支的分组。
     */
    open suspend fun generateBranch(branch: Skeleton.Branch, version: String = "v1"): List<TreeNode> {
        val user = """
            大类：${branch.root}
            中类：${branch.mid}
            这一支管的范围：${branch.scope}

            前两层已经给定，必须原样作为路径开头：${branch.path}/
            在它下面细分出 6 到 12 个末端考点，每个细到能对应一个可执行动作。

            输出 JSON 数组，除此之外不要有任何文字：
            [{"path":"${branch.path}/后缀转换/-tion","form_rule":"名词，动词加 -tion 后缀"}]
        """.trimIndent()

        val raw = client.complete(
            system = prompts.text(PromptSlot.TREE_GENERATE),
            user = user,
            maxTokens = 2000,
            temperature = 0.2,
        )
        return parseBranch(raw, branch, version)
    }

    /** 解析与过滤分开做：可复现，也可以脱离网络单独测。 */
    fun parseBranch(raw: String, branch: Skeleton.Branch, version: String = "v1"): List<TreeNode> =
        client.decode(raw, ListSerializer(RawNode.serializer()))
            .map { it.path.trim().trim('/') to it.form_rule.trim() }
            .filter { (path, rule) ->
                rule.isNotEmpty() &&
                    Skeleton.isValidPath(path) &&
                    // 跑到别的分支去了：丢弃，不要污染那一支的分组
                    Skeleton.branchOf(path) == branch
            }
            .distinctBy { it.first }
            .map { (path, rule) ->
                TreeNode(
                    path = path,
                    formRule = rule,
                    embedding = Embedder.embed(Embedder.nodeText(path, rule)),
                    createdIn = version,
                )
            }

    /**
     * 跑完整棵树。[onProgress] 在每个分支结束时回调一次，用来显示「…14/19」。
     * [only] 非空时只跑这几个分支，用于重试失败的那几支。
     */
    suspend fun generate(
        version: String = "v1",
        only: List<Skeleton.Branch> = Skeleton.BRANCHES,
        onProgress: (done: Int, total: Int, label: String) -> Unit = { _, _, _ -> },
    ): Outcome = coroutineScope {
        val gate = Semaphore(CONCURRENCY)
        var done = 0
        val perBranch = only.map { branch ->
            async {
                val outcome = gate.withPermit {
                    // 不用 runCatching：它连 CancellationException 一起吞，取消就不再是取消
                    try {
                        generateBranch(branch, version) to null
                    } catch (e: CancellationException) {
                        throw e
                    } catch (e: Exception) {
                        emptyList<TreeNode>() to (e.message ?: e.toString())
                    }
                }
                // 计数只在这一处递增，各分支串行地走过这段
                synchronized(this@TreeGenerator) {
                    done += 1
                    onProgress(done, only.size, branch.path)
                }
                Triple(branch, outcome.first, outcome.second)
            }
        }.awaitAll()

        val nodes = perBranch.flatMap { it.second }
        val failed = perBranch.mapNotNull { (branch, produced, error) ->
            when {
                error != null -> Outcome.Failure(branch, error)
                // 有回应但一条都不合格，和抛错一样要说出来，否则这一支会静静地缺着
                produced.isEmpty() -> Outcome.Failure(branch, "没有产出合格的末端节点")
                else -> null
            }
        }
        if (nodes.isEmpty()) {
            throw AgentClient.AgentException(
                "${only.size} 个分支一个都没生成出来。第一条原因：${failed.firstOrNull()?.reason ?: "未知"}"
            )
        }
        Outcome(KaodianTree(version = version, nodes = nodes), failed)
    }

    companion object {
        /** 并发上限。再高容易撞上厂商的每分钟请求数限制。 */
        const val CONCURRENCY = 4
    }
}
