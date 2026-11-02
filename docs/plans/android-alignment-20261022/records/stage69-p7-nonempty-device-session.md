# 阶段69：非空设备资料的实际Session迁移

2026-11-03，F55/F56。[总表](../progress.md)维护优先级，[待验清单](../pending-acceptance.md)维护缺口。无产品UI/iOS源码变化，implementation/完整verification保持。完整工作区检查通过；提交与PR交付见总表。

## 本次闭环与范围

API33/arm64独占验收包，`nonemptyDeviceProducersRestoreActualSession`，Run `85df22ad-5ba3-4080-afbc-4fc0939bfcb5`。真实producer先建立非空阅读账本、通知偏好/leases/unknown receipts和normalized CAF，再由普通实际Session执行：

1. 目的实际Session以正常本地草稿发布/清空、writer drain和metadata读回建立空profile/root KEY；它是已初始化空目标，不能代表pristine首次安装。
2. source Session真正quiesce后取得Frozen，调用实际`NativeMigrationExport.sealFile`及stage，核原candidate密文；source实际restore后原密文保持。
3. 实际destination lease、activate/reset读回，实际owner Session restore/await ready，核阅读/未读结果。
4. 只读原unknown journal/replyRecovery、不可变intent/submission及txt附件，再编辑独立新草稿；未query/send/retry原操作。

8条TEXT：`codex-private-connections-v1`、`draft-file:<synthetic-id>`、`metadata`、`mobile-reading-ledger-v1`、`native-chat-notification-sounds-v1`、`native-chat-notifications-v1`、`operations:<synthetic-target>`、`submission:<synthetic-id>`；1条binary为`native-chat-notification-sound-v1:<synthetic-id>.caf`，共9条。voice preference原记录缺省，声音catalog不是完整语音迁移。通知foreground identity、通知archive producer未覆盖，不能把阅读/通知相关样本视作全部通知owner范围。

准入登记27个stores、4个Sessions；4次实际abandon/join返回、25次显式writer retire返回加2个生产old-root退役、2次lease release返回。unknown acquisition与注入Codex forbidden attempts各0。这是登记/调用返回计数，不证明OS FD、executor termination或Controller worker join；真实EasyWork adapter仅构造未调用，也非全网络/通知sandbox。

iOS源码保留迁入device identity/bindings，并在通知关闭时排队revoke；Android把迁入设备authority重置为失效audit/tombstones。底层存储政策不同，不证明整包互通。原[阶段66](stage66-p7-strict-first-prepare.md)/[阶段68](stage68-p7-controller-strict-restore.md)strict owner/first-prepare与Controller结果保持独立，本例不扩大其授权证明。

## 真实结果和证据绑定

私有索引根：`parallel-20261028/`。

| 证据                                                             | 结果                                                                                                                       |
| ---------------------------------------------------------------- | -------------------------------------------------------------------------------------------------------------------------- |
| R5 SDK `stage69-r5-sdk-execution/index.json`                     | 实际exit0/23.660s；23份source前后相同，33份索引材料由Root核对；仅SDK/DEX，不是Native                                       |
| R5 Native `stage69-r5-native-root-admission/instrument.stdout.0` | 完整具名start/finish，STATUS 1/0、CODE -1，`OK (1 test)`，无失败/跳过                                                      |
| Native `instrument.wait.json`                                    | PID11576实际wait exit0，4.769823s，无timeout/signals                                                                       |
| 外层 `stage69-r5-native-actor-execution`                         | PID11556实际wait exit0/5.755452s；typed result outcome passed；33个host children全部实际wait，无timeout/signals            |
| `first-proof.json` / `external-observations.json`                | PID6426/start427947/UID10174/同boot；4次独立匹配均在原Native进程活跃期，errors空；不是cold                                 |
| `host-review/first-host-review.json`                             | 实际verify-first PID11645 exit0/0.136057s；nativeAssertionsReportedComplete与originalPidObserved为true，coldAccepted=false |
| `stage69-root-primary-native-review.json`                        | Root直接读JUnit/typed proof、7个raw hash joins、4次独立PID观察、全部33次wait与各scope密文图                                |
| `stage69-r4-parser-preflight`                                    | 现Tool同hash，20/20解析边界辅助通过；合成APK/parser测试，不是Native通过                                                    |

