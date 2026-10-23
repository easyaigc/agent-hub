# 原桌面控制和手机验收

本机 health、HTTPS、项目列表和能力声明不证明具体聊天接入原执行器。普通安装按 [独立空会话桥接](prepared-bridge.md) 的本机私有配置与真实运行时就绪开放正式功能，不要求目标电脑有 Xcode、手机在场或验收报告；报告仅作证据。开发与功能交付仍按规范仓库最新 `AGENTS.md` 实际执行本页完整真实验收；模拟器不代替 iPhone 或两台实体电脑。

首次接入默认执行 [完整功能清单](full-functions.md)，包括独立桥接生命周期、正式新建与主动打开；修复和升级按当前指纹复用有效证据，补验未覆盖或受影响项目。不能在只读历史、基础回复或验证专用 App 通过后结束完整接入。

默认入口按 [独立空会话桥接](prepared-bridge.md) 安装和验收。真实门槛保持完整，准备进程与首条执行次数用新桥接操作日志及真实 owner 核对；不再要求打开或维持普通控制聊天。验收使用独立状态与端口，共用正式 Host 不作为隔离验收进程；安装配置、能力启用与真实通过分别记录，缺证据标为未验收，不伪造通过。下文 controller 配置与证明门槛仅用于旧安装兼容诊断，不用于限制默认独立桥接。

## 当前兼容 driver 的控制聊天（不满足生命周期门槛）

以下记录现有实现的安装与功能验证步骤，便于维护既有接入。它依赖普通控制聊天的 owner，归档后会失去新建能力；不能作为新接入的完整方案。不得自动反归档、锁定或重建控制聊天，也不得向远端模型发送修复提示词来代替 Host 恢复设计。生命周期门槛需真实证明聊天归档后新建仍可用；尚未通过时准确报告设计失败。

已有合法配置/证明时检查是否匹配本机 Host、控制聊天、安装构建和当前 Adapter；不要新建重复控制聊天。首次接入在原桌面准备一条独立控制聊天并保持已加载。需要新建/发送时遵循当前工具的授权要求；用户已明确授权该专用聊天时，使用桌面可用的官方线程工具，否则先说明要创建的聊天及用途。受限或工具不可用时由用户在正常原桌面创建，不绕过访问限制，也不向现有工作聊天发送控制指令。

就绪轮只要求原聊天调用内置 `list_projects`，完成后待命，不执行代码工作。它接收本机 Host 的创建/打开请求，调用原内置 `create_thread` / 导航工具；Host 没有反向调用受保护工具管道的权限。这是模型参与的通道，有初始化轮、延迟和额度开销。

本机私有 `.local/desktop-runtime/controller.json` 使用本机实际值，以权限 0600 写入。首次真实验证使用：

```json
{
  "hostId": "<this-host-id>",
  "threadId": "<this-desktop-controller-thread-id>",
  "desktopVersion": "<actual-installed-build>",
  "validationOnly": true
}
```

按 Host 部署参考保留原环境并重启自己的 Host，加载该配置。测试能力仅供专用测试 App；普通 App 不接受 `validationOnly` 能力。控制聊天关闭或冷启动未加载时，创建/主动打开不可用，需要在正常桌面打开；不自动重启 Codex。

## 本机真实验证

完整 Xcode 可用时读仓库 `docs/runtime.md` 和 `scripts/desktop-controller-ios-acceptance.mjs`，为该电脑准备：有历史的项目、零聊天项目、已加载的专用空闲审批测试聊天、已索引但未加载的专用测试聊天，以及已启动的 iOS 模拟器。真实验收会创建测试聊天、调用模型、上传附件、停止指定测试 Turn、审批测试命令，并重启自己的 Host；不得使用用户正在工作的聊天。

从目标 Host 获取项目/聊天的实际 ID，明确 Host/Thread/Turn 归属。需要确认零聊天项目时核对完整分页与原桌面项目视图，不能凭首页没有记录判断项目为空。审批测试采用原桌面确实会产生请求的权限设置；不把整个桌面降为无审批模式。

按照运行指南设置测试环境，默认选择完整八项测试：

- `testOriginalDesktopProjectCreation`
- `testOriginalDesktopEmptyAndProjectlessCreation`
- `testOriginalDesktopCreationRestartRecovery`
- `testOriginalDesktopHostRestartRecovery`
- `testOriginalDesktopReplyProgressStopAndApproval`
- `testOriginalDesktopPhotoFileUploadAndDownload`
- `testOriginalDesktopConversationSwitchAndDraftIsolation`
- `testOriginalDesktopUnloadedOpenAndReply`

运行 `pnpm test:desktop:controller:ios`。单项通过不能算全部通过；必须核对实际 passed/failed/skipped 和独立审计结果。目标电脑没有 Xcode 时，可由主开发 Mac 的原生 App 直接连接目标 HTTPS Host 完成客户端部分，再由目标电脑独立核对原桌面、owner、目录、工具调用与消息输入；不能把主开发 Mac 的 Host 结果复制给目标。现有脚本包含本地 IPC、日志和服务路径，不能仅替换 endpoint 就将其本地审计声称为远端审计。没有完整本机或联合证据时保留新建关闭，继续完成其他已授权配置并准确报告缺项。

