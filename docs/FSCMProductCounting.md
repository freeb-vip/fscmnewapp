# 产品清点任务删除与完成

新建任务首先保存在 App 本地，清点完成后由用户手动上传。2026-10-09 增加空任务删除，不新增后端接口或数据库迁移，也不调用 Web 管理端的任务删除接口。

## 操作规则

- 任务列表与详情均提供“删除任务”，仅限当前账号、工作空间、编辑设备下的本地进行中空任务。用户确认后从列表移除；详情删除后返回任务列表。
- 删除必须从未保存扫描记录，检查所有重置代次和所有扫描状态。重置前、冲突或拒绝记录仍属扫描数据，不因当前有效箱数为 0 而允许删除。
- 已上传、正在上传、曾发起上传但结果未确认、存在远端身份/版本或已完成的任务不能通过空任务删除入口移除。
- 有扫描记录的进行中任务使用“完成清点”结束，保留全部记录及手动上传入口。只有历史扫描记录、当前代次为 0 箱的任务也可以结束。完成后不能继续扫描，需沿用原有“重新打开”流程。
- 正在处理或排队的扫码、重复码待确认期间不允许删除或结束；任务操作期间和删除确认弹窗中不接收新的扫码。

## 实现文件

- `app/src/main/kotlin/com/google/samples/apps/nowinandroid/fscm/data/ProductCountingData.kt`：摘要查询包含全部扫描记录数；条件删除在同一 SQL 中核对账号、空间、设备、状态、上传证据与扫描历史，防止“先判断空、再删除”之间插入数据。完成也在 SQL 中校验编辑权和扫描记录。仓储层删除、完成与扫码共用互斥锁。
- 同路径 `ui/ProductCountingViewModels.kt`：列表和详情的删除确认、执行状态、失败提示、扫描排队计数及任务操作互斥。
- `ui/ProductCountingScreens.kt`：空任务删除按钮与确认框；有记录任务显示完成操作。
- `ui/FscmApp.kt`：删除成功后返回产品清点列表。
- `app/src/main/res/{values,values-b+zh+Hans,values-th,values-my}/strings_product_counting.xml`：中、英、泰、缅删除提示。
- `app/src/test/kotlin/com/google/samples/apps/nowinandroid/fscm/data/ProductCountingTaskActionsTest.kt`：空任务删除、账号/空间/设备隔离、重置和异常扫描记录保护、上传证据保护、完成后只读、扫描与删除并发不产生孤立记录。

已有入库 SKU 大小写修复、箱唛二维码及库位盘点字体修改保持。本次未操作真实任务数据。

## 验证（2026-10-09）

- `.\gradlew.bat :app:spotlessKotlinCheck :app:testDemoDebugUnitTest :app:assembleDemoDebug '-Pkotlin.incremental=false' --no-build-cache --max-workers=1 --continue --console=plain`：通过；最终 371 项测试，0 失败、0 错误、0 跳过，包含本轮新增的 5 项任务操作测试。
- `git diff --check` 通过；开发包位于 `app/build/outputs/apk/demo/debug/app-demo-debug.apk`。
- `adb devices` 无连接设备，尚未做实机创建、列表删除、详情返回、扫码后完成和离线操作验收。已有 JDK 25 / JaCoCo 告警不影响本轮测试结果，但本轮不作为覆盖率结论。
- 后端接口、服务和数据库结构未变更，因此本轮未重复运行后端或 Compose 联调。新建空任务只存在本地，删除直接落在 Room 条件写入，不扩展远端任务删除权限。
