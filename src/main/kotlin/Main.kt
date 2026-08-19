package com.attuchengmen

import com.attuchengmen.agent.Agent
import com.attuchengmen.agent.AgentOptions
import com.attuchengmen.agent.model.LanguageModel
import com.attuchengmen.agent.model.providers.DeepSeekAdapter
import com.attuchengmen.agent.model.providers.DeepSeekConfig
import com.attuchengmen.agent.session.JsonlFileSessionLog
import com.attuchengmen.agent.session.Session
import com.attuchengmen.agent.tool.ReadFileTool
import com.attuchengmen.agent.tool.ToolRegistry
import com.attuchengmen.config.AppConfig
import com.attuchengmen.config.AppConfigLoader
import java.nio.file.Path

/** 从 YAML 和环境变量组装具体能力，并运行一次完整 Agent Turn。 */
fun main(args: Array<String>) {
    val configPath = Path.of(System.getenv("DS_HARNESS_CONFIG") ?: "config.yaml")
    val config = AppConfigLoader.load(configPath)
    val task = args.joinToString(" ").ifBlank {
        print("Task: ")
        readlnOrNull().orEmpty()
    }
    require(task.isNotBlank()) { "task must not be blank" }

    val model = createModel(config)
    val session = Session(JsonlFileSessionLog(config.sessionPath))
    val tools = ToolRegistry(
        listOf(ReadFileTool(config.workspaceRoot, config.readFileMaxBytes)),
    )
    val agent = Agent(
        session,
        model,
        tools,
        AgentOptions(maxStepsPerTurn = config.agent.maxStepsPerTurn),
    )

    println(agent.submit(task).content)
}

/** Adapter 选择只存在于应用组装层，不进入 Agent Runtime。 */
private fun createModel(config: AppConfig): LanguageModel = when (config.model.provider) {
    "deepseek" -> {
        val apiKey = System.getenv(config.model.apiKeyEnv)
            ?.takeIf { it.isNotBlank() }
            ?: error("environment variable ${config.model.apiKeyEnv} is required")
        DeepSeekAdapter(
            DeepSeekConfig(
                apiKey = apiKey,
                model = config.model.model,
                baseUri = config.model.baseUri,
                connectTimeout = config.model.connectTimeout,
                requestTimeout = config.model.requestTimeout,
            ),
        )
    }
    else -> error("unsupported model provider \"${config.model.provider}\"")
}
