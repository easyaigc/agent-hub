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

## 项目唯一完整功能清单（用户明确要求，2026-11-18）

- 整个项目只维护一份完整的当前功能清单：`docs/plans/android-ios-alignment-20261107/progress.md`。该文件同时覆盖 iOS 与 Android，不再分别建立 iOS、Android 或按阶段拆分的当前功能清单；新增功能也在此清单追加，现有功能不得因重组遗漏。
- 清单从用户角度组织：同一功能、同一操作流程只写一次，分别记录 **iOS 实现、iOS 模拟器验收、iOS 真机验收、Android 实现、Android 模拟器验收、Android 真机验收**。平台专属、不适用和暂停必须明确标注。
- 页面只保留用户功能、操作流程及上述双端状态。Android实现必须从实际生产入口、调用链和界面逐项核对，明确写“已实现”“部分实现（具体缺什么）”“未实现（具体缺什么）”“已有代码尚未集成”或“暂停”；不得以“有实现待确认”“是否齐全待确认”等占位结束梳理。已有代码不等于已集成，未验收不等于未开发。不要把提交号、工具修复、执行流水、指纹、工时或复杂过程表堆进功能清单。
- 用户已明确确认 **iOS全部既有功能已经真机验收**，按此登记，不因本轮未重跑或来源未重新查证而回退；模拟器不再单独补验。后续新增或改动只对受影响范围安排适用验收。
- 用户明确约定 **Android早期已通过线上PR合并主线的功能视为已验收**。直接承接该基线，不反复追问、重查其来源，不把共享文件有改动等同于整项失效；只写明确实缺少的开发/集成步骤、主线后的行为改动及对应增量待验操作。“沿用主线”是承接验收结论，不冒称本轮实际运行过模拟器或真机；原始失败和UNKNOWN仍保留后台。
- Android真机验收只用于有真实设备环境差异的步骤，例如系统权限/安全存储、相机/录音、网络/VPN、后台推送、通知/角标、系统分享/文件授权和系统交互。纯界面、状态规则、协议、搜索、排队控制、回答原文、文件解析和额度刷新等功能的真机栏写“不适用”，使用模拟器及相应真实服务验收；一个功能仅部分步骤涉及设备时，写清该部分，不能把所有功能都要求真机重验。
- 每次开发、修复、验收和进度回复先读该清单；取得新证据后在同一次交付中更新对应功能及受影响平台的状态。不得只更新完成的一端、仅追加日志，或新建另一份状态总表；统筹负责人复核后统一写入。新增或改变行为的验收，按适用范围区分辅助检查、模拟器和真机结果，不以编译或fixture冒充真实交互。
- 产品/设计文档、manifest、features JSON和验收档案只保留规格、生成兼容数据、来源、原始证据和历史，不独立维护当前实现/验收状态清单或完成率。详细提交与过程放后台证据，当前功能状态只回填唯一清单。
- 其他工作树如需镜像，应保持同一清单内容并核对实际同步结果，不作为独立维护入口；不假定远端自动生效。更新清单和规则不恢复已暂停的开发、验收或五线调度。

## iOS × EasyWork 专项唯一台账（本轮用户再次明确）

- 本任务仍以 `docs/plans/ios-easywork-alignment-20261112/README.md` 为入口，`status.json` 为唯一可编辑的对齐计划与进度事实源；每次继续先读 `maintenance.md`。此前“历史冻结”说明不能回退本轮人类指示和已回填事实。
- 全项目双端清单保留用户功能索引和既有平台验收结论；本轮 EasyWork 扩展、提交与验收计划引用专项台账，不另维护完成数，也不接管 Android 或恢复 F75/A6。
- F01–F75 保留原分母；新增 F76/F77 在同一 JSON 的扩展记录维护。提交索引与新增计划视图从该 JSON 生成；完成后执行 `render_status.py --write` 和 `--check`。原功能已合PR且范围匹配时复用原验收，只补新增或受影响范围，本轮 iOS验收仍限定模拟器。

## Implementation boundaries

