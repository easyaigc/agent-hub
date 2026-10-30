# Native 冷编译、历史分页与冻结 R5 集成

本轮规范输入为 main `4705a63292d468c556cf51b448380702f23ec49c` 与已发布冻结 R5 `735be79eacfa795ff5c1e1b3bc714981b90987cc`，后者官方 ZIP SHA-256 `394e97a03702d0f3d581ab2ae11dbdeaca662a9e3b0d166ed2b92eb407e617c2`。独立重建核对了完整相关源码／运行／Native／脚本差异，五组件 244 路径、实际 ESM 闭包 584 路径、两份 Windows 资源共 26 路径、脚本 388 路径全部逐字节匹配；唯一包文件差异为生成的 bundle createdAt。R5 旧公开 tag 的树不等于其记录的规范提交树，不能借标签宣称同树。新包由完整规范 main 合并后的同源快照交付。

R2→R5 的完整树差异共七文件。现合入 Trace 子进程 PSModulePath 规范化、准确原 Trace 后代的既有 Native owner 边界和正预算文件 selector，以及冻结安装器和最近测试，保留 main 私有创建租约及其他业务。未导入相邻活动 WIP、未接管 producer 安装，也未变更 Task Hub 内容。R5 的逐机旧结果仍绑定旧实现；本次变化按输入核对受影响检查。

## Native 87 的证据与修正

来源上一冻结包的真实失败只记录 compile / Win32 87，缺少具体 API 标签；后续旧 history、reader 和部署均未执行。该记录不足以证明准确 API，不能把静态解释或 macOS 编译当作 Windows 复现。

代码存在一个与该错误相符的发布调用：`SetFileInformationByHandle(FileRenameInfo)` 使用非空 RootDirectory 与相对文件名。[FILE_RENAME_INFO 文档](https://learn.microsoft.com/en-us/windows/win32/api/winbase/ns-winbase-file_rename_info)允许相对形式，但有[公开的 Win32 wrapper 逐步实测](https://learn.microsoft.com/ja-jp/answers/questions/6015074/setfileinformationbyhandle-filerenameinfo-rootdire)显示相对名被转换为绝对名后仍保留 RootDirectory，最终返回 87。因此产品改用同一 API 的 NULL RootDirectory／固定绝对目标形式，同时保留整条非 reparse、无 DELETE 共享的目录链租约、B 原创建句柄、禁止替换与发布后身份 receipt。不是改用路径删除／复制发布，不增加尝试或放宽 owner、作业和退出屏障。源配置和旧 cache 不接管，Native 原总预算保持。

Native 新增固定的 API 阶段枚举，首个异常不会再被 finally 的清理异常覆盖；清理阶段和数字 Win32 错误单列。PowerShell→Worker→Main 只传固定标签和数字，不带异常正文、路径、命令行、原始 RPC 或源码。若 Windows 新预检仍失败，以新标签定位实际阶段，不能据本说明提前放行。

## 首次 Session 与旧布局

Session 根从不存在的路径直接由私有创建边界建立，Windows 新上传和 inline 图像统一使用 `attachments-v2` 的排他私有文件；旧附件 ID、绝对登记引用、owner、DACL 与字节不改。既有 runtime-history-v1 保留，新可重建缓存仍在 v2；全局 state、原 Host 身份、设备授权、私有配置、草稿和回执路径保持。未知操作不新增重放路径。macOS 保持原附件命名空间。

原生私有状态验收新增未预建 Session 根及两种附件写入，继续原旧 history 与 owner／DACL 原件保留断言。该脚本只有合成 Session 本地 I/O，不是原桌面模型或真实安装更新验收。

## 历史分页契约

保留前缀 `desktop-page-v1:` 必须在 Host 根据 Host/thread/anchor 解析，不依赖下一页是否重复 window 参数。格式错误 400，跨目标或锚点／冷缓存失效 409，均不送 native RPC；有缓存时保持原 100 条／2 MiB 窗口。底层 reader 再次拒绝保留前缀。其他 native opaque cursor 沿用原协议。新 HTTP 夹具覆盖带／不带 window、错目标、未知／清空缓存、锚点失效，以及合法 native cursor 的独立拒绝；数字 -32600 仅与实际白名单方法和分类关联，不归因所有既有 502。

## Windows 独立入口与部署门禁

先核对新发布的 ZIP、公开同步树、manifest、实际五组件 ESM 闭包与 Native／脚本指纹。以下从统一包 `assets/codex-manage` 根运行，使用现有正式 Node；路径为占位符，不复制私有配置或凭据。

```powershell
& <original-node.exe> scripts/windows-private-state-preflight.mjs --file <existing-windows-desktop.json> --bundle <same-skill-bundle.json>
& <original-node.exe> scripts/windows-private-state-acceptance.mjs
& <original-node.exe> scripts/windows-desktop-update-readonly-preflight.mjs --file <existing-windows-desktop.json> --missing-cli <original-absent-codex.exe> --bundle <same-skill-bundle.json>
```

按失败即停执行。不能为通过而删除 CLI、重写 argv/NODE_OPTIONS、改变权限／Native 预算，或预建私有 Session 根。最后的入口解析实际 Host Adapter，普通 Main 创建独立 state、建立当前原桌面只读连接，以已经不存在的旧 CLI 作为启动 hint；只终止本入口创建的辅助 app-server reader，等待 close，再仅观察状态让产品后台恢复，不用新 workspace 请求驱动。核对主 reader、原 IPC、桌面 owner 连续，最终关闭自有 reader 并移除自有 state；退出未确认时保留现场并失败。它不监听 HTTP、不修改正式 Host／配置，不发送 replies、creations、审批、停止或模型任务，也不伪造实际更新窗口。

三项通过后仍须核对来源当时真实正式部署基线并差异部署，保留已部署 R5 和并行正式后继；再验三路鉴权、Host／证书／设备身份和分页。原连接已恢复、macOS 辅助通过或旧 Native 包通过都不替代新包的 Windows 结果。没有真实 Codex 安装更新窗口时，该场景单列未验。

本候选仅更新程序和同源指引。回滚范围是本轮有差异的程序组件／脚本，使用来源核实的当前正式包；不回滚其他任务的新正式功能，不覆盖私有 state、原 Host ID、证书、授权、草稿或回执。新 cache／attachments-v2 可保留用于复核，不为回滚删除旧数据或改 owner。
