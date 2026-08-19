package com.attuchengmen.agent.model.providers

import com.attuchengmen.agent.message.UserMessage
import com.attuchengmen.agent.model.ModelRequest
import com.attuchengmen.agent.model.ModelResponse
import org.junit.jupiter.api.Assumptions.assumeTrue
import java.net.URI
import java.time.Duration
import kotlin.test.Test
import kotlin.test.assertIs
import kotlin.test.assertTrue
import kotlinx.coroutines.runBlocking

/** 仅在调用者显式提供 DeepSeek 凭据和模型时运行的真实 API 冒烟测试。 */
class DeepSeekLiveTest {
    @Test
    fun `real DeepSeek returns an answer`() {
        val apiKey = System.getenv("DEEPSEEK_API_KEY")
        val model = System.getenv("DEEPSEEK_MODEL")
        assumeTrue(!apiKey.isNullOrBlank() && !model.isNullOrBlank())
        val baseUri = URI(System.getenv("DEEPSEEK_BASE_URL") ?: "https://api.deepseek.com")
        val adapter = DeepSeekAdapter(
            DeepSeekConfig(
                apiKey = apiKey,
                model = model,
                baseUri = baseUri,
                connectTimeout = Duration.ofSeconds(10),
                requestTimeout = Duration.ofSeconds(60),
            ),
        )

        val response = runBlocking { adapter.generate(
            ModelRequest(
                messages = listOf(UserMessage("Reply with a short greeting.")),
                tools = emptyList(),
            ),
        ) }

        assertTrue(assertIs<ModelResponse.Answer>(response).message.content.isNotBlank())
    }
}
