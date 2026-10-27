# 新版 Codex 应用操作审批

范围为 Codex → Host → 原生手机审批 → 原 owner 具名响应，关联 F41／S09／UI09。支持 MCP 空确认、单一 approved 布尔确认及额外网络／文件权限；只有原请求声明的 session／always 才提供持久授权。复杂表单、URL 和用户验证保持电脑端处理。macOS 系统录屏／辅助功能授权仍走系统流程。

## 本次隔离 PR 验收

基线为 `fa2f14dbde37535dee6db2353ae619325c26b589`，保留主线已合入的审批内容、导航及迟到回调归属保护。按用户最新要求，本轮以模拟器验收为交付条件，不再要求实体手机窗口；没有新增真实模型输入。

iOS 26 独立 iPhone 17 Pro 模拟器：1 个 XCTest 原生用例、7 个场景、0 失败、0 跳过。实际 App 经 HostService HTTP 提交准确 Host／thread／Turn／approval ID、具名 decision、空 answers 和 `mcp-v1` 请求头；每次提交后卡片消失并显示完成结果。HTTP fixture 使用生产 Adapter 投影，单独断言响应映射。它是合成辅助证据，不宣称真实 Electron／模型在本次隔离源码上通过。

| 场景 | 原生动作／结果 |
| --- | --- |
| Computer Use 会话允许 | 单个“本会话允许”；accept 映射为原 session |
| Computer Use 始终允许 | 原请求声明 always，精确提交 acceptAlways |
| 拒绝 | decline，原请求清除 |
| 取消 | cancel，按钮显示“取消” |
| 额外权限本轮允许 | accept，原权限对象与 scope=turn |
| 额外权限会话允许 | acceptForSession，scope=session |
| 复杂表单 | 没有允许入口；提示电脑处理，可拒绝／取消 |

7 张审批截图均保留，抽查会话授权、额外权限和复杂表单 3 张，未见按钮截断或重复会话入口。Android 当前隔离源码的 App 与 instrumentation APK 构建通过（82 项 Gradle 任务）；本轮未重新运行 Android 原生界面。早先相同新增界面逻辑在 API 36 独立模拟器上 1 项原生合成审批通过，作为历史辅助证据单列。

Host HTTP／桌面 IPC 回归覆盖各授权范围、精确原 owner 方法／版本／请求／响应、旧客户端仅拒绝降级、无效范围、完成／错目标／过期失效、并发点击单次提交，以及旧回执晚到后新请求仍能继续回答。新审批模块纳入 macOS 预备桥接、旧控制器及 Windows 证明摘要；没有据此新增 Windows 运行验收。

完整检查与构建结论在 PR 验证记录中登记。首轮在编辑中的源码摘要处失败；后续因本机磁盘耗尽，完整检查及 Android 构建遇到 `No space left on device`，iOS 首轮被“无法保存本地会话，请检查设备存储空间”弹窗挡在导航步骤。仅清理本任务可再生成的旧 derived 缓存，保留报告、截图、源码快照及 runner；恢复空间后 iOS 七场景和 Android 构建通过。Android 重跑期间保留了访问日志缓存损坏的告警，完成后只清理本任务已关闭 Gradle 的该日志缓存。失败记录均保留，没有跳过产品断言。

复验：先 `pnpm build`，再在明确指定的独立模拟器上运行 `IOS_SIMULATOR_ID=<simulator-id> node scripts/application-approval-ios-fixture.mjs`；结果写入独立目录，可用 `APPROVAL_RESULT_DIRECTORY` 指定。它只启动临时回环 HTTP fixture，不调用 Codex、实体手机或正式 Host。

## 早先真实 DEV 记录

本任务早先在独立验收 bundle 上捕获了原 Electron／真实模型的 Computer Use 空 form 审批（computer-use、session／always、实际 xhigh）。前三条全访问输入未产生审批，第四条显式批准模式产生原请求；首次实体手机用例因新 bundle 无线数据授权失败，随后核对同一原请求进行零新增输入恢复，1 项原生用例通过，原 owner 确认真实专用应用快照和唯一输入。最终单个会话授权入口整理后另发一条独立输入，手机允许、原 owner 真实工具结果与完成标记再次通过；本任务早先累计实际模型输入 5 条，均未重放。

该历史最终 Adapter 指纹为 `bfbb72714452716b34c1373973e7056f725e0426ed2244031a24e66a0b154c67`，冻结原生源码指纹为 `6454372f2aebb6f0b1b7105ee5f4783aab5776493f669996ea27808259c3725b`，Host server 构建指纹为 `f226754a55f930ed04ac3796d902e3509829a6fda5d0f6cf72f975f75b81ccda`。它们属于当时共享开发现场，不能替代本次隔离 PR 全部源码的真机证明。

历史实体设备复验入口保留为 `scripts/computer-use-approval-ios-acceptance.mjs`，要求显式设备 ID 和本机 Tailscale CLI，使用独立 bundle／Host 状态及临时 HTTPS 路由。`COMPUTER_USE_APPROVAL_RECOVER_RUN` 仅恢复已核对原请求，固定原输入、目标及请求指纹，禁止新增模型输入或重放。其临时 Host、探针及路由已退出／撤销，原正式服务和正式 App 未替换。

正式分发包、安装更新、Android 真实模型审批、Windows、其他电脑及后台 APNs 本轮未验收；完整分发包仍要求显式冻结的 Task Hub 依赖，不代替其他任务选择历史业务包。
