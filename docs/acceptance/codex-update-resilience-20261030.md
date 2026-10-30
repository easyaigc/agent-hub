# Codex 更新恢复（2026-10-30）

范围：F02／F03 Host 独立工作区目录 reader，与主桌面 runtime 的安装路径恢复。源码基线为 Gitea main `c7a6f95e03f3ef18403c68518cb403103cf633fd`；不合入主工作区并行未提交功能，不修改网络或桌面安装。验收以源码提交与统一包指纹为准。

## 根因与已实现恢复

启动 worker 会刷新安装配置，主 runtime 断连也会核对可信 owner／CLI，但 `/v1/workspace` 使用另一只固定启动环境路径的 reader。该 reader 的初始化 spawn 失败不触发主 IPC 的断连；于是服务健康／IPC 就绪与目录可用性分离，重试同一旧路径始终失败。

本次共享安装发现及下一连接 CLI 提供者，保留有界发现、当前 owner 信任、协议检查与退出屏障。并发合并，成功快照最多缓存 30 秒，缺失／传输故障可提前发现；发现失败和目录连续失败均以 1–15 秒退避。只读目录传输失败最多重读一次，退出未确认和协议错误不在同一次刷新内重试；写操作没有重放。健康状态单列目录，失败准确返回 503。

## 分层证据

- 源码实现：本轮候选，新增恢复边界及既有 history、desktop IPC、runtime、创建、聊天管理和分叉恢复检查已通过；完整 `pnpm check` 本轮已退出 0，最终静态／类型及针对性检查另外绑定最终输入 SHA；精确提交和统一包指纹见交付清单。
- 本机 macOS：最终 Adapter 五个相关模块 SHA 绑定的真实 socket／内置 CLI 只读检查通过，两次完整目录均为 15 个项目、993 个会话，主 IPC／主 reader／辅助 reader 保持同实例。未发送模型任务；不是 GUI、手机或真实更新验收。
- 来源 Windows：现有诊断与手工服务恢复是来源证据，本次产物尚需来源部署、可信发现及独立真实更新／重开验收。
- iOS／Android：本轮不新增功能或原生通过；复用现有 App 和连接，A6 保持暂停。

## Windows 最小独立验收

1. 核对可信 GitHub Release、源码提交、ZIP 和 `bundle.json`／实际 ESM 闭包指纹，保存正式程序及必要回滚资料。保留 Host ID、token、设备授权、证书 pin、roots、端口、服务路径、原模型与输入回执；不复制其他电脑配置。
2. 使用原计划任务和 Node 部署同包，不强退 Codex；核对新 Host PID／入口、登录自启及周期恢复。回环与既有 HTTPS 鉴权读取 `/healthz`、`/v1/workspace`，分别记录 Host 存活、runtime 与目录状态，不能只看任务 Running。
3. 在确认没有业务任务需打断的正常更新／正常重开窗口，记录原 owner PID、启动时间、CLI 路径以及新 owner／CLI。保持同一 Host 进程，轮询只读目录，记录短暂 503／degraded 到恢复 200 的实际时间和准确 owner，确认无需重装、登录或手工重启 Host。不得为了制造场景删改官方文件、路径或历史。没有实际更新时明确记为待验。
4. 并发目录读取应共享恢复；无可信替代／协议不匹配时准确失败，旧 reader 退出未确认不得启动第二只；在包目录运行 `node --test scripts/desktop-update-recovery.test.mjs`、`node scripts/windows-host-recovery-smoke.mjs` 和 `node scripts/history-smoke.mjs` 辅助补证，不能代替原生签名／更新证据。
5. 对已有未知操作仅查询原 receipt，核对正文／目标／ID／状态未被更换，没有新增输入；保留原生证据。手机仍使用原连接，读项目／聊天，核对原草稿与目标，无额外模型提交。
6. 回滚仅恢复本次程序／SKILL 字节并使用原服务重新载入，私有配置、回执、设备凭据与桌面版本不回滚。独立故障项不能通过切 tailnet／关闭证书验证掩盖。

保留最小脱敏测试摘要和操作回执；临时进程、目录、依赖和构建产物在不再需要后回收。当前专用聊天由来源核验后收尾，本机不自行归档。

目录 reader 失效或刷新失败后有单个后台只读恢复定时器，失败按既定退避继续；完整刷新成功后停止，关闭后清除且不能发布晚到目录。恢复不依赖手机或协调工具发起下一次读取，也不放宽其 health 校验。

## 本次辅助场景

