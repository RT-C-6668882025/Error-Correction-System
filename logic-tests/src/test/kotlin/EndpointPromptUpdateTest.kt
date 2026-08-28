import com.ecs.agent.AgentClient
import com.ecs.core.model.ApiEndpoint
import com.ecs.core.model.BuiltInEndpoints
import com.ecs.core.model.ModelCatalog
import com.ecs.core.model.Protocol
import com.ecs.core.model.ROLE_TEXT
import com.ecs.core.model.ROLE_VISION
import com.ecs.core.model.activeEndpoints
import com.ecs.core.model.normalizeUrl
import com.ecs.core.prompt.PromptSlot
import com.ecs.core.rules.Validation
import com.ecs.core.update.UpdateCheck
import kotlin.test.*

class NormalizeUrlTest {

    @Test fun `bare domain gets the whole openai path`() {
        assertEquals(
            "https://x.com/v1/chat/completions",
            normalizeUrl("https://x.com", Protocol.OPENAI),
        )
    }

    @Test fun `trailing slash is not doubled`() {
        assertEquals(
            "https://x.com/v1/chat/completions",
            normalizeUrl("https://x.com/", Protocol.OPENAI),
        )
    }

    @Test fun `a version segment only needs the method path`() {
        assertEquals(
            "https://x.com/v1/chat/completions",
            normalizeUrl("https://x.com/v1", Protocol.OPENAI),
        )
    }

    @Test fun `a full path is left alone`() {
        val full = "https://x.com/v1/chat/completions"
        assertEquals(full, normalizeUrl(full, Protocol.OPENAI))
    }

    @Test fun `zhipu style versioned base`() {
        assertEquals(
            "https://open.bigmodel.cn/api/paas/v4/chat/completions",
            normalizeUrl("https://open.bigmodel.cn/api/paas/v4", Protocol.OPENAI),
        )
    }

    @Test fun `anthropic protocol takes a different tail`() {
        assertEquals(
            "https://api.anthropic.com/v1/messages",
            normalizeUrl("https://api.anthropic.com", Protocol.ANTHROPIC),
        )
        assertEquals(
            "https://relay.example.com/v1/messages",
            normalizeUrl("https://relay.example.com/v1", Protocol.ANTHROPIC),
        )
    }

    @Test fun `an anthropic full path is left alone`() {
        val full = "https://relay.example.com/v1/messages"
        assertEquals(full, normalizeUrl(full, Protocol.ANTHROPIC))
    }

    @Test fun `scheme is added when the user omits it`() {
        assertEquals(
            "https://x.com/v1/chat/completions",
            normalizeUrl("x.com", Protocol.OPENAI),
        )
    }

    @Test fun `http is preserved for a self-hosted relay`() {
        assertEquals(
            "http://192.168.1.9:3000/v1/chat/completions",
            normalizeUrl("http://192.168.1.9:3000", Protocol.OPENAI),
        )
    }

    @Test fun `query and fragment are dropped`() {
        assertEquals(
            "https://x.com/v1/chat/completions",
            normalizeUrl("https://x.com/v1?key=abc#note", Protocol.OPENAI),
        )
    }

    @Test fun `a relay with a path prefix keeps it`() {
        assertEquals(
            "https://gw.example.com/openai/v1/chat/completions",
            normalizeUrl("https://gw.example.com/openai/v1", Protocol.OPENAI),
        )
    }

    @Test fun `blank stays blank rather than becoming a bogus url`() {
        assertEquals("", normalizeUrl("   ", Protocol.OPENAI))
    }

    @Test fun `same base under the two protocols diverges`() {
        val base = "https://relay.example.com"
        assertNotEquals(
            normalizeUrl(base, Protocol.OPENAI),
            normalizeUrl(base, Protocol.ANTHROPIC),
        )
    }
}

