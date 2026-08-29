import com.ecs.agent.AgentClient
import com.ecs.agent.PaperScanner
import com.ecs.core.export.Exporter
import com.ecs.core.model.*
import com.ecs.core.model.BuiltInEndpoints
import com.ecs.core.model.ModelCatalog
import com.ecs.core.parse.ChoiceStripper
import com.ecs.core.rules.Validation
import kotlin.test.*

class ChoiceStripperTest {

    @Test fun `four options are stripped off the stem`() {
        val raw = "The ___ of AI has changed everything. A. develop B. developing C. development D. developed"
        val out = ChoiceStripper.strip(raw, 'C')
        assertTrue(out.isChoice)
        assertEquals("The ___ of AI has changed everything.", out.stem)
        assertEquals(listOf('A', 'B', 'C', 'D'), out.options.map { it.letter })
        assertEquals("development", out.answer)
    }

    @Test fun `full width markers and parentheses`() {
        val raw = "He ___ here since 2019.（A）live （B）lived （C）has lived （D）living"
        val out = ChoiceStripper.strip(raw, 'C')
        assertTrue(out.isChoice)
        assertEquals("He ___ here since 2019.", out.stem)
        assertEquals("has lived", out.answer)
    }

    @Test fun `chinese enumeration markers`() {
        val raw = "他的建议很___。A、value B、valuable C、valued D、valuing"
        val out = ChoiceStripper.strip(raw, 'B')
        assertTrue(out.isChoice)
        assertEquals("valuable", out.answer)
    }

    @Test fun `a plain fill-in-the-blank is untouched`() {
        val raw = "The ___ (develop) of AI has changed everything."
        val out = ChoiceStripper.strip(raw, null)
        assertFalse(out.isChoice)
        assertEquals(raw, out.stem)
        assertTrue(out.options.isEmpty())
        assertNull(out.answer)
    }

    @Test fun `a stray capital A in prose is not an option marker`() {
        val raw = "A. Smith wrote the ___ (introduce) of the book."
        val out = ChoiceStripper.strip(raw, null)
        // 只有 A 没有 B，够不上选择题
        assertFalse(out.isChoice)
    }

    @Test fun `out of order letters are ignored`() {
        val raw = "The ___ is here. C. one A. two B. three"
        val out = ChoiceStripper.strip(raw, 'A')
        // A 之后才轮到 B，C 在前面不算起点
        assertTrue(out.isChoice)
        assertEquals("The ___ is here. C. one", out.stem)
        assertEquals("two", out.answer)
    }

    @Test fun `no stem means nothing worth stripping`() {
        val out = ChoiceStripper.strip("A. one B. two C. three D. four", 'A')
        assertFalse(out.isChoice)
    }

    @Test fun `missing correct letter leaves answer empty but still strips`() {
        val out = ChoiceStripper.strip("The ___ of AI. A. develop B. development", null)
        assertTrue(out.isChoice)
        assertNull(out.answer)
        assertEquals("The ___ of AI.", out.stem)
    }

    @Test fun `unknown letter yields no answer`() {
        val out = ChoiceStripper.strip("The ___ of AI. A. develop B. development", 'E')
        assertTrue(out.isChoice)
        assertNull(out.answer)
    }
}

class CommonRootTest {
    @Test fun `same lemma across four forms gives the base form`() {
        assertEquals(
            "develop",
            ChoiceStripper.commonRoot(listOf("develop", "developing", "development", "developed")),
        )
    }

    @Test fun `y restoration`() {
        assertEquals("apply", ChoiceStripper.commonRoot(listOf("apply", "applied", "applying")))
    }

    @Test fun `consonant doubling`() {
        assertEquals("plan", ChoiceStripper.commonRoot(listOf("plan", "planned", "planning")))
    }

    @Test fun `-ion beats -tion when that is what the set agrees on`() {
        assertEquals("invent", ChoiceStripper.commonRoot(listOf("invent", "invention", "inventing")))
    }

    @Test fun `unrelated words give nothing rather than a wrong guess`() {
        assertNull(ChoiceStripper.commonRoot(listOf("in", "on", "at", "by")))
        assertNull(ChoiceStripper.commonRoot(listOf("which", "that", "who", "whom")))
    }

    @Test fun `multi word options are rejected`() {
        assertNull(ChoiceStripper.commonRoot(listOf("has lived", "lived", "live")))
    }

    @Test fun `a single distinct option is not a family`() {
        assertNull(ChoiceStripper.commonRoot(listOf("develop", "develop")))
    }

