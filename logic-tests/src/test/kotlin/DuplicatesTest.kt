import com.ecs.core.dup.Duplicates
import com.ecs.core.model.RecordStatus
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * 判重：入库时按 uid 去重挡不住「换个卷名再录一遍」，
 * 而重复的题会在汇总时被当成两次证据。
 */
class DuplicatesTest {

    private val stem = "The ___ of AI has changed everything."

    @Test fun `the same question entered under another paper name is a duplicate`() {
        val a = rec("q_001_1", paper = "2023真题卷", stem = stem)
        val b = rec("q_007_1", paper = "随手拍的那张", no = 7, stem = stem)
        val groups = Duplicates.groups(listOf(a, b))
        assertEquals(1, groups.size)
        assertEquals(1, groups.first().drop.size)
        assertEquals(2, groups.first().size)
    }

    @Test fun `three blanks in one sentence are three questions, not duplicates`() {
        // 同一句话的三个空只有 slot 不同，把下划线抹掉就会并成一条
        val one = listOf(
            rec("q_003_1", slot = 1, stem = "___ man playing ___ guitar just celebrated his ___ birthday.", answer = "The"),
            rec("q_003_2", slot = 2, stem = "___ man playing ___ guitar just celebrated his ___ birthday.", answer = "the"),
            rec("q_003_3", slot = 3, stem = "___ man playing ___ guitar just celebrated his ___ birthday.", answer = "fortieth"),
        )
        assertTrue(Duplicates.groups(one).isEmpty(), "同句不同空被当成重复了")
    }

    @Test fun `写法上的差别不算区别`() {
        val a = rec("q_001_1", stem = "The ___ of AI has changed everything.")
        val b = rec("q_002_1", no = 2, stem = "the _____   of  ai has changed everything")
        assertEquals(1, Duplicates.groups(listOf(a, b)).size)
    }

    @Test fun `different answers on the same stem are left alone`() {
        val a = rec("q_001_1", answer = "development")
        val b = rec("q_002_1", no = 2, answer = "developments")
        assertTrue(Duplicates.groups(listOf(a, b)).isEmpty(), "答案不同还并成一条，等于替人做主")
    }

    @Test fun `a missing answer joins the only answer there is`() {
        val a = rec("q_001_1", answer = "development")
        val b = rec("q_002_1", no = 2, answer = null)
        val groups = Duplicates.groups(listOf(a, b))
        assertEquals(1, groups.size)
        // 有答案的那条留下
        assertEquals("development", groups.first().keep.answer)
    }

    @Test fun `the analysed one is the one that stays`() {
        val analysed = rec("q_001_1", createdAt = 2_000)
        val bare = rec(
            "q_002_1", no = 2, createdAt = 1_000,
            branch = null, formShape = null, basis = null, status = RecordStatus.PENDING,
        )
        val group = Duplicates.groups(listOf(bare, analysed)).single()
        assertEquals(analysed.uid, group.keep.uid, "删了已分析的那条等于白跑一次分析")
        assertEquals(listOf(bare.uid), group.drop.map { it.uid })
    }

    @Test fun `same inputs always keep the same record`() {
        val list = listOf(rec("q_001_1"), rec("q_002_1", no = 2), rec("q_003_1", no = 3))
        val first = Duplicates.groups(list).single().keep.uid
        repeat(3) { assertEquals(first, Duplicates.groups(list.shuffled()).single().keep.uid) }
    }

    @Test fun `a record without a stem never joins a group`() {
        val blanks = listOf(rec("q_001_1", stem = null), rec("q_002_1", no = 2, stem = "   "))
        assertTrue(Duplicates.groups(blanks).isEmpty(), "没题干就没法判断它跟谁重复")
        assertNull(Duplicates.signature(blanks.first()))
    }

    @Test fun `redundant lists exactly what can be deleted`() {
        val list = listOf(
            rec("q_001_1"),
            rec("q_002_1", no = 2),
            rec("q_003_1", no = 3),
            rec("q_009_1", no = 9, stem = "She is ___ than her sister.", answer = "taller"),
        )
        val redundant = Duplicates.redundant(list)
        assertEquals(2, redundant.size)
        // 每一簇至少留一条：删完之后不重复，且没有整簇被删光
        val left = list.filterNot { it.uid in redundant.map { d -> d.uid } }
        assertEquals(2, left.size)
        assertTrue(Duplicates.groups(left).isEmpty())
    }

    @Test fun `canonical keeps one record per duplicate group without deleting unique records`() {
        val analysed = rec("q_001_1", createdAt = 2_000)
        val duplicate = rec(
            "q_002_1", no = 2, createdAt = 1_000,
            branch = null, formShape = null, basis = null, status = RecordStatus.PENDING,
        )
        val unique = rec(
            "q_009_1", no = 9, stem = "She is ___ than her sister.", answer = "taller",
        )
        assertEquals(
            listOf(analysed.uid, unique.uid),
            Duplicates.canonical(listOf(duplicate, analysed, unique)).map { it.uid },
        )
    }
}