class EndpointTest {
    @Test fun `presets cover the four vendors and carry a protocol each`() {
        val ids = BuiltInEndpoints.ALL.map { it.id }
        assertTrue(ids.containsAll(listOf("anthropic", "zhipu", "moonshot", "minimax")))
        assertEquals(Protocol.ANTHROPIC, BuiltInEndpoints.byId("anthropic")!!.protocol)
        assertEquals(Protocol.OPENAI, BuiltInEndpoints.byId("zhipu")!!.protocol)
    }

    @Test fun `merge keeps user edits to a preset and appends customs`() {
        val editedPreset = BuiltInEndpoints.byId("zhipu")!!
            .copy(baseUrl = "https://my-relay.com/v1", apiKey = "k")
        val custom = ApiEndpoint("custom_1", "某中转站", "https://relay.cn", Protocol.OPENAI, "k2")

        val merged = BuiltInEndpoints.merge(listOf(editedPreset, custom))

        val zhipu = merged.first { it.id == "zhipu" }
        assertEquals("https://my-relay.com/v1", zhipu.baseUrl)
        assertEquals("k", zhipu.apiKey)
        assertTrue(zhipu.builtIn)
        assertEquals(BuiltInEndpoints.ALL.size + 1, merged.size)
        assertFalse(merged.first { it.id == "custom_1" }.builtIn)
    }

    @Test fun `merge surfaces presets a stored list has never seen`() {
        assertEquals(BuiltInEndpoints.ALL.size, BuiltInEndpoints.merge(emptyList()).size)
    }

    @Test fun `configured needs both a url and a key`() {
        val ep = ApiEndpoint("x", "X", "https://x.com", Protocol.OPENAI)
        assertFalse(ep.configured)
        assertTrue(ep.copy(apiKey = "k").configured)
        assertFalse(ep.copy(apiKey = "k", baseUrl = "").configured)
    }

    @Test fun `model suggestions are scoped to the endpoint and capability`() {
        val visionOnZhipu = ModelCatalog.suggestionsFor(BuiltInEndpoints.ZHIPU, vision = true)
        assertTrue(visionOnZhipu.isNotEmpty())
        assertTrue(visionOnZhipu.all { it.vision })
        assertTrue(visionOnZhipu.all { it.endpointId == BuiltInEndpoints.ZHIPU })
        assertTrue(ModelCatalog.suggestionsFor("nonexistent", vision = false).isEmpty())
    }

    @Test fun `deepseek and a lan endpoint are both present`() {
        val ds = assertNotNull(BuiltInEndpoints.byId(BuiltInEndpoints.DEEPSEEK))
        assertEquals(Protocol.OPENAI, ds.protocol)
        assertEquals("https://api.deepseek.com/v1/chat/completions", ds.url)

        val lan = assertNotNull(BuiltInEndpoints.byId(BuiltInEndpoints.LOCAL))
        // 局域网自部署走 http，不能被强行改成 https，否则 Ollama 连不上
        assertTrue(lan.url.startsWith("http://"))
        assertTrue(lan.url.endsWith("/chat/completions"))
    }

    @Test fun `deepseek text models are suggested, glm-ocr is a vision one`() {
        val ds = ModelCatalog.suggestionsFor(BuiltInEndpoints.DEEPSEEK, vision = false).map { it.id }
        assertTrue(ds.containsAll(listOf("deepseek-v4-flash", "deepseek-v4-pro")))
        assertTrue(ModelCatalog.suggestionsFor(BuiltInEndpoints.DEEPSEEK, vision = true).isEmpty())

        val ocr = assertNotNull(ModelCatalog.byId("glm-ocr"))
        assertTrue(ocr.vision)
    }

    @Test fun `retired deepseek ids are gone from the catalog`() {
        // 2026-07-24 之后这两个 ID 请求直接报错，清单里不能再出现
        assertNull(ModelCatalog.byId("deepseek-chat"))
        assertNull(ModelCatalog.byId("deepseek-reasoner"))
    }

