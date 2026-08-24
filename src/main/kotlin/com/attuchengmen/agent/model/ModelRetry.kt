package com.attuchengmen.agent.model

import java.time.Duration
import kotlin.math.min

/** 模型 Provider 针对瞬时请求失败声明的有界指数退避策略。 */
data class ModelRetryPolicy(
    val maxRetries: Int,
    val initialDelay: Duration,
    val maxDelay: Duration,
) {
    init {
        require(maxRetries >= 0) { "maxRetries must not be negative" }
        require(initialDelay.toMillis() > 0) { "initialDelay must be at least one millisecond" }
        require(maxDelay >= initialDelay) { "maxDelay must not be shorter than initialDelay" }
    }

    /** 返回从 1 开始编号的重试所需等待时间。 */
    fun delayMillis(retry: Int): Long {
        require(retry > 0) { "retry must be positive" }
        var delay = initialDelay.toMillis()
        repeat(retry - 1) {
            delay = min(maxDelay.toMillis(), delay.coerceAtMost(Long.MAX_VALUE / 2) * 2)
        }
        return delay
    }
}

/** 模型请求失败，并明确说明同一请求是否可以安全重试。 */
open class ModelRequestException(
    message: String,
    val retryable: Boolean,
    cause: Throwable? = null,
) : IllegalStateException(message, cause)
