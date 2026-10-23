# 独立空会话桥接

默认采用独立桥接：Host 通过官方 API 准备零 Turn 空会话，准备进程正常退出后，原桌面按准确 ID 接管并独占执行。不依赖安装聊天或控制聊天的 owner，不发送初始化模型轮。`CodexHistoryReader` 继续只读，准备器不接受任意 RPC、shell 或工具调用。

当前实现为 `DesktopThreadPreparer` 和 `DesktopPreparedBridge`。当前统一构建在三台目标通过六项原桌面门槛、归档与重启恢复，普通正式 iPhone 的三个连接通过新建、回复和草稿恢复；范围见随包 `docs/acceptance/desktop-prepared-current.md`。新接入或升级仍须按实际目标和指纹逐项验收。旧 controller 场景证明不能换字段充当新证明。

## 正式安装配置

从同一完整包部署，保留原身份、密钥、授权范围、网络和操作日志。目标 Codex 只用于安装、运行和具名验收，不承担产品恢复策略。

先从本机 `runtime.json` 取得 Host ID。在本机私有状态目录写入权限 0600（Windows 私有 DACL）的 `prepared-bridge.json`，不复制其他电脑的身份或证明；无需读取或检查 Codex 应用版本：

```json
{
  "hostId": "<this-host-id>"
}
```

通过 `CODEX_DESKTOP_PREPARED_BRIDGE_CONFIG=<absolute-local-config>` 加载；它优先于旧 controller 配置。该配置没有 threadId。CLI 固定为当前官方桌面附带的绝对路径，Codex Home 固定为同一原桌面用户的目录；不得用其他用户、其他电脑或隔离 probe 的 Home 接入正式运行时。只重启本产品 Host，不改变官方启动。

macOS 服务自动发现原状态目录的 `prepared-bridge.json`；Windows 启动／重启时传入该配置路径，并保留既有服务环境。正常安装省略 `validationOnly` 或设为 false；旧 `verification` 字段与报告可保留为证据，缺失、过期、不可读取均不关闭独立桥接。升级核对遗留验证限制的用途，保留用户明确选择的 `validationOnly=true`，不删除身份、密钥、授权目录、附件或操作日志。

私有配置匹配本 Host 且真实 IPC 就绪后，声明正式 `desktop:creations`、`desktop:creations:projectless`、`desktop:open`。`desktopVersion` 可省略，旧值无需校正且不影响能力；升级保留身份、证明引用及其他配置，不改写旧验收结论。普通安装无需 Xcode、手机在场、控制聊天或测试报告；每次实际创建仍须确认准备进程退出，打开及首条输入仍须核对准确原 owner。配置和能力启用不能替代真实验收。

## 真实 DEV 验收与证据

按规范仓库最新 `AGENTS.md` 实际执行完整验收，结果按目标和实现／环境指纹单列。使用专用状态目录和端口，配置显式 `validationOnly=true`；共用正式 Host 不充当隔离验收进程。缺设备或原始证据时记录未验收，不能改成通过，也不因此关闭已就绪的正式安装。

`pnpm probe:desktop:handoff` 只准备空元数据并检查原 owner，不运行模型；`pnpm acceptance:desktop:prepared` 在独立状态目录执行真实首条输入、原内置工具和回复，默认无项目，`DESKTOP_CREATION_PROJECT_ID` 指定准确 App Server 项目 ID。这两个脚本的局部通过不能导出完整验收证明，也不能代替原生手机与生命周期结果。

需要证明绑定安装的正式 Host 时，设置 `PREPARED_ACCEPTANCE_IDENTITY_FILE` 为这台电脑原状态中的 `runtime.json`；脚本仅复制 Host ID 到独立验收状态，不复制历史、凭据、草稿或旧操作。依次完成 `attachments`（无项目和原生附件）、`controls`（准确已有项目及原 owner 停止/审批）、`empty-project`（完整清单确认原先没有聊天）。

没有真实零聊天项目时，`scripts/desktop-prepared-empty-project.mjs` 仅通过官方 project/create 创建独立验收项目并独立读取完整清单；它不创建 Thread，也不运行模型。失败和恢复报告保持构建指纹；脚本拒绝把旧构建已有会话的恢复换成新构建首条通过。

`scripts/desktop-prepared-lifecycle-acceptance.mjs` 用独立 HTTP Host 验证真实进程崩溃、正常重启、同 ID 查询和重复提交不重发首条输入，以及重启后的原会话主动回复。需明确指定已归档的旧验收控制聊天 `PREPARED_ARCHIVED_CONTROLLER_ID` 和无法经只读发现确认 owner 的专用历史验收聊天 `PREPARED_UNLOADED_THREAD_ID`；发现超时不能宣称其他进程中必定没有 owner，记录为 `no-verified-owner`，再以真实打开后的原 owner、工具和回复证明恢复。归档专项只操作明确指定的旧验收控制聊天，不归档其他用户业务聊天，也不反归档或重建它。它同时核对具名打开操作及原桌面与执行器进程未变。零用户消息的空元数据被官方列表排除，不能冒充普通未加载聊天的目录验收。

