# 协程/RouterEngine/HealthChecker 冻结审查报告

审查文件：
- app/src/main/java/com/llmrouter/health/HealthChecker.kt
- app/src/main/java/com/llmrouter/router/RouterEngine.kt
关联调用方：RelayHandler.kt / HttpApiServer.kt / RouterService.kt / BootReceiver.kt / MainViewModel.kt / ChannelRepository.kt(SettingsRepository.kt) / Daos.kt / Entities.kt

## 一、严重缺陷（按优先级）

### S1. selectKey(polling) 用陈旧快照写回 DB，会"复活"已被禁用的 Key（AutoBan 被静默抵消）
文件：RouterEngine.kt `selectKey()` 轮询分支
```kotlin
channelRepository.updateKeyStates(channel.id, channel.keyStates, nextIndex)
```
- 写入的是缓存加载时的旧 `channel.keyStates` 字符串（内存快照），**不是** `parseKeyStates` 解析出的最新状态。
- 时序：refreshCache 后快照为"全部启用"；disableKey 在 DB 中禁用 key1 后，下一次 polling selectKey 就会把 DB 覆盖回旧快照 → key1 被重新启用。
- 修复：改为 `serializeKeyStates(keyStates)`；且每次请求都写 DB 属多余写放大。

### S2. disableKey/selectKey 均为基于陈旧内存快照的 read-modify-write → 并发丢更新，渠道级 AutoBan 可能永不触发
- 锁（Mutex）只保证进程内互斥，但两个函数都从不可变的缓存 `channel.keyStates` 出发再整串写回 DB。
- 两个并发 401（不同 key）→ 各自快照里互不知道对方的禁用 → 后写者覆盖先写者 → 只有一个 key 被禁用；`allDisabled` 判断也基于错误快照。
- 修复方向：进入锁后 `channelRepository.getChannelById(id)` 重读最新，或在内存维护 per-channel 实时 keyStates Map。

### S3. 渠道级 AutoBan 的恢复是死代码：健康检查永远不会检查 AUTO_BANNED 渠道
- DAO `getEnabledChannels()` 过滤 `status = 1`(ENABLED)；`refreshCache`/`allEnabledChannels` 只含启用渠道。
- `runHealthCheck()` 遍历 `allEnabledChannels` → AUTO_BANNED 渠道不在列表 → `checkChannel` 永远不会对其执行 → `recoverChannel` 不可达。
- 被 ban 渠道只能手工恢复；与 NEW API「对 banned 渠道也做健康检查以便自动恢复」的设计不符。

## 二、并发安全

### S4. channelLocks 是普通 HashMap，getOrPut 多线程并发突变 → 数据竞争
`private val channelLocks = mutableMapOf<Long, Mutex>()` + `getOrPut`，被 RelayHandler(IO 线程)、HealthChecker(IO)、UI 并发调用。普通 HashMap 非线程安全，并发 getOrPut 可能损坏结构（丢条目/异常），最坏情况同一渠道拿到两把不同 Mutex → 串行化保证失效、丢更新放大。
修复：`ConcurrentHashMap`。
（注：两个被审计文件中没有 MutableStateFlow；全项目唯一 MutableStateFlow 是 MainViewModel._isServiceRunning，仅在主线程 touch，无并发问题。）

### S5. refreshCache 两个缓存字段非原子更新
先写 `allEnabledChannels` 再写 `channelCache`，读取方可能短暂看到不一致（新 modelMap + 旧列表）。@Volatile 保证单字段可见性，不保证成对一致性。低风险。

## 三、Mutex 死锁 & runBlocking 检查结论

- Mutex：`selectKey`/`disableKey`/`recoverChannel` 各自只持一把锁，无嵌套加锁、无锁顺序反转 → **无死锁**。锁内做 suspend Room 写，会串行化该渠道 Key 操作（吞吐限制），但不会死锁。
- runBlocking：两个被审计文件**没有** runBlocking。
  - HttpApiServer.serve() 的 runBlocking 跑在 NanoHTTPD 工作线程（非主线程），不产生主线程 ANR，但每请求阻塞一个 HTTP 线程（配合 RelayHandler 内 OkHttp 阻塞 execute + 300s 读超时，高并发时线程累积）。
  - BootReceiver.onReceive 用 runBlocking 在主线程读 DataStore → **ANR 风险**（DataStore 首次读磁盘 + 广播接收 10s 限制），建议改为异步/取 lastKnownPort。
