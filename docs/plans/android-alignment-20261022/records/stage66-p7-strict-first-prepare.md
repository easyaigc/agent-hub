# 阶段66：Android严格首次prepare与独立冷读

日期：2026-11-02。F55/F56、P7限定增量；实现、辅助检查与两项Native结果分别记载，不关闭24域、P7/P8/P9或完整UI。

## 实现与双端边界

严格ExistingView.writer只为本storage实例、原lease/operation和自身已提交pointer补做首次prepare的核对/确认，不给冷reader或其他owner授权。实际MobileSession恢复先释放Frozen，再构造新Session完成BOUND→ACTIVE，交接原10个child owner并保存独立新草稿；未知操作的原目标、正文、ID、指纹及uncertain/noRetry保持，不查询真实Host或重放原操作。

Activation在finally逐个清理scratch；清理失败附加到原错误，不覆盖首错。Lifecycle在NonCancellable IO内先捕获已打开store；取消返回或factory失败时保留必要读取保护并退休writer，外层NonCancellable与内层IO分开，继续保留首错。这些取消/构造失败分支本轮只有源码审查与编译证据，没有实际Native故障取消通过。

iOS仍按[阶段64](stage64-p7-ios-sessionstore.md)的实际SessionStore三项结果与补偿失败恢复待验分别保留；本轮不改iOS，不证明双端整包互通。无页面、文案或token修改；Controller状态映射、完整迁移UI和真实全局通知端口未验收。

## 本次两项Native限定结果

API33/arm64独占验收安装，Run `3e9aa256-2321-427c-a2f7-a40a8e9f0058`。38份准入源码前后相同，App/Test/runner/原候选与完整typed材料按原摘要绑定。

- `actualStrictPostPointerFirstPrepareReconcilesAndTransfersOnce`：真实严格writer、Keystore、MobileSession和首次prepare交接，原始JUnit为OK (1 test)、0失败/跳过，INSTRUMENTATION_CODE -1。
- `distinctPidReadOnlyAuditsExactModernCandidateReceiptAndPostRestoreBaseline`：原PID独立确认退出后，另一PID只读核对原modern-v4 candidate/receipt、代次及交接后密文基线；不新建Session/writer、不重新Frozen/激活/restore。原始JUnit同为OK (1 test)、0失败/跳过，结束码-1。

first PID7904/startTicks382902，独立同boot退出核对后cold PID8003/startTicks383358；UID10174，准确验收包，boot `d70bf80f-77dc-440a-9ad6-ccacd72a4d21`。原helper实际Popen wait exit0、6.491977秒；不是由提交成功推定完成。origin `63bc69b3-061b-4ddd-915a-4eb5afcede2c` → generation `b2f675d1-b482-4c30-bbe6-e6a50ded2d60`，两方法核对同一原candidate/receipt。两份cleanup均bodyCompleted、worker join、writer退休，failureCount0/fallbackRequired false；冷方法不重复quiesce/release。

样本按handoff.sourceRecordsSha256实际长度为7条text，含2×2 PNG附件及缩略图关联，旧child owner为10个空数据图交接，不证明十域非空恢复；24项是系统范围清单，含非可移植及暂缓范围，不是24个共同wire域。AFTER_POINTER为具名scalar异常注入；afterPointer1、before/afterConfirmation0等为fixture边界计数，不是CPU、物理I/O或全局Activation实测。缺少后续observer事件只按源码发布顺序解释，不证明自然I/O故障、OS crash/断电或Controller恢复。

## 原失败与新Run分离

原Run `3166e86c-62c3-4808-8ed4-637f8f50411e` 在seed.publish后沿用旧代际writer；修fixture为重新打开current writer，生产guard保持。原Run `275dace1-b432-4967-8b9c-f5fe53030f49` 将保存后的device字段仍要求缺席；修为导入前缺席、保存后精确空JSONObject。原Run `d156dee0-8827-4b0b-be8f-2490f85ad958` 的namespaceInventory漏掉4个nested writer控制锁；仅按实际命名与regular 0B锁精确准入，不删除原数据或放宽未知项。

