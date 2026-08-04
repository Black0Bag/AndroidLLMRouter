# NEW API vs 安卓 LLM Router — 渠道管理功能逐项对比

> 分析基于：
> - NEW API 源码：`/workspace/AndroidLLMRouter/new-api-source/model/channel.go` (Go struct)
> - 安卓代码：`/workspace/AndroidLLMRouter/app/src/main/java/com/llmrouter/data/model/Entities.kt` (Room entity)
> - 路由引擎：`RouterEngine.kt` / 转发层：`RelayHandler.kt` / 编辑界面：`ChannelEditScreen.kt`

---

## 一、Channel 字段对比

| # | 字段名 | NEW API (Go) | 安卓 (Kotlin) | 状态 |
|---|--------|-------------|---------------|------|
| 1 | **id** | `Id int` | `id: Long` (autoGenerate) | ✅ 已有 |
| 2 | **name** | `Name string` (带索引) | `name: String` | ✅ 已有 |
| 3 | **type** | `Type int` (渠道类型枚举，1~60) | ❌ 无此字段 | ❌ **缺失** |
| 4 | **key** | `Key string` (单/多Key，支持换行/JSON数组) | `apiKeys: String` (逗号分隔) | ✅ 已有 (命名不同) |
| 5 | **base_url** | `BaseURL *string` (为空时按 type 回退默认值) | `baseUrl: String` | ✅ 已有 (但无 type 回退逻辑) |
| 6 | **models** | `Models string` (逗号分隔，上游支持的模型) | `models: String` (逗号分隔) | ✅ 已有 |
| 7 | **group** | `Group string` (逗号分隔多分组，默认 "default") | ❌ 无此字段 | ❌ **缺失** |
| 8 | **priority** | `Priority *int64` (bigint) | `priority: Int` | ✅ 已有 |
| 9 | **weight** | `Weight *uint` (默认0) | `weight: Int` (默认1) | ✅ 已有 |
| 10 | **status** | `Status int` (1=启用,2=手动禁用,3=自动禁用) | `status: Int` (同语义) | ✅ 已有 |
| 11 | **model_mapping** | `ModelMapping *string` (JSON text) | ❌ 无此字段 | ❌ **缺失** |
| 12 | **status_code_mapping** | `StatusCodeMapping *string` (JSON, 上游错误码→本地码) | ❌ 无此字段 | ❌ **缺失** |
| 13 | **auto_ban** | `AutoBan *int` (0/1) | `autoBan: Boolean` | ✅ 已有 (类型不同) |
| 14 | **test_model** | `TestModel *string` | `testModel: String` | ✅ 已有 |
| 15 | **response_time** | `ResponseTime int` (ms) | `responseTime: Int` | ✅ 已有 |
| 16 | **test_time** | `TestTime int64` | `testTime: Long` | ✅ 已有 |
| 17 | **created_time** | `CreatedTime int64` | `createdAt: Long` | ✅ 已有 |
| 18 | **used_quota** | `UsedQuota int64` | `usedQuota: Long` | ✅ 已有 |
| 19 | **balance** | `Balance float64` (USD余额) | ❌ 无此字段 | ❌ **缺失** |
| 20 | **balance_updated_time** | `BalanceUpdatedTime int64` | ❌ 无此字段 | ❌ **缺失** |
| 21 | **other** | `Other string` (Azure API Version 等) | ❌ 无此字段 | ❌ **缺失** |
| 22 | **other_info** | `OtherInfo string` (JSON，存储 status_reason/status_time 等) | ❌ 无此字段 | ❌ **缺失** |
| 23 | **tag** | `Tag *string` (带索引，用于批量管理同标签渠道) | ❌ 无此字段 | ❌ **缺失** |
| 24 | **setting** | `Setting *string` (JSON text，代理/HTTP传输等) | ❌ 无此字段 | ❌ **缺失** |
| 25 | **param_override** | `ParamOverride *string` (JSON text，请求参数覆盖) | ❌ 无此字段 | ❌ **缺失** |
| 26 | **header_override** | `HeaderOverride *string` (JSON text，请求头覆盖) | ❌ 无此字段 | ❌ **缺失** |
| 27 | **remark** | `Remark *string` (varchar 255) | ❌ 无此字段 | ❌ **缺失** |
| 28 | **openai_organization** | `OpenAIOrganization *string` | ❌ 无此字段 | ❌ **缺失** |
| 29 | **channel_info** (ChannelInfo) | `ChannelInfo` JSON (多Key状态/轮询索引/模式) | `keyStates: String` + `pollingIndex: Int` + `keyMode: String` (分散存储) | ✅ 已有 (实现方式不同) |
| 30 | **settings (OtherSettings)** | `OtherSettings string` (Azure 版本等) | ❌ 无此字段 | ❌ **缺失** |
| 31 | **disabled_models** | ❌ NEW API 无此字段 | `disabledModels: String` (逗号分隔) | ⭐ 安卓独有 |

