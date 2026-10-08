# 入库基础资料离线识别

库位和 SKU 以中心服务器为准，本地有效期均为两小时。有效缓存直接读取；在线缺失或过期时向中心精确查询，SKU 合并成批量查询，等待上限五秒。显式离线时读取已有缓存；联网请求遇到网络、服务或登录续期暂时不可用时可以回退已有缓存。中心明确返回库位不存在、停用或仓库不匹配时清除对应旧记录；明确缺失的 SKU 同样清除，不能继续使用旧名称。没有库位缓存且无法联网核验时提示核验失败，不提示中心已确认不存在。权限失败、登录失效、歧义或响应异常不会作为离线命中处理。

箱码仍由本地二维码、映射、当前单据快照及同仓跨单目录识别。未命中或归属有歧义时，提示同步后重扫，不创建未知库位批次或未知跨单任务。已有历史 locationId=0 草稿在同步时补做校验。

本地保存仍是待同步状态。跨单必须明确确认，确认后持久化任务交由同步处理。服务端仍校验库位有效性、单据归属、重复扫描和权限，并沿用既有幂等及冲突处理。

## 数据与更新

- Room 18→19 新增 `local_sku_catalog` 与 `reference_cache_meta`；19→20 新增 SKU 单条同步时间 `syncedAt`，旧值默认为 0，在线使用前重新核验。保留现有草稿、待上传记录及缓存。
- 库位与 SKU 每两小时后台检查更新；登录、切换账号/工作空间、回到前台、网络恢复及进入入库会话触发异步过期检查。周期任务受 Android 系统调度影响，不保证精确到点执行。沿用原任务名称并使用 UPDATE，将旧安装的 24 小时任务更新为两小时。
- APP 入库页手动同步同时强制更新基础资料和当前单据；“更新离线资料”入口也强制更新。任务按账号、工作空间去重，刷新互斥，不取消正在运行的下载。业务同步与资料同步分别记录结果，任一失败都会反馈。
- SKU 编码规范化后按账号、工作空间建立主键；定向查询最多每批 400 条，不再从旧盘点索引或箱码名称覆盖中心身份资料。单条查询不延长全量快照有效期。
- 箱码映射与单据归属属于业务资料，保留独立更新频率。全局箱码快照的成功时间与局部单据更新分开记录。
- 下载结束并通过分页检查才事务替换数据。中断、重复/倒退游标、异常空页及缺失记录保留旧缓存；成功全量替换清除删除项。
- 缓存完整性、数量、最后成功同步时间持久化并显示在入库页。基础资料更新错误独立显示具体原因；失败保留旧缓存，已有缓存仍可按上述离线规则使用。
- 商品名称与规格用于展示；箱内数量始终来自原始箱唛或箱码映射，商品资料不提供替代数量。

## 接口与兼容

所有请求遵循中心 `code/data/msg` 响应包装，附带当前登录及工作空间请求头，并在落库前复核账号/空间未切换。

- `GET /api/mobile/app/catalog/skus/snapshot?after_id=0&limit=500`：全量 SKU 身份快照。每项包含 `id,sku_code,sku_name,spec,product_id,product_code,product_name`，不包含价格、成本。原盘点快照接口保留。
- `POST /api/mobile/app/catalog/skus/lookup`：请求 `{"codes":["SKU-A"]}`，返回 `items` 与 `missing_codes`。校验响应覆盖所有请求编码、无重复或冲突后，事务更新并清理缺失项。
- `GET /api/mobile/app/catalog/locations/snapshot?after_id=0&limit=500`：当前绑定仓库有效库位，按 ID 游标分页；替代原带库存聚合的库位选项查询。
- `GET /api/mobile/app/catalog/locations/resolve?warehouse_code=WH&code=A-01`：中心精确核验。不存在、停用、仓库不匹配业务码分别为 42244、42245、42246。库位查询不触发 BigSeller/APIMix 同步。

两个全量快照均包含 `items,next_after_id,has_more`。中心权限与仓库配置边界保持不变；BigSeller 新库位应先进入中心库位表，APP 再从中心读取。

部署顺序：先发布中心新增接口，再发布 App。旧中心返回 404 时显示中心接口需升级；已有缓存可在有效期内或显式离线时使用。中心无需数据库迁移，App 自动执行 Room 19→20 迁移。

## 验证

Android：运行 `:app:testDemoDebugUnitTest`、`:app:spotlessKotlinCheck`、`:app:assembleDemoDebug`。测试包括未完成/过期缓存命中、仓库和用户隔离、删除项清理、分页异常、取消、数据库迁移及既有入库冲突处理。

