import com.ecs.core.image.ImageBudget
import kotlin.test.*

class ImageBudgetTest {

    @Test fun `sample size is the largest power of two that stays above the cap`() {
        assertEquals(2, ImageBudget.sampleSize(4000, 3000))   // 4000/2 = 2000 ≥ 1600
        assertEquals(4, ImageBudget.sampleSize(8000, 6000))   // 8000/4 = 2000 ≥ 1600
        assertEquals(1, ImageBudget.sampleSize(1600, 1200))
        assertEquals(1, ImageBudget.sampleSize(800, 600))
    }

    @Test fun `sample size is always a power of two`() {
        listOf(640, 1024, 1600, 2048, 3000, 4032, 6000, 12000).forEach { w ->
            val s = ImageBudget.sampleSize(w, w * 3 / 4)
            assertTrue(s > 0 && (s and (s - 1)) == 0, "sampleSize($w) = $s 不是 2 的幂")
        }
    }

    @Test fun `sample size never crops below the cap`() {
        // 降采样后长边必须还够 1600，剩下的零头才交给 targetSize
        listOf(2000 to 1500, 4032 to 3024, 8000 to 6000).forEach { (w, h) ->
            val s = ImageBudget.sampleSize(w, h)
            assertTrue(maxOf(w, h) / s >= ImageBudget.MAX_EDGE)
        }
    }

    @Test fun `degenerate sizes fall back to no downsampling`() {
        assertEquals(1, ImageBudget.sampleSize(0, 0))
        assertEquals(1, ImageBudget.sampleSize(-1, 100))
    }

    @Test fun `target size caps the long edge and keeps the ratio`() {
        val (w, h) = ImageBudget.targetSize(4000, 3000)
        assertEquals(ImageBudget.MAX_EDGE, w)
        assertEquals(1200, h)
        assertEquals(4.0 / 3.0, w.toDouble() / h, 0.01)
    }

    @Test fun `portrait photos are capped on their own long edge`() {
        val (w, h) = ImageBudget.targetSize(3000, 4000)
        assertEquals(ImageBudget.MAX_EDGE, h)
        assertEquals(1200, w)
    }

    @Test fun `small images are never upscaled`() {
        assertEquals(800 to 600, ImageBudget.targetSize(800, 600))
        assertEquals(1600 to 900, ImageBudget.targetSize(1600, 900))
    }

    @Test fun `an extreme aspect ratio still yields at least one pixel`() {
        val (w, h) = ImageBudget.targetSize(20000, 10)
        assertEquals(ImageBudget.MAX_EDGE, w)
        assertTrue(h >= 1)
    }

    @Test fun `quality stays in a range that keeps small print legible`() {
        assertTrue(ImageBudget.QUALITY in 70..90)
    }
}
