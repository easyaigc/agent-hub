# Agent Hub

面向 iOS 的多电脑、多 Agent 远程工作台，已接入 Codex 与 EasyWork，可通过注册适配器继续扩展。手机负责查看项目与对话、回复和审批，任务在所选电脑上执行。App 显示名称为 `Agent Hub`（原 Codex+ / Codex Manage），正式设备构建使用 `com.easyai.agenthub`，Apple 注册与真机迁移结果另行记录。仓库、内部包名与工程名沿用现有命名，图标资源状态见 [图标设计](docs/design/app-icon.md)。

界面支持简体中文与 English，默认跟随系统，也可在“设置 → 语言”立即切换并保存选择。连接、对话、任务管理和通知共用中英文资源；用户内容和已提交操作保持原样。开发与验证方式见 [界面语言](docs/development.md#ios-中英文文案)。

完整原生 Android 版本已按[九阶段计划](docs/plans/android-native.md)开始执行，目标是功能、产品 UI 与当前 iOS 一致；采用 Kotlin Multiplatform 共享业务核心，保留 SwiftUI 与 Android Compose 原生界面。A0 已完成 14 组／48 次适用原生基线验收与 89 张合成截图核对，A1 已建立 Android／KMP 工程并接入双端同步规则，API 26／37 各 5 项和 iOS 14 组／48 次回归均通过，共享向量与输入性能完成验收；A2 已完成协议与公共状态迁移；A3 已完成原生 UI 与读取门槛，iOS 55 次、Android 188 次通过并核对 322 张截图，阶段代码已推送并核对远端。执行、EasyWork、系统、通知与发布继续按 A4–A8 实施。[阶段记录](docs/acceptance/android-stages.md)保存模拟器门槛与结果；[双端功能与验收清单](docs/product/mobile-parity.md)作为两端新增、优化与修复的共同维护入口。

已实现 **紧凑原生 iOS UI 与真实运行时链路**：多客户端切换、项目与对话、新建与回复、图片/文件上传、语音转文字草稿、流式消息、工具进度和 Diff、文件下载与预览、统一任务操作按钮、审批、缓存与重连。原桌面模式由真实 owner 执行，正常启动的控制聊天入口已接入项目内新建、空项目首聊、无项目新建及主动打开未加载会话。实现与真实验收范围见 [运行指南](docs/runtime.md) 和 本机验收（本地文件 `docs/acceptance/ios-local.md`）。

独立创建桥接已实现：Host 只通过官方 API 准备零 Turn 空会话，确认准备进程正常退出后，原桌面接管准确 ID 并独占执行真实首条正文、图片和文件。操作 ID 与实际返回 ID 持久绑定；已尝试输入的不确定结果只查询，不重复执行。本机项目、无项目、原内置工具及回复已通过，完整回归、远端和正式 iPhone 仍在验收。见 [独立桥接](skills/codex-host-connect/references/prepared-bridge.md)。

旧控制聊天 driver 保留为兼容实现，其生命周期存在归档依赖，旧场景证明不能作为新 driver 的验收证据。新桥接无需普通控制聊天；官方图标、Dock 与 Spotlight 启动保持原样，不修改官方历史、安装包、启动配置或受保护工具管道。正式新建和主动打开按本机私有配置、实际身份／桌面版本及真实运行时就绪默认开放；安装不要求 Xcode、控制聊天或验收报告。真实 DEV 验收继续实际执行并单列证据，报告不作为独立桥接的能力开关。

## 项目结构

```text
apps/
  ios/                SwiftUI iPhone 应用、原生 Xcode 工程
  host-agent/         每台电脑的连接程序
  relay/              跨网络连接与通知中继
packages/
  protocol/           应用协议版本、健康检查与领域契约
  codex-adapter/      只读/托管/共享运行时适配与流式事件
  service-runtime/    Node.js 服务启动与健康检查
tooling/
  typescript/         共享 TypeScript 编译配置
docs/                 架构、产品范围、协议、PoC 与开发文档
scripts/              仓库验证脚本
```

采用 pnpm Workspaces + Turborepo；服务运行时为 Node.js，iOS 编译使用 Xcode。仓库选择 pnpm 的理由记录在 [ADR-001](docs/adr/0001-monorepo-toolchain.md)。

## 本地开发

要求 Node.js 24.x、pnpm 11.19.0；iOS 开发另需完整 Xcode 和 iOS Simulator SDK。

```bash
git clone https://github.com/easyaigc/agent-hub.git
cd agent-hub
pnpm install
pnpm dev
```

服务默认仅监听回环地址：

| 服务       | 地址                            | 当前功能     |
| ---------- | ------------------------------- | ------------ |
| Relay      | `http://127.0.0.1:8787/healthz` | 进程健康检查 |
| Host Agent | `http://127.0.0.1:8788/healthz` | 进程健康检查 |

运行验证：

```bash
pnpm build
pnpm check
pnpm build:ios
pnpm check:history
pnpm test:ios
```

`pnpm check` 包含格式、ESLint、TypeScript 检查、依赖顺序构建、服务进程 smoke 检查以及历史/运行时协议边界检查。`pnpm build` 仅构建 TypeScript 工作空间；原生 iOS 使用独立命令，避免 Linux 开发机依赖 Xcode。

打开 `apps/ios/CodexManage.xcodeproj`，选择 `CodexManage` Scheme 与 iPhone 模拟器即可运行原生应用。在“设置 → 测试 → 界面示例”中开启或关闭本地示例；关闭后隐藏示例客户端并保留真实连接与草稿。真实本机历史连接另开终端运行：

```bash
pnpm dev:history
```

在模拟器中添加客户端，地址填写 `http://127.0.0.1:8788`。此连接明确显示“历史连接”，支持真实浏览与草稿，禁止远程新建、回复和附件上传。默认服务仍只提供健康检查；Relay 尚未转发内容。iPhone 真机不能用该地址连接电脑。

## 文档入口

每台 Mac 的接入流程已封装为 [codex-host-connect Skill](skills/codex-host-connect/SKILL.md)，包括 Tailscale 安装/登录/HTTPS、Host Agent 部署、登录自启、独立桥接配置与正式能力核对；逐机真实验收另列结果。配置结束必须向请求接入的用户交付手机手动添加所需的电脑名称、实际 HTTPS 地址、私有密钥文件位置和 Host ID。它提供安装流程与只读检查工具，目标安装不以手机在场、Xcode 或测试报告为前提；原桌面、iPhone 跨网络和两台实体电脑仍须在相应环境实际验证，不能把安装完成说成实测通过。

将同一版本仓库放到目标 Mac 后，首次安装 Skill（目标目录必须不存在；已安装时比较并更新，不将同名目录嵌套进去）：

```bash
mkdir -p "${CODEX_HOME:-$HOME/.codex}/skills"
cp -R skills/codex-host-connect "${CODEX_HOME:-$HOME/.codex}/skills/codex-host-connect"
```

在目标电脑的 Codex 中调用：

> 使用 $codex-host-connect，将这台 Mac 接入我的 Agent Hub 手机 App。仓库在 /path/to/codex-manage，授权项目根目录为 /path/to/projects，电脑名称为“办公 Mac”。配置 Tailscale 和 Host Agent，完成后输出电脑名称、实际 HTTPS 地址、私有密钥文件位置、Host ID，并报告实际通过、未通过和需要我完成的动作。

完整接入默认配置已实现平台的持久 Trace，用于让手机读取桌面 Codex 已生成的下一步建议。手机可查看全文、采用到草稿和撤销，不自动发送；普通任务进度、完成和错误反馈仍走原运行时连接。安装时说明本机记录范围、核对配置就绪，再交付正常重开的动作，不重复索取记录授权；用户明确关闭或选择本次诊断时保留。原始记录默认在本机私有目录，macOS 登录环境也可能由同用户后续 Codex CLI 继承。用途、推荐方案和当前平台范围见 [Trace 安装说明](skills/codex-host-connect/references/desktop-suggestions.md)。

在 macOS 构建电脑执行 `pnpm package:connect`，生成完整接入包 `.local/skill-distribution/codex-host-connect-complete.zip` 和 SHA-256 文件。包内 Skill 的 `assets/codex-manage` 附带已编译 Host Agent、运行依赖、完整源码和 iOS 工程，其他 Codex 无需另找仓库即可部署受支持的 Mac，或按 [Windows 接入](skills/codex-host-connect/references/windows.md) 部署实验性 Windows 原桌面桥接。Windows 程序已实现，已有聊天按实际 owner 开放回复、停止和审批，不以专用验收聊天或报告作为启用条件；默认同时配置新建、无项目新建与主动打开，按本机真实运行时就绪开放，实际效果另行验收并单独报告。Linux 尚未实现。目标电脑仍需 Node.js 24.x、pnpm、原 Codex 桌面和 Tailscale；包不包含这些第三方软件或手机 App 签名安装。

本主开发仓库的 SKILL、Host 和桥接工具是唯一标准。远端已有改动先回收合并到本机，再检查、构建并将同一包分发给各电脑；部署副本不独立修改源码。见 [统一分发规范](docs/development.md#host桥接工具与-skill-的统一分发)。

只复制仓库中的 Skill 目录仍是精简版；跨电脑接入应优先交付上述完整包。不要复制 `.local`、令牌或本机真实验收记录。`pnpm check:skill` 验证检查工具的鉴权、身份、来源、重定向与输出边界，不调用模型。

- [文件互传](docs/file-transfers.md)：侧栏电脑级入口，手机向电脑独立上传、固定接收目录与结果恢复。
- [完整运行时连接](docs/runtime.md)：真实发送、附件、事件、授权、HTTPS 与接入模式。
- [架构设计](docs/architecture.md)：组件职责、运行时接入、状态同步、附件与安全边界。
- [MVP 范围](docs/product/mvp.md)：用户流程、范围和里程碑。
- [iOS 初版 UI / UX](docs/design/ui-ux-v1.md)：ChatGPT APP 风格侧栏与聊天主界面、客户端切换、新建和回复。
- [应用协议](docs/protocol.md)：已实现接口与未来领域契约。
- [可行性验证与 PoC](docs/feasibility.md)：已验证证据、复现路径和 Go/No-Go 标准。
- [开发指南](docs/development.md)：命令、环境、iOS 构建和 CI。
- 本机 iOS 验收（本地文件 `docs/acceptance/ios-local.md`）：原生交互、真实历史浏览与未完成范围。
- [技术决策记录](docs/adr/0001-monorepo-toolchain.md)：Monorepo 与 pnpm 的选型。
- [Android 开发计划](docs/plans/android-native.md)：完整功能分阶段交付、复用边界、通知后端与发布门槛。
- [双端功能清单](docs/product/mobile-parity.md)、[UI 规格](docs/design/mobile-parity.md)与[验收规范](docs/acceptance/mobile-parity.md)：功能 ID、页面状态和逐平台证据。
- [原生双端与共享核心决策](docs/adr/0002-native-mobile-shared-core.md)：KMP 选型、iOS 渐进迁移与平台边界。

真实运行模式的启动、HTTPS 配置、Keychain、附件和故障恢复详见 [完整运行时连接](docs/runtime.md)。原桌面接入的适用版本与验收记录见运行指南；两台物理电脑/iPhone 仍是独立产品验收门槛。

```bash
# 需已安装并登录 Codex；仅管理本服务创建的会话
pnpm dev:runtime
# 真实原生 UI 与混合附件验收，会调用模型
DEVELOPER_DIR=/Applications/Xcode.app/Contents/Developer pnpm test:live:ios
# 真实空会话重启及附件恢复验收，会调用模型
pnpm test:live:runtime
```

任务通知、分类设置、原生铃声、后台推送和安全迁移的配置与实际覆盖见 [通知指南](docs/notifications.md)。

## 开源许可

本项目采用 [MIT License](LICENSE)。公开仓库从已提交源码快照开始；本地配置、凭据、真实聊天与私有验收资料不包含在发布内容中。
