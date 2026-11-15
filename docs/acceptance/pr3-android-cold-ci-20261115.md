# PR3 Android 冷会话与 CI 交付记录

2026-11-15，在 Gitea `2ab8ab1d1adb7d35dc6f80a2cead9b8c139ef6b8` 上整合 GitHub PR3 `d4e247ef453b7df80c8184a7bcfb08a67c6c61ef`，保留已合入的文件下载进度功能和双方文档、功能变更记录。

`NativeColdThreadLiveTest.oldOriginalChatOpensOnceAndReceivesOneNativeReply` 本次在独占 Android 36 ARM64 模拟器实际执行，1 项通过，27.632 秒。测试 APK 为 `com.easyai.agenthub.android.acceptance`，使用候选 Host、本机真实 Electron 与真实模型，不使用合成模型回复。

- 独立进程先确认测试会话已有一个完成轮和两行历史，原 owner 未加载；Host 读取历史后 owner 仍未加载、不可控制。
- 原生界面打开并发送一次；处理必要的原生分享确认，按实际 HTTP/操作回执计数，open 和 reply 各一次。
- 原两行 ID 和原文分别保留一次，最终只增加本次 prompt/reply，共四行且 ID 唯一。
- 独立于 Host 的桌面进程确认准确原 owner、两个 completed Turn、两条原用户输入，其中本次新增一条；原生 reply 操作 ID 与不可变提交一致。
- 实际模型为 `gpt-6.1-terra / medium`，速度参数省略。原生测试执行 Agent 的同一模型/推理设置也已核实；入口没有速度参数，不宣称已设置最高速度。

应用 APK SHA-256：`06c9f8b250b1fc6b829ef8dea25daa3ef67254951bd293cbefd720b7427919bb`。Instrumentation APK：`483e3fe38879ceff3a1638164a53077f9b3f5bddd8bcdafc301c79df72ad4099`。Android、Host、共享核心验收前后源指纹均为 `dc002c6e52855a62d594cba7c6bee0387cbd80e961907493fe29147e4f4cb899`。主会话另核对 APK/截图摘要、原生通过记录和官方聊天的两个完成轮。

准备阶段两次错误保留：种子已提交后，观察器缺少 workspace 成员登记；后续只读确认原轮完成，没有重发。第一次独立 Host 身份未初始化，在任何 open/reply 前返回 403；修正该任务私有状态后重建冷基线。测试后 Host、反向端口、模拟器和 AVD 已回收，测试聊天通过正式入口归档；缓存按主任务依赖交接并回收。

本次仅证明所列模拟器/真实桌面冷会话。两条种子文本不同，不新增同文多重性、Android 实体手机、蜂窝、VPN或生产部署结论。同文不同消息和跨轮 ordinal 的行为仍由原 Host 回归另行覆盖。此前六项 iOS 补验见[原始限定记录](pr3-ios-20261115.md)，其准确候选与运行包保留；本次 CI 的两处展示表达式拆分不改变会话控制或历史协议。

CI 修复包括：

1. 在完整 `pnpm check` 前，按 PR 事件准确 base SHA 或 push 的 `before` 选择比较基准；公开独立历史不再依赖私有 Gitea baseline。缺失的显式基准仍失败，多提交 PR 仍检查完整范围。六项真实 Git 边界测试通过。
2. 将聊天操作查询按钮单独提取，并将 Git 摘要的可选数字转换和字符串构造拆开，降低 SwiftUI 类型推导复杂度。动作、标签和无障碍标识保持；F75/A6 的既有暂停范围保持。

本地 `pnpm build` 通过；完整检查曾因共享磁盘接近耗尽而主动中断，未执行完的本地检查及 iOS 构建不记为通过。中断、工具对 `rm -f` 命令的拒绝，以及经所有权/进程/挂载/文件身份核对后采用逐项安全清理的回执分别保留。后续以 Gitea 候选 PR 的真实完整检查、iOS 构建结果作为合并门槛；合入后继续核对主线和 GitHub 同步后的实际 CI，不用本地或旧 SHA 替代。

Gitea PR177 首轮候选的 iOS 构建通过；工作区安装时 Linux Turbo 可选二进制连续下载重试后缺失，启动器进入长时间无输出的 npm 补装，完整检查尚未开始，不能记为通过。新候选仅调整 CI 下载超时/并发，并增加安装后二进制与锁定版本核对，保持 Android、Host、共享核心和 iOS 产品源指纹。安装或核对失败时保留失败结果，仍以新候选完整 CI 成功为合并门槛。

源码、操作意图、回执、设备与独立桌面证据保存在维护者私有验收记录；PR 与 Actions 关联准确提交。本文属于 Codex PR3 专项，不改变 iOS × EasyWork 唯一计划、Android 其他阶段和既有暂停范围。