原脚本生成本机 `.local/controller-ios-acceptance/run-<nonce>.json` 和 xcresult，但**没有通用的一键生产证明导出命令**。代理应检查这些原始结果，并按当前 Adapter 的条件生成本机证明；不能只因为 status=passed 就手填六个 true，也不能复制另一台电脑的证明。

只有以下实际证据都满足才生成权限 0600 的 `controller-verification.json`：

| 门槛              | 所需证据                                                    |
| ----------------- | ----------------------------------------------------------- |
| project           | 有历史项目内创建；实际工具 ID、目录、原 owner、首条输入一致 |
| empty-project     | 测试前项目零聊天，首聊归属和原 owner 正确                   |
| projectless       | 明确 projectless；没有继承上一项目，首条正文只提交一次      |
| first-attachments | 原生图像 + TXT、隐藏随机码、下载字节/摘要及图片判断核对通过 |
| owner-controls    | 同一原 owner 的回复、进度、停止后续聊、实际审批均通过       |
| recovery          | App 与 Host 重启均恢复同一操作/聊天，create/首条各一次      |
| unloadedOpen      | 测试前无 owner；原导航加载同一 ID，后续原 owner 实际回复    |

另外确认原桌面/执行进程保持原 PID、原始 `turn.params.input` 中所有文字含 `text_elements` 数组；直接在原桌面打开新聊天核对文字/图片渲染。官方导航返回成功不是视觉通过。过程/原始证据留在本机私有目录。

证明字段由该电脑的实际结果派生：`kind=real-desktop-controller`、hostId、controllerThreadId、desktopVersion、`controllerAdapterDigest()` 的当前摘要、六个 gates、unloadedOpen，以及证据文件与时间。摘要函数来自仓库 Adapter，不重实现 Codex 协议。缺一项则保持 validationOnly，不生成生产通过证明。

生产配置移除 `validationOnly`，添加 `verification` 为本机证明的绝对路径；按原环境重启自己的 Host 后重新探测。必须看到实际 `desktop:creations`、`desktop:creations:projectless`、`desktop:open`，再单独执行 `testOriginalDesktopRegularAppCreation` 验证普通启动 App，并完成下述真实 iPhone 验收。此段仅描述旧兼容 driver 的证明流程；默认独立桥接按正式配置和真实运行时就绪开放能力，完整真实验收仍单列执行。已有匹配生产配置和证据时复用，不为重复接入重建身份或证明。

## 独立验证主动打开

用户明确仅需要主动打开，或完整接入尚在分阶段验证时，可为这台电脑生成 `kind=real-desktop-controller-open` 的专项证明：固定 Host、控制聊天、实际桌面版本和当前进程 Adapter 摘要，包含 `gates.open=true`、`unloadedOpen=true`，并保存测试前无 owner、原导航加载同一 ID、实际回复和原桌面核对证据。`openOnly=true` 配合 `verification` 指向该证明，只启用打开，不启用创建；不能手填摘要或把导航成功单独作为全部通过。默认完整接入仍需继续完成创建，不以 openOnly 结束交付。

需要继续验收创建时，移除 `openOnly`，用 `validationOnly=true` 开放创建测试入口，同时将已匹配的打开证明配置到 `openVerification`。此时正式打开与创建测试可以并存；独立打开证明不能用于生成创建通过。证据过期或 owner 丢失只撤掉受影响入口，不关闭其他聊天的实际 owner 控制。

## iPhone 和第二台电脑

手机 App 安装/签名独立进行；目前 `pnpm build:ios` 构建的是模拟器。优先在用户既有正式 App 和原连接上验收，保存原连接、草稿与版本；没有 App 时再完成签名安装，不为验收直接替换正式 App 或删除其他 App。需要替换测试 Runner 等额外操作时核对现有授权。真机操作实际产生的最后查看聊天和项目缓存变化，与用户连接、草稿保护分开记录。

普通启动的正式 App 不带验证能力或测试专用启动参数。对每台目标电脑至少完成实际新聊天、一次首条输入和真实模型回复，并用相同操作 ID/Thread ID 与该目标原 owner、内置创建工具、项目归属和原始输入独立核对。保留原桌面显示的准确观察；工具策略拒绝时可请求用户人工确认，不换通道绕过。其余手机和跨网络功能按完整清单逐项验收：

1. 查看真实项目，连续切换至少三条聊天，标题与正文目标一致，草稿不串。
2. 项目内新建、零聊天项目首聊、无项目新建；在原桌面打开同一 ID，文字和图片正常渲染。
3. 文字 + 图片 + TXT 上传，模型读取文件中不在正文里的随机码；下载产物核对内容/摘要。
4. 流式回复和工具进度、停止指定 Turn、停止后继续；原任务确实停止且没有重复执行。
5. 实际命令/文件审批与用户问题（仅适配类型），处理后另一端失效；不批准无关用户请求。
6. 切换 Wi-Fi/蜂窝、断开再恢复、App 回前台和自己的 Host 重启；未知结果先查询，不自动重发。
7. 接入第二台实体 Mac 或 Windows，核对不同 Host ID；在两个既有连接分别新建、回复并检查草稿隔离，相同项目名和相似聊天仍发往正确目标电脑。

私有报告按完整清单分别标明网络连接、本机原桌面、iPhone、第二台电脑的本次通过、复用通过、失败和未验收项；未覆盖的已实现能力继续验证。当前没有 APNs/后台传输或公网 Relay，不把电脑任务继续执行描述为手机常驻后台同步。
