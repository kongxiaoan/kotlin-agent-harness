package com.attuchengmen.agent.model

import java.math.BigDecimal
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith

class ModelAccountingTest {
    @Test
    fun `usage buckets are disjoint and reasoning is output detail`() {
        val usage = TokenUsage(
            inputTokens = 100,
            outputTokens = 30,
            cacheReadTokens = 900,
            cacheWriteTokens = 20,
            reasoningTokens = 10,
        )

        assertEquals(1_020, usage.billableInputTokens)
        assertEquals(1_050, usage.totalTokens)
    }

    @Test
    fun `reasoning tokens cannot exceed output tokens`() {
        assertFailsWith<IllegalArgumentException> {
            TokenUsage(inputTokens = 1, outputTokens = 2, reasoningTokens = 3)
        }
    }

    @Test
    fun `pricing calculates every token bucket without floating point`() {
        val pricing = ModelPricing(
            version = "2026-08-cny",
            currency = "CNY",
            inputPerMillion = BigDecimal("1.00"),
            cacheReadPerMillion = BigDecimal("0.10"),
            cacheWritePerMillion = BigDecimal("2.00"),
            outputPerMillion = BigDecimal("3.00"),
        )

        val cost = pricing.calculate(
            TokenUsage(
                inputTokens = 100,
                outputTokens = 30,
                cacheReadTokens = 900,
                cacheWriteTokens = 20,
                reasoningTokens = 10,
            ),
        )

        assertAmount("0.00010000", cost.input)
        assertAmount("0.00009000", cost.cacheRead)
        assertAmount("0.00004000", cost.cacheWrite)
        assertAmount("0.00009000", cost.output)
        assertAmount("0.00032000", cost.total)
    }

    private fun assertAmount(expected: String, actual: BigDecimal) {
        assertEquals(0, BigDecimal(expected).compareTo(actual))
    }
}
