# NEW API 对外暴露的 API 端点结构分析报告

分析对象：`/workspace/AndroidLLMRouter/new-api-source`（Go + Gin 项目，QuantumNous/new-api）

路由注册入口：`main.go` 中调用 `router.SetRouter(server, ...)`，由 `router/main.go` 依次注册
`SetApiRouter`(管理 API) / `SetDashboardRouter`(计费/用量兼容) / `SetRelayRouter`(模型转发) / `SetVideoRouter`(视频) / `SetWebRouter`(前端静态资源)。

---

## 一、OpenAI 兼容 API 端点（模型转发层，位于 `relay-router.go`）

核心框架：`/v1` 分组挂了以下中间件：`RouteTag("relay")`、`SystemPerformanceCheck()`、**`TokenAuth()`（鉴权）**、`ModelRequestRateLimit()`；具体路由上再挂 `Distribute()`（渠道分发）。流式（SSE）/streaming 由 `controller.Relay` 根据请求体 `stream=true` 自动处理（main.go 明确注释避免对全局启动 gzip 以保证 SSE 工作）。

### 1) 模型
| 方法 | 路径 | 说明 |
|------|------|------|
| GET | `/v1/models` | 列出模型（自动识别 OpenAI/Anthropic/Gemini 头部分发） |
| GET | `/v1/models/:model` | 获取单个模型 |

### 2) 对话 / 补全
| 方法 | 路径 | 协议 |
|------|------|------|
| POST | `/v1/chat/completions` | OpenAI 对话（支持 streaming） |
| POST | `/v1/completions` | OpenAI 补全 |
| POST | `/v1/responses` | OpenAI Responses API |
| POST | `/v1/responses/compact` | Responses 压缩 |
| POST | `/v1/realtime` | OpenAI Realtime（**WebSocket**，`GET` 升级） |
| POST | `/v1/messages` | **Anthropic Claude** 协议 |
| POST | `/v1beta/models/*path` | Gemini 原生命令式格式（`/models/xx:generate` 等） |
| POST | `/v1/engines/:model/embeddings` | Gemini 风格 |
| POST | `/v1/models/*path` | Gemini 风格 |
| POST | `/v1/alpha/search` | Codex 独立 web search |

### 3) Embedding / Rerank
| 方法 | 路径 |
|------|------|
| POST | `/v1/embeddings` |
| POST | `/v1/rerank` |

### 4) 图像（Image）
| 方法 | 路径 |
|------|------|
| POST | `/v1/images/generations` |
| POST | `/v1/images/edits` |
| POST | `/v1/edits`（OpenAI 遗留风格） |

### 5) 音频（Audio）
| 方法 | 路径 |
|------|------|
| POST | `/v1/audio/transcriptions` |
| POST | `/v1/audio/translations` |
| POST | `/v1/audio/speech`（TTS） |

### 6) 其他
| 方法 | 路径 |
|------|------|
| POST | `/v1/moderations`（内容审核） |
| POST | `/pg/chat/completions`（Playground，用 UserAuth） |

### 7) 已注册但返回“未实现”（`RelayNotImplemented`）
`POST /v1/images/variations`、`GET/POST /v1/files`、`GET/DELETE /v1/files/:id`、`GET /v1/files/:id/content`、
`POST/GET /v1/fine-tunes`、`GET /v1/fine-tunes/:id`、`POST /v1/fine-tunes/:id/cancel`、`GET /v1/fine-tunes/:id/events`、`DELETE /v1/models/:model`

### 8) Gemini 模型列举（额外）
| 方法 | 路径 |
|------|------|
| GET | `/v1beta/models`（Gemini 风格） |
| GET | `/v1beta/openai/models`（Gemini 兼容 OpenAI） |

### 9) 任务类（Midjourney / Suno）
- Midjourney：`/mj/*` 与 `/:mode/mj/*`（GET /image/:id、POST /submit/{action,shorten,modal,imagine,change,simple-change,describe,blend,...}）
- Suno：`/suno/submit/:action`、`/suno/fetch`（POST/GET）

---

## 二、鉴权机制（sk-xxx Token）——**支持**

所有 `/v1` 转发路由和 `/v1/models` 等均挂 **`middleware.TokenAuth()`**。传入方式（源码 `middleware/auth.go`）：

1. **标准 OpenAI 方式**：请求头 `Authorization: Bearer sk-xxxx`（也可直接放 token，不带 Bearer 前缀）。
2. **Anthropic 方式**：请求头 `x-api-key: <token>` —— 代码会自动转写为 `Authorization: Bearer <token>`。
3. **Gemini 方式**：请求头 `x-goog-api-key: <token>`，或 URL 查询参数 `?key=<token>`。
4. **WebSocket**：通过 `Sec-WebSocket-Protocol` 请求头携带 token。

Dashboard 管理接口（`/api/*`）则走另一套会话/访问令牌鉴权：`Authorization: Bearer` 中原生 dashboard 会话 token 或 PAT（个人访问令牌），并通过 `UserAuth()`/`AdminAuth()`/`RootAuth()` 做角色分级。

