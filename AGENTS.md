# Repository guidance

## Context

- This repository is an iOS-first multi-computer Codex manager. Read `README.md`, `docs/architecture.md`, and `docs/feasibility.md` before changing integration boundaries.
- Use pnpm exclusively. Keep `pnpm-lock.yaml` in sync with dependency changes. Do not add npm, Yarn, or Bun lockfiles.
- Node services use ESM and workspace dependencies. Native iOS stays in `apps/ios` and builds through Xcode.

## 源码推送、PR 与主线同步

- Gitea 的 `BCAI/agent-hub-app` 是开发主仓库，`origin` 指向该仓库，主分支为 `main`。默认推送、开发分支、PR 创建、审查及合并全部在 Gitea 完成；不能因存在 `github` 远端或 GitHub 的 Compare & pull request 提示而改变目标。
- GitHub 的 `easyaigc/agent-hub` 是公开同步仓库，由现有 Gitea 工作流将已合并的 `main` 同步到 GitHub `main`，保留独立公开历史。不得将开发／PR 分支推到 GitHub 或在 GitHub 创建开发 PR，也不将本地主线直接推到 GitHub 代替同步。
- 推送及创建 PR 前核对实际远端、源分支和目标主线。本仓库默认配置为 `remote.pushDefault=origin`、`branch.main.pushRemote=origin`，`main` 跟踪 `origin/main`；使用 Gitea 工具或 API 创建 PR，不使用 GitHub PR 接口。
- 用户另行明确指定的公开发布／分发任务仅按其授权范围执行，不改变默认 PR 与合并目标；既有分发分支先核对任务依赖，不擅自删除。

## Implementation boundaries

- Host Agent owns computer-local Codex access. Relay must not run Codex or own its login credentials.
- Keep Codex wire types and transport details behind `packages/codex-adapter`. Version-specific generated bindings should be isolated there when introduced.
- Our `packages/protocol` contracts are distinct from Codex JSON-RPC. Domain types currently marked as draft do not imply that endpoints are implemented.
- Every remote command must target a specific host and thread. A read from persisted history must not be presented as evidence that the desktop runtime is connected.
- Do not edit existing Codex history files or launch duplicate execution instances to simulate desktop integration.
- Keep health responses accurate: this bootstrap only advertises `health`. Add capabilities only with their actual implementations.
- Never commit credentials, real conversation exports, user attachments, or machine-local absolute paths. Documentation examples should use placeholders.

## Checks

- Run `pnpm check` for relevant TypeScript, workspace or tooling changes.
- Run `pnpm build:ios` for relevant native project changes when Xcode is available. Report a missing compiler or SDK accurately.
- Prefer meaningful boundary or integration checks. Do not add tests that merely repeat static type declarations.
- Update architecture, protocol or feasibility documents when their corresponding behavior changes. Preserve the distinction between proposed, implemented and verified capabilities.

## 通用 Agent 管理与真实 DEV 验收

- 按用户 2026-10-14 的明确要求，Codex 连接只校验机器运行目标（Agent、Host、workspace、runtime）、证书与设备密钥，不以 Codex 登录账号变化作为连接、缓存、草稿、阅读或命令拒绝条件。旧账号字段仅保留为历史元数据；其他 Agent 的账号协议、App 人类登录、设备会话与项目成员权限保持各自约束。已提交输入的原目标、正文、操作 ID 和指纹仍不可变，结果未知不能因身份策略变化而重放。

- 领域协议、Agent 适配、传输、认证、会话恢复和界面分别定义窄接口；公共层依赖版本化领域模型及能力描述，客户端名称仅用于展示。
- 新增 Agent 或传输方式通过注册适配器扩展；上游 wire、地址格式与认证差异留在适配层，业务规则不进入传输层。
- 目标身份独立于连接路径；缓存、草稿、操作回执、事件与审批按完整目标隔离。跨通道重试复用稳定操作 ID，结果不确定时先核对，不重复执行。
- 通用通信使用具名操作和严格输入契约，不开放任意 Electron IPC、shell 或方法名透传。Host 承担执行与权限，Relay 只处理身份、绑定、路由和密文。
- 功能交付必须完成本地真实 DEV 验收：真实 Electron、原生手机 App、真实模型任务及桌面独立核对。编译、单元测试、fixture 分别记为辅助证据。
- 验收按场景和实现/环境指纹保存结果，区分本次通过、复用通过、失败和未验收；保护凭据，保留既有未提交工作。设计文档分别注明拟实现、已实现和已验证。

