# 更新恢复、冻结 R9/R10 与旧附件兼容

本轮继续 [Native／游标修复](codex-update-native-cursor-r5-20261030.md)，规范基线为 main `1d81daf0fdbed78ee0f4e6c021b594ff7399d895`。不覆盖旧发布或旧回执。接收端正式安装已推进到冻结 R10，新统一包须包含实际 R9/R10 能力及规范 main 的私有创建与更新恢复修复。

## 冻结输入与源码边界

| 输入                                 | 官方 ZIP SHA-256                                                   | 本轮保留                                                                                    |
| ------------------------------------ | ------------------------------------------------------------------ | ------------------------------------------------------------------------------------------- |
| R9 `native-result-recovery-r9.zip`   | `bdb1dee4fca1f8fbe80aaf7486cdb5355f1416e63aafbcdff7f2f8d19bb35aad` | 独立 Bonjour generation worker、退出后重试、HTTPS 独立性、Windows installer NullString 修正 |
| R10 `native-result-envelope-r10.zip` | `281dc46a54300fc718a73256217e69624e68ae0cd57e2ed7a4c5347f2e8e66d3` | 有界顶层 Native frame 分类，不以嵌套历史正文的 thread/sample 字符串冒充路由字段             |

完整对照 R5→R9 共 13 个程序／文档条目；R9→R10 为 7 个程序条目、2 个文档及 bundle.json，后者 Native／worker 资源未变。R9/R10 声明的旧源码提交尚不能从实际托管对象证实；查到的公开 tag 树与资产中的相关源码不相等，不能宣称旧标签同树或猜测标签变动原因。本轮以已核对官方资产的实际字节为冻结输入，经规范仓库审查合入，再由新的真实 PR、main、公开同步快照和统一构建建立交付链。

保留 main 其他业务、Task Hub、私有创建租约与未知操作边界。Direct discovery 没有用 R9 旧文件整段替换 main；Bonjour 状态临时文件采用现有 `openPrivateFile` 排他创建与 `renamePrivateFileSync` 发布，避免提权 token 默认 Administrators owner 再触发 User-only guard。新增测试走真实 TLS listener、状态回调和私有文件边界，仅 DNS transport 使用本测试进程内替身；不宣称真实组播或手机发现通过。R10 bridge 同样保留 main 私有创建路径。不引入相邻活动 WIP，不代做 producer 安装或后续建议功能。

## 验收器路径与产品读取

上一脚本把旧附件 fixture 写入任意 persistence 测试使用的 `runtime-state.json`；真实 `RuntimeSession` 读取 `runtime.json`。这是验收器路径错误，来源没有运行该错误场景。本轮抽出共用 fixture，以 `sessionStatePath` 区分实际 Session 与原 persistence 路径，保留原断言，并增加普通 Node Main 共同回归。

旧布局场景由产品边界创建 User-owner 根，再由普通 Node 创建 16 MiB 旧附件叶文件；不改 token、owner、DACL、权限或预算。实际 Windows 默认 Owner=Administrators 时核对叶文件真实 owner、继承 DACL 与允许主体，不靠预建私有叶文件掩盖旧布局。登记旧 ID 后，真实 `RuntimeSession.resolveAttachment` 与 `download` 必须返回原内容，错误 Thread／未登记 ID 仍拒绝。之后核对原字节、owner、DACL、登记引用和 persistence 文件不变；新 upload／inline 保持排他私有创建与 Windows `attachments-v2`。

附件产品路径按完整目标、登记引用和稳定文件快照读取，不把所有附件等同全局私有状态文件。场景还要求 Admin-owner 旧叶不能通过全局 `assertPrivateFile`，避免兼容变为放宽 User-only 边界。结果 `legacyAdminOwnedLeafExercised=false` 只表示共同路径执行，不能算 Admin-owner 场景通过。

## 证据分层

来源已在普通 Main 完成上一冻结 Native 预检：退出 0、8471 ms，backend 关闭，目标及 caller bootstrap 元数据不变，8 项预检资源和实际 ESM 闭包匹配。该证据仅覆盖相应 Native 私有 ACL 输入；本轮 Native 源码不改，匹配资源可复用，新整包闭包仍须独立核对。

旧 Win32 87 回执仅定位 compile，不能确认准确 API。来源独立 API 对照中相对名／目录句柄返回 87，NULL RootDirectory／DOS 绝对名成功，与产品绝对发布修正相符；它不是旧产品失败阶段的直接证明。[微软 FILE_RENAME_INFO 文档](https://learn.microsoft.com/en-us/windows/win32/api/winbase/ns-winbase-file_rename_info)允许 RootDirectory 配相对名，不能写成文档要求 NULL。旧 cache 为空也不能证明日志从未存在。保留准确 API 阶段、首错、清理错和原退出屏障，不为通过改变预算。

macOS 构建、共同回归及 fixture 是辅助证据，不能替代 Windows 旧布局、只读 reader 后台恢复、真实安装更新窗口、launcher／Trace 或手机验收。逐机结果由来源独立记录，不公开私有日志、机器路径、原附件或聊天。

## Windows 门禁与回滚

用完整同源包和原 Node，在正常 Main 入口顺序执行：

```powershell
& <node.exe> <bundle>/scripts/verify-host-runtime.mjs --entry <bundle>/apps/host-agent/dist/runtime-entry.js --bundle <bundle>/bundle.json
& <node.exe> <bundle>/scripts/windows-private-state-preflight.mjs --file <formal-private-state>/windows-desktop.json --bundle <bundle>/bundle.json
& <node.exe> <bundle>/scripts/windows-private-state-acceptance.mjs
```

先核对输入及退出码；第一项不代表正式进程已加载新代码。Native 资源完全相同时可按指纹复用已通过证据。旧布局必须确认真实 Admin-owner 叶分支、旧 history／引用保持；失败即停，不在目标修产品、改 owner 或预建 Session 状态。再按同包 `windows-desktop-update-readonly-preflight.mjs` 的具名参数，验真实旧 CLI 发现及本任务自有 aux reader 完整退出后的后台恢复；保留原 IPC、桌面和主 reader。没有实际安装更新窗口时保留限制。

正式切换前重新核对实际基线和并行任务归属，保留 R10 能力、已有 `CODEX_DESKTOP_NATIVE_SUGGESTIONS_CONFIG`、observer、retention 与全部私有配置。仅差异部署托管程序，经原标准入口处理本产品 Host；不重开 Codex、不改网络或 producer。之后独立核对三路身份／证书／workspace、分页及原回执。

回滚仅恢复本次托管程序差异到切换前实际冻结基线；不回写旧配置快照，不删私有状态、不接管旧 cache／history／附件，不轮换身份／证书／令牌。保留失败和未知操作原回执。正式 Windows 部署由来源负责人执行。

## 本轮已执行辅助检查

Node 24.19.0／pnpm 11.19.0：构建 3.360 s 退出0；发现／Bonjour 8项通过（2.457 s）；共同旧附件产品路径1项通过（0.148 s）；Native sample／Windows相关86项通过、5项原生跳过（12.250 s）；Windows验收脚本语法检查退出0。定向实现／测试输入摘要 `7f2677186f8877f4b57d862f31ef9e18fb60405c8c9f8f6439fc7d1fd7490918` 前后不变。Windows skip、macOS共同布局及合成传输均不计 Windows 实机更新通过；完整检查、CI和发布指纹另绑定实际冻结提交。
