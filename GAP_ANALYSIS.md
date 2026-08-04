# AndroidLLMRouter vs NEW API 互检差距分析报告

> 分析时间：2026-08-04
> 分析方式：5 路 subagent 并行对比 NEW API 源码与 AndroidLLMRouter 全部 Kotlin 代码
> 版本基线：AndroidLLMRouter v0.5.0 vs NEW API 最新源码

---

## 一、渠道管理对比

### 已有（18 项）
id, name, apiKeys(多Key), baseUrl, models, disabledModels, priority, weight, status, autoBan, testModel, responseTime, testTime, createdAt, usedQuota, keyStates, pollingIndex, keyMode

### 缺失（12 项）

| # | 字段 | NEW API 用途 | 优先级 |
|---|------|-------------|--------|
| 1 | **type** (渠道类型) | OpenAI/Azure/Claude/Gemini/自定义等，影响 API 格式适配和默认 BaseURL | 🔴 P0 |
| 2 | **model_mapping** (模型映射) | JSON 格式 `{"gpt-4":"gpt-4o"}`，请求时自动替换模型名，支持链式重定向+循环检测 | 🔴 P0 |
| 3 | **group** (分组) | 逗号分隔多分组，用于多租户/分场景路由 | 🟡 P1 |
| 4 | **status_code_mapping** | 状态码映射，如将上游 429 映射为本地 529 | 🟡 P1 |
| 5 | **tag** (标签) | 渠道分组标签 | 🟢 P2 |
| 6 | **setting** (代理等设置) | HTTP 代理/超时/重试等 per-channel 设置 | 🟡 P1 |
| 7 | **param_override** | 请求参数覆盖（如强制设置 temperature） | 🟢 P2 |
| 8 | **header_override** | 请求头覆盖 | 🟢 P2 |
| 9 | **other/other_info** | 扩展信息字段 | 🟢 P2 |
| 10 | **balance/balance_updated_time** | 余额查询+更新时间 | 🟡 P1 |
| 11 | **openai_organization** | OpenAI 组织 ID | 🟢 P2 |
| 12 | **settings (OtherSettings)** | 其他渠道级配置 | 🟢 P2 |

### 安卓独有
- **disabledModels** — 拉取模型后可勾选排除（NEW API 无此概念）

---

## 二、路由与负载均衡对比

### ✅ 移植准确（5 项）
1. 优先级分层 — groupBy priority + 降序排列
2. 层内加权随机 — rand.nextInt(totalWeight) → 遍历减权
3. 多 Key 轮换 random/polling — random 随机选、polling 环形扫描
4. shouldRetry 状态码 — 不重试 {400,408,504,524}，其余重试
5. 重试次数 — 全局 retryTimes 配置

### 🔴 高优先级差距（4 项）

| # | 差距 | NEW API 行为 | 安卓行为 | 优先级 |
|---|------|-------------|---------|--------|
| 1 | **AutoBan 过于激进** | 默认仅 **401** 触发禁用 | 所有可重试错误码（401-599）都禁用 Key | 🔴 P0 |
| 2 | **AutoBan 与 shouldRetry 耦合** | 独立判断（可"重试但不禁用"） | 耦合（只有 shouldRetry=true 才 AutoBan） | 🔴 P0 |
| 3 | **缺少全局开关** | 全局 AutomaticDisableChannelEnabled + per-channel | 仅 per-channel | 🟡 P1 |
| 4 | **异常类错误也禁用** | 仅 HTTP 错误响应触发禁用 | 超时/连接异常也禁用 Key | 🔴 P0 |

### 🟡 中优先级差距（6 项）
- 错误码重试/禁用规则不可运行时配置（硬编码）
- 缺少渠道亲和性（sticky session）
- 缺少 AutoBan 关键词匹配
- 加权随机缺少 smoothing 逻辑
- 缺少 IsSkipRetryError / IsChannelError 错误类型级控制
- 缺少重试链路日志

---

## 三、API 端点对比

### 已有端点（6 个）
- `GET /` / `GET /health` — 健康检查
- `GET /v1/models` — 本地聚合模型列表
- `POST /v1/chat/completions` — 支持 streaming ✅
- `POST /v1/embeddings` — ✅
- `POST /v1/completions` — ⚠️ **路由错误**：复用 handleChatCompletions，硬编码上游路径 /v1/chat/completions

### 缺失端点（29 个）

**🔴 P0 核心缺失（6 个）**：
- `POST /v1/images/generations` — DALL-E 图片生成
- `POST /v1/images/edits` — 图片编辑
- `POST /v1/audio/transcriptions` — 语音转文字
- `POST /v1/audio/translations` — 语音翻译
- `POST /v1/audio/speech` — 文字转语音
- `POST /v1/moderations` — 内容审核

**🟡 P1 重要缺失（6 个）**：
- `POST /v1/rerank` — 重排序
- `POST /v1/responses` — OpenAI Responses API
- `POST /v1/messages` — Claude 兼容
- `GET /v1/realtime` — WebSocket 实时
- `POST /v1/alpha/search` — 搜索

