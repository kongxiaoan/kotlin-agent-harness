# Agent Runtime 架构与核心知识

## 应该怎样称呼

对外介绍整个核心模块时，推荐称为 **Agent Runtime（Agent 运行时）**。

| 名称 | 准确含义 | 适用场景 |
|---|---|---|
| Agent | 一个使用模型和工具完成任务的执行主体；在本项目中也是核心编排类 | 描述一个具体执行实例 |
| Agent Loop | Runtime 内部反复执行“模型判断 → 工具执行 → 模型继续判断”的控制循环 | 讲解核心算法 |
| Agent Runtime | 承载 Agent Loop，并管理 Session、模型、工具、状态、重试、取消和计量的通用运行内核 | 介绍本项目核心能力 |
| Agent System | Runtime 加上 HTTP、数据库、认证、租户、任务队列、监控和具体业务工具后的完整系统 | 介绍可部署产品 |

因此，本项目当前最准确的介绍是：

> 这是一个 Kotlin 实现的 Agent Runtime。它以追加式事件日志记录运行事实，在可恢复的 Session 中编排 LLM 与工具，并管理流式响应、重试、超时、取消和 Token Usage。

不能把 Agent Runtime 简化成 Agent Loop。Loop 只是控制算法，Runtime 才是让这段算法能够可靠运行的基础设施。

## 什么是 Agent Runtime

Agent Runtime 是运行 Agent 的通用执行内核。它接收用户任务，构造模型上下文，让模型选择回答或调用工具，并持续推进执行，直到产生最终结果或进入明确终态。

从工程角度看，它至少负责五件事：

1. **编排**：驱动模型判断、工具执行和后续模型调用。
2. **状态**：管理 Session、Turn、Step 和 Attempt 的生命周期。
3. **上下文**：从可信事实构造下一次模型请求，而不是依赖临时消息数组。
4. **执行控制**：处理流式响应、重试、超时、取消、Step 上限和失败传播。
5. **记录与观测**：保存运行事件、工具结果、模型 Usage 和最终终态，用于恢复、审计和统计。

普通模型 API 完成一次输入到输出：

```text
Request → LLM → Response
```

Agent Runtime 要持续运行，直到模型给出答案或执行被终止：

```text
用户任务
  → LLM 判断
  → 如果需要工具：执行工具并记录结果
  → 把结果交回 LLM 再次判断
  → 最终答案或明确终态
```

模型负责判断下一步做什么；Runtime 负责真正执行、保存事实并约束执行过程。Runtime 不应绑定某一家模型，也不应包含具体业务规则。业务能力通过 Prompt、Tool、权限和外围应用注入。

### 与相邻概念的区别

| 概念 | 负责什么 | 不负责什么 |
|---|---|---|
| 模型 API / SDK | 把请求发送给模型并返回响应 | 不持续管理工具循环和 Session 生命周期 |
| Agent Loop | 决定何时调用模型、工具以及何时结束 | 不代表完整的持久化、恢复和执行基础设施 |
| Agent Runtime | 可靠地承载 Loop、状态、上下文、工具和执行控制 | 不直接等于某个具体业务产品 |
| Agent Framework | 提供创建 Agent 的抽象、组件和开发 API | 只有在实际运行时才承担 Runtime 职责 |
| Agent Application | 使用 Runtime 和业务工具解决具体需求 | 通用运行机制通常交给 Runtime |

所以它是偏基础设施的通用技术，但不是脱离业务的“纯算法”。Runtime 决定 Agent 能否可靠执行，Prompt、Tool、权限和工作流决定它能解决什么业务问题。

## 当前架构

```text
Main（应用组装）
  │
  ▼
Agent（Turn / Step 编排）
  ├── SessionProjector ──> ModelRequest ──> LanguageModel
  │                                         └── DeepSeekAdapter
  ├── ToolRegistry ──────> Tool
  │                         └── ReadFileTool
  └── Session ───────────> SessionLog
                            ├── InMemorySessionLog
                            └── JsonlFileSessionLog

SessionEvent ──> 恢复 / 消息投影 / 实时展示 / Token Usage 统计
```

核心依赖方向是：`Agent` 依赖 `LanguageModel`，并通过 `Session` 使用 `SessionLog` 存储接口，不依赖 DeepSeek、JSONL 或终端。具体 Provider、存储和展示在应用组装层接入。

## 一次执行的层级

```text
Session
  └── Turn：一次用户提交到一个明确终态
       └── Step：一次模型判断，以及它要求的一次工具处理
            └── Attempt：同一个模型请求发生瞬时失败后的重试次数
```

