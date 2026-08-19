package com.attuchengmen.agent

import com.attuchengmen.agent.message.AssistantMessage
import com.attuchengmen.agent.message.ToolCallMessage
import com.attuchengmen.agent.message.ToolResultMessage
import com.attuchengmen.agent.message.UserMessage
import com.attuchengmen.agent.model.LanguageModel
import com.attuchengmen.agent.model.ModelRequest
import com.attuchengmen.agent.model.ModelRequestException
import com.attuchengmen.agent.model.ModelResponse
import com.attuchengmen.agent.model.ModelRetryPolicy
import com.attuchengmen.agent.model.ModelChunk
import com.attuchengmen.agent.model.ToolCall
import com.attuchengmen.agent.model.ToolDefinition
import com.attuchengmen.agent.session.AssistantMessageAdded
import com.attuchengmen.agent.session.ModelRequestPrepared
import com.attuchengmen.agent.session.ModelRetryScheduled
import com.attuchengmen.agent.session.ModelChunkReceived
import com.attuchengmen.agent.session.Session
import com.attuchengmen.agent.session.SessionProjector
import com.attuchengmen.agent.session.StepEnded
import com.attuchengmen.agent.session.StepStarted
import com.attuchengmen.agent.session.TurnEnded
import com.attuchengmen.agent.session.TurnOutcome
import com.attuchengmen.agent.session.TurnStarted
import com.attuchengmen.agent.session.ToolCallRequested
import com.attuchengmen.agent.session.ToolResultAdded
import com.attuchengmen.agent.session.UserMessageAdded
import com.attuchengmen.agent.tool.Tool
import com.attuchengmen.agent.tool.ToolRegistry
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitCancellation
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.yield
import kotlinx.coroutines.flow.flowOf
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import java.time.Duration
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith

/**
 * 阅读顺序 9：验证 Agent 的 Turn、Step 与工具编排行为。
 *
 * RecordingModel 代替网络模型，使测试能够检查准确请求；失败测试证明
 * 异常不会被吞掉，也不会在 Session 中产生虚假回复。
 */
class AgentTest {
    @Test
    fun `stream chunks are logged and assembled into one assistant answer`() = runBlocking {
        val session = Session()
        val model = object : LanguageModel {
            override suspend fun generate(request: ModelRequest): ModelResponse =
                error("stream should be used")

            override fun stream(request: ModelRequest) = flowOf(
                ModelChunk.TextDelta("hel"),
                ModelChunk.TextDelta("lo"),
                ModelChunk.Finished(ModelResponse.Answer(AssistantMessage("hello"))),
            )
        }
        val agent = Agent(session, model, ToolRegistry(), TEST_OPTIONS)

        val reply = agent.submit("greet me")

        assertEquals(AssistantMessage("hello"), reply)
        assertEquals(
            listOf(
                ModelChunkReceived(1, 1, attempt = 1, chunk = ModelChunk.TextDelta("hel")),
                ModelChunkReceived(1, 1, attempt = 1, chunk = ModelChunk.TextDelta("lo")),
                ModelChunkReceived(
                    1,
                    1,
                    attempt = 1,
                    chunk = ModelChunk.Finished(ModelResponse.Answer(AssistantMessage("hello"))),
                ),
            ),
            session.events.filterIsInstance<ModelChunkReceived>(),
        )
        assertEquals(listOf(AssistantMessageAdded("hello")), session.events.filterIsInstance<AssistantMessageAdded>())
    }

    @Test
    fun `retryable model failure retries inside the same step`() = runBlocking {
        val session = Session()
        val model = object : LanguageModel {
            override val retryPolicy = ModelRetryPolicy(
                maxRetries = 2,
                initialDelay = Duration.ofMillis(1),
                maxDelay = Duration.ofMillis(4),
            )
            var requests = 0

            override suspend fun generate(request: ModelRequest): ModelResponse {
                requests += 1
                if (requests == 1) throw ModelRequestException("service unavailable", retryable = true)
                return ModelResponse.Answer(AssistantMessage("recovered"))
            }
        }
        val agent = Agent(session, model, ToolRegistry(), TEST_OPTIONS)

        val reply = agent.submit("hello")

        assertEquals(AssistantMessage("recovered"), reply)
        assertEquals(2, model.requests)
        assertEquals(listOf(1), session.events.filterIsInstance<StepStarted>().map { it.step })
        assertEquals(
            listOf(ModelRetryScheduled(1, 1, retry = 1, delayMillis = 1, failure = "service unavailable")),
            session.events.filterIsInstance<ModelRetryScheduled>(),
        )
        assertEquals(2, session.events.filterIsInstance<ModelRequestPrepared>().size)
    }

