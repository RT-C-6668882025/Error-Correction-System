import com.ecs.agent.AgentClient
import com.ecs.agent.PromptProvider
import com.ecs.agent.TreeGenerator
import com.ecs.core.model.ApiEndpoint
import com.ecs.core.model.Protocol
import com.ecs.core.tree.Skeleton
import com.ecs.core.tree.TopLevel
import com.ecs.core.tree.truncate
import kotlinx.coroutines.runBlocking
import kotlin.test.*

class SkeletonTest {

    @Test fun `three roots, nineteen branches, matching the agreed taxonomy`() {
        assertEquals(3, TopLevel.all.size)
        assertEquals(19, Skeleton.BRANCHES.size)
        assertEquals(9, Skeleton.branchesOf(TopLevel.LEXICAL).size)
        assertEquals(3, Skeleton.branchesOf(TopLevel.SYNTAX).size)
        assertEquals(7, Skeleton.branchesOf(TopLevel.GRAMMAR).size)
    }

    @Test fun `every branch sits under a real root and carries a scope`() {
        Skeleton.BRANCHES.forEach {
            assertTrue(it.root in TopLevel.all, "${it.path} 的大类不在三类里")
            assertTrue(it.mid.isNotBlank())
            assertTrue(it.scope.isNotBlank(), "${it.path} 缺少范围约束")
        }
    }

    @Test fun `branch paths are unique`() {
        val paths = Skeleton.BRANCHES.map { it.path }
        assertEquals(paths.size, paths.distinct().size)
    }

    @Test fun `subject-verb agreement lives under grammar, not syntax`() {
        // 用户给的分法：主谓一致属于语法，不是句法
        assertNotNull(Skeleton.branchOf("语法/主谓一致/就近原则/either or"))
        assertNull(Skeleton.branchOf("句法/主谓一致/就近原则"))
    }

    @Test fun `valid paths are three or four deep under a real branch`() {
        assertTrue(Skeleton.isValidPath("语法/时态/完成时/现在完成时"))
        assertTrue(Skeleton.isValidPath("词法/名词/后缀转换/-tion"))
        assertTrue(Skeleton.isValidPath("句法/句子成分/定语"))
    }

    @Test fun `a mid-level path alone is not a valid leaf`() {
        // 深度 2 就是中类本身，范围太大，对应不到一个可执行动作
        assertFalse(Skeleton.isValidPath("语法/时态"))
        assertFalse(Skeleton.isValidPath("语法"))
    }

    @Test fun `invented mids and roots are rejected`() {
        assertFalse(Skeleton.isValidPath("语法/语篇衔接/连接词"))
        assertFalse(Skeleton.isValidPath("修辞/比喻/明喻"))
        assertFalse(Skeleton.isValidPath("词法/名词/后缀/转换/-tion"))  // 五层，太深
        assertFalse(Skeleton.isValidPath("词法//后缀转换"))
    }

    @Test fun `truncating a leaf lands back on the fixed skeleton`() {
        // 自下而上合并同类项：depth 2 与 depth 1 必须是固定词表，否则上层分组会裂开
        val leaf = "语法/非谓语动词/动名词/介词后接动名词"
        assertEquals("语法/非谓语动词", truncate(leaf, 2))
        assertEquals("语法", truncate(leaf, 1))
        assertNotNull(Skeleton.BRANCHES.firstOrNull { it.path == truncate(leaf, 2) })
    }
}

class TreeGeneratorBranchTest {

    private val generator = TreeGenerator(
        AgentClient { AgentClient.Config(ApiEndpoint("x", "x", "https://x.com", Protocol.OPENAI), "m") },
        PromptProvider.DEFAULT,
    )

    private val tense = Skeleton.BRANCHES.first { it.path == "语法/时态" }

    @Test fun `a clean branch response becomes nodes with embeddings`() {
        val raw = """
            [{"path":"语法/时态/完成时/现在完成时","form_rule":"have/has + 过去分词"},
             {"path":"语法/时态/进行时/现在进行时","form_rule":"be + 现在分词"}]
        """.trimIndent()
        val nodes = generator.parseBranch(raw, tense)
        assertEquals(2, nodes.size)
        assertEquals("have/has + 过去分词", nodes[0].formRule)
        assertTrue(nodes.all { it.embedding.isNotEmpty() })
        assertTrue(nodes.all { Skeleton.branchOf(it.path) == tense })
    }

    @Test fun `nodes that wander into another branch are dropped`() {
        // 跑题的节点若留下，会污染那一支的分组——宁可这一支少几个
        val raw = """
            [{"path":"语法/时态/完成时/现在完成时","form_rule":"have/has + 过去分词"},
             {"path":"语法/语态/被动语态/一般现在时被动","form_rule":"am/is/are + 过去分词"},
             {"path":"词法/动词/时态形式/第三人称单数","form_rule":"动词加 -s"}]
        """.trimIndent()
        val nodes = generator.parseBranch(raw, tense)
        assertEquals(1, nodes.size)
        assertEquals("语法/时态/完成时/现在完成时", nodes[0].path)
    }

