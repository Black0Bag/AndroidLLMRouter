# NEW API 源码 vs Android HttpApiServer.kt — API 端点对比分析

## 一、数据来源

| 项目 | 路径 | 技术栈 |
|------|------|--------|
| **NEW API** (Go) | `/workspace/AndroidLLMRouter/new-api-source/router/relay-router.go` | Go + Gin |
| **Android** | `/workspace/AndroidLLMRouter/app/src/main/java/com/llmrouter/server/HttpApiServer.kt` | Kotlin + NanoHTTPD |
| **Android Relay** | `/workspace/AndroidLLMRouter/app/src/main/java/com/llmrouter/relay/RelayHandler.kt` | Kotlin + OkHttp |

---

## 二、NEW API 暴露的全部 Relay 端点（relay-router.go）

### 对话 / 补全类
| # | 方法 | 路径 | RelayFormat | 说明 |
|---|------|------|-------------|------|
| 1 | POST | `/v1/chat/completions` | OpenAI | 聊天补全（支持 streaming） |
| 2 | POST | `/v1/completions` | OpenAI | 文本补全 |
| 3 | POST | `/v1/responses` | OpenAIResponses | OpenAI Responses API |
| 4 | POST | `/v1/responses/compact` | OpenAIResponsesCompaction | Responses 压缩格式 |
| 5 | POST | `/v1/alpha/search` | OpenAIAlphaSearch | Codex 独立 web search |
| 6 | POST | `/v1/messages` | Claude | Anthropic Claude 协议 |
| 7 | POST | `/pg/chat/completions` | — | Playground（UserAuth 鉴权） |

### 模型类
| # | 方法 | 路径 | 说明 |
|---|------|------|------|
| 8 | GET | `/v1/models` | 列出模型（自动识别 OpenAI/Anthropic/Gemini 头） |
| 9 | GET | `/v1/models/:model` | 获取单个模型 |
| 10 | GET | `/v1beta/models` | Gemini 风格模型列表 |
| 11 | GET | `/v1beta/openai/models` | Gemini 兼容 OpenAI 模型列表 |

### Embedding / Rerank
| # | 方法 | 路径 | 说明 |
|---|------|------|------|
| 12 | POST | `/v1/embeddings` | 文本嵌入 |
| 13 | POST | `/v1/rerank` | 重排序 |

### 图像类
| # | 方法 | 路径 | 说明 |
|---|------|------|------|
| 14 | POST | `/v1/images/generations` | 图像生成 |
| 15 | POST | `/v1/images/edits` | 图像编辑 |
| 16 | POST | `/v1/edits` | OpenAI 遗留风格编辑 |

### 音频类
| # | 方法 | 路径 | 说明 |
|---|------|------|------|
| 17 | POST | `/v1/audio/transcriptions` | 语音转文字 (STT/Whisper) |
| 18 | POST | `/v1/audio/translations` | 语音翻译 |
| 19 | POST | `/v1/audio/speech` | 文字转语音 (TTS) |

### 其他
| # | 方法 | 路径 | 说明 |
|---|------|------|------|
| 20 | POST | `/v1/moderations` | 内容审核 |
| 21 | GET | `/v1/realtime` | OpenAI Realtime（WebSocket） |

### Gemini 兼容
| # | 方法 | 路径 | 说明 |
|---|------|------|------|
| 22 | POST | `/v1/engines/:model/embeddings` | Gemini 风格 embeddings |
| 23 | POST | `/v1/models/*path` | Gemini 风格转发 |
| 24 | POST | `/v1beta/models/*path` | Gemini 原生 API 转发 |

### 任务类
| # | 方法 | 路径 | 说明 |
|---|------|------|------|
| 25 | — | `/mj/*` + `/:mode/mj/*` | Midjourney 全套（imagine/change/describe/blend/video/edits/action/modal/shorten/swap/upload/fetch/image-seed/list-by-condition） |
| 26 | — | `/suno/*` | Suno 音乐生成（submit/fetch/fetch/:id） |

