import com.ecs.core.agg.Aggregator
import com.ecs.core.export.Exporter
import com.ecs.core.model.*
import com.ecs.core.parse.SlotSpec
import com.ecs.core.report.ReportBuilder
import com.ecs.core.rules.StyleGuard
import com.ecs.core.rules.Validation
import com.ecs.core.tree.KaodianTree
import com.ecs.core.tree.TreeNode
import com.ecs.core.tree.truncate
import kotlin.test.*

private val TREE = KaodianTree(
    version = "v1",
    nodes = listOf(
        TreeNode("词法/名词/后缀转换/-tion", "名词，动词加 -tion 后缀", listOf(1f, 0f, 0f)),
        TreeNode("词法/动词/时态形式/一般过去时", "动词过去式", listOf(0f, 1f, 0f)),
        TreeNode("句法/从句结构/定语从句/关系代词", "关系代词 which/that", listOf(0f, 0f, 1f)),
        TreeNode("词法/名词/后缀转换/-ment", "名词，动词加 -ment 后缀", listOf(0.9f, 0.1f, 0f), mergedInto = "词法/名词/后缀转换/-tion"),
    ),
)

private fun rec(
    id: String,
    paper: String = "2023真题卷",
    section: Section = Section.GF,
    no: Int = 1,
    slot: Int = 1,
    conf: Confidence = Confidence.WRONG,
    kaodian: String? = "词法/名词/后缀转换/-tion",
    status: RecordStatus = RecordStatus.ACTIVE,
    total: Int? = 10,
    createdAt: Long = 1_700_000_000_000,
    batch: String = "b01",
    answer: String? = "development",
    eye: String? = "空前有 the，空后接介词短语",
    difficulty: Difficulty? = Difficulty.MEDIUM,
    stem: String? = "The ___ of AI has changed everything.",
) = ErrorRecord(
    id = id,
    src = Src(paper, section, no, slot, batch, total),
    stem = stem,
    answer = answer,
    confidence = conf,
    createdAt = createdAt,
    eye = eye,
    kaodian = kaodian,
    formRule = kaodian?.let { TREE.formRuleOf(it) } ?: "名词，动词加 -tion 后缀",
    formContext = "单数，谓语 has 限定",
    difficulty = difficulty,
    treeVersion = "v1",
    status = status,
)

class SlotSpecTest {
    @Test fun `mixed input parses and marks lucky`() {
        val r = SlotSpec.parse("3, ?5, 12-2", Section.WC)
        assertTrue(r.ok)
        assertEquals(3, r.entries.size)
        assertEquals(listOf(3 to 1, 5 to 1, 12 to 2), r.entries.map { it.no to it.slot })
        assertEquals(Confidence.LUCKY, r.entries[1].confidence)
        assertTrue(r.preview.contains("将生成 3 条记录"))
    }

    @Test fun `full width separators and question mark`() {
        val r = SlotSpec.parse("3，？5、7；15-1", Section.WC)
        assertTrue(r.ok)
        assertEquals(4, r.entries.size)
        assertEquals(Confidence.LUCKY, r.entries[1].confidence)
    }

    @Test fun `garbage is reported without dropping the good ones`() {
        val r = SlotSpec.parse("3, abc, 5", Section.GF)
        assertFalse(r.ok)
        assertEquals(2, r.entries.size)
        assertEquals(listOf("abc"), r.errors)
    }

    @Test fun `duplicate slot keeps the more severe confidence`() {
        val r = SlotSpec.parse("?7, 7", Section.GF)
        assertEquals(1, r.entries.size)
        assertEquals(Confidence.WRONG, r.entries[0].confidence)
    }

    @Test fun `id format`() {
        assertEquals("gf_034_1", SlotSpec.Entry(34, 1, Confidence.WRONG).id(Section.GF))
        assertEquals("wc_012_2", SlotSpec.Entry(12, 2, Confidence.WRONG).id(Section.WC))
        assertTrue(SlotSpec.isValidId("wc_012_2"))
        assertFalse(SlotSpec.isValidId("wc_12_2"))
        assertEquals(Triple(Section.WC, 12, 2), SlotSpec.parseId("wc_012_2"))
    }

