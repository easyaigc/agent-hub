# Codex 与 EasyWork 的调用端边界

按目标的真实协议选择调用端。连接记录、SDK 源码、工具分发和真实执行是分别核对的交付状态。

| 目标与用途                               | 已有入口                                                                            | 使用边界                                                                                         |
| ---------------------------------------- | ----------------------------------------------------------------------------------- | ------------------------------------------------------------------------------------------------ |
| 管理原 Codex 桌面                        | 本 Skill 的 `scripts/contact-host.mjs`                                              | 固定 Codex Host HTTPS 契约，核对 `host-agent` 和 `desktop-runtime`；保留原 owner、操作 ID 与回执 |
| EasyWork 桌面向 Codex／EasyWork 派发任务 | EasyWork 设置中的 Host 连接，以及任务管理／定时任务的执行目标                       | 需要对应连接已配对、目标能力就绪；由当前 EasyWork 计时，不是将定时定义托管到目标电脑             |
| 在 EasyWork 内复用任务传输               | `createEasyWorkHostTaskClient` 内部 SDK                                             | 由 Electron composition root 提供连接服务、安全凭据引用和加密会话；不是随本 Skill 分发的独立 CLI |
| 外部 Codex 管理项目工作台                | 从目标 EasyWork 下载的工作台开发 Skill 中 `easyai-managed-cli.mjs workbench_manage` | 仅用于已授权项目的工作台生命周期，不提供通用聊天或任务派发授权                                   |

## 复用内部 SDK

实现位于 EasyWork 源码的 `apps/desktop/src/main/remote-access/easywork-host-task-client.ts`，组装位于 `apps/desktop/src/main/work-tasks/host-runtime.ts`。连接服务是 `createDesktopHostConnections`，负责安全凭据引用；`EasyWorkHostSessions` 负责绑定到准确目标的加密租约。

已有 composition root 可调用 `createEasyWorkHostTaskClient(connections.service, connections.sessions)`，通过 `bind(selection, authorizationRef)` 取得绑定客户端。`selection` 包含连接 ID、目标项目和完整运行目标，EasyWork 账号作用域按其自身协议核对。调用者须复用目标提供的对象和授权引用，而非复制手机凭据或在 Codex 协调工具中伪造运行模式。

绑定客户端提供具名的 `context`、`projects`、`models`、`readThread`、`history` 和资源读取，以及 `prepareCreate`／`createThread`、`prepareStart`／`startTurn`、`commandStatus`、控制和单次审批接口。准确输入类型以 SDK 导出为准；SDK 不开放任意方法名或远端 shell 透传。

业务调用优先复用现有 Host runner、dispatch repository 和 coordinator，它们在提交前持久化意图、输入摘要及原操作 ID。空聊天创建、文件传输、模型提交和取消分别记录；结果未知时查询同一原操作，不以重新绑定、新建聊天或再次启动模型替代对账。审批固定原 Thread／Run／实时请求，单次决定不修改会话权限。

资源由目标正式读取并校验摘要，再通过统一产物服务登记。提交回执、运行结束、业务完成和验收通过分别判断。实际模型／推理／权限未被权威回执披露时显示未确认。

## 外部 Codex 的当前缺口

本 Skill 的统一包尚未包含独立的 EasyWork 通用任务 CLI、MCP 或可供外部 Codex 直接初始化的安全连接服务。内部 SDK 合入 EasyWork 主线、EasyWork 能主动连接其他 Host，均不能代替这一入口交付。

需要外部 Codex 直接控制 EasyWork 聊天／任务时，先核对目标是否另有已发布、匹配认证和协议的工具。若没有，报告缺少调用端，并记录可复用的内部 SDK 与所需连接租约；不要将 `contact-host.mjs` 的 Codex 运行校验放宽后套用。后续外部入口应在规范源码中维护认证适配、具名操作和未知结果恢复，按统一程序产物分发，并单独取得真实 EasyWork 执行证据。
