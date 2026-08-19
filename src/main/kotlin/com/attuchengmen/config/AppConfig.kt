package com.attuchengmen.config

import com.attuchengmen.agent.model.ModelRetryPolicy
import org.snakeyaml.engine.v2.api.Load
import org.snakeyaml.engine.v2.api.LoadSettings
import java.net.URI
import java.nio.file.Files
import java.nio.file.Path
import java.time.Duration

/** 应用入口组装 Agent Runtime 所需的非敏感配置。 */
data class AppConfig(
    val model: ModelConfig,
    val agent: AgentConfig,
    val sessionPath: Path,
    val workspaceRoot: Path,
    val readFileMaxBytes: Int,
)

/** Agent Loop 的部署限制。 */
data class AgentConfig(
    val maxStepsPerTurn: Int,
    val turnTimeout: Duration,
)

/** 模型 Adapter 的部署配置；[apiKeyEnv] 是环境变量名而不是密钥。 */
data class ModelConfig(
    val provider: String,
    val apiKeyEnv: String,
    val model: String,
    val baseUri: URI,
    val connectTimeout: Duration,
    val requestTimeout: Duration,
    val retryPolicy: ModelRetryPolicy,
)

/** YAML 配置无法安全映射为受支持的应用配置。 */
class AppConfigException(
    message: String,
    cause: Throwable? = null,
) : IllegalArgumentException(message, cause)

/** 严格读取 YAML，并相对配置文件目录解析文件系统路径。 */
object AppConfigLoader {
    private val yaml = Load(
        LoadSettings.builder()
            .setAllowDuplicateKeys(false)
            .setCodePointLimit(1_000_000)
            .build(),
    )

    fun load(path: Path): AppConfig {
        val document = try {
            Files.newBufferedReader(path).use(yaml::loadFromReader)
        } catch (error: Exception) {
            throw AppConfigException("cannot load config $path", error)
        }
        val root = ConfigNode.mapping(document, "config")
        root.requireOnly("model", "agent", "session", "workspace")

        val model = root.child("model")
        model.requireOnly(
            "provider",
            "api-key-env",
            "model",
            "base-url",
            "connect-timeout-seconds",
            "request-timeout-seconds",
            "retry",
        )
        val retry = model.child("retry")
        retry.requireOnly("max-retries", "initial-delay-ms", "max-delay-ms")
        val session = root.child("session")
        session.requireOnly("path")
        val workspace = root.child("workspace")
        workspace.requireOnly("root", "read-file-max-bytes")
        val agent = root.child("agent")
        agent.requireOnly("max-steps-per-turn", "turn-timeout-seconds")

        val configDirectory = path.toAbsolutePath().normalize().parent
            ?: throw AppConfigException("config path must have a parent directory")
        return AppConfig(
            model = ModelConfig(
                provider = model.string("provider"),
                apiKeyEnv = model.string("api-key-env"),
                model = model.string("model"),
                baseUri = model.uri("base-url"),
                connectTimeout = Duration.ofSeconds(model.positiveLong("connect-timeout-seconds")),
                requestTimeout = Duration.ofSeconds(model.positiveLong("request-timeout-seconds")),
                retryPolicy = ModelRetryPolicy(
                    maxRetries = retry.nonNegativeInt("max-retries"),
                    initialDelay = Duration.ofMillis(retry.positiveLong("initial-delay-ms")),
                    maxDelay = Duration.ofMillis(retry.positiveLong("max-delay-ms")),
                ),
            ),
            agent = AgentConfig(
                maxStepsPerTurn = agent.positiveInt("max-steps-per-turn"),
                turnTimeout = Duration.ofSeconds(agent.positiveLong("turn-timeout-seconds")),
            ),
            sessionPath = resolvePath(configDirectory, session.string("path")),
            workspaceRoot = resolvePath(configDirectory, workspace.string("root")),
            readFileMaxBytes = workspace.positiveInt("read-file-max-bytes"),
        )
    }
}

private class ConfigNode(
    private val values: Map<String, Any?>,
    private val location: String,
) {
    fun requireOnly(vararg allowed: String) {
        val unknown = values.keys - allowed.toSet()
        if (unknown.isNotEmpty()) {
            throw AppConfigException("$location contains unknown key \"${unknown.sorted().first()}\"")
        }
    }

    fun child(key: String): ConfigNode = mapping(required(key), "$location.$key")

    fun string(key: String): String {
        val value = required(key)
        if (value !is String || value.isBlank()) {
            throw AppConfigException("$location.$key must be a non-blank string")
        }
        return value
    }

    fun positiveLong(key: String): Long {
        val value = required(key)
        val number = value as? Number
            ?: throw AppConfigException("$location.$key must be a positive integer")
        val result = number.toLong()
        if (result <= 0 || number.toDouble() != result.toDouble()) {
            throw AppConfigException("$location.$key must be a positive integer")
        }
        return result
    }

    fun positiveInt(key: String): Int {
        val value = positiveLong(key)
        if (value > Int.MAX_VALUE) throw AppConfigException("$location.$key is too large")
        return value.toInt()
    }

    fun nonNegativeInt(key: String): Int {
        val value = required(key)
        val number = value as? Number
            ?: throw AppConfigException("$location.$key must be a non-negative integer")
        val result = number.toLong()
        if (result < 0 || result > Int.MAX_VALUE || number.toDouble() != result.toDouble()) {
            throw AppConfigException("$location.$key must be a non-negative integer")
        }
        return result.toInt()
    }

    fun uri(key: String): URI = try {
        URI(string(key))
    } catch (error: IllegalArgumentException) {
        throw AppConfigException("$location.$key must be a valid URI", error)
    }

    private fun required(key: String): Any? =
        values[key] ?: throw AppConfigException("$location.$key is required")

    companion object {
        fun mapping(value: Any?, location: String): ConfigNode {
            val source = value as? Map<*, *>
                ?: throw AppConfigException("$location must be a mapping")
            val result = linkedMapOf<String, Any?>()
            for ((key, entry) in source) {
                if (key !is String) throw AppConfigException("$location keys must be strings")
                result[key] = entry
            }
            return ConfigNode(result, location)
        }
    }
}

private fun resolvePath(directory: Path, configured: String): Path {
    val path = Path.of(configured)
    return (if (path.isAbsolute) path else directory.resolve(path)).normalize()
}