后端：`go test ./services ./handlers ./routes/modules -run '^TestMobileCatalog' -count=1`。设置 `FSCM_TEST_POSTGRES_DSN` 后，服务测试在隔离 PostgreSQL schema 内验证分页、namespace 隔离、软删除和非法参数；路由测试验证入库权限无需盘点权限、无权限/未登录拒绝。

设备验收需准备真实业务数据，分别验证：两小时内缓存命中不联网；中心新增库位在本地缺失时可直接识别；缓存过期后采用中心更新；中心删除/停用后拒绝旧身份；断网但仍在线登录时回退缓存；APP 手动同步更新资料；后台更新不清除待上传记录。记录有效缓存扫描 P50/P95（目标 P95 ≤ 200ms），中心查询单独统计。本地构建或模拟测试不能替代手持设备性能验收。

## 两小时中心资料调整：文件与验证（2026-10-08）

本次 Android 文件（相对仓库根目录，业务代码位于 `app/src/main/kotlin/com/google/samples/apps/nowinandroid/`）：

- `fscm/data/WarehouseLocationCache.kt`：轻量中心库位快照、两小时有效期、缺失/过期精确核验、暂时故障回退、中心拒绝时清理旧缓存。
- `fscm/data/LocalSkuCatalog.kt`：单条同步时间、中心批量查询、明确缺失项清理、共享两小时有效期及失败分类。
- `fscm/data/FscmDatabase.kt`：Room 19→20 非破坏性升级。
- `fscm/data/LocalInbound.kt`：APP 手动同步并行更新基础资料；读取持久化资料状态；商品展示使用中心 SKU；分别反馈业务和资料失败。
- `fscm/data/LocalInboundSync.kt`：两小时周期及旧周期更新。
- `FscmApplication.kt`：前台恢复和网络恢复触发异步过期检查。
- `fscm/ui/InboundManagementViewModels.kt`：同步后更新识别与显示数据，防止覆盖进行中的扫码；区分中心不存在和本地缺失。
- `fscm/ui/InboundManagementScreens.kt`：展示库位/SKU 条数与上次全量成功同步时间。
- `app/src/main/res/{values,values-b+zh+Hans,values-th,values-my}/strings_inbound.xml`：上述提示的四种语言文本。
- `app/src/test/kotlin/.../fscm/data/ReferenceCatalogRefreshTest.kt`：两小时边界、故障回退范围、快照完整性、定向删除隔离、19→20 数据保留测试。
- 同目录 `WarehouseLocationCacheTest.kt`：新的游标快照解码契约；`AutoPurchaseReceivingTest.kt`、`InboundCollectionMigrationTest.kt`：旧版本测试样本及迁移链适配。
- `app/src/test/kotlin/.../fscm/ui/InboundErrorTextTest.kt`：中心拒绝与本地缓存缺失提示区分。
- `docs/FSCMOfflineReferenceData.md`、`docs/FSCMInboundSyncPerformance.md`：同步行为、兼容性、验收与验证记录。

中心 `F:/code/fscm` 文件：

- `backend/dao/mobile_reference_catalog.go`：仅身份字段的库位读取及 SKU 批量查询，无库存聚合、上游同步。
- `backend/services/mobile_catalog_service.go`、`mobile_reference_catalog.go`：工作空间仓库边界、库位游标分页/精确核验、SKU 规范化及歧义检查。
- `backend/handlers/mobile_reference_catalog.go`：请求校验、统一响应与稳定错误、完整 Swagger 路径/数据结构。
- `backend/routes/modules/mobile.go`：三个新接口及既有权限规则。
- `backend/services/mobile_catalog_service_test.go`、`mobile_reference_catalog_test.go`：隔离 PostgreSQL 用例。
- `backend/handlers/mobile_catalog_handler_test.go`、`backend/routes/modules/mobile_catalog_test.go`：非法输入、错误码、认证与权限用例。
- `docs/features/mobile-offline-catalog.md`：中心契约及客户端缓存约定。

验证命令与限制：

