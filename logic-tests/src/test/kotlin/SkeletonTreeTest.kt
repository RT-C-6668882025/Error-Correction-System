import com.ecs.agent.AgentClient
import com.ecs.agent.Analyzer
import com.ecs.agent.DirectionBuilder
import com.ecs.agent.PromptProvider
import com.ecs.core.direction.Direction
import com.ecs.core.direction.DirectionNode
import com.ecs.core.model.ApiEndpoint
import com.ecs.core.model.Protocol
import com.ecs.core.tree.Skeleton
import com.ecs.core.tree.TopLevel
import kotlinx.coroutines.runBlocking
import kotlin.test.*

private val client =
    AgentClient { AgentClient.Config(ApiEndpoint("x", "x", "https://x.com", Protocol.OPENAI), "m") }

/** 按脚本依次返回预设回复；用完就一直返回最后一条。记下每次的 system 供断言。 */
private class Scripted(private vararg val replies: String) :
    AgentClient({ AgentClient.Config(ApiEndpoint("x", "x", "https://x.com", Protocol.OPENAI), "m") }) {

    val systems = mutableListOf<String>()
    val calls: Int get() = systems.size

    override suspend fun complete(
        system: String,
        user: String,
        images: List<Image>,
        maxTokens: Int,
        temperature: Double,
        role: Role,
    ): String {
        systems += system
        return replies[minOf(systems.size - 1, replies.size - 1)]
    }
}

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
        assertEquals("语法/主谓一致", Skeleton.branchOf("语法/主谓一致")?.path)
        assertTrue(Skeleton.branchesOf(TopLevel.GRAMMAR).any { it.mid == "主谓一致" })
        assertTrue(Skeleton.branchesOf(TopLevel.SYNTAX).none { it.mid == "主谓一致" })
        // 模型把大类写成句法时按中类救回来，落点仍是语法那一支
        assertEquals("语法/主谓一致", Skeleton.branchOf("句法/主谓一致")?.path)
    }

    @Test fun `branchOf matches on the first two segments`() {
        // 老库里的四层考点路径截到板块那一层照样认得
        assertEquals("词法/名词", Skeleton.branchOf("词法/名词/后缀转换/-tion")?.path)
        assertEquals("语法/时态", Skeleton.branchOf("语法/时态")?.path)
    }

    @Test fun `invented mids and roots are rejected`() {
        assertNull(Skeleton.branchOf("语法/语篇衔接"))
        assertNull(Skeleton.branchOf("修辞/比喻/明喻"))
        assertNull(Skeleton.branchOf(""))
        assertNull(Skeleton.branchOf(null))
        assertNull(Skeleton.branchOf("   /   "))
        assertFalse(Skeleton.isBranch("unmatched"))
    }

    @Test fun `spacing and full-width slashes do not cost a match`() {
        // 模型这几种写法其实都答对了，判成未归类是我们的问题不是它的
        listOf(
            "词法 / 名词",
            "　词法/名词　",
            "词法／名词",
            "/词法/名词/",
            "词法/ 名词 /后缀转换",
        ).forEach {
            assertEquals("词法/名词", Skeleton.branchOf(it)?.path, "「$it」没归到词法/名词")
        }
    }

    @Test fun `a bare mid resolves because the nineteen mids are unique`() {
        assertEquals("词法/名词", Skeleton.branchOf("名词")?.path)
        assertEquals("语法/非谓语动词", Skeleton.branchOf("非谓语动词")?.path)
        // 大类写歪了但中类对，也能救回来
        assertEquals("语法/时态", Skeleton.branchOf("句法/时态")?.path)
    }

    @Test fun `the nineteen mids really are unique - the bare-mid rescue depends on it`() {
        val mids = Skeleton.BRANCHES.map { it.mid }
        assertEquals(mids.size, mids.distinct().size)
    }

    @Test fun `a root on its own is not enough to pick a branch`() {
        // 「语法」下面有七支，猜哪一支都是错的
        assertNull(Skeleton.branchOf("语法"))
        assertNull(Skeleton.branchOf("词法"))
    }

    @Test fun `the listing shown to the model covers every branch with its scope`() {
        val listing = Skeleton.listing()
        Skeleton.BRANCHES.forEach {
            assertTrue(listing.contains(it.path), "清单里缺 ${it.path}")
            assertTrue(listing.contains(it.scope), "清单里缺 ${it.path} 的范围")
        }
    }
}

class AnalyzerParseTest {

    private val analyzer = Analyzer(client, PromptProvider.DEFAULT)

    @Test fun `a clean response becomes an analysis`() {
        val out = analyzer.parse(
            """{"branch":"词法/名词","form":"名词，动词加 -tion 后缀",
                "basis":"空前有 the，空后接 of 短语","context":"单数，谓语 has 限定"}"""
        )
        assertEquals("词法/名词", out.branch)
        assertEquals("名词，动词加 -tion 后缀", out.formShape)
        assertEquals("空前有 the，空后接 of 短语", out.basis)
        assertEquals("单数，谓语 has 限定", out.formContext)
        assertFalse(out.unmatched)
    }

    @Test fun `a branch off the skeleton is unmatched, not forced into the nearest one`() {
        // 硬塞会把那一支的汇总带偏，宁可留着让人处理
        val out = analyzer.parse("""{"branch":"词法/瞎编","form":"名词"}""")
        assertNull(out.branch)
        assertTrue(out.unmatched)
        assertEquals("名词", out.formShape)
    }

    @Test fun `the explicit unmatched marker is honoured`() {
        val out = analyzer.parse("""{"branch":"unmatched","form":"读懂选项才能选"}""")
        assertNull(out.branch)
        assertTrue(out.unmatched)
    }

