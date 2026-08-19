# 开发与架构学习指南

## 项目愿景

本项目最终实现一个可测试、可恢复、可扩展的 Kotlin Agent Runtime。它负责持续驱动模型与工具协作，并管理上下文、Session、执行状态、取消和错误；具体模型和业务工具由外部实现。

当前阶段支持 DeepSeek 文本与单工具 SSE、可取消的多 Step 工具执行、YAML 应用配置和 JSONL Session 持久化；暂不支持并行工具调用和插件框架。

## 当前数据流

```text
Agent.submit(content)
  → Step 1：记录用户消息并调用模型
  → 调用前记录 ModelRequestPrepared
  → 模型请求工具：ToolRegistry.execute
  → 记录 ToolCall 与 ToolResult
  → Step 2：从 Session 重新投影上下文并调用模型
  → 模型返回最终答案
  → 记录并返回 AssistantMessage
```

如果模型调用失败，异常向调用者传播。用户输入已经成为运行事实，因此继续保留；系统不会伪造 Agent 回复。

`Agent.submit` 是挂起函数。同一 Agent 的并发提交会串行执行，避免不同 Turn 的事件交错；调用方取消时，当前 Step 先记录结束，Turn 记录 `Cancelled`，随后原样传播 `CancellationException`。

`AgentOptions.turnTimeout` 限制一个 Turn 内所有模型与工具调用的总时间。超时会先取消并等待当前 Step 清理，再记录 `TimedOut` 并抛出 `TurnTimeoutExceededException`；它与外部调用方取消是两个不同事实。

模型重试遵循 Harness 的请求恢复语义：重试仍发生在原 Turn 和原 Step 内。策略由模型 Provider 通过 `LanguageModel.retryPolicy` 提供；Runtime 只处理 `ModelRequestException(retryable = true)`，达到上限后传播最后一次失败。每次请求都有递增 `attempt` 的 `ModelRequestPrepared`，等待计划记录为 `ModelRetryScheduled`，因此恢复和观测不依赖内存计数。工具异常、协议错误、鉴权错误和普通 4xx 不会被重试。

模型调用统一经过 `LanguageModel.stream`。每个原始 `ModelChunk` 先记录为不进入消息投影的 `ModelChunkReceived`，再交给 `ModelChunkAssembler` 验证并生成唯一的最终 `ModelResponse`；只有最终响应会产生 `AssistantMessageAdded` 或 `ToolCallRequested`。非流式 Provider 使用默认实现把 `generate` 结果包装成一个 `Finished` chunk。DeepSeek Adapter 按 wire `index` 累积工具 id、名称和原始 JSON 参数；当前领域模型只允许一个工具调用，因此第二个 index 明确失败。

## 代码阅读顺序

1. `message/Message.kt`：模型能够看到的数据。
2. `session/SessionEvent.kt`：Agent 实际发生过的事实。
3. `session/SessionLog.kt`：内存与持久化实现共同遵守的追加接口。
4. `session/Session.kt`：Session 对存储实现的封装。
5. `session/SessionEventJson.kt`：领域事件和文件 DTO 的显式映射。
6. `session/JsonlFileSessionLog.kt`：JSONL 追加与加载。
7. `session/SessionRecovery.kt`：崩溃尾部如何补齐工具、Step 和 Turn。
8. `session/SessionProjector.kt`：事实如何转换成模型上下文。
9. `model/LanguageModel.kt`：核心代码依赖的模型端口。
10. `tool/`：工具定义、注册表和受工作区限制的文件读取实现。
11. `Agent.kt`：驱动 Turn、Step、模型和工具。
12. Session 测试：验证格式往返、恢复和损坏文件失败。
13. 其余测试：验证投影、内存日志和 Agent 编排行为。

阅读时对每个模块回答：它拥有什么状态、谁能修改状态、输入输出是什么、失败如何传播、它依赖哪些模块。

## 核心概念

### Session Event 与 Message

`SessionEvent` 是事实源，记录 Agent 按顺序发生了什么。`Message` 是从部分事实计算出的模型上下文。并非所有事件都进入模型，例如 `TurnStarted` 需要记录，但不应发送给模型。

```text
SessionEvent Log = 唯一事实源
Message List     = 可重新计算的投影
```

`ModelRequestPrepared` 记录请求发生的位置和当时可见的工具定义。请求消息不重复保存，而是从该事件之前的消息事实重建；因此 Session Log 可以还原每个 Step 实际发送的 `ModelRequest`。

### 为什么使用 sealed interface

事件和消息目前是封闭集合。使用 `sealed interface` 后，`when` 可以穷尽检查；新增事件时，编译器会要求投影器明确决定它是否进入模型上下文。相比字符串类型，这能避免拼写错误和未处理分支。

### 为什么模型是接口

Agent Runtime 只需要“完整请求产生一个结果”的能力，不应该依赖具体供应商。`ModelRequest` 同时携带消息历史和工具定义；测试可以使用 Fake Model，未来的 HTTP Provider 也实现同一个端口。当前只有一个真实需求，因此没有 Factory、Provider Registry 或依赖注入框架。

