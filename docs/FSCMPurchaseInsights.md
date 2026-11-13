# App 采购洞察

## 入口与权限

原生应用键为 `purchase_insights`，入口路径为 `/pages/purchase-insights/index`。仅在线模式且具有 `purchase-insights` 业务权限时展示；不增加独立移动权限。配套后端在应用注册表、默认目录和旧配置合并中补充入口，保留用户隐藏、标题和排序设置。无数据库迁移。

## 列表

- 顶部产品编码 400 毫秒防抖；键盘搜索立即提交，清空立即查询。使用现有精确产品编码接口语义。
- 右上角筛选面板包含授权仓库多选、SKU 匹配、批量 SKU、数据状态、有销量及周期、有本地在途和异常。关闭面板丢弃草稿，应用后统一查询。
- 首次默认全部可访问仓库；显式选择后保持选择并剔除失效仓库，清空后不请求列表。
- 左上角选择 1–6 个指标，默认 30 天销量、可用库存和总在途。按选择顺序排列，普通字号每排三项，大字号每排两项。指标切换不请求后端。
- 账号、后端地址、工作空间共同隔离本机 DataStore 偏好。SKU 编码独立成行，数字保留千分位，未知值为「—」。
- 每页 50 条，上滑加载；排序在服务端执行。后续页与详情固定 snapshot_id，过期后重载第一页，不混合快照。
- 只有后端返回 totals 才显示固定总计。沿用产品/SKU 条件门禁及全部筛选结果范围，不能在客户端累加当前页。预测与覆盖天数显示「不汇总」。
- 初始数据准备状态自动轮询，离开页面停止请求；旧数据时间、同步失败和来源缺失分别展示。

## SKU 详情

显示完整编码、复制、产品、状态、列表仓库范围、所有指标、可折叠在途订单和异常说明。返回保留列表位置和筛选。

曲线独立选择列表已选仓库，默认按仓库名排序寻找首个有历史数据的仓库。历史使用 snapshot/day，展示近 7、30、90 天，系列为近 7、15、30 天滚动累计销量，默认 30 天系列。明确区分滚动累计与当日实际销量。

多仓响应不包含统一时区：先按 UTC 加宽两天查询，再根据所选仓库历史 coverage 的业务时区裁剪，单仓快照时区仅作已有明确时区的后备。缺失日期和同日重复身份记录不连线、不补零，真实零值保留。单位发生变化时不绘制混合曲线。多来源编码必须显式选择；未匹配本地身份的多仓 SKU 提示先单仓查询，不猜测同名身份。

点击/拖动曲线显示准确日期和数值，同时提供逐日数据列表。当前列表指标与历史分别标明范围和采集时间。

## 代码与验证

| 文件 | 职责 |
| --- | --- |
| `PurchaseInsightData.kt` | DTO、指标目录、接口 Repository、历史分页、偏好存储 |
| `PurchaseInsightViewModel.kt` | 搜索、防抖、快照分页、仓库/会话切换、历史编排 |
| `PurchaseInsightScreen.kt` | 固定搜索区、总计、SKU 列表及选择面板 |
| `PurchaseInsightDetail.kt` | 指标详情、曲线、逐日数据及在途订单 |
| `FscmApp.kt`、`FscmRepositories.kt`、`FscmAppIcon.kt` | 路由、目录权限、搜索与图标 |
| `PurchaseInsightTest.kt`、`PurchaseInsightScreenTest.kt` | 数据/异步回归与手机尺寸交互、截图 |

验证目标：`:app:testDemoDebugUnitTest`、`:app:assembleDemoDebug`、`:app:spotlessKotlinCheck`。截图仅写入 `app/build/outputs/purchase-insights/`，不提交截图基准。

先升级服务端应用目录，再发布 App。旧版 App 会过滤未实现的入口；撤回入口和 App 改动即可回退，不修改采购、库存和历史数据。首版不含采购创建、导出、货值统计及离线同步。

## 验收记录（2026-10-25）