    @Test fun `nodes missing a form rule or too shallow are dropped`() {
        val raw = """
            [{"path":"语法/时态/完成时/现在完成时","form_rule":""},
             {"path":"语法/时态","form_rule":"看时间状语"},
             {"path":"语法/时态/将来时/一般将来时","form_rule":"will + 动词原形"}]
        """.trimIndent()
        val nodes = generator.parseBranch(raw, tense)
        assertEquals(1, nodes.size)
        assertEquals("语法/时态/将来时/一般将来时", nodes[0].path)
    }

    @Test fun `duplicate paths collapse to one`() {
        val raw = """
            [{"path":"语法/时态/完成时/现在完成时","form_rule":"have/has + 过去分词"},
             {"path":"语法/时态/完成时/现在完成时","form_rule":"重复的一条"}]
        """.trimIndent()
        assertEquals(1, generator.parseBranch(raw, tense).size)
    }

    @Test fun `a fenced response with commentary still parses`() {
        val raw = """
            好的，这是结果：
            ```json
            [{"path":"语法/时态/完成时/过去完成时","form_rule":"had + 过去分词"}]
            ```
        """.trimIndent()
        assertEquals(1, generator.parseBranch(raw, tense).size)
    }

    @Test fun `the created version is stamped onto every node`() {
        val raw = """[{"path":"语法/时态/完成时/现在完成时","form_rule":"have/has + 过去分词"}]"""
        assertEquals("v3", generator.parseBranch(raw, tense, version = "v3").single().createdIn)
    }
}

/** 整棵树：一支失败不该拖垮其余十八支。 */
class TreeGeneratorWholeRunTest {

    private val client =
        AgentClient { AgentClient.Config(ApiEndpoint("x", "x", "https://x.com", Protocol.OPENAI), "m") }

    /** [broken] 里的分支抛错，其余各产出一个末端。 */
    private class Fake(client: AgentClient, val broken: Set<String>) : TreeGenerator(client) {
        override suspend fun generateBranch(branch: Skeleton.Branch, version: String) =
            if (branch.path in broken) throw AgentClient.AgentException("${branch.path} 挂了")
            else parseBranch(
                """[{"path":"${branch.path}/子类/末端","form_rule":"这一支的形态"}]""",
                branch,
                version,
            )
    }

    @Test fun `a partial run still produces a usable tree and names what failed`() = runBlocking {
        val broken = setOf("语法/时态", "词法/冠词")
        val result = Fake(client, broken).generate()

        assertEquals(Skeleton.BRANCHES.size - broken.size, result.tree.nodes.size)
        assertEquals(broken, result.failed.map { it.branch.path }.toSet())
        assertTrue(result.failed.all { it.reason.contains("挂了") })
        // 剩下的十七支照样能用来标注
        assertTrue(result.tree.liveNodes.all { it.embedding.isNotEmpty() })
    }

    @Test fun `an empty branch counts as a failure even without an exception`() = runBlocking {
        // 模型返回了东西但一条都不合格，和抛错一样要说出来
        val silent = object : TreeGenerator(client) {
            override suspend fun generateBranch(branch: Skeleton.Branch, version: String) =
                if (branch.path == "句法/句子种类") emptyList()
                else parseBranch(
                    """[{"path":"${branch.path}/子类/末端","form_rule":"形态"}]""", branch, version,
                )
        }
        val result = silent.generate()
        assertEquals(listOf("句法/句子种类"), result.failed.map { it.branch.path })
        assertTrue(result.failed.single().reason.contains("没有产出"))
    }

    @Test fun `when every branch fails the error names the first reason`() = runBlocking {
        val allBroken = Fake(client, Skeleton.BRANCHES.map { it.path }.toSet())
        val e = assertFailsWith<AgentClient.AgentException> { allBroken.generate() }
        assertTrue(e.message!!.contains("挂了"))
    }

    @Test fun `progress is reported once per branch`() = runBlocking {
        val seen = mutableListOf<Int>()
        Fake(client, emptySet()).generate { done, total, _ ->
            seen += done
            assertEquals(Skeleton.BRANCHES.size, total)
        }
        assertEquals(Skeleton.BRANCHES.size, seen.size)
        assertEquals(Skeleton.BRANCHES.size, seen.max())
    }

    @Test fun `only-mode runs just the branches asked for`() = runBlocking {
        val two = Skeleton.BRANCHES.take(2)
        val result = Fake(client, emptySet()).generate(only = two)
        assertEquals(2, result.tree.nodes.size)
        assertEquals(two.map { it.path }.toSet(), result.tree.nodes.map { truncate(it.path, 2) }.toSet())
    }
}