    @Test fun `retired model ids are canonicalized, custom ones are left alone`() {
        assertEquals("deepseek-v4-flash", ModelCatalog.canonical("deepseek-chat"))
        assertEquals("deepseek-v4-pro", ModelCatalog.canonical("deepseek-reasoner"))
        // 用户自己填的 ID 不归我管，原样返回
        assertEquals("my-relay/some-model", ModelCatalog.canonical("my-relay/some-model"))
        assertEquals(ModelCatalog.DEFAULT_VISION, ModelCatalog.canonical(ModelCatalog.DEFAULT_VISION))
    }

    @Test fun `every renamed target actually exists`() {
        listOf("deepseek-chat", "deepseek-reasoner").forEach {
            assertNotNull(ModelCatalog.byId(ModelCatalog.canonical(it)))
        }
    }

    @Test fun `no two models share an id`() {
        val ids = ModelCatalog.MODELS.map { it.id }
        assertEquals(ids.size, ids.distinct().size)
    }

    @Test fun `defaults point at real presets`() {
        assertNotNull(BuiltInEndpoints.byId(ModelCatalog.DEFAULT_TEXT_ENDPOINT))
        assertNotNull(BuiltInEndpoints.byId(ModelCatalog.DEFAULT_VISION_ENDPOINT))
    }
}

class ActiveEndpointsTest {

    private val zhipu = BuiltInEndpoints.byId("zhipu")!!.copy(apiKey = "k1")
    private val anthropic = BuiltInEndpoints.byId("anthropic")!!
    private val all = listOf(zhipu, anthropic)

    @Test fun `two slots on different endpoints, vision first`() {
        val active = activeEndpoints(all, visionId = "zhipu", textId = "anthropic")
        assertEquals(listOf("zhipu", "anthropic"), active.map { it.endpoint.id })
        assertEquals(listOf(ROLE_VISION), active[0].roles)
        assertEquals(listOf(ROLE_TEXT), active[1].roles)
    }

    @Test fun `one endpoint serving both slots collapses into a single row`() {
        val active = activeEndpoints(all, visionId = "zhipu", textId = "zhipu")
        assertEquals(1, active.size)
        assertEquals(listOf(ROLE_VISION, ROLE_TEXT), active.single().roles)
        assertEquals("视觉 / 文本", active.single().label)
    }

    @Test fun `a selection pointing at a deleted endpoint is skipped, not crashed on`() {
        val active = activeEndpoints(all, visionId = "gone", textId = "anthropic")
        assertEquals(listOf("anthropic"), active.map { it.endpoint.id })
        assertTrue(activeEndpoints(all, "gone", "also-gone").isEmpty())
        assertTrue(activeEndpoints(emptyList(), "zhipu", "anthropic").isEmpty())
    }

    @Test fun `configured tracks the underlying key`() {
        val active = activeEndpoints(all, visionId = "zhipu", textId = "anthropic")
        assertTrue(active.first { it.endpoint.id == "zhipu" }.configured)
        assertFalse(active.first { it.endpoint.id == "anthropic" }.configured)
    }

    @Test fun `filling a key flips the slot to configured`() {
        val before = activeEndpoints(all, "anthropic", "anthropic").single()
        assertFalse(before.configured)
        val after = activeEndpoints(
            listOf(zhipu, anthropic.copy(apiKey = "k2")), "anthropic", "anthropic",
        ).single()
        assertTrue(after.configured)
    }

    @Test fun `the defaults ship unconfigured so the prompt actually fires`() {
        // 预置项默认没有 Key——首页与设置页的「还没填」提示要靠这一点
        assertTrue(BuiltInEndpoints.ALL.none { it.configured })
        val active = activeEndpoints(
            BuiltInEndpoints.ALL,
            ModelCatalog.DEFAULT_VISION_ENDPOINT,
            ModelCatalog.DEFAULT_TEXT_ENDPOINT,
        )
        assertEquals(2, active.size)
        assertTrue(active.all { !it.configured })
    }
}

class ProtocolBranchTest {
    private val client = AgentClient {
        AgentClient.Config(BuiltInEndpoints.ALL.first(), ModelCatalog.DEFAULT_TEXT)
    }

