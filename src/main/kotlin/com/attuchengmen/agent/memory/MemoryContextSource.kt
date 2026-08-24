package com.attuchengmen.agent.memory

/** 为一次模型调用读取身份作用域内最近更新的 active memories。 */
class MemoryContextSource(
    private val store: MemoryStore,
    private val scope: MemoryScope,
    private val limit: Int,
) {
    init {
        require(limit > 0) { "memory context limit must be positive" }
    }

    /** 返回确定排序的候选；最终是否进入请求由 ContextManager 的 Token Budget 决定。 */
    suspend fun load(): List<MemoryRecord> = store.list(MemoryListQuery(scope, limit = limit))
}
