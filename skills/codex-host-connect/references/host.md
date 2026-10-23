# Host Agent 部署

显式选择原桌面单实例共享服务时，按 docs/runtime.md 的“原桌面单实例共享服务”配置。使用统一包内 desktop-shared-environment.mjs 只设置本机 CODEX_CLI_PATH；版本 2 保留原 durable／云端及远程连接，按私有旧清单迁移移除本产品曾设置的全局强制 CLI 开关，保持原 Dock／Spotlight 图标；Host 继续 desktop-runtime，仅聊天管理配置 CODEX_SHARED_SERVER_URL。配置器不启动或强退桌面，已有任务完成后正常重开，再核对服务 owner 与手机功能。未经此选择的既有安装保持原路径；未来启动环境就绪不能代替当前进程已切换或正式验收。

仓库当前依赖 Node.js 24.x / pnpm 11.19.0。macOS 已有构建 26.930.61225、26.1002.52244 的原桌面证据；2026-10-09 联合验收在构建 26.1002.52244 的目标 Mac 和 Windows 分别完成原生核心场景、普通 App 新建及用户真实 iPhone 新建/回复，目标原 owner 独立核对。该记录不代表其他电脑或所有功能自动通过，首次接入仍执行完整清单。不检查 Codex 应用版本或对照构建清单，实际 IPC/owner 决定运行时是否可用；历史构建记录只说明当时的验收环境。Linux 尚未实现。

## 检查和构建

记录当前软件、监听端口和本产品服务状态。Codex 需由同一登录用户通过官方方式打开并登录；默认安装位置是 `/Applications/ChatGPT.app`，产品 UI 名称为 Codex。默认协调入口是该用户的 `~/.codex/ipc/ipc.sock`，不是 App Server socket。

为本次接入设置变量，替换示例值；不要重新赋值 HOME 或 CODEX_HOME：

```bash
CONNECT_REPO='/path/to/codex-manage'
CONNECT_ROOT='/path/to/authorized/projects'
CONNECT_NAME='办公 Mac'
cd "$CONNECT_REPO"
node --version
pnpm --version
pnpm service:desktop status
```

Node 不匹配时优先复用已有 Node 版本管理器或官方 Node 安装；pnpm 按仓库 `packageManager` 安装。全部依赖操作使用 pnpm，不创建 npm/Yarn/Bun 锁文件。不要强制替换用户的默认 Node。

完整接入包已包含编译程序与运行依赖，先按 [程序分发](distribution.md) 验证模块可加载，然后直接进入启动步骤；无需再次下载源码。仅有源码、程序被修改或需要重建时执行：

```bash
pnpm install --frozen-lockfile
pnpm build
```

普通安装使用随包编译程序；仅源码安装需要构建，不要求目标电脑运行开发检查、具备 Xcode 或手机在场。程序修复在规范仓库按最新 `AGENTS.md` 运行 `pnpm check` 与真实 DEV 验收；本机原生验收需要完整 Xcode，手机安装需要另行签名。

## 启动

首次安装、修复及升级默认使用本产品 launchd 服务并注册登录自启，无需额外询问；默认回环端口 8794：

```bash
HOST_AGENT_NAME="$CONNECT_NAME" \
HOST_AGENT_ROOT="$CONNECT_ROOT" \
pnpm service:desktop start
pnpm service:desktop status
```

该脚本只启动自己的桥接进程，内部显式选择 `desktop-runtime`，不进行 Codex 应用版本预检或兼容性提示。`CODEX_DESKTOP_VERSION` 可省略，能取得的版本仅作验收元数据，读取失败不阻止启动。真实 IPC 初始化、owner 与 stream 版本仍需成功。`pnpm dev` 默认仅健康检查，`dev:history` 仅历史，均不能替代。

当前 `service:desktop` 使用仓库 `.local/desktop-runtime` 保存私有状态，并保留已有 Node 和程序路径。`start`／`restart` 将同一私有配置安装到当前用户 `~/Library/LaunchAgents/com.codexmanage.host.desktop.plist`，并启用该 job，解除其已有的 launchctl 禁用状态，下次登录自动恢复；`status` 的 `loginAutostart` 表示注册文件存在，不能单独证明服务在线或未被系统禁用。保持仓库与 Node 安装路径稳定，原桌面仍须由用户正常启动并登录。

安装后核对注册文件的 Label、程序路径、`RunAtLoad`、`KeepAlive` 和 0600 权限，不输出 EnvironmentVariables 中的秘密；检查 `status` 的 `running: true`、`loginAutostart: true`，再执行回环／HTTPS 身份探测。终端关闭不应停止 Host，意外退出由 launchd 恢复。普通安装无需用户注销或重启电脑，核对当前注册、系统启用和在线状态；真实注销／重启恢复仍在可用窗口实际验收，未执行时记录未验收，不能用注册文件存在代替恢复证据。

