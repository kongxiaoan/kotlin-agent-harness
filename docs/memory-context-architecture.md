# Memory 与 Context Management 架构

## 文档定位

本文定义 Kotlin Agent Runtime 的长期记忆与上下文管理架构，并区分当前实现与后续目标。

当前项目已经完成 Context Management V1：每次模型调用保留当前 Turn，并在输入预算内从近到远选择连续的完整历史 Turn；`ContextPrepared` 记录选择区间、预算和估算器版本，历史请求可以重建。身份作用域、长期 Memory、精确 Provider Tokenizer 和 Compaction 尚未实现。

## 一句话模型

```text
EventLog 回答：发生过什么？
Memory   回答：什么值得长期记住？
Context  回答：这次模型应该看到什么？
Window   约束：这次模型最多能看到多少？
```

Memory 服务于 Context，但 Context 的来源不只有 Memory。

## Context 是什么

Context 是**某一次模型调用实际可见的全部输入**。它不是 Session 的完整历史，也不只是 Message List；完整形态还可能包含系统指令、工具定义、当前 Turn、选中的历史、长期记忆和压缩结果。

Context 具有调用级生命周期：Agent 在每次 Model Call（包括工具执行后的下一 Step 和重试 Attempt）前重新构建，生成 `ContextPlan`，随后转换为 Provider 无关的 `ModelRequest`。调用结束后不把 Context 当作可修改状态保存，而是记录其来源区间、版本和预算，使这次输入能够审计和重建。

```text
SessionLog / Instructions / Tools / Memory / Compaction
                         │
                         ▼
                  Context Management
             选择、排序、压缩、容量校验
                         │
                         ▼
                    ContextPlan
                         │
                         ▼
                    ModelRequest
                         │
                         ▼
                         LLM
```

因此，Context Management 位于 **Agent Loop 与 Language Model 之间**：Agent Loop 决定何时调用模型，Context Management 决定这一次模型能看到什么，Model Adapter 负责把结果转换为具体 Provider 协议。

Context 不属于 Memory 领域。Memory 管理跨调用、跨 Session 保留的信息；Context Management 是 Memory 的消费者之一，负责判断某条 Memory 是否应该进入本次模型输入。两者的关系是：

```text
Memory 提供可选信息；Context 决定本次使用哪些信息。
```

还需要区分三个容易混淆的概念：

- `Context`：本次模型实际看到的信息。
- `Context Window`：模型允许的输入与输出总容量。
- `Context Management`：在容量和完整性约束下构建 Context 的 Runtime 能力。

## 概念边界

| 概念 | 职责 | 生命周期 |
|---|---|---|
| `SessionLog` | 保存一个 Session 的完整运行事实 | Session 级，追加式 |
| Message Projection | 从运行事实生成模型协议消息 | 每次读取时重新计算 |
| Long-term Memory | 保存跨 Session 有价值的信息 | 用户、Agent 或项目级 |
| Compaction | 将大段历史转换为更小的派生信息 | 可重新生成 |
| Context Management | 为一次模型调用选择并组装信息 | 每次 Model Call |
| Context Window | Provider 允许的最大输入与输出容量 | 模型级硬限制 |
| Token Budget | 在 Window 内给不同信息来源分配容量 | 每次 Model Call |

一个 Turn 可以包含多次模型调用和多条 Message。历史选择应优先保持完整 Turn，避免留下没有对应 ToolCall 的 ToolResult；Token 是容量限制，不是历史语义单位。

## 总体架构

```text
                    Memory 写入链路
SessionLog ─> Extract ─> Candidate ─> Consolidate ─> MemoryStore
    │                                                    │
    │                 Context 读取链路                    │
    └──────────────┐                                     │
Instructions ──────┤                                     │
Tool Definitions ──┤                                     │
Compactions ───────┼─> Collect ─> Select ─> Compress ─> Order
Current Turn ──────┤                                     │
MemoryStore ───────┘                                     │
                                                          ▼
                                      Fit ─> ContextPlan ─> ModelRequest
                                                 │
                                                 ▼
                                            SessionLog
```

