package com.attuchengmen.config

import java.nio.file.Files
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

            assertEquals("deepseek", config.model.provider)
            assertEquals("DEEPSEEK_API_KEY", config.model.apiKeyEnv)
            assertEquals("deepseek-v4-flash", config.model.model)
            assertEquals(root.resolve("data/session.jsonl"), config.sessionPath)
            assertEquals(root, config.workspaceRoot)
            assertEquals(1_048_576, config.readFileMaxBytes)
            assertEquals(8, config.agent.maxStepsPerTurn)
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
            model:
              provider: deepseek
              api-key-env: DEEPSEEK_API_KEY
              model: deepseek-v4-flash
              base-url: https://api.deepseek.com
              connect-timeout-seconds: 10
              request-timeout-seconds: 60
            session:
              path: data/session.jsonl
            workspace:
              root: .
              read-file-max-bytes: 1048576
            agent:
              max-steps-per-turn: 8
        """.trimIndent()
    }
}
