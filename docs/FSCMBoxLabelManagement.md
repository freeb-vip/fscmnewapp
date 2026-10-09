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

## 箱唛编码二维码（2026-10-09）

列表和详情页的箱唛编码可点击，编码旁增加二维码图标。点击后弹出黑白二维码及可选择复制的原始编码，扫码内容就是所点击的 `labelCode`，保留大小写，不拼接 SKU、数量、打印载荷或链接。列表卡片其余区域仍打开详情。弹窗支持关闭按钮、返回键及点击外部关闭；显示期间清除输入焦点、隐藏键盘并暂停列表扫码，关闭后恢复。

- `BoxLabelManagementScreen.kt`：两个编码入口、弹窗状态、扫码监听及输入焦点处理。
- `BoxLabelCodeDialog.kt`：编码按钮、原生弹窗、本地二维码生成；白底黑码、四模块静区及无插值显示，内容较多时可滚动。
- `app/src/main/res/{values,values-b+zh+Hans,values-th,values-my}/strings_box_labels.xml`：中、英、泰、缅文案。
- `app/build.gradle.kts`、`gradle/libs.versions.toml`：声明 [ZXing core 3.5.4](https://github.com/zxing/zxing/releases/tag/zxing-3.5.4) 编码依赖；`app/dependencies/prodReleaseRuntimeClasspath.txt` 追加对应基线条目。本地生成不需要网络，也不调用打印服务。
- `BoxLabelCodeQrTest.kt`：将生成的实际位图解码，验证原码、大小写、中文、长编码、白色边界和空编码拒绝。

二维码可从当前已加载的箱唛信息生成；生成失败时保留可复制原码并显示错误。此改动不修改箱唛或库存数据。

验证限制：`adb devices` 无设备，尚未进行真实手机/PDA 扫描屏幕二维码和小屏交互验收。额外执行 `:app:dependencyGuard` 发现既有 prodRelease 基线与当前 Camera、Firebase、SQLite、ML Kit 等依赖树不一致；本次仅加入 ZXing 基线条目，未整体重建无关依赖基线。该检查仍失败，详情保留在本地 `.artifacts/box-label-qr-dependencies.log`。

实际验证：`.\gradlew.bat :app:spotlessKotlinCheck :app:spotlessKtsCheck :app:testDemoDebugUnitTest :app:assembleDemoDebug '-Pkotlin.incremental=false' --no-build-cache --max-workers=1 --continue --console=plain` 通过。366 项测试，0 失败、0 错误、0 跳过；新增的二维码位图解码测试通过。开发包为 `app/build/outputs/apk/demo/debug/app-demo-debug.apk`。首轮构建因本机 classes.jar 被占用失败，停止 Gradle daemon 后串行重跑成功。现有 JDK 25 / JaCoCo 兼容告警仍在，不作为覆盖率结论。四语资源 XML 解析与 `git diff --check` 通过；保留此前 SKU 大小写修复和库位盘点字体修改。


## 本次验证（2026-09-06）

- `:app:testDemoDebugUnitTest`：37 个测试类、244 项测试全部通过，失败/错误/跳过均为 0；新增箱唛用例 4 项。
- `:app:spotlessKotlinCheck`、`assembleDemoDebug`：通过，生成 `app/build/outputs/apk/demo/debug/app-demo-debug.apk`。
- Windows 内存不足时采用单 worker、Gradle 1536 MB 堆、测试进程 512 MB 堆完成验证；临时 init script 不修改项目检查。JDK 25 / JaCoCo 0.8.12 有部分类插桩警告，因此不将本轮结果作为覆盖率结论。
- `git diff --check`、变更文件 UTF-8 检查通过。
- `adb devices -l` 无设备，尚未完成小屏、四个 Tab、相机/PDA、收货流程与真实打印手工验收。