    @Test fun `each protocol reads its own response shape`() {
        assertEquals(
            """{"a":1}""",
            client.extractText("""{"content":[{"type":"text","text":"{\"a\":1}"}]}""", Protocol.ANTHROPIC),
        )
        assertEquals(
            """{"a":2}""",
            client.extractText("""{"choices":[{"message":{"content":"{\"a\":2}"}}]}""", Protocol.OPENAI),
        )
    }

    @Test fun `a shape from the wrong protocol raises rather than returning empty`() {
        assertFailsWith<AgentClient.AgentException> {
            client.extractText("""{"choices":[{"message":{"content":"x"}}]}""", Protocol.ANTHROPIC)
        }
        assertFailsWith<AgentClient.AgentException> {
            client.extractText("""{"content":[{"type":"text","text":"x"}]}""", Protocol.OPENAI)
        }
    }

    @Test fun `the openai temperature floor is above zero`() {
        assertTrue(ModelCatalog.OPENAI_MIN_TEMPERATURE > 0.0)
        assertEquals(
            ModelCatalog.OPENAI_MIN_TEMPERATURE,
            0.0.coerceAtLeast(ModelCatalog.OPENAI_MIN_TEMPERATURE),
        )
    }
}

class PromptCatalogTest {

    @Test fun `every slot has a body and a contract`() {
        PromptSlot.entries.forEach { slot ->
            assertTrue(slot.body.isNotBlank(), "${slot.name} body")
            assertTrue(slot.contract.isNotBlank(), "${slot.name} contract")
            assertTrue(slot.title.isNotBlank())
            assertTrue(slot.description.isNotBlank())
        }
    }

    @Test fun `render appends the contract to the body`() {
        val out = PromptSlot.REVIEW.render(null)
        assertTrue(out.startsWith(PromptSlot.REVIEW.body.trim()))
        assertTrue(out.endsWith(PromptSlot.REVIEW.contract.trim()))
    }

    @Test fun `an override replaces only the body`() {
        val out = PromptSlot.ANNOTATE.render("只写一句话。")
        assertTrue(out.startsWith("只写一句话。"))
        assertFalse(out.contains(PromptSlot.ANNOTATE.body.trim()))
        assertTrue(out.endsWith(PromptSlot.ANNOTATE.contract.trim()))
    }

    @Test fun `clearing the body still leaves the contract intact`() {
        PromptSlot.entries.forEach { slot ->
            val out = slot.render("")
            assertEquals(slot.contract.trim(), out, "${slot.name} lost its contract")
        }
    }

    @Test fun `the output shape survives any override`() {
        // 用户把正文清空也毁不掉解析：约束都在契约里
        assertTrue(PromptSlot.ANNOTATE.render("").contains("\"choice\""))
        assertTrue(PromptSlot.ANNOTATE.render("随便写").contains("form_context"))
        assertTrue(PromptSlot.SCAN.render("").contains("correct_letter"))
    }

    @Test fun `the eye banned list is carried into the annotate contract`() {
        val contract = PromptSlot.ANNOTATE.contract
        Validation.EYE_BANNED.forEach { assertTrue(contract.contains(it), "missing $it") }
    }

    @Test fun `the choice slot locks the option rules`() {
        val contract = PromptSlot.ANNOTATE_CHOICE.contract
        assertTrue(contract.contains("not_form"))
        assertTrue(contract.contains("选项"))
    }

    @Test fun `the review slot carries the style ban list`() {
        assertTrue(PromptSlot.REVIEW.contract.contains("综上所述"))
        assertTrue(PromptSlot.REVIEW.contract.contains("题号"))
    }

    @Test fun `slots for deleted tasks are gone`() {
        listOf("VERIFY", "TREE_MAINTAIN", "REPORT_MICRO", "REPORT_MACRO").forEach {
            assertNull(PromptSlot.fromName(it), "$it 还在")
        }
    }