写入链路决定哪些经历应成为长期记忆；读取链路决定当前模型调用应使用哪些信息。两条链路独立演进，不应放进一个巨型 `MemoryManager`。

## Memory 写入链路

### Extract

从完整 Turn、用户显式命令或外部可信数据中产生 `MemoryCandidate`。Extractor 只提出候选，不直接覆盖已有记忆。

第一版应优先支持用户明确要求的 `remember` 行为。自动 LLM 提取会引入成本、误提取和提示注入风险，应在基础语义与评估体系建立后增加。

### Consolidate

Consolidator 决定候选记忆应当创建、替换、合并、等待确认或丢弃。它必须保留来源和版本，不能只保存一段无法解释来源的文本。

### Store

`MemoryStore` 保存长期记忆当前状态并提供检索。标准化的是领域语义，不是数据库；PostgreSQL、pgvector 或搜索引擎只是实现。

建议的最小数据模型：

```text
MemoryRecord
  id
  scope                 tenant / user / agent / project
  kind                  semantic / episodic / procedural
  content
  sourceReferences      sessionId + sequence range
  status                active / superseded / forgotten
  version
  confidence / importance
  createdAt / updatedAt / expiresAt
```

Embedding、分词结果和搜索分数是可重新生成的索引，不是记忆领域事实。

建议的最小存储能力：

```text
create
get
search(scope, query, filters, limit)
replace(expectedVersion)
forget(expectedVersion)
```

更新和遗忘使用乐观并发版本，避免两个写入者静默覆盖对方。

## Memory 的事实归属

`SessionLog` 是 Session 运行事实源，但不适合作为全部长期记忆生命周期的唯一事实源。长期记忆会跨 Session 更新、合并、遗忘、过期，也可能来自外部导入。

- `SessionLog` 保存来源经历和本次模型实际使用的记忆；
- `MemoryStore` 保存长期记忆当前状态，并通过 `sourceReferences` 保留来源；
- 如果未来要求完整 Event Sourcing，再增加按 Memory Scope 分区的 `MemoryEventLog`，由它投影 `MemoryStore`。

跨用户的 Memory 生命周期不应强行写进某一个 Session 聚合。

## Context Management 读取链路

Context Management 在每次 Model Call 前运行。一个 Turn 内的 Tool Result 会持续改变下一次模型调用需要的 Context。

```text
Collect   从 Instructions、Tools、Session、Memory、Compaction 收集候选
Select    按 Scope、相关性、时效、重要性和可信度选择
Compress  压缩过大的历史、文档和 Tool Result
Order     按稳定规则排列模型输入
Fit       在 Token Budget 内保留最高价值信息
Build     生成 ContextPlan，再组装 Provider 无关 ModelRequest
```

推荐排列顺序：

```text
Instructions
→ Stable Memory
→ Session State / Compaction
→ Retrieved History
→ Recent Turns
→ Current Turn
```

输入预算按照模型能力计算：

```text
inputBudget = contextWindow - reservedOutput - safetyMargin
```

超限时先移除低优先级派生信息，再减少检索结果或压缩旧历史；不得从中间截断 ToolCall/ToolResult 关系。

## 可审计与可重建

模型可见信息必须能够从 Session 事实重建。Memory 搜索结果和版本会变化，因此只记录“执行过搜索”不够。

每次模型调用前应记录类似 `ContextPrepared` 的事实：

```text
turn / step / attempt
model profile
selected event ranges
memory id + version + model-visible snapshot + integrity hash
compaction id + version
token budget and measured input tokens
tool definition snapshot
```

历史请求重建必须读取当时选择的不可变版本，不能重新运行今天的 Memory Search。

## 身份、安全与治理

跨 Session Memory 的前置条件是身份作用域。只有 `SessionId` 时直接实现用户长期记忆会产生数据泄漏风险。

生产实现至少需要：

- 明确的 `TenantId`、`UserId`、`AgentId` 或 `ProjectId` 组合；
- 存储查询强制 Scope 过滤，而不是依靠 Prompt；
- 来源、敏感类别、TTL 和保留策略；
- 用户查看、修改和遗忘能力；
- 自动提取防提示注入；
- 写入、检索和遗忘审计。

