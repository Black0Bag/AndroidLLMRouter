# NEW API vs Android RouterEngine/RelayHandler 路由与负载均衡差距清单

> 基于 NEW API (Go) 源码 `controller/relay.go`、`middleware/distributor.go`、`service/channel_select.go`、`model/channel_cache.go`、`model/channel.go`、`setting/operation_setting/status_code_ranges.go`、`service/channel.go` 与 Android `RouterEngine.kt`、`RelayHandler.kt` 逐项对比。

---

## 一、优先级分层

| 维度 | NEW API | Android | 差距 |
|------|---------|---------|------|
| 分层依据 | `channel.GetPriority()` (int64) | `channel.priority` (Int) | ✅ 一致 |
| 分层方式 | 提取 unique priorities → 降序排序 → retry 作为下标 | `groupBy { priority }` → `toSortedMap(reverseOrder())` → retry 作为 layers 下标 | ✅ 逻辑等价 |
| retry 越界处理 | `if retry >= len(uniquePriorities) { retry = len(uniquePriorities) - 1 }` | `retry.coerceAtMost(layers.size - 1)` | ✅ 一致 |
| 缓存结构 | `group2model2channels[group][model] = []int` (channelId 列表，按 priority 排序) | `channelCache[model] = List<ChannelEntity>` (按 priority 降序) | ⚠️ NEW API 有 group 维度，Android 无 group |

**结论：** 核心分层逻辑一致，Android 缺少 group 维度。

---

## 二、层内加权随机

| 维度 | NEW API | Android | 差距 |
|------|---------|---------|------|
| 算法 | `rand.Intn(totalWeight)` → 遍历减权 | `Random.nextInt(totalWeight)` → 遍历减权 | ✅ 一致 |
| weight 默认值 | `*uint`，默认 **0** | `Int`，默认 **1** | ⚠️ 不同默认值 |
| weight=0 处理 | **smoothing**：所有 channel 有效权重=100，等概率 | `coerceAtLeast(1)`：0 当 1 处理 | ⚠️ **差距显著** |
| 低权重平滑 | `sumWeight/len < 10` → `smoothingFactor=100`，放大权重差异精度 | 无 | ⚠️ **缺失** |
| 单渠道优化 | `len(channels)==1` 时直接返回，跳过随机 | 无此优化 | ⚠️ 缺失（影响微小） |

**差距说明：**
- NEW API 的 smoothing 逻辑确保 weight=0 时所有渠道等概率，且低权重时放大精度避免取整偏差。Android 强制 min=1，行为近似但不完全等价（如 weight=[0,0,0] → NEW API 等概率 1/3，Android 也等概率 1/3 但机制不同）。
- 低权重平滑（avg<10 → ×100）是 NEW API 独有的精度优化，Android 缺失。

---

## 三、多 Key 轮换 (random/polling)

| 维度 | NEW API | Android | 差距 |
|------|---------|---------|------|
| 模式 | `MultiKeyModeRandom` / `MultiKeyModePolling` | `"random"` / `"polling"` | ✅ 一致 |
| Random | `enabledIdx[rand.Intn(len(enabledIdx))]` | `enabledIndices.random()` | ✅ 一致 |
| Polling | 从 `MultiKeyPollingIndex` 起，环形扫描启用 Key | 从 `pollingIndex` 起，环形扫描启用 Key | ✅ 一致 |
| Polling 更新 | `MultiKeyPollingIndex = (idx+1) % len(keys)`，内存缓存更新 | `channelRepository.updateKeyStates(..., nextIndex)`，**每次写 DB** | ⚠️ Android 每次轮询写 DB，I/O 开销大 |
| 线程安全 | `GetChannelPollingLock(channelId)` (sync.Mutex) | `getLock(channelId)` (Kotlin Mutex) | ✅ 概念一致 |
| Key 状态存储 | `ChannelInfo.MultiKeyStatusList` (map[int]int, status code) | JSON 数组 `[{enabled, disabledReason, disabledTime}]` | ⚠️ 结构不同 |
| 非 Multi-Key | 直接返回 `channel.Key` (index=0) | 仍走 keyList 逻辑 | ⚠️ NEW API 有快速路径 |
| Key 解析 | `\n` 分割 或 JSON 数组 | 自定义 `keyList()` 方法 | ✅ 功能等价 |
| 无可用 Key | 返回 `ErrorCodeChannelNoAvailableKey` 错误 | 返回 null | ⚠️ NEW API 有明确错误码 |
| IsMultiKey 标记 | 显式 `ChannelInfo.IsMultiKey` 布尔字段 | 隐式（keyList.size > 1） | ⚠️ 不同判断方式 |

