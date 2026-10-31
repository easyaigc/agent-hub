# Windows 私有文件创建租约的共享检查

PR89 的真实 Windows Framework managed 阶段已通过，phase=complete，包含定型证据往返、任意 Data 排除、附加失败保留拒绝、可选读取保护及无关异常排除。该段未调用 P/Invoke。随后同一原用例进入真实 CLI／serve API，在 Native 创建租约期间 rename 没有抛错，整体失败；后续通知、ESM、launcher、Trace 和正式初始化未验收。原始通过与失败都保留，不能将 managed 通过扩展为完整门禁通过。

## 规则与代码缺口

原创建租约句柄只请求 READ_CONTROL 和 FILE_READ_ATTRIBUTES，share=READ|WRITE，保持 CREATE_NEW 与原句柄字典。微软 [MS-FSA 共享检查](https://learn.microsoft.com/en-us/openspecs/windows_protocols/ms-fsa/8c0e3f4f-0729-49f4-a14d-7f7add593819) 对既有打开要求 GrantedAccess 包含 READ_DATA、EXECUTE、WRITE_DATA、APPEND_DATA 或 DELETE；这两个 metadata 位不在集合中。[跨流 DELETE 检查](https://learn.microsoft.com/en-us/openspecs/windows_protocols/ms-fsa/82b364ce-6d7b-422f-8d88-4db32eea809a) 同样有该条件。[CreateFileW](https://learn.microsoft.com/en-us/windows/win32/api/fileapi/nf-fileapi-createfilew) 也区分属性访问与共享模式。故 metadata-only 句柄不分享 DELETE，不能据此保证租约阻止 rename/delete。

创建租约现在额外请求最小 FILE_READ_DATA，让该实际句柄参与共享检查，仍只分享 READ|WRITE。普通 Node r+ 打开与它的读写共享兼容，随后仍须核对 Native 创建 ID 与 Node FD、路径和私有边界，并收到原通道 release ACK 后才允许截断。只有相同 Windows／Node 输入实测才能确认这一兼容路径，Mac 静态分析或编译不能代替。

Native 编译／原子发布使用的 rename=true 分支仍保持原 WRITE/DELETE access；Owner、DACL、CREATE_NEW、reparse 检查、同句柄 ID、64 租约上限、EOF/退出释放、并发、预算、诊断和协议不改。不接管既有对象，不重放未知操作。

## 回归与复验

原 rename 拒绝断言保留，增加租约期间 unlink 拒绝，以及 Node 已打开并核对 FD、但未 release 时 rename 仍拒绝；原 release 后 rename 成功且 FD 身份不变断言保留。Windows 特定段仍明确跳过其他平台，不把 fixture 或 C# 编译算作实机通过。

Root 对新冻结完整包核对实际五组件、双份 Native、两个摘要口径与三个新 cache 源摘要后，优先运行原命令：

```powershell
& $Node --test --test-name-pattern "native Windows CLI and serve run the same C# source with equivalent decisions and relative cwd" "$CandidateProgram/scripts/windows-private-state-native.test.mjs"
```

先保留 managed phase／退出，再单列真实 CLI／serve 的租约前后决策与 FD 身份；失败即保留原始结果，不改断言、预建状态、Owner/DACL、身份、argv、NodeOptions 或预算。锁定临时测试依赖沿用已核验方式，结束后回收。

PR89 原 managed 成功属于旧精确输入；未改 reader 和 JS 的证据按实际指纹复用。新 Native key 不继承旧 EXE 身份或 Windows 通过。Trace 历史仅编译／resolve／PE／private 检查，未运行 Native EXE／协议。正式启动、真实通知状态、nextCursor 分页和外部读取仍待 Root 验收，原 17:48 RPC -32600 的具体原因未知。

本机只维护规范源码及冻结公开交付；Windows Root 独立控制现行 R10 生命周期与逐文件公共回滚。保留全部私有状态、原桌面／CLI、通知锁／快照、身份／证书／授权、项目／草稿／附件／收据、TaskHub、Native 配置、既有文档例外与 bundle 未知扩展。本聊天保留待来源核对。
