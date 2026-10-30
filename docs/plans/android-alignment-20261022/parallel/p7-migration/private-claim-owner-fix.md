# Private-claim Native 夹具的实际 owner 保留修正

本修正基于 `8ce8de85`，回应 Root 的 Source65 retirement addendum。原 `owned` 在 `retireForMigration` 抛错或未确认时仍无条件 `releaseRawWriters`，会解冻真实 raw/child gate；Session 和 lease 又在跨 `withContext`/`withTimeout` 返回后才由外层持有，存在取消丢失交接的窗口。此前 JVM11/PSI9 没有验证这些退出路径，不能用于 Native 准入。

本轮只改五个 Android 测试源文件及专属档案：完整 fixture、两个 Native 类的 owner rule、可移植的测试专用协调器/rule 和假资源用例。生产 codec、四文件图补丁及其前置字节不变，不写 Root、中央表或设备。Root 阶段31 `716c5ed7` 的 reading/commonIO 限定通过仍单列，不升级为新 claim 图通过。

## 实际对象与状态

`PrivateClaimFixtureRecord` 在调度前登记原 storage，并强持有实际 Session、lease、协调 Job、JUnit 线程和唯一测试体线程。Session 在 Main 的构造调用正常返回后立即登记，再调用 restore；lease 在同一个 Main block 内、`quiesceForMigration` 返回后立即登记，再跨取消边界。创建/restore/acquire 都位于覆盖全过程的外层 `try/finally` 中；清理在该 `finally` 中执行。

静态强引用登记按真实 canonical coordination directory 排除第二个 owner。只写诊断 JSON/文本不构成保留；取消 Job、阶段值和 Future 状态也不是退出证据。未确认时保留原对象及其真实字段/锁，不构造替代 Session 或 lease，不删除原件、候选或 key，不重试原动作。

退役只尝试一次。`retirementConfirmed` 仅在实际 `retireForMigration` 正常返回后、仍处于 Main block 时写入；其后才允许 IO 中一次 `releaseRawWriters`。`releaseConfirmed` 也只在实际释放正常返回后写入。退役失败不释放；释放失败按部分/未知保留，不重试、不宣称已释放。获取已经进入但没有返回可登记 lease，也按未知保留 Session 内的实际 owner，不擅自改走 abandon。

仅当获取从未进入时，允许实际 `abandonMigrationRestore` 正常返回确认无 lease 的 Session 停止。创建从未进入可以确认没有新 Session；构造已进入但未返回对象不能这样声明。明确状态、正常返回门槛与 Native 预期见 [生命周期契约](private-claim-owner-fix-contract.json)。

## 真实 JUnit 上下文不会提前结束

两个 Native 测试类都安装 `NativePrivateClaimFixtureOwnerRule`，九个 selector 名称保持。原 JUnit 线程监督一个准确的测试体线程，对其完成、实际线程退出及全部 owner 清理事实无超时等待；没有设备/网络轮询。第一次边界中断关闭新准入，只向这个测试体请求一次本地取消，不能被解释为取消远端操作。后续中断只记录并忽略，不重复退役或释放。

协调与不可取消清理在 Default 上推进，Main 执行生命周期调用，IO 执行释放。因此即使 `runBlocking` 的返回丢失，实际清理也不依赖已经阻塞的原事件环。若挂起的实际清理之后正常确认，等待才可结束；JUnit 保留正常成功或原始失败语义。若退役/释放失败且没有确认，实际 Session/storage/lease 与 instrumentation/JUnit 上下文继续存活，不通过“报错返回”丢掉锁。

首个已观察到的 body/清理错误保持原 Throwable；后续校验、退役、释放、诊断和中断附加为 suppressed，保留栈。若边界中断先发生，它优先于之后的取消错误；已有原断言错误仍优先。`owner-<sequence>.txt` 是私有夹具目录中的补充状态/栈信号，写失败不覆盖首错，更不能用它替代实际对象。

Root 的 Native harness 不得把该保留路径当作普通超时并自动 force-stop、重启进程或重跑 selector。失败后须保留准确原进程/上下文并由 Root 明确处理；代码没有对未确认 owner 的强制清除、重复 close 或制造终态入口。操作系统强杀不受此代码保证，也不能被报告为正常释放。

## 差异、证据和未验证范围

[最小差异](private-claim-owner-fix.patch) 仅修改原三个夹具文件，基于 `8ce8de85`，在任务临时目录应用后逐字节读回一致。两个新增测试源须一并加入；不要把旧完整共享文件覆盖 Root。[Source-map](private-claim-owner-fix-source-map.json) 给出原/新摘要、审查报告指纹、生产不变项与实际结果。本轮不改总表、claim 协议、未知操作、账号策略或数据格式。

有限检查中，实际可移植协调器/rule 与假资源 **14 项 JVM 用例通过**，五个 Kotlin 文件完成 PSI 语法解析，输入无漂移。覆盖正常退出、Main 创建/获取返回时取消、未返回 lease、失败退役零释放、失败释放一次、首错/附加栈、同存储拒绝替代、两次中断后保持 JUnit 存活至实际确认，以及尚无 Session 时的中断排序。首次 12 项中有一项依赖 `runBlocking` 中断传递时机的假设失败，原记录保留；改用明确的线程监督后13项通过，再补首错排序边界后14项通过。

执行方显式 Terra/medium，由主会话核对实际 journal；入口无速度参数，不宣称极速。辅助用例没有真实 Android Session、Keystore、lease、raw gate 或 Native 操作；失败假资源的观察等待仅在 JVM 测试中替换，Native 默认始终是无超时保留，外层 JUnit guard 也不会因观察异常而放行未确认 owner。

Root 独立语义复核、Android 类型绑定/组合 SDK、实际锁及九个 Native selector、OS/真实 DEV 均待执行。没有分配运行 UUID，也未操作 ADB、Host、GUI、安装、真实网络或模型输入。任务/项目、未知业务与 iOS A6 保持原暂停/不重放边界，完整 P7 尚未完成。
