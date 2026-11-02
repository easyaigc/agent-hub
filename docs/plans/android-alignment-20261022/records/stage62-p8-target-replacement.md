# 阶段62：P8 target/replacement 与退役夹具收尾

2026-11-02，F02/F03/F04、UI01/UI03；基线 `90ae9073af148571d4bb75e10b1ecdb858cae84e`。本轮完整方法的两个 case 与两组退役合成 fixture 的资源收尾限定通过，P8 七组仍未封存。iOS、Android 生产页面及业务协议不改；P7 仍为部分完成，不把树外未应用的 iOS Native 提案计入本阶段。

## 功能、交互、界面与实际范围

- **功能，F02/F03/F04**：切换 target 或替换 saved connection 后，旧 queued read 不选择旧行、不更新旧 cache，当前 draft/target/replacement client 与各 case 三份 protected 密文保持。只证明该回调隔离，不关闭全部凭据 family、缓存迁移或 shared default family 独核。
- **交互与界面，UI01/UI03**：实际 ComponentActivity isolated Compose／typed fake ports；共同断言是准确目标、当前选择与草稿不被旧回调覆盖。未验 Main 菜单、正式页面、完整编辑流程、方向/字体/AX、真实 issuer/网络/模型/桌面或物理 DEV，iOS不新增通过。
- **资源**：该 retired synthetic pair 先完整只读取证，再独立 Native 精确清理；不查询或触碰 Source79/80/91 等其他数据/key。所有 implementation/verification/shared 状态保持，清理不表示业务成功。

## 完整原生方法：一个 JUnit、两个 case

`originalQueuedReadCannotSelectAfterTargetSwitchOrSavedConnectionReplacement` 以 fresh run `cfa02692-bc4f-4356-8290-2b1f44fdae9f`、operation `2ef76759-a613-4071-a40f-7cd7d414262c` 执行。实际 API33／ComponentActivity isolated Compose、typed fake ports；完整 JUnit **1项**，status `[1,0]`、`OK (1 test)`、final `-1`。stale-target 与 stale-replacement 是该方法内部两 case，不能登记成两个 JUnit 方法。

两 case 均保当前 draft/target/replacement client，拒迟到 read 的旧选择和 cache 写回；各三份 protected 密文核对不变，App UID 前后自然无进程。使用 Source94 App `83f1…`／runner `1053…`，被测 saved test 源 `61d63d…` 与后续 compiler892 中原891份输入精确匹配。

Root 已核42个原 wait 与84份完整 raw；私有原件 `p8-root-fresh-target-replacement-review.json` 1558B，SHA-256 `0cf23044ba660e3fabea2f8d8decffe477dd9fc06eb0daf6fbd639226e4b5811`。原 AM 参数错误仍为未进入该方法，不改为通过。此方法不证明 Main 菜单、issuer、真实网络/模型/桌面、physical DEV、全A5或 default family 独核。

## 先保原件，再独立资源清理

两组已退役 synthetic fixture 各9 entry（3目录/6文件），7468B＋6622B＝14090B。独立只读 capture 的两轮 stat（UID/mode/devino/nlink/size/mtime/ctime）与全部 bytes/H 相同，六份 protected 密文匹配，App UID 自然空；94 wait0／188完整 raw 已由 Root 读取。旧 fixture 的 `cleanupPassed` 只表示 joined/retired，**不表示已删除**。

私有原件 `p8-root-passed-pair-readonly-capture-review.json` 1250B，SHA-256 `16de1e33fb04fdeecc2e1a51615ac93ec7478ebc3a356b4a7df7bc010b9bba0e`。采集原件保留；不以之后的清理覆写原证明。

新 collector 首个 Native operation `45274a37-38ab-49b5-8197-9701dfd8446c` 在源码65/211行的 closed-canonical gate 失败，停在 fixture preflight／Keystore／mutation intent 之前，三 receipt 未创建。24 wait0／48 raw 的工具退出不能改写原 JUnit 失败。Root 核对 Android13 JSONStringer 对 slash 的转义后，仅修正 host 内外层 JSON canonical 编码；源码与 expected 不降级，不重装已明确安装的 `c00e…` runner。

新 operation `d57fcfbe-b76c-4a77-98c0-aaefec7554b6` 的 `collectRetiredSavedSelectionFixturePair` 实际 JUnit **1项**通过：`[1,0]`／`OK (1 test)`／final `-1`；API33 SDS provider 本次实际支持。两组先全 byte/meta 预检，O_EXCL intent 后精确执行2 alias／12文件／6目录＝20 actions，产生41 journal events。Root 按 expected 顺序核全41 wait0／82 raw 与三 receipt；两目录不存在且非链接，两 key 由 Native contains=false 核对，App UID 之后自然空，892份当前输入保持。