`start` 不会重新加载已运行的 job，更改配置后用 `restart`。每次 restart 都显式传入原有名称、授权根目录、端口和控制配置，防止默认值覆盖；先从本机私有 plist 核对，避免打印环境中的秘密。默认端口冲突时选空闲端口并同时更新 Serve、探测及手机地址。

```bash
HOST_AGENT_NAME="$CONNECT_NAME" \
HOST_AGENT_ROOT="$CONNECT_ROOT" \
HOST_AGENT_PORT=8794 \
CODEX_DESKTOP_PREPARED_BRIDGE_CONFIG="$CONNECT_REPO/.local/desktop-runtime/prepared-bridge.json" \
pnpm service:desktop restart
```

首次启动取得本机身份后，按 [独立桥接配置](prepared-bridge.md) 写入私有 `prepared-bridge.json` 并重启。正式新建／主动打开按本机身份、私有权限与真实运行时就绪开放，无需 controller.json 或验收证明。保留已有私有配置和明确的 validationOnly；旧控制聊天仅作兼容，默认安装使用独立桥接。

## 身份与本机检查

运行时稳定身份在 `.local/desktop-runtime/runtime.json`，密钥在 `access-token`。每台电脑各自产生，不能复制整份 `.local`。没有设置环境令牌时，文件就是 Host 使用的令牌；环境令牌与文件不同会导致探测失败，应恢复一致配置而不是公开秘密。

