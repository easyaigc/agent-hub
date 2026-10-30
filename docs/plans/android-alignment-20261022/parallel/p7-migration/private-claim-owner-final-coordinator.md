# Private-claim owner 最终 coordinator 退出屏障

本轮基于 `815997a4`，只修 Root `retirement-final-review.json` 的两个 P2 窗口。上一轮实际 Main 交接、退役确认后才释放的修正继续保留；生产 codec、四文件图补丁、三个 Native fixture、九个 selector 和共享总表不变。源码差异只包含测试专用 helper 和假资源用例。

首次 JUnit 边界中断现在先检查 `outcomeDelivered=false` 的记录，清理已确认也不排除其中已经记录的原始错误；其次才考虑已观察到的测试体失败。已交付且被测试体处理的旧错误不被重新提升。

原 LAZY coordinator 在启动前登记准确 Job。正常释放只确认 owner 退出，静态记录要继续保留，直到同一 Job 真正完成；原记录仍保留时，同一 canonical resource key 不准入替代 owner。完成回调核对实际 `Job.isCompleted` 后才登记 worker 完成，不能由取消请求、阶段、等待入口信号或 JSON 推断退出。

原 JUnit guard 在 Main 之外等准确测试体 Thread 真正退出、未确认 owner 与每个准确 coordinator 完成。coordinator 内最后的同步 IO 仍由同一 Job 等待；因此即使 `runBlocking` 先因中断返回，JUnit 也不能在最后 signal 尚占用 IO 时结束。等待没有超时或强制放行，后续中断继续记录；不重试清理、重发最后 signal 或重建 Session。未知退出继续保留原进程/上下文，Native harness 不得自动 force-stop、重启或重跑 selector。

新增确定性向量由有门闩的真实 Statement 启动独立实际 caller Job/supervisorScope 来调用同一 Lifecycle；调用方最终 join 并取消其 scope。这样不依赖 `runBlocking` 的中断返回时机，明确制造测试体已退出、coordinator 仍在真实 IO 的窗口。向量用真实 CountDownLatch 阻塞实际最后 signal：此时 cleanup/release 已确认、outcome 未交付、原断言已记录。两次中断后实际测试体 Thread 退出，guard 已进入 coordinator 等待、Job 仍未完成，JUnit 与静态记录保持存活。放行原 signal 后，只有实际 Job 完成才结束 JUnit，并返回原断言。另一个向量确认已交付/处理的旧失败不压过后到中断。用例中的有限等待只是避免假资源检查挂死，不改变 Native 的无超时门槛。

[最小补丁](private-claim-owner-final-coordinator.patch) 仅含这两个文件，相对815；Root须同时保留上一轮三个 fixture delta。[Source-map](private-claim-owner-final-coordinator-source-map.json) 列明输入/输出与不变项，[契约](private-claim-owner-final-coordinator-contract.json) 列明退出门槛。本轮仅影响 Android 测试夹具，iOS产品行为不变，按 Root 范围不改中央双端总表。

辅助检查已通过：可移植 helper/rule 与假资源 JVM16、五文件 PSI 语法，输入无漂移、运行输出无未处理异常；没有 Android 类型绑定、SDK、真实 Session/Keystore/raw gate、九项 Native、OS或真实 DEV 证明。旧14项及失败证据保留，不能升级为本次通过。新向量首轮没有制造测试体退出窗口而失败；第二轮通过但假调用方出现未处理异常。两份记录都保留，最终 supervisor 隔离的调用方保持原断言并完成16项干净检查。执行方 Terra/medium由主会话核对实际 journal；入口无速度参数，不宣称极速。没有设备/ADB/Host/GUI/安装/运行 UUID/未知业务查询或重放，任务/项目和iOS A6保持暂停，完整P7未完成。
