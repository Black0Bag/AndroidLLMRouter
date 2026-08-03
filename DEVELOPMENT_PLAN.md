# Android LLM Router（安卓原生大模型 API 路由网关）开发流程

> 项目目标：将 NEW API 的核心功能，以 **安卓原生 APP**（Kotlin + Jetpack Compose）形式完整落地。
> 非 WebView 打包，所有配置以原生 UI 设置；内置 HTTP 服务器释放 OpenAI 兼容端点供其他 Agent/应用调用；
> 支持 URL 维度路由与模型维度路由、多 Key 轮换、健康检查与自动故障切换；
> 启动后界面显示实时路由状态，前台服务 + 下拉通知常驻，尽可能后台保活以提供稳定路由服务。
>
> 使用方式：按步骤顺序执行，每完成一步在该步前面的 `[ ]` 前打 ✅（对号），标记该步骤已完成。
> 本文件一旦生成，步骤不再改动，只做完成标记。

---

## 一、需求与核心功能（用户需求原文整理）

1. **管理所有大模型 API**：一堆 URL + Key，一个 URL 可对应多个 Key，一个 URL 可对应多个模型。
2. **URL 维度路由**：在本 URL 下的模型间路由，若该 URL 全部模型都不通，切换到下一个 URL。
3. **模型维度路由**：选定一个模型后，在多个 URL + Key 下，只在此模型间路由。
4. **释放一个专用 API-URL**，供其他 Agent / 应用直接调用（OpenAI 兼容端点）。
5. **可设置是否使用 key**（鉴权开关）及其他自定义设置。
6. **点击启动按钮**后软件界面显示实时路由状态。
7. **下拉菜单常驻 + 尽可能后台保活**，提供稳定路由服务。
8. 全部功能以安卓原生 APP 形式落地（非 HTTP 打包成 App）。

## 二、NEW API 运行逻辑分析结论（移植依据）

通过对 NEW API（Go 项目）源码的深入分析，得出以下核心机制，将在安卓端等价实现：

### 数据模型：渠道 Channel = URL
- `BaseURL`（上游 URL）、`Key`（可多个，逗号分隔）、`Models`（该 URL 下模型列表）
- `Priority`（优先级，用于 URL 维度分层选择）、`Status`（状态）
- `ResponseTime`（毫秒，速度监控）、`Weight`（权重）
- `AutoBan`（自动禁用开关）、`TestModel`、`TestTime`
- 多 Key 用 `ChannelInfo`：`MultiKeyMode`（random / polling）、`MultiKeyPollingIndex`、每个 key 的状态/禁用原因/禁用时间

### 路由选择机制
- 请求带 `model` → 匹配包含该 model 的渠道集合。
- 内存缓存 `group → model → 渠道ID列表`（按 priority 降序）。
- **选择顺序 = 优先级分层 + 层内加权随机**：以 retry 数作为优先级层级下标（retry=0 用最高优先级，重试递增降级）；同层内按 Weight 加权随机。
- 匹配支持模型名归一化（如 gpts/thinking-*）。

### 多 Key 轮换
- `MultiKeyMode` 仅两种：**random（随机）** 和 **polling（轮询）**。
- random 从启用 key 中随机选；polling 基于 MultiKeyPollingIndex 环形找下一个启用 key（per-channel 锁保证线程安全），仅挑启用（未禁用）key。

### 自动禁用 AutoBan 与恢复
- 命中错误（渠道错误、401 等禁用状态码、关键词）即异步禁用渠道；多 Key 只禁用出错的那个 key，全部 key 都禁用才禁用渠道。
- 恢复：后续渠道测试成功触发启用。

### 失败 Fallback（关键）
- 外层 `for retry <= RetryTimes` 循环，每次失败用递增的 retry 重新选更低优先级渠道。
- 判定 `shouldRetry`：按渠道错误/状态码范围（默认重试 1xx/3xx/401-407/409-499/500-503/505-523/525-599，**504/524/400/408 不重试**）。
- 成功直接透传；最终失败统一转为 OpenAI/Claude 错误。

### 对上暴露的 API 端点（需在安卓端经内嵌 HTTP 服务器实现）
- `GET /v1/models`、`POST /v1/chat/completions`、`POST /v1/completions`
- `POST /v1/embeddings`、`POST /v1/responses`
- streaming：请求体 `stream=true` 触发 SSE 流式返回
- 鉴权：`Authorization: Bearer sk-xxx`，支持开关；管理端点分级（本 App 内自用）