配置完成后按 [Skill 交付格式](../SKILL.md#配置后的必交付参数) 向请求接入的用户输出实际地址、Host ID、私有密钥文件位置及连接保留结果，完整密钥不进入聊天或日志。优先复用手机原连接；探测从私有文件读取当前服务已接受的令牌，仅输出摘要。

用本 Skill 的脚本检查，路径相对于仓库：

```bash
node skills/codex-host-connect/scripts/probe-host.mjs \
  --state-dir "$CONNECT_REPO/.local/desktop-runtime" \
  --endpoint http://127.0.0.1:8794
```

脚本会先检查无令牌的请求被拒绝，再用文件中的令牌读取 health/workspace；要求返回的是本机已保存的 Host ID 和 `desktop-runtime`。只说明接入/身份正常，不证明具体聊天有原 owner。

原回环 Host + Tailscale Serve 继续保留。`desktop-runtime` 默认另开私网 HTTPS 直连入口（8796，IPv4/IPv6 双栈），发布 `_codex-host._tcp`，每 15 秒刷新系统私网和 Tailscale 本机地址。Mac 使用系统 OpenSSL 生成并持久保存直连证书；Windows 默认寻找 Git for Windows 的 OpenSSL，也可通过 `HOST_AGENT_OPENSSL` 指定，或同时提供 `HOST_AGENT_DISCOVERY_TLS_CERT` / `HOST_AGENT_DISCOVERY_TLS_KEY`。缺少证书工具或端口冲突时保留原入口并明确报告自动发现不可用，不能冒充配置成功。

默认首次接入在手机“添加客户端 → Codex → 自动发现”中点选电脑，自动授权后读取真实工作区，无需二维码或手动输入密钥。Bonjour 能列出未保存电脑，并提供无凭据 VPN 目录来源；手机经公开 `/discovery/peers?hostId=<来源电脑>` 获取在线电脑提示，再独立读取各目标的公开 `/host`，不传递其他电脑的密钥、不扫描整个网段。首次只有纯跨网 VPN、没有 Bonjour 或已缓存目录入口时，仍需可定位的来源；非标准端口和其他 VPN 的目录范围按实际实现单列，不承诺任意 VPN 都能枚举全部设备。

安装和升级必须检查实际私网直连入口，而不只检查回环／Tailscale Serve：

1. 无凭据读取 `/host`，核对发现契约 v1、准确完整目标、实际 TLS 证书与描述指纹一致、`connectionMode: automatic`、有效直连地址；这里的契约版本不是 Codex 应用版本。公开响应不得含原密钥、项目或聊天内容。`/discovery/peers` 返回准确来源目标和有界电脑提示；未配置 VPN 时可为空，局域网连接继续可用。
2. 使用手机身份或专用验收 X25519 公钥向 `/connection/claim` 提交严格 v1 和完整目标，通过 X25519/HKDF-SHA256/AES-GCM 解密；取得的是该公钥的独立凭据，不是原 Host 密钥。同一公钥重试应取得同一凭据，复用验收公钥，不在每次检查时新增设备绑定。错误目标被拒绝；原身份、密钥、证书与服务配置保留。
3. 用新凭据访问真实 `/v1/workspace`，核对同一完整目标、Host ID 和 `desktop-runtime`，并逐项核对全部适用功能。手机在场时实际点选并普通重开；缺手机只标这部分未验收，不将协议检查写成真机通过。`probe-host.mjs` 仅检查原鉴权入口；它和 fixture 不能替代此真实自动授权检查。

旧 Host 能被发现但没有 `automatic` 或 claim 时，必须部署规范仓库的统一新版；仅补描述字段不能实现授权。二维码和手动地址保留为补充入口：本机 `direct-discovery/connect.html` 含原客户端密钥，只在私有目录保存，不上传、提交或打印；不把扫码作为默认接入或修复前提。旧鉴权 HTTPS `/host` 仍可补齐直连证书与地址。

`service:desktop` 与 `service:windows` 保留并透传 `HOST_AGENT_DISCOVERY`、`HOST_AGENT_DISCOVERY_BIND`、`HOST_AGENT_DISCOVERY_PORT`、`HOST_AGENT_DISCOVERY_TLS_CERT`、`HOST_AGENT_DISCOVERY_TLS_KEY`、`HOST_AGENT_OPENSSL`。原桌面模式默认启用发现；`HOST_AGENT_DISCOVERY=0` 显式关闭。遗留关闭设置先核对是否为用户明确选择，普通完整接入恢复默认，用户明确关闭则保留并报告子集。私网入口接受原客户端密钥及按公钥持久保存的独立凭据，控制继续使用鉴权与具名接口；公开发现不表示原 owner 已可控。

## 升级与停止

保留状态，更新同一规范接入包并按原环境重启 Host。不因 Codex 应用版本变化检查或校正桥接版本字段；保留身份、证明引用及其他配置，核对真实协议和 owner。旧验收结论按原环境保留，不自动补作当前通过，也不以报告缺失／过期关闭独立桥接。开发修复仍在规范仓库运行 `pnpm check` 和真实验收，不删除状态或生成虚假证明。

`pnpm service:desktop stop` 只停止本产品桥接，同时移除登录自启注册，防止下次登录意外恢复；保留 `.local/desktop-runtime` 的配置、身份、令牌和回执，之后 `start` 可恢复同一连接。不要删除现有状态来修复连接；这会更换身份、令牌并破坏操作恢复。

### Windows worker 异常恢复

统一 worker 由原计划任务启动监督进程，逐次运行一个 Host 子进程，异常退出后退避重启；不重启官方 Codex。不要并行手动启动第二 worker。stop/restart 先禁用准确 owner/action 的任务，再按安装路径、配置、SID、创建时间和保留句柄清理本产品进程树，包括 Ready 任务的孤儿 worker；清理失败明确报错且不继续 restart，不按进程名称或端口批量终止。stop/restart 内部期限 45 秒、外层 60 秒；根和父 PID 查询有界，返回耗时及固定计数，超时即使端口已消失也不能记为成功。任务 Running 不等于 HTTP 在线，继续鉴权核对健康、Host ID、desktop-runtime 与准确 Thread。同配置目录 `worker-diagnostics` 是私有限量脱敏日志，不复制身份、密钥、日志或 runtime 状态到其他电脑。后台保存失败有限重试，命令保存失败明确返回 503；保持原操作 ID 查询，未知结果不得重放。统一包的 `node scripts/host-lifecycle-smoke.mjs` 提供独立临时状态／端口故障验收；Windows 的 nativeWindowsAcl=true 才是该机原生 ACL 证据，正式计划任务恢复另验收。

## 原桌面下一轮建议

每次完整接入和升级均完成 [Trace 交付收尾](desktop-suggestions.md#每次安装与升级的收尾)：先解释它用于手机同步原生下一步建议，macOS 和原生 x64 Windows 默认配置各平台持久环境及观察器，保留用户明确关闭或本次诊断的选择，不增加重复授权门槛。配置就绪后给出正常重开的具体动作；未来环境、当前进程、新记录和手机结果分别记证。Windows 注册表成功不等于当前实例已继承，按本机实际结果报告，不能用其他电脑的验收代替。

按 [建议采集与客户端重启](desktop-suggestions.md) 启用。Host 服务持久保存 `CODEX_DESKTOP_SUGGESTION_OBSERVER` 和 `CODEX_DESKTOP_SUGGESTION_TRACE_ROOT`；`HOST_AGENT_STATE_DIR` 显式指定已有状态目录，更新时保留原程序、Node 和工作目录。原 Codex 的 `CODEX_ROLLOUT_TRACE_ROOT` 是另一个进程的启动环境，不能只给 Host 设置同名变量或仅重启 Host 就宣称开启。
