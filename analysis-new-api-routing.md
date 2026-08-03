# NEW API 源码分析：请求路由 / 转发 / 渠道选择完整逻辑

> 源码目录：`/workspace/AndroidLLMRouter/new-api-source`（Go 项目，fork 自 one-api）
> 本报告聚焦一个 OpenAI 兼容请求从进入到最终转发回上游的完整链路，涵盖渠道选择、多 Key 轮换、AutoBan 自动禁用与失败重试（fallback），并附关键代码引用。

---

## 一、总体调用链（一个请求如何被路由）

```
HTTP 请求
  └─ 鉴权中间件 middleware/auth（校验 token、分组、可用模型）
  └─ middleware/distributor.Distribute()          ← 在入口阶段一次性选定渠道（预选）
        ├─ 解析请求中的 model（getModelRequest）
        ├─ 若 token 指定了 specific_channel_id → 直接用该渠道
        ├─ 否则按"渠道亲和性缓存"→ 命中则复用上次成功的渠道
        └─ 否则调用 service.CacheGetRandomSatisfiedChannel() 随机选一个可用渠道
  └─ controller.Relay()                            ← 转发主循环（转发 + 失败重试/fallback）
        ├─ GenRelayInfo → 计费预扣 → RetryParam 初始化
        └─ for retry=0..RetryTimes:
             ├─ getChannel()        // 重试时重新选渠道
             ├─ relay.TextHelper / ClaudeHelper / ...（真正的向上游转发）
             ├─ 失败 → processChannelError()（可能触发 AutoBan 禁用）
             └─ shouldRetry()? → continue : break
```

关键点：**`Distribute` 中间件在请求进入 controller.Relay 之前已选好一个渠道并写入 gin context；`Relay` 的重试循环在每次失败后重新调用 `getChannel` 再次挑选渠道（即 fallback）。**

`controller/relay.go`:
- `Relay()`：转发主流程，`for ; retryParam.GetRetry() <= common.RetryTimes; retryParam.IncreaseRetry() {...}`。
- `getChannel()`：`service.CacheGetRandomSatisfiedChannel(retryParam)` 重新选渠道。

---

## 二、问题 1：如何根据 model 匹配可用渠道？选择顺序（priority / weight / 随机 / 轮询）？

### 2.1 预索引结构（内存缓存）

`model/channel_cache.go` 维护全局缓存（`InitChannelCache` 定时从 DB 全量同步）：
- `group2model2channels map[string]map[string][]int`：**group → model → 渠道ID列表**（仅含启用渠道）。
- `channelsIDM map[int]*Channel`：渠道 ID → 渠道对象（含禁用）。
- 构建时 `sort.Slice` 按 `GetPriority()` **降序**排序（`priority desc`）。

匹配规则（`GetRandomSatisfiedChannel`）：
1. 按 `group[model]` 精确取 ChannelID 列表；
2. 为空则用 `ratio_setting.FormatMatchingModelName(model)` 归一化名（匹配 `gpts`、`thinking-*` 等通配后缀）再取；
3. 对 Advanced Custom（type 58）渠道按 `requestPath` 过滤（`filterChannelsByRequestPathAndModel`），其余类型全部放行。

### 2.2 选择顺序 = Priority 优先 → Weight 加权随机

`GetRandomSatisfiedChannel(group, model, retry, requestPath)` 核心逻辑：
1. 收集候选渠道的**不同 Priority 值**，降序排列成 `sortedUniquePriorities`；
2. 用 `retry` 作为**优先级层级下标**：`targetPriority = sortedUniquePriorities[retry]`（越界取最后一层）。**即：第一次请求（retry=0）只用最高优先级；失败重试（retry=1）才用次高优先级……这就是"按优先级逐层降级 fallback"。**
3. 在该优先级层内收集所有渠道，按 **weight 加权随机**：
   - 累加 `sumWeight`；若全部 weight==0 → 每渠道视为权重 100（均权随机）；
   - 平均权重 <10 → `smoothingFactor=100` 放大；
   - `randomWeight := rand.Intn(totalWeight)`，顺序累减 `weight*factor + smoothingAdjustment`，首个减成负数即命中（权重越大越易命中）。