### 安卓独有字段（NEW API 没有）
| 字段 | 说明 |
|------|------|
| `disabledModels` | 安卓从服务器拉取全部模型后，可以勾选/取消勾选排除某些模型。NEW API 用 `models` 字段直接维护最终列表，没有"拉取后排除"的概念。 |
| `keyStates` (JSON数组) | 安卓把每个 Key 的 enabled/disabledReason/disabledTime 存为 JSON 数组字符串。NEW API 用 `ChannelInfo.MultiKeyStatusList` (map[int]int) 等字段存在 channel_info JSON 列中。功能等价。 |
| `pollingIndex` | 安卓独立字段。NEW API 在 `ChannelInfo.MultiKeyPollingIndex` 中。 |
| `keyMode` | 安卓用 `"random"` / `"polling"` 字符串。NEW API 用 `ChannelInfo.MultiKeyMode` 枚举。 |

---

## 二、模型映射（Model Mapping）对比

| 功能点 | NEW API | 安卓 | 状态 |
|--------|---------|------|------|
| **字段定义** | `ModelMapping *string` — JSON text，如 `{"gpt-4":"gpt-4o"}` | ❌ 无 | ❌ **缺失** |
| **实现逻辑** | `relay/helper/model_mapped.go`：解析 JSON → 支持链式重定向（A→B→C）→ 循环检测 → 最终替换 `request.Model` | ❌ 无任何模型名替换逻辑 | ❌ **缺失** |
| **UI 支持** | 前端有完整的 model_mapping 编辑器（key-value 表格）、预览、校验 |  渠道编辑界面无此输入 | ❌ **缺失** |
| **批量标签编辑** | `EditChannelByTag` 支持按 tag 批量修改 model_mapping | ❌ 无 tag 概念 | ❌ **缺失** |
| **缺失模型检查** | 前端有 `missing-models-confirmation-dialog`，检查 model_mapping 中的 target 模型是否在 models 列表中 | ❌ 无 | ❌ **缺失** |

### NEW API Model Mapping 工作原理（`model_mapped.go`）
1. 从 gin context 获取 `model_mapping` JSON 字符串
2. 解析为 `map[string]string`
3. 链式查找：`currentModel → modelMap[currentModel]`，直到没有映射或检测到循环
4. 循环检测：用 `visitedModels` 集合避免无限循环
5. 最终将 `info.UpstreamModelName` 设为映射后的模型名
6. 调用 `request.SetModelName()` 替换请求体中的模型名

---

## 三、模型重定向（Model Redirect）对比

> **注意**：在 NEW API 中，"模型重定向"和"模型映射"是同一个功能的不同称呼。前端 UI 中叫 "Model Redirect"（模型重定向），后端字段叫 `model_mapping`，实现文件叫 `model_mapped.go`。

| 功能点 | NEW API | 安卓 | 状态 |
|--------|---------|------|------|
| **请求时模型名替换** | ✅ 支持，转发前替换 `request.Model` 为映射后的名称 | ❌ 无，转发时直接用原始 model 名 | ❌ **缺失** |
| **链式重定向** | ✅ 支持 A→B→C 链式 | ❌ 无 | ❌ **缺失** |
| **循环检测** | ✅ 用 visitedModels 集合 | ❌ 无 | ❌ **缺失** |
| **响应时模型名还原** | ✅ 部分适配器会还原 `OriginModelName` | ❌ 无 | ❌ **缺失** |
| **Compact 模式后缀** | ✅ 支持 ResponsesCompact 模式的模型名后缀处理 | ❌ 无 | ❌ **缺失** |

