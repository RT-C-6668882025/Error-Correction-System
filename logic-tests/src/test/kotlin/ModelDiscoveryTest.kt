import com.ecs.core.model.BuiltInEndpoints
import com.ecs.core.model.ModelCatalog
import com.ecs.core.model.ModelDiscovery
import com.ecs.core.model.Protocol
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

class ModelsUrlTest {

    @Test fun `bare domain gets the whole path`() {
        assertEquals(
            "https://x.com/v1/models",
            ModelDiscovery.modelsUrl("x.com", Protocol.OPENAI),
        )
    }

    @Test fun `trailing slash and version segment`() {
        assertEquals(
            "https://x.com/v1/models",
            ModelDiscovery.modelsUrl("https://x.com/v1/", Protocol.OPENAI),
        )
    }

    @Test fun `a non-v1 version segment is kept`() {
        // 智谱：/api/paas/v4 已经是版本段，只补方法路径
        assertEquals(
            "https://open.bigmodel.cn/api/paas/v4/models",
            ModelDiscovery.modelsUrl("https://open.bigmodel.cn/api/paas/v4", Protocol.OPENAI),
        )
    }

    @Test fun `pasting the chat endpoint still resolves to models`() {
        assertEquals(
            "https://x.com/v1/models",
            ModelDiscovery.modelsUrl("https://x.com/v1/chat/completions", Protocol.OPENAI),
        )
        assertEquals(
            "https://api.anthropic.com/v1/models",
            ModelDiscovery.modelsUrl("https://api.anthropic.com/v1/messages", Protocol.ANTHROPIC),
        )
    }

    @Test fun `anthropic base url`() {
        assertEquals(
            "https://api.anthropic.com/v1/models",
            ModelDiscovery.modelsUrl("https://api.anthropic.com", Protocol.ANTHROPIC),
        )
    }

    @Test fun `every built-in endpoint resolves to something usable`() {
        BuiltInEndpoints.ALL.forEach { ep ->
            val url = ModelDiscovery.modelsUrl(ep.baseUrl, ep.protocol)
            assertTrue(url.endsWith("/models"), "${ep.id} → $url")
            assertTrue(url.startsWith("http"), "${ep.id} → $url")
        }
    }

    @Test fun `blank stays blank`() {
        assertEquals("", ModelDiscovery.modelsUrl("  ", Protocol.OPENAI))
    }
}

class ModelParseTest {

    private val openAiBody = """
        {"object":"list","data":[
          {"id":"glm-4.6v-flash","object":"model"},
          {"id":"glm-4-plus","object":"model"},
          {"id":"embedding-3","object":"model"},
          {"id":"cogview-4","object":"model"},
          {"id":"glm-4.6v-flash","object":"model"}
        ]}
    """.trimIndent()

    private val anthropicBody = """
        {"data":[
          {"type":"model","id":"claude-opus-5","display_name":"Claude Opus 5"},
          {"type":"model","id":"claude-haiku-4-5","display_name":"Claude Haiku 4.5"}
        ],"has_more":false}
    """.trimIndent()

    @Test fun `openai list drops non-chat models and duplicates`() {
        val models = ModelDiscovery.parse(openAiBody)
        assertEquals(listOf("glm-4.6v-flash", "glm-4-plus"), models.map { it.id })
    }

    @Test fun `catalog knowledge fills in label and note`() {
        val flash = ModelDiscovery.parse(openAiBody).first()
        assertEquals(ModelCatalog.byId("glm-4.6v-flash")!!.label, flash.label)
        assertTrue(flash.vision)
    }

    @Test fun `anthropic display name wins`() {
        val models = ModelDiscovery.parse(anthropicBody)
        assertEquals(listOf("Claude Opus 5", "Claude Haiku 4.5"), models.map { it.label })
        assertTrue(models.all { it.vision })
    }

    @Test fun `bare array and models key are both accepted`() {
        assertEquals(listOf("kimi-k2-turbo-preview"), ModelDiscovery.parse("""["kimi-k2-turbo-preview"]""").map { it.id })
        assertEquals(
            listOf("llama3.1"),
            ModelDiscovery.parse("""{"models":[{"name":"llama3.1"}]}""").map { it.id },
        )
    }

    @Test fun `garbage parses to empty instead of throwing`() {
        assertTrue(ModelDiscovery.parse("<html>404</html>").isEmpty())
        assertTrue(ModelDiscovery.parse("").isEmpty())
        assertTrue(ModelDiscovery.parse("""{"error":{"message":"invalid key"}}""").isEmpty())
    }
}

class VisionDetectionTest {

    @Test fun `catalog wins over guessing`() {
        assertFalse(ModelDiscovery.isVision("glm-4-plus"))
        assertTrue(ModelDiscovery.isVision("moonshot-v1-8k-vision-preview"))
    }