结论：**选择顺序是"优先级分层 + 层内加权随机"，不是轮询。重试次数决定落在哪一层优先级；同一层内按 weight 做加权随机（weight=0 则均权）。**

### 2.3 "auto" 分组与跨分组重试（service/channel_select.go）
`CacheGetRandomSatisfiedChannel` 处理 `TokenGroup == "auto"`：从 `GetRequestAutoGroups` 取分组列表，**每组先耗尽所有优先级，再切下一个分组**（`crossGroupRetry`），用 `ContextKeyAutoGroupIndex` 跟踪当前分组索引。逐层重试的详细示例见文件头注释（2 分组 × 2 优先级 × RetryTimes=3）。

---

## 三、问题 2：一个渠道多个 key 如何轮换？MultiKeyMode 有哪几种？

### 3.1 MultiKeyMode 定义（constant/multi_key_mode.go）
```go
const (
    MultiKeyModeRandom  MultiKeyMode = "random"   // 随机
    MultiKeyModePolling MultiKeyMode = "polling"  // 轮询
)
```
即 **两种模式：random（随机）、polling（轮询）**；默认/未知模式回退到"取第一个启用 key"。

### 3.2 单 Key vs 多 Key
`model.ChannelInfo` 保存 `IsMultiKey`、`MultiKeySize`、`MultiKeyStatusList`（keyIndex→status）、`MultiKeyDisabledReason/Time`、`MultiKeyPollingIndex`、`MultiKeyMode`。多 Key 时 `Channel.Key` 内用换行（或 JSON 数组）分隔多个 key，由 `GetKeys()` 解析。

### 3.3 Key 轮换入口：`GetNextEnabledKey()`（model/channel.go）
- **非多 Key**：直接返回 `channel.Key`（index=0）。
- **多 Key**：
  1. 用 per-channel 锁 `GetChannelPollingLock(channel.Id)` 保护并发；
  2. 收集所有 enabled 的 key index（`MultiKeyStatusList` 缺省视为 enabled）；
  3. 无可用 key → 返回 `ErrorCodeChannelNoAvailableKey`（上层据此禁用渠道）；
  4. 按模式：
     - `random`：`selectedIdx := enabledIdx[rand.Intn(len(enabledIdx))]` —— 在启用 key 中随机挑一个；
     - `polling`：从 `MultiKeyPollingIndex` 开始环形 `(start+i)%len(keys)` 找下一个启用 key，并更新 `MultiKeyPollingIndex=(idx+1)%len` —— 线程安全轮询；
     - 其他：取第一个启用 key。

每个 key 的启用/禁用（AutoBan）状态存于 `MultiKeyStatusList[keyIndex]`，随机/轮询自动跳过已禁用 key。选中后 `middleware/distributor.SetupContextForSelectedChannel` 把 key 与 index 写入 context（`ContextKeyChannelKey`、`ContextKeyChannelMultiKeyIndex`）。

---

## 四、问题 3：健康检查 / 自动禁用（AutoBan）机制

### 4.0 重要结论
**NEW API 的 AutoBan 不是"连续失败 N 次才禁用"，而是"单次命中禁用规则即立即禁用"。** 仓库中没有失败计数器设计（`ChannelDisableThreshold=5.0` 只用于渠道测试页面对慢渠道的提示阈值，与 AutoBan 无关）。这与 one-api 的"连续失败计数"不同。

### 4.1 触发位置
`controller/relay.go` → `processChannelError()`：
```go
if service.ShouldDisableChannel(err) && channelError.AutoBan {
    gopool.Go(func(){ service.DisableChannel(channelError, err.ErrorWithStatusCode()) })
}
```
即：**每次下游请求失败产生 error，若 `ShouldDisableChannel` 判定应禁用、且该渠道 `AutoBan` 开启，则异步调用 `DisableChannel` 立即禁用。**

### 4.2 判定条件 `ShouldDisableChannel`（service/channel.go）
返回 true 的条件（任一）：
1. 全局开关 `common.AutomaticDisableChannelEnabled` 开启（**默认 false**，common/constants.go）；
2. 错误非 nil；
3. `types.IsChannelError(err)` → true（渠道级错误：连接失败/上游异常等）；
4. `types.IsSkipRetryError(err)` → false（跳过重试的错不禁用）；
5. `operation_setting.ShouldDisableByStatusCode(err.StatusCode)` —— 默认禁用范围 `AutomaticDisableStatusCodeRanges = {{401,401}}`，即 **401 命中即禁用**（可在系统设置配置）；
6. 错误文本命中 `AutomaticDisableKeywords`（`AcSearch` 模糊匹配）。

