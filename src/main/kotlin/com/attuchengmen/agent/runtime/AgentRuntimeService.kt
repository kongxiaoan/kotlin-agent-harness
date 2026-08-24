package com.attuchengmen.agent.runtime

import com.attuchengmen.agent.Agent
import com.attuchengmen.agent.message.AssistantMessage
import com.attuchengmen.agent.session.InMemorySessionLog
import com.attuchengmen.agent.session.Session
import com.attuchengmen.agent.session.SessionEvent
import com.attuchengmen.agent.session.SessionEventEnvelope
import com.attuchengmen.agent.session.SessionId
import com.attuchengmen.agent.session.TurnEnded
import com.attuchengmen.agent.session.TurnOutcome
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.launch
import java.util.UUID
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.atomic.AtomicReference
import kotlin.coroutines.CoroutineContext

/** 一次异步 Agent 执行的不可互换标识。 */
@JvmInline
value class RunId(
    val value: String,
) {
    init {
        require(value.isNotBlank()) { "run id must not be blank" }
    }
}

/** Run 当前可查询的互斥状态。 */
sealed interface RunState {
    /** Run 已被接受且尚未产生终态。 */
    data object Running : RunState

    /** Run 已产生最终 Assistant 回复。 */
    data class Completed(
        val response: AssistantMessage,
    ) : RunState

    /** 模型达到输出上限，回复可能不完整。 */
    data class MaxTokens(
        val response: AssistantMessage,
    ) : RunState

    /** Run 因执行异常结束。 */
    data class Failed(
        val message: String,
    ) : RunState

    /** Run 因显式取消或 Runtime 关闭而结束。 */
    data object Cancelled : RunState
}

/** 不暴露内部可变状态的 Run 查询结果。 */
data class RunSnapshot(
    val id: RunId,
    val sessionId: SessionId,
    val state: RunState,
)

/** 请求引用的 Session 不属于当前 Runtime。 */
class UnknownSessionException(
    val sessionId: SessionId,
) : NoSuchElementException("unknown session ${sessionId.value}")

/** 请求引用的 Run 不属于当前 Runtime。 */
class UnknownRunException(
    val runId: RunId,
) : NoSuchElementException("unknown run ${runId.value}")

/** 同一 Session 已经由当前 Runtime 实例管理。 */
class SessionAlreadyOpenException(
    val sessionId: SessionId,
) : IllegalStateException("session ${sessionId.value} is already open")

/** 同一 Session 已有未结束 Run，不能接受并发执行。 */
class SessionBusyException(
    val sessionId: SessionId,
    val activeRunId: RunId,
) : IllegalStateException(
    "session ${sessionId.value} already has active run ${activeRunId.value}",
)

/** 已关闭的 Runtime 不再接受新 Session 或 Run。 */
class AgentRuntimeClosedException : IllegalStateException("agent runtime is closed")

/**
 * 管理单进程内多个 Session 和异步 Run 的应用服务。
 *
 * Run 使用独立于调用方的协程执行，因此查询请求结束不会取消 Agent。
 * 同一 Session 同时只允许一个 Run，不同 Session 可以并行执行。
 * 当前状态仅保存在内存中，进程重启后不会恢复 Run。
 *
 * @param sessionFactory 根据新标识创建 Session，默认使用内存日志。AgentRuntimeService 不需要知道 Session 到底怎么创建。
 * @param coroutineContext 执行 Run 的线程上下文；Runtime 始终拥有独立 Job。
 * @param agentFactory 为新 Session 创建拥有该 Session 的 Agent。
 */
