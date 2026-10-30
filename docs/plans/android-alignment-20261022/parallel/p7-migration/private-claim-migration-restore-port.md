# Private-claim fixture 接入正式迁移恢复端口

本轮基于 `0b6724e0`，回应 Root 的 Source65 C01 **明确失败**：首错是 `NativeMigrationReferences.kt:73` 的 vault client/metadata client 严格等值，附加错误是 `remoteCalls` 期望0、实际1。原 stdout 指纹保留在 source-map，Source65原失败、操作、fixture、alias均不改、不重跑、不清理。Root报告已采用此前owner修正并完成Source65组合SDK/三APK独审；这不构成本轮或新Native通过。

原fixture的 `leaveForeground(); restore()` 仍走 `preparedGeneration=null`。现有普通恢复尾部的history/refresh/operations分支不要求foreground为true；因此仅离开前台不能满足无remote尝试。Root当前源码另有localInspectionOnly门槛，但同样要求preparedGeneration为空才能进入该分支。具体哪次写入造成client差异不从堆栈猜测；本轮修正错误端口，不修改生产语义。

最小接线把sync `restore` 留空，suspend `awaitRestore` 调用现有 `restoreForMigration()`。后者自己设置foreground=false，在IO提前beginRestore，随后调用prepared-generation恢复并等待restore/reconciliation。主树与本树该正式端口区域逐字节相同，生产MobileSession不改。所有入口仍由同一owned()处理，因此source与destination一致接入。

同一受监督coordinator内，在IO保存恢复前的metadata与vault逻辑原文；正式恢复完成后立即在IO严格比较两份原文，再允许quiesce/acquire。这是未来真实Native会执行的原件保护断言，失败只给固定消息，不打印任何原文。没有伪造client预期或修改vault；原seed仍让metadata和vault使用同一encodeClient，References严格等值与remoteCalls0均保持。源export捕获、目标activation与后续unknown操作原件断言也不改。

Session仍在Main实际创建后立即登记，才进入上述可挂起IO/restore。此前0b owner helper完全不变，保持首错、准确coordinator真实完成、同步IO退出与未知保留门槛。新增两个可移植向量验证：awaitRestore挂起期间已持有原Session且不能提前acquire；挂起时取消仅通过现有实际abandon确认原Session退出，未进入acquire、不释放不存在的lease，并等待准确Job完成。

[最小补丁](private-claim-migration-restore-port.patch) 只改fixture与portable test两个文件；[source-map](private-claim-migration-restore-port-source-map.json) 给出前后摘要与生产/owner/Native selector不变项，[契约](private-claim-migration-restore-port-contract.json) 区分前后条件。只影响Android测试夹具，iOS产品行为不变，按Root范围不写中央总表或Root现场。

辅助检查一次通过：portable fake-resource18与PSI5，输入无漂移、无未处理运行异常；执行方实际Terra/medium已核journal，速度接口未提供参数，不宣称极速。不能用它证明真实Session/Keystore、原metadata/vault比较、References或remote0已在Native通过。Root仍需组合Source66 SDK、新Test-only升级及另行准入的全新Native fixture；原Source65失败永久保留。没有ADB/设备/安装/Host/GUI/业务输入/新运行UUID/未知操作查询重放，任务/项目和iOS A6继续暂停，完整P7未完成。

## Quiesce 后仍有明确的静态契约差异

Root后续要求扩大核对到实际quiesce捕获点。源码已确认：synthetic client省略requiresReadTarget参数，metadata/vault由同一encodeClient播种；prepared restore仍在内存client上设置requiresReadTarget；quiesce把metadataSnapshot及realClients重新编码后enqueue并等待，而这条路径没有同步vault。encoder包含该字段，References又比较完整JSON。因此移除普通refresh只能修掉remote尝试，仍不能据此宣称C01可通过。

当前seed的storageIdentity被显式编码为JSON null，所以withKeys不会改变这个seed；非空storageIdentity的一般恢复路径另会合并namespace keys，同样需由Root定义其与vault的关系。online仅为内存状态、不参与encodeClient；这个seed的readerId等其他列保持原来源。字段名、摘要、原始源码位置与三阶段判断见[quiesce审查](private-claim-migration-restore-port-quiesce-review.json)，没有读取原失败fixture的client/vault数据，也不打印原文。

**当前候选不具备新Native准入结论。** 生产References、vault、初始client、quiesce保存行为均未改；需要Root先对该whole-client契约与合法恢复字段的关系作语义取舍。本轮交付必要的端口修正和证据，不用初始化预期或vault重写绕过失败。原C01失败和原件保持，Source66设备准入由Root另行决定。