- `Session` 保存连续交互的全部运行事实。
- `Turn` 从 `Agent.submit()` 开始，以完成、截断、超时、取消或失败结束。
- `Step` 每调用一次模型增加一次；模型调用工具后，下一次判断属于新 Step。
- `Attempt` 是同一 Step 内的 Provider 重试，不会创建新 Step。

这四层不能混用。特别是重试不会改变模型的逻辑决策，因此它属于 Attempt，而不是 Step。

例如 Step 2 调用 DeepSeek 时发生两次瞬时故障：

```text
Turn 1
  └── Step 2：模型进行一次逻辑判断
       ├── Attempt 1：网络超时
       ├── Attempt 2：Provider 返回 503
       └── Attempt 3：请求成功
```

三个 Attempt 使用相同的模型消息和工具定义，只有实际请求次数发生变化。单独记录 Attempt 可以统计 Provider 请求次数、重试消耗的 Token 和成本，并防止失败请求的流式片段混入成功结果。

> 模型基于新上下文重新判断是新 Step；相同请求因瞬时故障重新发送是新 Attempt。

## Agent Loop 的实际流程

```text
1. 创建 TurnStarted
2. 创建 StepStarted
3. 首个 Step 记录 UserMessageAdded
4. 从 SessionEvent 投影消息历史
5. 记录 ModelRequestPrepared
6. 调用 LanguageModel.stream
7. 持久化并组装 ModelChunk
8. 根据模型结果分支：
   ├── Answer：记录 AssistantMessageAdded，结束 Turn
   └── ToolRequest：记录调用，执行工具，记录结果，进入下一 Step
9. 任何路径都关闭 Step
10. 用唯一 TurnOutcome 关闭 Turn
```

运行约束包括最大 Step 数、Turn 总超时、协程取消和有界模型重试。可预期工具错误会作为结果交回模型修正；未知内部错误会终止 Turn，避免把实现细节暴露给模型。

## 主要模块职责

| 模块 | 单一职责 |
|---|---|
| `Agent` | 驱动 Turn、Step、模型和工具，不保存第二份消息历史 |
| `SessionEvent` | 定义已经发生且需要审计的运行事实 |
| `Session` | 提供事实追加、稳定快照和实时事件发布 |
| `SessionLog` | 定义事件存储接口；内存和 JSONL 是不同实现 |
| `SessionProjector` | 从事实日志计算模型可见消息和历史请求 |
| `SessionRecovery` | 用追加事件关闭崩溃遗留的开放执行 |
| `LanguageModel` | 隔离 Runtime 与具体模型 Provider |
| `ModelChunkAssembler` | 校验流式协议并组装唯一最终响应 |
| `ToolRegistry` | 保存工具定义并按名称执行工具 |
| `SessionUsageReporter` | 从模型请求和 Usage 事实计算 Token 与成本 |

## 最重要的设计原则

### 1. 事实和视图分离

`SessionEvent` 是事实源，`Message List` 是给模型使用的投影。生命周期、重试和 Usage 需要保存，但不能全部发送给模型。

```text
SessionEvent Log = 不可随意改写的运行事实
Message List     = 可以重新计算的模型上下文
```

这属于 Event Sourcing 和 Projection 的简化应用。它提供审计、恢复、请求重建和多种统计视图，但未来接入数据库时仍需补充事件序号、Session ID 和并发控制。

### 2. 核心依赖能力接口

`LanguageModel`、`Tool` 和 `SessionLog` 是 Runtime 的端口，DeepSeek、文件工具和 JSONL 是适配器。这是依赖倒置和 Ports & Adapters 的实际应用，其价值是隔离已经存在的变化：模型厂商、工具实现和持久化方式。

### 3. 用状态表达生命周期

Turn 只能进入一个 `TurnOutcome`。事件和响应使用 `sealed interface` 表达封闭状态，编译器会在新增分支时提示遗漏处理。它比多个 boolean 或 magic string 更能保护状态机不变量。

### 4. 失败也是运行事实

重试、超时、取消、输出截断和崩溃中断不能统一写成“失败”。它们的恢复方式、计费含义和用户体验不同，因此必须分别记录。

### 5. 流式输出不等于最终结果

`ModelChunk` 是 Provider 传输事实，`ModelResponse` 是协议校验后的领域结果。失败 Attempt 的部分文本可以用于审计和展示，但不能进入后续消息历史，否则模型会看到从未正式完成的回复。

### 6. 工具让模型具备环境能力

