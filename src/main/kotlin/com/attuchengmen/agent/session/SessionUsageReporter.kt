package com.attuchengmen.agent.session

import com.attuchengmen.agent.model.ModelChunk
import com.attuchengmen.agent.model.ModelFinishReason
import com.attuchengmen.agent.model.ModelProfile
import com.attuchengmen.agent.model.TokenCost
import com.attuchengmen.agent.model.TokenUsage
import java.math.BigDecimal
import java.math.RoundingMode
import java.time.Instant

/** Session 内可安全相加的互斥 Token 总量。 */
data class TokenTotals(
    val inputTokens: Long = 0,
    val cacheReadTokens: Long = 0,
    val cacheWriteTokens: Long = 0,
    val outputTokens: Long = 0,
    val reasoningTokens: Long = 0,
) {
    val billableInputTokens: Long
        get() = addExact(inputTokens, cacheReadTokens, cacheWriteTokens)

    /** 推理 Token 已包含在输出中，因此不再次加入。 */
    val totalTokens: Long
        get() = addExact(billableInputTokens, outputTokens)

    internal fun plus(usage: TokenUsage): TokenTotals = TokenTotals(
        inputTokens = addExact(inputTokens, usage.inputTokens),
        cacheReadTokens = addExact(cacheReadTokens, usage.cacheReadTokens ?: 0),
        cacheWriteTokens = addExact(cacheWriteTokens, usage.cacheWriteTokens ?: 0),
        outputTokens = addExact(outputTokens, usage.outputTokens),
        reasoningTokens = addExact(reasoningTokens, usage.reasoningTokens ?: 0),
    )
}

/** Provider、模型和价格版本共同组成商业报表分组。 */
data class ModelUsageGroup(
    val provider: String,
    val model: String,
    val pricingVersion: String?,
    val currency: String?,
)

/** 一组模型 attempt 的调用量、Token、停止原因和成本。 */
data class UsageStatistics(
    val attempts: Int,
    val finishedAttempts: Int,
    val retryAttempts: Int,
    val usageReportedAttempts: Int,
    val timestampedUsageAttempts: Int,
    val pricedUsageAttempts: Int,
    val tokens: TokenTotals,
    val retryTokens: TokenTotals,
    val finishReasons: Map<ModelFinishReason, Int>,
    val costsByCurrency: Map<String, TokenCost>,
    val retryCostsByCurrency: Map<String, TokenCost>,
) {
    /** 未报告不等于零用量，商业系统应将其作为对账缺口。 */
    val unreportedAttempts: Int
        get() = attempts - usageReportedAttempts

    /** 有 Usage 但无法计算成本的调用数。 */
    val unpricedUsageAttempts: Int
        get() = usageReportedAttempts - pricedUsageAttempts

    /** 有 Usage 但无法进入准确账期的历史调用数。 */
    val untimestampedUsageAttempts: Int
        get() = usageReportedAttempts - timestampedUsageAttempts

    /** 缓存读取量占全部输入量的比例；没有输入时不可计算。 */
    val cacheHitRatio: BigDecimal?
        get() = tokens.billableInputTokens.takeIf { it > 0 }?.let { input ->
            BigDecimal.valueOf(tokens.cacheReadTokens)
                .divide(BigDecimal.valueOf(input), CACHE_RATIO_SCALE, RoundingMode.HALF_UP)
        }

    private companion object {
        private const val CACHE_RATIO_SCALE = 6
    }
}

/** 完整 Session 的全局统计及按模型价格版本拆分结果。 */
data class SessionUsageReport(
    val total: UsageStatistics,
    val byModel: Map<ModelUsageGroup, UsageStatistics>,
    val attempts: List<ModelAttemptUsage>,
)

/** 可导出到商业数据仓库的一次模型调用明细。 */
data class ModelAttemptUsage(
    val turn: Int,
    val step: Int,
    val attempt: Int,
    val profile: ModelProfile?,
    val usage: TokenUsage?,
    val usageObservedAt: Instant?,
    val finishReason: ModelFinishReason?,
    val retried: Boolean,
    val cost: TokenCost?,
)

