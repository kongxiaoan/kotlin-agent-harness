package com.attuchengmen

import com.attuchengmen.agent.Agent
import com.attuchengmen.agent.AgentOptions
import com.attuchengmen.cli.CliCommand
import com.attuchengmen.cli.CliCommandParser
import com.attuchengmen.agent.context.ConservativeUtf8TokenEstimator
import com.attuchengmen.agent.context.ContextManager
import com.attuchengmen.agent.context.ContextWindow
import com.attuchengmen.agent.model.LanguageModel
import com.attuchengmen.agent.memory.JsonFileMemoryStore
import com.attuchengmen.agent.memory.MemoryWriteTool
import com.attuchengmen.agent.model.providers.DeepSeekAdapter
import com.attuchengmen.agent.model.providers.DeepSeekConfig
import com.attuchengmen.agent.runtime.AgentRuntimeService
import com.attuchengmen.agent.runtime.RunState
import com.attuchengmen.agent.session.Session
import com.attuchengmen.agent.session.SessionFileRepository
import com.attuchengmen.agent.session.SessionId
import com.attuchengmen.agent.tool.ReadFileTool
import com.attuchengmen.agent.tool.ToolRegistry
import com.attuchengmen.config.AppConfig
import com.attuchengmen.config.AppConfigLoader
import kotlinx.coroutines.CancellationException
import java.nio.file.Path

/** 从 YAML 和环境变量组装 Agent Runtime，并启动多 Session 交互循环。 */
suspend fun main(args: Array<String>) {
    val configPath = Path.of(System.getenv("DS_HARNESS_CONFIG") ?: "config.yaml")
    val config = AppConfigLoader.load(configPath)
    val model = createModel(config)
    val memoryStore = JsonFileMemoryStore(config.memoryPath)
    val sessionFiles = SessionFileRepository(config.sessionDirectory)
    val tools = ToolRegistry(
        listOf(
            ReadFileTool(config.workspaceRoot, config.readFileMaxBytes),
            MemoryWriteTool(memoryStore, config.memoryWriteMaxChars),
        ),
    )
    val runtime = AgentRuntimeService(
        sessionFactory = { sessionId -> Session(sessionFiles.open(sessionId)) },
        agentFactory = { session ->
            Agent(
                session,
                model,
                tools,
                AgentOptions(
                    maxStepsPerTurn = config.agent.maxStepsPerTurn,
                    turnTimeout = config.agent.turnTimeout,
                ),
                contextManager = ContextManager(
                    ContextWindow(
                        contextWindowTokens = config.model.contextWindowTokens,
                        maxOutputTokens = config.model.maxOutputTokens,
                        safetyMarginTokens = config.model.contextSafetyMarginTokens,
                    ),
                    ConservativeUtf8TokenEstimator,
                ),
                identity = config.identity,
            )
        },
    )
    runtime.use { runtime ->
        runChat(runtime, args.joinToString(" ").takeIf(String::isNotBlank))
    }
}

/** 在当前 Session 连续提交消息，并处理 Session 控制命令。 */
private suspend fun runChat(runtime: AgentRuntimeService, initialInput: String?) {
    var sessionId = runtime.createSession()
    var pendingInput = initialInput
    println("Session: ${sessionId.value}")
    println("Commands: /new, /session, /exit")
    while (true) {
        val input = pendingInput ?: run {
            print("You: ")
            System.out.flush()
            readlnOrNull() ?: return
        }
        pendingInput = null
        when (val command = CliCommandParser.parse(input)) {
            null -> Unit
            is CliCommand.Submit -> submit(runtime, sessionId, command.content)
            CliCommand.NewSession -> {
                sessionId = runtime.createSession()
                println("Session: ${sessionId.value}")
            }
            CliCommand.ShowSession -> println("Session: ${sessionId.value}")
            CliCommand.Exit -> return
            is CliCommand.Invalid -> println(command.message)
        }
    }
}

/** 提交一个 Turn，并把流事件和最终状态渲染到终端。 */
private suspend fun submit(runtime: AgentRuntimeService, sessionId: SessionId, content: String) {
    val renderer = TerminalStreamRenderer(System.out)
    print("Agent: ")
    System.out.flush()
    runtime.subscribe(sessionId, renderer::onEvent).use {
        try {
            val run = runtime.awaitRun(runtime.startRun(sessionId, content))
            when (val state = run.state) {
                is RunState.Completed -> renderer.finish(state.response)
                is RunState.MaxTokens -> renderer.finish(state.response)
                is RunState.Failed -> {
                    renderer.finishFailure()
                    println("[error: ${state.message}]")
                }
                RunState.Cancelled -> {
                    renderer.finishFailure()
                    println("[cancelled]")
                }
                RunState.Running -> error("awaited agent run is still running")
            }
        } catch (error: CancellationException) {
            renderer.finishFailure()
            throw error
        }
    }
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
                streamIdleTimeout = config.model.streamIdleTimeout,
                retryPolicy = config.model.retryPolicy,
                pricing = config.model.pricing,
            ),
        )
    }
    else -> error("unsupported model provider \"${config.model.provider}\"")
}
