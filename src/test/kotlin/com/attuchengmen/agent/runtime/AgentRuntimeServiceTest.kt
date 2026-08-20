package com.attuchengmen.agent.runtime

import com.attuchengmen.agent.Agent
import com.attuchengmen.agent.AgentOptions
import com.attuchengmen.agent.message.AssistantMessage
import com.attuchengmen.agent.model.LanguageModel
import com.attuchengmen.agent.model.ModelChunk
import com.attuchengmen.agent.model.ModelFinishReason
import com.attuchengmen.agent.model.ModelRequest
import com.attuchengmen.agent.model.ModelResponse
import com.attuchengmen.agent.session.Session
import com.attuchengmen.agent.session.TurnEnded
import com.attuchengmen.agent.session.TurnOutcome
import com.attuchengmen.agent.tool.ToolRegistry
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.asCoroutineDispatcher
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitCancellation
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.runBlocking
import java.time.Duration
import java.util.concurrent.CountDownLatch
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertIs

/** 验证进程内 Session 与 Run 的所有权、并发和终态。 */
class AgentRuntimeServiceTest {
    @Test
    fun `completed run exposes answer and session events`(): Unit = runBlocking {
        val service = serviceWith(LanguageModel { ModelResponse.Answer(AssistantMessage("answer")) })
        try {
            val sessionId = service.createSession()

            val run = service.awaitRun(service.startRun(sessionId, "question"))

            assertEquals(sessionId, run.sessionId)
            assertEquals(RunState.Completed(AssistantMessage("answer")), run.state)
            assertEquals(
                TurnOutcome.Completed,
                service.sessionEvents(sessionId).filterIsInstance<TurnEnded>().single().outcome,
            )
        } finally {
            service.close()
        }
    }

    @Test
    fun `same session rejects a second active run`(): Unit = runBlocking {
        val started = CompletableDeferred<Unit>()
        val service = serviceWith(
            LanguageModel {
                started.complete(Unit)
                awaitCancellation()
            },
        )
        try {
            val sessionId = service.createSession()
            val firstRunId = service.startRun(sessionId, "first")
            started.await()

            val failure = assertFailsWith<SessionBusyException> {
                service.startRun(sessionId, "second")
            }

            assertEquals(sessionId, failure.sessionId)
            assertEquals(firstRunId, failure.activeRunId)
            service.cancelRun(firstRunId)
            assertIs<RunState.Cancelled>(service.getRun(firstRunId).state)
        } finally {
            service.close()
        }
    }

    @Test
    fun `different sessions can run concurrently`(): Unit = runBlocking {
        val starts = Channel<Unit>(capacity = 2)
        val release = CompletableDeferred<Unit>()
        val service = serviceWith(
            LanguageModel {
                starts.send(Unit)
                release.await()
                ModelResponse.Answer(AssistantMessage("done"))
            },
        )
        try {
            val firstSession = service.createSession()
            val secondSession = service.createSession()

            val firstRun = service.startRun(firstSession, "first")
            val secondRun = service.startRun(secondSession, "second")
            starts.receive()
            starts.receive()
            release.complete(Unit)

            assertIs<RunState.Completed>(service.awaitRun(firstRun).state)
            assertIs<RunState.Completed>(service.awaitRun(secondRun).state)
        } finally {
            service.close()
        }
    }

    @Test
    fun `cancelling one run does not affect another session`(): Unit = runBlocking {
        val starts = Channel<Unit>(capacity = 2)
        val service = serviceWith(
            LanguageModel {
                starts.send(Unit)
                awaitCancellation()
            },
        )
        try {
            val cancelledRun = service.startRun(service.createSession(), "cancel")
            val remainingRun = service.startRun(service.createSession(), "remain")
            starts.receive()
            starts.receive()

            service.cancelRun(cancelledRun)

            assertIs<RunState.Cancelled>(service.getRun(cancelledRun).state)
            assertIs<RunState.Running>(service.getRun(remainingRun).state)
            service.cancelRun(remainingRun)
        } finally {
            service.close()
        }
    }