新增恢复脚本覆盖真实 Node reader 关闭／stdio 屏障、退出未确认不替换、同路径代次变化、严格只读、坏协议、辅助 reader 故障且主 IPC 保持 ready 的后台恢复、关闭后无后台重试、关闭竞态，以及单次目录最多重试一次与退避。Windows OS 身份夹具另覆盖 12 个并发请求共用发现、旧配置路径消失、bundled／loose CLI 迁移、同路径文件身份变化立即失效、无可信替代和恢复、配置字节不变。夹具不等于 Windows 原机结果。

既有重连测试改为等待实际 reader 启动信号，保留原并发／关闭断言；四处原型跳过构造的 fixture 改用真实 DesktopRuntime 构造后注入原 metadata／IPC mock，保留创建确认、附件和管理操作的原预期。

## 冻结打包阻塞与准确源码登记

首次干净冻结构建通过六组件编译，但打包拒绝规范主线已跟踪的 NaturalProcessVisibility.java。完整路径审查另发现 OwnedNaturalNoticeObserver.java 与两个 Natural 诊断 JVM 向量同样未登记。它们分别是固定诊断解析器、独立验收观察器及合成向量，已存在于公开源码；本轮不修改或执行其 Android 行为。打包器仅增加这四个准确路径，ZIP 回归核对逐项字节保留，并继续拒绝三个对应目录中的未知 Java 文件、未知录音、改动的固定媒体和孤儿编译 JS。没有开放 Java 扩展名或目录通配。正式包须从补丁后的提交重新冻结，不复用失败候选的 dist。

## Windows 辅助夹具环境修复

来源 Windows 在首个 HTTP 等待条件超时，尚未执行后续 runtime-degraded 断言。夹具先为模拟安装发现设置 SystemRoot、LOCALAPPDATA、PSModulePath，却只恢复 platform 与 execFileSync；随后真实 Node HTTP 子进程继承了合成环境。三项环境直到 finally 才恢复，边界过晚。来源独立最小对照中，原环境子进程成功监听，合成 SystemRoot 的子进程退出 134 且未监听。这支持环境污染机制，但不是对原失败运行子进程退出原因的直接捕获。

本次仅修复辅助夹具：在真实 spawn 前恢复三项原环境，并由真实子进程写出环境摘要供父进程核对；不输出环境原值。发现子进程退出或 spawn 失败时报告实际退出码／信号及有限探测状态，超时也附带诊断计数。保留原 HTTP 超时、等待预算、四种状态转换、只启动一次、健康探测不得杀活进程与停止后不再探测的断言。未改变 Host 产品、publisher-not-checked 契约或恢复时限。

macOS 辅助检查包括正常夹具、缺少提前环境恢复的反事实、子进程提前退出的反事实及受影响检查；准确结果绑定新制品交付清单。核心源码与编译闭包指纹完全匹配时，复用前述恢复与真实只读证据。Windows 须从新独立 tag 取包重跑 `node scripts/windows-host-recovery-smoke.mjs`，再继续原隔离只读场景；macOS 通过不代替 Windows 原机通过。旧包、tag 与原失败回执保留，不能覆盖为成功。

## Windows 私有状态启动失败的独立诊断

来源真实只读验收在 reader 创建前收到 `WINDOWS_PRIVATE_STATE_UNAVAILABLE / startup`。同一配置文件的旧 backend 检查成功，新 backend 失败；新旧管道 owner 检查均成功。这是尚未定位到底层原因的原生启动阻塞，不能算作更新恢复通过，也不能用延长预算或改权限消除。

主线程直接导入后调用 `assertPrivateFile` 是受支持路径：它创建固定 transport Worker；owning Worker 则经实际 Main 注册的固定 supervisor 创建 transport。此处不存在脚本名称或 argv 白名单。标准 ZIP 的实际 node_modules 中，backend、worker、platform、process gate、diagnostics JS 与 private-state.ps1／cs 七项均存在；这不代替来源实际展开目录的逐项核对。旧 backend 的 edd257e1 指纹匹配 b000ead4 对应源码，新 adf71113 匹配 b28a9b6e 对应源码；这个匹配仅用于单个模块溯源，不能据此推断旧安装整包 SHA 或故障因果。

本轮补齐诊断缺口：独立 32 字节共享区域只携带固定阶段、首个失败类别、白名单错误码、退出状态及限定的 bootstrap／原生 ACL 错误类型。同步 caller 阻塞时也可读取，不依赖延迟到达的诊断事件。它与权限响应分离，不能授权访问、重试操作或改变退出屏障；原 30 秒启动、5 秒操作与 1 秒关闭预算不变，Native C# 权限实现及 publisher 契约不变。PowerShell 仅增加固定错误标签，不输出路径、SID、错误正文或原生日志。

