package com.attuchengmen.agent.model

import java.math.BigDecimal
import java.util.Currency
import java.util.Locale

/** 一个 Provider 调用的互斥 Token 计数；推理 Token 已包含在输出中。 */
data class TokenUsage(
    val inputTokens: Long,
    val outputTokens: Long,
    val cacheReadTokens: Long? = null,
    val cacheWriteTokens: Long? = null,
    val reasoningTokens: Long? = null,
) {
    init {
        require(inputTokens >= 0) { "inputTokens must not be negative" }
        require(outputTokens >= 0) { "outputTokens must not be negative" }
        require(cacheReadTokens == null || cacheReadTokens >= 0) { "cacheReadTokens must not be negative" }
        require(cacheWriteTokens == null || cacheWriteTokens >= 0) { "cacheWriteTokens must not be negative" }
        require(reasoningTokens == null || reasoningTokens >= 0) { "reasoningTokens must not be negative" }
        require(reasoningTokens == null || reasoningTokens <= outputTokens) {
            "reasoningTokens must not exceed outputTokens"
        }
    }

    /** 实际进入模型的全部输入，包括缓存读取和缓存写入。 */
    val billableInputTokens: Long
        get() = addExact(inputTokens, cacheReadTokens.orZero(), cacheWriteTokens.orZero())

    /** 输入与输出总量；推理 Token 不重复加入。 */
    val totalTokens: Long
        get() = addExact(billableInputTokens, outputTokens)
}

/** 一次模型响应结束的 Provider 无关原因。 */
enum class ModelFinishReason {
    STOP,
    TOOL_CALLS,
    MAX_TOKENS,
}

/** 用于持久化归因的模型和当次计价信息。 */
data class ModelProfile(
    val provider: String,
    val model: String,
    val pricing: ModelPricing? = null,
) {
    init {
        require(provider.isNotBlank()) { "provider must not be blank" }
        require(model.isNotBlank()) { "model must not be blank" }
    }
}

/** 每百万 Token 的价格快照；版本用于防止调价改写历史成本。 */
data class ModelPricing(
    val version: String,
    val currency: String,
    val inputPerMillion: BigDecimal,
    val cacheReadPerMillion: BigDecimal,
    val cacheWritePerMillion: BigDecimal,
    val outputPerMillion: BigDecimal,
) {
    init {
        require(version.isNotBlank()) { "pricing version must not be blank" }
        try {
            Currency.getInstance(currency)
        } catch (error: IllegalArgumentException) {
            throw IllegalArgumentException("currency must be an ISO 4217 code", error)
        }
        require(currency == currency.uppercase(Locale.ROOT)) { "currency must use uppercase ISO 4217 form" }
        require(inputPerMillion >= BigDecimal.ZERO) { "inputPerMillion must not be negative" }
        require(cacheReadPerMillion >= BigDecimal.ZERO) { "cacheReadPerMillion must not be negative" }
        require(cacheWritePerMillion >= BigDecimal.ZERO) { "cacheWritePerMillion must not be negative" }
        require(outputPerMillion >= BigDecimal.ZERO) { "outputPerMillion must not be negative" }
    }

    /** 按互斥 Token 桶计算精确成本，不使用二进制浮点数。 */
    fun calculate(usage: TokenUsage): TokenCost = TokenCost(
        input = price(usage.inputTokens, inputPerMillion),
        cacheRead = price(usage.cacheReadTokens.orZero(), cacheReadPerMillion),
        cacheWrite = price(usage.cacheWriteTokens.orZero(), cacheWritePerMillion),
        output = price(usage.outputTokens, outputPerMillion),
    )

    private fun price(tokens: Long, rate: BigDecimal): BigDecimal =
        rate.multiply(BigDecimal.valueOf(tokens)).movePointLeft(6)
}

/** 一次或一组调用按 Token 桶拆分的金额。 */
data class TokenCost(
    val input: BigDecimal,
    val cacheRead: BigDecimal,
    val cacheWrite: BigDecimal,
    val output: BigDecimal,
) {
    init {
        require(input >= BigDecimal.ZERO) { "input cost must not be negative" }
        require(cacheRead >= BigDecimal.ZERO) { "cacheRead cost must not be negative" }
        require(cacheWrite >= BigDecimal.ZERO) { "cacheWrite cost must not be negative" }
        require(output >= BigDecimal.ZERO) { "output cost must not be negative" }
    }

    val total: BigDecimal
        get() = input.add(cacheRead).add(cacheWrite).add(output)

    /** 合并同一币种下的成本；币种由外层分组保证。 */
    operator fun plus(other: TokenCost): TokenCost = TokenCost(
        input = input.add(other.input),
        cacheRead = cacheRead.add(other.cacheRead),
        cacheWrite = cacheWrite.add(other.cacheWrite),
        output = output.add(other.output),
    )

    companion object {
        /** 不带币种的零成本，供已按币种分组的聚合器使用。 */
        val ZERO = TokenCost(BigDecimal.ZERO, BigDecimal.ZERO, BigDecimal.ZERO, BigDecimal.ZERO)
    }
}

private fun Long?.orZero(): Long = this ?: 0

private fun addExact(first: Long, vararg rest: Long): Long =
    rest.fold(first, Math::addExact)
