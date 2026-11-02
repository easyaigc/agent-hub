# 阶段65：Android旧候选与冷恢复

日期：2026-11-02。功能范围F55/F56；仍为P7限定增量，不关闭24组或完整P7/P8/P9。

## 结果与iOS对照

Android导入仍支持旧portable envelope，本地会产生v2分块候选；既有固定命名域的只读candidate reader却只接收v3/v4。首次真实冷进程验收在读取原候选时拒绝，现已补齐本地v1/v2读取。共享原有有界解码器，继续校验完整字段、UUID、原receipt、总SHA、每块大小/摘要、来源applicationId和闭合资料图；只从已固定的原命名域读，不创建writer、密钥或重新激活。

- 功能：旧候选可独立只读核对；原receipt/generation、未知操作的原目标/正文/ID/指纹和不可重试状态保持。
- 交互：typed restore回执丢失后保留IMPORTED/RESTORE_FAILED，原owner继续恢复；新进程读取PREVIOUS_IMPORT，不能再次激活或覆盖交接后独立草稿/选择。
- 界面：本轮没有更改页面、文案或token，也未验收实际迁移窗口、系统选择器、密码/取消、错误/成功呈现和完整MobileSession UI。
- iOS：[阶段64](stage64-p7-ios-sessionstore.md)实际SessionStore成功/预检/写失败补偿3项保持各自证据。本轮Android本地候选版本兼容不修改iOS，不宣称跨平台整包互通或Android等同iOS多文件补偿。

## 实际执行

新建独立API33/arm64模拟器，固定acceptance包与签名。以下分别单方法执行，各为1 passed / 0 failed / 0 skipped；原始JUnit均有OK(1 test)、结束码-1，Root逐一核对。

| 方法                                                            | 本次限定结果                                                                                                                                                       |
| --------------------------------------------------------------- | ------------------------------------------------------------------------------------------------------------------------------------------------------------------ |
| `readOnlyLegacyCandidatesKeepReceiptAndChunkValidation`         | 内存中的本地v1/v2、合法多draft组成的多chunk正例，额外字段、错误receipt/count/digest/chunk大小/代次、缺块及损坏copy拒绝；没有Keystore/Activity/default family I/O。 |
| `realCommitThenPendingRestoreLostAckAndSameOwnerResume`         | 实际Keystore、旧代次、v2加密候选、发布和Controller；typed restore回调持有/丢失ACK后同owner继续，DONE，quiesce1/restore2。                                          |
| `freshPidControllerAuditsOriginalReceiptAndPreservesGeneration` | 不同实际进程核对原候选与原发布回执；PREVIOUS_IMPORT、回调0/0；新草稿/选择、旧代次/候选密文、unknown原操作与noRetry断言保持。                                       |

两实际进程分别采到PID6257/startTicks55382与PID6336/startTicks55591，UID10174、cmdline准确绑定验收包；之后独立/proc及ps均缺席。回调worker清理与controller状态为真实断言；不把清理标志当作直接物理FD关闭或private controller scope join证明。activation未重入仅按实际代码路径推导，没有实际边界计数。

正式SDK构建退出0，源码前后2905项相同。当前冻结App为70490662B、SHA-256 `20bf316eb0249026060b6342120aa08ec8d542237a9ad8fcf5c0544568bd1ffe`；Test为13490426B、SHA-256 `460846eedc866e8be84b8dbb7b950ba7463f887f72eefecd6a413036a00306b9`。严格签名/包名/runner、设备安装base.apk摘要和DEX实际定义已分别核对。新包不同于旧冻结包，不按旧APK匹配复用；DEX引用不等于定义。

## 失败与过程另存

首次离线SDK缺依赖退出1；按固定版本在本工作树专用缓存补齐后成功。较早DEX匹配输出只有命中行，未冒充完整raw，已独立保存全量未筛选输出并无损压缩核验。后续直接APK DEX parser初次写错两个生产类名的断言失败保留，修正parser后通过；没有修改业务预期。

首轮restore-port方法1通过，冷方法2在旧reader字段要求失败；旧Run未重跑或升级成通过。本轮另用新AVD和新Run复验。原AVD的supervisor/QEMU消失、原工具session不可用，退出原因/码无回执，不补造；新的runtime由Root主会话持有，正常关闭及原wait分别核对。

静态审查发现新增测试单draft超过100000上限，已改为同一fixture client的三个完整目标，各90000字符；生产配额保持。真实验收覆盖这些当前源码。

## 指纹与私有证据索引

| 当前源码                               | bytes | SHA-256                                                            |
| -------------------------------------- | ----: | ------------------------------------------------------------------ |
| `NativeMigrationCandidate.kt`          | 13647 | `393c8aa81b1bacdedfb6c886f4f8712a2602b9ee9fa17372ea33a090861c5844` |
| `NativeMigrationExistingCandidate.kt`  |  3087 | `e6cff1e2f48ed7f36853c28e02175a43573413bef3e9e2b810f7f4c9fb565680` |
| `NativeMigrationRestorePortAckTest.kt` | 38725 | `0357ae45f5dd47521de9d28e7a1d64a01aa83329ed0abbe828f32dccf5928f7c` |

Root复核SDK：`stage65-root-legacy-fix-sdk-review.json` 4937B / `71ceac6fed2861470db75601aef389a212160d24aff537acba723ba299d17913`；Native：`stage65-root-legacy-fix-native-review.json` 8855B / `27e43ea7098f38338328425b34a37336824898f598c927976f91e087dba25577`，实际53份wait、106份原始流完整核验。正式执行方日志中的模型/推理为Terra/medium；入口无速度参数，实际速度未核验。原始夹具、回执、日志和机器路径不进Git。

## 剩余与交付

本阶段结束时严格ExistingView.writer首次prepare与实际MobileSession交接尚未验收；后续两项限定结果见[阶段66](stage66-p7-strict-first-prepare.md)，不回写本阶段3项证据。Controller恢复映射、OS自然故障/崩溃、24域完整矩阵、旧portable archive binary内容专项、UI与物理DEV仍开放。P9的OS零进程/SystemUI实际点击另行执行；P8原待验项不由本轮关闭。

阶段65原提交 `93c44ed7` 的CI11151失败保持；修订源 `c7fad315c1c0f235ad8ac3c020ec5ad28d70cc35` 的CI11154已成功，[PR114](https://git.51easyai.com/BCAI/agent-hub-app/pulls/114)已合入main `c36a5267c31d5af7adc3334061d04b2765e87f5a`，本地/origin/tracking阶段65分支均已清理。实际合并、CI及失败边界见[CI夹具档案](stage65-pr114-ci-fixture.md)。两个本轮独占合成AVD已由官方avdmanager正常删除，data/ini均独立确认不存在，删除前合计2116206592B allocated；新runtime正常kill及Root原工具/child实际wait均0，旧缺失wait保持未知。当前APK与最小充分证据保留，无全盘副本或活跃模拟器。Root收尾索引 `stage65-root-owned-avd-cleanup-review.json` / `935cad6129638238e397281e5adbd3dd57d2cacbdc20b7b94628d65904bb56bf`。任务/项目业务、F75和iOS A6继续暂停；Host/model本轮+0、累计221，P4为205另1个未知原操作封存不查询或重放。