- Host Agent owns computer-local Codex access. Relay must not run Codex or own its login credentials.
- Keep Codex wire types and transport details behind `packages/codex-adapter`. Version-specific generated bindings should be isolated there when introduced.
- Our `packages/protocol` contracts are distinct from Codex JSON-RPC. Domain types currently marked as draft do not imply that endpoints are implemented.
- Every remote command must target a specific host and thread. A read from persisted history must not be presented as evidence that the desktop runtime is connected.
- Do not edit existing Codex history files or launch duplicate execution instances to simulate desktop integration.
- Keep health responses accurate: this bootstrap only advertises `health`. Add capabilities only with their actual implementations.
- Never commit credentials, real conversation exports, user attachments, or machine-local absolute paths. Documentation examples should use placeholders.

## 版本演进与兼容

- 每次升级默认向后兼容，保持已部署旧端、旧协议、已有数据和已授权流程可用；共同结构与语义仍成立时容忍可识别的新版本及新增可选字段，不能只因版本号变化使已有功能突然失效。能力按实际契约独立协商，不要求所有端同步升级，不因新能力缺失关闭无关旧功能。
- 兼容差异留在适配层；命令输入、身份、证书、权限与完整目标校验继续严格执行。保留原配置、草稿、附件、不可变操作及回执，未知结果先核对，不因升级或恢复自动重放输入。真实错误有界结束等待并保留恢复入口。
- 仅在设计或语义确实冲突，或兼容存在经分析难以合理承担的复杂度、维护成本或正确性问题时采用不兼容变更；记录影响、兼容替代方案及不采用原因，提供迁移、数据保护和回退安排，切换前说明，不能静默破坏已有能力。
- 按影响范围回归旧／新组合、升级与重启恢复、核心既有功能和输入去重；源码、辅助检查、真实验收及部署分别留证。完整定义见 [全局设计规范：版本演进与兼容](docs/design/compatibility.md)，不以规则写入冒充所有版本已验收。

## Checks

- Run `pnpm check` for relevant TypeScript, workspace or tooling changes.
- Run `pnpm build:ios` for relevant native project changes when Xcode is available. Report a missing compiler or SDK accurately.
- Prefer meaningful boundary or integration checks. Do not add tests that merely repeat static type declarations.
- Update architecture, protocol or feasibility documents when their corresponding behavior changes. Preserve the distinction between proposed, implemented and verified capabilities.

<!-- test-acceptance-content-authorization:v1:start -->

## 测试验收包含必要操作与测试内容处理授权

- 用户要求测试、复现、联调或验收，即包含在已授权目标、账号、数据和处理范围内完成必要操作的授权。可准备并使用任务自有、合成或用户已提供作测试的文字、普通附件、图片、音频（含 WAV）和视频，通过指定 App、Host、服务及该功能已明确采用的处理方完成上传、识别、转写或分析；不因样本类型、格式或单条内容变化而重复询问。原任务的仅模拟器、本地处理、不采集真实麦克风等限制继续遵守。
- 必要登录、已授权目标的配对、系统权限申请、内容处理同意提示及正常界面操作，均按原测试意图执行，不再逐项请求确认。不得扩大到未经授权的目标、账号、处理方、个人资料采集、付费或生产操作；确有范围缺失或必须本人完成的身份验证时，只请求所缺的最小动作。
- 分派测试 Agent 或专用测试会话时，一并传递可核实的人类授权、准确目标、样本来源、用途及范围限制，执行方不得再次索要相同授权。恢复前核原请求、回执和实际状态；结果未知时查询同一原操作，不重复上传、签发或执行。
- 本规则不改变工具权限，也不能绕过自动审批或其他强制确认。遇到拒绝先核对授权与证据是否完整传递；有新的人类指示或补齐证据后，通过正式审批入口继续。仍被拒绝时保留具体动作、理由和未执行状态，继续不受影响的工作，不换入口绕过、不修改审批设置；只有确实仍缺的人类动作才再次提出，并说明其明确来源。