    @Test fun `batch label`() = assertEquals("b07", SlotSpec.batchLabel(7))
}

class ValidationTest {
    @Test fun `eye length window`() {
        assertTrue(Validation.checkEye("空前有 the，空后无宾语").isEmpty())
        assertTrue(Validation.checkEye("太短了").isNotEmpty())
        assertTrue(Validation.checkEye("字".repeat(26)).isNotEmpty())
    }

    @Test fun `eye banned words rejected`() {
        val issues = Validation.checkEye("考查名词后缀转换的用法特征")
        assertTrue(issues.any { it.message.contains("考查") })
    }

    @Test fun `form_context capped at 20`() {
        assertTrue(Validation.checkFormContext("单数，谓语 has 限定").isEmpty())
        assertTrue(Validation.checkFormContext("字".repeat(21)).isNotEmpty())
    }

    @Test fun `kaodian must exist in current tree and merged node is gone`() {
        assertTrue(Validation.checkKaodian("词法/名词/后缀转换/-tion", TREE).isEmpty())
        assertTrue(Validation.checkKaodian("词法/名词/后缀转换/-ment", TREE).isNotEmpty())
        assertTrue(Validation.checkKaodian("词法/瞎编", TREE).isNotEmpty())
    }

    @Test fun `status derivation covers pending answer and incomplete`() {
        assertEquals(RecordStatus.ACTIVE, Validation.deriveStatus(rec("gf_001_1"), TREE))
        assertEquals(
            RecordStatus.PENDING_ANSWER,
            Validation.deriveStatus(rec("gf_001_1", answer = null), TREE),
        )
        assertEquals(
            RecordStatus.INCOMPLETE,
            Validation.deriveStatus(rec("gf_001_1", eye = null), TREE),
        )
        assertEquals(
            RecordStatus.INCOMPLETE,
            Validation.deriveStatus(rec("gf_001_1"), TREE, identifyConf = 0.5),
        )
    }

    @Test fun `duplicate uid is blocking but different papers are not`() {
        val a = rec("gf_001_1", paper = "卷A")
        val b = rec("gf_001_1", paper = "卷B")
        assertTrue(Validation.validate(a, TREE, existingUids = setOf(b.uid)).none { it.blocking })
        assertTrue(Validation.validate(a, TREE, existingUids = setOf(a.uid)).any { it.blocking })
    }
}

class AggregatorTest {

    private val tree4 = KaodianTree("v1", listOf(
        TreeNode("词法/名词/后缀转换/-tion", "名词，动词加 -tion 后缀"),
        TreeNode("词法/名词/后缀转换/-ment", "名词，动词加 -ment 后缀"),
        TreeNode("词法/动词/时态形式/一般过去时", "动词过去式"),
        TreeNode("句法/从句结构/定语从句/关系代词", "关系代词 which/that"),
    ))

    private val corpus = listOf(
        rec("gf_001_1", kaodian = "词法/名词/后缀转换/-tion"),
        rec("gf_002_1", no = 2, kaodian = "词法/名词/后缀转换/-ment", answer = "development"),
        rec("gf_003_1", no = 3, kaodian = "词法/动词/时态形式/一般过去时"),
        rec("gf_004_1", no = 4, kaodian = "句法/从句结构/定语从句/关系代词"),
    ).map { it.copy(formRule = tree4.formRuleOf(it.kaodian!!)) }

    @Test fun `unannotated records stay out of review`() {
        val withDraft = corpus + rec("gf_009_1", no = 9, kaodian = null)
        assertEquals(4, Aggregator.countable(withDraft).size)
        assertEquals(4, Aggregator.groups(withDraft, tree4, Aggregator.Level.LEAF).size)
    }

