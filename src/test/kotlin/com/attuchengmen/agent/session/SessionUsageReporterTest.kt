package com.attuchengmen.agent.session

import com.attuchengmen.agent.message.AssistantMessage
import com.attuchengmen.agent.model.ModelChunk
import com.attuchengmen.agent.model.ModelFinishReason
import com.attuchengmen.agent.model.ModelPricing
import com.attuchengmen.agent.model.ModelProfile
import com.attuchengmen.agent.model.ModelResponse
import com.attuchengmen.agent.model.TokenUsage
import java.math.BigDecimal
import java.time.Instant
import kotlin.test.Test
import kotlin.test.assertEquals

class SessionUsageReporterTest {
    @Test
    fun `report aggregates token cost cache efficiency and retry waste`() {
        val priced = ModelProfile(
            provider = "deepseek",
            model = "deepseek-v4-flash",
            pricing = ModelPricing(
                version = "2026-08-cny",
                currency = "CNY",
                inputPerMillion = BigDecimal("1.00"),
                cacheReadPerMillion = BigDecimal("0.10"),
                cacheWritePerMillion = BigDecimal("2.00"),
                outputPerMillion = BigDecimal("3.00"),
            ),
        )
        val unpriced = ModelProfile("local", "test-model")
        val firstUsage = TokenUsage(100, 30, cacheReadTokens = 900, cacheWriteTokens = 20, reasoningTokens = 10)
        val secondUsage = TokenUsage(50, 20, cacheReadTokens = 950, reasoningTokens = 5)
        val unpricedUsage = TokenUsage(10, 2)
        val events = listOf(
            ModelRequestPrepared(1, 1, emptyList(), attempt = 1, profile = priced),
            ModelChunkReceived(
                1,
                1,
                1,
                ModelChunk.Usage(firstUsage),
                observedAt = Instant.parse("2026-08-19T10:15:30Z"),
            ),
            ModelRetryScheduled(1, 1, retry = 1, delayMillis = 10, failure = "stalled"),
            ModelRequestPrepared(1, 1, emptyList(), attempt = 2, profile = priced),
            ModelChunkReceived(1, 1, 2, ModelChunk.Usage(secondUsage)),
            ModelChunkReceived(
                1,
                1,
                2,
                ModelChunk.Finished(ModelResponse.Answer(AssistantMessage("done")), ModelFinishReason.STOP),
            ),
            ModelRequestPrepared(1, 2, emptyList(), attempt = 1, profile = unpriced),
            ModelChunkReceived(1, 2, 1, ModelChunk.Usage(unpricedUsage)),
            ModelChunkReceived(
                1,
                2,
                1,
                ModelChunk.Finished(ModelResponse.Answer(AssistantMessage("cut")), ModelFinishReason.MAX_TOKENS),
            ),
            ModelRequestPrepared(1, 3, emptyList(), attempt = 1, profile = priced),
        )

        val report = SessionUsageReporter.report(events)
        val total = report.total

        assertEquals(4, total.attempts)
        assertEquals(2, total.finishedAttempts)
        assertEquals(1, total.retryAttempts)
        assertEquals(3, total.usageReportedAttempts)
        assertEquals(1, total.timestampedUsageAttempts)
        assertEquals(2, total.untimestampedUsageAttempts)
        assertEquals(2, total.pricedUsageAttempts)
        assertEquals(1, total.unpricedUsageAttempts)
        assertEquals(1, total.unreportedAttempts)
        assertEquals(TokenTotals(160, 1_850, 20, 52, 15), total.tokens)
        assertEquals(TokenTotals(100, 900, 20, 30, 10), total.retryTokens)
        assertEquals(2_030, total.tokens.billableInputTokens)
        assertEquals(2_082, total.tokens.totalTokens)
        assertAmount("0.911330", total.cacheHitRatio)
        assertEquals(
            mapOf(ModelFinishReason.STOP to 1, ModelFinishReason.MAX_TOKENS to 1),
            total.finishReasons,
        )
        assertAmount("0.00052500", total.costsByCurrency.getValue("CNY").total)
        assertAmount("0.00032000", total.retryCostsByCurrency.getValue("CNY").total)
        assertEquals(2, report.byModel.size)
        assertEquals(listOf(1, 2, 1, 1), report.attempts.map { it.attempt })
        assertEquals(Instant.parse("2026-08-19T10:15:30Z"), report.attempts.first().usageObservedAt)
        assertAmount("0.00032000", report.attempts.first().cost?.total)
        assertEquals(
            2,
            report.byModel.getValue(
                ModelUsageGroup("deepseek", "deepseek-v4-flash", "2026-08-cny", "CNY"),
            ).usageReportedAttempts,
        )
    }

    private fun assertAmount(expected: String, actual: BigDecimal?) {
        requireNotNull(actual)
        assertEquals(0, BigDecimal(expected).compareTo(actual))
    }
}
