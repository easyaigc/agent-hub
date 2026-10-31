# owner 证据 Data 读取边界

PR88 的 Windows 官方通知门禁已通过，包含真实默认文件 WRONG_FILE_OWNER、OWNER2、实际 helper 与同句柄身份；该记录绑定 PR88 原输入。随后同源 managed fixture 报 KeyNotFoundException，未给精确阶段，CLI／serve 的 Windows API 段未执行。候选未正式部署，不能把前者通过扩展为全部验收通过。

## 已核对的兼容差异与修复

固定 PR88 的 `OwnerFailureEvidence` 在判断异常类别前，直接索引 `error.Data`。Framework 的 `ReadOnlyDictionary<object,object>` 非泛型索引器对类型匹配的键调用泛型索引器；标准底层 Dictionary 缺键会抛异常。原夹具恰好使用空的该字典，但现有 Windows stdout 不能直接证明失败就在这一行。

微软一手实现：[Framework 非泛型索引器](https://github.com/microsoft/referencesource/blob/ec9fa9ae770d522a5b5f0607898044b7478574a3/mscorlib/system/collections/objectmodel/readonlydictionary.cs#L219-L230)、[泛型索引器](https://github.com/microsoft/referencesource/blob/ec9fa9ae770d522a5b5f0607898044b7478574a3/mscorlib/system/collections/objectmodel/readonlydictionary.cs#L91-L95)。.NET8 使用 TryGetValue 的实现不同，不能用其本地通过覆盖 Framework 行为。

生产读取改为先识别 owner 拒绝，在保护范围内读取 Data、检查 Contains 后再索引。getter、Contains、并发变化后的索引器等诊断失败返回无证据；并不授权、重放或消除原拒绝。私有 Serializable 证据类型和原有 OWNER2 格式保持，任意字符串 Data 不被接受。ReadSecurity、owner／DACL、句柄、创建、退出、并发和预算不变。

## 回归范围

原 `ReadOnlyDataRefusal` 与只读 Data 断言保留，未换成更宽松字典。增加明确标注的故障字典，在各 CLR 上覆盖缺键抛出、Data getter、Contains 和索引器失败；无关异常不读取 Data。该故障注入不是 Windows 实际 API 复现。仍验证定型证据往返和任意 Data 不导出。

managed 报告新增固定 `phase`，成功必须 complete；失败给出固定阶段和类型，不输出任意异常消息、正文、路径或凭据。本地旧源码／新源码使用相同新夹具分别记录，不改写 PR88 的 Windows 原始输出。C#5／net48 编译与实际 .NET8 执行继续分列；Windows Framework 与 Native 结果只能由真实执行取得。

## Windows 最小复验

核对新完整资产、实际五组件和两份 Native／三个新 cache 源摘要后，优先复跑原已进入但失败的同一 Windows CLI／serve 用例。若原临时测试依赖已回收，按既有独立验收约束准备精确锁定的依赖，不改测试、loader 或 argv：

```powershell
& $Node --test --test-name-pattern "native Windows CLI and serve run the same C# source with equivalent decisions and relative cwd" "$CandidateProgram/scripts/windows-private-state-native.test.mjs"
```

managed 阶段通过之后才会继续实际 Windows API 段；报告须分开保存。若仍失败，新 phase 用于定位，不能以本修复推断旧抛点。现有原通知脚本保持原字节，PR88 真实通过保留其输入范围；未改 reader 等按实际指纹复用，新 Native key 不直接继承旧 EXE 身份。

Trace 旧门禁的准确范围是 Main／Worker 编译和 resolve `windowsTraceNativeExecutable`、PE／private 检查；未启动 trace-state.exe、未验证 Trace 协议。日志中的 threadId 是 worker.threadId，不是 Native PID。旧原始回执保留，更正通过单独证据记录，不因本修复新增 Trace 执行通过。

正式 R10、原桌面／CLI、私有状态、通知锁／快照、证书／密钥／设备／草稿／收据、TaskHub、Native 配置及文档例外／bundle 扩展保持。Mac 不操作 Windows 生命周期；正式初始化、真实数据保留和分页仍待 Windows Root 控制验收。本聊天保留待来源核对收尾。
