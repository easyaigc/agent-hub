# Codex 与 EasyWork 的 Host 权限接管验收

2026-10-12，任务 `wechat-host-permission-takeover-20261012-01a12e1a`。人类明确确认使用 `codex-host-connect` 实际入口：独立 Host 状态经具名创建／打开和 HTTP 输入连接原桌面 owner。当前目录没有名为 `connect_host` 的额外工具，不把它描述为已存在的工具。保持原模型及 xhigh，不改其他任务、电脑身份、密钥或官方桌面生命周期。

## 权限权威与实际执行

Codex 权限以当前原 owner 的有效策略和目标电脑配置为准；显式请求绑定到实际 Turn，传递具体 sandboxPolicy、approvalPolicy 和 approvalsReviewer。默认跟随电脑配置；明确低权限不能被缺省完全访问覆盖。展示读取实际 owner 事实，不把请求中的名称当成执行证明。

Desktop 微信候选在没有助手或助手没有权限选择时默认 allow_all；助手显式的两种确认模式保留。Runtime 在执行前把实际模式绑定到 Thread／Run，并以 run_started 披露会话或单次作用域。单次 run 覆盖原先没有被真正子运行继承：完全允许会退回会话确认模式，显式确认也会退回会话完全允许。M4 在 Desktop 的独立工作树补齐同一 Thread／准确 parentRunId 的继承；嵌套子运行保留单次隔离，不改 UI 会话偏好，跨 Thread 父运行被拒绝。这是 Desktop 的组装／授权器修复，不虚称由 Codex Host ZIP 部署该代码。

候选原文 90,695 UTF-8 bytes，SHA256 `d6741eb350f23a9181bbc83ee0bdd8137291d85f768b1e0ff5d17ef63efeb726`，基线 `21d25c3c1269719c6eae6c9586f3a433e73efbc6`。独立应用候选与补齐差异，未覆盖规范 Desktop 根的既有 WIP，也未开发虚拟助手 UI／模型／DB 契约。

## M4 真实原 owner 结果

真实 Codex 桌面版本 26.1007.30912，冻结 Host 源码基线 `7d1e62f868399acced21464ca9e8c4e7d6eb8b39`。采用独立状态和随机回环端口、专用原桌面 Thread、工作区外本次临时文件；官方原桌面与其 app-server PID 保持。零 Turn 元数据准备确认退出后才由原 owner 执行；没有初始化模型任务或重复输入。

| 场景                | 本次结果与独立核对                                                                                           |
| ------------------- | ------------------------------------------------------------------------------------------------------------ |
| 缺省完全访问根运行  | dangerFullAccess / never，真实终端写入及读取工作区外文件，exit 0，没有审批                                   |
| 显式只读            | readOnly / on-request，真实写入返回 EPERM / exit 1，文件保持原值，没有升级权限                               |
| 完全访问子运行      | 准确父／子 Thread 关系，子实际 Turn 为 danger-full-access / never，实际写入及读取 exit 0；模型与推理档位继承 |
| 后续回复            | 默认仍实际完全访问，真实工作区外写入并独立读回，无审批                                                       |
| Host 状态恢复后接续 | 仅重启本次独立 Host，具名打开同一 Thread；真实工作区外写入并读回，无审批                                     |

根和子实际执行均为 gpt-6.1-sol / xhigh。Host 外层权限事实、原 owner Turn 参数、持久 turn_context、真实工具输出及文件内容分别核对。子 Thread 的 fork 历史包含父运行旧只读上下文，按准确父／子 Turn ID 排除后才判断子实际策略。

首个验收器只识别 commandExecution，漏掉只读轮持久保存的 custom exec 调用／输出，导致断言失败。保留原失败记录；只读核对同一原 Turn 的工具调用与 EPERM / exit 1 后补齐证据，没有重发输入。完整私有报告绑定源码／Adapter 指纹、Thread／Turn／operation ID；不把模型自述当成工具执行证据。

## M4 真实 EasyWork 客户端结果

人类补充要求同时验收 Codex 和 EasyWork。EasyWork 采用已审阅 Desktop 的独立工作树、独立依赖、持久 DEV 用户数据与工作区，通过真实 Electron Renderer 的权限按钮、具名 preload IPC、真实 gpt-6-astra / xhigh 模型和原生 Host 工具执行；没有替换 Agent Loop 或权限授权器。只观察真实授权结果，不用回显字段代替文件效果。

