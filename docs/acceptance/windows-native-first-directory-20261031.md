# Windows 首次私有目录创建与扩展路径

本轮规范基线是 PR78 合并 main `ace1bb2d691c052f0e53e00c914b008cd5cf7c15`。保留已冻结 R9/R10 与全部当前 main 能力，不导入相邻活动 WIP，不改正式配置、权限、原桌面、网络或 producer。

## 输入证据与限制

来源 v6 普通 Main Native 只读 ACL／五组件闭包预检退出0、8471ms，仍按其原输入保留。后续 reader 准备在第一次 `makePrivateDirectorySync` 创建完整缺失后缀时得到 `UNSAFE_WINDOWS_PRIVATE_STATE:ArgumentException`，RUNROOT 前后不存在；尚未写私有输入、构造 RuntimeSession 或启动 reader。这不是 reader 恢复失败的证据，更不能记为更新恢复通过。该轮没有完整准备 Node PID／启动时间和配置前哈希，不能补称完整进程观测或配置前后哈希相同。

来源独立纯 BCL 四进程对照支持初始化顺序假设：最早设置两个路径开关时扩展路径正常处理；先触发部分 BCL 操作再设置时出现 ArgumentException。该对照没有运行产品 helper，不能确定原产品准确失败 API。

[Microsoft 参考源码](https://github.com/microsoft/referencesource/blob/main/mscorlib/system/AppContext/AppContextSwitches.cs)显示路径开关会缓存；[路径规范化](https://github.com/microsoft/referencesource/blob/main/mscorlib/system/io/path.cs)选择不同逻辑。[SetSwitch](https://learn.microsoft.com/en-us/dotnet/api/system.appcontext.setswitch)更改 AppContext 值不等于清除已缓存的 BCL 行为。因此只把静态构造提前，不能覆盖先运行的 PowerShell 宿主路径操作。

## 修复边界

Native Main 在 serve／cwd 操作前显式初始化现有兼容开关。必要路径规范化采用有界 Unicode `GetFullPathNameW`，目录属性查询采用 `GetFileAttributesW`，已规范化 DOS／UNC 父目录按根边界分解；不再把扩展路径交给这段 BCL 文件属性／路径规范化。Native 查询只把 FILE_NOT_FOUND／PATH_NOT_FOUND 当作缺失；拒绝访问及其他错误仍拒绝，reparse 和非目录仍拒绝。缺失对象仍通过原 Native descriptor 在创建时设置 User owner／私有 DACL，既有对象不接管、不改 owner，原身份、租约和退出屏障保持。

首次异常保留固定阶段：normalize-path、directory-attributes、directory-parent、directory-descriptor、create-directory、directory-protect。失败 wire 只增加可选固定标签，Node 继续保留原 nativeError 类型，并单列 nativeStep；未知标签按坏协议退役，不输出路径／原消息，不续期预算、不改变重试或成功契约。旧无标签拒绝仍兼容。

最近回归覆盖第一次真实 serve 创建全缺失后缀，以及独立 PS5 先读取 cwd／调用 GetFullPath、再加载同源产品类型创建目录；后者避免“先成功 CLI 或预建目录”掩盖问题。原 owner、DACL、排他创建、相对 cwd、长路径和拒绝断言继续保留。六个标签的真实 Worker/Main 传递及未知标签退役为共同辅助检查。macOS 的测试、语义编译及 Windows skip 不计 Windows 创建通过。

## Windows 独立验收入口

用完整新包与原 Node 的正常 Main 入口，先核对官方 Release/tag/三树、ZIP／实际五组件闭包和全部 Native 资源。此次 C# 与 Native JS 输入发生变化，旧 v6 通过记录不能替代新包 Native 预检：

```powershell
& <node.exe> <program>/scripts/windows-private-state-preflight.mjs --file <formal-state>/windows-desktop.json --bundle <new-bundle.json>
& <node.exe> <program>/scripts/windows-private-state-acceptance.mjs
& <node.exe> <program>/scripts/windows-desktop-update-readonly-preflight.mjs --file <formal-state>/windows-desktop.json --missing-cli <absent-old-codex.exe> --bundle <new-bundle.json>
```

第二项必须从不存在的 Session 根创建，确认旧 Admin-owner 叶文件实际产品 resolve/download、原 owner／ACL／字节／引用保持；不得先 Node mkdir、预建私有 state、改权限、伪造 argv 或修改预算。第三项只管理本任务自有 aux reader，保留原 IPC／主 reader／桌面。失败即停并保存固定阶段，不重放未知业务操作。实际安装更新窗口仍单独记录。

正式切换前由来源再次核对真实已部署冻结基线与并行任务归属，保留 Native config、observer、retention、Host／证书／设备授权／项目／草稿／回执／Task Hub。差异切换托管程序后独立核对三路身份／固定证书／workspace／分页。回滚仅恢复本次托管程序到切换前实际冻结基线，不回写旧配置、不接管或删除旧 cache／history／附件。旧 tag、包与回执不可覆盖。
