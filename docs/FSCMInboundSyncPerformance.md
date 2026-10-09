# 入库加载与同步请求优化

## 当前行为

入库及采集页优先读本地完整单据快照，过期时共用单据刷新任务；首次无缓存按分页准备。下拉/手动同步只上传当前单据（含当前入口确认的跨单入库事件），随后刷新当前单据箱资料及必要识别资料。同时异步强制刷新中心库位、SKU 及箱码资料，不遍历其他单据。

“更新离线资料”独立调度库位、SKU、箱码及当前单据跨单目录。库位与 SKU 有效期及后台检查周期改为两小时；库位/SKU 两类下载并发，其他阶段顺序执行。登录、前台恢复、网络恢复及进入入库会话异步检查资料。在线扫描的缺失/过期库位和 SKU 通过中心定向查询补齐，有效缓存及显式离线仍使用本地资料，具体规则见 `FSCMOfflineReferenceData.md`。其他业务控制器保留原默认行为。

草稿库位和箱码采集不调度整单同步；存在可上传记录才排队。手动任务按用户/工作空间/单据合并；快照下载独立按同一范围合并。上传确认之后必须使用在确认之后启动的快照，正在下载的旧请求结束后最多追加一次。下载失败不缓存成功结果；重试重新尝试下载。上传沿用既有串行互斥及幂等机制。

工作区读取不再写入重算状态；箱码映射、SKU 批量读取，复用快照，统计在后台计算。缺失/过期 SKU 的中心查询按最多 400 条合并，单次读取最多等待五秒。采集页商品统计与箱明细共用一次本地读取和计算；标题只读本地单据头，采集数量由本地箱投影提供，不再额外构建工作区或下载商品汇总。混箱、异常、草稿、待上传和冲突统计口径不变。

前台显示上传、下载和识别资料阶段，箱快照显示已下载/总数。基础资料后台更新独立显示，不占用下拉动画。已存在的数据在网络刷新期间保持可见，扫码不依赖刷新完成。

## 接口兼容

`GET receiving-sessions/:id/inbound-items` 新增可选 `include_total=false`。默认仍返回 total；省略总数时返回 has_more，以多取一条判断后续页。App 首请求取总数和统计，后续页跳过。旧服务端忽略新增参数时 App 仍可分页。

非统计、非收货状态筛选请求，只计算当前页箱的收货状态、手动收货、采购事实/锁定和作废恢复状态。状态筛选及整单统计仍使用整单状态，避免改变筛选结果。采购来源快照仍需解析整单 JSON；未引入版本增量协议或跨请求一致性快照。

完整分页和数量/重复验证成功后才替换 Room 快照。SKU 按原始大小写保存，只拒绝重复 ID 或精确编码；先精确匹配，再仅允许唯一的大小写候选。分页异常、账号变化不替换完整缓存；本地待上传、冲突仍保留。服务端最终校验不变。Room 20→21 重建旧 SKU 缓存并增加大写候选索引，中心数据库无迁移，详见 `FSCMOfflineReferenceData.md`。

## 代码位置

- data/LocalInbound.kt、LocalInboundSync.kt、InboundRefreshCoordinator.kt：当前单据同步、任务合并、下载校验、分阶段进度。
- data/BoxLabelMappingCache.kt、LocalSkuCatalog.kt：批量本地识别资料读取。
- data/CrossOrderReceiving.kt、ui/CrossOrderReceivingPanel.kt：当前入库来源范围同步及目录互斥；入库页进入时不自动联网。
- data/ReceivingProductProgress.kt、ui/ReceivingProductProgressScreens.kt：缓存优先采集页及后台计算。
- ui/InboundManagementViewModels.kt、InboundManagementScreens.kt、四套 strings_inbound.xml：独立资料入口和同步反馈。
- 后端 receiving_session_handler.go、receiving_local_receipt_service.go、receiving_purchase_management.go、receiving_local_scan_service.go 及相关 DAO：分页可选总数与限定箱状态查询。

## 验证与设备验收

本地日志 `FscmInboundSync` 输出单据快照行数、页数和耗时，不输出扫描码。目标设备须分别测试500、5000、20000箱，记录首屏和扫码 P50/P95、请求数量、字节数及同步阶段耗时。库位识别目标 P95 ≤ 200ms，缓存首屏目标 P95 ≤ 500ms；本机测试不代替目标手持设备验收。

### 历史本地验证记录（两小时资料调整前）

- `.\gradlew.bat :app:testDemoDebugUnitTest :app:assembleDemoDebug :app:spotlessKotlinCheck --no-build-cache --continue`：307项单元测试通过，零失败/跳过，demoDebug APK生成。
- 格式检查仅剩原有5个文件：FscmModels.kt、BoxLabelManagementScreen.kt、FscmApp.kt、InboundChangeLocationRoomTest.kt、InboundPreviewAssignmentTest.kt。本次修改文件通过格式检查；没有批量整理无关文件。
- 本机 JDK25 与现有 JaCoCo 对 JDK内部类的插桩产生 major69 兼容警告；单元测试断言通过，不据此声称完整覆盖率。
- 后端执行分页/工作空间隔离、手动收货、采购保护、并发作废和分页状态等测试，含 PostgreSQL 隔离 schema 测试，全部通过。500/5000/20000箱规模结果见后端功能文档。
- 未连接目标手持设备；首屏/扫码 P50/P95、实际弱网请求字节数尚未验收。未创建提交，未部署生产。
