# W4：独立 Simulator 测试宿主的安全存储与签名边界

本记录关联 F37/F38/F39/F43/F44/F61 的原生合成协议回归及 Q11 环境恢复，不是新的当前状态表。当前开发、验收及真实 DEV 调度仍以[权威计划](../progress.md)为准；原队列源码与断言关联见[原线程与 ACK 记录](../../android-alignment-20261022/records/w4-original-thread-and-ack-20261112.md)。

## 本次定位与最小变化

Xcode 27.1（27A9275）、iPhoneSimulator SDK 27.1、实际运行时 iOS 27.0（24A434）下，旧 `CODE_SIGNING_ALLOWED=NO` 测试宿主可启动，但没有 `__TEXT/__entitlements`／`__ents_der`。旧真实 XCTest 在 CredentialVault 保存时失败，旧输出没有 OSStatus，不能反向补写一个错误码。

本轮独立原生 Security 探针使用随机账号及与生产相同的 SecItemUpdate/Add 属性。在同一个新空白 Simulator 中，无模拟权限段时 Update/Add/Copy/Delete 实际返回 `-34018`；SDK SecBase.h 将其定义为 errSecMissingEntitlement。相同原生代码仅将 iOS 权限写入 ad hoc 代码签名后，spawn 返回 163，宿主 AMFI 记录 `AppleMobileFileIntegrityError/-424`、restricted entitlements；这两个探针的 `__text` 摘要一致。将权限通过链接段提供、实际代码签名不带受限 iOS 权限时，Update 返回不存在的 `-25300`，Add/Copy/Delete 均为 0，随机值读回一致。普通 AMFI `-423` 日志也出现在可工作的对照中，不能单独用来判根因。以上是本次工具链的有界实证，不作为所有 Simulator／签名方式的通则。

最小源码变化为 `CodexManage-Simulator.entitlements` 的应用标识及默认 Keychain 组按 `PRODUCT_BUNDLE_IDENTIFIER` 展开，默认正式 Simulator bundle 的值保持原语义；增加 `pnpm --filter @codex-manage/ios build:native:simulator`，明确构建独立 `com.easyai.agenthub.easyworkacceptance`，并保留 Xcode 的 `CODE_SIGNING_ALLOWED=YES CODE_SIGN_IDENTITY=-` 流程。物理设备 entitlements、生产 CredentialVault、队列／回执方法与业务断言均未改。Android 代码与已有验收结果不受影响，不新增 Android、iPhone 或真实 DEV 通过。

## 真实产物与构建参数

实际命令为该 pnpm 入口展开的 `xcodebuild -project CodexManage.xcodeproj -scheme EasyWorkNative -sdk iphonesimulator -destination 'generic/platform=iOS Simulator' -derivedDataPath ../../.build/ios -jobs 1 CODE_SIGNING_ALLOWED=YES CODE_SIGN_IDENTITY=- NOTIFICATION_APP_BUNDLE_ID=com.easyai.agenthub.easyworkacceptance build-for-testing`。本次双架构完整编译／链接成功，恢复轮 500.111 秒；前一轮为恢复已有匹配依赖而正常中断，退出 75，不记编译语义失败。

实际独立宿主 arm64／x86_64 的两个模拟权限段逐字节相同：

| 段                      | 字节 | SHA256                                                             |
| ----------------------- | ---: | ------------------------------------------------------------------ |
| `__TEXT/__entitlements` |  539 | `4c1b186b54d9b129f729fb4aedf27a79377f32a90e333b4447e500fe8da4b0fc` |
| `__TEXT/__ents_der`     |  246 | `19a414a7e282155bc7daca5df174b45be583e81b8b301a6c353a743a894ebd1a` |

XML 段实际内容为独立 application-identifier、同名默认 Keychain 组及 `group.com.easyai.agenthub.easyworkacceptance.notifications`。Xcode 生成的实际代码签名权限为空；嵌套签名 deep/strict 校验通过。只读代码签名权限不足以判断是否存在上述 Simulator 段，其他构建参数或工具链必须重新检查二进制。

宿主可执行文件 SHA256 `cc03b2616ada91200abfcfed492b522d86d87a0b62881de7a73a2b08bebbc820`；测试可执行文件 SHA256 `4d10e042b137b2df9f27515199bf6c6ef9cd7422ecda3323618dea5d84b5f247`。实际安装副本与这两个构建产物分别逐字节一致；匹配 dSYM／双架构 UUID、5 个 xctestrun 依赖、原始及过滤后的计划摘要均在本轮私有证据中保存，不复制整套 DerivedData。

## 原方法执行与边界

官方 `test-without-building` 同时使用 xctestrun OnlyTestIdentifiers 与 `-only-testing:EasyWorkNativeTests/EasyWorkHostedNativeTests/testC2SyntheticEncryptedQueueAndReceipt`。原测试文件 SHA256 仍为 `9b8cca9818ad5f791038836be34999f244620c6a1da669ea5efa7df3477c808f`；没有改原方法或断言。

实际 xcresult 的方法为 `EasyWorkHostedNativeTests/testC2SyntheticEncryptedQueueAndReceipt()`：**1 实际方法、1 通过、0 失败、0 skip**，命令 15.479 秒、方法 0.944 秒。全部原断言包括正确队列 ACK、显式 false、原回执与缺失队列、错线程在 queue/status 之前拒绝、numeric/string 取消与停止 ACK 拒绝，以及入账丢 ACK 后仅查询原 ID、正文不变、唯一一次 turn/start。它仅使用 in-process 合成加密对端，不接触真实 Host／模型或原 grant。

旧首轮 1 unexpected failure 和后三轮资源／启动中断证据原样保留，未改写为此前通过。本次只补齐这个方法的原生辅助门槛；原 owner 的真实 queue/execute/cancel/steer/stop/status、真实丢 ACK、新草稿 UI 隔离与其他 native 场景仍按权威计划分别验收，A6 保持暂停。