LLM 只能生成决策；Tool 才能读取文件或操作外部系统。工具参数处在不可信 JSON 边界，必须校验。真正服务化后还要增加权限、目录隔离、超时、幂等和副作用审批。

### 7. 协程负责结构化生命周期

模型和工具调用使用挂起函数；调用方取消会向下传播，同时 Runtime 在清理阶段关闭 Step 和 Turn。超时是 Runtime 的部署策略，取消是调用方行为，两者不能记录成同一种终态。

## 当前能力边界

当前项目已经是可以本地运行和测试的 Agent Runtime 内核，但还不是完整 Agent System：

- 一个 `Agent` 实例内的 Turn 可以安全串行执行。
- JSONL 支持单进程持久化和崩溃尾部修复。
- 当前只支持单个工具调用，不支持并行工具调用。
- 当前没有多 Session 服务、HTTP API、数据库事务、身份认证和租户隔离。
- `read_file` 有工作区限制，但还不是面向不可信远程用户的完整工具沙箱。
- Token Usage 是运行计量事实，不等同于最终账单系统。

下一阶段接入 HTTP 时，应先增加独立的应用服务层管理 `SessionId`、`RunId` 和多 Session 生命周期，再让 Ktor 或其他 Web 框架作为传输适配器调用它。HTTP Handler 不应直接拥有 Agent Loop。

## 面试 Agent 工程师时怎样介绍

面试官通常不只想知道“接过哪个模型 API”，而是想判断你是否理解 Agent 的执行语义、状态管理、失败路径和生产化问题。介绍时按下面顺序展开：

```text
问题背景
  → 我负责的范围
  → 核心架构
  → 最重要的设计决策
  → 处理过的失败路径
  → 测试与验证
  → 当前边界和下一步
```

不要上来罗列类名。先说明为什么普通 Chat API 不够，再说明 Runtime 如何让模型和工具形成可恢复、可审计的执行过程。

### 30 秒介绍

> 我用 Kotlin 实现了一个 Agent Runtime，而不是简单封装模型 API。核心是一个按 Session、Turn、Step、Attempt 运行的 Agent Loop：模型可以返回答案或者请求工具，工具结果会进入下一次模型判断。运行事实通过追加式 SessionEvent 保存，再投影成模型上下文；模型、工具和存储都通过接口隔离。目前已经实现流式输出、工具调用、重试、超时、取消、JSONL 恢复和 Token 成本统计。

### 2 分钟介绍

> 这个项目解决的问题是：普通模型 API 只负责一次请求响应，但 Agent 需要多轮调用模型和工具，还要处理状态、失败和恢复。我把核心定义为 Agent Runtime，由 Agent 负责 Turn 和 Step 编排，SessionEvent 作为运行事实源，SessionProjector 从事件重建模型可见消息。这样消息历史不是另一份容易失真的可变状态，每次模型请求也可以从日志审计和重建。
>
> 模型通过 LanguageModel 接口接入，当前适配 DeepSeek 的 SSE；工具通过 ToolRegistry 注册，参数在工具边界校验。模型请求失败时，只有 Provider 明确标记为瞬时失败才在同一个 Step 内产生新 Attempt；工具调用导致下一次模型决策时才产生新 Step。Runtime 还区分调用方取消、Turn 超时、模型失败、输出截断和崩溃中断，因为这些状态的恢复和计费语义不同。
>
> 持久化目前有内存和 JSONL 实现，开放 Turn 可以通过追加补偿事件修复；Provider 返回的 Usage、模型身份和价格快照也会写入 Session，用于统计缓存率、重试浪费和成本。我参考了成熟 Agent Harness 的职责划分和失败语义，但在 Kotlin 中独立完成领域建模、协程生命周期、Provider 适配和测试。当前它是单进程 Runtime 内核，还没有把 HTTP、多 Session 数据库、认证和租户隔离说成已完成；下一阶段会先增加应用服务层，再接入 Ktor。

### 简历项目描述

可以写成以下三点，不要写尚未实现的商用能力：

- 使用 Kotlin 与协程实现可取消的 Agent Runtime，按 Session、Turn、Step、Attempt 编排 LLM、流式响应和工具调用。
- 采用追加式事件日志与投影分离运行事实和模型上下文，支持 JSONL 持久化、崩溃尾部恢复和历史模型请求重建。
- 实现 Provider 可替换接口、有界瞬时失败重试、超时与终态建模，以及 Token Usage、价格快照、缓存率和重试成本统计。