### 4.3 禁用落地：`service.DisableChannel` + `model.UpdateChannelStatus`
- `DisableChannel(channelError, reason)`：渠道 `AutoBan` 关则跳过；否则调 `model.UpdateChannelStatus(channelId, usingKey, ChannelStatusAutoDisabled, reason)` 并通知 root 用户。
- `UpdateChannelStatus` / `handlerMultiKeyUpdate`：
  - **单 Key**：直接 `channel.Status = ChannelStatusAutoDisabled(3)`，写入 `OtherInfo.status_reason/time`；
  - **多 Key**：`handlerMultiKeyUpdate` 定位 usingKey 对应 keyIndex，把 `MultiKeyStatusList[keyIndex]` 置为禁用（记录 reason/time）——**只禁用出错的这一个 key，其余 key 仍可用**；当 `!hasEnabledMultiKey(...)`（全部 key 已禁用）时，整个渠道才置 `ChannelStatusAutoDisabled`。

### 4.4 渠道级缓存同步
`CacheUpdateChannelStatus` 会把被禁用渠道从 `group2model2channels` 索引中移除，使后续请求不再选中它（实现"拉黑"）。

### 4.5 如何恢复（Enable）
`controller/channel-test.go` + `service/channel.go`：
- `service.ShouldEnableChannel(newAPIError, status)`：要求全局 `AutomaticEnableChannelEnabled`（默认 false）开启、`newAPIError == nil`（测试成功）、当前 `status == ChannelStatusAutoDisabled`。
- **渠道测试（testChannel）成功**且渠道是自动禁用状态时，调 `service.EnableChannel(channel.Id, key, name)` → `model.UpdateChannelStatus(..., ChannelStatusEnabled)` 恢复。
- 多 Key 恢复时对目标 key 执行 `delete(MultiKeyStatusList, keyIndex)` 解除禁用。

即：**AutoBan 渠道/Key 的恢复依赖"后续渠道测试成功"（手动测试，或系统设置的自动启用+定时重测任务）。默认整套机制（自动禁用/自动启用）关闭。**

---

## 五、问题 4：请求失败如何 fallback 到下一个渠道？返回给上层的关键设计

### 5.1 外层重试循环（controller/relay.go `Relay`）
```go
for ; retryParam.GetRetry() <= common.RetryTimes; retryParam.IncreaseRetry() {
    channel, channelErr := getChannel(c, relayInfo, retryParam)   // 重新选渠道
    ...
    newAPIError = relay.TextHelper / ClaudeHelper / ...            // 真正转发上游
    if newAPIError == nil { return }                               // 成功直接返回
    processChannelError(..., newAPIError)                          // 可能触发 AutoBan
    if !shouldRetry(...) { break }                                 // 决定是否继续
}
```
- 默认 `common.RetryTimes = 0`（common/constants.go，可配置），即默认不重试；配置后每次失败 `IncreaseRetry()` 并重新选渠道。

### 5.2 选渠道的"降级"语义
每次 `getChannel` → `CacheGetRandomSatisfiedChannel(retryParam)` 传入当前 `retry`，`GetRandomSatisfiedChannel` 用 `retry` 作为优先级层下标（见 2.2）：retry=0 用最高优先级、retry=1 用次高……实现逐层降级 fallback。
注意：同一优先级内是加权随机，可能再次随机到同一渠道；此时依赖 AutoBan 已把坏渠道拉黑来避免死循环。

### 5.3 是否重试的判定 `shouldRetry`（controller/relay.go）
顺序判定：
1. `err == nil` → false；
2. `service.ShouldSkipRetryAfterChannelAffinityFailure(c)` → false；
3. `types.IsChannelError(err)` → true（渠道级错误必须重试到别的渠道）；
4. `types.IsSkipRetryError(err)` → false；
5. `retryTimes <= 0` → false（次数用完）；
6. 指定 `specific_channel_id` → false（定向渠道不重试）；
7. 2xx → false；`code<100 || code>599` → true；命中 `IsAlwaysSkipRetryCode`（如 `ErrorCodeBadResponseBody`）→ false；否则 `operation_setting.ShouldRetryByStatusCode(code)`。

