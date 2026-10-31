# Windows 冷编译输出预约与 Node 创建租约

本轮只处理 Native 冷编译与验收基础设施，不扩大手机、建议 producer 或网络范围。PR91 的 PS5 系统模块绑定已经在主线，原 Owner、DACL、默认 Owner 和拒绝断言保留。

## 输入事实与限制

Windows 独立报告中，PR90 通知状态预检在首次私有目录动作之前失败，结果为 `WINDOWS_PRIVATE_STATE_UNAVAILABLE / startup / execute / compile / status=1`。没有该次产品 csc 的退出码、原始编译输出或同句柄追踪，不能据此直接断言历史原因。

独立合成对照使用同一个 Framework64 `csc.exe`（14.8.10001.0）、Hello 源码、系统引用和 flags，显式当前 User 安全描述符、`CREATE_NEW`、`share=3`、`OPEN_REPARSE_POINT` 不变。只改变输出句柄访问位和独立输出名：`0x20080` 得到 csc exit0、0→3584B；`0x20081` 得到 exit1、可辨认的 `CS0016`、0→0B。各一组且未重跑。该证据支持访问位影响此编译器输出，不代替产品回归。

对照没有模拟生产 job；短命编译器只有 spawn PID，未独立取得 OS 创建时间和解析路径；对象身份来自路径 lstat，而非 retained handle；原输出错误解码，除了 `CS0016` 外不据乱码推断，原始字节未保留。夹具准备的路径和引用修正在两组启动前，全部限制随原报告保留。原17:48分页事件与本故障的同因性仍未知。

## 实现边界

- 普通 `create-file`／Node 交接继续使用 `READ_CONTROL | READ_ATTRIBUTES | FILE_READ_DATA`，无 DELETE 共享；原 rename/unlink 拒绝、Node 先开 FD、release ACK 后截断和同身份断言不变。
- 编译输出 A 使用显式的 `CompilerOutput` 内部用途，仅保留 `READ_CONTROL | READ_ATTRIBUTES`。它不借用普通 Node lease 的数据访问位，也不宣称 metadata 句柄可以阻止路径重命名。
- 每级编译命名空间目录使用 `FILE_LIST_DIRECTORY` 和无 DELETE 共享的句柄，以实际共享检查保持目录租约。此位与文件的 `FILE_READ_DATA` 数值相同；不修改目录 owner 或 DACL，也不接管不可信对象。
- A 的原创建句柄持续保留，读句柄必须与其身份一致；A 的读句柄跨 metadata 释放和删除句柄重开继续存活。B 仍通过自身 WRITE/DELETE 创建句柄发布且禁止覆盖。身份不符时不复制 pathname 替代物、不按路径删除。
- 非零 csc 退出在原拒绝异常中保留可序列化的 UInt32 诊断，并标注既有固定 `compiler-exit-code` 阶段；诊断附加失败不能替换原失败。Host 的 `nativeStatus` 仍是原 helper 退出状态，未改成编译器退出码，未扩大公共诊断字段。
- SystemRoot、原私有状态、身份、权限、预算、csc/job flags 和退出确认边界不变。普通对象 owner／DACL 拒绝规则不放宽。macOS 运行分支、iOS/Android UI 与协议不变，A6 保持原状态。

## 最近回归

原 Windows CLI/serve 用例内新增同源 C#5 夹具，直接执行真实 `PrepareNative` 的三个冷目标及暖结果身份比较，不调用 Trace 捕捉、producer、Host 或模型。每个子进程只把自己的 `LOCALAPPDATA` 指向从未存在的独占夹具目录，SystemRoot、模块路径和父进程环境不变；每组沿用原30秒总界限，阶段不续期，避免共享暖缓存隐藏冷编译。

冷缓存使用系统临时目录下的短独占路径，避免长解包目录给旧 Framework csc 叠加输出路径限制；各子目录仍不存在，由产品首次创建。三组成功才清理该目录，任何失败保留路径供负责人确认子进程退出后复核和回收，不把失去退出确认的现场递归删除。

私有 helper 组另做合成身份边界：实际目录租约持有时 rename 因共享拒绝，释放后成功；完成的合成 A 已取得原读句柄后，将路径换为同 User 私有 B。新的 pathname 读取被身份检查拒绝，A 仅经 retained handle 清理，B 的字节、owner/DACL 和身份保持。这不是对 csc 打开输出前的恶意路径替换实验，不扩大其证明范围。

失败报告只输出固定阶段、类型及可用的 UInt32 compiler exit code，不输出源文件、命令或原始编译正文。普通 Node lease 原真实断言继续执行。修正后的 PS5 oracle 和新增真实冷编译均需要 Windows 独立执行；Mac/CI 的 OS skip 或纯托管编译不算通过。

私有组还向自有源目录写入固定的合成 `#error`，实际验证非零 UInt32、原拒绝阶段、未发布 executable 及已确认退出后的临时输出清理；它不模拟原始 CS0016，仍使用同一组剩余预算，不续期。该错误路径与真实冷编译一样待 Windows 执行。

## 验收状态

源码修复与新夹具已实现；本次辅助检查、CI、发布及 Windows 实测按冻结指纹分别记录。原独立 A/B 对照是修复前机制证据，原 CLI/serve 局部通过只保留其旧输入范围。完整 Windows CLI/serve、通知预检、正式初始化、真实 nextCursor 分页及统一候选的外部读取仍待验收。保留正式 R10 与原桌面，由 Windows 负责人控制实际生命周期和公共文件差异回滚。