---

## 三、管理类 API 端点与系统配置端点（`/api/*`，`api-router.go` / `channel-router.go` / `authz-router.go`）

统一前缀 `/api`，挂 `gzip`、`BodyStorageCleanup`、`GlobalAPIRateLimit`。

### 1) 公开 / 无需鉴权
`GET /api/setup`、`POST /api/setup`（初始化）、`GET /api/status`、`GET /api/uptime/status`、
`GET /api/notice`、`GET /api/user-agreement`、`GET /api/privacy-policy`、`GET /api/about`、`GET /api/home_page_content`、
`GET /api/ratio_config`、`GET /api/verification`、`GET /api/reset_password`、`POST /api/user/reset`
支付 webhook：`POST /api/stripe/webhook`、`POST /api/creem/webhook`、`POST /api/waffo/webhook`、`POST /api/waffo-pancake/webhook/:env`

### 2) 用户/认证（`/api/user/*`）
注册/登录/登出/刷新/2FA/Passkey：`POST /api/user/register|login|login/2fa|auth/refresh|auth/logout|passkey/login/begin|passkey/login/finish`
（登录需 UserAuth）会话管理、自我信息、额度、充值、支付：`GET /api/user/self`、`PUT/DELETE /api/user/self`、
`GET /api/user/token`（生成访问令牌）、`POST /api/user/topup`、`POST /api/user/pay`、Check-in、Aff 等
管理员（AdminAuth）：`GET/POST/PUT /api/user` 用户 CRUD、subscribe 管理等

### 3) Token 管理（UserAuth）
`GET /api/token/`、`GET /api/token/search`、`GET /api/token/:id`、`POST /api/token/`、`PUT /api/token/`、
`DELETE /api/token/:id`、`POST /api/token/:id/key`（取回密钥）、`POST /api/token/batch`、`POST /api/token/auto-groups`

### 4) 用量（`/api/usage/token`）
`GET /api/usage/token/`（`TokenAuthReadOnly`，可用 sk token 查用量）

### 5) 渠道管理（`/api/channel/*`，AdminAuth + 权限）—— `channel-router.go`
`GET /api/channel/` 列表、`GET /api/channel/search`、`GET /api/channel/models`、
`POST /api/channel/` 新增、`PUT /api/channel/` 更新、`DELETE /api/channel/:id`、
`GET /api/channel/test`、`POST /api/channel/:id/key`（RootAuth）、
`POST /api/channel/fetch_models`、`POST /api/channel/ollama/pull` 等大量管理子路由

### 6) 系统配置（RootAuth）
`GET/PUT /api/option/`（获取/更新全局配置）、`POST /api/option/payment_compliance`、
`GET/DELETE /api/option/channel_affinity_cache`、`POST /api/option/rest_model_ratio`、waffo-pancake 相关
权限目录：`GET /api/authz/catalog`（AdminAuth）
性能：`GET /api/performance/stats`、`POST /api/performance/reset_stats|gc`、`GET/DELETE /api/performance/logs`（RootAuth）
模型比例同步：`GET /api/ratio_sync/channels`、`POST /api/ratio_sync/fetch`（RootAuth）

### 7) 其他
充值码：`GET/POST/PUT/DELETE /api/redemption`（AdminAuth）
订阅：`GET /api/subscription/plans`、`GET/PUT /api/subscription/self` 及支付，（Admin 前缀 `/api/subscription/admin/*`）

---

## 四、健康检查端点

| 方法 | 路径 | 鉴权 | 用途 |
|------|------|------|------|
| GET | `/api/status` | **无需鉴权（公开）** | 系统运行状态 / 健康检查 |
| GET | `/api/uptime/status` | 公开 | Uptime Kuma 探活专用 |
| GET | `/api/status/test` | **AdminAuth** | 管理员手动测试/触发行人健康检查 |

> 其中 `GET /api/status` 为公开、无需 token，可作为最简便的存活探针；`/api/uptime/status` 面向 Uptime Kuma 监控。

---

## 五、Summary（要点）

- **OpenAI 兼容/转发**：`/v1/chat/completions`、`/v1/completions`、`/v1/embeddings`、`/v1/images/*`、`/v1/audio/*`、`/v1/models`、`/v1/rerank`、`/v1/responses`、`/v1/moderations`、`/v1/realtime`(WS) 等，支持 streaming（请求体 `stream=true`，main.go 特别保证 SSE 不被全局 gzip 破坏）。
- **鉴权**：支持 sk-xxx token，可通过 `Authorization: Bearer`、`x-api-key`、`x-goog-api-key`、`?key=`、WS `Sec-WebSocket-Protocol` 传入。
- **管理与配置**：集中在 `/api/*`，覆盖用户/Token/渠道/系统配置/订阅/充值码/权限等；分级鉴权 UserAuth/AdminAuth/RootAuth。
- **健康检查**：`GET /api/status`（公开）为最直接探针，另有 `/api/uptime/status`。
