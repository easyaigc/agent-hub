# 生成设置：原轮次与当前目录的边界

日期：2026-11-12。范围：W3 / F34、F35、F36 / S06 / UI07、UI08。候选从 `276bf420b8545b9c1389fac857586c469bc8eff9` 派生；这是专项源码与辅助检查记录，当前状态由 Android 对齐唯一计划维护。

## 实际发现与实现

- Android EasyWork 读取已有轮次时，原先将聊天今天的 generationPreferences 与 latestRun.settings 合并。旧轮次缺模型、强度或权限字段时，会误显示今天的默认值；速度虽已有缺失保护，其他字段仍不一致。现在有 latestRun 只读取其 settings，缺失保持未知；没有轮次才读取聊天默认。iOS 既有适配器已经采用该边界，无新增 iOS 读取实现。此读取变更不修改电脑、草稿或已提交操作。
- Android 旧快速开关的关闭回调仅复核当前目录含 fast。如果异步刷新后目录变为 fast-only，旧回调仍可写入 standard。现在菜单与开关共用 KMP 当前目录选择校验，具体档位未声明即拒绝；跟随电脑仍为 null。模型切换既有 changeModel 规则保持，不为 fast-only 制造 standard。
- 原目标、权限独立字段、原提交正文、operation ID、输入指纹及发送/队列/追加协议保持；未修改队列或子代理方法、Host、Codex Adapter 或正式配置。

## 本次辅助检查及限制

缓存 Kotlin 2.4.21 编译器直接编译实际 MobileRules / SessionPolicies，以及新增 GenerationSettingsPolicyTest 的同体 JVM JUnit 后端副本；仅将 kotlin.test 导入映射到已缓存 JUnit。2/2 通过，编译 2.616 秒，测试进程 0.091 秒。它不是 Gradle jvmTest、Kotlin/Native 或原生 UI 验收。

六个本线 Kotlin 文件经缓存编译器 PSI 语法解析，错误 0，0.386 秒；这只证明语法，不证明 Android 类型解析或运行。首次临时语法检查器因 Kotlin 2.4.21 要求注册 extensions storage 而初始化失败，补齐本地检查器配置后重跑通过；生产源码没有因该错误调整，原失败另保留。

新增但尚未运行的原生用例：MobileEasyWorkGenerationSpeedTest 中原轮次六种设置投影、跟随电脑不序列化隐式参数；MobileGenerationCallbackTest 中目录刷新为 fast-only 后旧开关不得写入 standard。既有加密对端和 UI 用例仍需在准确候选重新执行。本轮未安装依赖、未执行完整 Android/Xcode 构建、未占用设备、未连接真实 DEV 或提交模型输入。

## 总控租约后验收

1. 核对准确候选的源码/包摘要、独立 acceptance application ID、runner 与设备序列号。先在独占 API33/API37 辅助环境运行生成适配器 6 方法、设置回调 15 方法及规范 KMP 测试，保留原失败；实体另验，不以模拟器代替。
2. 使用原有合法授权，只读 runtime/context、model/list、thread/sync：核完整目标、实时 owner、模型目录与 generation-settings / generation-speed 能力；目录默认不作实际轮次设置证据。没有已有 fast-only 模型时用合成目录验证，不改正式目录制造能力。
3. 先在原生菜单核显式模型/强度/速度/权限及跟随电脑，编辑不发送；同一目标切模型按新目录校验，检查四字段独立和旧目标回调拒绝。记录 next-input 草稿和电脑原轮次的不同来源。
4. 总控明确分配设备和模型输入后，用唯一全新 operation ID 与不可变输入提交一次。按完整目标查询原回执并独立核对 Electron 原 owner、轮次设置及实际 provider 请求/用量；响应自述或目录声明不能证明速度生效。跟随电脑不得注入隐式 standard。每个实际档位/权限工具效果需要单独授权的具名场景和证据，不由一次输入推断全矩阵。
5. UNKNOWN 只查原 ID，不自动重放；迟到结果不改变新草稿或已提交意图。实体/API37、主题/语言/大字/横屏/短屏/IME/读屏、iOS 与双 Provider 仍各自登记。

F34/F35/F36 与 Q03 均不能由本记录关闭。PR169/171 原有局部通过和失败证据保留，当前候选未提交真实 DEV 验收。