### 已注册但未实现（RelayNotImplemented）
| # | 方法 | 路径 |
|---|------|------|
| 27 | POST | `/v1/images/variations` |
| 28 | GET/POST | `/v1/files` |
| 29 | GET/DELETE | `/v1/files/:id` |
| 30 | GET | `/v1/files/:id/content` |
| 31 | POST/GET | `/v1/fine-tunes` |
| 32 | GET | `/v1/fine-tunes/:id` |
| 33 | POST | `/v1/fine-tunes/:id/cancel` |
| 34 | GET | `/v1/fine-tunes/:id/events` |
| 35 | DELETE | `/v1/models/:model` |

---

## 三、Android HttpApiServer.kt 已实现的端点

| # | 方法 | 路径 | Handler | 说明 |
|---|------|------|---------|------|
| 1 | GET | `/` | 内联 | 服务信息 / 健康检查 |
| 2 | GET | `/health` | 内联 | 健康检查 |
| 3 | GET | `/v1/models` | `RelayHandler.handleListModels()` | 本地聚合模型列表 |
| 4 | POST | `/v1/chat/completions` | `RelayHandler.handleChatCompletions()` | 聊天补全（支持 streaming） |
| 5 | POST | `/v1/embeddings` | `RelayHandler.handleEmbeddings()` | 文本嵌入 |
| 6 | POST | `/v1/completions` | `RelayHandler.handleChatCompletions()` | ⚠️ 直接复用 chat/completions handler |

---

## 四、Android 缺失的端点（逐项详细分析）

### 🔴 P0 — 核心 OpenAI 兼容端点缺失

#### 1. `POST /v1/images/generations` — 图像生成
- **NEW API**: 通过 `RelayFormatOpenAIImage` 转发，Distribute 中间件自动填充默认 model（dall-e）
- **Android**: 完全缺失，RelayHandler 无对应方法
- **影响**: 无法使用 DALL-E / GPT-Image / Stable Diffusion 等图像生成模型
- **修复复杂度**: 中等 — 需新增 handler，转发逻辑与 chat 类似但需处理图片 base64/url 响应格式

#### 2. `POST /v1/audio/transcriptions` — 语音转文字 (STT)
- **NEW API**: 通过 `RelayFormatOpenAIAudio` 转发，distributor 中间件特殊处理 multipart/form-data
- **Android**: 完全缺失
- **影响**: 无法使用 Whisper 等模型进行语音识别
- **修复复杂度**: 高 — NanoHTTPD 的 `parseBody()` 对 multipart/form-data 支持有限，需处理文件上传

#### 3. `POST /v1/audio/speech` — 文字转语音 (TTS)
- **NEW API**: 通过 `RelayFormatOpenAIAudio` 转发
- **Android**: 完全缺失
- **影响**: 无法使用 TTS 模型
- **修复复杂度**: 中等 — 请求是 JSON，但响应是二进制音频流，需处理二进制透传

#### 4. `POST /v1/moderations` — 内容审核
- **NEW API**: 通过 `RelayFormatOpenAI` 转发
- **Android**: 完全缺失
- **影响**: 无法使用 OpenAI Moderation API
- **修复复杂度**: 低 — 请求/响应均为标准 JSON，逻辑与 embeddings 几乎相同

#### 5. `POST /v1/audio/translations` — 语音翻译
- **NEW API**: 通过 `RelayFormatOpenAIAudio` 转发
- **Android**: 完全缺失
- **影响**: 无法使用 Whisper 翻译功能
- **修复复杂度**: 高 — 同 transcriptions，需 multipart 文件上传

#### 6. `POST /v1/images/edits` — 图像编辑
- **NEW API**: 通过 `RelayFormatOpenAIImage` 转发
- **Android**: 完全缺失
- **影响**: 无法编辑已有图片
- **修复复杂度**: 高 — 需要 multipart/form-data 上传原图 + 蒙版

### 🟡 P1 — 重要兼容端点缺失

#### 7. `POST /v1/rerank` — 重排序
- **NEW API**: 通过 `RelayFormatRerank` 转发（Jina/Cohere 等）
- **Android**: 完全缺失
- **影响**: 无法使用 reranker 模型
- **修复复杂度**: 低 — 标准 JSON 请求/响应