**🟢 P2 协议兼容缺失（8 个）**：
- `GET /v1/models/:model` / `GET /v1beta/models` / `POST /v1/edits` 等

**⚫ P3 任务类 + 管理 API**：
- Midjourney `/mj/*` / Suno `/suno/*` / 管理 `/api/*`

### 架构层面差距
- **鉴权**：仅支持 Bearer，缺失 x-api-key / x-goog-api-key / ?key= 等多协议
- **Multipart**：parseBody() 不支持文件上传，无法处理 audio/image
- **/v1/completions 路由错误**：需要独立 handler 转发到上游 /v1/completions

---

## 四、日志与统计对比

### 已有
- RouteLogEntity: 时间/渠道/模型/状态码/响应时间/Token 用量
- HomeScreen 统计卡片: 总请求数/成功率/平均响应时间/活跃渠道数
- 最近 50 条日志列表

### 缺失（NEW API 有但安卓没有）

| # | 统计维度 | 说明 | 优先级 |
|---|---------|------|--------|
| 1 | **Token 消费详情** | input_tokens / output_tokens / cache_tokens 分项 | 🔴 P0 |
| 2 | **费用统计** | 按模型/渠道/时间段的消费金额 | 🟡 P1 |
| 3 | **错误码分布** | 各错误码出现次数和占比 | 🟡 P1 |
| 4 | **请求详情查看** | 点击日志查看完整请求/响应内容 | 🟡 P1 |
| 5 | **时间段筛选** | 按日/周/月统计 | 🟡 P1 |
| 6 | **渠道对比** | 各渠道的请求量/成功率/响应时间对比 | 🟡 P1 |
| 7 | **Token 用量趋势** | 时间序列图 | 🟢 P2 |
| 8 | **模型使用分布** | 各模型的调用量占比 | 🟢 P2 |

---

## 五、系统设置对比

### 安卓已有（8 项）
serverPort, authEnabled, authToken, retryTimes, healthCheckEnabled, healthCheckInterval, autoStart, routeMode

### NEW API 有但安卓缺失（企业级功能）

| 领域 | 缺失能力 | 手机端是否需要 |
|------|---------|---------------|
| **多用户体系** | 注册/登录/OAuth/2FA/角色权限 | ❌ 个人使用不需要 |
| **令牌管理** | 多令牌/配额/IP限制/模型限制/过期 | 🟡 P1（多令牌有用） |
| **分组路由** | 分组定义/用户分组/分组倍率 | 🟡 P1（分场景路由有用） |
| **倍率体系** | 模型/补全/缓存/图片/音频倍率 | ❌ 个人使用不需要 |
| **价格计费** | 动态定价/按量按次/阶梯计费 | ❌ 个人使用不需要 |
| **分布式限流** | Redis 令牌桶/模型级限流 | 🟡 P1（本地限流有用） |
| **通知推送** | Telegram/SMTP 邮件 | ❌ 有通知栏够了 |

### 安卓独有（手机端特有，NEW API 没有）

| # | 功能 | 说明 |
|---|------|------|
| 1 | 前台服务保活 | START_STICKY + 前台通知 |
| 2 | 开机自启 | BootReceiver |
| 3 | 配置导出/导入 | SAF 文件选择器 |
| 4 | 路由模式切换 | URL 维度 vs Model 维度 |
| 5 | NanoHTTPD 嵌入式服务器 | 本地 HTTP 服务 |
| 6 | 协程异常保护 | SupervisorJob + CoroutineExceptionHandler |
| 7 | 本地 IP 检测 | 自动获取局域网 IP |

---

## 六、优先级排序的开发建议

### 🔴 P0 — 必须实现（影响核心功能）

1. **模型映射 (model_mapping)** — 用户请求 `gpt-4` 但上游需要 `gpt-4o`
2. **AutoBan 逻辑修复** — 默认仅 401 触发禁用，不耦合 shouldRetry，异常不禁用
3. **/v1/completions 路由修复** — 独立 handler 转发到上游 /v1/completions
4. **服务启动可靠性** — 确保前台服务+HTTP 服务器真正启动（v0.5.0 已修复 0.0.0.0 绑定）
5. **日志补充 Token 消费详情** — input/output/cache tokens 分项记录

### 🟡 P1 — 重要功能

6. 渠道类型 type — 影响 API 格式适配
7. 状态码映射 status_code_mapping
8. 多令牌管理 — 支持多个 API Key + 独立配额
9. 本地限流 — 按模型/渠道限流
10. 日志详情查看 + 时间段筛选
11. 渠道余额查询
12. 多协议鉴权 (x-api-key 等)

### 🟢 P2 — 可选增强

13. 分组路由
14. 图片生成/语音端点
15. 参数覆盖 param_override
16. 模型使用分布统计
