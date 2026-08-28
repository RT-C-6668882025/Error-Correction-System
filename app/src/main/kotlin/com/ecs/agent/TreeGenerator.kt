package com.ecs.agent

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
class TreeGenerator(private val client: AgentClient) {

    @Serializable
    private data class RawNode(val path: String = "", val form_rule: String = "")

    private val system = """
        你在为专升本英语备考构建考点树，服务对象只有两种题型：语法填空（20分）与完成句子（18分）。
        两者共享同一件事——给定语境，填出正确形态。

        顶层三分固定，不可增删：
        词法/   词该长什么样：词性、词形变化、时态形式、语态形式、非谓语形式、比较级、单复数、词根词缀、固定搭配、词义辨析
        句法/   词怎么排怎么连：句子成分、语序、倒装、从句结构、并列从属、省略、强调、主谓一致、平行结构
        语法/   这样表达什么意思：语气虚拟、情态、时态语义、冠词、代词指代、连词逻辑、介词逻辑、语篇衔接

        规则：
        1. 每条路径深度 3 到 4 层，末端必须细到能对应一个可执行动作。
           合格：词法/名词/后缀转换/-tion   不合格：词法/名词/后缀转换（范围太大）
        2. 每个末端节点必须给出 form_rule：这一类空该填成什么形态，一句话，可直接背。
           form_rule 描述规则本身，不描述某一句的语境。
           合格：名词，动词加 -tion 后缀
           不合格：根据句子结构判断词形
        3. 只收「答案是一个形态」的考点。阅读理解式的理解题不要。
        4. 总数 120 到 180 个末端节点，三类都要覆盖，不要堆在词法。
        5. 路径不得重复，同一父节点下的末端要互斥。
    """.trimIndent()

    private val user = """
        输出 JSON 数组，除此之外不要有任何文字：
        [{"path":"词法/名词/后缀转换/-tion","form_rule":"名词，动词加 -tion 后缀"}]
    """.trimIndent()

    suspend fun generate(): KaodianTree {
        val raw = client.complete(system = system, user = user, maxTokens = 16000, temperature = 0.2)
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
