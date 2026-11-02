# Source88：Main 冷入口构建、升级与首次预检

状态：SDK、APK 后验和独立验收包原位升级通过；首次 writer 在 Native 调用前失败。完整 P7、OS 冷启动、系统通知点击及物理 DEV 均未通过。

## 本次实际完成

四个新验收源文件的 Root 采用指纹为 `458e4252b4a4da73213810632818293bbbd4f3447154678ca928100ed7846b7b`。新增 debug 图恢复夹具、release 空实现和三个单方法 Native 场景；原冷入口的旧版本路径保留。

- 原构建操作 `5c387270-93df-48e3-b17a-0346d0304582`：worker、构建槽、wrapper 和 pnpm 实际退出均为 0。三个 Debug assemble 及 release Kotlin compile 完成；release compile 为匹配输入下的 UP-TO-DATE，不代表 release 包已组装或签名。
- 九个 APK 工具子进程实际退出均为 0。完整 APK、ZIP CRC/EOF、manifest/权限、v2 签名及有限 DEX 方法检查通过，识别三个新 Graph 方法及七个已有 MRU 方法。这不是全字节码反汇编或 Native 通过。
- App：70,435,962 字节，SHA-256 `9ee9d6d8767a859ee182a48084a34d135b56de9e1edb0b4d93362d6224b2bbf5`。
- test：13,211,992 字节，SHA-256 `1977f382e0ee62a5f4c480606fb5cc306ea913c503be14608ecb281e70672bcb`。
- examiner：1,017,598 字节，SHA-256 `f6c813b5a10329ec1b97e7cbf009c71502837f794adf831b2a79cb4ec15c256a`，未安装。
- 原位升级操作 `d9848258-25ca-4dab-87c3-20271ee48398`：原 57 个子进程退出均为 0，App/test UID 保持，未卸载、清数据或降级，Source86 三张原图摘要保持。
- 原 stdout 解析器的 24 个 synthetic 边界场景通过，仅执行摘取的解析函数。结果摘要 `1cadf20982eb4b3b777e984ccd517d732df126105211e1506b61a80fa3d9acd2`；不计为 Native。

执行方的正式记录已核对为 Terra/medium。工具不提供速度参数，实际速度不可核验。部分辅助元数据输出曾被截断；Root 另外读取原始 APK 工具、升级及失败操作的完整账本和 raw，不以被截断输出作为通过证据。

## 保留的失败与继续入口

writer 原操作 `a3177c96-4131-4af9-8b37-2eda5b300a77` 实际退出 1。29 个只读子进程均已完成且退出 0；最后检查发现系统创建的 `no_backup` 为 0771，夹具要求 Root 先准备 0700。该次未调用 instrumentation，没有写入图夹具或 Keystore，不重放原操作。

Root 已独立核对原 wait、完整 raw 和命令范围，失败审阅摘要为 `3efa8754725d7a6abd4a1749fbeaa31c3e688e2efb1f9875f635654dd723dbd9`。进一步核对生产存储边界后，确认对系统父目录强制 0700 过严。Source91 改为接受已有、同 UID、非链接且规范路径的系统父目录 0700/0771，自己的夹具目录与记录仍为 0700/0600；目录 fsync 保持 NOFOLLOW 和前后身份核对。

先前考虑的父目录改权限方案未执行，系统原权限保持；该候选审查中发现的失败后还原缺口不计为设备故障。后续采用新的源码指纹、实际构建与操作目录，原失败不覆盖。

下一步依次为新 writer、原资料只读核对、新 PID 的首次 Main、两张实际原图审阅及 observer 归档。完整迁移、实际 OS 冷入口、真机/API26/API37、动态字体/TalkBack 和供应商生命周期仍在待验矩阵。

本轮新增 Host/模型输入为 0；原未知操作及忙碌真机均未触碰。任务与项目管理、物理 iOS A6 保持暂停。