    @Test fun `given is derived on strip`() {
        val out = ChoiceStripper.strip(
            "The ___ of AI. A. develop B. developing C. development D. developed", 'C',
        )
        assertEquals("develop", out.given)
    }

    @Test fun `given stays empty when options are not one family`() {
        val out = ChoiceStripper.strip("He is ___ tall. A. very B. much C. many D. quite", 'A')
        assertNull(out.given)
    }
}

class ChoiceAnalysisGuardTest {
    @Test fun `option words are banned from the basis`() {
        listOf("选项", "排除", "A项", "B项", "C项", "D项").forEach { word ->
            val issues = Validation.checkBasis("空前有 the，${word}都是名词形式")
            assertTrue(issues.any { it.message.contains(word) }, "missing guard for $word")
        }
    }

    @Test fun `a clean stem-only basis still passes`() {
        assertTrue(Validation.checkBasis("空前有 the，空后接介词短语").isEmpty())
    }

    @Test fun `the banned list keeps the original words too`() {
        assertTrue(Validation.BASIS_BANNED.containsAll(listOf("考查", "需要", "应该", "主要", "判断")))
        assertTrue(Validation.BASIS_BANNED.containsAll(listOf("选项", "排除", "A项")))
    }
}

class ScannerStrippingTest {
    private val scanner = PaperScanner(
        AgentClient { AgentClient.Config(BuiltInEndpoints.ALL.first(), ModelCatalog.DEFAULT_TEXT) }
    )

    @Test fun `a recognised choice question becomes a fill-in record`() {
        val q = scanner.toQuestion(
            PaperScanner.RawQuestion(
                no = 7, slot = 1,
                stem = "The ___ of AI has changed everything. A. develop B. developing C. development D. developed",
                correct_letter = "C", confidence = 0.95,
            )
        )
        assertTrue(q.isChoice)
        assertEquals("The ___ of AI has changed everything.", q.stem)
        assertEquals("development", q.answer)
        assertEquals("develop", q.given)
    }

    @Test fun `printed given wins over the derived one`() {
        val q = scanner.toQuestion(
            PaperScanner.RawQuestion(
                no = 1, stem = "The ___ of AI. A. develop B. development",
                given = "develop(v.)", correct_letter = "B", confidence = 0.9,
            )
        )
        assertEquals("develop(v.)", q.given)
    }

    @Test fun `slot is floored at one`() {
        val q = scanner.toQuestion(PaperScanner.RawQuestion(no = 1, slot = 0, stem = "x ___ y"))
        assertEquals(1, q.slot)
    }
}

class NoOptionsPersistedTest {
    private val record = ErrorRecord(
        id = "q_007_1",
        src = Src("2023真题卷", 7, 1, "b01"),
        stem = "The ___ of AI has changed everything.",
        given = "develop",
        answer = "development",
        confidence = Confidence.WRONG,
        createdAt = 1_700_000_000_000,
        branch = "词法/名词",
        formShape = "名词，动词加 -tion 后缀",
        basis = "空前有 the，空后接介词短语",
        status = RecordStatus.ANALYZED,
    )

    @Test fun `the exported record carries no option data at all`() {
        val pkg = Exporter.build(listOf(record), emptyList(), "20260828")
        listOf("options", "distractor", "选项", "developing", "developed").forEach {
            assertFalse(pkg.dataJson.contains(it), "data.json leaked $it")
            assertFalse(pkg.dataCsv.contains(it), "data.csv leaked $it")
        }
        assertTrue(pkg.dataJson.contains("development"))
    }

    @Test fun `a stripped choice question is just a fill-in record, with no section anywhere`() {
        val pkg = Exporter.build(listOf(record), emptyList(), "20260828")
        assertFalse(pkg.dataCsv.lines().first().contains("section"))
        assertFalse(pkg.dataJson.contains("语法填空"))
    }
}

class ModelSuggestionTest {
    @Test fun `the named GLM vision models are still in the suggestion list`() {
        listOf("glm-4.6v-flash", "glm-4.1v-thinking-flash").forEach { id ->
            val spec = assertNotNull(ModelCatalog.byId(id), "missing $id")
            assertEquals(BuiltInEndpoints.ZHIPU, spec.endpointId)
            assertTrue(spec.vision)
        }
    }

    @Test fun `defaults resolve to real entries`() {
        assertNotNull(ModelCatalog.byId(ModelCatalog.DEFAULT_TEXT))
        assertNotNull(ModelCatalog.byId(ModelCatalog.DEFAULT_VISION))
        assertTrue(ModelCatalog.byId(ModelCatalog.DEFAULT_VISION)!!.vision)
    }
}