---

## 四、渠道分组（Group）对比

| 功能点 | NEW API | 安卓 | 状态 |
|--------|---------|------|------|
| **字段定义** | `Group string` — 逗号分隔多分组，默认 `"default"` | ❌ 无 group 字段 | ❌ **缺失** |
| **分组过滤** | `ApplyChannelGroupFilter()` — SQL LIKE 查询 `%,group,%` | ❌ 无 | ❌ **缺失** |
| **分组列表 API** | `GetGroups` / `GetUserGroups` — 返回所有分组+费率 | ❌ 无 | ❌ **缺失** |
| **用户可用分组** | 基于 user group 限制可访问的渠道分组 | ❌ 无用户体系 | ❌ **缺失** |
| **分组费率** | `ratio_setting.GetGroupRatioCopy()` — 每个分组可有不同费率倍率 | ❌ 无 | ❌ **缺失** |
| **auto 分组** | 特殊 `auto` 分组，自动匹配 | ❌ 无 | ❌ **缺失** |
| **按 group 搜索** | `SearchChannels(keyword, group, model)` 支持按 group 过滤 | ❌ 无 | ❌ **缺失** |
| **prefill_group** | `controller/prefill_group.go` — 预填充分组 | ❌ 无 | ❌ **缺失** |

---

## 五、其他缺失功能汇总

### 5.1 渠道类型（Channel Type）
| 功能点 | NEW API | 安卓 | 状态 |
|--------|---------|------|------|
| **类型枚举** | 61 种类型 (OpenAI=1, Anthropic=14, Gemini=24, DeepSeek=43...) | ❌ 无类型概念，所有渠道按 OpenAI 兼容 API 处理 | ❌ **缺失** |
| **默认 BaseURL** | `ChannelBaseURLs[type]` — 按类型自动填充 | ❌ 无，用户必须手动填写 baseUrl | ❌ **缺失** |
| **类型名称** | `ChannelTypeNames` map | ❌ 无 | ❌ **缺失** |
| **特殊 BaseURL** | `ChannelSpecialBases` — 某些模型有 Claude/OpenAI 不同 endpoint | ❌ 无 | ❌ **缺失** |

### 5.2 状态码映射（StatusCode Mapping）
| 功能点 | NEW API | 安卓 | 状态 |
|--------|---------|------|------|
| **字段** | `StatusCodeMapping *string` (JSON) | ❌ 无 | ❌ **缺失** |
| **用途** | 将上游错误码映射为本地错误码（如 429→503），影响重试逻辑 | ❌ 安卓用硬编码 `shouldRetry(statusCode)` | ❌ **缺失** |

### 5.3 标签（Tag）批量管理
| 功能点 | NEW API | 安卓 | 状态 |
|--------|---------|------|------|
| **字段** | `Tag *string` (带索引) | ❌ 无 | ❌ **缺失** |
| **按标签查询** | `GetChannelsByTag()` | ❌ 无 | ❌ **缺失** |
| **按标签启用/禁用** | `EnableChannelByTag()` / `DisableChannelByTag()` | ❌ 无 | ❌ **缺失** |
| **按标签批量编辑** | `EditChannelByTag()` — 支持批量修改 model_mapping/models/group/priority/weight/param_override/header_override | ❌ 无 | ❌ **缺失** |

### 5.4 请求参数/请求头覆盖
| 功能点 | NEW API | 安卓 | 状态 |
|--------|---------|------|------|
| **ParamOverride** | JSON text，覆盖请求参数 | ❌ 无 | ❌ **缺失** |
| **HeaderOverride** | JSON text，覆盖请求头 | ❌ 无 | ❌ **缺失** |

