# 冻结 R11 与正式启动诊断的统一候选

## 输入及实现范围

本轮将已发布 R11 的冻结源码 `b394c186a423e6d47f9e64e26096491c87c8f3e3` 与规范主线 `b9ba0c69c0daec4219ff3e742801f07de9cb125d` 合并。共同基线是 `5d4d6676f3a757745f8826cadda72051391cd7ff`，不读取或复制其他任务的活动 WIP。

R11 完整 ZIP 的 SHA-256 为 `fdd67f5a220fcbaf6d5921ec438808f723654b0f3ed2f6d17854da273011bc44`，大小 58,975,657 bytes，其 Adapter 指纹为 `bb036b1d924b3ce1a49ccfd825b0d012b55cbf9af01167cbb8a7d5147c0138d1`。该公开 tag 的 anchor `dbe4ebcda36f22c0df706f351bf558728ae2ed43` 是基线树，不能当作 R11 ZIP 的源码提交或声称 R11 已合并到 main。本次源码交付另外核对实际 Gitea PR、合并及官方公开同步。

合并保留 R11 的固定 producer Worker、scope 校验、原生代次退出屏障、最新正文替代与 Trace 回收、原子发布前的租约检查及生产 Task Hub 构建配置。共享的 `desktop-private-state-native.ts` 同时保留后续固定 Native 拒绝原因的严格解析和传递。`private-state.cs` 保留主线的精确 DACL 幂等保护；安全接受/拒绝、User owner、DACL、保留句柄、预算及未知提交不重放规则不变。

正式 publisher 文件被拒但日志只有 `UnauthorizedAccessException`，不能据此判断 owner 错误、DACL 错误或本次候选已经修复。当前 BB 的 C# 响应没有传递五种固定原因；本候选补齐该诊断链及具名只读启动预检，不接受任意异常正文、路径、SID、环境值或凭据内容。既有文件归属的只读快照不能替代失败时保留句柄上的判断。

## 双端及证据边界

F03/F31/F32 的更新恢复、私有状态和失败诊断与 F69 的已发布建议修复统一保存；不新增移动 UI、协议或权限。iOS 的既有验收源码按冻结 R11 保留，Android 状态不升级，暂停的 A6 保持暂停。本机构建、合成测试和公开来源核对是辅助证据，不能代替 Windows 的当前正式树、scheduled 初始化或手机建议验收。

来源报告的 PR84 单次拒绝预检及 reader 合成检查保留原输入范围，不将 skip 算通过。此前两种摘要的差异已按其限定函数解释：prepared 的 29 输入与 Windows desktop 的 39 输入独立核对，不相互替代或改写旧证明。

## 部署与恢复边界

1. Windows 当前树已变化，原 R10/PR83 的 managed guards 全部退为历史依据，不能用于覆盖 BB。先重新冻结当前实际文件、bundle 扩展、五组件解析、Native、任务 action、进程精确身份和可用回滚资料。
2. 新候选先在隔离目录以实际 ESM 闭包及 Native 输入核对，执行具名 `--host-config` 只读启动预检和所需合成拒绝检查。不得读取 publisher 正文、改 owner/DACL、预建正式状态、延长预算或换身份来消除拒绝。
3. 只有 Windows Root 审查新鲜 guards、确认唯一部署负责人后，才可按现有标准生命周期执行一次受控正式诊断。注册成功、任务 Running、HTTP 成功和完整正式初始化各自记录。另一来源 Main 的部署或恢复尚未确认时，不复投未知操作或并行重启。
4. Host-only 文件差异以精确路径和 before/after SHA 限定，保留当前私有状态、证书、设备、草稿、收据、附件、Task Hub 及配置。bundle 只对已知 runtimeFingerprints 叶值作必要合并，未知部署扩展保留；已丢失的旧扩展不从历史快照猜造恢复。
5. 失败时保存固定 Native 类型、拒绝原因/步骤、首错和退出屏障，再由同一 Windows 生命周期负责人按本轮准确 preimages 回滚。没有新鲜快照时不下发可执行复制清单；不把旧回滚目录当作当前 BB 的完整回滚。

本候选不授权规范仓库会话远程停止/重启 Windows，不自动联系来源 Main 或其他客户端。正式服务可用性、未知操作回执、实际恢复及新部署证明仍由 Windows Root 与原部署负责人独立核对。
