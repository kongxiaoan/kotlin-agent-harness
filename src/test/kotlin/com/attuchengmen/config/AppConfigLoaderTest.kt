package com.attuchengmen.config

import java.nio.file.Files
import java.time.Duration
import kotlin.io.path.writeText
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith

class AppConfigLoaderTest {
    @Test
    fun `loads configuration and resolves paths relative to yaml`() {
        val root = Files.createTempDirectory("app-config-test")
        try {
            val path = root.resolve("config.yaml")
            path.writeText(validConfig)

            val config = AppConfigLoader.load(path)

            assertEquals("tenant-1", config.identity.tenantId.value)
            assertEquals("user-1", config.identity.userId.value)
            assertEquals("agent-1", config.identity.agentId.value)
            assertEquals("deepseek", config.model.provider)
            assertEquals("DEEPSEEK_API_KEY", config.model.apiKeyEnv)
            assertEquals("deepseek-v4-flash", config.model.model)
            assertEquals(Duration.ofSeconds(30), config.model.streamIdleTimeout)
            assertEquals(1_000_000, config.model.contextWindowTokens)
            assertEquals(8192, config.model.maxOutputTokens)
            assertEquals(2048, config.model.contextSafetyMarginTokens)
            assertEquals("deepseek-v4-flash-2026-08-19-cny", config.model.pricing.version)
            assertEquals("CNY", config.model.pricing.currency)
            assertEquals(0, config.model.pricing.cacheReadPerMillion.compareTo("0.02".toBigDecimal()))
            assertEquals(2, config.model.retryPolicy.maxRetries)
            assertEquals(root.resolve("data/session.jsonl"), config.sessionPath)
            assertEquals(root.resolve("data/memory.json"), config.memoryPath)
            assertEquals(4096, config.memoryWriteMaxChars)
            assertEquals(root, config.workspaceRoot)
            assertEquals(1_048_576, config.readFileMaxBytes)
            assertEquals(8, config.agent.maxStepsPerTurn)
            assertEquals(Duration.ofSeconds(120), config.agent.turnTimeout)
        } finally {
            root.toFile().deleteRecursively()
        }
    }

    @Test
    fun `unknown configuration key is rejected`() {
        val root = Files.createTempDirectory("app-config-test")
        try {
            val path = root.resolve("config.yaml")
            path.writeText(validConfig.replace("provider: deepseek", "provider: deepseek\n  typo: value"))

            val failure = assertFailsWith<AppConfigException> {
                AppConfigLoader.load(path)
            }

            assertEquals("config.model contains unknown key \"typo\"", failure.message)
        } finally {
            root.toFile().deleteRecursively()
        }
    }

    private companion object {
        private val validConfig = """
            identity:
              tenant-id: tenant-1
              user-id: user-1
              agent-id: agent-1
            model:
              provider: deepseek
              api-key-env: DEEPSEEK_API_KEY
              model: deepseek-v4-flash
              base-url: https://api.deepseek.com
              connect-timeout-seconds: 10
              request-timeout-seconds: 60
              stream-idle-timeout-seconds: 30
              context-window-tokens: 1000000
              max-output-tokens: 8192
              context-safety-margin-tokens: 2048
              pricing:
                version: deepseek-v4-flash-2026-08-19-cny
                currency: CNY
                input-per-million: "1.00"
                cache-read-per-million: "0.02"
                cache-write-per-million: "0"
                output-per-million: "2.00"
              retry:
                max-retries: 2
                initial-delay-ms: 500
                max-delay-ms: 4000
            session:
              path: data/session.jsonl
            memory:
              path: data/memory.json
              write-max-chars: 4096
            workspace:
              root: .
              read-file-max-bytes: 1048576
            agent:
              max-steps-per-turn: 8
              turn-timeout-seconds: 120
        """.trimIndent()
    }
}
