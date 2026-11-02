# 阶段68：Android实际Controller严格交接与冷读

日期：2026-11-02。F55/F56、P7限定增量；不关闭非空资料完整迁移、24项系统范围、P7/P8/P9或完整UI。24项包含非可移植及暂缓范围，不是24个共同wire域。

## 实现与边界

实际Controller的enrollment audit通过NativeMigrationReadLifetime.use reopen原storage，在finally退休audit writer并保留首错。ACTIVE后由新Session持有prepare后的runtime-owned candidate child，两处reader复用该child；不制造无owner的额外writer。constructor创建的Documents目录cache/migration-ui-v1以正规0700、空目录及同一identity精确纳入schema v3准入，不扩展为任意cache目录白名单。原direct v2语义与证据保留，本轮未重跑v2。

新测试实际调用Controller.show/recoverCandidate/confirmImport，观察PREVIEW(READY)→APPLYING→RESTORING(IMPORTED)→DONE(IMPORTED)→close。PREVIEW含1电脑/1草稿/1操作/1附件、通知archive0。没有MainActivity/dialog像素、完整MobileSession UI或真实全局通知端口验收；iOS没有改动，[阶段64](stage64-p7-ios-sessionstore.md)补偿失败恢复仍待验。

## 两项Native结果

API33/arm64独占验收包，Run `0163ba98-4e21-4a67-a056-9c37dc62f73b`；准入18份source guard，App/Test/runner/原candidate与typed材料按摘要绑定。

- `actualControllerStrictPostPointerFirstPrepareReconcilesAndTransfersOnce`：实际Controller、严格writer、MobileSession及首次prepare交接通过；原始JUnit OK (1 test)、STATUS_CODE 0、INSTRUMENTATION_CODE -1，0失败/跳过。
- `distinctPidReadOnlyAuditsExactModernCandidateReceiptAndPostRestoreBaseline`：原PID独立退出后，另一PID只读核对同一modern-v4 candidate/receipt和恢复后新草稿密文基线；JUnit同为OK (1 test)、状态0、结束码-1，0失败/跳过。

first PID4988/startTicks34496 → 独立同boot退出 → cold PID5104/startTicks35080，UID10174、准确验收包，boot `14c5cf2a-936a-45c0-97ce-73ae642f0414`。helper原Popen实际wait exit0、8.025136秒，10个raw哈希关联均核对。origin `2adeacc2-c9f6-45ab-a23c-8a3b32302365` → generation `5356ba0e-f746-48b9-9d10-3115ddd92a30`，只读核对原receipt，没有查询真实Host或重放未知操作。

quiesce/restore/prepare返回、enrollment audit打开/退休返回、candidate cache prepared的具名标量各1；activationCallsSourceDerived与prepareCallsSourceDerived各1是源码支持的推导，不是独立Activation边界实测。retire返回不证明物理FD关闭，ready/canDismiss不证明worker join。first登记53个acquisitions，unknown0、cleanupFailure0；两cleanup均bodyCompleted、test/Session workers joined、writer retired、fallback false。controllerCompletionObserved true，但controllerWorkerJoinClaimed false、controllerScopeDisposed false；必须等原PID实际退出后才准入cold。

cold acquisitions为空，newSession/writer/Frozen/Activation/prepare均0，不构造Controller；existingOnlyReadOnly与postRestoreCiphertextUnchanged为true。样本仍为7条text（含2×2 PNG附件/缩略图关联）与10个空数据child owner，不证明十个非空域恢复或完整范围关闭。AFTER_POINTER是具名scalar异常注入，不代表自然I/O故障、OS crash/断电、取消/构造失败Runtime或物理DEV通过。

## 辅助检查与指纹

SDK原wait exit0、62.786432秒，前后源码相同；Root核对25个index文件实际摘要。App 66808183B / `39c99bfd17dadf7947ad708f8e49eea2108eb294e277fed62d6acfbfc5dc7fc7`；Test 13730966B / `a87c895181392173723fecd4a48c5a679a06eb5bf2a11c9d9bf44da13a9ef440`。两包签名证书SHA-256 `fc12e68fdbe401e9dbea6c0ae9bb45e59f9d59517f9f19ac01663c04f737adf1`，包名/runner及DEX定义另核对。

| 阶段68源码                                       | bytes | SHA-256                                                            |
| ------------------------------------------------ | ----: | ------------------------------------------------------------------ |
| `NativeMigrationController.kt`                   | 18378 | `329236edcac18f8663250080abd1e18b8f2a4d63bd6efb5ec93a08f0f46f4224` |
| `NativeMigrationPostPointerStrictHandoffTest.kt` | 93630 | `d76fba1665925a9241029f8b2eb348ffa5aaa134940fb3a2489ac39b3830e6cc` |
| `scripts/mobile/android-postpointer-handoff.py`  | 45323 | `4f3c3247dd33d4f212ea86af108a1cd7c1f0965726a764ca4942a17265808c91` |

R2静态review未余P0/P1/P2；R1漏Documents目录导致的确定性false-fail已在源码修正，R1没有执行Native。Python AST检查通过；父级误用--help probe的exit1保留，工具实际只收两个位置参数，该错误不记产品回归或验收通过。阶段66 direct v2的原失败与两项通过不改写。

私有索引 `stage68-controller-native-execution/<run>/`：first-junit.raw 899B / `d453129b8d60cbde89c27487df7300d4be8d5bee6ecd1bb9d0dacd4fae71d116`；cold-junit.raw 906B / `1728e447495e4021c2f6bd0723a2cd257dea6c26de157656cf917022159383c3`。fixture-intent、handoff、两cleanup、cold-audit、outer-first-pid-exit与outer-run-complete均保留原typed/raw；Root primary review `root-primary-native-review.json`独立核对实际10个哈希关联。原件、机器路径与凭据不入Git。

## 剩余与交付

本阶段完整 `pnpm check` 实际exit0、775.008秒，原始198653B / `93f65d6410a1206f545e13d8b29138fe7168fe03d42d1b4614e7a3d4ec97bf22`。42次Node汇总1518项（含脚本重复）/1456pass/62skip/0fail，Gradle两次成功，Swift4/4；iosX64Test在Apple Silicon禁用，非真机通过。生成/限定格式化/diff均通过，源码3H保持；尚待提交/PR和实际远端交付，没有复用阶段66检查。SDK、Native和本阶段完整检查均已由Root读取官方turn_context核实为gpt-6.1-terra / medium；入口无速度参数，实际速度未核验。

最高下一项为P7非空资料域完整迁移，再推进语音、未读和生命周期；iOS补偿失败恢复、自然故障/崩溃/断电、取消失败Runtime、完整UI/物理与双端整包互通继续。P9阻塞不关闭P7，也不阻塞独立开发；P8原待验保持。任务/项目CRUD、F75及iOS A6继续暂停；Host/model本轮+0、累计221，P4 205另1未知原操作封存不查询或重放。

本轮重启的原Native66独占API33 runtime已由Root正常console停止，原工具实际wait exit0，本任务当前无运行VM；原receipt存于stage66-owned-api33-runtime/stage68-normal-stop。磁盘仅为本阶段源码提交/证据收尾及下一具名P7非空域复用暂留，未删除，依赖解除后复核。测试内cleanup不等于Controller job已join或安装数据已删除。