    @Test fun `each level collapses further, bottom up`() {
        fun n(l: Aggregator.Level) = Aggregator.groups(corpus, tree4, l).size
        assertEquals(4, n(Aggregator.Level.LEAF))    // 四个末端
        assertEquals(3, n(Aggregator.Level.THIRD))   // 两个 -tion/-ment 并进后缀转换
        assertEquals(3, n(Aggregator.Level.SECOND))  // 名词 / 动词 / 从句结构
        assertEquals(2, n(Aggregator.Level.ROOT))    // 词法 / 句法
    }

    @Test fun `an upper level takes the lower levels output as its input`() {
        val leaves = Aggregator.groups(corpus, tree4, Aggregator.Level.LEAF)
            .filter { it.kaodian.startsWith("词法/名词/后缀转换/") }
        val parent = Aggregator.groups(corpus, tree4, Aggregator.Level.THIRD)
            .first { it.kaodian == "词法/名词/后缀转换" }

        // 上一层拿到的正是下一层各组交出的形态
        assertEquals(
            leaves.flatMap { it.formRules }.toSet(),
            parent.formRules.toSet(),
        )
        assertEquals(2, parent.formRules.size)
    }

    @Test fun `a group names the finer kaodian it was rolled up from`() {
        val parent = Aggregator.groups(corpus, tree4, Aggregator.Level.ROOT)
            .first { it.kaodian == "词法" }
        assertEquals(3, parent.children.size)
        assertTrue(parent.children.all { it.startsWith("词法/") })
        assertEquals(3, parent.size)
    }

    @Test fun `a leaf group takes its form rule straight from the tree node`() {
        val leaf = Aggregator.groups(corpus, tree4, Aggregator.Level.LEAF)
            .first { it.kaodian == "词法/名词/后缀转换/-tion" }
        assertEquals(listOf("名词，动词加 -tion 后缀"), leaf.formRules)
        assertEquals("-tion", leaf.leafName)
        assertEquals("词法", leaf.root)
    }

    @Test fun `without a tree the form rules come off the records themselves`() {
        val leaf = Aggregator.groups(corpus, null, Aggregator.Level.LEAF)
            .first { it.kaodian == "词法/名词/后缀转换/-tion" }
        assertEquals(listOf("名词，动词加 -tion 后缀"), leaf.formRules)
    }

    @Test fun `rows carry what you need to answer, and nothing else`() {
        val leaf = Aggregator.groups(corpus, tree4, Aggregator.Level.LEAF)
            .first { it.kaodian == "词法/名词/后缀转换/-ment" }
        val row = leaf.rows.single()
        assertEquals("空前有 the，空后接介词短语", row.eye)
        assertEquals("development", row.answer)
        assertEquals("The ___ of AI has changed everything.", row.stem)
    }

    @Test fun `levels map to depths and survive a bogus value`() {
        assertEquals(4, Aggregator.Level.LEAF.depth)
        assertEquals(1, Aggregator.Level.ROOT.depth)
        assertEquals(Aggregator.Level.THIRD, Aggregator.Level.ofDepth(3))
        assertEquals(Aggregator.Level.DEFAULT, Aggregator.Level.ofDepth(99))
    }
}

class TreeTest {
    @Test fun `topK returns at most k and ranks by cosine`() {
        val hits = TREE.topK(listOf(1f, 0f, 0f), 5)
        assertEquals(3, hits.size)
        assertEquals("词法/名词/后缀转换/-tion", hits.first().node.path)
    }

    @Test fun `merged node resolves to its target`() {
        assertEquals("词法/名词/后缀转换/-tion", TREE.resolve("词法/名词/后缀转换/-ment"))
        assertFalse(TREE.contains("词法/名词/后缀转换/-ment"))
        assertNull(TREE.formRuleOf("词法/名词/后缀转换/-ment"))
    }

    @Test fun `merge cycles do not hang`() {
        val cyc = KaodianTree("v2", listOf(
            TreeNode("a", "ra", mergedInto = "b"),
            TreeNode("b", "rb", mergedInto = "a"),
        ))
        assertNull(cyc.resolve("a"))
    }

