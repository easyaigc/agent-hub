# 源码发现：撤销与迟到刷新缺少共用保存屏障

状态：**源码发现，具名 Android 回归已编译，未运行；生产不改。** 影响首先是已删除的本地授权记录可被迟到响应重新保存，不能据此宣称服务端已撤销授权仍可执行。

基线 `10b2060c943b5891225d0e32800769f98a81b90d` 的 `apps/android/src/main/kotlin/com/agenthub/android/EasyWorkDeviceAuthorization.kt`：

- 第 131 行 `validAccess` 仅持有该授权实例的 `refreshes` mutex，读取 saved 快照后发起刷新。
- 第 147 行收到有效密文 grant 后，直接修改旧 saved 并 `write(ref,saved)`。
- 第 150–159 行 `revoke` 没有使用该 mutex 或持久 generation/tombstone；独立请求确认后 `remove(ref)`。

可控交错为：刷新 POST 已被服务接受但响应挂起 → 同一客户端调用 revoke 并收到确认 → 本地 ref 已移除 → 释放此前有效刷新响应 → 原刷新路径仍可按旧 saved 写回 ref。`lateRefreshAfterConfirmedRevokeMustNotRestoreRemovedGrant` 明确保留“ref 继续为空”的产品断言，无 `@Ignore`。合成服务允许先前仍有效的 access token 发出撤销；即使已挂起刷新曾轮换 token，也始终拒绝撤销后资源访问。该工况检验本地保存顺序，不模拟权限绕过。

主统筹执行时须单列此方法及源/包/runner/fixture 指纹，保留实际失败或通过，随后评审是否用按 ref 的共享互斥和持久 generation/tombstone 阻止过期保存。仅给单一实例增加 mutex 不能自动证明跨实例、进程恢复或未知撤销已解决。产品修复及回归由主线统一处理。

其他边界：Android `claims`/`refreshes` 都是实例内 mutex；目前只准备了同实例并发和不同 grant 的隔离用例，同一 grant 多实例在“读取原状态到写入原 operation”之间的竞争没有安全屏障注入点，尚未复现。取消刷新用例等待原 HTTP socket 实际关闭后才释放旧响应，仅验证该狭窄取消路径，不能代替上述仍活跃的迟到刷新回归。

iOS 对应 `apps/ios/Sources/EasyWorkDeviceAuthorization.swift` 第 184–199 行用 actor 内按 ref 的 `refreshing` 集合协调，网络 await 前后保留 saved 快照；当前文件未定义 `revoke`，且身份和 CredentialVault 没有独立测试存储注入入口。本线仅阅读代码，未操作 Keychain、iOS App 或设备，不外推其竞争行为。
