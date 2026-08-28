import com.ecs.agent.AgentClient
import com.ecs.agent.TreeMaintainer
import com.ecs.agent.Verifier
import com.ecs.core.export.CsvImporter
import com.ecs.core.export.Exporter
import com.ecs.core.model.*
import com.ecs.core.model.BuiltInEndpoints
import com.ecs.core.model.ModelCatalog
import com.ecs.core.tree.Embedder
import com.ecs.core.tree.KaodianTree
import com.ecs.core.tree.TreeNode
import kotlin.test.*

private val client = AgentClient {
    AgentClient.Config(BuiltInEndpoints.ALL.first(), ModelCatalog.DEFAULT_TEXT)
}

private fun node(path: String, rule: String) =
    TreeNode(path, rule, Embedder.embed(Embedder.nodeText(path, rule)))

private fun r(
    id: String, kaodian: String?, paper: String = "卷A", no: Int = 1,
    eye: String? = "空前有 the，空后接介词短语", answer: String? = "development",
) = ErrorRecord(
    id = id,
    src = Src(paper, Section.GF, no, 1, "b01", 10),
    answer = answer,
    confidence = Confidence.WRONG,
    createdAt = 1_700_000_000_000,
    eye = eye,
    kaodian = kaodian,
    formRule = "名词，动词加 -tion 后缀",
    difficulty = Difficulty.MEDIUM,
    treeVersion = "v1",
    status = RecordStatus.ACTIVE,
)

class JsonExtractionTest {
    @Test fun `plain object`() {
        assertEquals("""{"a":1}""", client.extractJson("""{"a":1}"""))
    }

    @Test fun `fenced block`() {
        assertEquals("""{"a":1}""", client.extractJson("```json\n{\"a\":1}\n```"))
    }

    @Test fun `prose before and after`() {
        assertEquals("""{"choice":2}""", client.extractJson("好的，结果如下：{\"choice\":2}。以上。"))
    }

    @Test fun `braces inside strings do not terminate early`() {
        val raw = """{"eye":"空前有 } 符号","choice":1}"""
        assertEquals(raw, client.extractJson("说明：$raw"))
    }

    @Test fun `escaped quote inside string`() {
        val raw = """{"eye":"含 \" 引号","choice":1}"""
        assertEquals(raw, client.extractJson(raw))
    }

    @Test fun `array is extracted too`() {
        assertEquals("""[{"path":"a"}]""", client.extractJson("""结果：[{"path":"a"}]"""))
    }

    @Test fun `no json raises`() {
        assertFailsWith<AgentClient.AgentException> { client.extractJson("模型拒绝回答") }
    }

    @Test fun `unclosed json raises`() {
        assertFailsWith<AgentClient.AgentException> { client.extractJson("""{"a":1""") }
    }
}

class EmbedderTest {
    @Test fun `identical text is maximally similar`() {
        val a = Embedder.embed("词法/名词/后缀转换/-tion")
        assertEquals(1.0, KaodianTree.cosine(a, a), 1e-6)
    }

    @Test fun `vectors are unit length and fixed dimension`() {
        val v = Embedder.embed("定语从句 关系代词 which")
        assertEquals(Embedder.DIM, v.size)
        val norm = Math.sqrt(v.sumOf { (it * it).toDouble() })
        assertEquals(1.0, norm, 1e-4)
    }

    @Test fun `empty text yields a zero vector rather than crashing`() {
        val v = Embedder.embed("")
        assertEquals(Embedder.DIM, v.size)
        assertTrue(v.all { it == 0f })
        assertEquals(0.0, KaodianTree.cosine(v, Embedder.embed("x")))
    }

    @Test fun `retrieval puts the on-topic node ahead of an unrelated one`() {
        val tree = KaodianTree("v1", listOf(
            node("词法/名词/后缀转换/-tion", "名词，动词加 -tion 后缀"),
            node("句法/从句结构/定语从句/关系代词", "关系代词 which/that"),
            node("语法/冠词/零冠词/抽象名词", "抽象名词泛指时不加冠词"),
        ))
        val hits = tree.topK(Embedder.embed("名词 后缀转换 -tion development"), 3)
        assertEquals("词法/名词/后缀转换/-tion", hits.first().node.path)
    }
}

class MaintainerLocalRulesTest {
    private val maintainer = TreeMaintainer(client)
    private val tree = KaodianTree("v1", listOf(
        node("词法/名词/后缀转换/-tion", "名词，动词加 -tion 后缀"),
        node("词法/名词/后缀转换/-ment", "名词，动词加 -ment 后缀"),
    ))

    @Test fun `roll up needs both a thin node and enough total data`() {
        val thin = (1..101).map { r("gf_%03d_1".format(it), "词法/名词/后缀转换/-tion", no = it) } +
            listOf(r("gf_900_1", "词法/名词/后缀转换/-ment", no = 900))
        val out = maintainer.rollUpCandidates(thin, tree)
        assertEquals(listOf("词法/名词/后缀转换/-ment"), out.map { it.from })
        assertEquals("词法/名词/后缀转换", out.single().into)
        // 总量不足 100 时不动
        assertTrue(maintainer.rollUpCandidates(thin.take(20), tree).isEmpty())
    }