    @Test
    fun `retry limit preserves the final model failure`() = runBlocking {
        val session = Session()
        val model = object : LanguageModel {
            override val retryPolicy = ModelRetryPolicy(2, Duration.ofMillis(1), Duration.ofMillis(2))
            var requests = 0

            override suspend fun generate(request: ModelRequest): ModelResponse {
                requests += 1
                throw ModelRequestException("still unavailable", retryable = true)
            }
        }
        val agent = Agent(session, model, ToolRegistry(), TEST_OPTIONS)

        assertFailsWith<ModelRequestException> { agent.submit("hello") }

        assertEquals(3, model.requests)
        assertEquals(2, session.events.filterIsInstance<ModelRetryScheduled>().size)
        assertEquals(
            TurnOutcome.Failed("still unavailable"),
            session.events.filterIsInstance<TurnEnded>().single().outcome,
        )
    }

    @Test
    fun `caller cancellation during backoff prevents another request`() = runBlocking {
        val session = Session()
        val model = object : LanguageModel {
            override val retryPolicy = ModelRetryPolicy(2, Duration.ofMinutes(1), Duration.ofMinutes(1))
            var requests = 0

            override suspend fun generate(request: ModelRequest): ModelResponse {
                requests += 1
                throw ModelRequestException("busy", retryable = true)
            }
        }
        val agent = Agent(session, model, ToolRegistry(), TEST_OPTIONS)
        val submission = async { agent.submit("hello") }
        while (session.events.none { it is ModelRetryScheduled }) yield()

        submission.cancel()
        assertFailsWith<CancellationException> { submission.await() }

        assertEquals(1, model.requests)
        assertEquals(TurnOutcome.Cancelled, session.events.filterIsInstance<TurnEnded>().single().outcome)
    }

    @Test
    fun `turn timeout closes the active step with a distinct terminal outcome`() = runBlocking {
        val session = Session()
        val model = LanguageModel { awaitCancellation() }
        val timeout = Duration.ofMillis(20)
        val agent = Agent(
            session,
            model,
            ToolRegistry(),
            AgentOptions(maxStepsPerTurn = 8, turnTimeout = timeout),
        )

        val failure = assertFailsWith<TurnTimeoutExceededException> {
            agent.submit("hello")
        }

        assertEquals(timeout, failure.timeout)
        assertEquals(
            listOf(
                TurnStarted(turn = 1),
                StepStarted(turn = 1, step = 1),
                UserMessageAdded("hello"),
                ModelRequestPrepared(turn = 1, step = 1, tools = emptyList()),
                StepEnded(turn = 1, step = 1),
                TurnEnded(turn = 1, outcome = TurnOutcome.TimedOut(timeout)),
            ),
            session.events,
        )
    }

    @Test
    fun `cancellation closes the active step and turn then remains cancellation`() = runBlocking {
        val session = Session()
        val modelStarted = CompletableDeferred<Unit>()
        val model = LanguageModel {
            modelStarted.complete(Unit)
            awaitCancellation()
        }
        val agent = Agent(session, model, ToolRegistry(), TEST_OPTIONS)
        val submission = async { agent.submit("hello") }

        modelStarted.await()
        submission.cancel()
        assertFailsWith<CancellationException> { submission.await() }

        assertEquals(
            listOf(
                TurnStarted(turn = 1),
                StepStarted(turn = 1, step = 1),
                UserMessageAdded("hello"),
                ModelRequestPrepared(turn = 1, step = 1, tools = emptyList()),
                StepEnded(turn = 1, step = 1),
                TurnEnded(turn = 1, outcome = TurnOutcome.Cancelled),
            ),
            session.events,
        )
    }