    @Test
    fun `run cancelled before execution starts releases its session`(): Unit = runBlocking {
        val executor = Executors.newSingleThreadExecutor()
        val dispatcher = executor.asCoroutineDispatcher()
        val dispatcherOccupied = CountDownLatch(1)
        val releaseDispatcher = CountDownLatch(1)
        executor.execute {
            dispatcherOccupied.countDown()
            releaseDispatcher.await()
        }
        check(dispatcherOccupied.await(5, TimeUnit.SECONDS)) { "test dispatcher did not start" }
        val service = AgentRuntimeService(
            coroutineContext = dispatcher,
            agentFactory = { session ->
                Agent(
                    session,
                    LanguageModel { ModelResponse.Answer(AssistantMessage("done")) },
                    ToolRegistry(),
                    TEST_OPTIONS,
                )
            },
        )
        try {
            val sessionId = service.createSession()
            val cancelledRun = service.startRun(sessionId, "cancel before dispatch")

            val cancellation = async(start = CoroutineStart.UNDISPATCHED) {
                service.cancelRun(cancelledRun)
            }
            releaseDispatcher.countDown()
            cancellation.await()

            assertIs<RunState.Cancelled>(service.getRun(cancelledRun).state)
            assertIs<RunState.Completed>(
                service.awaitRun(service.startRun(sessionId, "next run")).state,
            )
        } finally {
            releaseDispatcher.countDown()
            service.close()
            dispatcher.close()
        }
    }

    @Test
    fun `session accepts another run after previous run finishes`(): Unit = runBlocking {
        val service = serviceWith(LanguageModel { ModelResponse.Answer(AssistantMessage("done")) })
        try {
            val sessionId = service.createSession()

            service.awaitRun(service.startRun(sessionId, "first"))
            val second = service.awaitRun(service.startRun(sessionId, "second"))

            assertIs<RunState.Completed>(second.state)
            assertEquals(2, service.sessionEvents(sessionId).filterIsInstance<TurnEnded>().size)
        } finally {
            service.close()
        }
    }

    @Test
    fun `model failure becomes a queryable run state`(): Unit = runBlocking {
        val service = serviceWith(LanguageModel { throw IllegalStateException("model unavailable") })
        try {
            val runId = service.startRun(service.createSession(), "question")

            val run = service.awaitRun(runId)

            assertEquals(RunState.Failed("model unavailable"), run.state)
        } finally {
            service.close()
        }
    }

    @Test
    fun `max token response remains distinct from normal completion`(): Unit = runBlocking {
        val model = object : LanguageModel {
            override suspend fun generate(request: ModelRequest): ModelResponse =
                error("stream should be used")

            override fun stream(request: ModelRequest) = flowOf(
                ModelChunk.Finished(
                    ModelResponse.Answer(AssistantMessage("partial")),
                    ModelFinishReason.MAX_TOKENS,
                ),
            )
        }
        val service = serviceWith(model)
        try {
            val run = service.awaitRun(service.startRun(service.createSession(), "question"))

            assertEquals(RunState.MaxTokens(AssistantMessage("partial")), run.state)
        } finally {
            service.close()
        }
    }

    @Test
    fun `cancelling an awaiter does not cancel its run`(): Unit = runBlocking {
        val started = CompletableDeferred<Unit>()
        val release = CompletableDeferred<Unit>()
        val service = serviceWith(
            LanguageModel {
                started.complete(Unit)
                release.await()
                ModelResponse.Answer(AssistantMessage("done"))
            },
        )
        try {
            val runId = service.startRun(service.createSession(), "question")
            started.await()
            val waiter = async(start = CoroutineStart.UNDISPATCHED) { service.awaitRun(runId) }

            waiter.cancel()
            assertFailsWith<CancellationException> { waiter.await() }

            assertIs<RunState.Running>(service.getRun(runId).state)
            release.complete(Unit)
            assertIs<RunState.Completed>(service.awaitRun(runId).state)
        } finally {
            service.close()
        }
    }

    @Test
    fun `closing runtime cancels active runs and rejects new work`(): Unit = runBlocking {
        val started = CompletableDeferred<Unit>()
        val service = serviceWith(
            LanguageModel {
                started.complete(Unit)
                awaitCancellation()
            },
        )
        val runId = service.startRun(service.createSession(), "question")
        started.await()

        service.close()

        assertIs<RunState.Cancelled>(service.awaitRun(runId).state)
        assertFailsWith<AgentRuntimeClosedException> { service.createSession() }
    }

    @Test
    fun `unknown ids fail explicitly`(): Unit = runBlocking {
        val service = serviceWith(LanguageModel { ModelResponse.Answer(AssistantMessage("unused")) })
        try {
            assertFailsWith<UnknownSessionException> {
                service.startRun(SessionId("missing-session"), "question")
            }
            assertFailsWith<UnknownRunException> {
                service.getRun(RunId("missing-run"))
            }
        } finally {
            service.close()
        }
    }

    private fun serviceWith(model: LanguageModel): AgentRuntimeService =
        AgentRuntimeService { session: Session ->
            Agent(
                session = session,
                model = model,
                tools = ToolRegistry(),
                options = TEST_OPTIONS,
            )
        }

    private companion object {
        val TEST_OPTIONS = AgentOptions(
            maxStepsPerTurn = 4,
            turnTimeout = Duration.ofSeconds(5),
        )
    }
}
