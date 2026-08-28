import com.ecs.core.agg.Aggregator
import com.ecs.core.export.Exporter
import com.ecs.core.model.*
import com.ecs.core.parse.SlotSpec
import com.ecs.core.report.ReportBuilder
import com.ecs.core.rules.Dormancy
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
) = ErrorRecord(
    id = id,
    src = Src(paper, section, no, slot, batch, total),
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
    @Test fun `only active and dormant records count`() {
        val rs = listOf(
            rec("gf_001_1"),
            rec("gf_002_1", status = RecordStatus.DORMANT),
            rec("gf_003_1", status = RecordStatus.PENDING_ANSWER, answer = null),
            rec("gf_004_1", status = RecordStatus.INCOMPLETE),
            rec("gf_005_1", status = RecordStatus.ARCHIVED),
        )
        assertEquals(2, Aggregator.countable(rs).size)
        assertEquals(2, Aggregator.maturity(rs).countedSlots)
    }

    @Test fun `weighted count halves lucky guesses`() {
        val rs = listOf(rec("gf_001_1"), rec("gf_002_1", conf = Confidence.LUCKY))
        val stat = Aggregator.kaodianStats(rs, TREE, depth = 4).single()
        assertEquals(2, stat.slots)
        assertEquals(1, stat.wrong)
        assertEquals(1, stat.lucky)
        assertEquals(1.5, stat.weighted)
    }

    @Test fun `hit_papers counts distinct papers not repeats`() {
        val sameCard = (1..5).map { rec("gf_00${it}_1", paper = "卷A", no = it) }
        val spread = (1..3).map { rec("gf_00${it}_1", paper = "卷$it", no = it) }
        assertEquals(1, Aggregator.kaodianStats(sameCard, TREE, 4).single().hitPapers)
        assertEquals(3, Aggregator.kaodianStats(spread, TREE, 4).single().hitPapers)
    }

    @Test fun `priority ordering prefers cross-paper over raw count`() {
        val rs = (1..5).map { rec("gf_01${it}_1", paper = "卷A", no = it) } +
            (1..2).map { rec("wc_02${it}_1", paper = "卷$it", no = 20 + it, section = Section.WC, kaodian = "句法/从句结构/定语从句/关系代词") }
        val stats = Aggregator.kaodianStats(rs, TREE, 4)
        assertEquals("句法/从句结构/定语从句/关系代词", stats.first().kaodian)
    }

    @Test fun `truncation collapses to the requested depth`() {
        assertEquals("词法/名词/后缀转换", truncate("词法/名词/后缀转换/-tion", 3))
        assertEquals("词法", truncate("词法/名词/后缀转换/-tion", 1))
        val rs = listOf(rec("gf_001_1"), rec("gf_002_1", kaodian = "词法/动词/时态形式/一般过去时"))
        assertEquals(1, Aggregator.kaodianStats(rs, TREE, 1).size)
        assertEquals(2, Aggregator.kaodianStats(rs, TREE, 2).size)
    }

    @Test fun `papers without total_in_section leave both numerator and denominator`() {
        val rs = listOf(
            rec("gf_001_1", paper = "卷A", total = 10),
            rec("gf_002_1", paper = "卷A", total = 10, no = 2),
            rec("gf_003_1", paper = "卷B", total = null, no = 3),
        )
        val gf = Aggregator.sectionRates(rs).first { it.section == Section.GF }
        assertEquals(10, gf.totalSlots)
        assertEquals(2.0, gf.weightedWrong)
        assertEquals(0.2, gf.rate)
        assertEquals(1, gf.papersSkipped)
        assertEquals(4.0, gf.weightedLoss)
    }

    @Test fun `missing denominator yields null rate not zero`() {
        val gf = Aggregator.sectionRates(listOf(rec("gf_001_1", total = null)))
            .first { it.section == Section.GF }
        assertNull(gf.rate)
        assertNull(gf.weightedLoss)
    }

    @Test fun `weighted loss scales to section full score`() {
        val wc = Aggregator.sectionRates(
            listOf(rec("wc_001_1", section = Section.WC, total = 9, kaodian = "句法/从句结构/定语从句/关系代词"))
        ).first { it.section == Section.WC }
        assertEquals(18, wc.section.fullScore)
        assertEquals(2.0, wc.weightedLoss)
    }

    @Test fun `same question different slots is a strong correlation`() {
        val rs = (1..3).flatMap { p ->
            listOf(
                rec("wc_012_1", paper = "卷$p", section = Section.WC, no = 12, slot = 1),
                rec("wc_012_2", paper = "卷$p", section = Section.WC, no = 12, slot = 2, kaodian = "词法/动词/时态形式/一般过去时"),
            )
        }
        val c = Aggregator.correlations(rs, depth = 4).single()
        assertEquals(3, c.strong)
        assertEquals(0, c.weak)
    }

    @Test fun `correlations below three occurrences are dropped`() {
        val rs = listOf(
            rec("wc_012_1", section = Section.WC, no = 12, slot = 1),
            rec("wc_012_2", section = Section.WC, no = 12, slot = 2, kaodian = "词法/动词/时态形式/一般过去时"),
        )
        assertTrue(Aggregator.correlations(rs, depth = 4).isEmpty())
    }

    @Test fun `maturity gates report entry points`() {
        fun mk(n: Int) = (1..n).map { rec("gf_%03d_1".format(it), no = it) }
        Aggregator.maturity(mk(10)).let {
            assertFalse(it.microEnabled); assertFalse(it.macroEnabled)
            assertTrue(it.hint.contains("40"))
        }
        Aggregator.maturity(mk(60)).let {
            assertTrue(it.microEnabled); assertTrue(it.macroEnabled); assertTrue(it.macroCaveat)
        }
        Aggregator.maturity(mk(250)).let {
            assertTrue(it.macroEnabled); assertFalse(it.macroCaveat)
        }
    }

    @Test fun `consistency alarm below 85 percent`() {
        val ok = (1..9).map { rec("gf_00${it}_1", no = it).copy(verified = Verified.CONSISTENT) }
        val bad = listOf(rec("gf_010_1", no = 10).copy(verified = Verified.CONFLICT))
        assertFalse(Aggregator.consistency(ok + bad).alarm)
        val worse = (1..3).map { rec("gf_10${it}_1", no = it).copy(verified = Verified.CONFLICT) }
        assertTrue(Aggregator.consistency(ok.take(5) + worse).alarm)
        assertNull(Aggregator.consistency(ok.map { it.copy(verified = Verified.UNCHECKED) }).rate)
    }

    @Test fun `round similarity flags an exhausted source`() {
        val b1 = (1..5).map { rec("gf_00${it}_1", no = it, batch = "b01") }
        val b2 = (1..5).map { rec("gf_01${it}_1", paper = "卷B", no = 10 + it, batch = "b02") }
        assertTrue(Aggregator.termination(b1 + b2, TREE, 4).sourceExhausted)
    }

    @Test fun `coverage counts live tree nodes only`() {
        val t = Aggregator.termination(listOf(rec("gf_001_1")), TREE, 4)
        assertEquals(3, TREE.liveNodes.size)
        assertEquals(1.0 / 3, t.coverage, 1e-9)
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

class DormancyTest {
    private val now = 1_700_000_000_000

    @Test fun `thirty idle days puts a kaodian to sleep`() {
        val old = rec("gf_001_1", createdAt = now - 31 * Dormancy.DAY)
        val t = Dormancy.evaluate(listOf(old), now).single()
        assertEquals(RecordStatus.DORMANT, t.to)
        assertEquals(now + 14 * Dormancy.DAY, t.nextCheck)
    }

    @Test fun `a new record wakes the whole kaodian back up`() {
        val old = rec("gf_001_1", createdAt = now - 60 * Dormancy.DAY, status = RecordStatus.DORMANT)
            .copy(nextCheck = now - Dormancy.DAY)
        val fresh = rec("gf_002_1", no = 2, createdAt = now)
        val ts = Dormancy.evaluate(listOf(old, fresh), now)
        assertEquals(RecordStatus.ACTIVE, ts.single().to)
    }

    @Test fun `dormant past next_check archives`() {
        val old = rec("gf_001_1", createdAt = now - 60 * Dormancy.DAY, status = RecordStatus.DORMANT)
            .copy(nextCheck = now - Dormancy.DAY)
        assertEquals(RecordStatus.ARCHIVED, Dormancy.evaluate(listOf(old), now).single().to)
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
    private val corpus = listOf(
        rec("gf_001_1", paper = "卷A", no = 1),
        rec("gf_002_1", paper = "卷B", no = 2, conf = Confidence.LUCKY),
        rec("wc_012_1", paper = "卷B", section = Section.WC, no = 12, slot = 1, total = 9,
            kaodian = "句法/从句结构/定语从句/关系代词", answer = "which"),
    )

    @Test fun `micro report carries the numbers and no question numbers`() {
        val stat = Aggregator.kaodianStats(corpus, TREE, 4)
            .first { it.kaodian == "词法/名词/后缀转换/-tion" }
        val md = ReportBuilder.micro(stat)
        assertTrue(md.contains("**空数** 2（错1 / 蒙对1）"))
        assertTrue(md.contains("**加权计数** 1.5"))
        assertTrue(md.contains("**跨卷次数** 2"))
        assertTrue(md.contains("名词，动词加 -tion 后缀"))
        assertTrue(StyleGuard.violations(md).isEmpty())
    }

    @Test fun `macro report has every required section`() {
        val md = ReportBuilder.macro(corpus, TREE, 2)
        listOf("# 全局分析", "## 薄弱格局", "## 反复出错", "## 跨考点关联",
            "## 优先级排序", "## 暂时不用管", "## 一句话结论").forEach {
            assertTrue(md.contains(it), "missing $it")
        }
        assertTrue(StyleGuard.violations(md).isEmpty())
    }

    @Test fun `facts block never leaks question ids`() {
        val facts = ReportBuilder.macroFacts(corpus, TREE, 2)
        assertFalse(facts.contains("gf_001_1"))
    }
}

class ExporterTest {
    private val corpus = (1..40).map {
        rec(
            "gf_%03d_1".format(it), paper = "卷${it % 5}", no = it,
            kaodian = if (it % 2 == 0) "词法/名词/后缀转换/-tion" else "词法/动词/时态形式/一般过去时",
        )
    } + listOf(
        rec("gf_900_1", status = RecordStatus.INCOMPLETE, no = 900),
        rec("gf_901_1", status = RecordStatus.PENDING_ANSWER, answer = null, no = 901),
    )

    @Test fun `readme stays within 120 lines`() {
        val pkg = Exporter.build(corpus, TREE, "20260828")
        assertTrue(pkg.readme.lines().size <= Exporter.README_MAX_LINES,
            "readme is ${pkg.readme.lines().size} lines")
        assertTrue(pkg.readme.contains("词法/名词/后缀转换/-tion"))
        assertTrue(pkg.readme.contains("hit_papers"))
    }

    @Test fun `readme stays within the cap even with a large node set`() {
        val many = (1..200).map {
            rec("gf_%03d_1".format(it % 999), no = it, kaodian = "词法/名词/后缀转换/n$it")
        }
        val md = Exporter.readme(many, null)
        assertTrue(md.lines().size <= Exporter.README_MAX_LINES, "readme is ${md.lines().size} lines")
        assertTrue(md.contains("另有"))
    }

    @Test fun `export excludes records that do not count`() {
        val pkg = Exporter.build(corpus, TREE, "20260828")
        assertFalse(pkg.dataJson.contains("gf_900_1"))
        assertFalse(pkg.dataJson.contains("gf_901_1"))
        assertEquals(40 + 1, pkg.dataCsv.trim().lines().size)
        assertEquals("export_20260828", pkg.dirName)
    }

    @Test fun `csv escapes commas`() {
        val r = rec("gf_001_1").copy(note = "a,b \"q\"")
        val line = Exporter.csv(listOf(r)).lines()[1]
        assertTrue(line.endsWith("\"a,b \"\"q\"\"\""))
    }

    @Test fun `reverse table groups by kaodian and keeps the three columns`() {
        val t = Exporter.reverseTable(Exporter.validRecords(corpus))
        assertTrue(t.contains("## 词法/名词/后缀转换/-tion"))
        assertTrue(t.contains("| eye | form_context | answer |"))
        assertTrue(t.contains("规则形态：名词，动词加 -tion 后缀"))
    }
}
