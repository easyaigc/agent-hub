# 阶段63：iOS Payload 九项原生组件用例

2026-11-02，F55/F56/F64；基线 `e2c3c3cc45c60782b3195b4eda1cb67aba31a7d7`。真实 production Payload/types 的9项原生组件用例限定通过，P7仍为部分完成。范围是平台既有容器，不新增整包互通任务或跨平台格式承诺；Android对应矩阵单列。

## 实现与功能／交互／界面范围

仅新增独立 Native test target、测试源与共享scheme；生产 Swift、App 61个Sources成员与设置保持。新target包含60份production源＋1份test，排除@main，无App依赖、TEST_HOST或生产entitlement，不引入Gradle测试工程；用真实Payload/types，不用fake替代实现。

版本、闭合字段、AAD和原件认证通过；惰性导入/再导出及不可变outbox在组件层核对。**第9项只验证原件archive边界已publish后失败时保留证据，不是actual SessionStore.restoreMigration或业务rollback。** 未实例化SessionStore/controller/transport，无Main UI、Host或模型任务。L10n负例读取runner自有语言UserDefaults，不能写成零defaults读取；生产default family/keychain/通知store未访问。组件通过不算迁入界面、失败返回、新草稿或真实用户交互通过，UI06/UI12/UI21与双端实施/验收状态保持。

## 本次SDK、Native与原失败

正式 `pnpm build:ios` 原PID12071，wait0／12.391s；Native xcodebuild原PID12265，wait0／34.610s、TEST SUCCEEDED。编译SDK27.1；实际专用iPhone17Pro Simulator为**runtime27.0／build24A434／arm64**，不混记为runtime27.1或物理iPhone。

实际xcresult为9 passed／0 failed／0 skipped，Root将9个节点与源码func集合逐项匹配：

1. `testV1EmptyPayloadRoundTripPreservesUnknownIntent`：v1空原件与原未知outbox。
2. `testV2NonemptyPayloadRoundTripAndSourceWitnessIsNotWireData`：非空v2与witness不入wire。
3. `testPayloadVersionAndClosedFieldBoundaries`：Payload版本及闭合字段。
4. `testPayloadDuplicateAndEscapedEquivalentFieldsAreRejected`：重复及转义等价字段。
5. `testEnvelopeClosedFieldsVersionsAndAEADAreRejected`：Envelope版本/salt/password/cipher/AAD拒绝边界。
6. `testOriginalTamperIsRejectedInsideAuthenticatedPayload`：已认证Payload内原件篡改。
7. `testChangedLiveSourceCannotBeExportedAsCapturedOriginal`：活source witness变化拒绝。
8. `testNativeOriginalImportIsInertLazyAndReexportable`：惰性import/lazy re-export与immutable outbox。
9. `testPublishedOriginalsSurviveReportedFailureAtOriginalBoundary`：原件发布后失败证据保留。

初次Native在x86链接时发现SwiftPM FilePreviewKit.o／Markdown.o仅arm64，退出65／0tests；package refs当时已链接。Root只为新test target的Debug/Release设ONLY_ACTIVE_ARCH=YES，对准实际arm64 destination，未改断言或生产设置。原 `stage63-ios-payload-native-execution/complete.json`（4459B）SHA-256 `e6df9eedfa405b243b7e9c6565b4b8e00fd5589bb0dd11d789c6834c4d220eae` 保持失败。更早official SDK PID152、wait0仍仅辅助，不升级为Native通过。

成功原件 `stage63-ios-sdk-native-r2-execution/complete.json`（6929B）SHA-256 `434efbea7935cdc3a7133155ee09e41721566399ea8c9de0f4d7ed427361f769`。Root核完整SDK/Native四流与chunks、实际9节点；总复核 `stage63-root-native-nine-review.json`（1777B）SHA-256 `6707cde9dba6cfecffc8e4fcdbc9667d5ead2f8b6bea95c03f96440d32ffc7ad`。

## 源码与framework指纹