新的 `scripts/windows-private-state-preflight.mjs` 是隔离只读入口。在新统一包 `codex-host-connect/assets/codex-manage` 目录，以原 Node 执行：

```powershell
& '<原 Node 绝对路径>' .\scripts\windows-private-state-preflight.mjs --file '<原私有 windows-desktop.json 绝对路径>'
```

入口固定核对原 Host 实际 ESM 闭包，再从同一 Adapter 实例核对七项资源指纹并执行配置文件 ACL 检查；不启动 Host、reader、模型或业务操作，不改调用者 argv／execArgv／NODE_OPTIONS，不保护或重写目标文件。后端自身的本机 helper 缓存初始化沿用既有产品路径。输出仅为有限 JSON，附目标文件元数据与调用者启动参数是否保持、关闭结果。它验证私有状态前置条件，不能替代 Host／owner／reader／真实更新验收。若失败，保留 failure 的 nativeStage、nativeFailure、nativeCode、nativeStatus、helperStage、nativeType、nativeWin32、nativeRejection，以及 resources 与闭包指纹；不发送配置或长日志。

macOS 的实际同步等待、owning Worker 经 Main 转发、缺失原生程序、错误握手与固定入口输入边界辅助检查绑定本轮输入；Windows 原生路径仍待来源独立执行。原环境修复脚本指纹与未改动的核心证据按匹配范围复用，不能把整个 Adapter 或 Windows 原机证明沿用为当前通过。

本轮交付基线对齐已合并 PR #67 的 main `622c30f419330c56111b68bdc6de366b9bce274d`；其 Host／Adapter／锁文件未改，保留其既有 Android 与文档内容。完整打包器补充四个该主线已跟踪的固定诊断源码准确路径：NativeFixedProcessCapture.java、NativePublicProcessCapture.java、OwnedPublicProcessDiagnosticObserver.java、OwnedPublicProcessNaturalObserver.java，均位于既有 android-inbox-examiner 源码目录。它们不是本轮新增的 Android 功能，本任务不修改或执行其行为。正向字节保留检查覆盖这四项，并沿用拒绝未知 Java 的负向断言；不开放扩展名或目录通配。

## Windows 默认 owner 与创建边界修复

来源机使用同源 `host-connect-native-preflight-20261030` 包，在正常 Main 中仅执行一次固定只读入口，失败定位到 `protect-directory / prepare-failed / WRONG_FILE_OWNER`。当前 token 的 User 与默认 Owner 不同：Node 新建 cache 目录默认归 Administrators，而既有严格检查要求 User。正式配置仍为 User owner；失败发生在 helper 编译前，不是 reader、协议或正式配置的 DACL 故障。

本轮在新建对象的安全描述符中显式指定 User owner 和受保护的限定 DACL。目录由 CreateDirectoryW 创建，文件以 CREATE_NEW 创建；既有对象继续严格核对 User，不接管任意 Administrators 对象，不使用所有权权限提升。私有状态、原子临时文件、后台异步写入及 Host 工具使用统一创建 API；打开文件后核对实际文件身份，再截断。编译输出先独占预留，编译后核对原文件身份和严格权限，发布不能覆盖已有 helper；缓存目录摘要同时绑定创建策略。旧失败目录保留，不迁移其 owner。

退出诊断保留合法 uint32 DWORD、原有有符号状态、0 与未知的区别。来源真实 Node 子进程给出的三个数值仅验证退出码表示，不能宣称实际 Ctrl-C 或 DLL 故障。权限控制共享区、退出确认屏障、30 秒启动／5 秒操作／1 秒关闭预算和未知操作不重放保持。

受影响回归结果与统一制品摘要登记在交付清单。macOS 的传输、合成权限和 HTTP 夹具均为辅助，Windows Native 分支跳过不计通过。来源机已确认上一环境修复夹具一次通过；本轮改动只在输入完全匹配时复用证据。正式 Windows Host 尚未替换，真实 Native 初始化、原桌面不变的独立 reader 恢复和实际更新窗口仍由来源机分别验收。iOS／Android 共用 Host，原生页面和协议不变，Android 未新增通过；A6 保持暂停。

本轮准备提交时主线已合入 PR #70（`c99684ba916aaf9e766904cb15165de28a46551b`）。它仅增加 Android 迁移/验收源码、共享文档及 Trace 夹具观测，Host/Adapter/Protocol 和锁文件未变。按功能 ID、变更 ID 保留双方 manifest 与文档；完整分发仅精确登记已合并的三项 Java 源码，增加逐项字节保留断言，未知 Java 继续拒绝。没有实现或验收新的 Android 功能。先前未发布候选包保留为中间证据，正式包必须绑定集成后的实际提交树重新冻结。
