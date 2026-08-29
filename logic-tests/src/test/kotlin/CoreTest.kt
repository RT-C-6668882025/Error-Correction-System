import com.ecs.core.agg.Aggregator
import com.ecs.core.direction.Direction
import com.ecs.core.direction.DirectionNode
import com.ecs.core.export.Exporter
import com.ecs.core.model.*
import com.ecs.core.parse.SlotSpec
import com.ecs.core.rules.StyleGuard
import com.ecs.core.rules.Validation
import com.ecs.core.tree.Skeleton
import kotlin.test.*

internal fun rec(
    id: String,
    paper: String = "2023真题卷",
    no: Int = 1,
    slot: Int = 1,
    conf: Confidence = Confidence.WRONG,
    branch: String? = "词法/名词",
    status: RecordStatus = RecordStatus.ANALYZED,
    createdAt: Long = 1_700_000_000_000,
    batch: String = "b01",
    answer: String? = "development",
    basis: String? = "空前有 the，空后接介词短语",
    formShape: String? = "名词，动词加 -tion 后缀",
    stem: String? = "The ___ of AI has changed everything.",
) = ErrorRecord(
    id = id,
    src = Src(paper, no, slot, batch),
    stem = stem,
    answer = answer,
    confidence = conf,
    createdAt = createdAt,
    branch = branch,
    formShape = formShape,
    basis = basis,
    formContext = "单数，谓语 has 限定",
    status = status,
)

class SlotSpecTest {
    @Test fun `id format is neutral now that there is no section`() {
        assertEquals("q_034_1", SlotSpec.Mark(34, 1, Confidence.WRONG).id)
        assertEquals("q_012_2", SlotSpec.Mark(12, 2, Confidence.LUCKY).id)
        assertTrue(SlotSpec.isValidId("q_012_2"))
        assertFalse(SlotSpec.isValidId("q_12_2"))
    }

    @Test fun `historical gf and wc ids still parse`() {
        // 老库里的记录不能因为改了前缀就被判成非法 id
        assertTrue(SlotSpec.isValidId("gf_034_1"))
        assertTrue(SlotSpec.isValidId("wc_012_2"))
        assertEquals(12 to 2, SlotSpec.parseId("wc_012_2"))
        assertEquals(34 to 1, SlotSpec.parseId("q_034_1"))
        assertNull(SlotSpec.parseId("xx_034_1"))
    }

    @Test fun `batch label`() = assertEquals("b07", SlotSpec.batchLabel(7))
}

class ValidationTest {
    @Test fun `basis length window`() {
        assertTrue(Validation.checkBasis("空前有 the，空后无宾语").isEmpty())
        assertTrue(Validation.checkBasis("太短了").isNotEmpty())
        assertTrue(Validation.checkBasis("字".repeat(26)).isNotEmpty())
    }

    @Test fun `basis banned words rejected`() {
        val issues = Validation.checkBasis("考查名词后缀转换的用法特征")
        assertTrue(issues.any { it.message.contains("考查") })
    }

    @Test fun `basis may not reference stripped options`() {
        assertTrue(Validation.checkBasis("排除 A项 后剩下的名词形式").isNotEmpty())
    }

    @Test fun `form_context capped at 20`() {
        assertTrue(Validation.checkFormContext("单数，谓语 has 限定").isEmpty())
        assertTrue(Validation.checkFormContext("字".repeat(21)).isNotEmpty())
    }

    @Test fun `branch must hit the skeleton`() {
        assertTrue(Validation.checkBranch("词法/名词").isEmpty())
        assertTrue(Validation.checkBranch("词法/瞎编").isNotEmpty())
        assertTrue(Validation.checkBranch(null).isNotEmpty())
        // 老的四层考点路径截到板块那一层照样认
        assertTrue(Validation.checkBranch("词法/名词/后缀转换/-tion").isEmpty())
    }

    @Test fun `status derivation covers pending answer and pending analysis`() {
        assertEquals(RecordStatus.ANALYZED, Validation.deriveStatus(rec("q_001_1")))
        assertEquals(
            RecordStatus.PENDING_ANSWER,
            Validation.deriveStatus(rec("q_001_1", answer = null)),
        )
        assertEquals(
            RecordStatus.PENDING,
            Validation.deriveStatus(rec("q_001_1", formShape = null)),
        )
        assertEquals(
            RecordStatus.PENDING,
            Validation.deriveStatus(rec("q_001_1", branch = "词法/瞎编")),
        )
        assertEquals(
            RecordStatus.PENDING,
            Validation.deriveStatus(rec("q_001_1"), identifyConf = 0.5),
        )
    }

