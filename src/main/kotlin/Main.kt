import com.attuchengmen.TerminalStreamRenderer
import com.attuchengmen.agent.Agent
import com.attuchengmen.agent.AgentOptions
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
import com.attuchengmen.agent.session.JsonlFileSessionLog
import com.attuchengmen.agent.session.Session
import com.attuchengmen.agent.tool.ReadFileTool
import com.attuchengmen.agent.tool.ToolRegistry
import com.attuchengmen.config.AppConfig
import com.attuchengmen.config.AppConfigLoader
import kotlinx.coroutines.CancellationException
import java.nio.file.Path

/** 从 YAML 和环境变量组装具体能力，并运行一次完整 Agent Turn。 */
suspend fun main(args: Array<String>) {
    val configPath = Path.of(System.getenv("DS_HARNESS_CONFIG") ?: "config.yaml")
    val config = AppConfigLoader.load(configPath)
    val task = args.joinToString(" ").ifBlank {
        print("Task: ")
        readlnOrNull().orEmpty()
    }
    require(task.isNotBlank()) { "task must not be blank" }

    val model = createModel(config)
    val memoryStore = JsonFileMemoryStore(config.memoryPath)
    val tools = ToolRegistry(
        listOf(
            ReadFileTool(config.workspaceRoot, config.readFileMaxBytes),
            MemoryWriteTool(memoryStore, config.memoryWriteMaxChars),
        ),
    )
    val sessionLog = JsonlFileSessionLog(config.sessionPath)
    val runtime = AgentRuntimeService(
        sessionFactory = { sessionId ->
            require(sessionLog.sessionId == sessionId) { "CLI session id does not match persisted log" }
            Session(sessionLog)
        },
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
        val sessionId = runtime.openSession(sessionLog.sessionId)
        val renderer = TerminalStreamRenderer(System.out)
        runtime.subscribe(sessionId, renderer::onEvent).use {
            try {
                val run = runtime.awaitRun(runtime.startRun(sessionId, task))
                when (val state = run.state) {
                    is RunState.Completed -> renderer.finish(state.response)
                    is RunState.MaxTokens -> renderer.finish(state.response)
                    is RunState.Failed -> error(state.message)
                    RunState.Cancelled -> throw CancellationException("agent run was cancelled")
                    RunState.Running -> error("awaited agent run is still running")
                }
            } catch (error: Exception) {
                renderer.finishFailure()
                throw error
            }
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
