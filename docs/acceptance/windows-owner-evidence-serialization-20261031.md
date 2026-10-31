# Framework owner 证据存储兼容性

PR87 的 Windows 通知门禁只通过创建、重开、释放三个正向断言，随后对自有默认创建文件的 `check` 返回 `ArgumentException`，没有 OWNER2。对象已由原脚本清理，其 owner 未另行观测，不能把本次具体抛出位置或原正式失败对象写成已直接证实。

## 已确认的代码缺陷

Framework 的普通 `Exception.Data` 使用 `ListDictionaryInternal`；其序列化构建会在索引器赋值时拒绝不可序列化的键或值，抛 `ArgumentException`。依据为微软原始源代码：[Exception.Data](https://github.com/microsoft/referencesource/blob/main/mscorlib/system/exception.cs#L138-L148) 和 [ListDictionaryInternal](https://github.com/microsoft/referencesource/blob/main/mscorlib/system/collections/listdictionaryinternal.cs#L45-L56)。

固定 PR87 的私有 `OwnerEvidence` 未标记可序列化，却直接赋给原始 owner 异常的 Data；该赋值在各字段采集保护之外。因此，只要进入该赋值，Framework 可用新的 `ArgumentException` 覆盖原 `WRONG_FILE_OWNER`。PR86 的外层采集保护可留下原拒绝却丢掉 frame，两者与现有症状吻合。症状吻合不是本次 Windows 抛出位置的直接采样。

修复只让这个包含有界字符串的私有定型值满足 Framework 存储约束，并保护证据附加不能覆盖原拒绝。未修改 `ReadSecurity`、owner／DACL 检查、权限、句柄身份、退出屏障、预算、frame schema 或成功路径。生产协议仍只读取定型 frame，不序列化任意 Exception.Data，不从外部反序列化数据。

## 辅助回归与实际 CLR

`scripts/fixtures/private-state-owner-evidence.cs` 与实际产品 C# 同编译，验证定型 frame 存取、自己构造内存流的序列化往返、任意 Data 不导出、只读 Data 附加失败保留拒绝。它不调用 Windows API，不读取配置、SID、token 或文件。测试分别记录实际 CLR 的 Data 策略；不根据目标框架名称猜测执行行为。

Windows 的现有 Native 单测入口增加同源 Framework 运行断言；非 Windows 仍明确 skip。Mac 上 net48 引用编译与 .NET 8 托管执行分开记录：后者需要仅属于自有回归进程的序列化测试开关，未修改产品环境或安全配置，也不等于 Windows Framework 通过。旧源码的失败和新源码的通过须分别保存，不能改写旧门禁回执。

## Windows 最小下一步

先核对本次新完整包、实际 ESM、两份 Native 和三个新 cache 源摘要。五个 JS 组件及原通知门禁脚本与 PR87 保持字节一致；前五项 PR87 通过仍保留原输入范围，新 Native 不直接标记已通过。

优先只复跑先前失败的同一官方通知入口，使用新的独占目录与原 Node 正常 Main，不改 argv、环境、owner／DACL 或预算：

```powershell
& $Node "$CandidateProgram/scripts/windows-notification-state-preflight.mjs" --bundle $CandidateBundle --directory $NewExclusiveFixtureDirectory
```

正向三个字段不代替整体通过。若再次 owner 拒绝，应核对原拒绝类型、OWNER2、实际 helper PID 与同句柄身份；若默认创建对象本来归 User，准确记录 not-exercised。若仍是 `ArgumentException`，保留原始固定字段，不能仅凭本修复追认其位置或删除断言。其余未改变实现按实际输入指纹复用；新 cache 的编译／执行／未观测分别登记，不用旧 EXE 身份代替新 key。

正式 R10 生命周期继续由 Windows Root 控制；本项不触发部署、重启、权限修复或业务重放。当前活动锁、既有通知状态、设备／密钥／事件／回执、Task Hub、私有配置、文档例外和 bundle 扩展继续保留。正式初始化、真实通知数据保留及分页尚未验收，不因 Mac 辅助或正向门禁通过改变状态。
