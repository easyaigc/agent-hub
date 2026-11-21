# W4：iOS 速度旧回调的当前目录校验（2026-11-12）

范围 F34/F36，UI07,UI08；S06；本文仅为单次来源记录，不是当前状态总表。唯一进度由主会话维护。

在候选6d61903f中，SessionStore.speed原setter仅校验canConfigureGeneration/supportsGenerationSpeed，旧回调可向当前fast-only目录保存standard。相同实际Swift属性抽取执行的14场景中原代码5失败，修复后14通过；旧反例确实执行remember/persist各一次，修复后均零次。空字符串保持nil/跟随电脑；合法目录项仍保存；禁用或不支持速度时保持原字段、原提交和独立草稿。

最小修复只在原guard后将newValue转换为显式String?，调用SessionPolicies.shared.generationSpeedAllowed(speed:supportedSpeeds:)，按当前availableSpeeds拒绝过期值，原rememberGeneration(.speed)/persist不改。未修改回执、请求、原操作或其他setter。

测试入口scripts/mobile/ios-generation-speed-setter.test.mjs。原baseline/fixed版本直接读取已有KMP JVM产物；总控随后为当前版本补入现有Gradle入口`:mobile-core:jvmJar`前置，并接入`check:ios:generation-speed`及既有`check:easywork-ios-boundaries`，冷CI不依赖预先存在的jar。原14场景及其脚本指纹保留，新引导版运行结果另行登记。Swift只使用桥接器调用真实编译KMP SessionPolicies.INSTANCE策略，不实现另一个布尔规则；该策略源码SHA与W1构建输入匹配。现有macOS框架未导出新函数，不用旧框架冒充当前策略。原baseline/fixed轮每次临时编译约0.33MiB，未调用Gradle/Xcode或安装依赖；该边界不套用到新增Gradle前置的当前版本。

本次为抽取属性执行与KMP策略辅助证据，不验证真实MobileCore Swift ABI、完整SessionStore持久化、整App/UI/原生/真实DEV；后续由总控分配正式App编译与具名验收。Android既有相同KMP准入不改，已冻结APK/runner不受本次Swift改动影响。A6继续暂停。