**差距说明：**
- Android 每次 polling 都写 DB 持久化轮询索引，NEW API 内存缓存模式下仅更新内存。Android 方案更可靠但 I/O 开销大。
- NEW API 有显式 `IsMultiKey` 标记和非多 Key 快速路径。

---

## 四、AutoBan 自动禁用

| 维度 | NEW API | Android | 差距 |
|------|---------|---------|------|
| 全局开关 | `common.AutomaticDisableChannelEnabled` | 无（仅 per-channel `autoBan`） | ⚠️ **缺失全局开关** |
| 禁用判断 | `ShouldDisableChannel(err)` 多条件： | `shouldRetry(code) && channel.autoBan` | ⚠️ **判断逻辑差距大** |
| ├─ 状态码范围 | `AutomaticDisableStatusCodeRanges`（默认仅 **401**） | 所有可重试的错误码都禁用 | ⚠️ **差距显著** |
| ├─ 关键词匹配 | Aho-Corasick 搜索 `AutomaticDisableKeywords` | 无 | ⚠️ **缺失** |
| ├─ IsChannelError | 渠道错误 → 强制禁用 | 无此概念 | ⚠️ **缺失** |
| ├─ IsSkipRetryError | 跳过重试的错误 → 不禁用 | 无此概念 | ⚠️ **缺失** |
| 禁用粒度 | 渠道级（`UpdateChannelStatus`）+ 多 Key 级（`usingKey` 参数） | Key 级 → 全 Key 禁用时渠道级 | ⚠️ **策略不同** |
| 禁用动作 | 异步 `gopool.Go()` 执行 | 同步执行 | ⚠️ NEW API 异步不阻塞请求 |
| 通知 | 通知 root user（邮件/Webhook） | 无 | ⚠️ 缺失 |
| 禁用状态码配置 | `AutomaticDisableStatusCodeRanges` 可运行时配置 | 硬编码（所有 retryable 都禁用） | ⚠️ **缺失可配置性** |

**差距说明：**
- **最关键差距**：NEW API 的 AutoBan 与 shouldRetry 是**独立判断**。一个错误可以「重试但不禁用」（如 500 错误重试但不禁用渠道），也可以「禁用但不重试」。Android 将两者耦合：`handleUpstreamError` 中 `if (!shouldRetry(statusCode)) return; if (!channel.autoBan) return;` — 只有可重试的错误才会触发禁用。
- NEW API 默认仅 **401** 触发自动禁用（`AutomaticDisableStatusCodeRanges = [{401, 401}]`），Android 对**所有可重试错误码**（401-407, 409-499, 500-503, 505-523, 525-599）都禁用 Key。这意味着 Android 禁用过于激进。
- NEW API 有关键词匹配禁用（如错误消息包含 "insufficient quota" 等），Android 无。
- NEW API 异步禁用不阻塞响应，Android 同步禁用。

---

## 五、Fallback 降级重试

| 维度 | NEW API | Android | 差距 |
|------|---------|---------|------|
| 重试循环 | `for ; retry <= RetryTimes; retry++` | `for (retry in 0..maxRetries)` | ✅ 一致 |
| 降级方式 | retry++ → `GetRandomSatisfiedChannel(group, model, retry)` 选更低优先级层 | retry++ → `selectChannel(model, retry, routeMode)` 选更低优先级层 | ✅ 一致 |
| 重试时重新选渠道 | 是（每次 retry 重新调用 `getChannel`） | 是（每次 retry 重新调用 `selectChannel`） | ✅ 一致 |
| 跨分组重试 | `CacheGetRandomSatisfiedChannel` 支持 auto group 跨组降级 | 无 group 概念 | ⚠️ **缺失** |
| 渠道亲和性 | `GetPreferredChannelByAffinity` + `RecordChannelAffinity` | 无 | ⚠️ **缺失** |
| 重试链路追踪 | `addUsedChannel()` 记录 `use_channel` 列表 → 日志输出 `重试：1->5->8` | 无 | ⚠️ 缺失 |
| 指定渠道不重试 | `specific_channel_id` → 不重试 | 无此功能 | ⚠️ 缺失 |
| 请求体重放 | `common.GetBodyStorage(c)` 重置 Body 流 | 请求体字符串复用 | ✅ 功能等价 |
| 锁定渠道 | `relayInfo.LockedChannel` (Task 类型) | 无 | ⚠️ 缺失（Task 专用） |

