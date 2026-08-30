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

    override suspend fun complete(
        system: String,
        user: String,
        images: List<Image>,
        maxTokens: Int,
        temperature: Double,
        role: Role,
    ): String {
        systems += system
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
