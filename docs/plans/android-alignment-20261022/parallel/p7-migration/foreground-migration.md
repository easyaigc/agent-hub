# 冻结 P9 v1 前台状态的迁移边界

源审计/闭合 Schema/策略 checkpoint 为 `dfaeac8b`，只读依据 P9 `160b51ec`（父线采用 `66b12bd8`）。完整 SHA 与 Schema 文件指纹见 `foreground-source-audit.json`；当前策略见 `foreground-migration-policy-v1.json`。未导入或改写 P9 的 identity、journal、wire、monitor 等生产文件，没有推测其开发中的 v2。

## 原件与安装权威

`NativeMigrationForeground` 是冻结 v1 的只读适配器。它检查实际 X25519 私/公钥对应关系、规范 Base64、真实 device ID、bound marker、journal 身份、完整目标、偏好、操作/事件指纹、unknown/direct-response 条件、batch/cursor 绑定和既有容量。P7 的封闭字段守卫不会因后续 P9 parser 扩展而接受 future 字段。它只调用已有 Codec/密码库校验，不调用 P9 identity loader、monitor、HTTP 或模型。

`SourceAudit` 返回精确原 journal 字符串及原记录摘要、操作/事件 ID 的只读视图，诊断 `toString` 固定脱敏。原 mutation/state/target/response/fingerprint 和 pending/processing/unknown 都不重写；授权指纹不成为 bearer credential 或原撤销授权。这个视图是源安装本地审计接口，不是可迁入的 journal 或永久便携归档承诺。原加密文件继续保留；非空 journal 的完整迁移仍未完成。

v1 的真实限制是：安装私钥、公钥与 device ID 必须排除便携文件，但把原 journal 一起移出活动记录后，重新启用通知可能新建身份并 register，从而绕过旧 unknown 或待撤销分支。原授权快照不在 journal 中，仅有指纹；P7不能由此捏造授权，也不能制造一个假 journal/身份来阻止运行。因此当前任何**非空 subscriptions**，包括已撤销或仅终态资料，都明确拒绝导出/激活；不靠重新标记或删除原件宣称完整通过。父线需与 P9 交接真实的不可执行归档、恢复 barrier 和原授权生命周期，才能放宽此边界。

## 已实现的安全子集

源安装只有合法未绑定身份，或合法匹配的空 journal 时，文件流与旧有界入口都先验证原件，再将源 identity/journal 从便携记录中排除；它们不出现在候选或目标安装。原源密文保持不变。手工用正确密码加密的档案如含这两个原活动记录名，也直接拒绝。

新目标的当前本地身份可以保留：只有完整有效的未绑定/空 pair，才逐字加入同一个准备代次，使用目标原密钥保存，而非接收源身份。非空、身份丢失、bound journal 丢失或不匹配均拒绝，留待独立审查的恢复/撤销流程。两个保留记录参与原 prepared 内容摘要、原子指针、失败回滚及 sourceRecords/actualRecords 区分；同一激活回执不会覆盖后来的本地身份或草稿。原通知偏好关闭/重置、policy3 审计/独立墓碑索引继续生效。

审计与目标 pair 读取使用窄 `migrationReadTextRecords`，在读取前检查 generation 指针与所选文件的 `.bak/.new`，包括符号链接；不借 AtomicFile 恢复这些身份资料。它没有扩大原记录/解密上限，不改 GCM 生产边界。该接口不承诺改变存储对象构造或其他已有调用的恢复语义。

## 原生准备与未验收

`NativeMigrationForegroundTest` 共 9 个方法，使用私有合成身份及原有 fixture。所有 child suffix 为短前缀加 8 位随机后缀，没有修改既有 32 字符限制。以下均只准备/编译，未在本线设备执行：

1. `unboundSourceIdentityNeverEntersEitherPortableFormatOrNewInstallation`
2. `validatedEmptySourceJournalIsExcludedAndDestinationOwnPairSurvivesRollbackAndRetry`
3. `unknownMutationHasExactReadOnlyAuditAndBlocksExportWithoutDeletingOrReplayingIt`
4. `processingBatchAndOriginalOutcomesRemainExactAndCannotBePromotedByMigration`
5. `futureCorruptMissingOrMismatchedIdentityJournalFailClosedWithoutGeneratingAReplacement`
6. `evenPasswordAuthenticatedPortableIdentityOrJournalCannotReplaceAnOriginalCandidate`
7. `nonemptyOrBrokenDestinationJournalBlocksActivationWithCandidateAndLocalAuthorityUnchanged`
8. `frozenP9DecodersIndependentlyValidateTheSameReadOnlyOriginals`
9. `pendingIdentityFilesCannotBeRecoveredOrDeletedByReadOnlyAuditOrActivation`

第8项在父线准确采用的 P9 依赖上，只读反射调用真实 Identity 私有 decoder 与 ForegroundStore.validateEncoded 作差分校验；缺少生产类是前置失败，不跳过冒充通过。本分支没有复制这些生产文件，其 APK 不能替代父线带该依赖的准确构建。其余方法只调用P7适配器/现有Keystore，不把合成 journal 或本地 BC 密钥校验当成 P9 注册、系统通知、真实 Host 或 DEV 成功。

父线独占的 LegacyGcmTest 缓冲修复、Capacity4/Sound7 namespace 修复未修改或覆盖。本次 APK 若单独使用，仍含这些旧夹具，不应替代父线新候选执行它们。中央总表、Manifest、localization、MobileSession/MainActivity和iOS未编辑；A6保持暂停。非空 journal、系统生命周期、监控暂停/fence、真实授权/撤销及完整双端/DEV仍分别验收。
