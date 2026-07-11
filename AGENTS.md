# FSCM Android App Agent Instructions

## 项目定位

本项目是 FSCM 原生 Android App，基于 Now in Android 工程骨架改造，使用 Kotlin、Jetpack Compose、Material 3、Hilt、Navigation、Retrofit、OkHttp、DataStore。

`:app` 是 FSCM 主应用；`:app-nia-catalog` 只是 Now in Android 设计系统组件展示 App，不是业务 App，不作为运行或发布目标。

## 开发原则

- 优先保持 FSCM 移动工作台定位：登录、首页、应用、消息、我的、利润看板、收货扫码等移动高频业务。
- 不恢复 NIA 的资讯、话题、收藏、兴趣流业务。
- 保持单 Activity + Compose + ViewModel + UiState + Repository 的结构。
- 网络、会话、权限、业务模型应放在 FSCM 数据层，不把接口调用散落到 Composable 中。
- 任何改动必须尊重已有用户改动，禁止使用 `git reset --hard`、`git checkout --` 等破坏性命令回退文件。

## UI 与设计系统

- 视觉必须保持 FSCM 风格一致：浅蓝工作台背景、白色业务卡片、紧凑列表、状态胶囊、应用宫格、扫码收货优先。
- 主色使用 FSCM token：`#1677ff`，深蓝 `#0958d9`，背景 `#f5f8ff` / `#eef6ff`，并保留成功、警告、危险状态色。
- 优先使用 Material 3 Compose 组件；可参考 `app-nia-catalog` 的展示效果，但实际复用来源应是 `core:designsystem`。
- 可复用 `core:designsystem` 中的按钮、Chip、Navigation、Tab、Icon 等基础组件，但需要包成 FSCM 风格组件或通过 FSCM theme 调整。
- FSCM 专属组件应保持本地业务语义，例如工作台头部、业务卡片、应用入口、指标卡、收货扫码卡、状态胶囊。
- 不要把 Web 后台样式、H5 页面或小程序页面直接嵌入 WebView；应使用原生 Compose 实现。
- UI 文案必须是 UTF-8 中文，提交前检查是否出现乱码。
- 移动端布局要适配小屏，避免文字溢出、按钮拥挤、卡片嵌套卡片、过度装饰背景。

## 代码风格

- Kotlin 遵循项目现有 ktlint/Spotless 风格。
- Composable 保持小而清晰，复杂页面拆成稳定的业务组件。
- ViewModel 暴露不可变 UiState，不在 UI 层直接处理复杂业务规则。
- Repository 负责接口编排、DTO/domain 转换和错误映射。
- 网络 DTO 使用 `@Serializable`，统一处理 FSCM 响应结构 `code/data/msg`。
- 请求必须自动带 `X-FSCM-Client: mobile-app`，登录态请求带 `Authorization: Bearer ...`，有 namespace 时带 `X-Namespace-ID`。
- `BACKEND_URL` 由 flavor 配置生成：demo 优先读 `BACKEND_URL_DEMO`，prod 优先读 `BACKEND_URL_PROD`，地址必须规范化为 `/api/` 结尾。
- 不新增无必要的大型抽象；优先沿用当前 `fscm/data`、`fscm/ui`、ViewModel、Repository 的模式。
- 不把密钥、真实 token、账号密码提交到仓库。

## 构建目标

- Android Studio 中开发 FSCM App 时选择 `:app` 模块的变体。
- 常用开发包：`demoDebug`，对应 `assembleDemoDebug`。
- 生产包：`prodRelease`，对应 `assembleProdRelease`。
- `app-nia-catalog` 仅用于查看设计系统组件效果，不用于 FSCM 发布。

## 测试与验证

每次改动至少执行与影响范围匹配的验证：

- 普通 Kotlin/数据层改动：运行 `.\gradlew.bat :app:testDemoDebugUnitTest`。
- UI 或 Compose 改动：运行 `.\gradlew.bat :app:spotlessKotlinCheck` 和 `.\gradlew.bat assembleDemoDebug`。
- 网络配置或 flavor 改动：同时验证 demo/prod 的 `BACKEND_URL` 生成逻辑。
- 设计系统或导航改动：检查首页、应用、消息、我的四个底部 Tab。
- 收货扫码改动：验证列表、详情、扫码预览、确认、异常、取消流程。
- 不提交本机生成的截图基准；视觉回归基准由 CI 或明确任务生成。

## Agent 工作流程

- 修改前先读相关代码，不凭印象改。
- 先搜索现有组件、Repository、ViewModel、测试，再决定新增还是复用。
- UI 改动要同时考虑产品语义、视觉一致性、移动端可用性。
- API 改动要确认路径、请求头、响应包装、错误处理和登录态。
- 完成后在回复中说明改了什么、验证了什么、未验证什么。
- 如果发现中文乱码、旧 NIA 业务残留、错误的 Build Variant 使用方式，应主动指出并纳入修复计划。

## 默认假设

- 首版目标是 FSCM 原生移动工作台，不覆盖 Web 后台全量管理能力。
- 登录首版采用账号密码 + refresh token。
- 微信、手机号一键登录、Apple 登录、复杂扫码硬件适配属于后续独立任务。
- 后端地址示例：demo 可用 `BACKEND_URL_DEMO=http://127.0.0.1:3000/api/`，Android 模拟器访问宿主机时通常需改为 `http://10.0.2.2:3000/api/`。
