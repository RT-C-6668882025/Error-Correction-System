import com.ecs.core.export.Zip
import java.io.File
import java.util.zip.ZipFile
import kotlin.test.*

class ZipTest {

    private fun tempDir(name: String): File =
        File(System.getProperty("java.io.tmpdir"), "ecs-zip-$name-${System.nanoTime()}")
            .apply { mkdirs() }

    @Test fun `every file in the export makes it into the zip`() {
        val src = tempDir("src")
        File(src, "README.md").writeText("# 说明")
        File(src, "data.json").writeText("""[{"id":"q_001_1"}]""")
        File(src, "data.csv").writeText("id,paper\nq_001_1,卷A\n")
        File(src, "directions.json").writeText("""[{"scope":"词法/名词"}]""")

        val zip = Zip.zipDir(src, File(tempDir("out"), "export.zip"))

        ZipFile(zip).use { z ->
            val names = z.entries().toList().map { it.name }.toSet()
            assertEquals(setOf("README.md", "data.json", "data.csv", "directions.json"), names)
        }
        src.deleteRecursively()
    }

    @Test fun `entry names are relative - not the whole device path`() {
        // 写绝对路径的话，解压出来会带上一长串 /storage/emulated/0/Android/data/… 空目录
        val src = tempDir("rel")
        File(src, "sub").mkdirs()
        File(src, "sub/data.json").writeText("{}")

        val zip = Zip.zipDir(src, File(tempDir("out2"), "e.zip"))

        ZipFile(zip).use { z ->
            val name = z.entries().toList().single().name
            assertEquals("sub/data.json", name)
            assertFalse(name.startsWith("/"))
            assertFalse(name.contains(src.absolutePath))
        }
        src.deleteRecursively()
    }

    @Test fun `contents survive the round trip byte for byte`() {
        val src = tempDir("round")
        val body = "题干：The ___ of AI has changed everything.\n答案：development\n形态：名词，动词加 -tion 后缀"
        File(src, "data.json").writeText(body)

        val zip = Zip.zipDir(src, File(tempDir("out3"), "e.zip"))

        ZipFile(zip).use { z ->
            val entry = z.getEntry("data.json")
            assertEquals(body, z.getInputStream(entry).readBytes().decodeToString())
        }
        src.deleteRecursively()
    }

    @Test fun `an empty export does not blow up`() {
        val src = tempDir("empty")
        val zip = Zip.zipDir(src, File(tempDir("out4"), "e.zip"))
        assertTrue(zip.exists())
        ZipFile(zip).use { assertEquals(0, it.entries().toList().size) }
        src.deleteRecursively()
    }

    @Test fun `the destination directory is created if missing`() {
        val src = tempDir("mk")
        File(src, "a.txt").writeText("x")
        val dest = File(tempDir("out5"), "nested/deeper/e.zip")
        assertFalse(dest.parentFile.exists())

        Zip.zipDir(src, dest)

        assertTrue(dest.exists())
        src.deleteRecursively()
    }
}
