# 阶段71：未预建根密钥的普通 Controller 导入

功能 ID：F55、F56。状态：1 项 Android 原生组件验收有限通过；全量检查通过，进入 PR 交付。P7 未关闭。

## 功能、交互与界面边界

普通生产 Storage 与空 Session 在确认前没有由夹具人为创建目的 root KEY、草稿或 metadata。生产 CandidateStore 完成 stage，真实 Controller 经 recover、PREVIEW、一次 confirmImport、发布及真实 Session 工厂恢复，最终 DONE/IMPORTED。8 条文本和 1 条 normalized CAF、原 source/candidate 密文、通知权限重置、原未知回执只读及独立新草稿均有实际断言。

六个观察窗在构造前、空 Session 恢复后、stage 后、PREVIEW 后和 confirm 前均无目的 root KEY、根密文为零；DONE 后有 KEY、根密文两项。正常 quiesce 会先保存 metadata，可在 Activation 前创建 KEY。因此该结果不证明首次 KEY 由 publication 触发，也不证明 Activation 直接接收无 KEY 的目标。原私有提案的更强推断已纠正；existing-only reader 规则及生产代码不变。

交互验收覆盖实际 Controller 状态与 Session 交接；新 Session/Storage 身份、阅读就绪、通知 reset 和新草稿已核对。iOS 对照入口为 `EncryptedMigrationView` 与 `SessionStore.migrationPayload/restoreMigration`，本轮未改 iOS。SAF/content URI、Main 的密码/预览/取消/错误/成功窗口、方向/短视口/字体/AX、pristine 独立 cold、物理 DEV 及双端整包互通仍待逐项验收。本次组件调用没有验证这些界面。

## 原始执行与指纹

- API33/arm64；新 run `16e7a13e-9887-498d-b695-39a5f88506f9`。唯一实际 selector 为 `NativeMigrationNonemptyDeviceSessionTest#pristineNonemptyControllerImportsWithoutPrecreatedRootKey`，mode `pristine-first`。
- 原 instrumentation Host 子进程实际 wait=0，原始输出 `OK (1 test)`；设备原 PID 4507/startTicks 34443 被独立观察，随后自然退出。原生 proof SHA-256：`061911920952fabe1103286ff5946c7d13fc128b2b6bf75cf288c1da7523ff59`。
- 27 个登记 store、4 个实际 Session join、25 个外层 writer retire 返回及 2 个生产 old-root 退役、2 个 lease release 返回；Controller 的 prepare/audit/retire/cache 四个 scalar 回执顺序通过。这些不证明 OS FD/executor 终止或 Controller worker join。
- SDK profile `p7-pristine`：875 项输入、7 项显式 reviewed overlay，构建前后与 Native 源码一致。17 个原始子进程实际 wait=0、双流完整；Gradle 82 tasks，外层实际 wait=0。seal：`cef8b1c1f4b6fcff1d70ea3927c2ba2f9e499da97602d98d27ba753551f94dbf`。
- App：`39c99bfd17dadf7947ad708f8e49eea2108eb294e277fed62d6acfbfc5dc7fc7`；Test：`7ce7110d011c12ea8a4868dafe851b0a100337a9b43aa1780a1a8096bd325d18`。测试 APK 声明版本为空，未拿 PackageInfo 的回退值替代声明。
- 现有 `verify_native` 合成正例1/负例21、七 overlay review 正例1/负例4、四 Python compile 与 source guard 通过。Kotlin 额外参数拒绝门只有静态复核；合成检查不代替 Native。

安装后首次 Host 入口因输出子目录未预建，在第一条目录 lstat 即退出，早于 admission、设备调用与 instrumentation。旧 capture 的 UNKNOWN/rawComplete=false 原样保留。Root 独立核对实际 wait=1、完整 traceback、无 Native 目录/intent/worker/准确 UUID 设备目录，再以独立恢复意图预建空目录，同一尚未提交的 run 执行首次实际 Native；没有第二次 instrumentation 或重复安装。当前 boot count 6 仅为首次观测，历史基线未记录，不声称历史比较。

完整 `pnpm check` 实际 wait=0，用时708.499秒；原文42组、1518 tests、1456 pass、62 skip、0 fail。生成、格式、矩阵与差异检查通过，875项源码/7项overlay前后相同。

## 收尾与剩余项

实际导出的源 archive 为 21,479 字节，SHA-256 `c1e1cbab9ea2ef2e7b0fb7c3b9e8130f24a9ff50a3496c09e835abb355b55238`，与 proof 一致，仅供后续独立互通检查。原包和原始凭证留在私有证据目录，没有提交真实聊天、附件或机器路径。

Root 自有模拟器正常关闭，Root 原执行会话实际 wait=0；端口释放。既有 API33 AVD、此 run 的 fixture/KEY/data 保留，具体依赖为后续 pristine cold 与完整系统范围核验，24 小时内复核；旧失败记录不升级、不重放。真实模型账本仍为 221+0，P4 为 205+1 sealed unknown。

“24项”指系统范围清单的24行，包括排除及暂停项，并非24条共同 wire record。identity/foreground、notification archives、语音原件及其真实 producer、iOS 补偿失败恢复、自然故障/崩溃、完整界面和物理验收仍开放。任务/项目 CRUD、F75、A6 保持暂停。
