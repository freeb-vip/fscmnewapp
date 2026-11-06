# 工单涉及范围

原生 App 接入工单的可选店铺／产品／SKU 范围。列表支持本地店铺多选、SKU／产品编码包含查询和本地对象精确查询；卡片优先展示服务端标记的匹配范围。详情展示范围、原始订单 SKU、商品属性与失效说明，个人协作继续按人员完成。

创建人、当前负责人在未解决／未关闭时可编辑范围。编辑使用打开时的 `scope_version`，冲突保留草稿，不自动覆盖最新数据。商品身份更换通过移除旧项、添加新项完成；删除被协作引用的明细由服务端拒绝，并提示到 Web 管理。没有新增工单创建、分工或异常登记入口。

`TicketScopeRepository` 使用现有 `FscmApiClient`，沿用认证、空间请求头、移动端标识及响应解包。选择器只读取当前空间精简引用信息；没有调用完整产品／店铺管理接口。仓库异常仍经过原授权入口，详情只展示已登记商品。

实现文件：`data/TicketScope.kt`、`ui/TicketScopeScreens.kt`，接入既有 Workplace、TicketConversation、OrderExceptions 页面。英文、简体中文、泰文、缅甸文资源位于各语言目录 `ticket_scope.xml`。

后端契约和迁移说明见同级 fscm 仓库 `docs/features/ticket-scope.md`。必须先部署343／344迁移及全部后端实例，再部署 Web 和 App。回退 App 不清除服务端范围。

验证包含 `TicketScopeTest` 的旧响应兼容、前导零、身份规则和属性可读展示，以及 `TicketScopeSmallScreenTest` 的320dp筛选输入与确认按钮可达性。完整验证命令为 `:app:spotlessKotlinCheck :app:testDemoDebugUnitTest :app:assembleDemoDebug`。

2026-11-06验收：完整单元测试458项通过，格式检查与Demo调试构建通过。最终属性展示调整另跑 `:app:spotlessKotlinCheck :app:testDemoDebugUnitTest --tests '*TicketScope*' :app:assembleDemoDebug --no-build-cache`，5项专项测试均通过，包含原生Compose的320dp小屏交互。没有连接实体设备，未声称完成真机验证。

本机JBR25与既有JaCoCo版本会输出不支持class major version 69的覆盖率插桩告警；测试XML确认无失败、无跳过，不以该次覆盖率作为验收依据。磁盘缓存打包曾长时间等待，停止该次构建后使用 `--no-build-cache` 重跑。未部署生产、未生成正式发布包。