**差距说明：**
- 核心降级逻辑一致。
- NEW API 有渠道亲和性（sticky session）、跨分组降级、重试链路日志等高级特性，Android 均缺失。
- 渠道亲和性是 NEW API 的重要特性：成功使用某渠道后，后续请求优先复用该渠道，避免会话上下文断裂。

---

## 六、错误码处理（shouldRetry）

| 维度 | NEW API | Android | 差距 |
|------|---------|---------|------|
| 不重试状态码 | 400, 408, 504, 524 | 400, 408, 504, 524 | ✅ **一致** |
| 重试状态码范围 | 100-199, 300-399, 401-407, 409-499, 500-503, 505-523, 525-599 | 同上 | ✅ **一致** |
| 200-299 处理 | 显式 `return false` | 未处理（`else -> true`） | ⚠️ 理论上 Android 对 2xx 返回 true，但实际 2xx 不触发 shouldRetry |
| <100 或 >599 | `return true` | `else -> true` | ✅ 一致 |
| 可配置性 | `AutomaticRetryStatusCodeRanges` 运行时可配 | **硬编码** | ⚠️ **缺失可配置性** |
| IsChannelError | → 始终重试 | 无此概念 | ⚠️ 缺失 |
| IsSkipRetryError | → 始终不重试 | 无此概念 | ⚠️ 缺失 |
| IsAlwaysSkipRetryCode | 特定 ErrorCode（如 BadResponseBody）不重试 | 无 | ⚠️ 缺失 |
| 渠道亲和性失败 | `ShouldSkipRetryAfterChannelAffinityFailure` → 不重试 | 无 | ⚠️ 缺失 |
| Task 类型重试 | `shouldRetryTaskRelay()` 独立逻辑（429/307/5xx 重试，400/408/2xx 不重试） | 无 Task 概念 | ⚠️ 缺失 |

**差距说明：**
- **状态码范围完全一致**，这是移植最准确的部分。
- NEW API 在状态码之外还有多层判断（错误类型、错误码、亲和性），Android 仅看 HTTP 状态码。
- NEW API 的重试规则可运行时配置，Android 硬编码。

---

## 七、重试次数配置

| 维度 | NEW API | Android | 差距 |
|------|---------|---------|------|
| 变量 | `common.RetryTimes` (int) | `settings.retryTimes` (Int) | ✅ 概念一致 |
| 默认值 | **0**（不重试） | 由 SettingsSnapshot 决定 | ⚠️ 需确认 Android 默认值 |
| 配置范围 | 0-10（UI 校验 `z.coerce.number().min(0).max(10)`） | 无明确范围限制 | ⚠️ 缺失范围校验 |
| 配置方式 | 管理后台 → `model/option.go` → 全局变量 | App 设置页面 → DataStore | ✅ 功能等价 |
| 作用域 | 全局（所有模型/分组共享） | 全局（App 级别） | ✅ 一致 |
| 循环条件 | `retry <= RetryTimes`（含等号，共 RetryTimes+1 次） | `retry in 0..maxRetries`（含 maxRetries，共 maxRetries+1 次） | ✅ 一致 |

**结论：** 重试次数配置逻辑一致。

---

## 八、差距汇总清单

### 🔴 高优先级差距（影响正确性/可靠性）

| # | 差距项 | NEW API 行为 | Android 行为 | 影响 |
|---|--------|-------------|-------------|------|
| 1 | **AutoBan 禁用条件过于激进** | 默认仅 401 触发禁用，还有关键词匹配、错误类型判断 | 所有可重试错误码（401-407, 409-499, 500-503, 505-599）都禁用 Key | Android 会过度禁用健康 Key，导致可用渠道快速耗尽 |
| 2 | **AutoBan 与 shouldRetry 耦合** | `ShouldDisableChannel()` 与 `shouldRetry()` 独立判断 | `handleUpstreamError` 中先判 shouldRetry 再判 autoBan | 某些应重试但不应禁用的错误（如 500）在 Android 中会错误禁用 Key |
| 3 | **缺少全局 AutomaticDisableChannelEnabled 开关** | 全局开关 + per-channel autoBan 双重控制 | 仅 per-channel autoBan | 无法一键关闭所有自动禁用 |
| 4 | **异常类错误也触发 AutoBan** | 仅 HTTP 错误响应经 processChannelError 判断 | `handleException` 对所有异常（超时、连接失败）都禁用 Key | 网络抖动导致的超时会误禁用 Key |