| 场景                           | 结果                                                                                                                  |
| ------------------------------ | --------------------------------------------------------------------------------------------------------------------- |
| UI 完全访问                    | 标签为“完全访问”，真实高风险终端操作 allowed / allow_all，工作区外专用文件内容匹配，确认零次                          |
| UI 高风险确认                  | 标签为“高风险确认”，实际高风险操作进入确认并被明确拒绝，授权结果 denied / confirm_high_risk，原文件未变               |
| UI 每次确认                    | 标签为“每次确认”，真实确认卡片可见，操作被明确拒绝，denied / confirm_every_operation，原文件未变                      |
| 低权限会话、本次完全允许子运行 | 准确 parentRunId 下的真实子 Run 用产品支持的文件入口写入成功；根／子授权均 allow_all、确认零次，UI 会话偏好仍是低权限 |
| 完全允许会话、本次低权限子运行 | 只单次批准本轮子 Agent 启动，子文件操作按 confirm_every_operation 拒绝；原文件未变，UI 会话偏好仍 allow_all           |
| 缺省接续                       | 未传权限覆盖，按持久会话 allow_all 执行成功，无确认                                                                   |
| 独立 EasyWork 重启恢复         | 原私有工作区和同一 Thread，未传权限覆盖的真实新 Run 仍 allow_all，文件效果匹配                                        |

七项通过绑定原始实现／环境指纹、操作意图与 Run、真实工具结果、授权器结果、provider 请求指纹和正用量。三项已通过 UI 场景在只修验收驱动后按逐文件未变的产品输入复用；初始界面就绪驱动失败以及子模型拒绝 shell 重定向的原失败均保留。子模型拒绝的旧操作没有实际工具副作用，不能据此断言权限失败；改用产品支持的文件操作、以新的稳定操作 ID 单列补验，没有重发旧输入。

另有一项入口关联实测：未绑定助手、电脑默认逐次确认时，实际微信服务输入为 allow_all；真实根及子 Run 的 Host 文件写入、独立字节核对、权限结果与 UI 完全访问事实均通过，确认零次。微信消息来源与发送为受控 DEV bridge，SQLite、真实模型和 Host 工具均为正式实现；未发送真实微信联系人消息。

客户端截图独立观察到完全访问标签、实际最终回复、确认卡片与拒绝结果。单次运行事实不覆盖会话偏好，避免把 run-only allow_all 显示成永久完全访问。实体 iPhone 和其他 Simulator 未参与本专项。

## 辅助验证与分发边界

Desktop 候选 12 个直接测试文件共 133 通过、0 失败、0 跳过；权限 UI 5 项通过。新增子运行缺陷回归在修复前 3 项失败，修复后相关 15 项全部通过。真实 SQLite、Runtime 和 PermissionAuthorizer 联测仍是受控辅助证据，不能代替上述原 owner 真实执行。独立 lint 通过；首次 typecheck 缺少本工作树 canvas-cli 构建，补齐既有 workspace 构建后完整 typecheck 通过。没有重跑来源已报告失败的无关全量 renderer 场景。

当前已完成 M4 的 Codex 实际 Host 入口与 EasyWork 原生客户端权限专项；没有发送真实外部微信消息，也没有用本机结果代替其他目标电脑。Codex Host 程序在本次权限专项未新增策略改动；Desktop 修复差异单独交付来源集成。统一 Host 包及逐机部署、owner-ready、实际权限执行的回执另存私有清单；发送／排队不记为完成。实体 iPhone、其他 Simulator 和虚拟助手模型联动未参与此专项。

通用入口为 `scripts/host-permission-takeover-acceptance.mjs`。目标 owner 提供已核对的 `HOST_PERMISSION_ACCEPTANCE_HOST_ID`、现有模型 `HOST_PERMISSION_ACCEPTANCE_MODEL`、官方 `CODEX_EXECUTABLE`，Windows 再复用其 `CODEX_DESKTOP_WINDOWS_CONFIG` 路径；可指定本次私有 `HOST_PERMISSION_ACCEPTANCE_DIRECTORY` 和项目 ID。仅创建本次独立状态、随机端口、专用 Thread 及工作区外临时文件。未知结果只查同一 operation ID，不重新提交。工具证据识别 custom／function span，并按准确 Turn 排除子线程 fork 历史。Windows 官方 PID 由目标 owner 另行核对，不用空列表冒充保持证明。

Codex 图形显示和截图未验收：Computer Use 明确禁止访问 `com.openai.codex`。本次仅报告允许的 Host 权限事实、底层策略及真实执行证据，未通过其他 UI 路径绕过该限制。
