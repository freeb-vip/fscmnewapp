# 实时数据

通用与运营首页共用今日数据摘要，点击打开原生实时数据应用；详情为今日大卡及店铺、商品排行榜 Tab。首页搜索通过标题栏图标进入，支持原应用搜索与扫码。仓库首页没有新增指标卡。

## 权限和接口

入口标识 `realtime_data`，路径 `/pages/realtime-data/index`，同时要求 `mobile-app:realtime-data`、`realtime-data`。离线会话隐藏入口与首页卡片。后端需先执行 FSCM 迁移 212，并为需要访问的非管理员角色授予新 App 权限。

Repository 使用统一 FscmApiClient 请求 `mobile/app/realtime-data/connections`、`overview`、`work-summary`；自动携带既有移动端标识、认证及工作空间请求头，遵循 code/data/msg 解包。原运营首页聚合请求传 `include_sales=false`，不重复获取销售。

## 页面与数据行为

- 浅蓝背景、白卡、蓝色主数字；16dp 圆角和内边距、12dp 区块间距；小于 360dp 或字体比例至少 1.3 时单列。
- 首页销售两列、作业可选两列，展示涨跌和日期／时区；详情补充昨日同期与昨日全天。金额两位小数，计数整数，人效两位小数，未知显示“—”。
- 店铺按服务端销售额顺序显示；商品使用现有 TOP 10。两个 Tab 分别记忆滚动位置，账户切换后重置。图片失败显示占位图，不自动滚榜。
- 销售固定北京时间；作业使用接口时区，取已包装数及总体人效，与昨日固定全天日报比较。作业任一有效非零时展示；完整作业快照遇到部分缺失，保留整组原值与原时间。
- MainShell 持有唯一 RealtimeDataViewModel，在首页或实时详情可见且生命周期 STARTED 时刷新；正常 180 秒，首次待采集模块 5 秒，后台停止。手动刷新合并在途请求。
- 账户选择兼容按用户＋空间保存的 sales_source_selection；实时数值仅保留在内存。切换用户、空间、账户或跨各自时区日期时清空对应结果；请求版本隔离迟到响应。
- 四语资源位于各语言目录的 realtime_data.xml。

## 验证

执行 `:app:testDemoDebugUnitTest`、`:app:spotlessKotlinCheck`、`assembleDemoDebug` 均通过；332 项单元测试无失败，其中 `RealtimeDataTest` 新增 11 项覆盖账户、权限、精度、日期、取消和部分失败。

`RealtimeDataScreenTest` 使用明确隔离的演示数据进行 Compose 仪器测试，4 项测试通过，覆盖首页跳转与隐藏、账户选择、双榜、大字体和中文文案；另外 3 项交互测试在约 329dp 窄屏通过。测试工程引入已有 `core:testing` 模块以提供配置中的 `NiaTestRunner`。截图保存到测试设备应用外部 files 目录，不作为真实业务数据或截图基准提交。

正式数值对账需在存在当日来源快照、具有双权限及完整账户授权的登录环境执行。当前开发库无本日销售快照，不能将模拟器演示数据当作实数验收。

首页、应用、消息、我的四个底部入口及返回路径已检查代码和构建；真实登录后的四入口连贯操作、搜索结果跳转及摄像头扫码仍需在有业务账户的设备上验收。

## 主要改动位置

- `fscm/data/RealtimeData.kt`：移动接口、DTO、账户偏好和权限判断；`WorkbenchStyle.kt` 扩展销售模型并关闭旧聚合销售请求。
- `fscm/ui/RealtimeDataViewModel.kt`：共享状态、定时刷新、跨日失效、取消和迟到响应隔离。
- `fscm/ui/RealtimeDataScreen.kt`：首页摘要、详情指标、账户面板及双榜；`FscmApp.kt` 和 `WorkbenchStyleScreens.kt` 负责首页、搜索、导航接入。
- `FscmRepositories.kt`、`FscmAppIcon.kt`：应用目录、搜索别名和专用图标；各语言目录 `realtime_data.xml` 提供四语文案。
- 单元及仪器测试 `RealtimeDataTest`、`RealtimeDataScreenTest`：数据语义及原生界面验证。
- `FscmViewModels.kt`：移除原运营销售账户请求链，保留日报、待办加载；`app/build.gradle.kts` 补齐仪器测试运行器依赖。

## 执行记录（2026-10-09）

在 Android 仓库使用已配置 Android Studio JBR 执行：

```powershell
.\gradlew.bat :app:spotlessKotlinCheck :app:testDemoDebugUnitTest assembleDemoDebug --console=plain
.\gradlew.bat :app:connectedDemoDebugAndroidTest '-Pandroid.testInstrumentationRunnerArguments.class=com.google.samples.apps.nowinandroid.fscm.ui.RealtimeDataScreenTest' --console=plain
```

两项构建命令均成功，仪器测试在 API 28 模拟器执行。另设置 `adb shell wm size 864x1920`，通过 `am instrument` 重跑 3 项交互测试后恢复尺寸。JBR 25 与既有 JaCoCo 组合有 JDK 类插桩兼容警告；测试结果为 332/332 通过，不将该日志视为覆盖率完整的证明。

产物为 `app/build/outputs/apk/demo/debug/app-demo-debug.apk`，沿用本机现有 demo 后端地址配置。截图为测试页面内容，不包含外层业务导航栏。真实设备验收前，目标后端须已提供新接口与权限。

## 作业汇总与同期比较（2026-11-05）

今日作业改读后端 count 与 efficiency，统一为订单统计作业汇总（含补货），不用 packed 替代作业数。新增 comparison 按双方相同整点累计指标计算涨跌，显示截止小时；缺失时显示未知，不回退昨日全天。补货数据缺失时明确显示订单人效。仓库首页同样使用实时 metric 及 comparison，人效显示两位小数，缓存键改为 warehouse_summary_v2。跨日、范围/口径变化时清空旧比较，同小时来源失败时完整保留配对并标注延迟。旧后端响应可解析，但没有 comparison 时不显示涨跌。


本次验证（2026-11-05）：`testDemoDebugUnitTest` 455 项全部通过，`spotlessKotlinCheck` 和 `assembleDemoDebug` 通过。检查 320dp 大字体仓库首页截图，实时值、昨日整点同期值和比较截止时间显示正常。未执行真机 instrumentation 测试，未用已登录业务账户对账或发布生产。后端和 Web 验证限制见 FSCM 仓库 `docs/features/realtime-data.md` 同日记录。