当前1180份body、SDK前/SDK后/Native后三快照精确一致，SHA-256 `b3c12b6e3d00958d4d9f150a3eb217e5492c7a6c6f5a8c0b7c964477381cc1b4`。旧69-input源pin包含旧project8bd，仅为前置输入，不当当前1180证明。R2独审无新P0/P1/P2，原UsageViews缺23位ID的P1已补；Root为两个new-file diff补mode100644，文件bytes保持已审版本。

接入记录 `stage63-root-source-adoption-r3.json`（2152B）SHA-256 `150f2e02813c552ebda51168591439471fe880557729bddf07524feff923caab`；实际三文件为：

- `apps/ios/CodexManage.xcodeproj/project.pbxproj`，63850B，SHA-256 `07ba009cfcfa1bdc5edaf969f96dbb8fa49c48b4de6911b93423a805750aca53`。
- `apps/ios/Tests/Stage61VoiceNativeTests.swift`，15248B，SHA-256 `b15d66287701069748ae1ef88366dc8be800567a1ecd0e46fc1dc81bc4c0e6b0`。
- `apps/ios/CodexManage.xcodeproj/xcshareddata/xcschemes/Stage61VoiceNativeTests.xcscheme`，1211B，SHA-256 `1d0148f6eaac62c0ee5c19268389bf388f0412bb6973a44f360f39078a52d44c`。

producer/private framework各7 entries，binary51255904B、SHA-256 `cdc5dbd8c0cabe73c75874e574c6c1ec6a7b0453354747de88c991c59e0bcdb6`。实际Mach-O为IOSSIMULATOR7／min15／SDK27.1，Info.plist仍写iPhoneOS的差异保留。Gradle为UP-TO-DATE，本次App LD使用当前producer；证据限于本次实际链接与完整匹配，不宣称archive本次重建或旧SDK binary的源码匹配复用。独立复核 `stage63-root-official-sdk-framework-review.json`（1153B）SHA-256 `b8958e8516fdfd71f0725ea3573d6e1e34136233280f9df15bdbe5a80b7f2e21`。

## 收尾、交付与后续

唯一owned Simulator在9pass及throwing teardown后正常shutdown/delete，Root再读simctl registry确认同UUID不存在，无stage61-voice-native-*残留；初次0tests失败xcresult保留。cleanup没有该目录下独立child raw，资源结论来自executor报告与Root实际registry独核，不伪造逐步journal。

临时runner已清；DerivedData/private framework因下一阶段actualSessionStore两case的明确依赖暂留，登记18:00+08复核，依赖结束再释放。Root即时清249335808B可重建cache和84693013B重复Android APK，保留当前frozen三包/fallback及必要证明；回执 `stage63-r2-space-cleanup-receipt.json`（2369B）SHA-256 `2e2ad618610071d3743c31f7b4c1562acf858ef935df07e3fa05a187996a70a2`。无disk wipe、physical或A6操作。

上一阶段[PR111](https://git.51easyai.com/BCAI/agent-hub-app/pulls/111)已合入main `c6ab99b7aa5ddf090ed3decc8c1bd6b40adbdc6c`，CI11119 workspace26868／ios26869成功，阶段62本地/origin tracking/远端ref清理已核。本阶段正式生成、限定11文档格式化、`check:mobile-parity` 与 `git diff --check` 已各实际 wait0（PID20948／21016／21050／21151），Root核四份完整merged raw、三源前后不变及14文件范围。复核原件 `stage63-root-focused-checks-review.json` 为2923B、SHA-256 `23ca6b074879111978e02ae2a2a93aba8a1e83a8232ccaa4ff79b1c68fc0e1c2`。本阶段仅Native测试/项目/文档，未重跑JS全仓检查；parent e2c3的694s全仓及CI11119成功保持各自范围。提交继续独立核对。

下一优先actualSessionStore成功/真实I/O故障；当前只有树外未接入、未测试提案。静态发现新legacy写入后state失败未回滚legacy，待修，不记已修/已验证。actual restore/rollback、24域、激活/崩溃、完整UI/物理DEV、旧reader实机与Android对应矩阵均待验，九组件case不关闭完整P7。任务/项目、F75与iOS A6仍暂停；Host/模型新增0累计221，P4为205＋1未知封存，不查询或重放。
