package com.attuchengmen.agent.model

/**
 * 阅读顺序 5：Agent Runtime 依赖的最小模型能力端口。
 *
 * 核心编排不认识具体模型厂商。测试 Fake、未来的 HTTP Provider 都通过
 * 这个接口提供“完整模型请求生成一个模型结果”的能力。
 */
fun interface LanguageModel {
    /**
     * 根据消息历史和可用工具定义生成最终答案或工具请求。
     * 模型失败直接抛出异常，由更高层决定恢复策略。
     */
    fun generate(request: ModelRequest): ModelResponse
}