`scripts/desktop-prepared-proof.mjs` 仅从同一 Host、当前版本与加载摘要、全部通过的六项原桌面/HTTP 场景报告派生证明；它不启动正式服务，并明确标记 `phoneUiVerified=false`。切换生产后仍必须用原生正式手机连接验收全部受影响场景，不能把桌面证明当成手机验收。失败报告必须保留，恢复已有操作不能更换 nonce、输入或 ID。

继续执行 [完整功能清单](full-functions.md) 和 [真实验收要求](acceptance.md)：有历史项目、零聊天项目、无项目、图像加 TXT、owner 控制、重启恢复，另加未加载打开和归档旧控制／安装聊天后仍能新建。相同目录的项目按选定的实际项目 ID 区分；不能拿最近新增聊天猜测返回值。原桌面必须独立核对正文、附件和显示归属。

验收证明为 `kind=real-desktop-prepared-bridge`，字段由该电脑真实证据派生：hostId、实际 desktopVersion、`preparedAdapterDigest()` 的加载摘要、verifiedAt、六项 gates、unloadedOpen、controllerArchive 及证据引用。每个 true 都应有原始场景证据；未知、失败或未验收不能填 true。证明用于记录覆盖，不授予运行时能力，`verification` 可选。验证结束后保留独立测试配置；正式 Host 按原环境使用正式配置，不把测试限制带入正常连接，也不解除用户明确指定的验证专用配置。最后用普通正式 iPhone App 验证新建，不带测试能力或测试启动参数。

## 恢复边界

创建日志在非幂等 thread/start 写出前落盘，实际 ID 收到后立即落盘；标题持久化后必须确认准备进程退出。准备退出、原桌面仍为空且首条从未尝试时，可以继续同一 ID 的接管；首条尝试后结果未知只查询，不重新新建或发消息。已保存的未知旧 controller 操作保留原回执，不迁移成已执行或自动重发。

深链接导航与 owner 注册异步，桥接以限定时间重新进行只读 owner 发现。导航成功不证明可控制。无项目目录使用原桌面认可的 Documents/Codex 日期格式，同时验证真实 snapshot 的 projectless 和空项目元数据；不写原历史或私有桌面状态。

实际命令提交主动核对该目标的 owner 和完整当前快照，不能仅检查缓存的 hasOwner 或旧 turnId。同一 owner 的发现成功也必须重新取得快照；只读订阅不执行模型，不改变官方启动或会话归档状态。原桌面接受执行后的未知结果仍只查询。

Host 订阅关闭可能使已完成会话的 owner 卸载。新的回复先去重并核对准确目标；若只读发现仍没有 owner，仅对本桥接已确认创建的 ID 使用既有官方会话 URI 重新取得原 owner，不准备新会话、不发送初始化轮。回执已接受或结果未知时不执行该恢复，也不重发输入。正常服务 start/restart 必须保留已有私有环境、独立桥接配置、Node 和入口路径；Windows worker 明确转发独立桥接配置，不能重启后退回控制聊天入口。

命令前的 owner 再发现也可能失败。仅 `DesktopCommandNotSubmitted.ownerUnavailable` 证明执行命令完全尚未写出时，允许对同一已创建目标恢复一次原 owner 并继续发送；写出后的超时、拒绝或未知回执不进入此分支。Windows 的文件所有者和 DACL 检查由随包固定用途 C# 工具执行，首次用已安装 .NET Framework 编译并保护本用户缓存；没有 RPC、shell 或模型入口。状态保存不逐次启动 PowerShell。安装使用随包权限工具及实际权限检查，不要求客户端运行开发验收脚本；Windows 开发验收仍应运行 `scripts/windows-private-state-acceptance.mjs`，验证宽权限拒绝、恢复保护和连续原子保存；缺少编译器或检查失败必须准确报告。

权限工具显式使用 .NET Framework 4.6.2+ 的长路径设置和 Windows 扩展路径，防止真实附件或日志路径超过 260 字符时保护失败；[Microsoft 长路径兼容规范](https://learn.microsoft.com/en-us/dotnet/framework/migration-guide/retargeting/4.6.x)。Windows 专项还必须验证超过 300 字符的目录／文件保护和原子保存，不能以缩短验收目录代替修复。

草稿可以编辑、清除或结束等待；结束等待保留不可变操作 ID、原输入、附件与待确认回执，不宣称取消远端。晚到确认只更新旧操作，不占用新草稿或切换目标。