私有原件 `stage62-root-native-pair-cleanup-review.json` 876B，SHA-256 `6fc4d781225ed109fcc4593ac26650dd67e43180a3d9c11b339e8584e1594115`。只清该 retired pair，原采集、mutation intent、receipt 与失败材料保留；不重放原业务、未知输入或旧封存操作，也不据此关闭 shared default family。

## SDK、APK 与实际源码

早期 collector 的 O_DIRECTORY 在公开 SDK 不可用；Root 仅替换为公开 O_NONBLOCK，fstat／S_ISDIR 边界不变。最终 R2 正式四任务 SDK 原 wait0，892份源码前后稳定；9个只读 APK tool 原 wait0、18 raw 与新 class/method 的实际 DEX 已核。同证书沿用（fc12…），本阶段没有生产业务源码变更。

最终 App `83f1…`（70436343B）、test `c00e…`（13239072B）、examiner `f6c8…`（1017598B）由完整APK SHA绑定。私有 `stage62-root-sdk-apk-review.json` 1578B，SHA-256 `cf1e10747073074290218eafeadcf6de5f4e41e5f70ebd6b4ca12f06b214a2e9`，含实际SDKReady与892源前后指纹；该 SDK review 中 provider 当时未验，最终有限结论来自后续 Native 回执，不改原 review。APK检查不单独算 Native 或页面验收。

| Git 相对源码                                                                                             | 字节数 | SHA-256                                                            |
| -------------------------------------------------------------------------------------------------------- | ------ | ------------------------------------------------------------------ |
| `apps/android/src/androidTest/kotlin/com/agenthub/android/NativeEasyWorkSavedSelectionTest.kt`           | 21292  | `61d63d0a5ff963c15671a7a37e8579c760206ab504f4bae873c103aa0e32182e` |
| `apps/android/src/androidTest/kotlin/com/agenthub/android/NativeRetiredSavedSelectionFixturePairTest.kt` | 20337  | `15db439e238811f329e168369a40c259535245ca02a206056fcd1f347f9f5348` |

## 交付与下一步

阶段61 [PR109](https://git.51easyai.com/BCAI/agent-hub-app/pulls/109) 已合入 main `0d966ddb8b389a4e1093b515fcac8ac1af07a410`，CI11111 workspace26855／ios26856成功，本地及origin阶段61分支已清。Root合并收尾原件379B，SHA-256 `039647586386178de14459f5c4797965dec30f7b248556a2750e41aaaca68722`。本阶段正式生成、限定文件格式化、`check:mobile-parity`、`git diff --check` 已各实际 wait0（PID57112／57209／57252／57351），10项相关检查测试通过。Root 核四条完整 merged raw；复核原件 `stage62-root-focused-checks-review.json` 为677B、SHA-256 `e9d874ca4780cd7151c9797c7942e8c64cbc611086ee9ff168360983186829bb`。本阶段没有 JS/TypeScript/工具实现变化，基线90ae的全仓CI11111成功结果保持原范围；随后主线整合及交付见本档后续记录。

继续 P8 恢复采集退出、Main菜单、真实issuer/网络/模型/桌面/物理、双通道信任、shared default family独核与完整界面/七组封存；P7原生Payload/SessionStore、24域/激活/真实故障及完整UI仍开放。任务与项目、F75、iOS A6继续暂停。Host/模型本轮新增0、累计221；P4为205，另1个未知原操作封存，不查询或重放。

主线同步：提交 `e176294e14820e0a2ed066a159605a5f5d1e40ea` 推送后，main 经 PR110 前进至 `f149a1dfbb66bcb0ecd82974214c4e61c8845fd4`，新增 F69 的 cancelled-composer 合成边界测试。Root 只合并清单中的独立 F69 与本阶段 F02/F03/F04 关联、保留双方状态与源码，不改其已合入测试。该整合版本已正式执行一次全仓 `pnpm check`（PID62978，694.311s，实际 wait0），各 suite 汇总1518项、1456通过、62跳过、0失败；Windows原生等跳过项不算通过。生成、四文件格式化、两项差异检查均 wait0，Root 已核五条完整 merged raw/分块。复核原件 `stage62-root-main-integration-checks-review.json` 为2830B、SHA-256 `46dddf5f0faf57193b4484c4cf415b56c46b7643c6291152c1e8a51860d20c2f`；原有限验收结果不扩大。

阶段62交付：[PR111](https://git.51easyai.com/BCAI/agent-hub-app/pulls/111)已合入 main `c6ab99b7aa5ddf090ed3decc8c1bd6b40adbdc6c`，CI11119 workspace26868／ios26869成功，本地分支、origin tracking与远端ref已清理。Root复核原件 `stage62-root-merge-and-cleanup-review.json` 412B，SHA-256 `52620d5bb27a75e7986a20c887206b85ab8e732c6554a78c8d7cc64d104df767`；原限定范围与失败状态保持。
