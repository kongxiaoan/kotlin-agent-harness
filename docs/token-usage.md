# Token Usage 与商业计量

## 目标

Token Usage 不是界面上的一个 `totalTokens` 数字，而是模型调用的计量事实。它需要支持成本核算、账期汇总、模型对比、缓存效率分析、重试浪费定位和异常审计。

本项目采用以下数据流：

```text
DeepSeek usage
  → Provider 无关 TokenUsage
  → ModelChunk.Usage
  → Session JSONL
  → SessionUsageReporter
  → 商业数据仓库或账单系统
```

## Token 字段

`TokenUsage` 的计数彼此互斥：

| 字段 | 含义 | 是否计入总量 |
|---|---|---|
| `inputTokens` | 未使用缓存的输入 Token | 是 |
| `cacheReadTokens` | 从 Provider 缓存读取的输入 Token | 是 |
| `cacheWriteTokens` | 写入 Provider 缓存的输入 Token | 是 |
| `outputTokens` | 模型生成的全部输出 Token | 是 |
| `reasoningTokens` | 输出中属于推理过程的部分 | 否，已包含在 `outputTokens` |

计算公式：

```text
billableInputTokens = inputTokens + cacheReadTokens + cacheWriteTokens
totalTokens         = billableInputTokens + outputTokens
```

不能把 `reasoningTokens` 再加到总量，否则会重复统计输出。

## DeepSeek 字段映射

DeepSeek 的 `prompt_tokens` 等于缓存命中与未命中输入之和。本项目转换为互斥字段：

```text
cacheReadTokens = prompt_cache_hit_tokens
inputTokens     = prompt_tokens - cacheReadTokens
outputTokens    = completion_tokens
reasoningTokens = completion_tokens_details.reasoning_tokens
```

Adapter 会验证缓存命中与未命中之和、`total_tokens`、非负值以及推理 Token 不超过输出 Token。矛盾数据作为协议错误失败，不进入账单。

流请求始终发送 `stream_options.include_usage=true`。DeepSeek 可能在结束 chunk 后再发送 usage-only chunk，因此 Adapter 缓存最新 usage，在 `[DONE]` 时按 `Usage → Finished` 的顺序提交。

字段依据：[Create Chat Completion](https://api-docs.deepseek.com/api/create-chat-completion/)、[Context Caching](https://api-docs.deepseek.com/guides/kv_cache)。

## 价格与金额

价格不能硬编码在 Adapter。`config.yaml` 提供每百万 Token 的价格：

```yaml
pricing:
  version: deepseek-v4-flash-2026-08-19-cny
  currency: CNY
  input-per-million: "1.00"
  cache-read-per-million: "0.02"
  cache-write-per-million: "0"
  output-per-million: "2.00"
```

金额使用 `BigDecimal`，配置值必须是带引号的十进制字符串，禁止使用 `Double`。计算公式为：

```text
bucketCost = tokens × pricePerMillion ÷ 1,000,000
```

每次 `ModelRequestPrepared` 都保存 provider、model 和完整价格快照。价格变化时必须同时修改 `version`；历史 Session 继续使用请求发生时的费率，不会被新配置重新定价。不同币种只分别汇总，不能直接相加。

当前示例费率对应 `deepseek-v4-flash`，核对日期为 2026-08-19。DeepSeek 会调整价格，上线前应重新检查[官方价格页](https://api-docs.deepseek.com/zh-cn/quick_start/pricing)。`cache-write-per-million` 为显式的 `0`，因为 DeepSeek 当前没有独立的缓存写入计费字段。

## 已实现的商业统计

`SessionUsageReporter.report(events)` 同时返回 Session 总计、按模型价格版本分组和逐 attempt 明细。

| 指标 | 用途 |
|---|---|
| `attempts` | Provider 请求次数，包括重试 |
| `finishedAttempts` | 收到 Provider 终态的次数 |
| `retryAttempts` | 被后续重试替代的 attempt 数 |
| `usageReportedAttempts` | Provider 返回 Usage 的次数 |
| `timestampedUsageAttempts` / `untimestampedUsageAttempts` | Usage 是否具备可进入账期的 UTC 时间 |
| `unreportedAttempts` | 没有 Usage 的 attempt；不能当成零成本 |
| `pricedUsageAttempts` | 有价格快照并完成成本计算的次数 |
| `unpricedUsageAttempts` | 有 Usage 但没有价格快照的次数 |
| `tokens` | 所有互斥 Token 桶的总量 |
| `retryTokens` | 失败重试消耗的 Token，可用于计算浪费率 |
| `cacheHitRatio` | `cacheReadTokens / billableInputTokens` |
| `finishReasons` | `STOP`、`TOOL_CALLS`、`MAX_TOKENS` 分布 |
| `costsByCurrency` | 各币种的输入、缓存读写、输出和总成本 |
| `retryCostsByCurrency` | 因重试产生的成本 |
| `byModel` | provider、model、price version、currency 四维分组 |

逐 attempt 的 `ModelAttemptUsage` 包含 `turn`、`step`、`attempt`、模型信息、Usage、UTC `usageObservedAt`、停止原因、是否被重试和精确成本，可直接作为数据仓库事实表的技术侧输入。

失败 attempt 的 Usage 仍然计费并保留；重试不会覆盖历史。Provider 未返回 Usage 时只增加 `unreportedAttempts`，系统不会猜测 Token 或把它当成零。

## Finish Reason

`STOP` 表示正常文本结束，`TOOL_CALLS` 表示模型请求工具，`MAX_TOKENS` 表示输出被截断。`MAX_TOKENS` 会把 Turn 记录为 `TurnOutcome.MaxTokens`，不会伪装成 `Completed`；即使截断结果没有文本，Usage 仍会持久化，空 Assistant 消息不会进入后续模型上下文。

## 商业系统的职责边界

Runtime 只记录可信的模型执行事实。租户、用户、项目、套餐、订单、税率、折扣和 API Key 归属必须由认证后的 API/BFF 或账单系统补充，不能由模型请求或客户端自由传入。

商业事实表应使用以下组合键关联：

```text
tenant/project (业务层)
session id      (持久化层)
turn/step/attempt
provider/model/pricing version
usageObservedAt
```

发票金额不应直接使用 Runtime 的估算结果代替 Provider 最终账单。Runtime 成本用于实时预算、告警和内部核算；月末应与 Provider 账单对账，并重点检查 `unreportedAttempts`、未定价 Usage 和重试成本差异。

## 使用示例

```kotlin
val report = SessionUsageReporter.report(session.events)
val cnyCost = report.total.costsByCurrency["CNY"]?.total
val retryCost = report.total.retryCostsByCurrency["CNY"]?.total
val cacheHitRatio = report.total.cacheHitRatio
```

禁止只存聚合结果。Session 中的请求价格快照和原始 Usage 是审计依据；聚合报表可以随时重建。
