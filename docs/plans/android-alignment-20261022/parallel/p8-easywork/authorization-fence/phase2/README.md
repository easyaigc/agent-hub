# 授权保存栅栏：生产接入与原生准备

本阶段接入阶段一协调器，修复统筹已在准确真机基线复现的“撤销确认后迟到刷新重新保存 grant”。本线的 17 项宿主检查、4 项 Node 纯 issuer 检查和原生编译通过；**12 个新原生方法尚未执行**，网络侧车未启动，未调用设备、真实服务、Host、桌面或模型。

阶段一提交为 `05be2b2a7531d704852488e1989c55f0c95b330b`。旧 16/12/19 方法和此前档案不变。父线 API33 基线 18 核心通过、单列原断言失败的原始证据继续独立保存，不由本次辅助检查覆盖。

生产范围为 `EasyWorkDeviceAuthorization` 与 `EasyWorkAuthorizationCoordinator`。使用现有 canonical storage root/ref 共享 mutex 和 `operationTransaction`，不改 NativeSessionStorage API；pending 撤销和 confirmed 撤销都保留持久栅栏。原 ID/body/token 快照及指纹在第一轮 HTTP 前保存，恢复使用同一具名授权操作和新 DPoP jti。业务输入不重放，原 grant 不重新注册，未知操作不清除或换 ID。

刷新等待不会阻塞撤销的本地栅栏。刷新准备/保存、旧批准 grant 保存和 proof 返回前分别复核最新状态。确认撤销先保存 receipt，再删除 grant；失败删除可只完成本地清理。grant 文件丢失但 pending fence 仍在时，显式撤销仍能从原快照恢复。原始保存 JSON、未知附加字段和旧 `revokeOperationId` 保留；正常刷新以最新记录为基准深合并已验证 grant，原 pending 的未知字段及 token/body 指纹先入独立审计。

`NativeEasyWorkAuthorizationFenceTest` 直接调用生产 HTTP、DPoP、AndroidKeystore 与 NativeSessionStorage。新目录为 `p8-auth-fence-<case UUID>-<a|b>`，AES alias 为 `p8-auth-fence-storage-<case UUID>-<a|b>`；初始化要求目录不存在，只预置公开 RFC7748 身份。DPoP alias 仅由 `p8-fence-<case UUID>-<a|b>` 派生，不读取或清理默认身份、原钥匙或其他空间。每个新客户端都重新打开同一测试存储。

| 分组                         | 方法数 | 原生准备覆盖                                                                                   |
| ---------------------------- | -----: | ---------------------------------------------------------------------------------------------- |
| `P8-fence-late-save`         |      3 | 同实例/跨实例撤销确认后的迟到拒绝；未知撤销和迟到刷新同时发生，原 DELETE 可恢复                |
| `P8-fence-persistent-revoke` |      3 | 新鲜 token 也受未知撤销栅栏约束；丢失 grant 文件从原 fence 恢复；旧 revokeOperationId 原样接入 |
| `P8-fence-isolation`         |      3 | 同 grant 跨实例只刷新一次；同根不同 grant 互不阻塞；同 ref 字符串跨根不混用                    |
| `P8-fence-preserve-refuse`   |      3 | 未知 JSON/审计保留；旧批准响应不能重新安装；错误 ref/null revoke/未知状态拒绝且不丢记录        |

准确方法、包及全部摘要见 [checkpoint.json](checkpoint.json)。每方法 JUnit 12 秒，场景 8 秒，收尾 1.5 秒，控制连接/读取各 1 秒；12 方法上限之和 144 秒。统筹必须再设 **240 秒外层期限**，按准确设备/包/时间租约串行运行。新方法捕获并断言明确拒绝，再检查 grant 缺失，失败不打印完整 grant/token。

冻结的 `lateRefreshAfterConfirmedRevokeMustNotRestoreRemovedGrant` 要求等待成功后再检查 null；正确拒绝会使它在 `await` 处抛异常。该原方法保持原样作基线，不可记成修复后通过。修复验证使用新类的 12 方法，并将旧核心 18 方法另列复验；旧回归的实际异常须原样保留。

## 统筹运行

先接入两个阶段的精确 delta，按 [merge-notes.md](merge-notes.md) 逐 hunk 合并原任务 WIP。通过统一构建槽运行：

```sh
node scripts/mobile/gradle.mjs :android:testDebugUnitTest --tests com.agenthub.android.AuthorizationCoordinatorTest --tests com.agenthub.android.AuthorizationFenceShapeTest --init-script docs/plans/android-alignment-20261022/parallel/p8-easywork/authorization-fence/phase2/validation.init.gradle :android:assembleDebug :android:assembleDebugAndroidTest -PagentHubAcceptance=true --console=plain
```

App 为 `com.easyai.agenthub.android.acceptance`，runner 包为 `com.easyai.agenthub.android.acceptance.test`，执行器为 `androidx.test.runner.AndroidJUnitRunner`。组合候选必须重新记录 source/App/runner/fixture 指纹。本线编译成功不等于组合候选或设备通过。

取得设备租约后可从仓库根目录启动独立侧车，使用尚不存在的树外私有目录：

```sh
node docs/plans/android-alignment-20261022/parallel/p8-easywork/authorization-fence/phase2/fixture.mjs --start /private/evidence/p8-fence-new-run
```

两个 listener 仅监听 `127.0.0.1` 临时端口。统筹读取 config，建立本设备的恰好两个同端口 loopback reverse 映射；将 JSON 原样作为 `p8AuthorizationFenceConfig` 参数，选择新类或准确方法并加 240 秒外层期限。保存逐方法原始结果、`testMethod` 对应 case JSON、config、组合指纹、映射前后记录，再停止本侧车并确认退出，只释放本次映射。入口不自行安装或调用 ADB。

夹具复用冻结授权夹具的独立 JWE/DPoP 纯函数，使用新的 issuer/状态机，原文件不改。签名先验证再更新状态，支持持有已接受 refresh 与丢弃 revoke 响应的组合。原 access token 在模拟服务原过期点前可发出撤销，撤销后资源访问始终拒绝。保留的批准密文用于检验迟到保存，不能冒充真实服务允许重新授权。Node 自检只调用纯 issuer，不开 socket；目录 0700，config/case 0600，日志仅保存 token/body 摘要、公钥 thumbprint、原 operation 和完整目标。

## 限制

共享锁/现有存储事务协调当前 App 进程中的多个实例；持久 fence 跨客户端重建保留决策。真实进程杀死/重启和同时多进程文件锁未验收。已交付 proof、已建立的会话/通道和在途请求由服务端裁决；本窄修复不修改会话/传输，也不宣称取消远端命令。新审计名必须由迁移负责人纳入完整 inventory；当前未知/未支持记录应保持整档拒绝，不能遗漏 fence 只恢复 grant。原有账号/设备/目标和未知操作策略不变。旧实现若已覆盖原意图字段，本修复无法从现有记录重建丢失数据；legacy 接入只冻结现存原 ID 和可读取快照，不独立证明更早 HTTP 的 token 指纹，历史未知仍需核对原回执。

授权 HTTP 保持系统信任，本夹具只用生产 DEBUG 明确允许的 loopback HTTP。系统信任 HTTPS、真实 issuer/桌面批准、nonce challenge 和 iOS 原生路径未验。iOS 无源码变动；其撤销与迟到保存仍按共同断言独立核对。完整七组未关闭，A6 保持暂停。