    @Test fun `an unanalysed record is not nagged about missing analysis fields`() {
        val raw = rec("q_001_1", branch = null, basis = null, formShape = null)
            .copy(formContext = null)
        assertTrue(Validation.validate(raw).none { it.field == "basis" })
    }

    @Test fun `duplicate uid is blocking but different papers are not`() {
        val a = rec("q_001_1", paper = "卷A")
        val b = rec("q_001_1", paper = "卷B")
        assertTrue(Validation.validate(a, existingUids = setOf(b.uid)).none { it.blocking })
        assertTrue(Validation.validate(a, existingUids = setOf(a.uid)).any { it.blocking })
    }
}

class AggregatorTest {

    private val corpus = listOf(
        rec("q_001_1", branch = "词法/名词", formShape = "名词，动词加 -tion 后缀"),
        rec("q_002_1", no = 2, branch = "词法/名词", formShape = "名词复数，词尾加 -s"),
        rec("q_003_1", no = 3, branch = "语法/时态", formShape = "一般过去时，动词过去式"),
        rec("q_004_1", no = 4, branch = "句法/句子结构", formShape = "关系代词 which/that"),
    )

    @Test fun `unanalysed records stay out of the blocks`() {
        val withDraft = corpus + rec("q_009_1", no = 9, branch = null, formShape = null)
        assertEquals(4, Aggregator.analyzed(withDraft).size)
        assertEquals(1, Aggregator.pending(withDraft).size)
        assertEquals(4, Aggregator.blocks(withDraft).sumOf { it.size })
    }

    @Test fun `a record whose branch is off the skeleton counts as pending`() {
        val bogus = corpus + rec("q_010_1", no = 10, branch = "修辞/比喻")
        assertEquals(1, Aggregator.pending(bogus).size)
        assertTrue(Aggregator.blocks(bogus).none { it.path == "修辞/比喻" })
    }

    @Test fun `blocks group by branch and keep skeleton order`() {
        val blocks = Aggregator.blocks(corpus)
        assertEquals(listOf("词法/名词", "句法/句子结构", "语法/时态"), blocks.map { it.path })
        assertEquals(2, blocks.first().size)
    }

    @Test fun `every skeleton branch shows up when empties are included`() {
        assertEquals(Skeleton.BRANCHES.size, Aggregator.blocks(corpus, includeEmpty = true).size)
        assertEquals(3, Aggregator.blocks(corpus, includeEmpty = false).size)
    }

    @Test fun `one block carries exactly the analyses that feed the minor direction`() {
        val nouns = Aggregator.block(corpus, Skeleton.branchOf("词法/名词")!!)
        assertEquals(2, nouns.size)
        assertEquals(
            setOf("名词，动词加 -tion 后缀", "名词复数，词尾加 -s"),
            nouns.analyses.map { it.formShape }.toSet(),
        )
        val one = nouns.analyses.first()
        assertEquals("空前有 the，空后接介词短语", one.basis)
        assertEquals("The ___ of AI has changed everything.", one.stem)
    }

    @Test fun `the facts block never leaks question ids or counts of wrongness`() {
        val facts = Aggregator.facts(Aggregator.block(corpus, Skeleton.branchOf("词法/名词")!!))
        assertFalse(facts.contains("q_001_1"))
        listOf("错误率", "加权", "难度").forEach { assertFalse(facts.contains(it)) }
        assertTrue(facts.contains("名词，动词加 -tion 后缀"))
    }
}

class DirectionTest {

    private val noun = Direction(
        scope = "词法/名词",
        nodes = listOf(
            DirectionNode(
                "名", children = listOf(
                    DirectionNode("专有n", rule = "首字母大写，通常不加冠词"),
                    DirectionNode(
                        "普通n", children = listOf(
                            DirectionNode(
                                "可数n", children = listOf(
                                    DirectionNode("规则复数", rule = "词尾加 -s/-es"),
                                    DirectionNode("不规则复数", rule = "man→men 一类，背"),
                                )
                            ),
                            DirectionNode("不可数n", rule = "不加 -s，用量词计量"),
                        )
                    ),
                )
            ),
            DirectionNode("名词的格", rule = "'s属格 / of属格 / 双重属格"),
        ),
        fromCount = 7,
        generatedAt = 0,
    )

    @Test fun `render draws the tree you can read at a glance`() {
        val out = noun.render()
        val lines = out.lines()
        assertEquals("名", lines[0])
        assertTrue(lines[1].startsWith("├── 专有n"))
        assertTrue(lines[2].startsWith("└── 普通n"))
        assertTrue(lines[3].startsWith("    ├── 可数n"))
        assertTrue(lines[4].startsWith("    │   ├── 规则复数"))
        assertTrue(lines[5].startsWith("    │   └── 不规则复数"))
        assertTrue(lines[6].startsWith("    └── 不可数n"))
        assertTrue(lines.last().startsWith("名词的格"))
    }

