# 阶段102：F59 首轮原生验收与 generation 准备修复

## 本次实际结果

源码 c4fcea62 的独立 SDK 完成：940 项实际源码前后相同、123 项覆盖、14 项工具保持、92 项封存材料逐字节核对，82 个 Gradle 任务执行。App 与测试 APK 的包名、签名及完整字节分别绑定，原构建等待和双流完整。工具采集曾执行 adb version，与执行方“完全无 adb”约束不符；这是本地版本查询，未据此宣称没有 ADB 调用，也没有在 SDK 阶段执行设备命令。

API33 新 boot 上，App/Test 各一次普通 -r -t 更新、UID 及安装后字节核对后，仅调用一次八方法 instrumentation。实际八项启动、七项通过、一项失败；客户端 exit0 不代表测试通过，外层最终 exit1。104 条原始客户端等待/双EOF/关闭/摘要和327份材料均已核对。旧66文件、133对象、默认prefs状态与源输入保持，无遗留UUID夹具；这不能证明全部 opaque Keystore/系统副作用不变。

失败方法 wrongInstalledKeyAndReplacedActualGenerationAreBothRejected 的第一部分不独立升级成方法通过。失败栈位于第二个夹具的 prepareGeneration→produce→computerFileWriteChunk→key resolve，尚未 freeze 或进入真实 generation 替换消费者。保留 IllegalStateException: Existing installation key required; no fallback generation 原记录，不改成 skip 或 reader 拒绝通过。

## 根因及最小修复

prepareGeneration 在真实本地 publication 后把普通 producer 显式重开为 existingKeyOnly=true。prepareChild 会继承该标志，因而新的合法 F59 child 无法在准备阶段生成自己的 key。修复先确认本夹具原 root alias 已存在，再通过普通 ownStorage 重开 producer；新的 child key 只允许在 freeze 之前由真实普通生产路径创建。迁移 snapshot 的 existing-only decoder、缺key拒绝、bad-key 与 replacement 断言、生产 Export/Activation 保守拒绝均保持。捕获工具仅刷新该夹具固定 after pin；940/123/8 purpose 不扩围。

本修复源码尚待新提交指纹下的 SDK 和八项实际 Native，不能继承本轮七项为新源码通过。

## 辅助工具与证据边界

有限纯检查原记录27通过、1条 harness 断言失败。它把“超时结果UNKNOWN”误等同于“日志不能完整”；Root依据实际EOF/源码纠正语义后，只执行新的单个本地超时补验：确实抛出 Refusal、原子进程等待、UNKNOWN/无重放保持，两份4字节流实际EOF及完整摘要一致。原27/1和未覆盖的故障向量保留，不能写成全部30组通过。

Root 正常关闭本次自有模拟器，取得原始 launcher 的 wait0、双EOF及原始日志摘要，核对supervisor/QEMU及5558/5559监听退出；既有R66数据保留。回收已替代的普通编译APK三份，共146,522,112已分配字节；保留当前SDK包和一个回退SDK包，旧charge不退款。

## 功能、交互、界面与继续顺序

F59当前仍为单child只读内核，完整功能未关闭。旧代family候选已准备并独审，跨代相同ID需使用不同payload/hash和独立原receipt断言，live-owner负例必须证明内层真正执行；候选尚未采用、编译或验收。后续先让修复后的八项完整通过，再接旧代完整库存、新流式大容量容器、目的端惰性发布与24组域，之后语音/未读/生命周期。

iOS主线已前进至4c55e1a1，新增原消息意图冲突/恢复及LAN撤权的原生测试与验收文档，未新增Android实现或通过；逐提交续接另行完成。没有新增Main UI、截图或真实Host/模型/手机通过。F59/UI13、迁移preview/激活/失败、方向/字号/AX及双端互通继续逐项待验；任务/项目、F75、iOS A6保持暂停。