### 5.5 渠道设置（Setting）
| 功能点 | NEW API | 安卓 | 状态 |
|--------|---------|------|------|
| **代理 URL** | `ChannelSettings.Proxy` | ❌ 无 | ❌ **缺失** |
| **HTTP 传输配置** | `ChannelSettings.ValidateHTTPTransport()` | ❌ 无 | ❌ **缺失** |
| **高级自定义** | `AdvancedCustom` — 自定义路由/模型列表路径 | ❌ 无 | ❌ **缺失** |

### 5.6 余额查询
| 功能点 | NEW API | 安卓 | 状态 |
|--------|---------|------|------|
| **Balance** | `Balance float64` + `BalanceUpdatedTime` | ❌ 无 | ❌ **缺失** |
| **余额查询 API** | `controller/channel-billing.go` | ❌ 无 | ❌ **缺失** |

### 5.7 上游模型同步
| 功能点 | NEW API | 安卓 | 状态 |
|--------|---------|------|------|
| **上游模型更新** | `controller/channel_upstream_update.go` (35KB) — 自动检测上游新增/移除模型 | ❌ 安卓仅手动拉取，无自动同步 | ❌ **缺失** |
| **模型同步** | `controller/model_sync.go` (17KB) | ❌ 无 | ❌ **缺失** |

### 5.8 Ability 表（能力表）
| 功能点 | NEW API | 安卓 | 状态 |
|--------|---------|------|------|
| **Ability 模型** | 独立的 `abilities` 表，存储 `channel_id × model × group × enabled` | ❌ 安卓用内存缓存 `modelMap` 替代 | ⚠️ 实现不同 |
| **能力查询** | 通过 abilities 表做高效的 model→channel 路由查询 | ❌ 安卓在 `refreshCache()` 时遍历渠道构建内存 Map | ⚠️ 实现不同 |

---

## 六、总结统计

| 类别 | NEW API 有 | 安卓已有 | 安卓缺失 | 安卓独有 |
|------|-----------|---------|---------|---------|
| **Channel 基础字段** | 30 | 18 | 12 | 1 (disabledModels) |
| **模型映射** | 5 项 | 0 | 5 | 0 |
| **模型重定向** | 5 项 | 0 | 5 | 0 |
| **渠道分组** | 8 项 | 0 | 8 | 0 |
| **渠道类型** | 4 项 | 0 | 4 | 0 |
| **状态码映射** | 2 项 | 0 | 2 | 0 |
| **标签批量管理** | 4 项 | 0 | 4 | 0 |
| **参数/请求头覆盖** | 2 项 | 0 | 2 | 0 |
| **渠道设置(代理等)** | 3 项 | 0 | 3 | 0 |
| **余额查询** | 2 项 | 0 | 2 | 0 |
| **上游模型同步** | 2 项 | 0 | 2 | 0 |
| **合计** | ~69 | ~18 | ~49 | ~1 |

### 安卓已实现的核心能力
1. ✅ 基础渠道 CRUD (name/baseUrl/apiKeys/models/priority/weight/status)
2. ✅ 多 Key 轮换 (random / polling 模式)
3. ✅ AutoBan (Key 级别禁用 + 全部禁用时渠道级禁用)
4. ✅ 优先级分层 + 权重加权随机路由
5. ✅ 重试 Fallback (按 retry 递增选更低优先级层)
6. ✅ 健康检查 + 自动恢复
7. ✅ 模型拉取 (从 /v1/models 拉取后可勾选排除)
8. ✅ 路由日志记录
9. ✅ 配置导出/导入
10. ✅ 两种路由模式 (URL 维度 / 模型维度)

### 安卓缺失的高优先级功能（建议优先实现）
1. 🔴 **模型映射 (model_mapping)** — 最核心缺失，影响模型名转换
2. 🔴 **渠道类型 (type)** — 影响 API 格式适配和默认 BaseURL
3. 🟡 **渠道分组 (group)** — 影响多租户/分组路由
4. 🟡 **状态码映射 (status_code_mapping)** — 影响精确重试控制
5. 🟡 **标签 (tag) 批量管理** — 影响大批量渠道运维
6. 🟢 **请求参数/请求头覆盖** — 高级定制需求
7. 🟢 **渠道设置(代理)** — 网络代理需求
8. 🟢 **上游模型自动同步** — 运维自动化需求