#### 8. `POST /v1/responses` — OpenAI Responses API
- **NEW API**: 通过 `RelayFormatOpenAIResponses` 转发
- **Android**: 完全缺失
- **影响**: 无法使用 OpenAI 最新 Responses API（Codex/GPT-4.1 等）
- **修复复杂度**: 中等 — 新协议格式，需理解 responses streaming

#### 9. `POST /v1/responses/compact` — Responses 压缩
- **NEW API**: 通过 `RelayFormatOpenAIResponsesCompaction` 转发
- **Android**: 完全缺失
- **修复复杂度**: 中等

#### 10. `POST /v1/messages` — Anthropic Claude 协议
- **NEW API**: 通过 `RelayFormatClaude` 转发，自动处理 Anthropic 头
- **Android**: 完全缺失
- **影响**: 无法直接使用 Claude SDK / Anthropic API 格式
- **修复复杂度**: 中等 — 需处理 Anthropic 特有的请求/响应格式转换

#### 11. `GET /v1/realtime` — OpenAI Realtime (WebSocket)
- **NEW API**: WebSocket 路由，通过 `RelayFormatOpenAIRealtime` 转发
- **Android**: 完全缺失
- **影响**: 无法使用 Realtime API（语音对话等）
- **修复复杂度**: 极高 — NanoHTTPD WebSocket 支持有限，需大量改造

#### 12. `POST /v1/alpha/search` — Codex Web Search
- **NEW API**: 通过 `RelayFormatOpenAIAlphaSearch` 转发
- **Android**: 完全缺失
- **修复复杂度**: 低 — 标准 JSON

### 🟢 P2 — 协议兼容端点缺失

#### 13. `GET /v1/models/:model` — 获取单个模型
- **NEW API**: 支持按模型名查询单个模型详情
- **Android**: 缺失，仅支持列表
- **修复复杂度**: 低

#### 14. `GET /v1beta/models` — Gemini 风格模型列表
- **NEW API**: Gemini SDK 兼容
- **Android**: 缺失
- **修复复杂度**: 低

#### 15. `GET /v1beta/openai/models` — Gemini 兼容 OpenAI 列表
- **Android**: 缺失
- **修复复杂度**: 低

#### 16. `POST /v1/engines/:model/embeddings` — Gemini 风格 embeddings
- **Android**: 缺失
- **修复复杂度**: 低

#### 17. `POST /v1/models/*path` — Gemini 风格转发
- **Android**: 缺失
- **修复复杂度**: 中等

#### 18. `POST /v1beta/models/*path` — Gemini 原生 API
- **Android**: 缺失
- **修复复杂度**: 中等

#### 19. `POST /v1/edits` — OpenAI 遗留编辑
- **Android**: 缺失
- **修复复杂度**: 低（与 images/edits 类似）

#### 20. `POST /pg/chat/completions` — Playground
- **Android**: 缺失（使用 UserAuth 而非 TokenAuth）
- **修复复杂度**: 低

### ⚫ P3 — 任务类 API 完全缺失

#### 21. Midjourney 全套 (`/mj/*`)
- imagine / change / simple-change / describe / blend / video / edits / action / modal / shorten / swap / upload / fetch / image-seed / list-by-condition
- **Android**: 完全缺失
- **修复复杂度**: 高 — 异步任务模型，需任务存储 + 轮询机制

#### 22. Suno 全套 (`/suno/*`)
- submit / fetch / fetch/:id
- **Android**: 完全缺失
- **修复复杂度**: 高 — 同 Midjourney 异步任务

---

## 五、已有端点的功能缺陷

### 1. `/v1/completions` 路由错误 ⚠️
- **Android**: `uri == "/v1/completions"` → 调用 `handleChatCompletions(session)`
- **问题**: `handleChatCompletions` 内部硬编码上游路径为 `/v1/chat/completions`
- **结果**: `/v1/completions` 请求被错误转发到上游的 `/v1/chat/completions`，而非 `/v1/completions`
- **NEW API**: 正确区分 `/v1/completions`（RelayFormatOpenAI）和 `/v1/chat/completions`（同为 OpenAI 但路径不同）
- **修复**: 需在 RelayHandler 中新增 `handleCompletions()` 方法，或参数化上游路径