    @Test fun `version bumps`() {
        assertEquals("v8", KaodianTree.bumpVersion("v7"))
        assertEquals("v1", KaodianTree.bumpVersion("garbage"))
    }
}

class StyleGuardTest {
    @Test fun `banned phrases and question numbers are detected`() {
        val bad = "综上所述，本报告认为 gf_034_1 需要加强学习。"
        val v = StyleGuard.violations(bad)
        assertTrue("综上所述" in v && "本报告" in v && "加强学习" in v && "题号" in v)
    }

    @Test fun `cleaning removes them`() {
        val out = StyleGuard.clean("从数据中可以看出，-tion 后缀是主战场。\n多加练习。")
        assertTrue(StyleGuard.violations(out).isEmpty())
        assertTrue(out.contains("-tion 后缀是主战场"))
    }
}

class ReportTest {
    private val tree = KaodianTree("v1", listOf(
        TreeNode("词法/名词/后缀转换/-tion", "名词，动词加 -tion 后缀"),
    ))
    private val group = Aggregator.groups(
        listOf(rec("gf_001_1").copy(formRule = "名词，动词加 -tion 后缀")),
        tree,
        Aggregator.Level.LEAF,
    ).single()

    @Test fun `the review card leads with the answer form`() {
        val md = ReportBuilder.render(group)
        assertTrue(md.contains("**答案形式**"))
        assertTrue(md.contains("名词，动词加 -tion 后缀"))
        assertTrue(md.contains("| 题眼 | 语境限定 | 答案 |"))
        assertTrue(StyleGuard.violations(md).isEmpty())
    }

    @Test fun `no statistics leak into the card`() {
        val md = ReportBuilder.render(group)
        listOf("加权", "跨卷", "错误率", "难度", "空数").forEach {
            assertFalse(md.contains(it), "还在写统计：$it")
        }
    }

    @Test fun `the facts block never leaks question ids`() {
        assertFalse(ReportBuilder.facts(group).contains("gf_001_1"))
    }
}

class ExporterTest {
    private val tree = KaodianTree("v1", listOf(
        TreeNode("词法/名词/后缀转换/-tion", "名词，动词加 -tion 后缀"),
    ))
    private val corpus = (1..40).map { rec("gf_%03d_1".format(it), no = it) }

    @Test fun `readme stays within 120 lines`() {
        val pkg = Exporter.build(corpus, tree, "20260828")
        assertTrue(pkg.readme.lines().size <= Exporter.README_MAX_LINES,
            "readme is ${pkg.readme.lines().size} lines")
        assertTrue(pkg.readme.contains("词法/名词/后缀转换/-tion"))
        assertTrue(pkg.readme.contains("自下而上的递归聚合"))
    }

    @Test fun `readme documents the stem field now that we store it`() {
        val md = Exporter.readme(corpus, tree)
        assertTrue(md.contains("`stem`"))
        assertFalse(md.contains("不含原题"))
    }

    @Test fun `the stem survives json and csv`() {
        val pkg = Exporter.build(corpus.take(1), tree, "20260828")
        assertTrue(pkg.dataJson.contains("The ___ of AI has changed everything."))
        assertTrue(pkg.dataCsv.lines().first().contains("stem"))
        assertTrue(pkg.dataCsv.contains("The ___ of AI has changed everything."))
    }

    @Test fun `everything is exported now that there is no statistics gate`() {
        val withDraft = corpus + rec("gf_900_1", no = 900, kaodian = null)
        assertEquals(41, Exporter.validRecords(withDraft).size)
    }

    @Test fun `csv escapes commas`() {
        val r = rec("gf_001_1").copy(note = "a,b \"q\"")
        val line = Exporter.csv(listOf(r)).lines()[1]
        assertTrue(line.endsWith("\"a,b \"\"q\"\"\""))
    }
}
