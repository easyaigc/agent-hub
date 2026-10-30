# Windows 标准私有 DACL 的幂等保护

本轮基于 Gitea main `1915eea22147b66811f040b6c1a983717ed4a162`（PR80），保持 PR79 `host-connect-native-first-directory-20261031` 冻结包 ZIP `439463b0c1c69e885a4d64ca3712c0227883283a774caaef991a5dc5e9fd2e51` 的五组件 ESM 和依赖作为运行输入。规范主线的 Native／验收与文档差异统一入包，不导入建议任务的活动 R11 或其他 WIP，不因文档变化重编 Host／reader。新包是 Native 修复，不是 script-only；旧 Release／资产／回执不覆盖。

## 已知调用链与证据范围

`RuntimeSession` 构造时 `makePrivateDirectorySync(options.directory)` → Native `create-directory` → `EnsureDirectory(existing target)` → `Execute(protect)`。原实现即使同 User owner、DACL 已 protected 且为标准三 SID ACL，仍无条件 `SetSecurityInfo(DACL | PROTECTED_DACL)`，目录规则含 OI／CI。微软 [SetSecurityInfo](https://learn.microsoft.com/en-us/windows/win32/api/aclapi/nf-aclapi-setsecurityinfo) 与[自动传播](https://learn.microsoft.com/en-us/windows/win32/secauthz/automatic-propagation-of-inheritable-aces) 文档描述向现存子对象传播可继承 ACE 的行为及共享访问／句柄等例外；自身 protected 是阻止从父级继承，不是关闭向子级传播。所查文档未承诺重复设置相同 DACL 无副作用。[ChangeTime](https://learn.microsoft.com/en-us/windows/win32/api/winbase/ns-winbase-file_basic_info) 与元数据变化相关，但未据此证明本次具体安全写入与 ctime 的因果。

来源 Windows 的 PR79＋PR80 正常 Main 完整 legacy 验收退出1／38164ms。首次缺失 Session／v2、legacy 附件函数的真实 runtime.json、Admin-owner 旧叶文件、16MiB 产品 resolve/download 与404、严格私有文件拒绝、新 upload／inline 断言均已通过；函数返回后旧 history 内容等值通过，原 ctime 断言失败。旧 v1 目录及文件 ctime 同变、文件 mtime 和内容未见该断言指示改变，protected 附件子根的旧叶 ctime 保持，与传播假设相容。没有直接捕获此次安全系统调用，运行前也没有该旧 history 对象的独立 SHA／size／mtime／ACL 完整快照，不能补造前后全项证明或认定内核因果已证实。

随后 history 安全复验、broad ACL 拒绝／恢复、24次原子保存、长路径和成功 report 尚未执行，整体仍未通过。先前隔离只读 reader 的并发目录读取、自己的 aux 退出后后台恢复，以及 launcher／注册 Worker 的 Trace helper resolve/bootstrap/private ACL/PE 已有来源独立证据；前者为隔离目录0项目／7线程，不扩大为正式3项目／435线程，也不表示 Trace 捕捉、原实例检查、producer 或手机建议已通过。来源正式 R10 未由本任务部署或重启。

## 最小修复与保留边界

在原同一对象句柄上先保留对象类型／reparse 和严格当前 User owner 检查。构造与原来完全相同的标准 DACL，仅当 `DiscretionaryAclPresent`、`DiscretionaryAclProtected` 都存在，且序列化 DACL 长度及每字节与目标完全相同时跳过 setter。精确比较同时覆盖主体、ACE 类型、权限、顺序和继承／传播标志；不是仅 trusted subjects 或权限看似等效就接受。无论是否写入，原重读 owner／protected 与访问主体验证继续执行。未 protected、NULL／不同／broad DACL 仍走原设置和验证；错误 owner、对象类型、重解析和 Native 退出／身份／编译租约均不放宽，不接管 Admin 对象，不改 timeout 或预算。

官方 `windows-private-state-acceptance.mjs` 保留原旧 history ctime 基线及失败断言，不重设、不删除。在完整 legacy 函数原断言后增加旧目录 ctime 基线比较，再重复 `protect`／`create-directory` 与 `protect-file`，要求根目录、旧 v1 目录／文件和私有主状态文件 ctime 及原 ACL／owner／内容保持。原 broad ACL 拒绝／恢复、24次原子保存、长路径、首次目录、附件和未知回执断言保持；新 report 字段仅在全部原门禁通过后产生。普通 Main 入口继续不需要 TypeScript 或开发依赖。

## 运行指纹、统一制品及复验

统一完整包从上述原 PR79 冻结运行输入派生，只更新已提交的规范源码／测试／文档、两份 Native C# 和 bundle 创建元数据，原五组件 JS／Node 依赖、Task Hub、SKILL 与其他 Native 资源按实际字节复核。新源码、PR／merge／公开三树、ZIP、变动文件、Native14／预检8及所有 helper cache 源摘要均由新的 runtime manifest 列出。`private-state.cs` 被 private-state、trace-state、host-launcher 共用，不能只凭五组件 JS 匹配把旧 helper 证明算成新 Native 输入通过。

未变 Node 传输／reader／主 IPC／共享发现／退出屏障的历史证据按原实现指纹和具体范围保留；旧 Native 预检、首次目录、launcher／Trace helper 与 reader 私有准备的通过也保留为旧输入历史，不升级为新包完整通过。新 C# 编译语义与 macOS／CI 辅助通过不代替 Windows 执行。

来源先核对新公开 tag／源码树／ZIP／五组件和新14／8资源，再用原可信 Node 的正常 Main 执行新完整包的 `scripts/windows-private-state-preflight.mjs --file <existing-private-windows-desktop.json> --bundle <same-new-bundle.json>`；新 helper 普通冷编译及只读 ACL 通过后，在独占 cwd 执行 `scripts/windows-private-state-acceptance.mjs` 的完整门禁，明确到达原 ctime、重复保护、broad 拒绝／恢复、24保存和长路径／成功 report。不要预建状态、改 owner／ACL、伪造 argv、改 NodeOptions、延长预算或安装 dev dependencies 绕过。共享 C# 变化所影响的 launcher／注册 Worker Trace helper resolve/bootstrap/ACL/PE 按新摘要复验，不启动 Trace 捕捉或 producer。Reader 核心 JS 证据按未变范围复用，新的 Native 私有准备／初始化另核对，不将未测组合写成新包实测。

正式切换仍由来源 Windows Root 按即时实际已安装冻结基线及私有配置差异处理；保留身份／证书／设备授权／项目／草稿／操作收据、Task Hub、Native config／observer／retention 和原 Codex。普通 R11 隔离长测不是全局升级锁，但实际共享输入／前后证据窗口需界定，不能把外部授权切换误记为套件副作用。本任务不联系或干预它。回滚仅撤回本轮 managed 程序差异，不回写旧配置、不删缓存／历史／附件或接管 owner。