## Host 与 SKILL 的唯一标准

- 用户明确指定其他电脑并要求排查、修复、部署、联调或验收其 Host／手机连接等问题时，任务本身已授权联系这些电脑上的 Codex，直接完成同一范围内的诊断、修复、跟进、统一分发和验收，不再追加跨电脑沟通确认。仍按全局规则核对目标身份、新建专用对话并保留操作回执；不扩大到未指定电脑、其他客户端或无关任务。
- 主开发电脑上的本仓库是唯一源码标准：`skills/codex-host-connect`、`apps/host-agent`、`packages/codex-adapter` 及其桥接、服务和分发脚本统一维护。
- Host、Adapter、桥接工具或 SKILL 的修复必须先在本仓库修改、检查和构建，再从本机生成同一接入包分发到目标 macOS 与 Windows；目标电脑只部署统一产物和维护各自私有配置，不独立维护源码分支。
- 目标 Windows 或 Mac 已有修复必须先导出差异，在本仓库审查合并并完成回归，再统一分发。保留本机和远端既有未提交工作，不以整目录覆盖代替差异合并。
- 升级保留各电脑的 Host ID、密钥、授权目录、服务路径、端口及控制聊天。身份、令牌、运行状态和真实验收证明不得跨电脑复制；代码更新后的证明按目标环境和实际 Adapter 指纹重新核对。
- 部署记录必须绑定本机生成的接入包 SHA-256，并分别记录每台目标电脑的部署与真实验收结果。流程详见 `docs/development.md` 的统一分发规范。
- 接入默认配置并验证当前已实现的完整原桌面与手机功能，包括新建、无项目新建和主动打开；只有用户明确缩小范围时才交付子集。已实现但未声明的能力先定位修复，缺证据时准确报告部分完成，不能省略失败项来宣称全功能通过。SKILL/文档单独同步保持已验证程序、私有配置与证明，按原指纹复用有效结果。

## 桥接生命周期与创建恢复

- Host／桥接服务独立于普通聊天的归档、关闭和模型调度；各目标 Codex 仅负责安装、运行和具名验收。产品恢复策略统一在本仓库实现，不靠给远端聊天发送修复指令。当前控制聊天 driver 是兼容实现，尚不满足此门槛；反归档或重建聊天不是根治证据。
- 可编辑本地草稿与不可变已提交操作分离。未知结果不得自动重放；明确结束等待只解除当前等待，保留同一操作 ID 的可查询回执和原附件，不宣称取消远端。晚到结果不覆盖新草稿、操作或目标。
- 正式交付增加归档安装／历史控制聊天后仍可新建、Host 重启后自动恢复、未知结果脱离等待及无重复输入的真实手机／桌面核对；历史功能通过不能代替生命周期验收。

- 创建边界允许 Host 通过官方 API 准备零 Turn 空会话元数据，原桌面独占执行；准备进程未确认退出时禁止接管。准备器使用独立方法白名单，历史 reader 保持只读，禁止初始化模型轮、普通控制聊天依赖和未知输入重放。

- 用户要求独立工作树时，从已授权开发现场派生并保存输入摘要，后续开发和真实 DEV 使用该工作树及独立依赖、构建和验收目录。并行现场的变化不进入通过指纹，共享正式服务不充当隔离验收进程。

## 本机 iPhone 的 Wi-Fi 真机调试

