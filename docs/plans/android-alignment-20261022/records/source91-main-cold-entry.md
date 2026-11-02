# Source91：实际 Main 冷入口与生命周期收尾

状态：构建、APK、原位更新及 writer 单项限定通过；reader JUnit 失败，保持未通过。完整 P7、实际 OS 冷启动和物理 DEV 仍开放。

## 本次已核实

- Source91 四源采用摘要 `c3d278e440828057c7a73781c814ec3d0e73506a83bed3b2d88f76af64264f9c`；系统父目录按生产存储边界接受已有 0700/0771，自己的夹具目录/记录保持 0700/0600。父目录权限没有修改。
- Source91 SDK 启动器的字段适配遗漏在实际构建前由独立审查发现。Source92 驱动同步新 `sourceFiles`、实际 Source88 保护资料与双启动门禁；27 项 synthetic 通过，含新结构连续两次门禁与禁止启动 SDK 的拒绝场景。原 Source91 只读观察保留，不算构建失败或 SDK 通过。
- Source92 原构建 `ce333bfb-9478-419a-bc69-bb2912e84d25`：worker、构建槽、pnpm 实际退出均为 0；三个 Debug assemble 和 release Kotlin compile 完成，后者为匹配输入下的 UP-TO-DATE。未组装/签名 release。
- 实际 App：70,436,343 字节，SHA-256 `83f1fd78e33f985113a5a91dbec884db6d09d1b89710bf96f431586f2ab4967c`；test：13,211,992 字节，SHA-256 `1977f382e0ee62a5f4c480606fb5cc306ea913c503be14608ecb281e70672bcb`。九个 APK 后验工具退出均为 0；有限 DEX 检查检出三个 Graph 和七个 MRU 方法，非全字节码证明。
- 原位更新 `312c21b9-03ac-4851-8eb8-d7955e2f35e1` 只安装变化的 App，未变 test 保留并核对原完整 tuple；56 个子进程调用退出均为 0。UID、数据和 Source86 三张原图保持，未卸载、清数据或安装 examiner。
- writer `a9b46f7e-c521-431d-b2aa-398c70fa3990`：实际 JUnit 一项通过，60 个子调用退出均为 0。独立只读核对的 98 个子调用均完成，原 intent、armed、nonce、操作、成员及原 PID/startTicks 连续；读取时 App UID 无进程，未使用强制停止。不是 OS 零进程证明。

## 原 reader 失败与原图

reader `2206a6ca-073a-4ee8-babc-3c3bc80b47d4` 的 instrumentation 子进程退出为 0，JUnit 一项实际失败（结束状态 -2）；外层 worker 退出 1。73 个子调用完整原始输出已核对。

原始异常为 `ActivityScenario.close()` 等待 DESTROYED 超时，最后跟踪状态为 PAUSED。不能将 instrumentation shell 退出 0 当成测试通过。完成回执位于 close 之后，本次没有 `complete.json`；原失败不重放、不补写成功。

后续只读 reconcile 的 110 个子调用均退出 0，保留 `noNextPhaseAdmissionPossibleFromThisResult=true`。原图只读取回的 112 个子调用均退出 0，Root 分别查看两张 1080×2400 原图：首次 Main 的原草稿可见；后续草稿在终态通知“无法打开”提示下仍保留。有限可见状态不关闭 JUnit、完整 UI 或生命周期矩阵。

| 原图        |  字节数 | SHA-256                                                            |
| ----------- | ------: | ------------------------------------------------------------------ |
| first-main  |  79,796 | `c2a3653fd17efa793d1b8a3bc2f0a4c07b27812a0a52576f73a0c6e69c725506` |
| later-draft | 102,495 | `aae27cbabfc264850d2311e3ad95288c39551c9fa19dfb2b6b111c6b1b9e4960` |

锁定 AndroidX core 1.7.0 的静态字节码核对表明，Scenario 按原启动 Intent 的 action/data/type 筛选生命周期；生产 Main 正确更新新通知 Intent 后，data 不同的后续事件被旧 Scenario 忽略。当时正在仅修测试收尾：保留原业务断言、两图及生产行为，使用公开 Intent API 还原 Scenario 跟踪身份，并验证真实 STOP/DESTROY 与原通知不重写后再完成。该修复在本档记录时尚未重新构建或验收；后续全新夹具的限定结果见[Source94](source94-main-cold-entry-accepted.md)，不改变本次原reader失败。

本次失败图、namespace、密钥和原回执全部保留；失败图的保存与下一次全新夹具验收分别执行。新增 Host/模型输入 0；任务/项目管理、物理 iOS A6、API26/37及真实系统通知点击等待验保持。