    @Test fun `a deeper path is accepted and folded back to its branch`() {
        val out = analyzer.parse("""{"branch":"词法/名词/后缀转换/-tion","form":"名词"}""")
        assertEquals("词法/名词", out.branch)
        assertFalse(out.unmatched)
    }

    @Test fun `a response with no answer form is refused outright`() {
        // 没有答案形式，这条分析就没有任何用处
        assertFailsWith<Analyzer.EmptyResult> {
            analyzer.parse("""{"branch":"词法/名词","basis":"空前有 the"}""")
        }
    }

    @Test fun `an empty context becomes null rather than an empty string`() {
        val out = analyzer.parse("""{"branch":"词法/名词","form":"名词","context":"  "}""")
        assertNull(out.formContext)
    }

    @Test fun `an unmatched branch is retried, and the second answer sticks`() = runBlocking {
        // 归不进板块的题在复习页无处可去，等于白分析——所以必须再要一次
        val client = Scripted(
            """{"branch":"名词用法","form":"名词，动词加 -tion 后缀","basis":"空前有 the，空后接 of 短语"}""",
            """{"branch":"词法/名词","form":"名词，动词加 -tion 后缀","basis":"空前有 the，空后接 of 短语"}""",
        )
        val out = Analyzer(client).analyze(Analyzer.Input("The ___ of AI", "develop", "development"))
        assertEquals("词法/名词", out.branch)
        assertFalse(out.unmatched)
        assertEquals(2, client.calls)
        // 第二次必须把上次错在哪说清楚，否则它多半原样再给一遍
        assertTrue(client.systems[1].contains("名词用法"))
        assertTrue(client.systems[1].contains("不在板块清单里"))
    }

    @Test fun `two bad answers in a row end as unmatched, not as a crash`() = runBlocking {
        val client = Scripted("""{"branch":"瞎编","form":"名词"}""")
        val out = Analyzer(client).analyze(Analyzer.Input("The ___ of AI", null, null))
        assertTrue(out.unmatched)
        assertNull(out.branch)
        assertEquals("名词", out.formShape)
        assertEquals(2, client.calls)
    }

    @Test fun `a good first answer is not retried`() = runBlocking {
        val client = Scripted(
            """{"branch":"语法/时态","form":"一般过去时","basis":"句末有 last year 这个时间状语"}"""
        )
        val out = Analyzer(client).analyze(Analyzer.Input("He ___ last year", "go", "went"))
        assertEquals("语法/时态", out.branch)
        assertEquals(1, client.calls)
    }

    @Test fun `apply touches the analysis half only`() {
        val before = rec("q_001_1", branch = null, formShape = null, basis = null)
        val after = analyzer.apply(
            before,
            Analyzer.Output("语法/时态", "一般过去时", "句末有 last year", null, unmatched = false),
        )
        assertEquals("语法/时态", after.branch)
        assertEquals("一般过去时", after.formShape)
        // 原始数据一个字都没动
        assertEquals(before.stem, after.stem)
        assertEquals(before.answer, after.answer)
        assertEquals(before.src, after.src)
        assertEquals(before.confidence, after.confidence)
        assertEquals(before.createdAt, after.createdAt)
    }
}

class DirectionBuilderTest {

    private val builder = DirectionBuilder(client, PromptProvider.DEFAULT)

    @Test fun `nested nodes parse into a tree`() {
        val nodes = builder.parseNodes(
            """[{"name":"普通n","children":[
                 {"name":"可数n","children":[{"name":"规则复数","rule":"词尾加 -s"}]}]}]"""
        )
        assertEquals(1, nodes.size)
        assertEquals("普通n", nodes.single().name)
        assertEquals("规则复数", nodes.single().children.single().children.single().name)
    }

    @Test fun `parsing cleans out the junk the model sometimes emits`() {
        val nodes = builder.parseNodes(
            """[{"name":"  "},{"name":"空壳","children":[]},{"name":"好的","rule":"词尾加 -s"}]"""
        )
        assertEquals(listOf("好的"), nodes.map { it.name })
    }

    @Test fun `a fenced response with commentary still parses`() {
        val nodes = builder.parseNodes(
            "好的：\n```json\n[{\"name\":\"可数n\",\"rule\":\"词尾加 -s\"}]\n```"
        )
        assertEquals(1, nodes.size)
    }

    @Test fun `the major stage is fed the minor outputs and nothing else`() {
        val minors = listOf(
            Direction(
                "词法/名词",
                listOf(DirectionNode("可数n", children = listOf(DirectionNode("规则复数", rule = "词尾加 -s")))),
                fromCount = 4, generatedAt = 0,
            ),
            Direction("语法/时态", listOf(DirectionNode("现在完成时", rule = "have + 过去分词")), 3, 0),
        )
        val payload = builder.majorInput(minors)

        // 上一阶段的输出确实在里面
        assertTrue(payload.contains("词法/名词"))
        assertTrue(payload.contains("规则复数"))
        assertTrue(payload.contains("have + 过去分词"))
        // 原题的任何东西都不该出现在这一级
        listOf("q_001_1", "The ___ of AI", "development", "空前有 the", "2023真题卷").forEach {
            assertFalse(payload.contains(it), "大方向的输入里混进了原题字段：$it")
        }
    }

    @Test fun `the major payload groups the minors under their roots`() {
        val minors = listOf(
            Direction("词法/名词", listOf(DirectionNode("可数n", rule = "加 -s")), 1, 0),
            Direction("语法/时态", listOf(DirectionNode("完成时", rule = "have + 过去分词")), 1, 0),
        )
        val payload = builder.majorInput(minors)
        assertTrue(payload.indexOf("# 词法") < payload.indexOf("# 语法"))
        assertFalse(payload.contains("# 句法"), "没有小方向的大类不该出现")
    }
}