    @Test fun `rules are the leaves output, deduped and in order`() {
        assertEquals(
            listOf(
                "首字母大写，通常不加冠词",
                "词尾加 -s/-es",
                "man→men 一类，背",
                "不加 -s，用量词计量",
                "'s属格 / of属格 / 双重属格",
            ),
            noun.rules(),
        )
    }

    @Test fun `size counts every node, not just the leaves`() {
        assertEquals(8, noun.size())
        assertEquals(5, noun.leaves().size)
    }

    @Test fun `clean drops empty names and shells`() {
        val messy = listOf(
            DirectionNode("  "),
            DirectionNode("可数n", children = listOf(DirectionNode("空壳"))),
            DirectionNode("有内容", rule = " 词尾加 -s "),
        )
        val out = Direction.clean(messy)
        // 空名字整条丢掉；「空壳」既没形态也没孩子，跟着丢；父节点也就空了
        assertEquals(listOf("有内容"), out.map { it.name })
        assertEquals("词尾加 -s", out.single().rule)
    }

    @Test fun `clean cuts nesting deeper than the cap`() {
        var node = DirectionNode("底", rule = "形态")
        repeat(8) { node = DirectionNode("层", children = listOf(node)) }
        val out = Direction.clean(listOf(node))
        fun depth(n: DirectionNode): Int = 1 + (n.children.maxOfOrNull { depth(it) } ?: 0)
        assertTrue(out.isEmpty() || depth(out.single()) <= Direction.MAX_DEPTH)
    }

    @Test fun `an empty direction says so instead of pretending`() {
        assertTrue(Direction("词法/冠词", emptyList(), 0, 0).empty)
        assertFalse(noun.empty)
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

class ExporterTest {
    private val corpus = (1..40).map { rec("q_%03d_1".format(it), no = it) }
    private val directions = listOf(
        Direction("词法/名词", listOf(DirectionNode("可数n", rule = "词尾加 -s")), 12, 0),
        Direction(Direction.ALL, listOf(DirectionNode("词法", rule = "词形")), 3, 0),
    )

    @Test fun `readme stays within 120 lines`() {
        val pkg = Exporter.build(corpus, directions, "20260828")
        assertTrue(
            pkg.readme.lines().size <= Exporter.README_MAX_LINES,
            "readme is ${pkg.readme.lines().size} lines",
        )
        assertTrue(pkg.readme.contains("词法/名词"))
    }

    @Test fun `readme explains the three stage pipeline, not the old tree`() {
        val md = Exporter.readme(corpus, directions)
        assertTrue(md.contains("`stem`"))
        assertTrue(md.contains("`form_shape`"))
        assertTrue(md.contains("小方向"))
        assertTrue(md.contains("大方向"))
        // 题型、抽检、向量检索这些机制已经不存在，说明书里不能还留着
        listOf("语法填空", "完成句子", "抽检", "tree_version", "Top-5", "向量").forEach {
            assertFalse(md.contains(it), "README 还在说已经删掉的 $it")
        }
    }

    @Test fun `the major direction is told to read only the minor outputs`() {
        val md = Exporter.readme(corpus, directions)
        assertTrue(md.contains("不要回头读原题"))
    }

    @Test fun `stem and analysis survive json and csv`() {
        val pkg = Exporter.build(corpus.take(1), directions, "20260828")
        assertTrue(pkg.dataJson.contains("The ___ of AI has changed everything."))
        val header = pkg.dataCsv.lines().first()
        assertTrue(header.contains("stem"))
        assertTrue(header.contains("branch"))
        assertTrue(header.contains("form_shape"))
        assertFalse(header.contains("section"))
        assertTrue(pkg.dataCsv.contains("词法/名词"))
    }

    @Test fun `directions ride along in their own file`() {
        val pkg = Exporter.build(corpus.take(1), directions, "20260828")
        assertTrue(pkg.directionsJson.contains("词法/名词"))
        assertTrue(pkg.directionsJson.contains(Direction.ALL))
    }

    @Test fun `everything is exported, analysed or not`() {
        val withDraft = corpus + rec("q_900_1", no = 900, branch = null, formShape = null)
        assertEquals(41, Exporter.validRecords(withDraft).size)
    }

    @Test fun `csv escapes commas`() {
        val r = rec("q_001_1").copy(note = "a,b \"q\"")
        val line = Exporter.csv(listOf(r)).lines()[1]
        assertTrue(line.endsWith("\"a,b \"\"q\"\"\""))
    }
}
