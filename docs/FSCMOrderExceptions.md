# 异常订单

原生应用使用服务端授权后的 `order_exceptions` 入口，打开异常列表、详情、作业履历、整改表单和照片上传。查看权限为 `mobile-app:order-exceptions`，处理权限为 `order-exceptions:resolve`；具有查看权限即可查看当前工作空间全部自有仓的有效作业异常，具有处理权限即可整改，不再配置独立仓库授权。离线不提供此应用，也不在服务端未返回入口时自行补出应用。

## 应用列表入口修复（2026-11-12）

1.3.5 已包含异常订单页面及导航代码，但 `implementedMobileAppKeys` 遗漏 `order_exceptions`，导致服务端已返回且授权的入口仍被客户端过滤。生产工作台接口已确认返回该入口并设置 `visible=true`。

`FscmRepositories.kt` 将该入口登记到已实现应用及元数据目录，在线显示仍要求查看权限。`OrderExceptionsTest.kt` 覆盖工作台新旧目录格式、授权后可见、无权限／离线／显式隐藏时不可见，以及无服务端目录时不补造入口。

修复包为 1.3.6／36，沿用正式包名和签名，不提高最低支持版本。

验证：`:app:spotlessKotlinCheck :app:testDemoDebugUnitTest :app:assembleProdRelease` 通过，461 项单元测试无失败或跳过，新增用例验证授权入口经过真实目录过滤后仍可见。正式包为 `com.fscm.app`／1.3.6／36，签名校验通过且证书与 1.3.5 相同。JBR 25 的既有 JaCoCo 插桩警告保留，不据此宣称覆盖率完整。尚未在用户手机验收安装后的实际入口及拍照整改流程。无数据库、后端接口或权限配置变更。

北京时间 2026-11-12 08:45 发布上线，发布记录 62；1.3.5 查询更新已返回 1.3.6，非强制更新。修复提交 `f0553d9e96b52e1465707a01256e9d7805059456` 已推送 `origin/dev`，提交后重新构建并核对 APK 内源码提交标识。公开下载 SHA-256 与本地相同：`5460a445887dfbeccac00649f5acc0e22bdaec1a797b0dfdc90cb6adfc220b6f`。发布记录及公开下载验收保存在生产波次发布目录，不包含认证凭据。

## 全部自有仓及列表性能发布（2026-11-12）

1.3.7／37 已发布为线上版本，发布记录63，非强制更新，最低支持版本维持0。Android源代码仍为 Windows `F:\code\fscmnewapp`，以提交 `bb83f22dfd00f82ce36ddb37d1ac608cb6694c2c` 使用Windows JBR和Android SDK构建prodRelease，未使用旧后端源码作为发布基线。

后端改为使用WSL `/home/hong/code/fscm` 的最新d7d9194基础上提交的48e6507（1.1.10），保留现有SKU同步等代码。先执行361索引迁移（首次applied=1、复跑applied=0），再滚动更新3个后端实例，最后发布App。独立仓库授权入口、DTO和请求移除；查看／处理权限、工作空间、自有仓和有效作业校验继续生效。

`:app:testDemoDebugUnitTest` 461项、Kotlin格式检查及Demo Debug构建此前通过，本次Windows生产版本构建成功。公开下载与本地SHA-256一致：`87874db42127e6936b1584238e5822c7f18a72673ec33f051baa8e9134e15508`；签名证书SHA-256 `50282b0fe48954d9990641bb3e1bee91a9fb4d59af1f04e574cc7da86c9189ba`，与线上1.3.6相同，APK内源码标识为bb83f22d。

公开路径 `/api/app-downloads/cd9b2df0-2795-4ae7-8c01-fb3058589c09` 已下载验收，36版本检查更新返回37，37不再提示升级，force_update=false。已通过该公开文件升级登录的i6310设备，登录保留，异常页正常显示待处理70、已处理0，没有网络/权限失败提示或仓库授权入口。未提交真实整改、上传业务照片或关闭工单；设备自动锁屏，未完成这一轮详情交互验收。

发布审计记录保存在 `/home/hong/releases/exception-wsl-20261112`。先前旧路径发布任务在镜像构建阶段取消，未执行迁移或更新生产。App保留1.3.6发布记录62作为历史版本；回退需前后端配套并保留361结构及处理历史。

## 附件预览与整改输入优化（2026-11-12，本地实现）

- 原始异常凭证和历次整改照片可点击全屏查看，支持双指缩放、拖动、双击放大、重置和关闭。继续使用授权图片接口，不公开私有图片地址。
- 新表单及提交 DTO 不再包含责任归属、责任人和责任说明；问题类型仍必填，措施和照片可选。后端须先应用362迁移并升级，保留历史责任数据及旧客户端请求兼容；新版不能先于后端发布。
- 整改措施提供大面积预览和独立全屏编辑器，输入即时保存草稿，最多2000字符；编辑窗口随键盘调整可用高度，“完成”按钮保留在上方。拍照及相册使用带图标的全宽大按钮。
- 首次提交前弹出整改确认声明，点击“返回修改”不提交；确认后使用原有幂等机制提交。超时后的“确认处理结果”仅重试已确认的原请求，不生成新处理。
- 固定界面文案、校验提示、权限／网络错误及确认声明提供简体中文、英文、泰语、缅甸语资源，跟随App设置。运营填写的描述、仓名、员工姓名等原始业务内容不机器翻译。
- 确认声明：我已确认该问题并认真整改和优化，若再次发生同样的问题将追究相关人员责任。

验证：Windows执行`:app:testDemoDebugUnitTest`（461项通过），以及`:app:spotlessKotlinCheck`、`:app:assembleDemoDebug`。设备界面测试覆盖四语确认、正文多行输入及照片缩放；实际结果见后续验收记录。已纳入本地提交（编号见 Git 历史），未推送，未发布生产。构建日志存在既有JBR25与JaCoCo字节码兼容告警，不将其视为覆盖率结果。


验收补充：Windows最终`:app:spotlessKotlinCheck`、`:app:assembleDemoDebug`及461项`:app:testDemoDebugUnitTest`通过；连接i6310（API25）执行`:app:connectedDemoDebugAndroidTest -Pandroid.testInstrumentationRunnerArguments.class=com.google.samples.apps.nowinandroid.fscm.ui.OrderExceptionDialogsTest`，6项全部通过，包含双击缩放／重置／关闭、多行输入时完成按钮可见，以及中英泰缅确认／取消。已人工核对泰语和缅甸语设备截图。初次设备运行因自动锁屏失败，测试窗口改为仅在测试期间保持亮屏；语言夹具补充LocalResources，避免仍使用系统中文资源。未改变生产界面权限或设备永久设置。

核心文件：`OrderExceptionsScreens.kt`负责列表及详情国际化、移除责任表单、入口和提交确认；`OrderExceptionDialogs.kt`负责高分辨率图片预览、手势及键盘适配编辑器；`OrderExceptionsViewModels.kt`负责无责任提交校验与本地化错误；`OrderExceptions.kt`负责新提交DTO及草稿兼容；四份`strings_order_exceptions.xml`负责四语文案；`OrderExceptionsTest.kt`与`OrderExceptionDialogsTest.kt`覆盖请求、恢复及界面交互。后端仅在WSL最新工程修改，包含362迁移、可选责任字段和作业证据冻结、相关事务及迁移测试。

边界：未实测摄像头／相册选图后的真实上传、上传中断或弱网端到端链路；既有归属检查和上传失败重试逻辑保留。生产未迁移、App未生产发布、已纳入本地提交（编号见 Git 历史），未推送，无设计约束偏离。