    @Test
    fun `submit sends projected session history to model and records its reply`() = runBlocking {
        val session = Session().apply {
            append(UserMessageAdded("previous question"))
            append(AssistantMessageAdded("previous answer"))
        }
        val model = RecordingModel(ModelResponse.Answer(AssistantMessage("current answer")))
        val agent = Agent(session, model, ToolRegistry(), TEST_OPTIONS)

        val reply = agent.submit("current question")

        assertEquals(AssistantMessage("current answer"), reply)
        assertEquals(
            listOf(
                UserMessage("previous question"),
                AssistantMessage("previous answer"),
                UserMessage("current question"),
            ),
            model.requests.single().messages,
        )
        assertEquals(
            listOf(
                UserMessageAdded("previous question"),
                AssistantMessageAdded("previous answer"),
                TurnStarted(turn = 1),
                StepStarted(turn = 1, step = 1),
                UserMessageAdded("current question"),
                ModelRequestPrepared(turn = 1, step = 1, tools = emptyList()),
                ModelChunkReceived(
                    turn = 1,
                    step = 1,
                    attempt = 1,
                    chunk = ModelChunk.Finished(ModelResponse.Answer(AssistantMessage("current answer"))),
                ),
                AssistantMessageAdded("current answer"),
                StepEnded(turn = 1, step = 1),
                TurnEnded(turn = 1, outcome = TurnOutcome.Completed),
            ),
            session.events,
        )
        assertEquals(model.requests.single(), SessionProjector.toRequest(session.events, turn = 1, step = 1))
    }

    @Test
    fun `model failure remains visible and does not fabricate an assistant reply`() = runBlocking {
        val session = Session()
        val failure = IllegalStateException("model unavailable")
        val model = LanguageModel { throw failure }
        val agent = Agent(session, model, ToolRegistry(), TEST_OPTIONS)

        val thrown = assertFailsWith<IllegalStateException> {
            agent.submit("hello")
        }

        assertEquals(failure::class, thrown::class)
        assertEquals(failure.message, thrown.message)
        assertEquals(
            listOf(
                TurnStarted(turn = 1),
                StepStarted(turn = 1, step = 1),
                UserMessageAdded("hello"),
                ModelRequestPrepared(turn = 1, step = 1, tools = emptyList()),
                StepEnded(turn = 1, step = 1),
                TurnEnded(turn = 1, outcome = TurnOutcome.Failed("model unavailable")),
            ),
            session.events,
        )
    }

    @Test
    fun `each submission receives the next turn number`() = runBlocking {
        val session = Session()
        val agent = Agent(
            session,
            LanguageModel { ModelResponse.Answer(AssistantMessage("answer")) },
            ToolRegistry(),
            TEST_OPTIONS,
        )

        agent.submit("first")
        agent.submit("second")

        assertEquals(
            listOf(1, 2),
            session.events.filterIsInstance<TurnStarted>().map { it.turn },
        )
    }

    @Test
    fun `tool request executes and its result drives the next step`() = runBlocking {
        val session = Session()
        val call = ToolCall(id = "call-1", name = "read_file", arguments = "{\"path\":\"README.md\"}")
        val model = RecordingModel(
            ModelResponse.ToolRequest(call, content = "I will read it."),
            ModelResponse.Answer(AssistantMessage("the project is a Kotlin agent runtime")),
        )
        val tool = RecordingTool(name = "read_file", result = "project content")
        val agent = Agent(session, model, ToolRegistry(listOf(tool)), TEST_OPTIONS)

        val reply = agent.submit("read the readme")

        assertEquals(AssistantMessage("the project is a Kotlin agent runtime"), reply)
        assertEquals(listOf(call.arguments), tool.arguments)
        assertEquals(
            listOf(
                listOf(UserMessage("read the readme")),
                listOf(
                    UserMessage("read the readme"),
                    ToolCallMessage(call, content = "I will read it."),
                    ToolResultMessage(callId = "call-1", content = "project content", isError = false),
                ),
            ),
            model.requests.map { it.messages },
        )
        assertEquals(listOf(tool.definition), model.requests[0].tools)
        assertEquals(listOf(tool.definition), model.requests[1].tools)
        assertEquals(
            listOf(
                TurnStarted(turn = 1),
                StepStarted(turn = 1, step = 1),
                UserMessageAdded("read the readme"),
                ModelRequestPrepared(turn = 1, step = 1, tools = listOf(tool.definition)),
                ModelChunkReceived(
                    turn = 1,
                    step = 1,
                    attempt = 1,
                    chunk = ModelChunk.Finished(ModelResponse.ToolRequest(call, content = "I will read it.")),
                ),
                ToolCallRequested(turn = 1, step = 1, call = call, content = "I will read it."),
                ToolResultAdded(
                    turn = 1,
                    step = 1,
                    callId = "call-1",
                    content = "project content",
                    isError = false,
                ),
                StepEnded(turn = 1, step = 1),
                StepStarted(turn = 1, step = 2),
                ModelRequestPrepared(turn = 1, step = 2, tools = listOf(tool.definition)),
                ModelChunkReceived(
                    turn = 1,
                    step = 2,
                    attempt = 1,
                    chunk = ModelChunk.Finished(
                        ModelResponse.Answer(AssistantMessage("the project is a Kotlin agent runtime")),
                    ),
                ),
                AssistantMessageAdded("the project is a Kotlin agent runtime"),
                StepEnded(turn = 1, step = 2),
                TurnEnded(turn = 1, outcome = TurnOutcome.Completed),
            ),
            session.events,
        )
        assertEquals(model.requests[0], SessionProjector.toRequest(session.events, turn = 1, step = 1))
        assertEquals(model.requests[1], SessionProjector.toRequest(session.events, turn = 1, step = 2))
    }

