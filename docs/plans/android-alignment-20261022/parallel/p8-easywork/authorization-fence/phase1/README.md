# 授权保存栅栏：阶段一

启动时的状态为“源码发现，等待父线基线实测”。随后统筹回报准确组合 `b2a4f589db49e4555f1c9679d9f6e32d9d1856aa` 在 Android API33 真机上：18 核心通过；`lateRefreshAfterConfirmedRevokeMustNotRestoreRemovedGrant` 因本地 grant 被迟到刷新恢复而失败。该组合的授权生产文件和冻结 19 方法与本分支基线 `9f79fe5ef5ec028f510b796d86c5c4e0704f2494` 字节一致。原始设备证据由统筹保管，本线没有执行设备或服务操作。

本阶段新增 `EasyWorkAuthorizationCoordinator`，**尚未接入生产授权调用**。13 项宿主 JVM 故障/并发检查通过；这是记录协调器的辅助证据，不代表 Android 文件、Keystore、网络或修复后真机通过。下一阶段只改 `EasyWorkDeviceAuthorization` 接入，并新增修复专属原生场景。旧 16/12/19 方法与其文档不改。

协调器复用现有 `NativeSessionStorage.coordinationKey` 和 `operationTransaction`，不扩展存储 API。网络 mutex 按完整 canonical root/ref 共享，刷新与撤销分别持有 mutex；短记录事务由现有同根锁串行执行，撤销可在刷新等待回包时先保存栅栏。不同 root 或 ref 不共享网络等待。

持久记录名为 `easywork-authorization-fence:<SHA256(ref)>`，由原 NativeSessionStorage 加密。记录含 ref、epoch、撤销 pending/confirmed、原操作/body/原保存快照/指纹。撤销栅栏先于 DELETE 和原 grant marker 写入；确认栅栏先于 grant 删除。任一步骤中断均保持拒绝本地授权，显式恢复使用同一原 ID、body、token 和新的 DPoP proof；不重注册、不换 ID、不重放业务输入。原有 `revokeOperationId` 可原样接入。

刷新准备在根事务中读取当前记录并持久原 pending；保存时重新检查根/ref、epoch、原 pending 与 grant 状态。确认撤销及未知撤销都阻止迟到保存。保存以最新根 JSON 为基准，已验证新 grant 深合并保留未知字段；原 pending 的额外字段及 token/body 指纹先写入本协调器的 `refreshResults` 审计。不会改写原任务未来可能带来的根级 `refreshReceipts` 或其他未知字段。审计没有新增清理策略；后续容量策略必须保留活动栅栏及未知意图。

`install` 防止旧批准响应恢复同一已撤销 ref，也保留已存在的轮换 grant。`assertAuthorized` 用于最后返回授权 proof 前复核。已返回的 JWT/已在传输中的请求仍由服务端撤销规则裁决，本地栅栏不宣称收回此前已交付的请求。

阶段二将拒绝活跃迟到刷新的等待方。冻结旧回归直接 `await` 成功才检查 null，修复后会在该点得到拒绝异常；保留它作原基线，新方法须同时断言拒绝和 ref 未恢复。不得把旧回归的异常结果记为修复后通过。

合并边界：只接入本分支已提交授权源码，不读取或整文件覆盖主检出的临时错误分类、批准续读、refreshReceipts 和过期确认 WIP。提供逐 hunk 的合并说明。新逻辑记录必须由迁移负责人纳入完整 inventory；缺失栅栏的迁移不得静默恢复授权，本线不改共用存储/迁移 API。iOS 实现未改，授权撤销与迟到保存仍需其负责人按共同断言核对。

宿主检查入口（通过统一构建槽运行）：

```sh
node scripts/mobile/gradle.mjs :android:testDebugUnitTest --tests com.agenthub.android.AuthorizationCoordinatorTest --init-script docs/plans/android-alignment-20261022/parallel/p8-easywork/authorization-fence/phase1/validation.init.gradle -PagentHubAcceptance=true --console=plain
```

这些测试使用真实宿主 JSON 与可控事务记录 adapter，覆盖同根跨实例、同 ref 跨根、持久未知恢复、写入/移除故障、原意图与未知 JSON 保留、迟到刷新与批准拒绝。它不假装是 Android 原生结果。
