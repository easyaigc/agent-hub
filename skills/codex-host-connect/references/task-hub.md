# Task Hub 电脑 Web 与唯一主服务

Task Hub 与 Codex Host 是两个独立服务。完整包的 `assets/task-hub` 包含同一冻结候选的电脑 Web、编译 API、PostgreSQL 迁移、生产 Node 依赖、独立源码 workspace/锁文件及管理工具。安装仅使用固定产物，不在目标电脑从源业务目录重新构建。Task Hub 不使用 Host 的令牌、端口或 Tailscale Serve 配置，也不复制旧任务台的数据库、Worker 状态或 Host 连接身份。

当前 primary 安装/升级/运行实现限于 **macOS，且 native 依赖要求与 manifest 的 architecture 一致**。Windows 只能作为 executor；Windows primary、主机迁移和真实注销/重启后的恢复尚未验收。实际 primary 必须使用 **Node 24.x**：安装/升级核对本次 executable，start 核对配置的 executable，runtime 再核对自身及配置 executable；源工程较宽的 engines 声明不算运行证据。PostgreSQL 的 `initdb`、`pg_ctl` 必须在本机可用。缺少时报告依赖缺项，不改动已有 PostgreSQL，不使用数组数据库。

## 构建与固定候选

在规范仓库构建。源工程保留自己的 pnpm 版本（当前 `pnpm@10.18.1`）；本仓库使用 `pnpm@11.19.0`。pnpm 按工作目录选择项目版本，构建器再次核对实际版本。只冻结明确的源码、manifest、workspace、锁文件和迁移；前后核对摘要后在独立副本安装、构建及 deploy。排除 `.local`、`.env`、附件、私有配置和运行数据；部署依赖中的构建自引用、绝对构建目录和无用 pnpm 运行元数据被去除。

```bash
node scripts/task-hub-build.mjs \
  --source "/path/to/approved/agent-task-hub" \
  --output "/path/to/new-frozen-task-hub-artifact"

TASK_HUB_BUNDLE="/path/to/new-frozen-task-hub-artifact" pnpm package:connect
```

输出目录必须不存在，不能默默替换已发布候选。`manifest.json` 固定 sourceSha256、payloadSha256、每个文件的哈希/相对符号链接、源锁文件摘要、源 pnpm 版本、契约及平台支持范围。完整包的 `bundle.json` 同时记录 Task Hub 指纹、Host/Adapter 编译指纹和平台范围；zip 的 SHA-256 在外部 `.sha256` 文件记录，避免自引用。失败的冻结副本与构建诊断留在规范仓库私有 `.local/task-hub-build-*`。

## 安装前预检

从完整 Skill 使用 `node scripts/task-hub-service.mjs`；从规范仓库使用同名包装入口。两者执行同一套规范 Skill 代码。

```bash
node scripts/task-hub-service.mjs preflight \
  --bundle "/path/to/skill/assets/task-hub" \
  --state "/path/to/private/task-hub-state" \
  --deployment-id "<explicit-deployment-id>" \
  --legacy-api-origin "http://127.0.0.1:4311"
```

预检读取本机登记、标准 `~/.config/agent-hub/task-hub/deployment.json`、Bonjour 提示、可信服务 HTTPS 身份及已有回环任务 API 的 OpenAPI/health。旧 API 探测仅 GET，只报告是否识别为 Agent Task Hub，不读取任务或凭据；4311 是已知旧入口的默认探测候选，可显式指定其他回环端口。探测沉默标为 absenceProved=false，不能宣称不存在。当前 Host 尚无 Task Hub 只读目录能力，结果明确写 not-implemented，不伪造目录。

发现冲突、原 primary 离线、登记不明、证书不符时保留归属，不提升另一台电脑。已有标准登记或旧非 Bonjour 任务 API 时，普通新 primary 安装被拒绝。人类明确指定新的隔离 DEV 时可用 `--isolated-dev`，但必须使用新 deploymentId、私有状态及独立端口；它不迁移旧数据库，不接管旧 Worker，不能把新空库说成旧服务升级完成。Bonjour 无响应本身从不授权新建数据库。