### 🟡 中优先级差距（影响功能完整性）

| # | 差距项 | NEW API 行为 | Android 行为 | 影响 |
|---|--------|-------------|-------------|------|
| 5 | **错误码重试规则不可配置** | `AutomaticRetryStatusCodeRanges` / `AutomaticDisableStatusCodeRanges` 运行时可配 | 硬编码 | 无法按需调整重试/禁用策略 |
| 6 | **缺少渠道亲和性** | sticky channel，成功后优先复用 | 无 | 多轮对话可能切换到不同上游，导致上下文不连续 |
| 7 | **缺少 AutoBan 关键词匹配** | Aho-Corasick 搜索错误消息关键词 | 无 | 无法按错误内容精细控制禁用 |
| 8 | **加权随机缺少 smoothing** | weight=0 → 等概率；avg<10 → ×100 放大精度 | weight 最小强制 1 | 低权重场景下随机分布不够精确 |
| 9 | **缺少 IsSkipRetryError / IsChannelError** | 错误类型级别控制重试行为 | 仅 HTTP 状态码 | 无法对特定错误类型做精细控制 |
| 10 | **缺少重试链路日志** | 记录 `use_channel` 列表，输出 `重试：1->5->8` | 仅记录最后一次错误 | 排查问题困难 |

### 🟢 低优先级差距（架构差异/可后续优化）

| # | 差距项 | NEW API 行为 | Android 行为 | 影响 |
|---|--------|-------------|-------------|------|
| 11 | 无 group 维度路由 | group → model → channels 三级索引 | model → channels 两级索引 | Android 无分组概念，简化场景可接受 |
| 12 | 无跨分组降级 | auto group 跨组重试 | 无 | 单分组场景无影响 |
| 13 | Polling 每次写 DB | 内存缓存更新 polling index | 每次写 DB | I/O 开销，但更可靠 |
| 14 | 无通知机制 | 禁用/启用通知 root user | 无 | 运维感知滞后 |
| 15 | 无 Task 类型重试 | `shouldRetryTaskRelay()` 独立逻辑 | 无 | Android 不支持 Midjourney/Suno 等 Task |
| 16 | 无状态码映射 | `StatusCodeMapping` 渠道级状态码映射 | 无 | 无法转译上游非标状态码 |
| 17 | 无自动恢复 | `ShouldEnableChannel` + `AutomaticEnableChannelEnabled` | 仅健康检查恢复 | Android 依赖定时探测，NEW API 可在成功时自动恢复 |
| 18 | 无锁定渠道 | `LockedChannel` for Task | 无 | Task 专用，不影响 chat completions |
| 19 | 非 Multi-Key 快速路径 | 直接返回 channel.Key | 仍走 keyList 逻辑 | 性能微小差异 |
| 20 | 异步禁用 | `gopool.Go()` 异步 | 同步 | Android 禁用操作阻塞响应（微小） |

---

## 九、关键源码引用

| 功能 | NEW API 文件 | Android 文件 |
|------|-------------|-------------|
| 优先级分层 + 加权随机 | `model/channel_cache.go: GetRandomSatisfiedChannel()` | `RouterEngine.kt: selectChannel() + weightedRandomSelect()` |
| 多 Key 轮换 | `model/channel.go: GetNextEnabledKey()` | `RouterEngine.kt: selectKey()` |
| AutoBan 判断 | `service/channel.go: ShouldDisableChannel()` | `RelayHandler.kt: handleUpstreamError() + handleException()` |
| AutoBan 执行 | `service/channel.go: DisableChannel()` | `RouterEngine.kt: disableKey()` |
| shouldRetry | `controller/relay.go: shouldRetry()` | `RelayHandler.kt: shouldRetry()` |
| 状态码范围配置 | `setting/operation_setting/status_code_ranges.go` | (硬编码在 RelayHandler.kt) |
| Fallback 重试循环 | `controller/relay.go: Relay()` | `RelayHandler.kt: handleChatCompletions()` |
| 渠道选择入口 | `service/channel_select.go: CacheGetRandomSatisfiedChannel()` | `RouterEngine.kt: selectChannel()` |
| 重试次数 | `common/constants.go: RetryTimes` | `SettingsSnapshot.retryTimes` |