    @Test fun `scan slot keeps the no-handwriting and choice rules`() {
        val contract = PromptSlot.SCAN.contract
        assertTrue(contract.contains("手写"))
        assertTrue(contract.contains("correct_letter"))
        assertTrue(contract.contains("confidence"))
    }

    @Test fun `modified only counts a real difference`() {
        assertFalse(PromptSlot.REVIEW.modified(null))
        assertFalse(PromptSlot.REVIEW.modified(PromptSlot.REVIEW.body))
        assertFalse(PromptSlot.REVIEW.modified("  ${PromptSlot.REVIEW.body}  "))
        assertTrue(PromptSlot.REVIEW.modified("别的写法"))
    }

    @Test fun `slot names round trip`() {
        PromptSlot.entries.forEach {
            assertEquals(it, PromptSlot.fromName(it.name))
        }
        assertNull(PromptSlot.fromName("NOPE"))
    }
}

class UpdateCheckTest {

    @Test fun `v prefix is ignored`() {
        assertEquals(0, UpdateCheck.compareVersions("v2.1.0", "2.1.0"))
    }

    @Test fun `missing patch counts as zero`() {
        assertEquals(0, UpdateCheck.compareVersions("2.1", "2.1.0"))
    }

    @Test fun `numeric segments compare as numbers not strings`() {
        assertTrue(UpdateCheck.compareVersions("2.10.0", "2.9.0") > 0)
        assertTrue(UpdateCheck.compareVersions("2.9.0", "2.10.0") < 0)
    }

    @Test fun `a prerelease sorts below its release`() {
        assertTrue(UpdateCheck.compareVersions("2.2.0-beta1", "2.2.0") < 0)
        assertTrue(UpdateCheck.compareVersions("2.2.0", "2.2.0-beta1") > 0)
    }

    @Test fun `isNewer is the whole point`() {
        assertTrue(UpdateCheck.isNewer(installed = "2.1.0", latest = "v2.3.0"))
        assertFalse(UpdateCheck.isNewer(installed = "2.3.0", latest = "v2.3.0"))
        assertFalse(UpdateCheck.isNewer(installed = "2.4.0", latest = "v2.3.0"))
    }

    @Test fun `garbage versions do not crash the check`() {
        assertFalse(UpdateCheck.isNewer("未知", "v2.3.0") && false)
        UpdateCheck.compareVersions("未知", "v2.3.0")
    }

    @Test fun `the apk asset is picked out of several`() {
        val body = """
            {
              "tag_name": "v2.3.0",
              "name": "v2.3.0",
              "body": "changelog here",
              "html_url": "https://github.com/o/r/releases/tag/v2.3.0",
              "assets": [
                {"name": "source.zip", "browser_download_url": "https://x/source.zip"},
                {"name": "ecs-2.3.0.apk", "browser_download_url": "https://x/ecs-2.3.0.apk"}
              ]
            }
        """.trimIndent()
        val release = assertNotNull(UpdateCheck.parseLatest(body))
        assertEquals("v2.3.0", release.tagName)
        assertEquals("changelog here", release.notes)
        assertEquals("https://x/ecs-2.3.0.apk", release.apkUrl)
        assertEquals("https://github.com/o/r/releases/tag/v2.3.0", release.pageUrl)
    }

    @Test fun `a release with no apk still parses, with a null link`() {
        val body = """{"tag_name":"v2.3.0","assets":[]}"""
        val release = assertNotNull(UpdateCheck.parseLatest(body))
        assertNull(release.apkUrl)
        assertEquals("v2.3.0", release.title)
    }

    @Test fun `malformed responses return null instead of throwing`() {
        assertNull(UpdateCheck.parseLatest("not json"))
        assertNull(UpdateCheck.parseLatest("{}"))
        assertNull(UpdateCheck.parseLatest("""{"message":"Not Found"}"""))
    }

    @Test fun `the api url points at this repo`() {
        assertTrue(UpdateCheck.LATEST_RELEASE_API.startsWith("https://api.github.com/repos/"))
        assertTrue(UpdateCheck.LATEST_RELEASE_API.endsWith("/releases/latest"))
    }
}