<!-- test-acceptance-content-authorization:v1:end -->

## iOS 对接 EasyWork 的唯一计划与状态

- 按用户 2026-11-12 的明确要求，`docs/plans/ios-easywork-alignment-20261112/README.md` 是本任务唯一计划入口，`status.json` 是唯一可编辑进度事实源；`progress.md`、`checklist.md` 从其生成，不独立维护完成数。
- 每次继续本任务先读取该计划及 `maintenance.md`，分别更新开发、验收、交付和执行状态，附准确源码/运行包/场景证据、阻塞和下一步。不得用“开放/进行中”混称未开发、未验或历史通过，也不将旧证据直接升级为当前通过。
- 旧 EasyWork 计划、矩阵和阶段档案只保留历史事实与证据；新增结果回填唯一台账。更新后执行本目录 `render_status.py --write` 和 `--check`。全局F/UI/协议定义仍按原规范维护，Android独立进度和既有暂停范围不由本计划接管。

## 通用 Agent 管理与真实 DEV 验收

- iOS × EasyWork 对齐必须遵守 [统一 Host 协议与客户端适配规范](docs/design/unified-host-protocol.md)。以已实现的 Codex Host 领域契约为基准，EasyWork Host／Adapter 映射上游差异，App 复用公共状态和页面；不得为每个 Agent 复制业务协议或为使用草案类型新增第三套接口。相关改动先列实际调用链、能力／错误／回执／恢复映射和兼容例外，公共行为不按客户端名称分叉；操作分类须有共同来源或共同向量校验，静态接口与 Schema 一致不能单独证明生产协议统一。当前进度仍只回填唯一台账，F75／A6 暂停保持。

- 按用户 2026-10-14 的明确要求，Codex 连接只校验机器运行目标（Agent、Host、workspace、runtime）、证书与设备密钥，不以 Codex 登录账号变化作为连接、缓存、草稿、阅读或命令拒绝条件。旧账号字段仅保留为历史元数据；其他 Agent 的账号协议、App 人类登录、设备会话与项目成员权限保持各自约束。已提交输入的原目标、正文、操作 ID 和指纹仍不可变，结果未知不能因身份策略变化而重放。

- 领域协议、Agent 适配、传输、认证、会话恢复和界面分别定义窄接口；公共层依赖版本化领域模型及能力描述，客户端名称仅用于展示。
- 新增 Agent 或传输方式通过注册适配器扩展；上游 wire、地址格式与认证差异留在适配层，业务规则不进入传输层。
- 目标身份独立于连接路径；缓存、草稿、操作回执、事件与审批按完整目标隔离。跨通道重试复用稳定操作 ID，结果不确定时先核对，不重复执行。
- 通用通信使用具名操作和严格输入契约，不开放任意 Electron IPC、shell 或方法名透传。Host 承担执行与权限，Relay 只处理身份、绑定、路由和密文。
- 功能交付按上述唯一清单确定适用验收：远端执行链使用真实 Electron、原生 App、实际模型任务及桌面独立核对；无真机环境差异时原生 App 可在模拟器验收，纯本地功能不额外消耗模型任务。仅设备相关步骤要求真机，既有 iOS 用户确认及 Android 主线验收结论直接承接，新增/改动仅补受影响范围。编译、单元测试、fixture 分别记为辅助证据，不替代适用的真实交互。
- 验收按场景和实现/环境指纹保存结果，区分本次通过、复用通过、失败和未验收；保护凭据，保留既有未提交工作。设计文档分别注明拟实现、已实现和已验证。

## Host 与 SKILL 的唯一标准

