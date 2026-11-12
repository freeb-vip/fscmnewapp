# 异常订单

原生应用使用服务端授权后的 `order_exceptions` 入口，打开异常列表、详情、作业履历、整改表单和照片上传。查看权限为 `mobile-app:order-exceptions`，处理权限为 `order-exceptions:resolve`；可见订单另受仓库授权约束。离线不提供此应用，也不在服务端未返回入口时自行补出应用。

## 应用列表入口修复（2026-11-12）

1.3.5 已包含异常订单页面及导航代码，但 `implementedMobileAppKeys` 遗漏 `order_exceptions`，导致服务端已返回且授权的入口仍被客户端过滤。生产工作台接口已确认返回该入口并设置 `visible=true`。

`FscmRepositories.kt` 将该入口登记到已实现应用及元数据目录，在线显示仍要求查看权限。`OrderExceptionsTest.kt` 覆盖工作台新旧目录格式、授权后可见、无权限／离线／显式隐藏时不可见，以及无服务端目录时不补造入口。

修复包为 1.3.6／36，沿用正式包名和签名，不提高最低支持版本。

验证：`:app:spotlessKotlinCheck :app:testDemoDebugUnitTest :app:assembleProdRelease` 通过，461 项单元测试无失败或跳过，新增用例验证授权入口经过真实目录过滤后仍可见。正式包为 `com.fscm.app`／1.3.6／36，签名校验通过且证书与 1.3.5 相同。JBR 25 的既有 JaCoCo 插桩警告保留，不据此宣称覆盖率完整。尚未在用户手机验收安装后的实际入口及拍照整改流程；发布结果另行补录。无数据库、后端接口或权限配置变更。
