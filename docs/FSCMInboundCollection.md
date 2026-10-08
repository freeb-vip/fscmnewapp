# 入库采集工作台

## 统计与操作

入库首页、入库作业明细和产品箱唛列表使用同一箱唛投影：当前有效箱唛为计划采集；中心确认真实库位或本机扫描成功计入已采集。混箱归首个 SKU 所属产品，单箱去重；未知产品归入“产品信息待补全”，不丢弃记录。未采集等于计划减已采集。

草稿显示“待完成库位”；批次完成后显示“待同步”；冲突/拒绝显示“同步异常”。采集齐全且无异常的产品进入“已完成”，即便尚待同步仍明确展示待同步箱数。中心入库确认与本机采集分开显示。作废、取消、手动收货等不可采集箱唛不进入采集分母，保留为异常记录。中心快照缺失的本地扫描同样保留为异常。

“已采集”入口默认看全部产品，“未采集”入口默认看仍有未采集箱唛的产品。进入产品默认看未采集箱唛，可切换全部/已采集。箱唛行展示箱唛号、首个 SKU 名称及混箱标记。作业明细下拉刷新完整箱唛快照；同步页及主工作台下拉执行与手动同步相同的流程。失败时保留旧快照和更新时间。

## 同步与升级

扫描库位、箱唛和完成库位触发持久后台请求。只有完成批次后的记录才上传，草稿仍可撤销。后台同步不持有草稿锁、不占用扫码提交状态、不自动弹出冲突对话框。按账号/工作空间/单据记录请求代次，正在同步时的新请求合并补跑；已覆盖的排队任务直接结束，失败保留待处理代次。手动与后台同步通过同一个串行入口上传、更新快照并尝试结束入库阶段。账号或空间切换后不继续请求旧单据。

Room 从 17 升至 18，仅添加箱唛展示字段，保留所有草稿和待同步记录。旧服务器不提供产品元数据时归入“产品信息待补全”。完整功能需同步上线后端的 inbound-items 补充字段；后台采购、库存、导出混箱口径不变。

## 验证

本轮修改文件及用途：

| 文件 | 用途 |
| --- | --- |
| `data/InboundCollection.kt`、`data/ReceivingProductProgress.kt` | 共用箱唛计数、产品归组与完整产品详情 |
| `data/LocalInbound.kt`、`data/LocalInboundSync.kt` | 完整缓存下载、预览保留资料、草稿完成及持久后台同步 |
| `data/FscmDatabase.kt` | 17 → 18 非破坏性迁移 |
| `ui/InboundManagementScreens.kt`、`ui/InboundManagementViewModels.kt` | 主工作台汇总、下拉同步及不阻塞扫码的独立状态 |
| `ui/ReceivingProductProgressScreens.kt` | 产品筛选、下钻箱唛、下拉刷新、空状态与小屏筛选换行 |
| 四种语言的 `strings_inbound.xml` | 采集、同步、异常与空状态文案 |
| `InboundCollectionTest.kt`、`InboundCollectionMigrationTest.kt`、`InboundSyncCoalescingTest.kt` | 统计、迁移与同步合并回归；既有 `AutoPurchaseReceivingTest.kt` 补充迁移链 |
| 后端 `services/receiving_collection_metadata.go` 及对应测试、`handlers/receiving_session_handler.go` | 批量读取并附加首个 SKU 产品信息 |

APP 路径以 `app/src/main/kotlin/com/google/samples/apps/nowinandroid/fscm` 为基准；测试位于对应 `app/src/test/kotlin` 目录。

新增 InboundCollectionTest、InboundCollectionMigrationTest、InboundSyncCoalescingTest，覆盖列表完整性、计数与明细一致性、混箱/未知资料、草稿撤销、重复/重新绑定、异常、同步前后去重、升级保留数据及请求合并/失败重试。既有收货和入库测试一并回归。

开发验证命令：`:app:testDemoDebugUnitTest`、`assembleDemoDebug`、`:app:spotlessKotlinCheck`。全量格式检查可能受到任务开始前的其他文件换行符问题影响；本次修改文件独立检查。当前没有连接 Android 设备，真机小屏布局、扫码枪和摄像扫码需现场验收。

现场验收建议使用一张含普通箱、混箱、虚拟库位箱及异常箱的收货单：

1. 在作业明细查看全部产品，核对计划数等于有效实物箱唛数；分别切换未采集、已完成及异常，确认已完成产品没有从“全部”消失。
2. 从已采集和未采集入口进入同一产品进度页，点产品核对未采集清单。混箱仅出现一次，显示首个 SKU 名称；缺资料明确提示更新。
3. 断网扫描库位和箱唛，确认草稿显示“待完成库位”；删除或取消后数量恢复。完成库位后立即扫描下一库位，联网后核对待同步逐步清空且计数不重复增加。
4. 在同步期间继续扫码、完成下一批及切换单据；核对输入不被禁用，扫描反馈不被同步结果覆盖，旧单据数据不进入新页面。检查小屏多语言筛选按钮换行、长名称、空列表下拉及刷新失败原因。

## 本次实际结果（2026-09-12）

- `:app:testDemoDebugUnitTest assembleDemoDebug`：最终构建成功，297 项测试，0 失败、0 错误、0 跳过。Room 升级保留数据、采集统计、重复/重新绑定、同步请求合并及失败重试均在单元测试内验证。
- `:app:spotlessKotlinCheck`：全量检查受到已有 `FscmModels.kt`、`InboundChangeLocationRoomTest.kt`、`InboundPreviewAssignmentTest.kt` 换行符问题影响；仅限定本轮修改文件的 Spotless Apply/Check 通过，未格式化其他工作区文件。
- 四种语言的资源键、参数占位符、XML 和 UTF-8 检查通过；已从最终 APK 内确认新增中文空状态文案存在。
- 后端 `go test ./services ./handlers -run 'TestCollectionMetadata|TestBuildReceivingInboundProductBox' -count=1` 成功：services 测试通过；handlers 编译通过，但该筛选没有匹配测试，不能视为 handler 业务测试覆盖。
- `docker compose -f docker-compose.dev.yml up -d --build` 成功；前端、HMR 客户端及后端健康检查返回 HTTP 200。后端监听到本轮文件变更并完成重新编译、运行，重启后健康检查再次返回 HTTP 200。收货单列表接口返回 HTTP 200；抽查单据无箱唛，真实箱唛端到端业务联调未验证。
- 无 Android 实机连接：小屏触控、扫码枪、摄像扫码、真实慢网/断网、应用重启及连续扫码现场流程仍需验收。前端 HMR 客户端可访问，但未进行浏览器交互热重载验收。

测试包：`app/build/outputs/apk/demo/debug/app-demo-debug.apk`。SHA-256：`F87D203DB1D30A4E90C6A37782BA6CA051CAA6591E18B1DCB3C212EA2A5BEE1D`。

APP 最终日志位于 `app/build/collection-shipping-validation.log`、`app/build/collection-shipping-format.log`；后端日志位于 `backend/tmp/inbound-collection-verification/`。未创建提交，未发布生产环境，无入库准入或后台混箱核算规则偏离。