/** 从不可变 Session 事实计算商业用量，不修改或补写历史。 */
object SessionUsageReporter {
    /**
     * 折叠一个 Session 的完整事件序列。
     *
     * @param events 按持久化顺序排列的 Session 事实。
     * @return 可审计的总计、分组和逐 attempt 明细。
     */
    fun report(events: List<SessionEvent>): SessionUsageReport {
        val attempts = linkedMapOf<AttemptKey, AttemptRecord>()
        for (event in events) {
            when (event) {
                is ModelRequestPrepared -> attempts.record(event.key()).profile = event.profile
                is ModelChunkReceived -> {
                    val record = attempts.record(event.key())
                    when (val chunk = event.chunk) {
                        is ModelChunk.Usage -> {
                            record.usage = chunk.usage
                            record.usageObservedAt = event.observedAt
                        }
                        is ModelChunk.Finished -> record.finishReason = chunk.reason
                        is ModelChunk.TextDelta,
                        is ModelChunk.ToolCallDelta,
                        -> Unit
                    }
                }
                is ModelRetryScheduled -> attempts.record(event.key()).retried = true
                else -> Unit
            }
        }

        val total = StatisticsBuilder()
        val groups = linkedMapOf<ModelUsageGroup, StatisticsBuilder>()
        for (record in attempts.values) {
            total.add(record)
            groups.getOrPut(record.group(), ::StatisticsBuilder).add(record)
        }
        return SessionUsageReport(
            total = total.build(),
            byModel = groups.mapValues { it.value.build() },
            attempts = attempts.values.map(AttemptRecord::toView),
        )
    }
}

private data class AttemptKey(
    val turn: Int,
    val step: Int,
    val attempt: Int,
)

private class AttemptRecord(
    private val key: AttemptKey,
) {
    var profile: ModelProfile? = null
    var usage: TokenUsage? = null
    var usageObservedAt: Instant? = null
    var finishReason: ModelFinishReason? = null
    var retried: Boolean = false

    fun group(): ModelUsageGroup {
        val value = profile
        return ModelUsageGroup(
            provider = value?.provider ?: "unattributed",
            model = value?.model ?: "unattributed",
            pricingVersion = value?.pricing?.version,
            currency = value?.pricing?.currency,
        )
    }

    fun toView(): ModelAttemptUsage = ModelAttemptUsage(
        turn = key.turn,
        step = key.step,
        attempt = key.attempt,
        profile = profile,
        usage = usage,
        usageObservedAt = usageObservedAt,
        finishReason = finishReason,
        retried = retried,
        cost = usage?.let { value -> profile?.pricing?.calculate(value) },
    )
}

private class StatisticsBuilder {
    private var attempts = 0
    private var finishedAttempts = 0
    private var retryAttempts = 0
    private var usageReportedAttempts = 0
    private var timestampedUsageAttempts = 0
    private var pricedUsageAttempts = 0
    private var tokens = TokenTotals()
    private var retryTokens = TokenTotals()
    private val finishReasons = linkedMapOf<ModelFinishReason, Int>()
    private val costs = linkedMapOf<String, TokenCost>()
    private val retryCosts = linkedMapOf<String, TokenCost>()

    fun add(record: AttemptRecord) {
        attempts = Math.incrementExact(attempts)
        record.finishReason?.let { reason ->
            finishedAttempts = Math.incrementExact(finishedAttempts)
            finishReasons[reason] = Math.incrementExact(finishReasons[reason] ?: 0)
        }
        if (record.retried) retryAttempts = Math.incrementExact(retryAttempts)

        val usage = record.usage ?: return
        usageReportedAttempts = Math.incrementExact(usageReportedAttempts)
        if (record.usageObservedAt != null) {
            timestampedUsageAttempts = Math.incrementExact(timestampedUsageAttempts)
        }
        tokens = tokens.plus(usage)
        if (record.retried) retryTokens = retryTokens.plus(usage)

        val pricing = record.profile?.pricing ?: return
        pricedUsageAttempts = Math.incrementExact(pricedUsageAttempts)
        val cost = pricing.calculate(usage)
        costs[pricing.currency] = (costs[pricing.currency] ?: TokenCost.ZERO) + cost
        if (record.retried) {
            retryCosts[pricing.currency] = (retryCosts[pricing.currency] ?: TokenCost.ZERO) + cost
        }
    }

    fun build(): UsageStatistics = UsageStatistics(
        attempts = attempts,
        finishedAttempts = finishedAttempts,
        retryAttempts = retryAttempts,
        usageReportedAttempts = usageReportedAttempts,
        timestampedUsageAttempts = timestampedUsageAttempts,
        pricedUsageAttempts = pricedUsageAttempts,
        tokens = tokens,
        retryTokens = retryTokens,
        finishReasons = finishReasons.toMap(),
        costsByCurrency = costs.toMap(),
        retryCostsByCurrency = retryCosts.toMap(),
    )
}

private fun ModelRequestPrepared.key(): AttemptKey = AttemptKey(turn, step, attempt)

private fun ModelChunkReceived.key(): AttemptKey = AttemptKey(turn, step, attempt)

private fun ModelRetryScheduled.key(): AttemptKey = AttemptKey(turn, step, retry)

private fun MutableMap<AttemptKey, AttemptRecord>.record(key: AttemptKey): AttemptRecord =
    getOrPut(key) { AttemptRecord(key) }

private fun addExact(first: Long, vararg rest: Long): Long = rest.fold(first, Math::addExact)