## 三、技术选型

- **语言/UI**：Kotlin + Jetpack Compose（Material 3）
- **本地 HTTP 服务器**：NanoHTTPD 或 Ktor 嵌入式服务器（暴露 OpenAI 兼容端点）
- **数据存储**：Room（SQLite）+ DataStore；Key 加密存储（EncryptedSharedPreferences / SQLCipher）
- **网络**：OkHttp（上游转发，支持 Streaming/SSE 透传）
- **后台**：Foreground Service（前台服务 + 常驻通知下拉）+ WorkManager（健康检查定时任务）+ 开机自启（可选）
- **路由引擎**：自研（移植 NEW API 逻辑）

## 四、核心架构（对齐 NEW API）

1. **Channel（渠道 = URL）**：BaseURL + Key(可多个) + Models + Priority + Status + AutoBan + ResponseTime
2. **路由策略**：
   - URL 维度：按 Priority 分层选择 URL，本 URL 内模型全不通再降级到下一 URL
   - 模型维度：锁定 model 名，在所有包含该模型的 URL+Key 间按优先级/权重/轮询路由
3. **多 Key 轮换**：random（随机）/ polling（轮询），只选启用 Key
4. **健康检查**：定时探测各渠道/Key 可用性与响应延迟（ResponseTime），异常达阈值自动禁用
5. **AutoBan + Fallback**：错误命中即禁用渠道/Key；请求失败按 retry 递增切换更低优先级渠道
6. **API 端点**：`/v1/chat/completions`、`/v1/completions`、`/v1/models`、`/v1/embeddings`，支持 `sk-xxx` 鉴权（可开关）、Streaming
7. **状态展示**：启动后实时显示各渠道/Key 健康状态、当前路由链路、响应延迟

## 五、开发步骤（按顺序执行，完成后在本步骤前打 ✅）

- [ ] 1. 深入分析 NEW API 运行逻辑，产出移植设计文档（数据模型、路由、多Key、AutoBan、Fallback、API端点）
- [ ] 2. 创建并迁移本地设计文档到项目 docs，建立项目基础（Gradle 工程骨架、Kotlin、Compose、包结构）
- [ ] 3. 定义并实现本地数据模型与存储层（Channel/Key/Model/路由配置，Room 实体，Key 加密存储）
- [ ] 4. 实现核心路由引擎（渠道匹配、URL 维度路由、模型维度路由、多Key 轮换 polling/random）
- [ ] 5. 实现转发层（OkHttp 上游请求、Streaming/SSE 透传、错误码映射、重试/Fallback/AutoBan）
- [ ] 6. 实现健康检查与监控（定时探测渠道/Key 可用性与延迟、自动禁用与恢复、阈值判断）
- [ ] 7. 实现内嵌 HTTP 服务器（NanoHTTPD/Ktor + OkHttp 转发，暴露 OpenAI 兼容端点，sk-xxx 鉴权 + 开关）
- [ ] 8. 实现 App 主界面与移动端 UI（Material 3：首页路由状态、渠道列表、添加/编辑渠道表单）
- [ ] 9. 实现配置页原生 UI（URL/多Key/模型/优先级/路由模式/鉴权开关/自定义设置等，全部原生表单）
- [ ] 10. 实现实时路由状态展示（各渠道/Key 健康度、当前路由链路、响应延迟，UI 实时刷新）
- [ ] 11. 实现启动控制与后台服务（启动/停止路由服务、前台服务保活、下拉通知常驻、开机可选自启）
- [ ] 12. 本地联调与基础测试（单元测试路由逻辑、HttpServer 端点 smoke test、功能自测）
- [ ] 13. 编写 README、LICENSE、.gitignore，完善项目文档
- [ ] 14. 在 GitHub 新建仓库并 push 源码
- [ ] 15. 编写 GitHub Actions 自动化编译脚本（Ubuntu + JDK17 + Android SDK + Gradle，打包 APK）
- [ ] 16. 触发 CI，跟踪调试直至编译成功产出 APK
- [ ] 17. 将 APK 推送到 GitHub Release 发布（含版本 tag、release note、Assets）

## 六、版本与仓库

- GitHub 仓库：`AndroidLLMRouter`（新建）
- 版本：v0.1.0（首个可安装 APK）
- 语言：Kotlin，minSdk 26，targetSdk 34