    @Test fun `split fires only above 15 percent`() {
        val rs = (1..10).map { r("gf_%03d_1".format(it), if (it <= 2) "词法/名词/后缀转换/-tion" else "x/y/z$it", no = it) }
        assertEquals(listOf("词法/名词/后缀转换/-tion"), maintainer.splitCandidates(rs))
        assertTrue(maintainer.splitCandidates(emptyList()).isEmpty())
    }

    @Test fun `similar pairs are found by embedding proximity`() {
        val close = KaodianTree("v1", listOf(
            node("词法/名词/后缀转换/-tion", "名词，动词加 -tion 后缀"),
            node("词法/名词/后缀转换/-tion后缀", "名词，动词加 -tion 后缀"),
            node("句法/倒装/部分倒装/否定词前置", "否定词提前，助动词提到主语前"),
        ))
        val pairs = maintainer.similarPairs(close, threshold = 0.8)
        assertEquals(1, pairs.size)
        assertTrue(pairs.single().toList().all { it.startsWith("词法/名词/后缀转换") })
    }

    @Test fun `applying a merge redirects the node and keeps history`() {
        val merged = maintainer.apply(
            tree.nodes,
            TreeMaintainer.Proposal.Merge("词法/名词/后缀转换/-ment", "词法/名词/后缀转换/-tion", "同一考点"),
            "v2",
        )
        val next = KaodianTree("v2", merged)
        assertEquals("词法/名词/后缀转换/-tion", next.resolve("词法/名词/后缀转换/-ment"))
        assertEquals(1, next.liveNodes.size)
    }

    @Test fun `applying a roll up creates the parent when it is missing`() {
        val out = maintainer.apply(
            tree.nodes,
            TreeMaintainer.Proposal.RollUp("词法/名词/后缀转换/-ment", "词法/名词/后缀转换", 2),
            "v2",
        )
        val next = KaodianTree("v2", out)
        assertTrue(next.contains("词法/名词/后缀转换"))
        assertEquals("词法/名词/后缀转换", next.resolve("词法/名词/后缀转换/-ment"))
    }

    @Test fun `applying a split retires the parent into its first child`() {
        val children = listOf(
            node("词法/名词/后缀转换/-tion/动词直接加", "动词直接加 -tion"),
            node("词法/名词/后缀转换/-tion/去e加", "动词去 e 加 -tion"),
        )
        val out = maintainer.apply(
            tree.nodes,
            TreeMaintainer.Proposal.Split("词法/名词/后缀转换/-tion", children, "两种拼写变化"),
            "v2",
        )
        val next = KaodianTree("v2", out)
        assertFalse(next.contains("词法/名词/后缀转换/-tion"))
        assertEquals("词法/名词/后缀转换/-tion/动词直接加", next.resolve("词法/名词/后缀转换/-tion"))
        assertEquals(2, next.liveNodes.count { it.path.startsWith("词法/名词/后缀转换/-tion/") })
    }
}

class VerifierSampleTest {
    private val verifier = Verifier(client)

    @Test fun `five percent with a floor of one`() {
        val hundred = (1..100).map { r("gf_%03d_1".format(it), "词法/名词/后缀转换/-tion", no = it) }
        assertEquals(5, verifier.sample(hundred).size)
        assertEquals(1, verifier.sample(hundred.take(3)).size)
        assertTrue(verifier.sample(emptyList()).isEmpty())
    }

    @Test fun `unannotated records are never sampled`() {
        val pool = (1..10).map { r("gf_%03d_1".format(it), null, no = it) }
        assertTrue(verifier.sample(pool).isEmpty())
    }
}

class CsvRoundTripTest {
    @Test fun `export then import preserves the fields that matter`() {
        val original = r("gf_034_1", "词法/名词/后缀转换/-tion").copy(
            note = "含,逗号和\"引号",
            secondary = listOf("词法/动词/时态形式/一般过去时"),
            formContext = "单数，谓语 has 限定",
        )
        val back = CsvImporter.parse(Exporter.csv(listOf(original))).single()
        assertEquals(original.id, back.id)
        assertEquals(original.src, back.src)
        assertEquals(original.kaodian, back.kaodian)
        assertEquals(original.eye, back.eye)
        assertEquals(original.formContext, back.formContext)
        assertEquals(original.note, back.note)
        assertEquals(original.secondary, back.secondary)
        assertEquals(original.confidence, back.confidence)
        assertEquals(original.status, back.status)
        assertEquals(original.createdAt, back.createdAt)
    }

    @Test fun `rows missing optional columns still load`() {
        val csv = "id,paper,section,no,slot\ngf_001_1,卷A,语法填空,1,1\n"
        val back = CsvImporter.parse(csv, now = 42L).single()
        assertEquals("b01", back.src.batch)
        assertEquals(42L, back.createdAt)
        assertEquals(RecordStatus.INCOMPLETE, back.status)
        assertNull(back.answer)
    }

    @Test fun `rows missing an identifying column are skipped, not crashed on`() {
        val csv = "id,paper,section,no,slot\n,卷A,语法填空,1,1\ngf_002_1,卷A,语法填空,2,1\n"
        assertEquals(listOf("gf_002_1"), CsvImporter.parse(csv).map { it.id })
    }
}
