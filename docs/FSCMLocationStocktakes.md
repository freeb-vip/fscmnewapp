# 盘点任务 App

## 2026-10-10 本地实现

原生应用 key `location_stocktakes`；页面权限 `mobile-app:location-stocktakes` + `location_stocktakes`，认领 / 快速创建 / 提交另需 `location_stocktakes_submit`。不提供离线库存写入。

- 顶部常驻“快速盘点”；待认领 / 进行中 / 已完成列表，进行中默认本人，可看全部，其他人员只读。
- 认领后固定 SKU，展示所有关联库位，拣货区优先；零、负、未知数量展示原因且不可选。点击和扫描均重新核对 SKU / 库位，只有正库存可进入数量页。
- 快速盘点先创建并认领独立任务，再扫库位 → 点击或扫描 SKU → 输入实盘。尚无 SKU 可取消；已有 SKU 可退回待认领并清除草稿。普通返回保存服务端进度。
- 数量页突出库位 / SKU / 实际数量，完成前确认；差额绝对值大于 100 重新输入。完成页只显示服务端核验数量和时间，可保留本库位继续快速盘点。
- 相机复用 BarcodeScanner，PDA 复用 ScannerManager；数量输入和确认期间拦截扫描回调 / 硬件键盘字符，软件键盘手输可用。

DataStore 按后端、用户、工作空间、任务隔离。创建前持久化创建 UUID，提交前持久化原请求和任务版本。网络同步失败时仍保存本机草稿，普通返回可回到列表；本机数量与服务端版本冲突时明确选择保留本机或使用任务数量。重开任务加载服务端当前状态并重新获取库存快照；响应不明继续恢复同请求，确定拒绝且未产生记录时提供重新打开入口。确认凭证仅用于对应原请求；退回或修改作废。处理中 / 待核实不能取消、退回或另发写入。

## 文件

- `fscm/data/CountTasks.kt`、`LocationStocktakes.kt`：任务 / 回执 DTO、任务资源、目录 / 快照查询、版本提交、本地草稿。
- `fscm/ui/LocationStocktakeViewModel.kt`：任务与扫码流程、按任务恢复、服务端进度、旧请求恢复、所有权和状态守卫。
- `fscm/ui/LocationStocktakeScreen.kt`：原生列表、常驻入口、库位 / 数量 / 确认 / 完成界面；复用独立顶部栏、数量面板、库位行方便小屏测试。
- 四个 `res/values*/strings_stocktakes.xml`：中文、英文、泰文、缅文同步。
- `src/test/.../LocationStocktakeTest.kt`：数量 / 阈值、权限状态、快照、任务 / 创建请求草稿序列化。
- `src/testDemo/.../LocationStocktakeScreenTest.kt`：320 dp 长编码、缩短到 480 dp 可用高度后的滚动、常驻入口、软件输入与硬件扫码隔离、零 / 负 / 未知库存行。

## 验证

执行 `:app:testDemoDebugUnitTest` 全量 376 项通过（含 8 项盘点数量 / 恢复 / 小屏测试），`:app:spotlessKotlinCheck` 和 `:app:assembleDemoDebug` 通过。另针对盘点多轮运行 `:app:testDemoDebugUnitTest --tests '*LocationStocktake*'` 修复小屏与扫码测试中发现的问题。

本地截图在 `app/build/outputs/stocktake-tasks/`，不加入截图基准。开发包在 `app/build/outputs/apk/demo/debug/app-demo-debug.apk`。本机 JDK 25 与已有 JaCoCo 0.8.12 存在 major version 69 插桩告警，测试结果以 Gradle / JUnit 报告为准；未因此调整业务依赖。

没有连接的 Android 真机；相机 / PDA 实机、厂商软键盘、真实断网 / App 重启端到端恢复仍需设备验收。本轮未触及真实库存；历史文档的库存授权不适用于本轮。后端 PostgreSQL 隔离 fixture 已验证丢失写回响应后恢复原任务、回执核验、竞争与权限。

无设计约束偏离，未创建提交，未发布生产。原有 `app/prod/release/` 生成文件改动保留，不属于本任务。

## 2026-10-11 手动前缀选码

- 盘点的库位、SKU 输入使用入库管理同一套 Room 离线资料，输入 150 ms 后查询本机，不逐字请求网络或创建异步任务。前缀忽略大小写，保留编码原始大小写；`%`、`_`、反斜杠按字面匹配。
- 按当前用户、工作空间隔离，最多显示 20 个候选，超出时提示继续输入。编码完整换行显示，SKU 附商品名称／规格，库位附本地仓库名称；同码库位合并，不展示停用库位。
- 本地仓库 ID 与盘点服务的供应商仓库 ID 不是同一标识，不能直接比较。离线库位仅作编码建议，选中后仍由原盘点查询核验配置仓库、任务 SKU 和正库存，不能凭离线候选进入数量提交。原关联库位及库位 SKU 列表保持可用。
- 点击候选提交完整编码并收起键盘；完整编码查询、PDA、摄像头扫描保留原流程。切换任务、步骤或进行实时查询时清除候选，取消或忽略过期搜索结果。
- 页面提供“更新离线资料”，主动复用入库的目录快照下载接口；受现有目录读取权限约束，失败保留已有缓存并提示。没有缓存时仍可扫码或输入完整编码。离线候选不代表离线盘点提交能力。
- 同步中、英、泰、缅文案。新增 Room 测试覆盖 API 28／36 的前缀、特殊字符、范围隔离、排序、限量、去重与停用库位；Compose 测试覆盖 320dp 小屏部分输入选择完整编码。
- 本次验证：`:app:testDemoDebugUnitTest` 414 项全部通过（新增 6 项 API 28／36 用例），`:app:assembleDemoDebug` 成功。真实 PDA 操作未验收，未写入真实库存。
