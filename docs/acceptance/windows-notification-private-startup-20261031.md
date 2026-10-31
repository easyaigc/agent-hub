# Windows 通知私有创建与完整 owner 拒绝链

正式初始化已返回三次 `WRONG_FILE_OWNER`，PR86 事件没有 `nativeOwner`。来源已停止该代次并恢复原受管程序；运行任务不等于接口就绪。本项继续保留原身份、数据、权限和执行预算，不能把独立 `Get-Acl` 或只读 envelope 通过写成正式初始化通过。

## 固定产物的栈映射修正

PR86 的实际 ZIP 中，`desktop-platform.js:248` 调用的是 `privateState('protect-file', path)`；`runtime-server.js:195` 开始构造 `NotificationPublisher`。此前对这一行的“publisher token assert”解释不准确：真正的 token assert 在上一行，不能仅凭该栈认定被拒绝对象是 token。

通知构造器获取 `notifications.json.lock`，并在缺少状态时保存初始快照。旧实现使用普通 Node `openSync(...,'wx')` 创建锁和临时快照，再调用 Host 的 `protectFile`。当进程 token 的默认 Owner 为 Administrators 时，新对象可不是 User owner，而严格检查会正确拒绝。该调用链是产品缺陷，能产生所见 `protect-file` 路径；具体哪一个实机对象或代次失败，仍须绑定实际拒绝证据，不能倒写为已证实的唯一原因。

通知 Runtime 现通过窄回调取得 Host 现有 `openPrivateFileSync(path,'wx')` 和 `makePrivateDirectorySync`。Native 在创建时设置 User owner／私有 DACL，保留对象创建回执、句柄身份及释放确认；不先普通创建再接管 owner。锁和原子快照都经过同一入口，不能在拒绝时退回普通创建。已有通知状态照原格式读取；原私钥、设备、事件、回执及目标校验保持，未因启动修复迁写或接管旧对象。未来正常状态保存仍按既有写入／flush／原子 rename 边界发布。

## PR86 诊断缺口及修复

PR86 只有目标 check/protect 路径附加 owner 证据，创建文件和编译私有检查仍直接调用 `CheckOwner`。其采集器遇到某个元数据 API 或摘要失败会丢掉整份证据。bootstrap 还有独立缺口：PowerShell 没有输出 owner frame，原数值共享诊断区也没有携带 frame。

本项让三类检查都保留**已拒绝的同句柄／安全描述符**。`OWNER2` 独立采集各字段，失败字段缺省，并保留第一个固定采集阶段／异常类型；不输出原消息、路径、SID、文件正文或任意异常 Data。创建文件标记 `create-file`，编译句柄检查标记 `compilation-check`。编译检查没有独立的请求路径参数，因此请求路径摘要缺省，最终句柄路径另列；不得将二者伪装相同。

PowerShell 只转发 Native 私有类型生成的 frame；Node 严格解析后经有界共享诊断槽传递。原数值索引 0–10 保留，新增长度及最多 1024 字节的独立诊断区；现有 serve 响应仍最多 1024 字节。记录 `nativeOwnerOrigin` 为 `bootstrap-csharp` 或 `native-serve`，未产生证据时标记 `not-emitted`。bootstrap 的 PID 是 PowerShell 宿主，不冒充后续尚未启动的 serve helper。旧 `OWNER1` 响应仍可严格解析，但新冻结输入须整体一致。

Host 额外投影实际 private-state action、请求路径摘要和 `notification-lock`／`notification-snapshot` 固定角色。该角色来自调用点，不是从错误文字猜测。采集缺失、PID 不一致或 Native 拒绝均不能授予访问或自动重放未知操作。`CheckOwner`、`CheckAccess`、DACL、compiler Job、退出屏障、并发和原执行时限保持。

## 最小独立 Windows 前置

先核对新 Release 的源码、整包和实际 Host ESM／两份 Native 资源。保留现行可用服务，不在目标修改程序或 owner／DACL／UAC／任务级别。普通 Main 原 Node 执行：

```powershell
& $Node "$CandidateProgram/scripts/windows-private-state-preflight.mjs" --host-config $FormalHostConfig --bundle $CandidateBundle
& $Node "$CandidateProgram/scripts/windows-notification-state-preflight.mjs" --bundle $CandidateBundle --directory $NewExclusiveFixtureDirectory
```

第二项只使用实际 Host ESM 解析出的 Adapter 和 Notification Runtime，在尚不存在的独占目录内创建合成通知锁／快照、关闭并重开，检查私有对象、保留合成状态、释放锁和 backend。它不读取原通知状态、publisher token、Host 身份或配置，不启动 relay、HTTP、IPC、模型或正式 Host。该项会创建并在身份保持且没有额外条目时删除自己的 fixture；失败时只保留必要现场，不删除未知对象。完整 JSON 与真实 ExitCode 分别记录。

该独占目录内还用普通 Node 创建一个非敏感合成文件，**不设置或修改 owner／DACL**。若原 token 的默认 Owner 导致 `WRONG_FILE_OWNER`，必须收到 OWNER2，并核对 User／owner 摘要不同、Native PID 与实际传输子进程匹配、同句柄身份与自己合成文件一致，才能记为拒绝证据链已验证。若当前 token 本来就创建 User-owner 文件，允许检查通过，但准确记 `ownerRefusalEvidence=not-exercised`，不能假称拒绝分支通过。这一步用原环境验证 PR86 缺失的实际拒绝链，不触碰生产对象，也不为制造拒绝调整权限。

如需原 Principal 的只读 envelope，继续使用[同源固定入口](windows-native-owner-evidence-20261031.md)。Limited 是配置字段，实际 token 必须按报告核对；high 的报告不算 medium。普通 Main 与 envelope 的通过都不覆盖正式初始化序列。

正式切换仅由 Windows Root 控制，先保存当前受管程序前像并逐项新鲜 SHA 守卫，保留 Task Hub／`.local`／身份／设备／配置和文档例外及 bundle 扩展。停止必须确认准确 PID+开始时间+映像的旧代次退出及端口释放，再复制统一受管产物。start 超时先查原动作，不重复派发。若失败，只保存固定 action／角色／origin／同句柄 frame 与原始回执，按准确公共前像恢复原程序，不改私有对象权限来通过；恢复后独立确认 health 和 workspace 就绪。

正式成功后再验证最新页及原 nextCursor 省略 window 的真实分页、非法／跨目标／过期游标的准确错误。旧 `-32600` 请求内容不明时仍不追认同一根因。

## 验证范围

本项本地回归覆盖通知锁／快照必须通过私有创建回调、原状态重开保持、创建拒绝不普通兜底／不发布、部分 OWNER2 经过真实子进程通道仍拒绝、bootstrap frame 经过真实 Worker 共享槽且不派发 serve、Host 脱敏投影。C# 编译／纯托管反射及合成 OS 证据不替代真实 Windows ACL、实际 helper 或正式启动。

Adapter、Host、Notification Runtime 及三个 Native cache key 均按新完整包重新绑定；其余未改实现按实际文件输入核对复用。旧通过记录保留原范围，不全量重做不受影响场景，也不把新 Native 或通知构造器记为旧 Windows 证据已通过。本专用聊天保留，待来源验收收尾。