- 首次运行完整 `:app:testDemoDebugUnitTest`：441 项中 439 项通过，发现新增资源缺少其他语言对应键、历史测试样例跨时区日期边界两项问题。已补齐四种语言的入口资源，并修正测试日期生成方式。
- 最终执行 `:app:testDemoDebugUnitTest --tests '*PurchaseInsight*' --tests '*LocalizationTest' :app:assembleDemoDebug -Pkotlin.incremental=false -Pkotlin.compiler.execution.strategy=in-process --no-build-cache --max-workers=1 --continue` 成功：采购洞察逻辑 10 项、Compose 界面 5 项、资源一致性 4 项，共 19 项全部通过。包括单仓详情不发送多仓参数、快照过期重载、过期请求隔离、空间切换及仓库授权范围变更清理。最终安装包为 `app/build/outputs/apk/demo/debug/app-demo-debug.apk`。
- Robolectric 原生绘制覆盖 320、360、393、430dp；320dp 使用 1.5 倍字号和六项指标。验证筛选应用、指标选择与恢复默认、详情返回、曲线点击取值，人工查看了长 SKU、空值、完整数字、两排指标及缺失日期断线截图。截图使用测试数据，不是真实业务数据。
- 本次新增 Kotlin 文件通过定向 Spotless 检查。全量 `:app:spotlessKotlinCheck` 被已有的 `FscmDatabase.kt`、`LocalSkuCatalog.kt`、`AutoPurchaseReceivingTest.kt`、`InboundCollectionMigrationTest.kt`、`OfflineCodeSearchTest.kt`、`ReferenceCatalogRefreshTest.kt`、`SkuCatalogResolutionTest.kt` 格式问题阻挡，未修改这些无关文件。
- 后端入口权限、错误权限配置、用户布局保留、自动补齐幂等测试在现有 Docker 开发环境通过。服务健康和未登录接口拦截检查通过。
- 当前没有连接 Android 设备，网页会话停留在登录页。因此真实账号数据与网页的明细/跨页总计核对、真实仓库权限撤销、四个主标签的真机回归尚未执行；发布前应完成这些验收。

构建日志保存在 `.artifacts/purchase-insight-final.log`，完整首轮测试日志为 `.artifacts/purchase-insight-validation.log`，全量格式结果为 `.artifacts/purchase-insight-spotless.log`。这些日志及 `app/build` 截图不纳入源码提交。


## 产品排名与日订单销量（2026-11-13）

列表默认产品折叠，支持产品/SKU、全部/TOP30/TOP50。排序中选择今天、昨天、3/7/30/60天销量；展开使用相同分析快照，并按所选销量周期降序独立加载SKU。旧15/90天展示偏好迁移为有效指标，历史曲线周期保留。日销量来自销售订单，库存和滚动窗口仍来自采购快照；未知值保持空缺。

API增加 analysis_snapshot_id、group_key、sales_period、dimension、top_limit，分页保持订单及库存版本。新增文案维护四语资源。设备与真实账号验收边界见本次交付说明。


## 月份系数与预测（2026-11-13）

新增预测未来30天销量（demand）默认指标、超过365天覆盖标识、部分预测合计和固定快照预测依据。单仓具备purchase-insights-config权限时可编辑仓库默认或产品覆盖的指定年份12个月系数，复制上一年只填充草稿；空白继承，默认1。系数表示月总量环比，例如1月0.8为12月总量的80%；服务端校正月份天数并积分未来曲线。可靠SKU/产品往年历史优先于人工设置。产品模型组件可逐项查看，避免同时渲染全部成员。

PurchaseInsightData.kt补充DTO/API；PurchaseInsightViewModel.kt处理权限、请求代次、空间/仓库切换与409版本冲突；PurchaseInsightScreen.kt和InsightDemandSheets.kt提供原生指标、配置和详情；四语strings_purchase_insights.xml提供新增文案。原有无关修改保留。

验证：:app:spotlessKotlinCheck、:app:assembleDemoDebug、:app:testDemoDebugUnitTest --tests '*PurchaseInsight*'成功，11项逻辑和9项界面测试无失败。新增四语320dp/1.5倍字体测试覆盖预测依据、12月字段及保存入口的可达性；截图位于build/outputs/purchase-insights/demand-*，未纳入源码。JDK25的既有JaCoCo插桩警告不影响测试结果，不据此声明覆盖率。本轮未连接真机或核对登录态业务数据。

先发布含372/373迁移的兼容服务端，再发布Web和Android。本轮按用户要求纳入本地提交，提交号见Git历史；尚未发布生产，无设计约束偏离。