- 已授权任务的消息调度由模型根据任务优先级、时效、依赖和目标实际运行状态自主决定，可显式选择 `next-turn`、`queue`、`steer` 或提升原队列项执行；不固定要求忙时排队，不以用户另行说“插队／立即追加”为前置条件，也不因发送模式追加确认。即时追加与队列提升仍核对能力、准确原 owner、当前 Turn 及原消息 ID，保留不可变意图与回执；消息调度不等于强制终止执行中的工具。
- 协调正文含来源头最多 8 KiB UTF-8，建议 2 KiB 内；只写本次变化、任务、验收与文件引用，不重复历史。Base64、data URI、大文件、完整补丁和长日志不得通过消息或工具输出传递，使用独立文件通道并引用传输 ID、实际路径、大小和 SHA-256；正式程序仍走既有统一分发。超限直接拒绝，不自动截断、分片或编码绕过。工具每次输出最多 16 KiB，目录按需分页，回执仅保留状态和标识；旧操作仍保持不可变、可查询且不重放。
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

## 原生双端一致性与功能维护

- iOS 与 Android 的完整功能及当前实现、模拟器/真机验收统一维护在上述唯一清单；旧分期计划只保留历史。技术边界仍见 `docs/adr/0002-native-mobile-shared-core.md`，不能用早期planned字段代替当前源码和验收事实。
- 实现、模拟器和真机验收按端分别判断；交付、阻塞、指纹和详细执行过程在后台证据中记录，不给前台功能清单增加复杂过程表。实际源码/原始证据与清单冲突时先核实并更正，不能用台账覆盖事实。
- 产品 manifest、features JSON、验收矩阵及旧进度中的实现/验证字段保留为来源或兼容摘要，不独立决定当前完成率；规格、共同断言、来源和UI/S关联仍按各自规范维护。禁止新增竞争的当前状态总表；暂停/不适用/Host依赖不计通过。其他工作树和远端是否取得更新按实际同步结果记录，不假定自动生效。
- 任一端新增、优化或修复产品功能时，先更新唯一功能清单的同一功能及双端影响；`docs/product/mobile-parity.md`仅维护功能ID、共同断言、规范来源与验收关联，不独立维护当前实施/验收状态。平台专属变化说明另一端是否受影响，不能删除未验收项。
- 产品 UI 遵照现有 iOS，并同步维护 `docs/design/mobile-parity.md` 的页面、状态、设计 token 和文案；系统 UI／通知／角标差异明确登记，不能静默降低一致性要求。
- 公共规则计划由 KMP 复用，平台能力通过窄接口提供。迁移后删除对应旧规则的生产路径，不让对照实现重复发送；版本化 Schema、跨语言向量及双端原生验收分别执行。
- 原始验收步骤与结果在 `docs/acceptance/mobile-parity.md`等证据档案保存；当前结论只回填唯一清单的对应平台/模拟器/真机格。辅助、历史、复用、失败与未验范围必须区分，不将iOS/macOS/fixture结果代作Android/Windows/真机通过。
- JS 工作空间继续使用 pnpm；Android／KMP 使用锁定 Gradle Wrapper，根 pnpm 命令调用原生工具。构建、一致性和 CI 入口以当前 `package.json`、Wrapper 与工作流源码为准；实际执行结果按权威计划关联指纹登记，不把拟建命令写成已执行。

<!-- BEGIN:turborepo-agent-rules -->

# This is NOT the Turborepo you know

Turborepo configuration, task behavior, and CLI commands can vary between installed versions and may differ from your training data. Resolve the `turbo` package from this file's directory or relevant workspace; in monorepos, it may not be visible from the repository root. For example, run `node -p "require.resolve('turbo/package.json')"` from a workspace that depends on `turbo`.

Read `docs/README.md` inside that installed package first, then read the relevant pages from its `docs/` directory before changing Turborepo configuration or commands. Heed deprecation notices. These bundled docs match the installed package version and are available without network access.

This block is written and re-added by `turbo` before repository-scoped commands when an AI agent is detected. In the Turborepo source repository, its template is defined in `crates/turborepo-cli/src/cli/agent_guidance.rs`. Removing the managed block while updates are enabled means a later qualifying invocation will add it again. Set `"agentGuidance": false` in the root `turbo.json` or `turbo.jsonc` to opt out; this does not remove an existing block. Keep the block committed with your work to avoid an uncommitted change on the next agent invocation.
<!-- END:turborepo-agent-rules -->