三原失败永久noCold/noReplay；原PID6192/7009/7538均独立核对同boot/proc ENOENT。只用新R6 Run执行新证据，不把修订后的结果写回原失败。

## 指纹与私有证据

| 当前源码                                         |  bytes | SHA-256                                                            |
| ------------------------------------------------ | -----: | ------------------------------------------------------------------ |
| `NativeSessionStorage.kt`                        | 109166 | `7248a18e7999cddc03c64ab418ce8e9636028a6dfe7d5da75d36d4bdc8d96a0f` |
| `NativeMigrationActivation.kt`                   |   8313 | `9f8239e40506ace7686675f5e40aa700ae27c475e991fd326d33b37d0d4c93b1` |
| `NativeMigrationExistingPolicy.kt`               |   6964 | `2084dbd91f3032bf07e68d1b3cec37ab0774415faf8ba9f4d15537325d41aa66` |
| `NativeMigrationLifecycle.kt`                    |   9377 | `4a37504cca06a536376e9b52aa829e02834537630d676b03beac886100005b24` |
| `NativeMigrationPostPointerStrictHandoffTest.kt` |  70937 | `49b8f6a20665c43285fa8bfe1f28eb86d2b9ff20130d53b27ca77e6bd0f37f44` |
| `scripts/mobile/android-postpointer-handoff.py`  |  40065 | `0ad525a3ca87fd3b3f61d631221e065ce1c8ed4560440331af16c12fb99ca512` |

App 70495033B / `941df6427c16e592b9f5ef4799e882ef3437ceebca8a9a48e28039953ab249a2`；Test 13700941B / `b860fa273362e1a9f3995a95b425401bb87594e41fa625d278bb93cddcaee3a0`。R6 Test SDK实际wait exit0、5.834秒；源码前后相同。仓库helper与实际执行的私有R4 helper（40142B / `4db734e405f51f9dd32e8872f5b2188cbd0355168da40364c1919b4cc76298b2`）除module docstring外AST完全相同，不声称本轮执行过新仓库路径。

私有索引为 `stage66-r6-strict-native-execution/<run>/`：first-junit.raw 880B / `54ea9bcacdabfaec69992fce1842549a1716996d2a5d699c97135a2480ac61fe`；cold-junit.raw 906B / `83bf52152bd47348b58b4d977f907967414a9ddf68d05f962b9dde5cf5f809fd`。fixture-intent、handoff-complete、两份cleanup、cold-audit、outer-first-pid-exit与outer-run-complete均保留原typed JSON；Root已逐份读回核对。原件、机器私有路径与凭据不入Git。

## 剩余与交付

本阶段完整 `pnpm check` 实际exit0、714.042秒；原始日志190347B / `1261b50301c32060965dac767b2d3276c62b39dd96a5b0d16c4c838cb305b0c4`。各次Node汇总1518项（含脚本重复），1456pass/62skip/0fail；Gradle两次成功，Swift辅助检查通过。Native五文件前后指纹相同；生成、限定格式化、Python AST等价及diff检查通过。尚待提交/PR及实际交付核对，没有沿用阶段65检查。R6 SDK、Native及完整检查执行方均已读回官方turn_context，实际为gpt-6.1-terra / medium；入口无速度参数，实际速度未核验。

Controller映射/完整MobileSession UI、真实全局通知端口隔离、24域/自然I/O故障/崩溃/断电、取消失败恢复、物理DEV与双端整包互通均未验。P8剩余、P9 OS/SystemUI及所有单列待验项继续；任务/项目业务、F75与iOS A6仍暂停。Host/model本轮+0、累计221，P4为205，另1个未知原操作封存不查询或重放。原Run材料保留用于只读取证；设备资源与正式交付收尾由Root按实际状态独立核对，不把测试内cleanup当作AVD/安装数据已删除。