    @Test fun `unknown ids are judged by their name`() {
        assertTrue(ModelDiscovery.isVision("qwen2-vl-7b"))
        assertTrue(ModelDiscovery.isVision("glm-4v-plus"))
        assertTrue(ModelDiscovery.isVision("some-omni-model"))
        assertTrue(ModelDiscovery.isVision("minicpm-ocr"))
        assertFalse(ModelDiscovery.isVision("qwen2.5-72b-instruct"))
        assertFalse(ModelDiscovery.isVision("deepseek-v4-flash"))
    }

    @Test fun `embeddings and friends are not chat models`() {
        listOf("text-embedding-3-large", "bge-reranker", "whisper-1", "cosyvoice-tts", "gpt-image-1")
            .forEach { assertFalse(ModelDiscovery.isChatModel(it), it) }
        assertTrue(ModelDiscovery.isChatModel("glm-4-plus"))
    }
}

class ModelPickTest {

    private fun models(vararg ids: String) = ids.map {
        ModelDiscovery.RemoteModel(it, it, ModelDiscovery.isVision(it))
    }

    @Test fun `catalog order decides among known models`() {
        val picked = ModelDiscovery.pick(models("glm-4-plus", "glm-4.6v-flash", "glm-ocr"), vision = true)
        // 视觉档默认挑免费的 flash：录入频率是系统生命线
        assertEquals("glm-4.6v-flash", picked?.id)
    }

    @Test fun `text slot prefers the strong one`() {
        val picked = ModelDiscovery.pick(models("deepseek-v4-flash", "deepseek-v4-pro"), vision = false)
        assertEquals("deepseek-v4-flash", picked?.id)  // 清单顺序即推荐顺序
    }

    @Test fun `unknown ids fall back to naming heuristics`() {
        val vision = ModelDiscovery.pick(models("house-vl-max", "house-vl-flash"), vision = true)
        assertEquals("house-vl-flash", vision?.id)

        val text = ModelDiscovery.pick(models("house-air", "house-max"), vision = false)
        assertEquals("house-max", text?.id)
    }

    @Test fun `a text-only provider yields no vision model`() {
        assertNull(ModelDiscovery.pick(models("deepseek-v4-flash", "deepseek-v4-pro"), vision = true))
    }

    @Test fun `empty list picks nothing`() {
        assertNull(ModelDiscovery.pick(emptyList(), vision = false))
        assertNull(ModelDiscovery.pick(emptyList(), vision = true))
    }

    @Test fun `text slot may use a vision model when that is all there is`() {
        val picked = ModelDiscovery.pick(models("glm-4.6v-flash"), vision = false)
        assertEquals("glm-4.6v-flash", picked?.id)
    }
}

/**
 * 拉到清单之后，内置候选还在不在。
 *
 * 智谱的 /models 只回文本档，视觉档（glm-4v 系列）不在里面。原来「拉到了就整份换掉」，
 * 于是拉一次清单之后识别那一行直接写「这个厂商没有能看图的模型」。
 */
class ModelMergeTest {

    private fun remote(vararg ids: String) =
        ids.map { ModelDiscovery.RemoteModel(it, it, ModelDiscovery.isVision(it)) }

    private val zhipuBuiltIn = ModelCatalog.MODELS
        .filter { it.endpointId == BuiltInEndpoints.ZHIPU }
        .map { ModelDiscovery.RemoteModel(it.id, it.label, it.vision, it.note) }

    @Test fun `a text only listing keeps the built-in vision models`() {
        val merged = ModelDiscovery.merge(remote("glm-4-plus", "glm-4-air", "glm-4-flash"), zhipuBuiltIn)
        assertTrue(merged.any { it.vision }, "拉完清单反而没有视觉模型可选了")
        assertTrue(merged.map { it.id }.contains(ModelCatalog.DEFAULT_VISION))
        assertNotNull(ModelDiscovery.pick(merged, vision = true))
    }

    @Test fun `what the vendor returned comes first and is not duplicated`() {
        val merged = ModelDiscovery.merge(remote("glm-4-plus", ModelCatalog.DEFAULT_VISION), zhipuBuiltIn)
        assertEquals("glm-4-plus", merged.first().id)
        assertEquals(merged.size, merged.map { it.id }.distinct().size)
        // 厂商回过的那条保持原样，不该被标成内置候选
        assertEquals("", merged.first { it.id == ModelCatalog.DEFAULT_VISION }.note)
    }

    @Test fun `models the vendor never listed say so`() {
        val merged = ModelDiscovery.merge(remote("glm-4-air"), zhipuBuiltIn)
        assertEquals(ModelDiscovery.SUGGESTED_NOTE, merged.first { it.id == "glm-4-plus" }.note)
    }

    @Test fun `an empty listing falls back to the built-ins untouched`() {
        assertEquals(zhipuBuiltIn, ModelDiscovery.merge(emptyList(), zhipuBuiltIn))
    }

    @Test fun `zhipu vision ids are recognised by name`() {
        listOf("glm-4v", "glm-4v-flash", "glm-4v-plus-0111", "glm-4.5v", "glm-4.6v-flash", "glm-4.1v-thinking-flash")
            .forEach { assertTrue(ModelDiscovery.isVision(it), it) }
    }
}