- `SettingsRepository.getSnapshot()` 的 `prefs.collect { ...; return@collect }`：因 collect 为 inline 函数，`return@collect` 是非局部返回，第一次发射即返回 → **功能正常**（非挂死），只是更清晰的写法是 `.first()`。

## 四、AutoBan/Fallback 逻辑正确性

- C6. `disableKey(channel, keyIndex: Int, reason: String)` 参数类型：**无问题**。唯一调用方 RelayHandler.handleUpstreamError 传 String（`"HTTP $statusCode: ${errorBody.take(100)}"`）。类型一致。
- C7. `selectChannel` null 处理：RelayHandler 中 `channel == null → break`（合理，避免无谓重试）；`selectKey == null → continue`（继续下一层，合理）。无崩溃。
- C8. `selectChannel` 的 `if(routeMode=="url"){...}else{...}` 两分支**完全相同**，文档声称的 URL 维度 vs 模型维度降级并未实现；且按 priority 分组而非按 baseUrl 分组，与注释「本 URL 内模型全不通再降级」不符。
- C9. 重试计数与优先级层级混用：若 `retryTimes < 优先级层数`，低优先级层永远到不了；同层内 weightedRandomSelect 为随机，retry 可能反复选中同一失效渠道/Key。
- C10. `weightedRandomSelect` 的 `sumOf { weight.coerceAtLeast(1) }` 可能 Int 溢出为负 → `Random.nextInt(负数)` 抛 IllegalArgumentException。默认权重 1 不触发，属健壮性隐患；且 RelayHandler 中 selectChannel 在 try 块外调用，异常会冒到 HttpApiServer.serve() 的 catch → 返回 500（不崩溃）。
- C11. polling 模式 `channel.pollingIndex % keys.size` 若 pollingIndex 为负（DB 异常）→ checkIdx 为负 → `keyStates[负数]` IndexOutOfBoundsException。低概率。

## 五、HealthChecker 细节

- H1. `checkChannel` 中 `response.close()` 在 try 内：若 `updateTestResult`/`recoverChannel` 抛异常则不会 close → OkHttp 连接泄漏；catch 吞掉所有异常（失败静默）。建议 try/finally close()。
- H2. 只有 `channel.status == STATUS_AUTO_BANNED` 才调用 recoverChannel；因 S3，该分支实际不可达。
- H3. `runHealthCheck` 串行逐渠道检查，一轮耗时 = Σ各渠道网络耗时上限（15s/30s），与 healthCheckInterval 无错峰，渠道多时可能持续占用 IO。非崩溃。
- H4. `testChannel` 逐个 key 测试逻辑正确（首个成功即返回；bestResult 记录首次失败）。

## 六、其它

- O1. MainViewModel 与 RouterService 各持**独立** RouterEngine 实例：UI 增删改后只刷新 VM 的缓存，运行中服务的缓存不更新 → 服务端路由不感知渠道变更（需重启生效）。逻辑缺陷，非崩溃。
- O2. `refreshCache` 调 suspend Room DAO，Room suspend 查询离线执行 → 从主线程/Default 调用都安全，无主线程 DB 阻塞问题。
- O3. RelayHandler `selectKeyWithIndex` 用 `keys.indexOf(key)` 定位 keyIndex：key 重复时总命中第一个；若 keyStates 长度与 keys 不匹配则索引错位。次要。
- O4. 流式分支把 `response.body!!.byteStream()` 交给 NanoHTTPD 而自身不 close 调用 → 客户端断连时连接可能泄漏。次要。

## 修复优先级建议
1. S1/S2：进入锁后重读 DB 最新 keyStates，selectKey 写回 `serializeKeyStates(...)`。
2. S3：健康检查改为覆盖 AUTO_BANNED 渠道（单独查询 or 扩展 DAO）。
3. S4：channelLocks 换 ConcurrentHashMap。
4. C8/C9：明确 routeMode 两个分支的降级差异（或删除重复分支）；retry 与优先级层解耦。
5. H1：checkChannel 用 try/finally 关 response；C10/C11 加边界保护。