## Mac primary 安装与生命周期

```bash
node scripts/task-hub-service.mjs install \
  --bundle "/path/to/skill/assets/task-hub" \
  --state "/path/to/private/task-hub-state" \
  --first-primary \
  --deployment-id "<human-designated-deployment-id>" \
  --instance-id "<stable-instance-id>" \
  --primary-host-id "<this-computer-host-id>" \
  --host-runtime "/path/to/host-state/runtime.json" \
  --name "Agent Hub Tasks" \
  --tls-port 9443 --api-port 9444 --pg-port 55432

node scripts/task-hub-service.mjs start --state "/path/to/private/task-hub-state"
node scripts/task-hub-service.mjs status --state "/path/to/private/task-hub-state"
node scripts/task-hub-service.mjs stop --state "/path/to/private/task-hub-state"
node scripts/task-hub-service.mjs upgrade \
  --bundle "/path/to/new-frozen-artifact" --state "/path/to/private/task-hub-state"
```

安装要求明确 first-primary 和指定 Host ID，并与本机 Host runtime 的身份相符。API identity 从 TASK_HUB_DEPLOYMENT_ID、TASK_HUB_INSTANCE_ID、TASK_HUB_PRIMARY_HOST_ID 注入；TASK_HUB_SERVICE_NAME 为公开名称。DATABASE_URL、KEY_HMAC_SECRET、SESSION_SECRET、UPLOAD_DIR、BOOTSTRAP_PASSWORD、WEB_ORIGIN、API_PORT 及受限 PG 管理密码留在私有 env.json，从不打印。API 本身维护迁移校验和业务规则；安装器不修改源工程 API/contracts/DB。

安装锁、本机实例锁和 PostgreSQL advisory execution lock（84311322）共同保护启动；取得执行锁后才启动 API/Worker。专用 PG 使用回环 SCRAM 和受限 taskhub 用户；API 只监听回环。TLS 默认局域网监听，`--bind` 可限定本机接口。Web 和 API/SSE/附件流式代理同源，健康分别报告 identityVerified、backendReady、processRunning、Worker 是否启用和发现发布情况，业务验收始终单列。

`status` 的 `autostart.configured` 表示当前用户 plist 的 Label、准确 Node/entry/state、RunAtLoad/KeepAlive 均匹配；`loaded` 来自 `launchctl print`，`disabled` 来自 `print-disabled`。`loginAutostart` 仅在匹配配置、作业已加载且未被禁用时为 true；安装意图或单个 plist 存在不算通过。`runtimeHealthy` 另核对实际运行命令、本机 HTTPS 身份/数据库、ready 的 PID/nonce/三元身份/产物摘要。`processRunning` 保留活 PID 的占用事实，`processOwnershipVerified` 单列身份核对；不能据 reused PID 宣称运行健康。`worker.enabled` 是配置意图；关闭或 runtime 不健康时 `active=false`，启用且健康时仍 `active=null`，因为 API 尚无独立 Worker 活性契约。启用并不证明 Worker 近期处理或每个执行 Host 可控。`primaryEligibility` 和 `fences` 明确恢复/维护禁启状态。

`install` 不启动服务；默认自启配置在 `start` 注册独立的当前用户 LaunchAgent。`stop` 删除本服务自启注册并保留数据，之后 `start` 恢复。隔离 DEV 用 `--no-autostart` 安装并启动；`--disable-worker` 仅用于需要禁止调度的独立安装测试，`--isolated-dev` 本身不会关闭 Worker。日常主服务与普通聊天归档、Host 重启独立。异常退出恢复只回收此私有安装记录并核对过命令/数据目录的 API 与 PG，不信任单个 PID，不触碰其他服务。运行时失去数据库锁连接即停止 API。

