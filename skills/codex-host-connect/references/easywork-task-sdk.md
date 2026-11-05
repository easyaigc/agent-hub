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

## 已有调用端与运行前置

已有已授权编排端通过真实产品服务、连接适配和加密 SDK 控制 EasyWork 的执行记录。是否存在独立 CLI、MCP 或 `/v1` 门面，与是否已实现、调用过 EasyWork 协议适配分别核对；不能从脚本名称推断能力缺失。

正常业务复用 `createDesktopHostTaskRuntime` → Host runner / `createEasyWorkHostDispatch` → `createEasyWorkHostTaskClient`。SDK 工厂和 transport 的源码接口不要求已有自动化或 WorkTask 对象，但必须有真实连接服务、自己的持久凭据引用和当前授权会话。已有验证来自真实 EasyWork composition root；源码可导入不等于独立无上下文工具已分发或已完成新的 headless 验收。

历史入口 `scripts/host-task-workflows-real-dev-acceptance.ts` 导出 `runHostTaskWorkflowLauncher`。它读取 `EASYAI_HOST_TASK_WORKFLOWS_CONFIG` 和 `EASYAI_HOST_TASK_WORKFLOWS_EVIDENCE_DIRECTORY`，要求已有授权的持久 DEV profile，经正常租约启动真实 Source Electron，运行固定目标的 UI、任务、定时器和模型场景。它用于追溯原调用，不是任意目标的通用任务 CLI，也不是查询旧操作的只读工具。不得重跑旧验收配置来恢复权限等待或未知写入。

## 地址、复制链接和授权引用

连接服务入口为 `createDesktopHostConnections({ directory, secrets, localTarget, adapters? })`。具名产品 API `HostConnectionService.manage` 支持：

```ts
type ConnectionRequest =
  | { action: 'list' }
  | { action: 'discover' }
  | { action: 'inspect'; endpoint: string }
  | { action: 'connect'; candidateId: string; key: string; name?: string }
  | { action: 'refresh'; connectionId: string }
  | { action: 'models'; connectionId: string; target: HostRuntimeTargetDto };
```

以上是 `manage({ action, ... })` 的请求形状。直接调用服务方法时是 `service.inspect(endpoint: string)` 和 `service.connect({ action: 'connect', candidateId, key, name? })`；`service.connect` 返回 `Promise<void>`，`manage` 则返回公开的 `connections`。连接后按完整 `target` 筛选唯一记录，再取得 `connectionId`，不能按显示名或直接取列表第一项。

`endpoint` 是经实际检查的 HTTPS 根地址，不含凭据、query、hash 或业务路径。先 `manage({ action: 'inspect', endpoint })` 或 `service.inspect(endpoint)` 取得候选及目标、证书信息，再使用返回候选的 `id` 作为 `candidateId` 连接。

目标 EasyWork 直连设置的“复制连接链接”复制 `state.lan.pairingUri`，格式为 `easywork-host://pair?descriptor=<base64url 描述>`。这条直连链接本身包含 bootstrap 所需描述，但仍须实际 `inspect` 其中的 HTTPS origin，核对有效 offer，并由目标确认本客户端的配对。

EasyWork adapter 的 `key` 只接受目标生成的六位配对码，或这条完整直连配对链接。现有 `readEasyWorkPairingCode` 解析并核对 `endpoints`、`target`、`fingerprint`、`publicKey`、`offerId`、`pairingCode`、`expiresAt`；链接内地址不能覆盖已检查的目的地址。`easywork://pair?version=1&relay=...` 和 `com.easyai.remote://pair?code=...` 属于其他入口，不是这个解析器接受的 `key`。不要泛化为所有远控链接都能直接传给 SDK，也不要将公钥、描述文本或另一产品的 Host Key 当作 EasyWork 长期访问令牌。

连接服务自行生成本连接的身份 seed 和 `credentialRef`，由适配器申请目标确认并保存 `{ deviceId, privateKey, binding }`。wire 的兼容字段 `mobileDeviceId` 在这条桌面调用中承载调用者自己的设备身份，不能复用手机凭据。SecretService namespace 为 `host-client-connections`；最终凭据以 `credentialRef` 索引，未确认申请暂存为 `pairing:<credentialRef>`。