- `.\gradlew.bat :app:testDemoDebugUnitTest :app:assembleDemoDebug --console=plain --continue`：316 项单元测试全部通过，无失败/跳过；生成 `app/build/outputs/apk/demo/debug/app-demo-debug.apk`。
- `.\gradlew.bat :app:spotlessKotlinCheck`：四个既有文件仍有格式问题：`FscmModels.kt`、`BoxLabelManagementScreen.kt`、`InboundChangeLocationRoomTest.kt`、`InboundPreviewAssignmentTest.kt`。本次修改文件已定向格式化且未出现在违规列表中，未批量修改无关内容。
- `go test ./services ./handlers ./routes/modules -run '^TestMobileCatalog' -count=1`：三个包通过；配置 `FSCM_TEST_POSTGRES_DSN`，在独立 schema 验证中心新增/删除库位、停用、跨仓/跨空间、SKU 修改/删除/歧义。
- `go build ./...` 通过；`sh scripts/generate_swagger.sh` 成功生成并补充 AI 语义元数据，自动补全路由文件无遗留改动。
- `docker-compose -f docker-compose.dev.yml up -d`：默认 fscm 服务运行，后端挂载当前 `F:/code/fscm/backend`，热重载正常。前端与健康检查 HTTP 200，新增接口直接及经前端代理均返回预期的未登录 401。
- 两个仓库 `git diff --check` 通过。
- 本机 JDK 25 与已有 JaCoCo 代理仍产生内部类插桩版本警告，测试断言通过，不据此声称完整覆盖率。
- `adb devices` 没有设备：未完成手持设备扫码、实际后台调度/弱网和 P50/P95 验收，也未连接生产 BigSeller 数据验收。未发布生产、未创建提交，无已确认方案偏离。

## 历史实施验证记录（2026-09-13，调整前）

- `:app:testDemoDebugUnitTest`：304 项测试，0 失败，0 跳过。
- `:app:assembleDemoDebug`：成功，产物 `app/build/outputs/apk/demo/debug/app-demo-debug.apk`。
- 最终联合命令：`gradlew.bat :app:testDemoDebugUnitTest :app:assembleDemoDebug :app:spotlessKotlinCheck --continue --no-build-cache --console=plain`。联合命令非零退出仅来自全仓格式检查，测试和 APK 构建任务分别成功。
- `:app:spotlessKotlinCheck`：仍有既有文件 `FscmModels.kt`、`BoxLabelManagementScreen.kt`、`FscmApp.kt`、`InboundChangeLocationRoomTest.kt`、`InboundPreviewAssignmentTest.kt` 的格式/换行问题。本次离线识别新增及改动文件未出现在违规列表中，未对上述无关内容做批量格式化。
- 后端 `go test ./services ./handlers ./routes/modules -run '^TestMobileCatalog' -count=1`：三个包通过，PostgreSQL 测试配置了隔离 schema，未跳过。
- 现有 Docker Compose 开发环境已验证：新增接口带登录身份实际请求 HTTP 200，前端 HTTP 200；新增路由已被开发服务加载。
- `git diff --check`：通过。没有创建 Git 提交或发布生产版本。
- Android 测试进程使用本机 JDK 25，既有 JaCoCo 代理输出部分 JDK 类的版本不支持警告；应用测试断言全部通过，未据此声称获得完整覆盖率。
- 调试环境没有连接 Android 设备，因此没有执行手持设备扫码抓包、屏幕验收或 P50/P95 实测，也未声称达到 200ms 目标。

## 历史实施主要文件（2026-09-13）

- `WarehouseLocationCache.kt`：本地库位扫描、每日刷新、分页完整性、账号隔离。
- `LocalInbound.kt`：入库本地快照识别、限定候选查询、共享 SKU 展示资料、同步编排及历史草稿兼容。
- `CrossOrderReceiving.kt`、`BoxLabelMappingCache.kt`：跨单本地确认、未知/歧义拦截、映射游标及快照完整性。
- `LocalSkuCatalog.kt`、`FscmDatabase.kt`：共享 SKU 仓库、持久化元数据及 Room 19 迁移。
- `LocalInboundSync.kt`、`FscmApplication.kt`：身份变更触发准备、每日任务、按身份去重。
- `InboundManagementViewModels.kt`、`InboundManagementScreens.kt` 及四种语言的 `strings_inbound.xml`：独立资料同步状态、同步后更新识别集合、SKU 说明及本地未命中文案。
- `OfflineReferenceCacheTest.kt`：新增离线查询、分页、删除、隔离和迁移用例；既有迁移测试追加 18→19 迁移链。
- 既有 `FscmBusinessScreens.kt` 中对不存在字段 `displaySkuCode` 的引用改回该模型实际提供的 `skuCode`，使工作区能够编译。
- 后端新增 `mobile_catalog_dao.go`、`mobile_catalog_service.go`、`mobile_catalog_handler.go` 及三个对应测试文件；`routes/modules/mobile.go` 注册授权入口，相关功能文档同步更新。

本次没有偏离已确认方案；设备性能和真实弱网验收待有设备时完成。