如果项目真正上线后，再补充吞吐量、延迟、成本下降或故障恢复时间等可验证指标。没有数据时不要编造百分比。

### 怎样说明测试和正确性

> 我没有把“能调用模型”当成完成，而是对执行状态和失败路径做确定性测试。Agent 使用 Fake Model 和 Fake Tool 验证完整事件顺序、工具多 Step、重试、超时和取消；Session 测试覆盖 JSONL 往返、损坏数据失败和崩溃恢复；DeepSeek Adapter 使用固定响应验证 SSE 分片、Usage 映射、空闲超时和协议错误。真实 API 测试单独运行，避免普通单元测试依赖网络和密钥。

这里体现的不是测试数量，而是测试分层：纯领域状态使用单元测试，持久化和 Provider 协议使用边界测试，真实服务只做显式集成冒烟测试。

### 不要这样介绍

- 不要只说“接入 DeepSeek 并实现 Function Calling”，这会被理解成 API 集成项目。
- 不要把简化事件日志描述成完整 CQRS 平台，应明确这是 Event Sourcing 思想在 Runtime 内的有限应用。
- 不要把工作区路径检查描述成完整沙箱，也不要声称已经具备多租户安全。
- 不要只讲正常流程；主动讲重试、取消、崩溃和未知工具副作用，更能体现工程深度。
- 不要背诵设计模式名称，要先说它降低了什么真实变化成本。

## 高频面试追问

### 为什么不用一个 Message List 保存全部状态？

Message 只表示模型可见上下文，无法准确表达 Turn 生命周期、重试、Usage、取消和崩溃恢复。以 SessionEvent 为事实源后，可以针对模型、终端和计量生成不同投影，避免多份状态逐渐不一致。

### Step 和 Attempt 为什么要分开？

Step 表示一次新的模型决策。Attempt 表示同一请求因网络或 Provider 瞬时故障发生的重试。把重试算成新 Step 会污染 Agent 的逻辑轨迹、Step 上限和成本分析。

### 为什么超时和取消不是同一种失败？

超时是 Runtime 主动执行部署策略；取消来自调用方意图。两者的告警、重试和用户提示不同，所以必须保留不同终态，同时都要通过协程取消清理正在运行的模型或工具。

### 为什么需要 LanguageModel 接口？

Agent 只依赖“模型请求产生流式结果”的能力，不应该认识 DeepSeek 的鉴权、DTO、SSE 和错误码。接口隔离了真实存在的 Provider 变化，也允许测试使用 Fake Model，不需要为了扩展而引入复杂工厂。

### 进程在工具执行期间崩溃怎么办？

如果工具调用已经记录但结果没有持久化，外部副作用是否发生是未知的。恢复时只能记录未知结果，不能假装工具失败或直接重试；只有只读或幂等工具才能安全重试，其他工具需要先核对外部状态。

### Token Usage 为什么属于 Runtime？

Runtime 最清楚每个模型请求的 Turn、Step、Attempt、模型身份和重试关系，因此它负责记录可信的技术计量事实。租户套餐、折扣、税率和最终账单属于外围商业系统。

### 这个项目现在能直接商用吗？

它已经具备本地 Agent Runtime 的主要执行能力，但不是完整商用 Agent System。还需要多 Session 应用服务、数据库事务、HTTP 异步 Run 协议、幂等、认证授权、租户隔离、工具沙箱、限流和生产监控。

### 如果并发扩大十倍，最先改哪里？

当前 JSONL 和进程内 `Mutex` 只适合单进程实例。首先要引入带 Session ID 和单调事件序号的数据库存储，再用事务、乐观锁或租约保证同一个 Session 只有一个执行者；HTTP 请求生命周期也必须与 Run 执行生命周期分离。

## 对外介绍模板

简短版本：

> 我实现的是一个 Kotlin Agent Runtime，不是模型 API 包装器。它负责在 Session 中循环调用 LLM 和工具，并用事件日志管理上下文、执行状态、恢复、重试、取消和用量统计。

技术版本：

> 系统采用事件日志作为事实源，通过投影重建模型上下文；Agent Loop 按 Turn、Step、Attempt 编排模型和工具。模型、工具和存储通过接口隔离，DeepSeek 和 JSONL 只是当前适配器。

产品版本：

> Agent Runtime 是 Agent 系统的执行内核。再接入 HTTP、数据库、认证、任务调度、隔离和监控后，才构成能够对外提供服务的 Agent System。

进一步的实现细节见[开发与架构学习指南](development-guide.md)，商业计量见 [Token Usage 文档](token-usage.md)。