class AgentRuntimeService(
    private val sessionFactory: (SessionId) -> Session = { Session(InMemorySessionLog(it)) },
    coroutineContext: CoroutineContext = Dispatchers.Default,
    private val agentFactory: (Session) -> Agent,
) : AutoCloseable {
    private val lifecycleLock = Any()
    private var closed = false
    private val runtimeJob = SupervisorJob()
    private val scope = CoroutineScope(coroutineContext.minusKey(Job) + runtimeJob)
    private val sessions = ConcurrentHashMap<SessionId, SessionEntry>()
    private val runs = ConcurrentHashMap<RunId, RunRecord>()

    /** 创建新的独立 Session，并返回后续命令使用的标识。 */
    fun createSession(): SessionId = synchronized(lifecycleLock) {
        ensureOpen()
        var id: SessionId
        do {
            id = SessionId(UUID.randomUUID().toString())
        } while (sessions.containsKey(id))
        registerSession(id)
    }

    /** 按持久化标识打开已有 Session；重复打开会明确失败。 */
    fun openSession(sessionId: SessionId): SessionId = synchronized(lifecycleLock) {
        ensureOpen()
        if (sessions.containsKey(sessionId)) throw SessionAlreadyOpenException(sessionId)
        registerSession(sessionId)
    }

    private fun registerSession(id: SessionId): SessionId {
        val session = sessionFactory(id)
        require(session.id == id) {
            "session factory returned ${session.id.value}, expected ${id.value}"
        }
        sessions[id] = SessionEntry(session, agentFactory(session))
        return id
    }

    /**
     * 接受一次异步执行并立即返回 RunId。
     * Session 已有活跃 Run 时明确拒绝，不在内存中隐式排队。
     */
    fun startRun(sessionId: SessionId, content: String): RunId = synchronized(lifecycleLock) {
        ensureOpen()
        require(content.isNotBlank()) { "run content must not be blank" }
        val session = sessionEntry(sessionId)
        synchronized(session) {
            session.activeRunId?.let { throw SessionBusyException(sessionId, it) }
            val record = createRunRecord(sessionId)
            session.activeRunId = record.id
            record.job = scope.launch(start = CoroutineStart.LAZY) {
                executeRun(session, record, content)
            }
            record.job.invokeOnCompletion { cause -> completeRun(session, record, cause) }
            record.job.start()
            record.id
        }
    }

    /** 返回读取时刻的 Run 状态快照。 */
    fun getRun(runId: RunId): RunSnapshot = runRecord(runId).snapshot()

    /** 等待 Run 到达终态并返回最终快照；等待方取消不会取消 Run。 */
    suspend fun awaitRun(runId: RunId): RunSnapshot {
        val record = runRecord(runId)
        record.job.join()
        return record.snapshot()
    }

    /** 取消指定 Run 并等待 Agent 完成 Step 和 Turn 清理。 */
    suspend fun cancelRun(runId: RunId) {
        runRecord(runId).job.cancelAndJoin()
    }

    /** 返回 Session 当前的稳定事件快照。 */
    fun sessionEvents(sessionId: SessionId): List<SessionEvent> = sessionEntry(sessionId).session.events

    /** 返回指定游标之后的 Session 事实，供断点续传使用。 */
    fun sessionEnvelopes(sessionId: SessionId, afterSequence: Long = 0): List<SessionEventEnvelope> {
        require(afterSequence >= 0) { "event cursor must not be negative" }
        return sessionEntry(sessionId).session.envelopes.filter { it.sequence > afterSequence }
    }

    /** 订阅成功追加后的 Session 实时事件，不重放历史。 */
    fun subscribe(sessionId: SessionId, observer: (SessionEvent) -> Unit): AutoCloseable =
        sessionEntry(sessionId).session.subscribe(observer)

    /** 订阅带游标元数据的 Session 实时事件，不重放历史。 */
    fun subscribeEnvelopes(sessionId: SessionId, observer: (SessionEventEnvelope) -> Unit): AutoCloseable =
        sessionEntry(sessionId).session.subscribeEnvelopes(observer)

    /** 停止接受新工作并向全部活跃 Run 传播取消。 */
    override fun close() {
        synchronized(lifecycleLock) {
            if (!closed) {
                closed = true
                scope.cancel("agent runtime closed")
            }
        }
    }

    private suspend fun executeRun(session: SessionEntry, record: RunRecord, content: String) {
        try {
            val response = session.agent.submit(content)
            record.state.set(completedState(session, response))
        } catch (_: CancellationException) {
            record.state.set(RunState.Cancelled)
        } catch (error: Throwable) {
            val message = error.message ?: error::class.simpleName ?: "unknown agent failure"
            record.state.set(RunState.Failed(message))
            if (error is Error) throw error
        }
    }

    private fun completedState(session: SessionEntry, response: AssistantMessage): RunState =
        when (val outcome = session.session.events.filterIsInstance<TurnEnded>().lastOrNull()?.outcome) {
            TurnOutcome.Completed -> RunState.Completed(response)
            TurnOutcome.MaxTokens -> RunState.MaxTokens(response)
            TurnOutcome.Cancelled,
            TurnOutcome.Interrupted,
            is TurnOutcome.Failed,
            is TurnOutcome.TimedOut,
            null,
            -> error("agent returned a response without a successful turn outcome: $outcome")
        }

    /** Job 可能在执行函数开始前取消，因此终态兜底和占用释放属于 Job 完成职责。 */
    private fun completeRun(session: SessionEntry, record: RunRecord, cause: Throwable?) {
        if (cause is CancellationException) {
            record.state.compareAndSet(RunState.Running, RunState.Cancelled)
        }
        synchronized(session) {
            if (session.activeRunId == record.id) session.activeRunId = null
        }
    }

    private fun createRunRecord(sessionId: SessionId): RunRecord {
        while (true) {
            val record = RunRecord(RunId(UUID.randomUUID().toString()), sessionId)
            if (runs.putIfAbsent(record.id, record) == null) return record
        }
    }

    private fun sessionEntry(sessionId: SessionId): SessionEntry =
        sessions[sessionId] ?: throw UnknownSessionException(sessionId)

    private fun runRecord(runId: RunId): RunRecord = runs[runId] ?: throw UnknownRunException(runId)

    private fun ensureOpen() {
        if (closed) throw AgentRuntimeClosedException()
    }

    private class SessionEntry(
        val session: Session,
        val agent: Agent,
        var activeRunId: RunId? = null,
    )

    private class RunRecord(
        val id: RunId,
        val sessionId: SessionId,
    ) {
        val state = AtomicReference<RunState>(RunState.Running)
        lateinit var job: Job

        fun snapshot(): RunSnapshot = RunSnapshot(id, sessionId, state.get())
    }
}
