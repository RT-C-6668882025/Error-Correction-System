import com.ecs.agent.AgentClient
import com.ecs.core.export.CsvImporter
import com.ecs.core.export.Exporter
import com.ecs.core.model.*
import com.ecs.core.model.BuiltInEndpoints
import com.ecs.core.model.ModelCatalog
import kotlin.test.*

private val client = AgentClient {
    AgentClient.Config(BuiltInEndpoints.ALL.first(), ModelCatalog.DEFAULT_TEXT)
}

class JsonExtractionTest {
    @Test fun `plain object`() {
        assertEquals("""{"a":1}""", client.extractJson("""{"a":1}"""))
    }

    @Test fun `fenced block`() {
        assertEquals("""{"a":1}""", client.extractJson("```json\n{\"a\":1}\n```"))
    }

    @Test fun `prose before and after`() {
        assertEquals("""{"branch":"词法/名词"}""", client.extractJson("好的，结果如下：{\"branch\":\"词法/名词\"}。以上。"))
    }

    @Test fun `braces inside strings do not terminate early`() {
        val raw = """{"basis":"空前有 } 符号","form":"名词"}"""
        assertEquals(raw, client.extractJson("说明：$raw"))
    }

    @Test fun `escaped quote inside string`() {
        val raw = """{"basis":"含 \" 引号","form":"名词"}"""
        assertEquals(raw, client.extractJson(raw))
    }

    @Test fun `array is extracted too`() {
        assertEquals("""[{"name":"可数n"}]""", client.extractJson("""结果：[{"name":"可数n"}]"""))
    }

    @Test fun `no json raises`() {
        assertFailsWith<AgentClient.AgentException> { client.extractJson("模型拒绝回答") }
    }

    @Test fun `unclosed json raises`() {
        assertFailsWith<AgentClient.AgentException> { client.extractJson("""{"a":1""") }
    }
}

class CsvRoundTripTest {
    @Test fun `export then import preserves the fields that matter`() {
        val original = rec("q_034_1").copy(note = "含,逗号和\"引号")
        val back = CsvImporter.parse(Exporter.csv(listOf(original))).single()
        assertEquals(original.id, back.id)
        assertEquals(original.src, back.src)
        assertEquals(original.stem, back.stem)
        assertEquals(original.branch, back.branch)
        assertEquals(original.formShape, back.formShape)
        assertEquals(original.basis, back.basis)
        assertEquals(original.formContext, back.formContext)
        assertEquals(original.note, back.note)
        assertEquals(original.confidence, back.confidence)
        assertEquals(original.status, back.status)
        assertEquals(original.createdAt, back.createdAt)
    }

    @Test fun `an old export still loads, with its four-level kaodian cut to a branch`() {
        // v3 及以前的导出包用的是 section / kaodian / form_rule / eye
        val csv = "id,paper,section,no,slot,kaodian,form_rule,eye\n" +
            "gf_034_1,卷A,语法填空,34,1,词法/名词/后缀转换/-tion,名词加 -tion,空前有 the\n"
        val back = CsvImporter.parse(csv).single()
        assertEquals("gf_034_1", back.id)
        assertEquals("词法/名词", back.branch)
        assertEquals("名词加 -tion", back.formShape)
        assertEquals("空前有 the", back.basis)
    }

    @Test fun `rows missing optional columns still load`() {
        val csv = "id,paper,no,slot\nq_001_1,卷A,1,1\n"
        val back = CsvImporter.parse(csv, now = 42L).single()
        assertEquals("b01", back.src.batch)
        assertEquals(42L, back.createdAt)
        assertEquals(RecordStatus.PENDING, back.status)
        assertNull(back.answer)
        assertNull(back.branch)
    }

    @Test fun `rows missing an identifying column are skipped, not crashed on`() {
        val csv = "id,paper,no,slot\n,卷A,1,1\nq_002_1,卷A,2,1\n"
        assertEquals(listOf("q_002_1"), CsvImporter.parse(csv).map { it.id })
    }
}