`DeepSeekAdapter` 是当前第一个真实实现。它独立拥有 URL、Bearer 鉴权、模型名、超时、DeepSeek JSON DTO、SSE 解析和 HTTP/协议异常；这些内容不会进入 `Agent`。当前显式关闭 thinking，因为 Session 尚未保存 reasoning content；SSE 必须以 `[DONE]` 结束，文本和工具参数均可分片，多工具响应会明确失败。

### 工具定义

每个 `Tool` 同时提供执行实现和模型可见的 `ToolDefinition`。`ToolRegistry` 保持注册顺序，并在每个 Step 组装 `ModelRequest` 时提供名称、描述和参数 JSON Schema；真实模型因此能够决定是否以及如何调用工具。

`ToolException` 表示参数错误、未知工具和文件读取失败等可预期结果。Agent 将其记录为 `ToolResultAdded(isError = true)` 并继续下一 Step，让模型修正调用。其他异常包装为不暴露内部细节的 `UnexpectedToolException`，记录稳定错误文本后终止 Turn。

### Step 上限

`AgentOptions.maxStepsPerTurn` 和 `turnTimeout` 是应用层必须提供的部署配置。达到 Step 上限时，Agent 在创建下一 Step 前抛出 `StepLimitExceededException`；达到总时间限制时取消当前执行。两条路径都会先关闭当前 Step，再以明确终态关闭 Turn。

### SessionLog 与 JSONL

`Session` 依赖 `SessionLog`，默认使用 `InMemorySessionLog`；需要跨进程恢复时注入 `JsonlFileSessionLog`。文件实现每次先完成磁盘追加，再更新内存快照，避免写入失败后内存声称事件已经持久化。

`Session.subscribe` 对成功追加后的事件进行实时广播，不重放历史。持久化失败不会通知观察者；观察者失败也不会反转已经完成的持久化。应用入口使用 `TerminalStreamRenderer` 消费 `ModelChunkReceived.TextDelta`，Session 和 Agent 不依赖终端展示策略。

JSON DTO 与领域事件分离。`SessionEventJson` 显式完成双向映射，因此磁盘字段属于存储边界，不要求核心领域类型携带序列化注解。

文件加载采用失败即停止：未知事件或无效 JSON 会抛出包含路径和行号的 `SessionLogFormatException`。当前不支持多个进程同时写一个文件，也不承诺断电级 `fsync` 持久性。

加载发现开放 Turn 时，`SessionRecovery` 只向日志尾部追加修复事件，不改写历史。未配对的 `ToolCallRequested` 会得到 `isError = true` 的未知结果，然后依次补齐 `StepEnded` 和 `TurnOutcome.Interrupted`。工具可能已经产生外部副作用，因此恢复信息要求模型仅对只读或幂等工具重试；其他工具必须先核对外部状态。

### 运行日志

业务代码依赖 SLF4J API，Logback 是当前运行时 Provider，配置位于 `src/main/resources/logback.xml`。Session 日志只输出路径、事件类型和数量，不记录消息正文或工具参数，避免把用户内容和潜在敏感数据复制到运维日志。

## 如何分析一个需求

不要先创建类。按以下顺序分析：

### 1. 明确行为

用一句话描述用户能够观察到的结果。例如：“提交用户内容后，模型收到完整历史，回复被记录并返回。”

### 2. 找出输入、输出和规则

```text
输入：用户内容、已有 Session、模型能力
输出：AssistantMessage
规则：先记录输入；从日志投影历史；成功后记录回复
```

### 3. 找出不变量

不变量是任何实现都必须保持的事实，例如：

- 事件只能按发生顺序追加。
- 外部不能修改 Session 内部日志。
- 模型上下文必须能够从日志重建。
- 模型失败时不能记录不存在的回复。

### 4. 划分职责

让一个模块只回答一个主要问题：

- `Session`：事实如何保存？
- `SessionProjector`：事实如何变成消息？
- `LanguageModel`：如何请求模型能力？
- `Agent`：一次交互按什么顺序执行？

### 5. 分析失败路径

至少检查：操作在哪一步失败、之前发生的事实是否保留、是否产生虚假状态、异常由谁处理。本阶段选择传播模型异常，并保留已经追加的用户消息。

### 6. 决定测试策略

确定性业务逻辑、状态转换、投影和错误恢复适合 TDD。第三方 SDK 探索可以先做小型实验，确认行为后再补测试。测试应描述可观察行为，不依赖私有字段。

### 7. 实现最小正确改动

只实现当前验收标准。不要提前加入数据库、Event Bus 或通用插件系统。新抽象必须回答：“它正在降低哪个已经存在的变化成本？”

## 每个需求的开发流程

```text
Problem
→ Existing Design
→ Proposed Change
→ Risks
→ Test Strategy
→ Red
→ Green
→ Refactor
→ Verification
```

提交设计时可使用：

```text
1. 需求要解决什么问题？
2. 输入和输出是什么？
3. 有哪些不变量？
4. 哪个模块拥有状态？
5. 正常数据流是什么？
6. 失败发生后留下什么状态？
7. 为什么选择这个设计？
8. 哪些内容明确不做？
9. 如何验证？
```

## 当前明确不做

- 一个模型响应中的并行工具调用
- Retry-After、随机抖动和无限重试模式
- 插件系统和 Subagent

这些会在核心行为出现真实需要时逐步加入，而不是提前设计。
