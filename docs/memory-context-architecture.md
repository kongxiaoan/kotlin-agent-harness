# Memory 与 Context Management 架构

## 文档定位

本文定义 Kotlin Agent Runtime 的长期记忆与上下文管理架构，并区分当前实现与后续目标。

当前项目已经完成 Context Management V1、Memory Store 领域基础、原子 JSON 快照、安全的 LLM 写入工具和 Memory Context V1：长期记忆使用 tenant/user/agent 强制 Scope、Session 来源、版本化替换和遗忘墓碑；每次模型调用读取当前 Scope 内最近更新的 active memories，按 Token Budget 选取，并把模型实际看到的版本快照记录到 Session。

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

从当前对话、完整 Turn 或外部可信数据中产生 `MemoryCandidate`。Extractor 只提出候选，不直接覆盖已有记忆。

产品主链路不要求用户说出“记住”之类的技术命令。正常对话中的主 LLM 根据 Memory Policy 判断信息是否稳定且对未来有价值，并调用模型可见的 `memory_write` 工具提交候选；用户明确要求记忆只是同一链路的特殊输入，不是独立功能。

第一版复用正在执行 Agent Loop 的主 LLM，不额外发起一次后台提取请求。LLM 只提供候选内容和类型；`MemoryScope`、Session 来源和事件区间由 Runtime 注入，不能由模型参数指定。Consolidator 随后决定 create、replace 或 ignore，避免模型直接覆盖 Store。独立的 Turn 后置 Extractor 可以提高召回率，但会增加调用成本、延迟和重复写入，应在主链路可评估后再增加。

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

### 当前 Memory Store V1

当前实现刻意停在存储语义层：

```text
MemoryScope = TenantId + UserId + AgentId
MemoryRecord = ID + Scope + Kind + Content + Sources + Version + Timestamps
MemorySource = SessionId + SessionEventRange
```

`InMemoryMemoryStore` 支持 create、get、文本 search、replace 和 forget。所有读取和写入都要求完整 Scope；Scope 不匹配与不存在使用相同结果，避免泄漏其他用户是否拥有某个 Memory ID。replace 和 forget 必须提交 `expectedVersion`，过期写入明确冲突。forget 删除可检索正文，只保留内部版本墓碑，旧版本不能恢复已遗忘内容。

`JsonFileMemoryStore` 使用原子文件替换保存当前索引，重启后恢复 active 记录和遗忘墓碑。它不采用追加日志，因为 replace 和 forget 必须从磁盘移除旧正文；完整 Memory Event Sourcing 与隐私删除需要另行设计。该实现不协调多进程写入，只适合当前单进程 CLI。当前 list/search 只是确定性的最近更新时间与文本匹配基础，不代表最终语义检索质量。

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
memory id + kind + version + model-visible snapshot
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
| `SessionProjector` | 按已记录区间和 Memory 快照重建历史请求 | 后续接收 Compaction 快照 |
| `MemoryContextSource` | 按 Scope 和更新时间读取 active 候选 | 后续替换为可评估的混合检索与 Ranking |
| `ContextManager` | 按预算选择 Memory、当前 Turn 与最近完整历史 | 后续统一更多来源的候选排序 |
| `ContextPrepared` | 保存事件区间、Memory 快照、估算量、预算和估算器版本 | 后续增加 Compaction 版本快照与完整性校验 |
| `Agent.generateWithRetry` | 每个 Step 读取一次 Memory，同 Step Attempt 复用候选 | 保持重试输入的 Memory 版本稳定 |
| `TokenUsage` | Provider 返回的事后精确计量 | 与调用前保守估算分别保留 |

当前已实现确定性的 Session Context V1、内存与本地持久化 `MemoryStore`、已激活的 LLM 写入工具，以及最近更新时间排序的 Memory Context V1；语义 Ranking、Consolidator、数据库实现与 Compactor 仍是后续职责，不应一次性全部创建。

## 推荐实施顺序

### 1. Context Management V1

已完成：定义 Window、输出预留与安全余量，从 SessionEvent 识别完整 Turn，在 Token Budget 内选择历史，并记录可重建的选择结果。第一阶段不实现长期 Memory 和自动摘要。

### 2. Memory Scope 与 LLM 写入

Store、安全写入链和 CLI 本地持久化已完成：主 LLM 可以自主调用 `memory_write`，Scope 和来源不能来自模型参数，落盘失败不会产生成功结果。第一版不额外调用提取模型，也不需要向量数据库。

### 3. Memory 接入 Context

已完成 V1：`MemoryContextSource` 强制 Scope 并读取最近 active memories；`ContextManager` 逐条执行 Token Budget 选择；`ContextPrepared` 记录实际使用的 ID、类型、版本和内容快照，`SessionProjector` 可离线重建请求。Memory 作为 JSON 数据进入系统消息，不被声明为指令。当前排序不考虑查询相关性，配置的 `retrieval-limit` 只限制候选数量。

### 4. 后置提取与 Compaction

在评估样例保护下增加独立的 Turn 后置 Extractor、Session Summary、混合检索和持久化索引。后置生成失败不应阻塞主要 Agent Turn。

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
