# Private-claim 审计接入生产迁移图的候选

本阶段基于 Root `144cce4adb7d009ba27c1bcbf70f3aade866e444`，将此前 `301a8f42` 的 standalone codec 接入同一生产迁移图。交付是四个 Root 文件的窄补丁、新 owner 边界、更新后的 codec 与辅助用例、实际加密存储的 Native 夹具源码。Root 当前文件没有被写入；本次组合尚未应用、SDK 编译或 Native 执行。

Root 已采用 commonIO 的实际 Session IO、HTTP/NSD 停止边界，本阶段直接复用。此前 843 份源码的 SDK 结果和 32 项 commonIO 辅助结果分别属于原指纹，不能证明本次 claim 图通过；完整 P7 仍未完成。

## 接线与权限边界

[补丁](private-claim-production-root.patch) 及 [八处精确片段](private-claim-production-root-hooks.json) 绑定 Root 原文 SHA 和逐文件投影 SHA，已只读 `git apply --check`。仅涉及 `NativeMigrationExport`、`NativeMigrationOriginals`、`NativeMigrationCandidate`、`NativeMigrationActivation`。Storage、Session、Main、生产 claim 提交函数和 wire 不修改。Root 需加入本阶段完整 codec、`NativeMigrationPrivateClaimBoundary` 及对应测试，再语义合并补丁；不能仅应用补丁而遗漏 standalone 依赖。

生产流式导出在原始快照上先要求原 Session lease、同一 storage 身份和准确 operation 的 frozen writer，再将完整 claim 原文投影到现有 `NativeMigrationInertArchive`。原始密文不因导出被重写；普通 Session quiesce 的元数据排队落盘先完成，夹具从这一准确边界采集密文摘要。现有 `originalsUnchanged`、owner 和最终发布栅栏继续检查。旧的无 owner `capture`/`seal` 对活动、惰性及未知 claim 家族都拒绝，避免从旧入口绕过。

导入候选只识别通过闭合校验的审计目录与别名，活动 claim 文本/二进制键仍拒绝。stage、旧有界候选读取、stream cold read、`currentReceipt` 和激活前校验共用同一图，不新增“恢复时宽松接受”的旁路。缺目录、缺正文、额外或改写别名、坏原始 wrapper/版本/哈希/目标/公钥/证书及超限资料整包拒绝。既有全局未知记录、二进制和引用检查继续保留。

目标机当前 claim 及既有审计先以原名、原文、字节数和摘要并入惰性原始组，再与来源并集。原名称保存在 entry 中，活动 `codex-private-claim:` 名称不进入新代生产命名空间；原安装资料与目标原始意图不会被当作可丢弃缓存。迁入审计不成为新设备凭据、私钥、本地 URI、OS grant 或执行入口，不能触发 `authorize`、POST、结果查询或重放。

激活不仅检查目标已有 claim，还检查迁入审计；即使目标根为空，也须持有实际 Session/frozen writer lease 才能发布。检查位于既有发布 guard，覆盖 checkpoint 0 前后，目标原件的快照闭包也保留同一 owner 检查。已明确发布的本地候选收据仍可幂等返回，保留之后的新草稿；它不再次发布，也不是对未知远端操作的查询或重放。

## 不把未知资料变成成功

完整 producer intent 可在已配对成功或移除连接后留存；它没有持久回复/完成字段。因此，账本存在只证明原意图曾落盘，不推断配对结果，也不要求它解析成当前客户端或目标机相同手机私钥。这里的稳定 intent ID 是原始 UTF-8 SHA-256 逻辑名称；wrapper 没有独立 `operationId` 字段，不能补造。主迁移图中已有操作的 ID、原始 intent、摘要、phase/result 则继续独立保存并保持只读。

本次增加对 `codex-private-claim*`、`migration-private-claim*` 保留家族的显式拒绝：拼错、未来版本或未实现形式不能在目标并集时被忽略。旧 tag1 哈希文件若没有闭合名称索引，仍按未知原件整包拒绝并保留，不猜名、不读取网络结果。其他存储组沿用各自 Root 边界，不因为加入 claim 就宣布支持。

沿用 v1 目录及原身份域，格式、密码封套和算法不变。每个原始组最多 128 条、单条最多 8192 UTF-8 字节、合计最多 512 KiB，同时受总图预算约束；超过预算保留原件并拒绝，不能截断。[补充契约](private-claim-production-contract-v1.json) 与 [完整 24 组差量](private-claim-production-scope-delta.json) 保留所有未验、模型/缓存、恢复候选及暂停业务项。[库存窄补丁](private-claim-production-inventory.patch) 只给 Root 的 `root-claims` 行增加当前候选、契约、未验和未知策略四字段，原 24 行及其他内容保持。两份中央库存和产品/设计/验收表均未被覆盖，Root 按实际组合与验收更新。

## 可交 Root 调度的 Native 源码

[精确 selectors](private-claim-production-native-selectors.json) 包含六个独立 warm 方法和三个有序 cold 方法，全部未执行。Root 为每个 warm 方法提供新 `privateClaimMigrationRun`；cold 链沿用同一新 run，并独立核对三个 instrumentation PID 不同。此处未分配运行 UUID，没有构建、安装或调度设备。

夹具使用隔离 ContextWrapper 的 source/destination 根和角色专属 Keystore alias，走实际 Session restore/quiesce、frozen lease、密码流式封套、候选与原子激活。HostReads 及 CodexCommands 全部由拒绝端口计数，要求零调用；不启动真实 Discovery、HTTP、UI、语音或模型。夹具不会自动清理原件，Root 保存最小证据后按准确 run 收尾。

断言覆盖来源密文、目标当前 claim 并集、原操作意图/未知结果、坏原始加密记录、缺失/改写/额外别名、旧未知 tag1 与其他未知根 scope、无 owner/错误 owner/过期 lease、空目标迁入、发布故障、未来目标 claim 版本、冷收据不重建与后续草稿保持。坏候选从已通过完整图校验的真实候选构造，避免用无效 metadata 掩盖校验缺口。它们是待执行的源码，不是加密存储或生命周期已通过的证明。

## 本次辅助结果及限制

最新窄 JVM 编译和 **11 项 codec 用例通过**；真实 storage/Session/lease 在此检查中是不可达桩，新增 owner 边界只编译声明。生产 strict JSON 以 Gson 绑定，DomainContract 与仓库 schema 为实际源码。Kotlin PSI 对四个 Root 投影及五个独占 Kotlin 文件完成语法解析，**没有类型绑定**，不等于 Android 或 Native 编译。原 parser 工具先因 Kotlin 2.4 的 ExtensionStorage 未初始化失败，原失败保留；只修 helper 初始化后通过。后续因保留家族校验/Native 断言变化按需重跑，未重放未知测试或原操作。

执行方均显式 Terra/medium，并由主会话核对实际 journal；创建入口没有速度参数，不宣称速度档生效。完整来源、摘要、结果与限制见 [source-map](private-claim-production-source-map.json)。Root 实际应用、SDK、九个 Native 方法、真实 NSD/HTTP/OS、真实 DEV 和完整 P7 仍待验收。

iOS 实际 `CodexDiscovery.swift`、`EncryptedMigration.Payload`、`SessionStore.migrationPayload/restoreMigration` 仍为显式字段流程，没有同类 generic claim 账本迁入或恢复 POST。本轮不改 iOS、UI 或格式，不借用其历史证据；F65–F67/任务项目与 iOS A6 保持暂停。旧 5566/Native 未知状态、设备、Host 和原业务操作均未触碰。
