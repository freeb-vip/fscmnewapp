# 库位盘点 App

原生应用 key 为 `location_stocktakes`，权限为 `mobile-app:location-stocktakes` 与 `location_stocktakes`，提交另需 `location_stocktakes_submit`。入口会补入已有自定义应用布局；不提供离线库存写入。

正常操作：扫库位 → 扫 SKU → 查看当前数量 → 输入实盘数量 → 完成 → 保留库位继续扫 SKU。仅允许正库存；其他非零库位可查看，负库存不能进入盘点。设为 0 只修改输入；绝对差额超过 100 由服务端要求重新输入实盘数量。切换时提示放弃未提交修改。

数量编辑和确认框期间忽略扫描回调，并拦截数量框硬件键入字符；相机扫码复用 BarcodeScanner。查询使用代次检查防止过期响应串入；提交前持久保存原请求 UUID，响应不明时恢复原请求，不能自动生成新盘点。DataStore 按后端/用户/工作空间隔离，保存未提交数量、快照及原请求；过期快照不能提交。

数据和接口在 `fscm/data/LocationStocktakes.kt`，状态在 `LocationStocktakeViewModel.kt`，Compose 页面在 `LocationStocktakeScreen.kt`。中、英、泰、缅文字分别位于 `strings_stocktakes.xml`。

## 当前接入限制

2026-10-09：服务恢复后，apimix 已验证授权目标 `F0-01-02 / TB999-VIP-BOX` 的 252 → 253 → 252，两笔任务完成且其他相关库存不变，并已发布标准盘点创建能力。未清零。App 写入按钮依赖后端能力、权限和共享仓库配置；默认开发库尚未配置工作空间共享仓库，因此 App 登录后的真实提交仍未验收。后端详细状态见 FSCM 仓库 `docs/features/location-stocktakes.md`。

## 本轮验证

- `:app:testDemoDebugUnitTest`：350 个测试通过，其中新增 3 个数量/确认阈值、扫码状态锁、快照/请求序列化测试。
- `:app:spotlessKotlinCheck` 与 `:app:assembleDemoDebug` 通过。
- 未进行真机 PDA、相机、小屏视觉与 App 登录提交验收；apimix 到 BigSeller 的真实盘点已验证。其他功能已有改动保留，不纳入本功能提交。