SDK index H `d3bce5b850ffe80b3fa8f3c87d76d3d8ca4be9da87a1d5a55a1307fc9f9c1453`；app H `39c99bfd17dadf7947ad708f8e49eea2108eb294e277fed62d6acfbfc5dc7fc7`，test H `24652ed49e53f7b5f97b7285749f5d6d8076f14b6fb46588e2269d4f55e737c1`。实际loaded包绑定source guard；first guard=19份基线+3份新增，独立于SDK计数。

| 原材料            | SHA-256                                                            |
| ----------------- | ------------------------------------------------------------------ |
| Native raw stdout | `7159381f69c36516032e2119f3359e3e448bdedf8d12f1be5d8111979221e554` |
| first-intent      | `dd8bdf1b75591ebd96d2475d48488d96aaec45999cfe35e21065b2da59afc539` |
| first-proof       | `9f98237c17c4c219e1e2b1e7e20fff4f3b7b1a0354382a52e4209fb8fca3da12` |
| host first review | `d212c8b9fdb7aa480fb8a460fade9adb452cd9983cfa0b92163cab14fda3f42c` |
| outer-result      | `3eb8cba11b4f7c86f590aa52edb3988bef1506e93f2226f4e1bf8670b9eb5b66` |

执行与查证Agent均逐个核对官方journal，实际为`gpt-6.1-terra`/`medium`，索引`stage69-official-execution-models.json`；速度接口无参数、实际未核。文档Agent未执行测试/格式/生成器或改绑定源码。

## 修正和原失败保留

生产阅读API的body visible lease没有notice IDs；新Case在body visible后显式取得`visibleNotices` lease，再按原断言核seen notice，未弱化断言。旧`NativeMigrationDeviceStateTest`同一调用者修正本次只编译，未执行该旧Native方法。目的profile/root KEY改经真实Session提前初始化，未放宽existing-only读者或修改生产fallback。

- R3 Run `9469c6f7-9a6b-49cf-86ed-b28de00f5750`：seed seenNotice断言因body-only lease失败；JUnit FAIL1，即便am instrument host rc0仍是失败，尚未export。
- R4 Run `91c7e63b-05b0-4b0b-832c-8943ab431eb9`：export/stage返回后，目的实际Session尚未建立，existing-only root读者因缺installation KEY拒绝；JUnit FAIL1/host rc0。

两次均无first-proof、不verify、不cold，封存NO REPLAY。旧PID5554/start159845与6006/start288082已在同boot独立确认自然退出，无force-stop。早期R1/R2/R3所有权审查是静态边界核对，不能记成自然I/O或掉电故障通过。

全部四源完整H见manifest；Case/Tool摘要如下，绑定字节未改：

| 源码简称                             | SHA-256                                                            |
| ------------------------------------ | ------------------------------------------------------------------ |
| NativeMigrationNonemptyDeviceCase.kt | `daa028bebbe288408d25f50d51c7cf06ae21d6ea238d58a6e1ca4458f163b781` |
| android-nonempty-device-first.py     | `11c13340cee7474bf91b750fc01f07fa9de29fc0db5e7c469e7aa7fa9837b7bd` |

`ThreadReading.kt`使用原7685基线blob，H `08269bf48dea76f82d209ff251aa1ea3f1304893ed350c881ee94babfe99f280`，来源核对见`stage69-r4-fixture-fix/root-baseline-blob.json`。

## 独立待验与下一步

最高优先补配对first/只读cold：cold尚未实现，后续必须新UUID、同一新SDK/app+test/source guard；原PID独立退出后才读原candidate/receipt。若test APK变化，不复用本轮first-proof。接着单列pristine无root KEY首次导入，再按[24项系统范围](../parallel/p7-migration/system-scope-inventory.md)逐域补齐；当前不关闭任何整组。

