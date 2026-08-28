package com.ecs.agent

import com.ecs.core.prompt.PromptSlot
import com.ecs.core.tree.Embedder
import com.ecs.core.tree.KaodianTree
import com.ecs.core.tree.TopLevel
import com.ecs.core.tree.TreeNode
import kotlinx.serialization.Serializable
import kotlinx.serialization.builtins.ListSerializer

/**
 * F7.1 树生成（首次启动）。产出 120–180 个末端节点，每个带 path + form_rule，
 * embedding 本地计算。tree_version = v1。
 */
class TreeGenerator(
    private val client: AgentClient,
    private val prompts: PromptProvider = PromptProvider.DEFAULT,
) {

    @Serializable
    private data class RawNode(val path: String = "", val form_rule: String = "")

    private val user = """
        输出 JSON 数组，除此之外不要有任何文字：
        [{"path":"词法/名词/后缀转换/-tion","form_rule":"名词，动词加 -tion 后缀"}]
    """.trimIndent()

    suspend fun generate(): KaodianTree {
        val raw = client.complete(
            system = prompts.text(PromptSlot.TREE_GENERATE),
            user = user,
            maxTokens = 16000,
            temperature = 0.2,
        )
        val nodes = client.decode(raw, ListSerializer(RawNode.serializer()))
        val cleaned = nodes
            .map { it.path.trim().trim('/') to it.form_rule.trim() }
            .filter { (path, rule) ->
                path.isNotEmpty() && rule.isNotEmpty() &&
                    TopLevel.isValidRoot(path) && path.split("/").size in 3..4
            }
            .distinctBy { it.first }
            .map { (path, rule) ->
                TreeNode(
                    path = path,
                    formRule = rule,
                    embedding = Embedder.embed(Embedder.nodeText(path, rule)),
                    createdIn = "v1",
                )
            }
        if (cleaned.size < MIN_NODES) {
            throw AgentClient.AgentException("生成的末端节点只有 ${cleaned.size} 个，少于 $MIN_NODES，未写入")
        }
        return KaodianTree(version = "v1", nodes = cleaned)
    }

    companion object {
        const val MIN_NODES = 60
    }
}
