package com.attuchengmen.agent.session

/**
 * 阅读顺序 3：Session 事件日志的追加与读取入口。
 *
 * 实际存储由注入的 [SessionLog] 负责；调用者只能通过 Session 追加事实
 * 或读取稳定快照，不能绕过日志实现修改历史。
 */
class Session(
    private val eventLog: SessionLog = InMemorySessionLog(),
) {

    /**
     * 返回读取时刻的稳定快照。
     * 后续追加事件不会改变调用者已经取得的列表。
     */
    val events: List<SessionEvent>
        get() = eventLog.events

    /** 将一个已经发生的事实追加到日志末尾。 */
    fun append(event: SessionEvent) {
        eventLog.append(event)
    }
}
