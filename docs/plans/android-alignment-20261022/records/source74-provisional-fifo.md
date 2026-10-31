# Source74：原录音迁移的特殊文件拒绝

2026-11-01，阶段49。Android 原录音清单中的 FIFO 可以通过旧 size 检查，旧只读 open 在 fstat 类型拒绝前会等待配对端。现增加 O_NONBLOCK，保留 regular/owner/单 link/身份/大小/摘要及发布门禁；拒绝特殊文件时保留原件和既有输出，不补录、不恢复或重放业务。

## 本次限定结果

Source74r4 API33 独立验收包的 `indexedFifoOriginalRejectsWithoutPeerAndClosesReader` 本次限定通过。实际同步导出在 56.015 ms 返回，异常确在生产 copy 的非 regular 检查；期间没有 FIFO 配对端。原 row/index、源密文、目录身份和输出 sentinel 保持，未发布新输出。

只有真实调用返回并完成原件核对后，才对本用例自己的准确 FIFO 做两次 write-only/nonblocking/no-follow open 尝试；均返回 ENXIO，未创建 writer handle，证明该读取端未保留。中间实际重新取得并释放 raw owner，全部门禁通过后才删除自己的 FIFO。此证明不等于全进程 FD 关闭、物理 executor 退出或完整生命周期通过。

- App：`e7879d0687ab179317a1b48269551f45021710026f59d86704e41b3a5ffe94f1`。
- Test：`c664d5254c22c9e5cf4bb7fc1609b12c8d06e4bdc05063ef20331be9e8a5daf1`。
- canonical 854：`3c94cd3d819e7272b80bfbad31a330d1dcb4770896728dee08ffdd004edea53f`。
- expanded 867：`e0976849ccd4b9a047dc4b82aa88bd49c40c245ba46218d2a8083dc668ab7789`。
- 主统筹 SDK 核对：`86c9a9dd23d0d060567c1d3d23d6df7fbf2f82c5f36e523860a3012c2778d4aa`。
- 安装／Native 主统筹核对：`e878721c2e665d5b29770e3a77d22ba95b17a1a0071a79fa0f622b69f849116a`／`698ee701be46888f26d3f377cdcd5c4bc259e540e565b5c770b7f6f4077a18f2`。

安装 76 次设备＋8 次本机、Native 75＋8，共 167 份捕获，由主统筹独立读原始流、全部清单／九 stat 和普通文件字节。30 个保护根保持：原 Cold/reading/P8/P9 28 根，加两个旧失败夹具。目录及旧 FIFO 仅保存元数据，tar 精确只含普通文件，未把 FIFO 算作已读字节。旧原件、源密文和权限保持，未卸载或清数据。

## 保留的失败与限制

r1 测试新增行使用 SDK 不提供的 Os.unlink，编译失败；改 Files.delete 后 r2 SDK 通过。r2 与 r3 的全进程 FD 观察分别在 stat/fstat 遭 EACCES，均在 export 前失败；原断言及失败保留，单列待验。两条旧 FIFO 仅只读核对准确元数据，未读取、供配对端、删除或重放。r4 使用另一个具名限定方法，未将旧方法改记通过。

r4 实际 Gradle 原回执 exit0／4.839秒、完整 9311 字节输出及空 stderr 已读回；外层记录器在 wait 返回后因 Python 不提供 file_digest 而失败，helper 退出码没有保存。此缺口保持未知，未补造0或重跑构建；主统筹核对真实 Gradle 原回执、最终 helper 输出及全部当前源／旧21APK守卫后，另行准入冻结与离线 SDK。后五步及12项 SDK 输出完整核对通过，不将外层缺口写成通过。

## 双端与后续

本轮只修 Android 文件 I/O，不改页面、token或文案。共同断言仍为失败保留原件／原稿、未知不重放；iOS 本轮无源码修改或新增验收。完整 UI、API26/37、真实48MiB、物理DEV与24组整体继续待验。

下一项是音频 binary 已提交但 manifest row 尚未提交的精确窗口：当前仍明确拒绝并保留，v2惰性目录仅有树外方案，未实现或验收。iOS 当前迁移 Payload 不含 PendingVoiceStore 音频／metadata，不能当完整语音迁移已通过；F47/F55/F56/F64、UI21 后续分别登记。任务／项目、F75原生项目界面及A6暂停。

本阶段没有 Host／模型输入。独立运行环境及失败原件保留供后续精确保护；当前单一构建目录仅为下一迁移阶段增量SDK复用，已替代草稿按归属回收，不复制完整环境或删共享缓存。
