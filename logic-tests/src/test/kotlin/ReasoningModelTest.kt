import com.ecs.agent.AgentClient
import com.ecs.agent.Analyzer
import com.ecs.agent.PromptProvider
import com.ecs.core.model.ApiEndpoint
import com.ecs.core.model.Protocol
import kotlinx.coroutines.runBlocking
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertTrue

private fun offline() = AgentClient {
    AgentClient.Config(ApiEndpoint("x", "x", "https://x.com", Protocol.OPENAI), "m")
}

/** 按脚本依次返回；用完一直返回最后一条。记下每次的 system。 */
private class Replies(private vararg val replies: String) :
    AgentClient({ AgentClient.Config(ApiEndpoint("x", "x", "https://x.com", Protocol.OPENAI), "m") }) {

    val systems = mutableListOf<String>()
    val budgets = mutableListOf<Int>()

    override suspend fun complete(
        system: String,
        user: String,
        images: List<Image>,
        maxTokens: Int,
        temperature: Double,
        role: Role,
    ): String {
        systems += system
        budgets += maxTokens
        return replies[minOf(systems.size - 1, replies.size - 1)]
    }
}

class ReasoningResponseTest {

    private val client = offline()

    @Test fun `an empty content falls back to the reasoning field`() {
        // DeepSeek 的 pro 档、各家 thinking 系列都是这个形状：content 空，正文在思考里
        assertEquals(
            """{"a":1}""",
            client.extractText(
                """{"choices":[{"message":{"content":"","reasoning_content":"{\"a\":1}"}}]}""",
                Protocol.OPENAI,
            ),
        )
        assertEquals(
            """{"a":2}""",
            client.extractText(
                """{"choices":[{"message":{"content":null,"reasoning":"{\"a\":2}"}}]}""",
                Protocol.OPENAI,
            ),
        )
    }

    @Test fun `content still wins when both are present`() {
        assertEquals(
            "正文",
            client.extractText(
                """{"choices":[{"message":{"content":"正文","reasoning_content":"想了很久"}}]}""",
                Protocol.OPENAI,
            ),
        )
    }

    @Test fun `no body at all says what to do instead of leaking into the json parser`() {
        val e = assertFailsWith<AgentClient.AgentException> {
            client.extractText("""{"choices":[{"message":{"content":""}}]}""", Protocol.OPENAI)
        }
        assertEquals(AgentClient.NO_BODY, e.message)
        // 关键：这条报错不能再表现成「响应中没有 JSON」
        assertTrue(e.message!!.contains("max_tokens"))
        assertTrue(e.message!!.contains("模型"))
    }

    @Test fun `an anthropic response of only blank text says the same thing`() {
        val e = assertFailsWith<AgentClient.AgentException> {
            client.extractText("""{"content":[{"type":"text","text":"   "}]}""", Protocol.ANTHROPIC)
        }
        assertEquals(AgentClient.NO_BODY, e.message)
    }
}

class AnalyzeRetryTest {

    private val good = """{"branch":"词法/名词","form":"名词复数","basis":"空前有 the，空后接介词短语","context":""}"""
    private val input = Analyzer.Input("The ___ of AI has changed everything.", "develop", "development")

    @Test fun `a response with no json is retried instead of failing the record`() {
        val client = Replies("嗯，让我想想……这个空应该填名词。", good)
        val out = runBlocking { Analyzer(client, PromptProvider.DEFAULT).analyze(input) }
        assertEquals("词法/名词", out.branch)
        assertEquals(2, client.systems.size)
        // 第二次要说清上一次错在哪，否则它多半原样再来一遍
        assertTrue(client.systems[1].contains("只输出那一个 JSON"))
    }

    @Test fun `two unparseable answers still surface the real reason`() {
        val client = Replies("我先分析一下这道题的结构。")
        val e = assertFailsWith<AgentClient.AgentException> {
            runBlocking { Analyzer(client, PromptProvider.DEFAULT).analyze(input) }
        }
        assertTrue(e.message!!.contains("没有 JSON"), "报错被吞了：${e.message}")
        assertEquals(2, client.systems.size)
    }

    @Test fun `the token budget leaves room for models that think first`() {
        assertTrue(Analyzer.MAX_TOKENS >= 3000, "推理档会把 1024 烧光，JSON 还没开头就被截断")
    }
}

class TruncatedResponseTest {

    private val client = offline()

    /** 截断的思考里没有 JSON：这正是「响应中没有 JSON + 一大段思考」那条报错的来源。 */
    private val cutOff = """{"choices":[{"finish_reason":"length","message":{"content":"",""" +
        """"reasoning_content":"我们分析题干：需要填三个空。第一个空后是 man 名词，前面"}}]}"""

    @Test fun `a thought cut off by the token budget says so instead of leaking into the parser`() {
        val e = assertFailsWith<AgentClient.AgentException> {
            client.extractText(cutOff, Protocol.OPENAI)
        }
        assertEquals(AgentClient.TRUNCATED, e.message)
        // 这条报错以前长成「响应中没有 JSON：我们分析题干……」，看不出该改什么
        assertTrue(!e.message!!.contains("没有 JSON"))
        assertTrue(e.message!!.contains("截断"))
    }

    @Test fun `a truncated thought that does carry the json still counts`() {
        val raw = """{"choices":[{"finish_reason":"length","message":{"content":"",""" +
            """"reasoning_content":"想了想，答案是 {\"a\":1}"}}]}"""
        assertTrue(client.extractText(raw, Protocol.OPENAI).contains("{"))
    }

    @Test fun `a complete thought without json is still handed over as before`() {
        // 没被截断说明模型是「说完了但没按格式说」，上层重试一次就好，不该报成截断
        val raw = """{"choices":[{"finish_reason":"stop","message":{"content":"",""" +
            """"reasoning_content":"这个空填名词。"}}]}"""
        assertEquals("这个空填名词。", client.extractText(raw, Protocol.OPENAI))
    }

    @Test fun `anthropic reports the same truncation`() {
        val e = assertFailsWith<AgentClient.AgentException> {
            client.extractText("""{"stop_reason":"max_tokens","content":[]}""", Protocol.ANTHROPIC)
        }
        assertEquals(AgentClient.TRUNCATED, e.message)
    }
}

class RetryBudgetTest {

    private val good = """{"branch":"词法/名词","form":"名词复数","basis":"空前有 the，空后接介词短语","context":""}"""

    @Test fun `the retry asks for a bigger budget than the one that got cut off`() {
        val client = Replies("思考被掐断了，没有 JSON", good)
        runBlocking {
            Analyzer(client, PromptProvider.DEFAULT)
                .analyze(Analyzer.Input("___ man playing ___ guitar just celebrated his ___ fortieth birthday.", null, null))
        }
        assertEquals(listOf(Analyzer.MAX_TOKENS, Analyzer.RETRY_MAX_TOKENS), client.budgets)
        assertTrue(Analyzer.RETRY_MAX_TOKENS > Analyzer.MAX_TOKENS)
    }
}