重复安装复用实例、证书、密钥和数据库；不同候选升级前须明确 stop 此 Task Hub，安装器不会自动重启共享服务。保留旧 releases 供核对；数据库迁移由源 API 处理，未经备份/逆向迁移验证不能盲目回退旧 API。升级不改变 PG major；跨 major 迁移需专门方案。主机迁移/primary 提升没有实现自动路径，旧主 fencing 不明始终拒绝，不以离线推断旧主失权。

## HTTPS 身份探针

每次 `tlsJson` 探针使用独立的一次性 HTTPS Agent，避免旧版 Node 复用上一份登记的连接或 TLS session 后跳过新的证书指纹检查。证书链、登记域名和 SHA-256 指纹仍须全部通过；错误指纹或主机名必须在 HTTP 请求到达 API 前被拒绝，失败不能污染后续正确登记。该边界由 `scripts/task-hub-service.test.mjs` 的真实回环 TLS 网关测试覆盖，并在 Node 24.16 与当前 CI 基线分别验证；这不代替正式部署或真机验收。Node Agent 行为参见 [官方 HTTPS 文档](https://nodejs.org/download/release/v24.18.1/docs/api/https.html#class-httpsagent)。

## 可选业务通知私有配置与持久 key

`install` 或已停止原 primary 的 `upgrade` 可明确传入 `--business-notifications-config "/path/to/private/business/publisher.json"`。未配置时不注入环境变量；空路径不表示关闭。安装器把同一路径记录在 runtime 与私有 env 的 `TASK_HUB_BUSINESS_NOTIFICATIONS_CONFIG`，API 的规范 async factory 只读取该文件。启用要求完整冻结包包含 canonical business-crypto、源摘要及 Node 24 清单。

JSON 仅接受 `version:1`、`domain:"taskhub.business"`、原 `deploymentId/serverInstanceId/primaryHostId`、HTTPS origin `relayURL`、专用 `publisherToken`、绝对 `keyDirectory`、`bundleRoot`、`payloadSha256`。禁止用执行 Host token。配置父目录 0700、普通配置文件 0600/current uid/no symlink/no hardlink；key 父目录同样 0700，使用专用目录，路径与 release、PG、uploads、Host 客户端绑定及 recovery 分离。所有凭据由调用方预先提供，不扫描、复制或覆盖全局秘密配置。首次配置的 pin 必须匹配明确提供的冻结候选；安装器只把 bundleRoot/payloadSha256 更新为实际安装的 canonical release。

首次最后一级 keyDirectory 可不存在，只有规范 factory 生成 X25519；部署器不生成替代密钥。已有目录必须包含原 `publisher-x25519.json`（0600/current uid/no links、同三 ID、真实 X25519 私钥），空目录、缺文件、损坏或身份不符均拒绝。私有 `business-key-binding.json` 保存原路径/三 ID/key 字节摘要和首次生成资格；runtime 在启动 factory 前持久化 initialization-started，健康后绑定实际 key。首次失败且 key 仍缺失时须核对原初始化，不自动再生成；已初始化目录丢失也禁启，可用原加密备份恢复。

升级不传此选项时保留原配置/key 路径、专用 publisher 凭据和全部 key 字节，只更新同身份的 release pin；明确换配置路径时，新文件仍须使用同身份及完全相同的原 key 字节。路径变化须通过新私有配置文件明确提供，不通过修改旧 binding 偷换。升级要求无 loaded/configured job、PG 已关闭、无 runtime/API/未知 Worker journal、原端口空闲；取得实例锁后持久化 maintenance fence，并把旧配置及 pin 更新阶段保留在独立 recovery journal。任意失败保持维护 fence 和旧材料，完成后才解除该次维护 fence，不自动启动。

冷备的 GCM 清单额外收录原配置路径/key 路径及两个文件的字节 SHA；配置、publisher token、X25519 私钥和 binding 全部在密文内。读取原外部材料前后再核对摘要，没有复用或生成 key。offline 恢复只在新目录的 `business-notifications/config.json` 与 `keys/` 解密校验，始终不解引用其中的原外部路径，也禁止 destination 与原凭据重叠。in-place 恢复必须匹配原 runtime 路径和私有 key binding，可恢复丢失的原配置/key；原配置及旧 key 目录分别保留在 recovery/同一私有 key 父目录的具名 rollback，journal 记录实际替换路径。恢复后 factory 读取原字节，恢复激活再次把这些材料纳入 proof/fingerprint；换 token/relay/key/pin/path 后旧恢复资格失效。

## 加密冷备与离线恢复

具名入口为 `backup`、`backup-status`、`restore`、`restore-activate`。备份要求显式 `--state` 和不可覆盖的 `--output`；恢复要求显式 `--state`、原 `--archive`、`--key-file` 及互斥的 `--offline` / `--in-place`。前三者不会隐式 stop、restart 或启动业务进程；`restore-activate` 是人类明确对账后恢复使用的正式入口。当前实现是完整 PostgreSQL cluster 的停机冷备，限原 OS/architecture/PG major；不是逻辑 SQL dump、跨 major 升级或自动 primary 迁移。PostgreSQL [文件级备份规范](https://www.postgresql.org/docs/17/backup-file.html) 要求完整 cluster 且服务器停止；只拒绝新连接、复制运行中的 PGDATA 都不能作为冷备。

在父任务确认的停写窗口先显式停止这一个主服务。核对其原 Host/签名登记、固定产物、专属目录、无活 runtime/API、LaunchAgent 未配置/未加载、无 postmaster.pid、端口空闲，再以 `pg_controldata` 确认 clean shutdown。取得安装锁/实例锁并持久化 maintenance fence 后复制完整 cluster、全部 uploads 字节、唯一签名登记、TLS/登记私钥、env/PG 管理密码和 Task Hub 自身的客户端绑定。拒绝任何符号链接、外置 tablespace/WAL 或其他用户的条目；复制前后逐字节 SHA 核对原 cluster/附件/配置未变。备份没有读取 `hostRuntime` 之外的 Host 状态：该文件仅核对 hostId，文件内容不进入备份；执行 Host 的身份、access-token、runtime、全局 hosts.json、授权目录和验收证明都不复制。`host-connections` 仅是 Task Hub 作为连接客户端的本地身份与绑定。

隔离副本通过原生 `postgres --single` 检查专属业务库、全部 public 表的稳定行摘要、外键实际关联、约束 catalog、原 SQL 迁移字节与 `taskhub_migrations` checksum；业务回执/手机会话的 service_key 必须匹配三元身份。每份附件的 SQL storage_name、size_bytes、sha256 与真实字节一致，所有缩略图/未引用文件也纳入文件清单。原 pending/unknown/confirmed/rejected 回执、sealed_result、执行尝试及 lease 等原字段保持，不在恢复时重放输入。单用户校验不启动 postmaster、监听 socket、API、TLS、Worker 或 Bonjour；它是离线数据库工具操作，不算业务运行或 Worker 验收。

清单内保存原 SQL 迁移、源/产物指纹、PG major、checkpoint/system identifier、表/回执摘要以及每个文件的字节大小和 SHA-256。整个清单、数据库、附件及配置流式 AES-256-GCM 加密，外部只返回 archiveSha256/manifestSha256 等非秘密摘要；格式为固定 magic、随机 96-bit nonce、密文和 128-bit tag，header 纳入 AAD。密钥是独立 32-byte 随机值，使用私有文件读取，绝不放命令行参数或日志。`--create-key` 只创建不存在的 key 文件；必须存到 service state 和备份目录之外，另行保护。输出/密钥/临时明文均 0600、目录 0700。备份发布不覆盖既有 archive，备份失败或被打断保留维护 fence；先核对原进程和源数据再人工解除，不能自动忽略 fence。

```bash
# 仅在已授权停写窗口操作明确的 primary
node scripts/task-hub-service.mjs stop --state "/path/to/private/task-hub-state"
node scripts/task-hub-service.mjs backup \
  --state "/path/to/private/task-hub-state" \
  --output "/path/to/private/backups/task-hub-immutable.enc" \
  --key-file "/path/to/separate-private-keys/task-hub-backup.key" --create-key

node scripts/task-hub-service.mjs backup-status \
  --archive "/path/to/private/backups/task-hub-immutable.enc" \
  --key-file "/path/to/separate-private-keys/task-hub-backup.key"

node scripts/task-hub-service.mjs restore \
  --state "/path/to/private/task-hub-state" --offline \
  --destination "/path/to/new-private-offline-restore" \
  --archive "/path/to/private/backups/task-hub-immutable.enc" \
  --key-file "/path/to/separate-private-keys/task-hub-backup.key" \
  --bundle "/path/to/original-frozen-task-hub-artifact"
```

`backup-status` 只证实 GCM 认证和每个文件清单；准确返回 `sqlRestoreVerified=false`。`restore --offline` 在不存在且位于原 state 之外的目录中完成全量 SQL/附件复验；GCM 最终认证前不解释恢复路径，拒绝路径穿越、truncated/trailing payload 和 SHA 不符。持久 `offline-only.json` 使该副本永久禁止业务启动；已解密但复验失败的副本仍被禁启。`--bundle` 可另核对原固定候选及全部迁移；省略时只依赖加密包内原迁移字节与摘要，不意味着可启动该副本。

原址恢复使用同一命令改为 `--in-place`，不提供 destination。仅允许相同原 state、三元身份、原 Host 和产物摘要的已停止 primary；先持久 `restore-fence.json` 撤销旧主执行资格，再解密/完整复验/逐项替换。原 cluster、附件、配置保留在本 state 的私有 recovery 目录，restore-journal 记录替换阶段；中途失败保留原材料和 fence。成功返回 `original-primary-restored-fenced` 和独立 `restoreId`，不启动服务。v2 证明保存在 `recovery/<restoreId>/restore-verification.json`，以不可覆盖的文件绑定原 archive SHA、GCM manifest SHA、完整原 bundle manifest SHA、state/三元身份、恢复材料摘要及实际 SQL 结果；完成 journal 的 `phase=completed` 另绑定证明 SHA。根 restore-report 仅是最近一次证明的副本，历史证明、rollback 和激活意图/回执分别保留。恢复点后的远端操作可能已经执行，必须按原 operation ID/实际 owner 对账后才明确激活。直接删除 fence 不能算恢复闭环；不提供自动 primary 提升。

### 具名恢复激活

```bash
# 用 restore --in-place 实际返回的 ID；先完成原远端操作对账
node scripts/task-hub-service.mjs restore-activate \
  --state "/path/to/original-private/task-hub-state" \
  --restore-id "<restore-id-from-completed-in-place-restore>" \
  --archive "/path/to/private/backups/task-hub-immutable.enc" \
  --key-file "/path/to/separate-private-keys/task-hub-backup.key" --verify-only

# 同一参数省略 --verify-only：复验后走 normal start 和安装时的用户自启策略
node scripts/task-hub-service.mjs restore-activate \
  --state "/path/to/original-private/task-hub-state" \
  --restore-id "<restore-id-from-completed-in-place-restore>" \
  --archive "/path/to/private/backups/task-hub-immutable.enc" \
  --key-file "/path/to/separate-private-keys/task-hub-backup.key"
```

`--verify-only` 只返回 `restore-activation-verified`，维持 fence，绝不启动业务。正式激活必须仍是原 state、三元身份、Host、完整原 bundle/manifest，且固定 runtime 含 activation guard；旧版无 guard 的运行包或缺 v2 原 manifest 绑定的旧证明不能获得激活资格。入口重新核对签名登记/配置、不可变证明、对应根报告与已完成 journal、实际 archive SHA 和 GCM manifest；再次完整检查安装 bundle、原 SQL 迁移、恢复材料和 SQL/附件副本。材料、证明、archive 或 Host 在复验期间变化均拒绝。

PG 必须 clean shutdown、无 postmaster，runtime/API/Worker 不活动；任何已配置 plist、loaded job、遗留 ready/resources journal、maintenance/offline/pending activation 或不同 fence 都阻止激活。旧执行只接受已终结的 `completed/failed/superseded` 且无 lease；queued/prepared/submitted/running/uncertain/blocked、未知状态和任意未清 lease 都需要先按原操作对账。业务 pending/unknown 回执保持原 ID 和原内容，不由激活重放。`offline-only` 副本永远不能被激活，这条入口不创建新库、不改变 Host/身份/端口，也不作为跨 Host 迁移。

正式动作复验成功后持久化本次不可变意图和 `restore-activation.json` 门闩，原 fence 转为仅允许本次激活的 `activating`；安装锁的准确 nonce/活 owner、restoreId、state、证明和配置摘要共同限定权限，实例锁仍保证同一 state 只有一个 runtime。普通 `start` 不能绕过该门闩；normal runtime 只在这个显式授权下启动原 PG/API/TLS/Worker/Bonjour，启动期间持续核对 controller 与完成回执，owner 消失或 fence 冲突则自行停止。健康 ready 必须携带准确 restoreId/activationId；已有启用自启策略还必须实际 `loginAutostart=true`，不能拿 healthy 代替 loaded job。

健康/原身份/产物及所选自启全部通过后，先 fsync 并不可覆盖发布完整 `committed.json`，成功后再删除原资格 fence，最后解除 pending 门闩，返回 `restore-activated`。不可变证明/意图/回执均先写同目录临时文件、文件 fsync，再通过拒绝覆盖的原子 hard-link 发布和目录 fsync；I/O 失败不留下可接受的最终文件，激活失败仍保留资格 fence。默认使用原安装的用户自启设置；首次默认安装的自启会通过原 `start` 注册并加载，用户已选 `--no-autostart` 的安装保持其选择。重复同一已提交 restoreId 只返回 `activation-already-committed`，不再次启动。提交前中断的 pending 门闩拒绝无 owner 启动；已提交回执使完成后的恢复有效，不依赖 controller 继续存活。v2 activation intent/receipt 使用同一严格 reader：准确 kind、actual-normal-start source/action/phase、原三 ID、目录 activationId/restoreId、state/proof/archive/runtime/full manifest/payload hash 全部匹配，且绑定不可变 intent 与原恢复证明；同 family 的 failed.json 存在即拒绝，即使失败文件残缺或同时存在 committed.json。service 重复激活与 runtime 恢复均使用这一 reader，未知/旧版/辅助回执不能授予启动资格。合成 lifecycle callback 仅返回 auxiliary-verified，完整保留资格 fence，永不变成正式恢复成功；独立辅助证据仍保留。

任何启动、健康、自启或提交失败都保留恢复证明和原资格 fence，并只清理这次原 state 的具名运行资源；清理无法证明时同时保留 pending 门闩。不可读或冲突的他人 fence/门闩不会被覆盖或删掉。失败后若数据库或配置已经变化，必须重新审验恢复材料，不能把旧证明当新状态的资格。每次意图/成功/失败位于独立 activationId 目录，不覆盖旧的 source/hash/验收记录。此正式闭环的真实运行验证必须等原操作已核对完成后，由父任务在唯一 primary 窗口执行；本轮只能完成合成离线工具与状态机辅助验证。

辅助验证：`pnpm test:task-hub:deployment` 覆盖加密往返、错密钥/篡改/截断、不可覆盖、私有权限、路径/链接拒绝、X25519 身份/权限/key 保留及 fence/状态分层。`pnpm exec node scripts/task-hub-offline-acceptance.mjs --migrations "/path/to/approved/original/migrations" --business-factory-bundle "/path/to/approved-frozen-factory-aux"` 只用新私有目录和真实 PostgreSQL 单用户工具，按原迁移建立合成数据并检查账本/附件恢复、迁移/实际外键负例、原址 fence 与 rollback；可选 factory 参数完整校验该 AUX，并通过实际冻结 factory 创建合成 X25519、换 release、拒绝旧空目录、加密恢复原配置/key 及激活漂移负例，fetch 明确禁止网络。install/upgrade helper 与 activation lifecycle callback 仍属于合成辅助验证，不算实际安装/启动/自启/推送验收。它不使用正式 Host、不创建活动业务库。报告写入私有 `.local/task-management/deployment-completeness`，明确为原 SQL/冻结 factory 加真实工具辅助证据。

最终冻结包可直接用于同一离线 AUX：`pnpm exec node scripts/task-hub-offline-acceptance.mjs --bundle "/path/to/approved-complete-frozen-artifact" --expected-payload "<payload-sha256>" --expected-source "<source-sha256>" --progress`。这两个显式 pin 必填，先校验全部文件及 frozen core/runtime/service 与当前 canonical SHA 一致；迁移默认从该包读取，factory 必须来自同一包。只在新合成 state 中把 upgrade helper 的目标 pin 更新为这份原封不动的实际 release，随后检查通知订阅、task/round outbox、uncertain delivery、独立收方解密及配置/key/DB 的加密备份恢复。篡改负例只使用另建副本，最后重验完整原包及 manifest 未变。调用不会执行 API main、postmaster、Worker 或实际激活，不能计入真实 primary/业务 APNs 验收。

父任务确认无第二主冲突后，可运行 `node scripts/task-hub-install-acceptance.mjs --bundle "/path/to/new-frozen-task-hub-artifact" --confirmed-isolated-primary-window --autostart-lifecycle`。该门禁必须来自父任务明确的窗口确认，不能为通过测试自行补标记。脚本创建专用状态及独立服务端口，保留原 Host；`--autostart-lifecycle` 实际核对 plist/loaded/disabled/运行健康后，仅 SIGKILL 自己且命令/状态归属匹配的 API、runtime，检查 LaunchAgent 自动恢复后的新 nonce/原三元身份/任务/登录会话，再 stop、冷备、离线复验及原址恢复，用正式 restore-activate 恢复 normal start/任务/会话/所选用户自启，最后 stop 自己。未加 lifecycle 时不宣称覆盖自启/崩溃。真实注销/电脑重启、手机/模型闭环仍分别待验。完整安装启动/LaunchAgent/崩溃/恢复激活/登录重启/iOS/模型闭环由父集成在原操作已核对完成的明确窗口实际执行；本次离线复验不替代这些场景。最后由父任务统一 packaging 并绑定接入包 SHA-256。

## 可信登记、TLS 与手机/浏览器

首次指定生成 Ed25519 私钥，公共 deployment.json 包含签名、稳定 deploymentId/serverInstanceId/primaryHostId、登记版本、canonical .local 域、证书及 HTTPS origin。私钥只在主机 0600 文件中，目录 0700。executor 必须通过独立可信渠道确认 registryKeySha256，单靠广播或自签文件不能授信。登记 envelope 与 registration 使用精确字段契约，所有证书/端点字段严格检查类型，并将真实证书 DER SHA 与所声明 fingerprint 对照；可信签名下的未知字段、非字符串或不一致证书仍因契约不合法而拒绝，不能误称该签名伪造。

Bonjour `_agenthub-task._tcp` 只广播版本、三元身份、名称、契约及描述路径。先发现，再核对真实 HTTPS `/api/v1/service` 和登记；发现描述不包含账号/令牌或任务内容。标准健康入口为 `/health`。公共登记和配对描述分别为 `/task-hub/deployment.json`、`/task-hub/pairing.json`；同源 QR 为 `/task-hub/pairing.png`。本机也保存 pairing.json/pairing.png。

macOS canonical 发布与浏览通过系统 `/usr/bin/dns-sd` 和 mDNSResponder，避免 GUI LaunchAgent 的 raw multicast 失败被混同为数据库/API 不健康。发布使用原登记 canonical 域与当前物理 LAN 路由的 IPv4 地址，不更改 VPN、路由、防火墙或系统权限；没有适用 LAN IPv4 或系统拒绝登记时准确报告 unavailable。网络地址变更先撤销旧登记，再重新登记；服务实例名称附稳定 instance 摘要，TXT 中保留原显示名称，降低不同隔离实例的名称/TTL 冲突。IPC 专属公告 helper 随 runtime 退出清理；它不启动 PG、API、TLS 或 Worker。系统登记回执与另一个设备实际发现仍分别记录，`status.discovery.lanDiscoveryVerified=false` 不将本机登记提升为手机实测。只读 discovery 是提示，executor 仍严格核对可信签名、证书 DER pin、canonical Host/SNI 和三元身份。Windows executor 保留其库实现，未因 macOS sidecar 验证宣称跨平台通过。

具名升级前保留现有包与 primary；native DNS-SD 的有界 QA 类型发布、正常停止和 owner 崩溃清理只算 sidecar 辅助证明。不得在父正在运行真实手机/模型验收时自行升级或重启 primary；完整升级、自启、网络恢复和手机复验由父安排独占窗口。

配对 JSON v1 的 type=agenthub-task，endpoint/canonicalOrigin 是同一个 HTTPS `.local` origin；fingerprint 为叶证书 DER SHA-256 十六进制，spkiSha256Base64 为 SPKI DER SHA-256 Base64，另带三元身份、name、registryKeySha256。不广播可直接访问的裸 IP origin。远端浏览器可用独立 profile 的 host-resolver-rules 将 canonical 域解析至已核对的 LAN/VPN IP，并单独配置精确 SPKI 例外；仍发送 canonical Host/SNI/Origin。直接用 IP 请求返回 421，不能改 WEB_ORIGIN 或网关来绕过。LAN/VPN 只改变路径，同一 payload、同一数据库；VPN 是可选补充。

自签证书可以显式运行 `trust-certificate --state ...`，只信任当前用户登录 Keychain 中本服务证书，系统可能要求用户认证。手机也可明确确认主机指纹并按 origin 固定 Pin。现有 Swift `DiscoveredHostTrust` 只适合首次本地描述探测并采集证书；其采集结果不得直接变成授权。用户确认后使用 `PairedHostTrust`，每次请求、上传、SSE、回执均核对已保存域名/证书，拒绝重定向。不设置全局 TLS 跳过开关。

## Executor

```bash
node scripts/task-hub-service.mjs executor \
  --bundle "/path/to/skill/assets/task-hub" \
  --state "/path/to/private/task-hub-binding" \
  --registry "/path/to/trusted-public-deployment.json" \
  --trust-key "<out-of-band-verified-registry-key-sha256>"
```

先核验签名、TLS、实际服务身份/健康和发现冲突，再保存公共绑定，不建 PG/API、不生成另一 primary、不修改 Host。Windows executor 使用公共绑定路径，不宣称 Windows primary 已支持或已验收。Worker 的执行 Host 绑定及项目权限仍在任务业务服务中建立，这条 CLI 只处理服务归属与可信接入。

## 结果与验收

报告 Task Hub 包指纹、公共身份、canonical HTTPS origin、证书指纹、状态路径和实际安装检查。口令、数据库 URL、私钥和 Host token 不进入报告。预检、构建、真实专用 PG 安装/HTTPS/浏览器检查、真实手机与模型闭环分别记录。独立安装测试通过不能代替真实 iPhone、原桌面 owner、模型、任务全流程或迁移验收；没有执行旧库迁移必须明确写未执行。
