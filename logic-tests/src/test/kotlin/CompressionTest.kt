import com.ecs.agent.AgentClient
import com.ecs.agent.DirectionBuilder
import com.ecs.agent.PromptProvider
import com.ecs.core.agg.Aggregator
import com.ecs.core.agg.Compressor
import com.ecs.core.direction.Direction
import com.ecs.core.direction.DirectionNode
import com.ecs.core.model.ApiEndpoint
import com.ecs.core.model.Confidence
import com.ecs.core.model.ErrorRecord
import com.ecs.core.model.Protocol
import com.ecs.core.model.RecordStatus
import com.ecs.core.model.Src
import com.ecs.core.tree.Skeleton
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

private val offlineClient =
    AgentClient { AgentClient.Config(ApiEndpoint("x", "x", "https://x.com", Protocol.OPENAI), "m") }

private fun record(
    n: Int,
    shape: String,
    basis: String? = "空前有 the，空后接介词短语",
    context: String? = null,
    branch: String = "词法/名词",
) = ErrorRecord(
    id = "q_%03d_1".format(n),
    src = Src("2026卷", n, 1, "b01"),
    // 每个 n 代表不同原题；否则判重门会把测试夹具当成同一道题反复导入。
    stem = "Question $n: The ___ of AI has changed everything.",
    given = "develop",
    answer = "development",
    confidence = Confidence.WRONG,
    createdAt = 1_700_000_000_000,
    branch = branch,
    formShape = shape,
    basis = basis,
    formContext = context,
    status = RecordStatus.ANALYZED,
)

private fun block(records: List<ErrorRecord>) =
    Aggregator.block(records, Skeleton.branchOf("词法/名词")!!)

class CompressorTest {

    @Test fun `the same form collapses into one pit and keeps the wording`() {
        val out = Compressor.compress(
            block((1..12).map { record(it, "名词复数，词尾加 -s") })
        )
        assertEquals(1, out.pits.size)
        assertEquals(12, out.pits.single().count)
        assertEquals(12, out.total)
        // 形态原文一字不改
        assertEquals("名词复数，词尾加 -s", out.pits.single().shape)
    }

    @Test fun `only the writing is normalized, never the meaning`() {
        val out = Compressor.compress(
            block(
                listOf(
                    record(1, "名词复数，词尾加 -s"),
                    record(2, " 名词复数，词尾加 -s "),   // 只是空白不同
                    record(3, "名词复数，词尾加 -es"),    // 意思不同，必须留成两个坑
                )
            )
        )
        assertEquals(2, out.pits.size)
        assertEquals(2, out.pits.first().count)
    }

    @Test fun `evidence is de-duplicated and ranked by how often it repeats`() {
        val out = Compressor.compress(
            block(
                listOf(
                    record(1, "名词", basis = "空前有 the"),
                    record(2, "名词", basis = "空前有 the"),
                    record(3, "名词", basis = "空前有 the"),
                    record(4, "名词", basis = "主语是复数，谓语用 are"),
                    record(5, "名词", basis = "空后接 of 短语"),
                )
            )
        )
        val bases = out.pits.single().bases
        assertEquals(3, bases.size)                       // 默认每个坑留 3 条
        assertEquals("空前有 the", bases.first().text)
        assertEquals(3, bases.first().count)
    }

    @Test fun `pits are ordered by frequency and ties keep first-seen order`() {
        val records = listOf(
            record(1, "甲"), record(2, "乙"), record(3, "乙"),
            record(4, "丙"), record(5, "丁"),
        )
        val out = Compressor.compress(block(records))
        assertEquals(listOf("乙", "甲", "丙", "丁"), out.pits.map { it.shape })
    }

    @Test fun `compressing twice gives exactly the same result`() {
        val records = (1..30).map { record(it, "形态${it % 7}", basis = "依据${it % 3}") }
        val a = Compressor.compress(block(records))
        val b = Compressor.compress(block(records))
        assertEquals(a, b)
    }

    @Test fun `beyond the cap the rest keep their shapes but lose the evidence`() {
        val records = (1..50).map { record(it, "形态$it") }
        val out = Compressor.compress(block(records), Compressor.Limits(pits = 40))
        assertEquals(40, out.pits.size)
        assertEquals(10, out.tail.size)
        // 覆盖不丢：种类数守恒
        assertEquals(50, out.kinds)
        assertEquals(50, out.total)
    }

    @Test fun `blank evidence never becomes a line`() {
        val out = Compressor.compress(
            block(listOf(record(1, "名词", basis = null, context = "   ")))
        )
        assertTrue(out.pits.single().bases.isEmpty())
        assertTrue(out.pits.single().contexts.isEmpty())
    }
}

class FactsCompressionTest {

    @Test fun `the payload grows with kinds of form, not with the number of questions`() {
        // 同样 3 种形态，题数差 10 倍，送出去的正文应当几乎一样长
        val few = Aggregator.facts(block((1..9).map { record(it, "形态${it % 3}") }))
        val many = Aggregator.facts(block((1..90).map { record(it, "形态${it % 3}") }))
        assertTrue(
            many.length < few.length + 40,
            "题数涨了十倍，正文长度也跟着涨：${few.length} → ${many.length}",
        )
    }

    @Test fun `three hundred questions compress to a fraction of the raw listing`() {
        val records = (1..300).map { record(it, "形态${it % 12}", basis = "依据${it % 5}") }
        val facts = Aggregator.facts(block(records))
        // 未压缩时是 300 行、每行 30 字上下
        val raw = records.joinToString("\n") { "- 答案形式：${it.formShape}｜依据：${it.basis}｜语境：无" }
        assertTrue(facts.length * 5 < raw.length, "压缩后还有 ${facts.length}，原文 ${raw.length}")
        assertTrue(facts.contains("300 道题"))
        assertTrue(facts.contains("12 种答案形式"))
    }

