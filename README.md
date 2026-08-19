# kotlin-agent-harness

这是一个用于学习和实现 Agent Runtime 的 Kotlin 项目。目标不是包装某个模型 API，而是逐步建立负责 Session、模型调用、工具执行和生命周期管理的通用运行时。

## 当前能力

- 使用追加式 `SessionEvent` 记录 Agent 的运行事实。
- 支持内存日志和 JSONL 文件日志，重启后可恢复 Session 事实。
- JSONL 重启会追加修复崩溃时未闭合的工具结果、Step 和 Turn。
- 从事件日志投影模型需要的消息历史。
- 通过 `LanguageModel` 端口调用可替换的模型实现。
- 通过 `ToolRegistry` 执行工具，并用后续 Step 把结果交回模型。
- 可预期工具错误作为错误结果交回模型，允许模型在下一 Step 修正调用。
- 每次模型请求携带已注册工具的描述和参数 JSON Schema。
- 模型请求边界和当时的工具定义写入 Session，可从日志重建准确请求。
- 每个 Turn 有可配置的最大 Step 数，防止模型无限调用工具。
- 模型与工具调用支持协程取消；取消会完整关闭当前 Step 和 Turn。
- Turn 总超时可配置，超时与调用方取消使用不同终态。
- 模型 Provider 可声明有界指数退避策略；Runtime 只重试明确标记的瞬时模型失败。
- Runtime 记录并组装 `ModelChunk` 流；非流式 Provider 通过兼容实现产生一个终态 chunk。
- `DeepSeekAdapter` 支持纯文本 SSE；携带工具定义的请求暂时保留非流式路径。
- 提供限制在工作区内、带大小上限的 `read_file` 工具。
- 使用 SLF4J + Logback 输出运行日志，使用 kotlinx.serialization 处理 JSON。

## 运行真实流程

模型、超时、Turn Step 上限、Session 和工作区配置位于 `config.yaml`。文件只保存环境变量名，不保存 API Key。

```bash
read -s DEEPSEEK_API_KEY
export DEEPSEEK_API_KEY
./gradlew run --args='读取 README.md 并总结项目用途'
unset DEEPSEEK_API_KEY
```

使用其他配置文件时设置 `DS_HARNESS_CONFIG=/absolute/path/config.yaml`。没有 `--args` 时，Main 会从终端读取一行任务。

## 文件 Session

```kotlin
val session = Session(JsonlFileSessionLog(Path.of("data/session.jsonl")))
```

每个 `SessionEvent` 占一行。重新构造 `JsonlFileSessionLog` 会按原顺序恢复事件；损坏数据会报告文件路径和行号，不会被静默忽略。

## 阅读入口

先阅读 [开发与架构学习指南](docs/development-guide.md)，再按其中标注的顺序阅读源码。`src/main/kotlin/Main.kt` 是应用组装入口。

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