`ShouldRetryByStatusCode`（setting/operation_setting/status_code_ranges.go）的**默认重试范围**：
```
100-199, 300-399, 401-407, 409-499, 500-503, 505-523, 525-599
```
即除 400/408/504/524 外的多数 4xx/5xx 可重试；**504/524 永不重试**（`alwaysSkipRetryStatusCodes`），400/408 不在范围内自然不重试。

### 5.4 Task 类（异步任务）的 fallback
`controller/relay.go RelayTask` + `shouldRetryTaskRelay`：429/307/5xx（除超时）可重试，400/408/2xx/LocalError 不重试。且 `ResolveOriginTask` 会把渠道**锁定**（`LockedChannel`），重试时**复用同一渠道、仅轮换 key**（`GetNextEnabledKey`）。

### 5.5 返回给上层（客户端）的关键设计
- 成功：`Relay()` 直接返回，无封装，流式/非流式响应透传。
- 失败：`defer` 中统一把 `newAPIError` 转成 OpenAI 格式 `{"error":{...}}`（Claude / WSS 视 relayFormat），并带上 `requestId`。
- 最终失败时**退还预扣额度**（`PreConsumeBilling` + Refund）、记错误日志（`admin_info.use_channel` 记录尝试过的渠道链）、`perfmetrics.RecordRelaySample` 上报指标。

---

## 六、关键文件清单

| 文件 | 作用 |
|---|---|
| `controller/relay.go` | 转发主循环、getChannel、shouldRetry、processChannelError、AutoBan 触发 |
| `middleware/distributor.go` | 入口渠道预选、SetupContextForSelectedChannel（取 key）、getModelRequest |
| `service/channel_select.go` | CacheGetRandomSatisfiedChannel、RetryParam、auto 分组跨组重试 |
| `model/channel_cache.go` | GetRandomSatisfiedChannel（priority+weight 加权随机）、缓存索引 |
| `model/channel.go` | Channel/ChannelInfo、GetNextEnabledKey（多Key轮换）、UpdateChannelStatus、handlerMultiKeyUpdate |
| `constant/multi_key_mode.go` | MultiKeyMode（random/polling） |
| `service/channel.go` | DisableChannel / EnableChannel / ShouldDisableChannel / ShouldEnableChannel |
| `setting/operation_setting/status_code_ranges.go` | 重试/禁用状态码默认范围 |
| `common/constants.go` | RetryTimes、AutomaticDisable/EnableChannelEnabled、ChannelStatus 常量 |
| `controller/channel-test.go` | 渠道测试与自动恢复（EnableChannel 调用点） |

---

## 七、结论速览（回答原始 4 问）

1. **渠道选择**：按 `group → model` 从缓存索引取候选（含归一化匹配与 Advanced Custom 路径过滤）；按 **Priority 降序分层，重试次数决定落在哪个优先级层；同一层内按 weight 加权随机（weight=0 均权）**。非轮询。
2. **多 Key 轮换**：MultiKeyMode 有 **random（随机）** 与 **polling（轮询）** 两种；均只挑启用 key，已禁用 key 自动跳过；全部禁用则整个渠道禁用。
3. **AutoBan**：**单次命中即禁用（非连续计数）**；`ShouldDisableChannel` 依据渠道错误 / 401 等禁用状态码 / 关键词，需全局开关与渠道 AutoBan 都开启。多 Key 只禁用出错的那个 key；全部禁才禁渠道。**恢复靠后续渠道测试成功触发 `EnableChannel`（配合 `AutomaticEnableChannelEnabled`），默认整套机制关闭。**
4. **Fallback**：外层重试循环（默认 RetryTimes=0 可配置），每次失败重试时以 retry 计数把选择降到下一优先级层重新随机；`shouldRetry` 按渠道错误/状态码范围决定是否继续；成功直接透传，最终失败统一转 OpenAI/Claude 错误、退还预扣、记录尝试渠道链。
