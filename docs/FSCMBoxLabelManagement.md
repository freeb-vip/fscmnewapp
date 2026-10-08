# 箱唛管理集成说明

本页面复用现有 FSCM 网络层、会话、扫码设备和打印服务，使用中心 `/box-labels` 智能搜索与稳定 ID 详情，协议版本为 2。接口不可达时使用已同步的边缘目录，并显示节点、同步时间及覆盖限制。

- `BoxLabelManagement.kt`：搜索元数据、尺寸、收货、关联和变更 DTO，以及精确跳转和打印内容比较规则。
- `FscmModels.kt`：兼容扩展箱唛、混箱 SKU 数据。
- `FscmRepositories.kt`：智能查询、中心/边缘回退、打印校验与超时任务核对。
- `EdgePrintLeaseStore.kt`：节点设备授权和待确认打印幂等键的加密持久化。
- `EdgeCatalogData.kt`：复用设备签名授权和同步时间响应头。
- `FscmViewModels.kt`：500ms 防抖、旧请求取消与版本校验、分页去重、只触发一次的主动精确跳转、详情与打印状态。
- `BoxLabelManagementScreen.kt`：Compose 搜索列表、混箱展开、详情分区、当前/历史关系、底部打印操作。
- `FscmApp.kt`：既有导航入口接入新页面，复用打印对话框与状态胶囊。
- `ProductCountScanPolicy.kt`：原 `ProductCountingPolicy.kt` 仅更名以符合现有 Spotless 文件名规则。
- `BoxLabelManagementTest.kt`：跳转策略、混箱/历史解码及打印内容变化测试。

中心实现位于 FSCM 主仓库，节点实现位于 FSCM Windows 仓库。发布顺序为中心、节点、Android；旧节点必须升级并全量同步，不能把不完整结果标为全系统查询。默认打印 1 份，范围 1–100；没有可用节点时保留错误，不安排未来自动出纸。

验收命令：`./gradlew.bat :app:testDemoDebugUnitTest :app:spotlessKotlinCheck assembleDemoDebug`。真机需额外验证四个底部 Tab、小屏中文、相机/PDA 输入、列表返回位置、收货扫码及真实节点打印。


## 本次验证（2026-09-06）

- `:app:testDemoDebugUnitTest`：37 个测试类、244 项测试全部通过，失败/错误/跳过均为 0；新增箱唛用例 4 项。
- `:app:spotlessKotlinCheck`、`assembleDemoDebug`：通过，生成 `app/build/outputs/apk/demo/debug/app-demo-debug.apk`。
- Windows 内存不足时采用单 worker、Gradle 1536 MB 堆、测试进程 512 MB 堆完成验证；临时 init script 不修改项目检查。JDK 25 / JaCoCo 0.8.12 有部分类插桩警告，因此不将本轮结果作为覆盖率结论。
- `git diff --check`、变更文件 UTF-8 检查通过。
- `adb devices -l` 无设备，尚未完成小屏、四个 Tab、相机/PDA、收货流程与真实打印手工验收。