连接元数据由当前 `workspaceRoot/.easyai-desktop/host-connections/connections.json` 管理；SecretService 的当前桌面 backend 是同一 workspace 的数据库 `secrets` 表。原 workflow config 只持有连接、目标、指纹和设置引用。私有路径及内容留在各机，不复制另一台电脑的数据库、身份或授权，不将凭据写入 Skill。Codex Host 协调清单是另一套固定协议入口，不能替代这里的 EasyWork 设备凭据。

## SDK 调用形状

```ts
const sdk = createEasyWorkHostTaskClient(connections.service, connections.sessions);
const stored = await connections.service.requireConnection(connectionId, target);
const selection = {
  kind: 'host' as const,
  connectionId: stored.id,
  target: stored.target,
  projectId, // 可省略；目标项目 ID
  settings: { model, effort, permissionMode: permissionMode ?? 'allow_all' },
};
const bound = await sdk.bind(selection, stored.credentialRef);
```

`connectionId` 必须来自按完整 `target` 唯一筛选后的连接记录；目标身份比较使用 `hostRuntimeTargetKey`，不使用显示名。`target` 固定 `agentId: 'easywork'`、`hostId`、`workspaceId`、`runtimeId`、`account: { authority, subject }`。`authorizationRef` 是受保护连接对象的 `credentialRef`，不是配对码、链接、Host Key 或 OAuth token；公开连接列表不返回此授权引用。参数省略和实际生效的模型、推理、权限分别核对；此 selection 没有速度字段。

Host 任务未显式指定权限时采用 `allow_all`，不要通过省略权限来表达完全权限：旧 SDK 会原样省略，目标可能回落到需要审批的普通聊天默认值。调用方按上例在准备新意图前补齐默认；显式 `confirm_high_risk` 或 `confirm_every_operation` 必须保留。首轮、后续回复及排队的 `prepareStart` 使用同一 selection；模型和速度仍沿用其既有规则。Codex Host 任务使用其原生 `full-access`，不能把 EasyWork 的权限字符串透传给 Codex。

旧意图恢复使用原请求与摘要执行 `commandStatus`／`queueStatus`，不修改已提交意图的 settings、不补写权限或再次派发。新权限规则只影响新准备的任务输入；正在执行的轮次、既有队列项和单次审批不因此改变会话权限。SDK 请求值不是实际生效证据，仍须读取目标 Run 的 generationSettings 或同等权威字段。

- 只读：`context()`、`projects()`、`models()`、`readThread(threadId, runId?)`、`history(threadId, { runId?, before?, limit? })`。
- 创建：`prepareCreate({ commandId, requestId })` → 持久保存原 intent → `createThread(intent, { beforeWrite? })`。
- 启动：`prepareStart({ commandId, requestId, threadId, text, mode? })` → 持久保存原 intent → `startTurn(intent, { beforeWrite? })`。此处 `mode` 仅为 `next-turn` 或 `queue`；即时追加使用单独的具名 control 接口。
- 恢复：`commandStatus(originalIntent)`；队列可查询 `queueStatus(originalIntent)`。`commandId` 和 `requestId` 分开，恢复保留原 intent 的目标、正文、授权引用和请求摘要。重连不授予重发原写入的权限。

生产任务优先沿现有 dispatch repository、runner 和 coordinator 保存提交边界，而非另用无持久状态的临时脚本替代。SDK 内部通过 `EasyWorkHostSessions`、`EncryptedRemoteChannel` 和 `WSS /channel` 执行固定的具名 v2 请求，不开放任意方法或远端 shell。

## 验证、登记与分发

既有私有验收包含隔离 EasyWork 的原生创建、启动和真实模型业务完成；具体 operator、SDK peer、完整 target、operation/Thread/Run、输入及返回字节摘要留在对应私有证据中。设备显示名不能证明调用者是 GUI、脚本或另一 Agent。创建和启动回执完成，也不代表模型、权限请求、产物或业务验收完成。

规范源码已登记的 SDK、已安装 Skill 文档、随包可执行脚本和各机已授权上下文分别核对。安装副本若落后，先归并这里的既有入口与参数说明，再按统一产物流程更新引用；不因此另做一套协议适配，也不把源码已合入当作目标部署或新的真实验收。`contact-host.mjs` 继续按 Codex Host 契约核对目标，不能放宽 runtime 校验后套用于 EasyWork。
