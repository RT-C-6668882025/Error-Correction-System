import com.ecs.agent.Batch
import kotlinx.coroutines.delay
import kotlinx.coroutines.runBlocking
import java.util.concurrent.atomic.AtomicInteger
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/** 批量分析的并发闸门。串行是「分析很慢」的全部来源，所以这里直接量它。 */
class BatchTest {

    @Test fun `calls actually overlap instead of queueing up`() = runBlocking {
        val running = AtomicInteger()
        val peak = AtomicInteger()
        Batch.map(items = (1..8).toList(), concurrency = 4) {
            val now = running.incrementAndGet()
            peak.updateAndGet { maxOf(it, now) }
            delay(30)
            running.decrementAndGet()
        }
        assertTrue(peak.get() > 1, "还是一条一条串着跑，峰值并发 ${peak.get()}")
        assertTrue(peak.get() <= 4, "超过了闸门，会把中转站打成 429：${peak.get()}")
    }

    @Test fun `results come back in the order they went in`() = runBlocking {
        val out = Batch.map((1..10).toList(), concurrency = 5) {
            delay((10 - it) * 3L)
            it * 2
        }
        assertEquals((1..10).map { it * 2 }, out.map { it.getOrThrow() })
    }

    @Test fun `one failure does not take the whole batch down`() = runBlocking {
        val out = Batch.map(listOf(1, 2, 3), concurrency = 3) {
            if (it == 2) error("这条炸了") else it
        }
        assertEquals(listOf(true, false, true), out.map { it.isSuccess })
        assertEquals("这条炸了", out[1].exceptionOrNull()?.message)
    }

    @Test fun `progress counts finished items and ends at the total`() = runBlocking {
        val seen = mutableListOf<Int>()
        Batch.map(
            items = (1..6).toList(),
            concurrency = 3,
            onProgress = { finished, total ->
                seen += finished
                assertEquals(6, total)
            },
        ) { delay(5) }
        assertEquals(6, seen.size)
        assertEquals((1..6).toList(), seen.sorted())
        assertEquals(6, seen.last(), "进度条最后一格要走到头")
    }

    @Test fun `an empty batch does nothing`() = runBlocking {
        assertEquals(emptyList(), Batch.map(emptyList<Int>()) { error("不该被调用") })
    }
}
