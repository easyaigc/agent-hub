# Source94：Main 冷入口限定验收通过

2026-11-02，阶段60；F50/F51/F54/F55/F56、UI11。已由[PR108](https://git.51easyai.com/BCAI/agent-hub-app/pulls/108)合入 main `8ff12d7d21b28c86a131984e415f99d80ec576ce`，CI11104的workspace26844/ios26845成功；阶段60本地及origin分支已清理。合并父提交为`7b779d5d`与`e9257cb253b62fbfb95e62a371b00853b1563edc`，合并tree与后者相同。完整 P7/P9 仍为部分完成。iOS 源码不改；本档只登记 API33 arm64 的合成加密既有回执图及 instrumented FIRST Main，不是完整产品或真机 DEV 通过。

## 功能、交互与界面对照

| 项目           | iOS 源契约与本轮 Android 断言                                                                                                                                                 | 本轮结果与边界                                                                                                                                 |
| -------------- | ----------------------------------------------------------------------------------------------------------------------------------------------------------------------------- | ---------------------------------------------------------------------------------------------------------------------------------------------- |
| 准确目标与回执 | iOS `TaskNotifications.route` 核对通知目标；`SessionStore.openNotification` 刷新后核对客户端/选择代次。Android 实际 Main 使用原 pending nonce、完整目标和原 ACK，不走默认选择 | 三个指定 Native 各一项通过；pending 仅 ACK 一次，terminal/prefs/alias、unknownRecovery 与 identity 密文保持。仅合成既有回执图，不代表真实 Host |
| 迟到与草稿     | 对照先核目标、保留新选择/草稿、不隐式批准或重发的共同断言；原 duplicate/terminal 经实际 onNewIntent                                                                           | 后续聊天与新草稿保留，终态只显示不可用；未知操作没有重放                                                                                       |
| UI11           | 原草稿/等待 owner、切换后新草稿及不可用提示、重试/好、工具栏和输入区应可读                                                                                                    | 两张 1080×2400 原图由 Root 分别真实查看；仅这些可见状态，完整方向/字体/AX/语言/主题矩阵未关闭                                                  |
| 生命周期与存储 | Android 测试仅在全部业务断言和两图之后，用公开 setIntent 副本修正 Scenario 跟踪；生产 Main 行为不改                                                                           | 同一个实际 Activity 的 STOP→DESTROY、五计数及通知密文原件 hash 不变，通过后才写 complete；未伪造生命周期事件                                   |
| 平台系统差异   | Android instrumentation 与 iOS 源契约分别记证                                                                                                                                 | 不把 instrumentation 首次 Main 当 OS 零进程创建或 SystemUI 通知实际点击；本轮无 iOS 真机验收                                                   |

对照源为 `apps/ios/Sources/TaskNotifications.swift`、`apps/ios/Sources/SessionStore.swift`；不是本轮 iOS 运行证明。F50 未读汇总、F51 完整权限/声音偏好、F55 完整迁移和 F56 完整视觉/生命周期仍须各自验收。

## 源码、SDK 与安装

采用基线 `8f9f9094f9622bcbb8719298377a1c38bdb0df23`；四源采用摘要 `8b3d2b0e5431a6ee983f841e823ba5c92a44ad1504498bb8ef2002561d86e30b`。实际四源 SHA-256：

| Git 相对路径                                                                                          | SHA-256                                                            |
| ----------------------------------------------------------------------------------------------------- | ------------------------------------------------------------------ |
| `apps/android/src/androidTest/kotlin/com/agenthub/android/NativeChatNotificationGraphMainColdTest.kt` | `d567e1c59be12f459b96195d013cd5117684f5498a4c2b80d190a126cf98f042` |
| `apps/android/src/debug/kotlin/com/agenthub/android/NativeChatNotificationGraphColdStart.kt`          | `84a987c36d5562d5860295c64394f633ea44e61bd25f995df0de33893c72d0b6` |
| `apps/android/src/main/kotlin/com/agenthub/android/NativeChatNotificationColdStart.kt`                | `c802500c99d17c83bfa74458f70b0e6ecad9910a754a4260541e06ce8b2e217c` |
| `apps/android/src/release/kotlin/com/agenthub/android/NativeChatNotificationGraphColdStart.kt`        | `f0ff4a2234a0bc15d6b8118f1bb93cef179e6ce49f1258b3fe4ce9b20a8c5ae3` |

官方 pnpm 入口的三个 Debug assemble（App/test/examiner）与 release Kotlin compile 四任务退出0，891输入稳定；不是 release 组装/签名或 DEV。九项 APK 工具退出0，有限 DEX 检查覆盖3 Graph/7 MRU方法。20个实际 pure 切片通过；候选中25个描述未全跑，不计作25通过。

App 70,436,343B / `83f1fd78e33f985113a5a91dbec884db6d09d1b89710bf96f431586f2ab4967c` 保留完整安装 tuple、UID与数据；只原位更新一次 test，13,217,315B / `1053e07b4d4eed5ede4f2b348882a21f734b757f842e1393c2e5c834082a1596`。UID仍10174/10175；examiner不安装，无卸载/清数据。Manifest、DEX、签名及 Manifest flags 由真实 APK 后验与设备完整 APK SHA 绑定；没有新增 PackageManager 运行时 override 逐字段证明。

SDK Ready 摘要 `d6d27eedfa17573659552efcd814fc91317650ffeefe6c07284dba6ab61b389b`，binding 摘要 `f425b7697ade5449a6ad1dede5f6087f36bbccbd7e7d34149a978bc389992947`；前置 CLI/预检回执不算 SDK 或 Native/DEV 通过。

## 实际链与原图

七阶段原子调用为 upgrade56 → writer60 → writer-readback98 → reader100 → reader-readback110 → observer112 → observer-readback113，共649。Root核对各原 wait、完整 ledger/raw，全部退出0。三次指定 Native 各一项 JUnit `[1,0]` / `OK (1 test)` / final `-1`；两次未进入对应主体的 preflight/错CLI不计入主体通过，原回执保留。

同一新 lease `64e529aa-c845-44d2-b16a-b6792b49653a`；图摘要 `b0c4f6969e3b91a6e7cfb35dc43c850e634856fc038a2150cae3836ed6d07617`。writer PID12042/ticks1965083、reader PID12669/ticks2008036、observer PID13338互异；前一 App UID 进程自然为空，未执行 stop。observer 的 startTicks 未采集，不补造。

| 原图        |  字节数 | SHA-256                                                            |
| ----------- | ------: | ------------------------------------------------------------------ |
| first-main  |  82,730 | `8839d1d30f68c94d110097e73e4d711f86c0adbdf2df8bb22670373a2fecfac5` |
| later-draft | 105,131 | `f1f75b91676a6a4398e37b45512c108a8a0923f741ebfc8fec844c2b654672b6` |

## 归档、失败与后续

原6 raw与8 namespace成员归档前后 bytes/nines一致，准确新增 `release.json`；active slot不存在，archive与namespace存在。最终读回摘要 `e4ab33c381d855ec8b7ebdbb6fc998aa90941b7f9d165d4f46992ae5da801fd8`。Root最终限定验收记录 `source94-root-final-main-graph-review.json` 为1461B，摘要 `6db0ee2272b64d2aa9440165a77d71ac743d210d96a85524992bc0fdc0dedeaf`。

[Source91原reader失败](source91-main-cold-entry.md)始终失败；Source93仅单次同parent改名保存其原6 raw、8 namespace、key及两图，没有重放旧lease/操作或补写complete，不冒充成功observer。Root已查看的新Source94原图和结果不能升级旧失败。

仍待：OS零进程创建、SystemUI实际通知点击、真实Host/模型路径、真实I/O崩溃/断电、完整24域/激活/恢复、完整UI/API37/OEM/physical DEV及完整P7/P9。继续P7语音原件集成与24域/激活/真实故障；P8完整target/replacement方法可并行。任务/项目、F75与iOS A6维持暂停。新增Host/模型输入0，累计221/P4为205，另1个未知原操作继续封存不查不重放。

资源收尾：Root核对无openFD、nlink1及SHA后回收5个自产替代/重复APK，释放156,131,328 allocated bytes；当前E94三包与E91旧test1977唯一回退保留，原Scene/raw/namespace/key不动。清理回执摘要 `0b78df5967fddfde73bcf9bfdccddd480fb97216eddf2b74a8820a5e70d1d124`；旧APK只保留历史摘要，不列作当前可执行包。

下一阶段语音原件仍是私有候选；仅macOS边界CLI/真实filesystem有界通过，iOS SDK、SessionStore集成及双端DEV未验，不标为Voice迁移完成。
