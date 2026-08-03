# AndroidLLMRouter（安卓原生大模型 API 路由网关）

> 将 [New API](https://github.com/QuantumNous/new-api) 的核心功能，以 **安卓原生 APP**（Kotlin + Jetpack Compose）形式完整落地。
> 非 WebView 打包，所有配置以原生 UI 设置；内置 HTTP 服务器释放 OpenAI 兼容端点供其他 Agent / 应用直接调用。

## ✨ 核心功能

- **渠道管理**：一堆 URL + Key，一个 URL 对应多个 Key 和多个模型
- **URL 维度路由**：在本 URL 下的模型间路由，全不通再换下一个 URL
- **模型维度路由**：锁定一个模型，在多个 URL + Key 间路由
- **多 Key 轮换**：随机（random）/ 轮询（polling），只选启用 Key
- **健康检查**：定时探测各渠道/Key 可用性与响应延迟
- **自动故障切换**：错误命中即禁用（AutoBan），请求失败按 retry 递增切换更低优先级渠道（Fallback）
- **OpenAI 兼容端点**：`/v1/chat/completions`、`/v1/models`、`/v1/embeddings`，支持 Streaming/SSE
- **可选鉴权**：可设置是否使用 Key，自定义访问令牌
- **后台保活**：前台服务 + 下拉通知常驻 + 开机自启
- **全简体中文 UI**：Material 3 移动端优化

## 📱 使用方式

1. 安装 APK
2. 添加渠道（URL + Key + 模型 + 优先级）
3. 选择路由模式（URL 维度 / 模型维度）
4. 点击启动按钮
5. 获取 API 端点地址（如 `http://手机IP:8080/v1`）
6. 在其他应用/Agent 中配置此端点即可使用

## 🔧 技术栈

| 组件 | 技术 |
|---|---|
| 语言/UI | Kotlin + Jetpack Compose (Material 3) |
| 本地存储 | Room (SQLite) + DataStore |
| HTTP 服务器 | NanoHTTPD（内嵌，暴露 OpenAI 兼容端点） |
| 网络转发 | OkHttp（支持 Streaming/SSE） |
| 后台服务 | Foreground Service + WorkManager |
| CI/CD | GitHub Actions（自动编译 APK） |

## 🏗️ 架构

```
请求 → NanoHTTPD → 解析 model → RouterEngine 选渠道 → 选 Key
  → OkHttp 转发到上游 → 成功透传 / 失败 AutoBan + Fallback
```

### 路由引擎核心逻辑（移植自 NEW API）

1. **渠道 = URL**：BaseURL + Key(多个) + Models + Priority + Status + AutoBan
2. **优先级分层 + 层内加权随机**：retry=0 用最高优先级，失败递增降级
3. **多 Key 轮换**：random / polling，per-channel 锁保证线程安全
4. **AutoBan**：命中错误即禁用渠道/Key，靠测试恢复
5. **Fallback**：retry 循环，失败用递增 retry 选更低优先级渠道

## 📦 编译

本项目通过 GitHub Actions 自动编译 APK，无需本地配置 Android 开发环境。

```bash
# 本地编译（需要 JDK 17 + Android SDK + Gradle 8.5）
gradle assembleDebug
```

## 📄 License

MIT