    @Test fun `the counts are stated but no question id or scoring language leaks`() {
        val facts = Aggregator.facts(block((1..4).map { record(it, "名词复数，词尾加 -s") }))
        assertTrue(facts.contains("（4 题）"))
        assertFalse(facts.contains("q_001_1"))
        listOf("错误率", "加权", "难度").forEach { assertFalse(facts.contains(it), "混进了统计层的词：$it") }
    }

    @Test fun `an empty tail adds no line`() {
        val facts = Aggregator.facts(block((1..3).map { record(it, "名词") }))
        assertFalse(facts.contains("其余"))
    }
}

class DirectionViewTest {

    private val tree = Direction(
        scope = "词法/名词",
        nodes = listOf(
            DirectionNode(
                "普通名词",
                children = listOf(
                    DirectionNode(
                        "可数名词",
                        children = listOf(
                            DirectionNode("规则复数", rule = "词尾加 -s/-es"),
                            DirectionNode("不规则复数", rule = "child→children 逐个记"),
                        ),
                    ),
                    DirectionNode("不可数名词", rule = "不加 -s，谓语用单数"),
                ),
            ),
        ),
        fromCount = 42,
        generatedAt = 0,
    )

    @Test fun `the skeleton keeps the structure above the leaves, de-duplicated`() {
        assertEquals(
            listOf("普通名词 › 可数名词", "普通名词"),
            tree.skeleton(),
        )
    }

    @Test fun `leaf lines carry the node name, not just the rule`() {
        assertEquals(
            listOf(
                "规则复数　词尾加 -s/-es",
                "不规则复数　child→children 逐个记",
                "不可数名词　不加 -s，谓语用单数",
            ),
            tree.leafLines(),
        )
    }

    @Test fun `identical leaves collapse`() {
        val twins = Direction(
            "词法/名词",
            listOf(
                DirectionNode("甲", children = listOf(DirectionNode("复数", rule = "加 -s"))),
                DirectionNode("乙", children = listOf(DirectionNode("复数", rule = "加 -s"))),
            ),
            2, 0,
        )
        assertEquals(listOf("复数　加 -s"), twins.leafLines())
    }
}

class MajorInputCompressionTest {

    private val builder = DirectionBuilder(offlineClient, PromptProvider.DEFAULT)

    private fun wide(scope: String, leaves: Int) = Direction(
        scope,
        listOf(
            DirectionNode(
                "容器",
                children = (1..leaves).map { DirectionNode("末端$it", rule = "规则$it") },
            )
        ),
        fromCount = leaves,
        generatedAt = 0,
    )

    @Test fun `the payload is the compressed view, not the full tree render`() {
        val payload = builder.majorInput(listOf(wide("词法/名词", 3)))
        assertTrue(payload.contains("骨架：容器"))
        assertTrue(payload.contains("末端（3 条，已去重）"))
        assertTrue(payload.contains("- 末端1　规则1"))
        // 整树渲染里的枝干符号不该再出现
        assertFalse(payload.contains("└──"))
        assertFalse(payload.contains("├──"))
    }

    @Test fun `a very wide minor is capped and says so`() {
        val payload = builder.majorInput(listOf(wide("词法/名词", 45)))
        assertTrue(payload.contains("末端（45 条，已去重）"))
        assertTrue(payload.contains("- 末端${DirectionBuilder.LEAF_CAP}　规则${DirectionBuilder.LEAF_CAP}"))
        assertFalse(payload.contains("- 末端${DirectionBuilder.LEAF_CAP + 1}　"))
        assertTrue(payload.contains("另有 ${45 - DirectionBuilder.LEAF_CAP} 条"))
    }

    @Test fun `each minor contributes a bounded number of lines, however big its tree`() {
        // 有界才是重点：一棵树再大，往上送的行数也只由 LEAF_CAP 决定
        val huge = builder.majorInput(listOf(wide("词法/名词", 400))).lines().size
        val small = builder.majorInput(listOf(wide("词法/名词", 5))).lines().size
        assertTrue(huge <= DirectionBuilder.LEAF_CAP + 8, "一棵树送了 $huge 行")
        assertTrue(huge - small <= DirectionBuilder.LEAF_CAP, "行数随树的大小失控：$small → $huge")
    }

    @Test fun `the compressed payload is smaller than the full render`() {
        val minors = (1..10).map { wide("词法/名词", 40) }
        val compressed = builder.majorInput(minors).length
        val full = minors.sumOf { it.render().length }
        assertTrue(compressed < full, "压缩后 $compressed，全文 $full")
    }

    @Test fun `repeated middle layers are stated once, not once per leaf`() {
        val deep = Direction(
            "词法/名词",
            listOf(
                DirectionNode(
                    "普通名词",
                    children = listOf(
                        DirectionNode(
                            "可数名词",
                            children = (1..20).map { DirectionNode("末端$it", rule = "规则$it") },
                        )
                    ),
                )
            ),
            20, 0,
        )
        val payload = builder.majorInput(listOf(deep))
        // 「普通名词 › 可数名词」这条路径只说一次
        assertEquals(1, payload.split("普通名词 › 可数名词").size - 1)
        assertTrue(payload.length < deep.render().length)
    }

    @Test fun `where the analyses came from is still stated`() {
        val payload = builder.majorInput(listOf(wide("词法/名词", 2)))
        assertTrue(payload.contains("由 2 条分析汇总"))
    }
}
