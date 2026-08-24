# kotlin-agent-harness

这是一个用于学习和实现 Agent Runtime 的 Kotlin 项目。目标不是包装某个模型 API，而是逐步建立负责 Session、模型调用、工具执行和生命周期管理的通用运行时。

## 当前能力

- 使用追加式 `SessionEventEnvelope` 保存 Session ID、连续序号、发生时间和运行事实。
- 支持内存日志和 JSONL 文件日志，重启后可恢复 Session 事实。
- 提供多 Session `SessionEventStore` 端口和内存实现，通过 `expectedSequence` 拒绝并发写覆盖。
- JSONL 重启会追加修复崩溃时未闭合的工具结果、Step 和 Turn。
- 每次模型调用按 Token 预算保留当前 Turn 和连续的最近完整 Turn，超限时不拆断工具交换。
- 通过 `LanguageModel` 端口调用可替换的模型实现。
- 通过 `ToolRegistry` 执行工具，并用后续 Step 把结果交回模型。
- 可预期工具错误作为错误结果交回模型，允许模型在下一 Step 修正调用。
- 每次模型请求携带已注册工具的描述和参数 JSON Schema。
- Context 选择区间、估算器版本、预算、输出上限和工具定义写入 Session，可从日志重建准确请求。
- 每个 Turn 有可配置的最大 Step 数，防止模型无限调用工具。
- 模型与工具调用支持协程取消；取消会完整关闭当前 Step 和 Turn。
- Turn 总超时可配置，超时与调用方取消使用不同终态。
- 模型 Provider 可声明有界指数退避策略；Runtime 只重试明确标记的瞬时模型失败。
- Runtime 记录并组装 `ModelChunk` 流；非流式 Provider 通过兼容实现产生一个终态 chunk。
- `DeepSeekAdapter` 支持文本和单个工具调用的 SSE 增量组装，并在流长期无数据时中止和重试。
- Provider Usage、模型身份和价格快照持久化到 Session；支持精确成本、缓存率和重试浪费统计。
- 提供强制 tenant/user/agent Scope、来源追踪、版本冲突和遗忘语义的 MemoryStore；CLI 使用原子 JSON 快照持久化，并向主 LLM 注册由 Runtime 注入身份与来源的 `memory_write`。
- Session 在持久化后发布实时事件，终端按 attempt 增量显示模型文本、标记失败重试且不重复最终答案。
- `AgentRuntimeService` 在单进程内管理多个 Session 和异步 Run；同一 Session 拒绝并发 Run，不同 Session 可以并行。
- CLI 支持当前 Session 多轮对话，并可通过 `/new` 创建和切换到独立持久化 Session。
- 提供限制在工作区内、带大小上限的 `read_file` 工具。
- 使用 SLF4J + Logback 输出运行日志，使用 kotlinx.serialization 处理 JSON。

## 运行真实流程

模型、超时、Turn Step 上限、Session 和工作区配置位于 `config.yaml`。文件只保存环境变量名，不保存 API Key。

Memory 快照路径也由 `config.yaml` 配置。`memory_write` 只有在新快照原子替换成功后才向模型返回成功；当前 Memory 尚未进入模型 Context，读取链路将在下一阶段接入。

```bash
read -s DEEPSEEK_API_KEY
export DEEPSEEK_API_KEY
./gradlew run
unset DEEPSEEK_API_KEY
```

启动后直接输入消息即可连续对话。控制命令：

```text
/new      创建并切换到新 Session
/session  显示当前 Session ID
/exit     退出
```

`--args='第一条消息'` 可以提供启动后的首条消息，随后仍进入交互循环。使用其他配置文件时设置 `DS_HARNESS_CONFIG=/absolute/path/config.yaml`。

## 文件 Session

```kotlin
val session = Session(JsonlFileSessionLog(Path.of("data/session.jsonl")))
```

每个 `SessionEventEnvelope` 占一行。重新构造 `JsonlFileSessionLog` 会恢复原 Session ID、序号、时间和事件；错误归属、跳号或损坏 JSON 会报告文件路径和行号，不会被静默忽略。

CLI 的 `session.directory` 为每个 Session 保存独立 JSONL 文件。文件名由 Session ID 的 SHA-256 生成，Session ID 不能形成路径穿越；长期 Memory 仍由所有 Session 共享。

当前 JSONL 格式版本为 `1`。引入事件信封之前生成的预发布日志不包含可靠 Session ID 和时间，系统会明确拒绝，不会猜测或静默迁移；需要保留时应先归档旧文件。

## 阅读入口

先阅读 [Agent Runtime 架构与核心知识](docs/agent-runtime-architecture.md)，再通过[开发与架构学习指南](docs/development-guide.md)进入源码。后续架构分别见 [Memory 与 Context Management](docs/memory-context-architecture.md)以及 [Token Usage 与商业计量](docs/token-usage.md)。`src/main/kotlin/Main.kt` 是应用组装入口。

## 验证

```bash
./gradlew test
```

真实 DeepSeek 冒烟测试需要显式提供凭据和模型，否则会跳过：

```bash
DEEPSEEK_API_KEY=... DEEPSEEK_MODEL=... \
  ./gradlew test --tests '*DeepSeekLiveTest'
```

可选 `DEEPSEEK_BASE_URL`，默认使用 `https://api.deepseek.com`。密钥只进入 Adapter 的 Authorization Header，不写入 Session 或日志。