### 2. 鉴权方式单一
| 鉴权方式 | NEW API | Android |
|----------|---------|---------|
| `Authorization: Bearer <token>` | ✅ | ✅ |
| `x-api-key: <token>` (Anthropic) | ✅ 自动转写 | ❌ |
| `x-goog-api-key: <token>` (Gemini) | ✅ | ❌ |
| `?key=<token>` URL 参数 (Gemini) | ✅ | ❌ |
| `Sec-WebSocket-Protocol` (WS) | ✅ | ❌ (无 WS) |
| 无 token 时放行 | ✅ (可选) | ✅ (authEnabled=false) |

### 3. 中间件层缺失
| 中间件 | NEW API | Android | 影响 |
|--------|---------|---------|------|
| CORS | ✅ `middleware.CORS()` | ❌ | 跨域请求被拒 |
| 请求解压 | ✅ `DecompressRequestMiddleware()` | ❌ | gzip 请求体无法处理 |
| 系统性能检查 | ✅ `SystemPerformanceCheck()` | ❌ | 过载时无保护 |
| 请求速率限制 | ✅ `ModelRequestRateLimit()` | ❌ | 无限流保护 |
| 渠道分发 | ✅ `Distribute()` | ❌ (用 RouterEngine 替代) | 架构不同，功能对等 |
| 统计中间件 | ✅ `StatsMiddleware()` | ❌ | 无全局请求统计 |

### 4. Streaming 实现差异
- **NEW API**: `controller.Relay` 根据 `stream=true` 自动切换，SSE 不被全局 gzip 破坏（main.go 特别处理）
- **Android**: `handleChatCompletions` 支持 stream，通过 `newChunkedResponse` 返回 SSE
- **问题**: Android 仅 chat/completions 支持 streaming；embeddings 和其他端点不支持（NEW API 中也主要 chat/responses 支持）

### 5. `/v1/models` 实现差异
- **NEW API**: 请求转发到上游获取真实模型列表，支持按渠道类型（OpenAI/Anthropic/Gemini）返回不同格式
- **Android**: 本地聚合所有渠道的 `modelList()`，返回统一的 OpenAI 格式
- **影响**: Android 无法获取上游真实的模型能力信息（如 context_length、capabilities 等）

### 6. Multipart/Form-Data 支持
- **NEW API**: distributor 中间件对 `/v1/audio/transcriptions` 等端点特殊处理 multipart 请求
- **Android**: `parseBody()` 仅处理 `postData`（JSON body），不支持 multipart 文件上传
- **影响**: 即使添加了 audio/image 端点路由，也无法正确转发文件上传请求

### 7. HTTP 状态码映射
- **Android**: 502 → `INTERNAL_ERROR`（应为 `BAD_GATEWAY`，NanoHTTPD 可能不支持）
- **NEW API**: 完整的 HTTP 状态码支持

---

## 六、汇总矩阵

| 端点 | NEW API | Android | 状态 |
|------|---------|---------|------|
| `GET /v1/models` | ✅ | ✅ | ⚠️ 实现不同（本地聚合 vs 上游转发） |
| `GET /v1/models/:model` | ✅ | ❌ | 🔴 缺失 |
| `POST /v1/chat/completions` | ✅ | ✅ | ✅ 基本对齐 |
| `POST /v1/completions` | ✅ | ⚠️ | 🔴 路由错误（转发到错误上游路径） |
| `POST /v1/embeddings` | ✅ | ✅ | ✅ 基本对齐 |
| `POST /v1/images/generations` | ✅ | ❌ | 🔴 缺失 |
| `POST /v1/images/edits` | ✅ | ❌ | 🔴 缺失 |
| `POST /v1/edits` | ✅ | ❌ | 🟡 缺失 |
| `POST /v1/audio/transcriptions` | ✅ | ❌ | 🔴 缺失 |
| `POST /v1/audio/translations` | ✅ | ❌ | 🔴 缺失 |
| `POST /v1/audio/speech` | ✅ | ❌ | 🔴 缺失 |
| `POST /v1/moderations` | ✅ | ❌ | 🔴 缺失 |
| `POST /v1/rerank` | ✅ | ❌ | 🟡 缺失 |
| `POST /v1/responses` | ✅ | ❌ | 🟡 缺失 |
| `POST /v1/responses/compact` | ✅ | ❌ | 🟡 缺失 |
| `POST /v1/alpha/search` | ✅ | ❌ | 🟡 缺失 |
| `POST /v1/messages` (Claude) | ✅ | ❌ | 🟡 缺失 |
| `GET /v1/realtime` (WS) | ✅ | ❌ | 🟡 缺失 |
| `POST /v1/engines/:model/embeddings` | ✅ | ❌ | 🟢 缺失 |
| `POST /v1/models/*path` (Gemini) | ✅ | ❌ | 🟢 缺失 |
| `GET /v1beta/models` | ✅ | ❌ | 🟢 缺失 |
| `GET /v1beta/openai/models` | ✅ | ❌ | 🟢 缺失 |
| `POST /v1beta/models/*path` | ✅ | ❌ | 🟢 缺失 |
| `POST /pg/chat/completions` | ✅ | ❌ | 🟢 缺失 |
| `/mj/*` (Midjourney) | ✅ | ❌ | ⚫ 缺失 |
| `/suno/*` (Suno) | ✅ | ❌ | ⚫ 缺失 |
| `GET /` (health) | ❌ | ✅ | — Android 独有 |
| `GET /health` | ❌ | ✅ | — Android 独有 |
| `/api/*` (管理 API) | ✅ | ❌ | ⚫ 完全缺失（另一架构层） |

