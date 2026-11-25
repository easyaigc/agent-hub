# 原桌面下一轮建议：启用、升级与实测

2026-11-23 正常 macOS 路径优先：使用下方原生结果采集入口，CLI 子进程移除全量诊断环境，并仅撤回本产品可核对归属的旧登录 Trace 环境。`traceRoot` 继续作为精简建议/producer/有界诊断的私有目录，目录存在不代表完整 Trace 已开启。以下原生 rollout Trace 准备/启用段仅用于既有兼容路径与历史诊断，不作为 macOS 正常建议安装前置，也不在升级后重新开启。既有 Trace 只读及已确认安全回收保持；配置准备不代表当前原进程生效。

Windows 原桌面 Responses 转发已退役，安装及升级不写模型 URL，也不启用该监听。仅用旧工具的 `status`／`disable` 处理有归属回执的遗留配置。Windows 暂停此原生建议来源，详见[运行说明](../../../docs/runtime.md#windows-原生建议-responses-转发退役)。

当前 macOS 实现读取原 Codex 已经生成的建议，不调用预测生成接口，不采用或发送桌面建议。请求中的来源 Thread／Turn 与预测 Thread／Turn 精确连接，响应正文须与原完成通知一致；手机按完整机器目标、来源轮次、摘要和响应声明的有效性读取。Windows 原生 x64 适配复用公共解析器，以当前原实例与句柄／DACL 校验直接读取准确来源的 Trace，不模拟 macOS 通知；源码实现、正式部署、正常重开、新记录与手机验收分别记录。见 [Windows 安装步骤](windows.md#trace-与下一轮建议)。

## 正常 macOS 建议采集

正常安装使用 `scripts/desktop-native-suggestions-environment.mjs install|status`，同时准备原桌面共享 v2。实际官方 CLI 的两个启动入口共用移除 `CODEX_ROLLOUT_TRACE_ROOT` 的规则；采集器从原桌面已发起的 `thread/sample` 请求／响应被动取得正文，并校验原 producer 安全日志。完整 Trace 不再是普通建议的依赖，也不额外调用模型。`source=native-trace` 是兼容来源标签，不能据此认定全量记录正在运行。

| 信息或行为                           | 当前实现与用户收益                                                                |
| ------------------------------------ | --------------------------------------------------------------------------------- |
| 下一步建议正文、来源聊天与轮次       | 手机显示两行灰字，可展开全文；明确采用才填草稿，可撤销，不自动发送。              |
| 同轮次的新建议版本                   | 前台约两秒读取，以六秒租期确认新鲜度；未完成、失败、空或失效结果不回退旧建议。    |
| 普通消息、进度、完成、错误及工具反馈 | 继续由既有运行时／事件通道提供。                                                  |
| 本机采集资料                         | 每聊天最新精简状态与已限额轮转的 native diagnostics；不新增完整推理／工具 Trace。 |
| 旧 Trace                             | 保留兼容读取与安全回收；未知、占用和未决记录不按文件年龄直接删除。                |

`traceRoot` 是当前用户私有目录。已有目录按实际路径复用；首次接入可创建一个 0700 空目录，供精简状态与 producer 使用，不运行旧全量 Trace `install` 来创建。原生配置、共享启动工具和 Host 需来自同一统一包，保留原 Host ID、令牌、设备绑定、地址、端口和默认目录。程序不能部署到即将清理的临时工作树。

### 每次安装与升级的收尾

在稳定程序目录使用原生环境工具，保留原可执行文件、Node、共享回环 URL 与完整机器目标。工具只准备未来正常启动，不退出或重开原桌面；仅撤回能核对归属的旧登录 Trace 设置，不覆盖无关环境。运行中执行器不会因 Host 重启而改变环境。

升级已有有效 producer 时，以独立 0700 state（工具已有 `--state` 参数）准备新配置，保留原配置及当前 Host 引用。未来共享入口改指向新配置；原桌面正常重开并核对新 producer 就绪后，再让新 Host 使用同一配置。配置准备不应先破坏当前建议；原文件保留作恢复输入，身份与连接地址保持。

| 当前状态                                           | 安装及交付的下一步                                                                               |
| -------------------------------------------------- | ------------------------------------------------------------------------------------------------ |
| 原生采集配置匹配，当前 producer 实现与真实结果有效 | 复用并记录实际结果，不重复要求重开。                                                             |
| 尚未配置且用户未关闭                               | 准备私有目录、原生采样、共享 v2 和 Host 观察器，完成核对后交付正常重开动作。                     |
| 未来配置就绪，原执行器尚未加载                     | 用户保存工作后正常退出、从原图标重开；报告“配置就绪，待正常重开”，不宣称当前停录或建议通过。     |
| 仅依赖旧 Trace 的 legacy 安装                      | 迁移前保持既有行为；准备 native 与共享入口，真实建议通过后才声明停录。                           |
| 用户明确关闭或缩小范围                             | 保留选择，交付其余已配置能力与具体缺项。                                                         |
| 系统权限或无关环境冲突                             | 保留身份和原设置，处理已授权可恢复项，只交付确需人工完成的最小动作。                             |
| Windows                                            | 保留旧 Trace reader；已暂停的原生 producer 安装路径保持暂停，单列实际状态，不复用 Mac 通过证明。 |

推荐话术：

> 已准备原生建议采集，手机可查看、采用到草稿和撤销，不自动发送。正常模型进程不再录制完整推理与工具 Trace；精简状态留在本机私有目录。请保存工作后正常退出 Codex，再从原图标打开一次。实际加载、真实建议与 App 结果分别为〈实际状态〉。

### 逐层实测

先核对当前原 Electron、直属执行器、共享服务与准确 owner，不把 history reader 当原执行器。核对 producer generation、已加载实现指纹与准确机器目标后，再从任务自有的真实模型轮次取得自然建议。分别验证 Host 正文／来源／六秒租期、App 展示／采用／撤销／草稿保护，以及原 owner 没有因此收到额外输入。完整 Trace 的目录事件与文件量必须在实际模型任务期间独立观察；future 配置、短测与 24–72 小时观察分别记录。

原连接的正式运行与隔离 DEV 各自留证，临时 DEV 不能替代正式连接。源码、统一包 SHA、实际加载和真实验收状态分别交付，不把 HTTP 成功、fixture 或有一个旧建议当成全部通过。

## 历史完整 Trace 诊断与兼容

以下流程只用于用户明确选择的完整诊断或既有 legacy 安装；不作为正常 macOS 建议安装前置，不在升级后自动重新启用。完整 Trace 可包含提示、模型返回和工具输入输出；其内容及版本以实际执行器为准。仅有目录、环境键、Host 观察开关或旧回执不证明当前在录制。

既有兼容路径使用 `CODEX_ROLLOUT_TRACE_ROOT`，Host 使用 `CODEX_DESKTOP_SUGGESTION_OBSERVER=1` 与 `CODEX_DESKTOP_SUGGESTION_TRACE_ROOT` 指向同一目录；原客户端与 Host 分别生效。旧持久工具 `desktop-suggestion-trace-environment.mjs status|disable` 用于对账与撤回本产品设置，`disable` 保留记录及恢复资料。明确诊断任务才按范围使用 `install` 或下方一次性流程；不得为恢复普通建议盲目开启全量记录。

## macOS 仅本次诊断：首次开启原客户端 trace

### 1. 用户选择本次诊断时准备工具

随包仓库包含固定用途启动器。使用原 Host 配置中实际保留的 Node 绝对路径；程序和私有目录保持稳定，退出当前聊天后仍可读取，不能放在将清理的临时工作树中。以下变量都是本任务变量，不覆盖 HOME 或 CODEX_HOME：

```bash
SUGGESTION_REPO='/path/to/stable/program'
SUGGESTION_LAUNCH='/path/to/private/new-suggestion-launch'
SUGGESTION_NODE='/path/to/existing/node'
cd "$SUGGESTION_REPO"
"$SUGGESTION_NODE" scripts/desktop-suggestion-trace-launch.mjs --prepare "$SUGGESTION_LAUNCH"
"$SUGGESTION_NODE" scripts/desktop-suggestion-trace-launch.mjs --check "$SUGGESTION_LAUNCH"
SUGGESTION_TRACE_ROOT="$SUGGESTION_LAUNCH/traces"
```

准备目录须是新建空目录，由当前用户所有，0700；意图文件 0600。工具创建 `traces/`、`launch-intent.json` 与 `launch-original-codex.command`，保存准确原应用／直属执行器的 PID、启动时间与安装指纹。准备后另存原 Host 服务配置的私有备份和摘要（目录 0700、文件 0600），备份范围包括原身份、令牌／证书位置、设备绑定、默认工作目录、名称、端口、Node、入口、工作目录和控制配置；不在聊天展示原文。备份不写入须保持空白的 `traces/`。准备和检查不启动、不结束任何程序。

`--check` 的 `readyToLaunch` 只表示当前能否立即 `--launch`；原客户端仍在时为 false 是正常结果，不是等待器状态。不能为取得 true 提前让用户退出。

用户已经选择本次诊断时，直接完成上述准备并进入等待器步骤，不为每个文件或进程另行询问。只有准备文件时报告“准备完成”，不能说等待器已运行或先让用户退出。正常退出的时间仍由用户决定。

### 2. 实际启动并核对独立等待器

在仅本次诊断模式，由执行接入的 Agent 先启动可脱离当前聊天存活的 `--wait-and-launch`，不让用户先退出再回来补准备。该模式最多等十分钟，直到原应用及准备时记录的直属执行器退出，再通过 LaunchServices 正常启动。单独运行一条前台命令、生成文件或得到 `--check` 的结果均不能证明独立等待器已存活。以下命令在独立进程组中启动，关闭标准输入，把输出写到私有日志，并保存 PID、启动时间、原意图摘要；原客户端仍在时仅等待，若已经退出则可能直接进入启动阶段：

```bash
"$SUGGESTION_NODE" --input-type=module - "$SUGGESTION_REPO" "$SUGGESTION_LAUNCH" <<'NODE'
import { spawn, execFileSync } from 'node:child_process';
import { createHash } from 'node:crypto';
import { once } from 'node:events';
import { closeSync, existsSync, openSync, readFileSync, writeFileSync } from 'node:fs';
import { join } from 'node:path';

process.umask(0o077);
const [repo, root] = process.argv.slice(2);
for (const name of ['wait-claim', 'waiting.json', 'waiter-process.json', 'launch-claim', 'launch-receipt.json']) {
  if (existsSync(join(root, name))) throw new Error('Existing attempt: inspect it before any new launch');
}
const intent = join(root, 'launch-intent.json');
const intentSha256 = createHash('sha256').update(readFileSync(intent)).digest('hex');
const log = join(root, 'waiter.log');
const fd = openSync(log, 'wx', 0o600);
const child = spawn(process.execPath, [join(repo, 'scripts/desktop-suggestion-trace-launch.mjs'), '--wait-and-launch', root], {
  cwd: repo,
  detached: true,
  stdio: ['ignore', fd, fd],
});
try { await once(child, 'spawn'); } finally { closeSync(fd); }
child.unref();
const processIdentity = execFileSync('/bin/ps', ['-p', String(child.pid), '-o', 'lstart=,comm='], {
  encoding: 'utf8', env: { ...process.env, LC_ALL: 'C' },
}).trim();
writeFileSync(join(root, 'waiter-process.json'), JSON.stringify({
  pid: child.pid, recordedAt: new Date().toISOString(), processIdentity,
  node: process.execPath, intent, intentSha256, log,
}) + '\n', { mode: 0o600, flag: 'wx' });
console.log(JSON.stringify({ waiterPid: child.pid, waitingVerified: false }));
NODE
```

命令返回仅说明启动了后台进程，尚不能提示退出。随后在一次有界本地检查中等待 `waiting.json` 出现（例如最多五秒），核对以下各项；未就绪立即按下一节报告，不让模型反复轮询或重新运行启动命令：

- `waiter-process.json` 的 PID 是该 Node 等待器，`ps` 的启动时间和可执行路径与保存的 `processIdentity` 一致，进程仍存活且不是僵尸。只有 PID 存在或旧 `waiting.json` 存在不够。
- `waiting.json` 的 PID 相同、`phase` 为 `waiting-for-normal-quit`，`deadline` 尚未到达；保存其实际截止时间与意图、日志位置。核对原应用及直属执行器仍对应准备对象；若已进入启动阶段，直接核对回执和新进程，不再提示退出。
- 原 `launch-intent.json` 摘要未变，没有 `launch-claim` 或 `launch-receipt.json`，私有日志没有失败。`wait-claim` 是等待器的一次性占位，不是启动完成证明。检查失败时保留这些文件，不移除占位或改写意图来重试。

按下一节启用 Host 观察器，保留其全部原配置；发退出提示前再次确认上述等待状态与剩余窗口。只有这些检查通过才提示用户：

> 专用等待器已运行并核对，截止时间为〈本次实际本地时间及时区〉。请保存工作后正常退出 Codex；退出后它会自动通过官方 LaunchServices 带 trace 环境重新打开，无需再点普通图标或运行启动器。请在截止时间前退出；若已过期，先核对状态再安排。

不强退，不使用 `open -n`，不更改官方安装包、配置、默认图标或全局环境，不启动第二个原执行器。安装指纹变化时停止这次启动；这里只防止准备对象在执行前变化，不是 Codex 版本门槛。当前 macOS 工具使用官方默认安装路径，路径不符时在主库实现并验证适配，不在目标临时改写脚本。

### 3. 分清手动入口与失败状态

`launch-original-codex.command` 固定调用 `--launch`：只供用户明确选择的“已正常退出后手动启动”窗口使用，不会等待，也不会后台常驻；原进程仍在时拒绝启动。仅本次诊断的自动流程使用已运行的 `--wait-and-launch`，不要再让用户点击 `.command` 或同时从普通图标打开。两者共享同一 `launch-claim`；不能并行使用，也不能把手动入口文件存在写成等待器已启动。

| 实际状态                                                         | 处理与准确报告                                                                                                                                                                                                                   |
| ---------------------------------------------------------------- | -------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------- |
| 已有有效本机 trace                                               | 复用实际目录；只在 Host 需更新时按原入口重启 Host，不要求原客户端重复退出。                                                                                                                                                      |
| 只有 prepare/check，尚未尝试等待                                 | 只报告准备完成；本次诊断模式继续启动并核对独立等待器，再提示退出。                                                                                                                                                               |
| 已尝试等待，但等待器未活、身份不符或五秒内未就绪                 | 报告“尚未确认等待器运行，请保持当前客户端”；保留私有日志、意图及占位。先核对旧进程和启动结果，不重新提交同一意图。                                                                                                               |
| `timed-out-without-launch`                                       | 报告“已超时，本等待器没有启动原客户端”，不再叫用户按旧窗口退出。确认旧等待器已结束且无启动请求／新 trace 后，才可在同一本次诊断任务内准备新空目录和新窗口。                                                                      |
| `installation-changed-without-launch`，或 check 报安装／意图变化 | 停止本次流程并核对变更；不能改写旧指纹或重放旧意图。确认没有启动和活跃等待器后才重新准备。                                                                                                                                       |
| 已有 `wait-claim`、`launch-claim`、回执或新 trace                | 先核对原尝试的进程、日志、回执、实际原客户端环境和 trace；占位不等于成功，缺回执也不等于未启动。有效等待器仍活且匹配时继续同一窗口，不起第二个；只有准确确认未启动且旧等待器已结束才可另排窗口。启动未知时不自动新建意图或重放。 |

即使没有 `launch-claim`，未知状态下仍先核对原应用与新 trace，不能推定允许重放。等待器已结束、安装变化及超时均需保留原证据；不为恢复而强退原桌面、解除占位或启动第二实例。

## 启用日常 Host

在稳定程序目录中运行升级后的服务脚本，显式指定原状态目录。原名称、默认工作目录、端口、Node、程序路径、工作目录、通知和创建配置均应从当前服务保留；不要用示例值替换已有值。

```bash
HOST_AGENT_STATE_DIR='/path/to/existing/host-state' \
CODEX_DESKTOP_SUGGESTION_OBSERVER=1 \
CODEX_DESKTOP_SUGGESTION_TRACE_ROOT="$SUGGESTION_TRACE_ROOT" \
"$SUGGESTION_NODE" scripts/desktop-service.mjs restart
```

脚本持久保存这两个 Host 开关，保留既有 Node／入口和工作目录；确认稳定入口本身已更新为同一统一包，再重启。既有客户端已开启 trace 时，将最后一项指向该本机已验证目录，而不是新建空目录。不要把别机 trace 或观察结果移植到本机 Host。

已有 trace 分支也要将 `SUGGESTION_TRACE_ROOT` 设为该实际目录，并从原配置取得 `SUGGESTION_NODE` 与稳定程序目录；不要为了套用首次准备的示例创建新目录。变更前后比对私有配置，除这两个观察器变量及本次已授权程序升级外，其余原值全部保持；仅更新本文／SKILL 时不重启 Host。

观察器使用随包 Python 工具与 macOS 现有完成通知；确认 `/usr/bin/python3` 可用。只有观察器活着、私有 trace 目录可读时才有 `desktop:suggestions:read:v1`。目录存在或 health 声明能力都不证明新建议可读；实际新来源是必要验收。未配置、原桌面断开、来源运行中、冲突或绑定不完整时不投影建议，其他原有功能应保持可用。

漏通知恢复采用有界原生补读：通过准确 fork 来源、原生 cwd 元数据、app-generated 请求、四个执行／来源 ID、完整成功响应与原生 `task_complete` 正文取得既有建议，不重新生成或补造通知。当前生产先排除旧桌面实例，再按原生请求开始时间选择唯一最新执行；最新未完成、失败、空结果或请求时间无法消歧时返回空，不回退旧正文。返回 `source:native-trace`、`validity:native-version` 和六秒读取租期，手机前台每两秒刷新；`source-turn` 是历史协议，不作为新生产 scope 的无限期有效依据。原通知冲突仍拒绝。原客户端当前 trace 已有效开启时，更新 Host 无需再次重启原 Codex；只有旧目录或旧记录不构成这一条件。检查真实手机时保留原连接、App、草稿与通知证据，分别记录 HTTP 来源、正文核对与手机结果；辅助测试不能代替真机结果。

## 自动重开后的独立核对

**`launch-receipt.json` 只证明请求过启动，不证明 trace 已生效。** 对真正的原客户端及其直属执行器，重新核对用户、PID、启动时间、父子关系和实际 trace 环境均指向这次私有目录，排除 history reader 或其他实例。核对本次新产生的原生请求／返回、原 owner 恢复及准确来源 Thread／Turn 绑定；没有自然新记录时保留待验证，不重放旧模型输入制造证明。

分别记录等待器／启动请求、进程环境、Host 观察器与能力、实际建议来源／正文，以及手机展示稳定性。启动回执、health 能力、HTTP 来源核对、手机实际结果不能互相替代；未验收的手机或其他电脑不写成通过，已完成且指纹匹配的旧场景不重复执行。

仅本次诊断的启动环境不会自动保留到下一次普通图标启动；之后先检查实际环境再安排启用。采用默认持久环境时，普通图标重开可继承已核对的登录域设置，无需每次再启动一次性等待器。两种方式都不能以 Host 登录自启证明原 Codex 当前已采集，也不把普通重开通过扩大为整机登录时序通过。

## 手机原有连接的真实验收

先记录手机完整连接列表和目标原连接，保留用户原草稿。通过已授权的专用来源聊天正常完成模型轮次，让原 Codex 自行生成预测；不能靠重写过期时间、复制旧 Host 观察记录或给已有用户聊天重复发送测试输入制造通过。原 Host 的观察器必须在生成时已经启动；旧预测的原生 trace 即使保留，也不代表本 Host 曾捕获通知。

核对 `GET /v1/threads/next-turn-suggestion` 的完整 target、threadId、sourceTurnId、正文 SHA-256；`runtime.suggestionSourceTurnId` 来自当前原 owner。再使用普通安装的原生手机 App 和**原有连接**，不新加测试连接、不修改地址或密钥，实际检查：

- 空输入框显示灰字；展开全文不改草稿。点采用才填入，正文一致，无自动发送。
- 撤销恢复原草稿；继续编辑后旧撤销失效，不能覆盖新输入。
- 已有正文时没有直接覆盖入口；默认追加且原文原样保留，替换另行确认，取消保留原文。
- 至少两个准确来源切换不串聊天或轮次，草稿按目标保留。手机阶段前后独立读取原 owner，核对没有新增模型输入。

仅清理本任务已知、字节完全匹配的测试草稿，不清空用户原草稿。完成后核对完整连接列表、选择、Host ID、凭据／证书／配置摘要及 Serve 配置保持；无临时连接才可记录“原连接验证”。真实手机、原桌面来源、HTTP、编译和 fixture 各自记证，DEV 连接的通过不替代日常连接。

## 建议读取较慢时

若建议反复消失、重现，分别核对手机状态原因与正式 Host 的历史／事件来源是否稳定。Host 的原 owner 复核不得在等待期间先清空已确认来源；这一修复需要部署统一新版 Host，单独安装手机包不能修复该事件空档。实际复核失败、新轮次和 owner 不可用仍清除资格；不能通过持续显示旧来源或改写时间掩盖错误。

分别测量原生证据扫描、Host HTTPS、手机历史确认、建议返回和提示挂载，不把连续 XCTest 查询的上界当作界面显示时间。新版 Adapter 的原生 trace 校验按目录与文件线性完成；手机将来源候选的只读请求与历史加载并行，并在新历史确认来源后发布。首次没有候选时仍需先获得真实来源，网络慢或预测尚未生成时不能保证一秒显示；不得用旧轮次、改写时间或生成替代建议来提速。

只复测发生变化的场景，复用匹配实现和环境指纹的既有采用／撤销等证据。建议速度优化不需要轮换密钥、重建手机连接、删除草稿或重复开启 trace。新增移动端优化需要更新原手机 App；只更新 Host／Skill 不能替代手机版本升级。

## 关闭与恢复

关闭提取时把 Host 的 `CODEX_DESKTOP_SUGGESTION_OBSERVER=0` 持久保存并重启本产品 Host；保留原状态、密钥、草稿和已提交操作。已经采用的文字是用户本机草稿，不随建议失效清除。

若要停止原生诊断记录，仅在用户明确要求关闭或回退持久 Trace 时，按平台使用安装时的同一私有状态目录，在随包稳定程序目录执行：

macOS：

```bash
'<原 Node 24 绝对路径>' scripts/desktop-suggestion-trace-environment.mjs disable --state '<原私有状态目录>'
```

Windows 原生 PowerShell：

```powershell
& '<原 Node 24 绝对路径>' scripts/windows-suggestion-trace-environment.mjs disable '<原私有状态目录>'
```

原私有状态目录是包含 `environment.json` 的安装目录，不是其 `traces` 子目录；复用实际安装路径，不新建或改用默认目录。macOS 使用 `--state` 参数，Windows 使用位置参数。关闭后核对未来环境；仅本次诊断不修改持久环境。之后由用户选择正常退出、按普通官方入口重开的时机，当前进程不能动态解除环境。不强退、不修改官方应用；私有诊断记录按用户要求保留或另行清理，不随升级自动删除。

## 不同项目的建议为空时

desktop-runtime 的 HOST_AGENT_ROOT 只保留历史默认 cwd 语义，不参与建议授权或就绪检查。建议只读私有 Trace，以当前原桌面实例、准确原 owner、最新完成轮及四个来源／执行 ID 验证；原项目目录不存在、移动或被替换，不会单独使建议失效。私有 Trace 的目录、文件、权限、完整性与最新结果校验及六秒租期继续生效。手机不传任意目录，也不触发模型生成。源码、辅助检查和逐机真实桌面／手机结果分别记录。

## 建议存储与 Trace 自动回收（2026-10-30）

新包同时升级 reader、精简存储和回收 worker。用户选择每会话仅保留最新一条建议时，在原正式 Host 环境显式设置 CODEX_DESKTOP_TRACE_RETENTION=1，并确认该环境在原服务配置持久保存；保留机器身份、证书、设备密钥、原端口/Node/Trace根与其他已开启功能，只重启本产品Host。不要让隔离验收进程在同一正式Trace根开启回收。成功建议/普通执行的封闭且无占用Trace尽快回收，取消/失败/空结果保留24小时；未知格式、来源/文件身份矛盾、仍在写入或无法确认占用时保留并报告。每会话一条最新建议或失效状态，按原请求顺序防止旧建议复活。部署后核对私有trace-retention状态计数、精简记录可读与实际释放字节，手机仍需单列准确来源展示/采用验收。未决删除意图及隔离目录需要核对，不盲目重放。当前未提供活动Trace轮转或硬容量删除策略。

## 原生结果采集升级（2026-10-30，候选）

若原始 Trace 只有内部取消而桌面稍后存在建议，部署统一包中的被动原 CLI 结果观察器。macOS 通过 desktop-native-suggestions-environment.mjs 复用共享通道；Windows 的 C# shim 安装已因阻断原 Codex 启动风险停用（2026-11-01），不得再设置用户或系统级 `CODEX_CLI_PATH` 指向它。升级先运行同包 `windows-native-suggestions-environment.mjs disable`（自定义目录加 `--state <原目录>`），仅删除经私有清单核对的原 HKCU 覆盖。`install` 兼容入口也只恢复旧覆盖，报告 `WINDOWS_NATIVE_INSTALLATION_SUSPENDED` 并非零退出；不得绕过该拒绝手写注册表。配置损坏、旧摘要或旧应用路径不妨碍有归属证明的恢复；缺少或不可信清单则保留现场并报告。保留垫片及配置以照顾已继承旧环境的进程，不改名或删除、不强退桌面。Windows 被动结果采集暂不可安装，不能报告建议已恢复；已有 Trace 读取按原实际能力核对。恢复 HKCU 不会修改运行中进程的环境，分别核对未来启动与当前原桌面。Host 显式设置 CODEX_DESKTOP_NATIVE_SUGGESTIONS_CONFIG，保留原身份、Trace 路径、原 Node 和服务端口。该路径观察原请求/返回并等待原安全判定，不触发建议生成。先准备未来启动配置，再由用户正常重开原桌面；不自动强退或重启。原进程仍使用旧观察器时必须报告未生效。

### 冻结 R2 与更新恢复包的升级边界

已有 producer、保留策略及私有路径继续保留，文档同步不自动开启回收、重装 observer 或要求原桌面重开；仅未来启动配置实际改变时才等待用户正常重开。Windows producer 自身的签名／Store／父子进程核对与通用桌面连接的既有 Publisher 契约分开。退役仅在 macOS／Windows 实现；Linux 保留原始 Trace，不新增 Linux Host 支持。本轮统一源码及辅助检查不代替目标 Windows、手机建议展示或实际安装更新验收。

## macOS 采集器停止原因与私有日志（2026-11-04，源码候选）

原桌面长时间运行会创建很多零字节 worker 日志。新版仍有界枚举准确原 PID 的当前／前一天日志，但空且未跟踪的文件不占 16 个正文候选名额；每次扫描重新检查，增长后从零读取。已跟踪文件变空、截短、路径或身份变化仍拒绝，不能用忽略空文件绕过原证据守卫。非空文件、目录项、每 tick 文件数和字节预算不变。

新版 macOS producer 在原私有 Trace 根的 `.agent-hub-suggestions/<目标键摘要>/native-diagnostics/producer.jsonl` 记录 starting/listening、停止／关闭、固定错误码及 pending/completed/cancelled 等保存状态。记录绑定 producer epoch、PID／启动时间、实现摘要和来源 Thread／Turn；包含阶段、心跳年龄、tick 间隔、阶段耗时及候选／空文件／目录项计数。不记录建议正文、模型上下文、凭据、原始错误或完整文件路径。当前及两份轮转各约 128 KiB，目录 0700、文件 0600；日志失败只报固定标识，不改变原字节转发或采集结果。Windows 仍沿用原固定 status 通道，本次不启用额外同步 ACL 日志写入。

先保存停止事件与相邻的采集状态，再核对 Host 原 owner 和原生日志；内部 Trace 的 inference_cancelled 不等于原桌面最终没有建议。原生结果／安全决定已出现而 producer 没有结果记录时，定位 producer 阶段与具体错误，不能只让用户重复重启。日志新增不能补造升级前的首次停止原因。部署记录须区分源码候选、当前已加载程序及正常重开后生效；不要原地覆盖当前进程的 Adapter 或更改活跃 producer 配置来制造恢复。