全局通知coordinator/真实端口、foreground identity/通知archive producer、OS权限/SystemUI、shipping Main、完整语音原件/偏好、未读/生命周期与完整UI、真实Host/模型/桌面、自然I/O/掉电/崩溃、KMP整包互通及物理DEV仍未验。iOS SessionStore补偿失败恢复独立继续；P5私有harness的read-lifetime控制不记成产品修复。A6、任务/项目业务F65–67/UI14–19/UM06后续业务与UM07–08、F75继续暂停。

P8 Main偏好R3仅静态通过，未接入源码，运行态仍待验。P9新API36诊断boot/SystemUI不等于正常网络/产品通过，C侧新SDK、RWE34+及原public跨UID/SystemUI仍待验；原3次SIGSEGV、feature override/ANGLE和配置漂移分别保留于[待验清单](../pending-acceptance.md)，不阻塞P7。

## 资源和交付

成功Native既有sealed archive只读导出21479B，H `0126f3b1d71f0251e8d859617ecdbedaa5251a8fdd16d707b8c418086b5c8b8b`，索引`stage69-native-first-export-artifact/index.json`。它仅作为后续跨语言辅助向量的合成输入，不是A6或整包互通通过。

App6426自行退出，无force-stop。Root正常console关闭唯一5558，并亲自poll原exec27912核exit0；原QEMU59234实际wait exit0/4794.464s，端口已关闭，原回执`stage66-owned-api33-runtime/launch-stage69-nonempty/actualwait.json`。AVD磁盘为下一新UUID/同新包paired first+cold具体依赖保留，24小时内复核，不记本轮cold。P9诊断5560也在完整检查前正常console关闭，Root亲自poll原exec2863核原QEMU74868实际wait exit0/4794.170s；产品包未安装，最小config/boot/flag/原始日志已保存后，经官方avdmanager仅删除其AVD。回执`stage69-p9-api36-diag-runtime/normal-stop-retirement/actual-retirement.json`，后续当前C SDK就绪时重建新实例再验，不复用旧boot作为产品结果。其他VM不动。旧重复APK仅精确回收2份/67,856,057逻辑字节，当前同hash副本保留，回执`obsolete-identical-apk-retirement20261103`；其余依赖不动。

Android业务Host/model本轮+0，累计221，P4为205及另1条封存真实unknown；跨Host CI协调独立计数，不宣称所有Host流量0。已完成的远端CI专用聊天仍loaded，当前archive能力要求unloaded，保留待收尾且不动旧人类CI审批聊天。

PR116与准确CI/公开同步保留在[阶段68](stage68-p7-controller-strict-restore.md)。Root后续完成生成/格式/既定检查、安卓分支提交及交付后更新总表；不预记成功。

完整检查前另回收T中6个已完成Android编译/transform中间目录，约1.253GB allocated上界；33项SDK证据与四源前后hash一致，回执`stage69-android-intermediate-retirement/receipt.json`。APFS共享使allocated不等于实际释放，保留包/原始失败证据和Gradle下载依赖不动。Source72/82检查发现原QEMU仍活跃，且82是受保护的old5580，均未回收。

## 完整工作区检查

首次完整检查exit1/120.01s：公共日志拒绝测试等待超时，已完成Node组32项/31pass/1fail，后续未执行。原driver的私有证据umask077透传给子进程，将fixture要求的0644掩成0600；实际077/022权限探针分别为0600/0644，077单项再次复现同一5秒超时。仅恢复workspace子进程正常umask022、继续保护证据0700/0600后，该文件10项全部通过，未改产品代码、断言或等待上限。原失败及解析器首次只识别TAP而漏Node24摘要的证据缺口保留，未改写旧回执。

正式完整`pnpm check`以umask022实际wait exit0/720.77s，42个Node组摘要合计1518 tests、1456pass、62skip、0fail；不是1518个独立产品场景。原始日志198563B/H `70a2bed5a28b0c370725bcc3470b7150238eef9dd5b06b744b6312df5a591e1c`，索引`stage69-umask-recheck-execution/evidence-index.json`；Root重算组摘要并核原始退出，见`stage69-root-workspace-check-review.json`。15项工作树状态与四源H前后一致。workspace检查独立空间准入未改变Native16GiB门，未运行新SDK/Native或iOS工程build；本轮无iOS工程/源变化。文档交付更新后另执行生成/格式与一致性聚焦检查。
