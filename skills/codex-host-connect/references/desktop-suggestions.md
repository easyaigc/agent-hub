# 原桌面下一轮建议：启用、升级与实测

当前 macOS 实现读取原 Codex 已经生成的建议，不调用预测生成接口，不采用或发送桌面建议。请求中的来源 Thread／Turn 与预测 Thread／Turn 精确连接，响应正文须与原完成通知一致；手机按完整机器目标、来源轮次、摘要和响应声明的有效性读取。Windows 原生 x64 适配复用公共解析器，以当前原实例与句柄／DACL 校验直接读取准确来源的 Trace，不模拟 macOS 通知；源码实现、正式部署、正常重开、新记录与手机验收分别记录。见 [Windows 安装步骤](windows.md#trace-与下一轮建议)。

## 为什么开启 Trace，推荐怎么安装

Trace 是原 Codex 的本机运行记录。当前产品用它把“桌面已经生成的下一步建议”与准确聊天和来源轮次连接起来；这条原生建议同步链路需要 Trace，单独升级 Host 或创建空目录不能取得建议正文。

| 信息或行为                               | 当前实现与用户收益                                                                                                                                                                   |
| ---------------------------------------- | ------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------ |
| 下一步建议正文、来源聊天与轮次           | 已实现：手机输入框显示两行灰字，可展开全文；点采用才填草稿，可撤销，不自动发送，也不额外请求模型生成建议。                                                                           |
| 同轮次的新建议版本                       | 已实现：前台约每两秒读取，使用六秒租期确认当前结果；新的、空的或失效结果会更新，不把过期建议当作实时内容。它证明原生生成结果，尚不等于桌面输入框此刻的显隐。                         |
| 普通消息、任务进度、完成、错误及工具反馈 | 由现有运行时／事件通道提供，不要求开启 Trace；Trace 不是这些功能的统一开关。                                                                                                         |
| 原生请求／返回、时间和关联 ID            | Trace 在本机保留请求／返回、时间、关联 ID；原格式还记录模型／提供方、轮次及工具运行开始／结束与状态，可用于排查来源、失败与延迟。当前手机只投影校验后的建议，没有通用 Trace 查看器。 |
| 更细的请求耗时、失败原因与执行诊断       | 可按 Trace 实际包含的字段继续扩展分析；属于后续能力，不宣称目前已向手机展示，不能据此承诺不存在的字段或完整屏幕镜像。                                                                |

本机诊断字段的依据是当前固定版本的 [OpenAI 原生 Trace 源码](https://github.com/openai/codex/blob/rust-v0.163.0-alpha.10.2/codex-rs/rollout-trace/src/thread.rs)，实际字段以目标执行器生成结果为准。

**推荐持久开启，并作为完整安装的默认步骤。** 安装程序和 Agent 完成固定登录环境、私有目录、Host 观察器和状态核对，用户不需要每次启动前运行脚本。首次配置后，用户择机正常退出 Codex，再从原图标打开；安装 Agent 不强退原应用。仅本次诊断是用户明确选择的排查方式，不是每次安装都要走的默认流程。

交付时用一句话说明：原始 Trace 默认留在本机私有目录，本功能仅经已有连接提供校验后的建议；macOS 登录域设置也可能由同用户后续 Codex CLI 继承。常规安装继续执行，不另设“是否允许记录”“是否允许准备目录”等多轮授权门槛。用户明确关闭或仅做文档同步时按其范围执行。实际系统登录、权限失败和配置冲突照实处理，不绕过操作系统或覆盖无关设置。Trace 临时不可用时继续完成其余接入，不阻断普通消息、进度和控制；单列可修复缺项与具体下一步。

## 每次安装与升级的收尾

每次适用平台的完整接入与升级都检查并完成此项，先解释上述用途和默认方案，再直接核对与配置。普通安装不等待额外 Trace 选择，用户已有明确选择时保留。只写“未启用／未验收”而没有具体下一步，仍属于收尾未完成。

| 当前状态                             | 安装及交付的下一步                                                                                       |
| ------------------------------------ | -------------------------------------------------------------------------------------------------------- |
| 已有有效持久配置、当前进程和新 Trace | 复用并报告实际结果，不重复安装或要求重开。                                                               |
| 尚未配置且用户未明确关闭             | 默认安装持久环境和 Host 观察器，核对实际路径与未来环境；就绪后交付正常重开的具体动作，不再追加授权问答。 |
| 用户明确暂不开启或缩小范围           | 保留选择，说明原生建议同步暂不可用；其他已配置功能照常使用，不反复询问。                                 |
| 用户明确仅本次诊断                   | 按下文准备并实际启动独立等待器，核对存活与截止时间，才提示正常退出后自动重开。                           |
| 未来环境已就绪，当前进程尚未继承     | 报告“持久配置已完成，待正常重开生效”；新 Trace 与手机效果另验，不把未来配置冒称当前采集。                |
| 实际系统登录、权限或无关环境冲突     | 交付已完成项和具体需处理的系统动作，保留身份、工作与原设置；不新增业务授权关卡。                         |
| Windows                              | 使用 Windows 专用持久工具和私有 DACL；注册表、原进程、新记录、Host／手机逐项核对，不用 macOS 命令。      |

推荐交付话术（用本次真实结果替换状态）：

> 已配置持久 Trace，用于把桌面 Codex 已生成的下一步建议同步到手机。手机可查看、采用到草稿和撤销，不会自动发送。记录保存在本机私有目录；此 Mac 的登录环境也可能由同用户后续 Codex CLI 继承。未来启动环境已核对，请保存工作后择机正常退出 Codex，再从原图标打开，无需一次性启动器。当前进程、新记录和手机效果分别为〈实际状态〉。

最终回复按用户能理解的顺序给出：用途与推荐方案、已完成的配置、当前是否生效、具体下一步。脚本路径、目录、摘要、原进程与 Host／手机证明放在需要核对的技术记录中，不把内部等待器术语当作默认安装说明。macOS 持久工具为 `scripts/desktop-suggestion-trace-environment.mjs`，Windows 为 `scripts/windows-suggestion-trace-environment.mjs`；一次性诊断另用 `scripts/desktop-suggestion-trace-launch.mjs` 的实际 `--prepare`／`--check`。仅有脚本文件不等于当前已生效。

## 先保留日常连接

从当前服务取得真实状态目录、稳定程序路径、Node、工作目录、名称、端口、默认工作目录、通知与桥接配置；保存私有备份及文件摘要。核对 Host ID、工作区／runtime、证书、设备绑定和令牌文件，升级后使用相同值。不要输出密钥原文，不改手机原连接地址，不复制另一台电脑的状态。

先合并统一包的程序差异并保留目标独有文件；本机独有程序修复先导出到主开发仓库审查，不在目标另开源码标准。Task Hub 及其数据不因这项升级而迁移。包 SHA-256、实际部署的 Adapter／Host 摘要和本机真实验收分别记录。

## 两个进程、两组环境

| 进程                        | 配置                                                                                    | 生效条件                                                  |
| --------------------------- | --------------------------------------------------------------------------------------- | --------------------------------------------------------- |
| 原 Codex 客户端及其原执行器 | `CODEX_ROLLOUT_TRACE_ROOT=<本机私有 trace 根目录>`                                      | 必须在该原客户端启动前设置；当前进程不能动态开启          |
| 本产品 Host                 | `CODEX_DESKTOP_SUGGESTION_OBSERVER=1`、`CODEX_DESKTOP_SUGGESTION_TRACE_ROOT=<同一目录>` | 按既有服务配置重启 Host，观察器和目录实际就绪后才声明能力 |

先确认同一用户的原客户端、直属执行器和原 owner；独立 history reader 不算原执行器。只核对这两个目标进程的 `CODEX_ROLLOUT_TRACE_ROOT`，不输出完整环境或无关凭据。若两者已指向同一本机私有可读目录，复用该实际目录供 Host 观察；有当前实例的新原生记录时保留其指纹。仅有环境但尚无新记录时记为待验证，不能宣称已采集，也不为等候自然生成而重复重启。目录存在、旧回执或 Host 的观察器变量不能证明原客户端已开启 trace；两进程环境不一致时先查明，不能当作有效复用。

首次没有有效 Trace 时，默认按下一节配置持久环境并保存原 Host 私有备份，核对就绪后才交付正常重开的动作。用户选择仅本次诊断时，改用后面的一次性准备和等待器；正常退出前完成对应工具核对，不先让用户重启再补配置。Trace 会记录完整请求／返回并附加诊断请求标识，随用途简要告知，不另起授权问答。

## macOS 默认持久采集

完整安装默认用随包 `scripts/desktop-suggestion-trace-environment.mjs install` 配置当前用户的固定登录环境，再用 `status` 核对；已有匹配配置可直接复用。`disable` 保留记录并恢复之前的设置。配置在用户私有目录，程序不会退出、重启或附加原桌面。此方式会使同用户后续 GUI 子进程继承该键，其他 Codex CLI 也可能记录完整请求／返回，安装说明中简要告知即可；保留用户明确关闭的选择。

持久环境核对成功后，用户可选择时机正常退出、从原图标重开，无需一次性携带环境启动器。Host 的 trace 路径须同步为工具返回的实际路径；保留原身份、凭据与连接。当前进程不能动态生效，实际新记录与普通重开分别验收。登录自动恢复应用可能早于 RunAtLoad，需要独立验证，不能把应用普通重开通过扩大为整机登录排序通过。

新 Host 声明 `desktop:suggestions:native-version:v1`，按当前实例内准确来源的原生请求顺序选择最新结果；不以最后完成或文件修改时间决定版本。最新未完成、失败、空结果或无法消歧时不回退旧结果。iOS 对 `native-trace`／`native-version` 使用有限租期并前台每两秒刷新，同轮次也更新；不标为桌面实际展示证明，用户草稿和发送保持独立。

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

若原始 Trace 只有内部取消而桌面稍后存在建议，部署统一包中的被动原 CLI 结果观察器。macOS 通过 desktop-native-suggestions-environment.mjs 复用共享通道；Windows 通过 windows-native-suggestions-environment.mjs 安装私有已校验 C# shim。Host 显式设置 CODEX_DESKTOP_NATIVE_SUGGESTIONS_CONFIG，保留原身份、Trace 路径、原 Node 和服务端口。该路径观察原请求/返回并等待原安全判定，不触发建议生成。先准备未来启动配置，再由用户正常重开原桌面；不自动强退或重启。原进程仍使用旧观察器时必须报告未生效。

### 冻结 R2 与更新恢复包的升级边界

已有 producer、保留策略及私有路径继续保留，文档同步不自动开启回收、重装 observer 或要求原桌面重开；仅未来启动配置实际改变时才等待用户正常重开。Windows producer 自身的签名／Store／父子进程核对与通用桌面连接的既有 Publisher 契约分开。退役仅在 macOS／Windows 实现；Linux 保留原始 Trace，不新增 Linux Host 支持。本轮统一源码及辅助检查不代替目标 Windows、手机建议展示或实际安装更新验收。