---

## 七、建议优先级排序

### 立即修复（P0 — 基础功能正确性）
1. **修复 `/v1/completions` 路由错误** — 当前转发到错误的上游路径
2. **新增 `POST /v1/moderations`** — 实现最简单（纯 JSON），且是 OpenAI 核心端点
3. **新增 `POST /v1/rerank`** — 纯 JSON，实现简单
4. **新增 `POST /v1/alpha/search`** — 纯 JSON，实现简单

### 短期补齐（P1 — 核心 OpenAI 兼容）
5. **新增 `POST /v1/images/generations`** — 需处理图片响应格式
6. **新增 `POST /v1/audio/speech`** — 需处理二进制音频响应透传
7. **新增 `POST /v1/responses`** — OpenAI 最新 API，越来越重要
8. **新增 `GET /v1/models/:model`** — 简单补充
9. **添加 CORS 中间件** — 浏览器客户端必需

### 中期补齐（P2 — 多协议支持）
10. **新增 `POST /v1/messages`** — Claude 协议
11. **新增 multipart/form-data 支持** — 为 audio transcriptions/translations 和 images/edits 铺路
12. **新增 `POST /v1/audio/transcriptions`** — 需 multipart 支持
13. **新增 `POST /v1/audio/translations`** — 需 multipart 支持
14. **新增 `POST /v1/images/edits`** — 需 multipart 支持
15. **扩展鉴权方式** — 支持 x-api-key / x-goog-api-key / ?key=

### 长期规划（P3 — 高级功能）
16. **新增 `GET /v1/realtime`** — WebSocket，需 NanoHTTPD 改造
17. **新增 Gemini 兼容端点** — /v1beta/*
18. **新增 Midjourney/Suno 任务类** — 需异步任务存储架构
19. **新增管理 API (`/api/*`)** — 需完整的管理后台架构

---

## 八、关键文件索引

| 文件 | 路径 |
|------|------|
| NEW API 路由定义 | `/workspace/AndroidLLMRouter/new-api-source/router/relay-router.go` |
| NEW API RelayMode 常量 | `/workspace/AndroidLLMRouter/new-api-source/relay/constant/relay_mode.go` |
| NEW API 端点默认值 | `/workspace/AndroidLLMRouter/new-api-source/common/endpoint_defaults.go` |
| NEW API 已有分析报告 | `/workspace/AndroidLLMRouter/new-api-api-analysis.md` |
| Android HTTP 服务器 | `/workspace/AndroidLLMRouter/app/src/main/java/com/llmrouter/server/HttpApiServer.kt` |
| Android 转发处理器 | `/workspace/AndroidLLMRouter/app/src/main/java/com/llmrouter/relay/RelayHandler.kt` |
| Android 路由服务 | `/workspace/AndroidLLMRouter/app/src/main/java/com/llmrouter/service/RouterService.kt` |
