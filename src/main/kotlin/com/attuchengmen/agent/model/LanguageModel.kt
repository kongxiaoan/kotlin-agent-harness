package com.attuchengmen.agent.model

import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flow

/**
 * 阅读顺序 5：Agent Runtime 依赖的最小模型能力端口。
 *
 * 核心编排不认识具体模型厂商。测试 Fake、未来的 HTTP Provider 都通过
 * 这个接口提供“完整模型请求生成一个模型结果”的能力。
 */
fun interface LanguageModel {
    /** Provider、模型与可选价格快照；缺失表示该实现不提供商业归因。 */
    val profile: ModelProfile?
        get() = null

    /** Provider 拥有的重试策略；缺失时 Runtime 不重试。 */
    val retryPolicy: ModelRetryPolicy?
        get() = null

    /**
     * 根据消息历史和可用工具定义生成最终答案或工具请求。
     * 模型失败直接抛出异常，由更高层决定恢复策略。
     */
    suspend fun generate(request: ModelRequest): ModelResponse

    /**
     * 返回一个 attempt 的 chunk 流。
     * 非流式 Provider 通过默认实现产生单个终态 chunk。
     */
    fun stream(request: ModelRequest): Flow<ModelChunk> = flow {
        emit(ModelChunk.Finished(generate(request)))
    }
}
