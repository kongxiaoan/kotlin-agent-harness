package com.attuchengmen.agent.session

import org.slf4j.LoggerFactory
import java.time.Clock
import java.util.concurrent.CopyOnWriteArrayList

/**
 * 阅读顺序 3：Session 事件日志的追加与读取入口。
 *
 * 实际存储由注入的 [SessionLog] 负责；调用者只能通过 Session 追加事实
 * 或读取稳定快照，不能绕过日志实现修改历史。
 */
class Session(
    private val eventLog: SessionLog = InMemorySessionLog(),
    private val clock: Clock = Clock.systemUTC(),
) {
    private val observers = CopyOnWriteArrayList<(SessionEventEnvelope) -> Unit>()
    private val publicationLock = Any()
    private val pendingPublications = ArrayDeque<SessionEventEnvelope>()
    private var publishing = false

    /** 当前 Session 的持久化标识。 */
    val id: SessionId
        get() = eventLog.sessionId

    init {
        SessionRecovery.interruptedTurnClosers(events).forEach(::append)
    }

    /**
     * 返回读取时刻的稳定快照。
     * 后续追加事件不会改变调用者已经取得的列表。
     */
    val events: List<SessionEvent>
        get() = eventLog.envelopes.map(SessionEventEnvelope::event)

    /** 返回包含 Session、序号和发生时间的稳定事实快照。 */
    val envelopes: List<SessionEventEnvelope>
        get() = eventLog.envelopes

    /** 将一个已经发生的事实追加到日志末尾。 */
    fun append(event: SessionEvent) {
        val startsPublisher = synchronized(publicationLock) {
            val expectedSequence = eventLog.envelopes.lastOrNull()?.sequence ?: 0
            val envelope = SessionEventEnvelope(
                sessionId = id,
                sequence = expectedSequence + 1,
                occurredAt = clock.instant(),
                event = event,
            )
            eventLog.append(expectedSequence, envelope)
            pendingPublications.addLast(envelope)
            if (publishing) {
                false
            } else {
                publishing = true
                true
            }
        }
        if (startsPublisher) publishPending()
    }

    /**
     * 订阅成功追加后的实时事件，不重放历史。
     * 返回值必须关闭以释放观察者；观察者异常不会改变已持久化结果。
     */
    fun subscribe(observer: (SessionEvent) -> Unit): AutoCloseable {
        return subscribeEnvelopes { observer(it.event) }
    }

    /** 订阅带游标元数据的实时事件，不重放历史。 */
    fun subscribeEnvelopes(observer: (SessionEventEnvelope) -> Unit): AutoCloseable {
        observers.add(observer)
        return AutoCloseable { observers.remove(observer) }
    }

    /** 排空成功持久化事件；重入追加只入队，不嵌套通知。 */
    private fun publishPending() {
        while (true) {
            val envelope = synchronized(publicationLock) {
                if (pendingPublications.isEmpty()) {
                    publishing = false
                    return
                }
                pendingPublications.removeFirst()
            }
            for (observer in observers) {
                try {
                    observer(envelope)
                } catch (error: Exception) {
                    logger.warn(
                        "Session observer failed eventType={} errorType={}",
                        envelope.event::class.simpleName,
                        error::class.simpleName,
                    )
                }
            }
        }
    }

    private companion object {
        private val logger = LoggerFactory.getLogger(Session::class.java)
    }
}
