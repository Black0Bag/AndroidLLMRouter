# Android LLM Router（安卓原生大模型 API 路由网关）开发流程

> 项目目标：将 NEW API 的核心功能，以 **安卓原生 APP**（Kotlin + Jetpack Compose）形式完整落地。
> 非 WebView 打包，所有配置以原生 UI 设置；内置 HTTP 服务器释放 OpenAI 兼容端点供其他 Agent / 应用直接调用；
> 支持 URL 维度路由与模型维度路由、多 Key 轮换、健康检查与自动故障切换；
> 启动后界面显示实时路由状态，前台服务 + 下拉通知常驻，尽可能后台保活以提供稳定路由服务。

---

## ✅ 全部步骤已完成

- [x] 1. 深入分析 NEW API 运行逻辑，产出移植设计文档
- [x] 2. 建立项目基础和设计文档（Gradle 工程骨架、Kotlin、Compose、包结构）
- [x] 3. 实现本地数据模型与存储层（Channel/Key/Model，Room 实体，Key 加密存储）
- [x] 4. 实现核心路由引擎（渠道匹配、URL 维度路由、模型维度路由、多Key 轮换）
- [x] 5. 实现转发层（OkHttp 上游请求、Streaming/SSE 透传、重试/Fallback/AutoBan）
- [x] 6. 实现健康检查与监控（定时探测、自动禁用与恢复、阈值判断）
- [x] 7. 实现内嵌 HTTP 服务器（暴露 OpenAI 兼容端点，sk-xxx 鉴权 + 开关）
- [x] 8. 实现 App 主界面与移动端 UI（Material 3：首页路由状态、渠道列表）
- [x] 9. 实现配置页原生 UI（URL/多Key/模型/优先级/路由模式/鉴权开关等原生表单）
- [x] 10. 实现实时路由状态展示（渠道健康度、路由链路、响应延迟，UI 实时刷新）
- [x] 11. 实现启动控制与后台服务（前台服务保活、下拉通知常驻、开机自启）
- [x] 12. 本地联调与基础测试（单元测试、HttpServer smoke test）
- [x] 13. 编写 README、LICENSE、.gitignore，完善项目文档
- [x] 14. GitHub 新建仓库并 push 源码
- [x] 15. 编写 GitHub Actions 自动化编译脚本（打包 APK）
- [x] 16. 触发 CI，跟踪调试直至编译成功产出 APK
- [x] 17. 推送到 GitHub Release 发布（版本 tag、release note、Assets）

## 📦 发布结果

- **仓库**：https://github.com/Black0Bag/AndroidLLMRouter
- **Release**：https://github.com/Black0Bag/AndroidLLMRouter/releases/tag/v0.1.0
- **APK 下载**：
  - app-debug.apk (16.6 MB)：https://github.com/Black0Bag/AndroidLLMRouter/releases/download/v0.1.0/app-debug.apk
  - app-release-unsigned.apk (11.9 MB)：https://github.com/Black0Bag/AndroidLLMRouter/releases/download/v0.1.0/app-release-unsigned.apk
- **CI 编译**：第三轮成功（3 次迭代：修复 combine 类型推断 + inline lambda break/continue + NanoHTTPD 枚举）