    @Test
    fun `expected tool failure is returned to model for recovery`() = runBlocking {
        val session = Session()
        val call = ToolCall(id = "call-1", name = "missing", arguments = "{}")
        val model = RecordingModel(
            ModelResponse.ToolRequest(call),
            ModelResponse.Answer(AssistantMessage("I cannot use that tool")),
        )
        val agent = Agent(session, model, ToolRegistry(), TEST_OPTIONS)

        val reply = agent.submit("use the missing tool")

        assertEquals(AssistantMessage("I cannot use that tool"), reply)
        assertEquals(
            ToolResultMessage(
                callId = "call-1",
                content = "Error: unknown tool \"missing\"",
                isError = true,
            ),
            model.requests[1].messages.last(),
        )
        assertEquals(TurnOutcome.Completed, session.events.filterIsInstance<TurnEnded>().single().outcome)
    }

    @Test
    fun `unexpected tool failure terminates turn without exposing exception detail`() = runBlocking {
        val session = Session()
        val call = ToolCall(id = "call-1", name = "broken", arguments = "{}")
        val model = RecordingModel(ModelResponse.ToolRequest(call))
        val tool = object : Tool {
            override val definition = ToolDefinition(
                name = "broken",
                description = "test tool",
                parameters = buildJsonObject { put("type", "object") },
            )

            override suspend fun execute(arguments: String): String = error("database password leaked")
        }
        val agent = Agent(session, model, ToolRegistry(listOf(tool)), TEST_OPTIONS)

        assertFailsWith<IllegalStateException> {
            agent.submit("use the broken tool")
        }

        assertEquals(
            "Error: internal tool failure",
            session.events.filterIsInstance<ToolResultAdded>().single().content,
        )
    }

    @Test
    fun `step limit stops turn before another model request`() = runBlocking {
        val session = Session()
        val call = ToolCall(id = "call-1", name = "read_file", arguments = "{}")
        val model = RecordingModel(
            ModelResponse.ToolRequest(call),
            ModelResponse.ToolRequest(call),
        )
        val tool = RecordingTool(name = "read_file", result = "content")
        val agent = Agent(
            session,
            model,
            ToolRegistry(listOf(tool)),
            AgentOptions(maxStepsPerTurn = 1, turnTimeout = Duration.ofSeconds(5)),
        )

        val failure = assertFailsWith<StepLimitExceededException> {
            agent.submit("keep reading")
        }

        assertEquals("turn exceeded configured maximum of 1 step", failure.message)
        assertEquals(1, model.requests.size)
        assertEquals(listOf(1), session.events.filterIsInstance<StepStarted>().map { it.step })
        assertEquals(
            TurnOutcome.Failed("turn exceeded configured maximum of 1 step"),
            session.events.filterIsInstance<TurnEnded>().single().outcome,
        )
    }
}

private val TEST_OPTIONS = AgentOptions(
    maxStepsPerTurn = 8,
    turnTimeout = Duration.ofSeconds(5),
)

private class RecordingModel(
    vararg responses: ModelResponse,
) : LanguageModel {
    private val pendingResponses = ArrayDeque(responses.toList())
    val requests = mutableListOf<ModelRequest>()

    override suspend fun generate(request: ModelRequest): ModelResponse {
        requests.add(request)
        return pendingResponses.removeFirst()
    }
}

private class RecordingTool(
    name: String,
    private val result: String,
) : Tool {
    override val definition = ToolDefinition(
        name = name,
        description = "test tool",
        parameters = buildJsonObject { put("type", "object") },
    )
    val arguments = mutableListOf<String>()

    override suspend fun execute(arguments: String): String {
        this.arguments.add(arguments)
        return result
    }
}