- 按人类最新指示，真机验收使用 Xcode／CoreDevice 与 XCTest／XCUIAutomation；CUA 不作为前置条件。Xcode 路径的设备连接、签名、测试运行器和实际执行结果分别核对，不把 CUA 未提供直接判定为 Xcode 真机验收不可行。
- 本项目当前 iOS 真机验收使用与本机 Xcode／CoreDevice 已配对的物理 iPhone 15 Pro Max，通过 Wi-Fi 无线调试；不依赖 USB 数据线。具体 UDID、CoreDevice 标识和候选指纹以本机私有验收记录为准，不把模拟器或其他电脑的设备作为本机真机来源。
- 用户已明确确认这台 iPhone 始终保持解锁。不得仅因无线设备预览变化、短暂不同步或操作反馈不一致，就认定手机切到了其他 App、归因用户并发操作，或反复要求用户解锁、拔插线、确认手机就绪。预览异常先核对准确设备、连接状态、原生操作回执与目标 bundle；未核实的现象按预览／目标状态待确认记录。
- 使用 Apple Device Hub 的物理 View Screen 时，先按准确设备和独立验收 bundle 激活已安装 App，再刷新预览确认目标界面。普通恢复直接切回目标后继续已授权场景，无需重新配对、重装或重建。只有工具明确返回锁定、断连、授权拒绝等阻塞且本机无法恢复时，才依据实际回执说明所需人类动作。
- 三个 App 的展示名称可能相同，不凭名称或图标判断身份；只操作独立验收版，保留正式版、原 DEV 版及其数据。遇到目标身份确实无法确认时先停止输入核实，不触碰其他 App 或通知；不得把预览画面当成跨 App 数据迁移或会话连续性的证明，不重放原业务操作。A6 继续保持人类暂停。

## 原生双端一致性与 Android 开发

- Android 完整版本按 `docs/plans/android-native.md` 分阶段实现；技术边界见 `docs/adr/0002-native-mobile-shared-core.md`。当前文档为计划，不能宣称 Android 或 KMP 已实现。
- 任一端新增、优化或修复产品功能时，同步更新 `docs/product/mobile-parity.md` 的功能 ID、共同断言、双端影响、实施状态与验收关联。平台专属变化说明另一端是否受影响，不能只维护已完成端或删除未验收项。
- 产品 UI 遵照现有 iOS，并同步维护 `docs/design/mobile-parity.md` 的页面、状态、设计 token 和文案；系统 UI／通知／角标差异明确登记，不能静默降低一致性要求。
- 公共规则计划由 KMP 复用，平台能力通过窄接口提供。迁移后删除对应旧规则的生产路径，不让对照实现重复发送；版本化 Schema、跨语言向量及双端原生验收分别执行。
- 真实结果按 `docs/acceptance/mobile-parity.md` 记录，区分实现、辅助检查、本次通过、匹配复用、失败与未验收。现有 iOS、macOS 或 fixture 证据不能代替 Android、Windows 或真机通过。
- JS 工作空间继续使用 pnpm；Android／KMP 使用锁定 Gradle Wrapper，根 pnpm 命令调用原生工具。Android 构建、一致性命令与 CI 尚待计划阶段建立，不把拟建命令列为当前可用。

<!-- BEGIN:turborepo-agent-rules -->

# This is NOT the Turborepo you know

Turborepo configuration, task behavior, and CLI commands can vary between installed versions and may differ from your training data. Resolve the `turbo` package from this file's directory or relevant workspace; in monorepos, it may not be visible from the repository root. For example, run `node -p "require.resolve('turbo/package.json')"` from a workspace that depends on `turbo`.

Read `docs/README.md` inside that installed package first, then read the relevant pages from its `docs/` directory before changing Turborepo configuration or commands. Heed deprecation notices. These bundled docs match the installed package version and are available without network access.

This block is written and re-added by `turbo` before repository-scoped commands when an AI agent is detected. In the Turborepo source repository, its template is defined in `crates/turborepo-cli/src/cli/agent_guidance.rs`. Removing the managed block while updates are enabled means a later qualifying invocation will add it again. Set `"agentGuidance": false` in the root `turbo.json` or `turbo.jsonc` to opt out; this does not remove an existing block. Keep the block committed with your work to avoid an uncommitted change on the next agent invocation.
<!-- END:turborepo-agent-rules -->