## 评估指标

Memory 系统不能只验证“数据库返回了结果”。至少需要评估：

- Recall：应该想起的信息是否被找到；
- Precision：返回的信息是否与任务相关；
- Freshness：是否使用已经失效的记忆；
- Isolation：是否出现跨 Scope 泄漏；
- Grounding：是否能追溯到可信来源；
- Context Cost：注入记忆的 Token 与成本；
- Task Impact：记忆是否提高任务成功率；
- Forget Correctness：遗忘后是否从检索和 Context 中消失。

没有离线样例集和回归评估时，复杂 Ranking 或自动 Consolidation 只是不可验证的猜测。

## 与当前 Kotlin Runtime 的关系

| 当前模块 | 已提供的基础 | 后续变化 |
|---|---|---|
| `SessionLog` / `SessionEventStore` | 有序运行事实和游标 | 保持 Session 聚合职责 |
| `SessionProjector` | 投影消息，并按已记录区间重建历史请求 | 后续接收 Memory 与 Compaction 快照 |
| `ContextManager` | 按预算选择当前 Turn 与最近完整历史 | 后续统一多来源候选排序 |
| `ContextPrepared` | 保存事件区间、估算量、预算和估算器版本 | 后续增加 Memory 与 Compaction 版本快照 |
| `Agent.generateWithRetry` | 每个 Attempt 前重新构建 Context | 保持调用级重建语义 |
| `TokenUsage` | Provider 返回的事后精确计量 | 与调用前保守估算分别保留 |

当前只有确定性的 Session Context V1；`MemoryStore`、Extractor、Consolidator 和 Compactor 仍是目标职责，不应一次性全部创建。

## 推荐实施顺序

### 1. Context Management V1

已完成：定义 Window、输出预留与安全余量，从 SessionEvent 识别完整 Turn，在 Token Budget 内选择历史，并记录可重建的选择结果。第一阶段不实现长期 Memory 和自动摘要。

### 2. Memory Scope 与显式记忆

引入用户和 Agent 作用域，定义 `MemoryRecord` 与 `MemoryStore`，实现内存版本的 remember、search、replace、forget，并验证跨 Session 读取和跨用户隔离。第一版不需要向量数据库。

### 3. Memory 接入 Context

Memory Retrieval 产生 `ContextCandidate`，与历史 Turn 一起参与 Token Budget，并记录实际使用的 Memory ID、版本和内容快照。

### 4. 自动提取与 Compaction

在评估样例保护下增加后台 Extractor、Consolidator、Session Summary、混合检索和持久化索引。自动生成失败不应阻塞主要 Agent Turn。

PostgreSQL `SessionEventStore` 属于服务可靠性路线，可以与 Context V1 独立推进；生产级跨 Session Memory 则同时依赖身份体系和持久化存储。

## 设计原则

1. Session 事实、长期记忆和本次 Context 是不同数据。
2. Turn 是历史完整性的语义单位，Token 是容量硬限制。
3. Context 在每次 Model Call 前重新构建。
4. 模型可见等于可记录、可审计、可重建。
5. Memory 必须具备 Scope、来源、版本和遗忘语义。
6. 先实现确定性最小闭环，再增加 LLM 提取和向量检索。
7. Memory 提供候选，Context Management 对最终模型输入负责。

## 参考设计

- [OpenAI Agents SDK Sessions](https://openai.github.io/openai-agents-python/sessions/)
- [OpenAI Sandbox Agent Memory](https://openai.github.io/openai-agents-python/sandbox/memory/)
- [LangGraph Persistence](https://langchain-ai.github.io/langgraph/concepts/time-travel/)
- [Google ADK MemoryService](https://github.com/google/adk-python/blob/main/src/google/adk/memory/base_memory_service.py)
- [Letta Memory Blocks](https://docs.letta.com/tutorials/attaching-detaching-blocks/)
- [Claude Code Memory](https://docs.anthropic.com/zh-CN/docs/claude-code/memory)
