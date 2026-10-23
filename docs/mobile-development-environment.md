# 本机原生移动开发环境

**当前设备更新（2026-10-21／22）：**已更换为 Xiaomi M2007J1SC、Android 13/API33。USB、已有独立样例安装/启动/KMP/实际 JDWP 调试与现有产品构建已核验，见 [新手机环境记录](acceptance/android-environment-20261021.md)。以下 M2011K2C/API34 与旧样例/产品记录保留为历史，不转记为新设备通过；当前安排见 [Android 后续开发与验收](plans/android-alignment-20261022.md)。

环境准备日期：2026-10-11。本文记录开发工具与独立样例的实际检查；Android 产品工程和 KMP 生产模块仍按[开发计划](plans/android-native.md)实施。环境样例不代表双端功能一致性或真实 Host／模型验收通过。

## 工具与版本

| 工具                           | 本次版本／配置         | 用途                                         |
| ------------------------------ | ---------------------- | -------------------------------------------- |
| macOS                          | 27.0，Apple Silicon    | 本机开发与 ARM64 模拟器                      |
| Node.js / pnpm                 | 24.19.0 / 11.19.0      | 既有 JS 工作空间                             |
| Xcode                          | 27.0，27A266a          | SwiftUI、iOS SDK 27.0、模拟器编译            |
| Android Studio                 | 2026.2.1.8，Rabbit 1   | Android 原生 IDE                             |
| OpenJDK                        | 17.0.20.1，ARM64       | Android／Gradle 编译                         |
| Android command-line tools     | 22.0，build 15859902   | SDK 管理；新 Android CLI 1.0.16500706        |
| Android SDK platforms          | 36、37.0               | 已安装平台；样例编译／目标使用 API 36        |
| Android build-tools            | 36.0.0、37.0.0         | 打包；样例使用 36.0.0                        |
| Android platform-tools         | 37.0.1，ADB 1.0.41     | USB、安装、日志和调试转发                    |
| Android Emulator               | 37.2.12.0              | ARM64 AVD，CPU 使用 Hypervisor.Framework     |
| 独立样例 Gradle / AGP / Kotlin | 9.5.0 / 9.3.1 / 2.4.21 | 验证 Android 与 KMP 工具链，不是产品版本锁定 |

Xcode 与 Android Studio 来自官方 App Store／官方发行文件，JDK 来自 Homebrew。SDK 许可证已在安装时确认。Gradle 样例使用固定 Wrapper 和官方分发文件 SHA-256；JS 工作空间继续只使用 pnpm。

AGP 9.3 要求 Gradle 9.5、JDK 17；本次选择的 AGP 9.3.1 在 Kotlin 2.4.21 官方兼容范围内。Kotlin 官方表列出的 Xcode 是 26.4，本机 Xcode 27.0 的样例结果应单列，不能把一次编译视为官方支持范围扩展。产品 A1 仍需锁定并回归完整工具链。[AGP 官方要求](https://developer.android.com/build/releases/agp-9-3-0-release-notes)、[KMP 兼容表](https://kotlinlang.org/docs/multiplatform/multiplatform-compatibility-guide.html)

## 本机路径与终端配置

Android SDK 统一放在 `$HOME/Library/Android/sdk`，JDK 注册在当前用户的 `Library/Java/JavaVirtualMachines`。终端配置保存在 `$HOME/.config/codex-manage/mobile-development.zsh`，已由 `.zprofile` 和 `.zshrc` 加载；既有配置保留并备份。新开终端即可获得 `JAVA_HOME`、`ANDROID_HOME`、`ANDROID_SDK_ROOT`、`DEVELOPER_DIR` 与原生工具 PATH。

当前已打开的终端可以显式加载：

```zsh
source "$HOME/.config/codex-manage/mobile-development.zsh"
pnpm check:mobile-env
pnpm check:mobile-env --android
pnpm check:mobile-env --ios --json
pnpm build:ios
```

`check:mobile-env` 只盘点工具、SDK、许可证记录、AVD 与可用 iOS runtime，缺项退出码为 1。它不会下载组件、启动模拟器、安装 App 或访问 Host，也不代替构建、调试和产品验收。工具检查输出不包含手机序列号。

Android Studio 使用标准用户缓存 `$HOME/.gradle`，JVM criteria 固定为 17；该缓存下的样例编译已通过。独立验证另保留 `.local/mobile-environment/gradle-home`，IDE 原生导入预备任务也已离线运行通过。手机验收暂停期间，可在 IDE 设备下拉中选择 `AgentHub API37 Arm64` 后运行。

本机既有代理配置已适配 Gradle；相关设置仅保存在用户私有配置中，不提交地址或凭据。网络环境改变时调整当前用户的 Gradle 配置，不能将本机代理写入公共工程。

Xcode 27.0 本次提供 Device Hub 作为设备管理界面，已打开专用模拟器并核对 App 启动后的未连接页面；命令行继续使用 `xcrun simctl`。

## Android 模拟器与真机

已创建 `AgentHub_API37_Arm64`（Android 17／API 37，Google APIs，Pixel 9）及 `medium_phone`（Android 16／API 36，Google Play）两个 ARM64 AVD。API 37 已用 SwiftShader 启动并完成 boot；CPU 保持硬件加速。默认 GPU 自动模式曾出现渲染错误，已在本次创建的 API 37 AVD 中保存 SwiftShader 配置。也可以显式指定：

```zsh
emulator -avd AgentHub_API37_Arm64 -gpu swiftshader
adb devices -l
```

USB 真机为 Xiaomi M2011K2C，Android 14／API 34，ARM64。手机已确认 USB 调试授权，ADB shell 可用。真机安装与调试结果见下表；`device` 状态只证明连接，不能单独作为安装或断点调试通过的证据。

小米系统曾拒绝 ADB 安装并返回 `INSTALL_FAILED_USER_RESTRICTED`，安全中心日志提示需插入 SIM 卡。手机端的 USB 调试、USB 安装和安装确认由用户正常完成；不通过修改安全设置或绕过厂商检查处理。独立 APK 已放到手机下载目录，正常手动安装也可用于验证后续 ADB 调试。

## 2026-10-12 独立样例真机补验

本机私有记录补齐了独立样例 `Mobile Environment Smoke` 的 Android 真机安装、启动和调试器验证：同一 APK 安装返回 `Success`，冷启动为 `Status: ok`、409 ms，`run-as` 与 JDB 经 USB JDWP 连接、主线程枚举和临时转发清理均通过。APK SHA-256 为 `96f7896d7dd98a7d9de1a37d04e1f0866bb44da678e306d8abe03f3743b106f1`。

历史证据保存在忽略目录 `.local/mobile-environment/` 的 `device-install-retry-20261012.log`、`device-launch-20261012.log`、`device-debugger-20261012.log` 和 `device-debug-verification-20261012.json`；本次整理已核对原件及摘要。这些结果只属于独立环境样例，不改变产品真机的 JDWP、完整功能或阶段验收结论；2026-10-11 的安装失败和后续产品记录继续保留。

## 本次运行结果

| 检查                          | 结果                         | 证据／范围                                                                         |
| ----------------------------- | ---------------------------- | ---------------------------------------------------------------------------------- |
| Android 工具盘点              | 本次通过                     | `pnpm check:mobile-env --android`                                                  |
| Android Studio 首次初始化     | 本次通过                     | 识别统一 SDK，安装 API 37.0 sources，进入 IDE 并打开独立样例                       |
| Android Studio 工程同步       | 本次通过                     | `app` 与 `shared` 模块、运行／调试配置已识别；JVM criteria 17、固定 Wrapper        |
| Android 样例构建              | 本次通过                     | 固定 Wrapper 执行 `:app:assembleDebug`，44 个任务                                  |
| API 37 模拟器安装与启动       | 本次通过                     | APK 安装、Activity 启动、前台进程与 `run-as`                                       |
| API 37 模拟器调试器连接       | 本次通过                     | JDB 经 ADB JDWP 转发连接并枚举主线程；转发已清理                                   |
| Android 真机 ADB 连接         | 本次通过                     | USB 授权后为 `device`，设备属性与 shell 可读                                       |
| Android 真机安装／启动／JDWP  | 安装启动本次通过；JDWP未验收 | 用户恢复小米后已安装实际产品并核对字节；原生 UI 与模型任务专项通过，JDWP 未执行    |
| iOS 工程模拟器编译            | 本次通过                     | `pnpm build:ios`，含通知扩展，`BUILD SUCCEEDED`                                    |
| iOS 模拟器 runtime／启动      | 本次通过                     | iOS 27.0（24A434），专用 iPhone 17 Pro 模拟器安装并启动现有 App                    |
| iOS 模拟器调试器连接          | 本次通过                     | LLDB 连接 App、读取主线程并 detach，App 保持运行                                   |
| KMP iOS framework／Swift 调用 | 本次通过                     | Kotlin/Native framework 构建 18 秒；Swift 在 iOS 27 模拟器调用公共代码并校验返回值 |

Android 独立样例名为 **Mobile Environment Smoke**，包名 `com.codexmanage.environmentsmoke`，没有网络或其他权限。其 native Activity 调用公共 Kotlin 模块并显示 `Android + KMP environment ready`。APK SHA-256 为 `96f7896d7dd98a7d9de1a37d04e1f0866bb44da678e306d8abe03f3743b106f1`。

样例、下载、构建和调试日志保存在仓库忽略目录 `.local/mobile-environment/`；产品 iOS 构建在 `.build/ios/`。独立样例含本机 SDK 路径，不能直接作为可提交产品工程。后续 Android 产品将另建受版本控制的工程、Wrapper、依赖和验收入口。

本次 `pnpm check:mobile-env` 双端盘点与新增脚本 ESLint 检查通过。仓库整体 `pnpm check` 在既有 `check-agent-contract.mjs` 的 Swift 契约往返断言失败：`networkKind: vpn` 未保留。工作区存在其他并行修改，此处只记录实际失败，不覆盖其他业务代码；先前使用 Command Line Tools 的检查通过属于较早工作区快照，不能替代当前结果。

iPhone 真机的开发团队、签名与安装不在本次已验证范围；用户连接 Android 手机不能代替 iPhone 验证。完整产品验收继续按[双端验收规范](acceptance/mobile-parity.md)逐端记录。

## 产品真机验收入口

用户 2026-10-13 恢复小米验收后，实际产品 `com.easyai.agenthub.android.debug`／`0.1.0-dev` 已安装在 API34 设备。原生首次启动、示例设置、发送／停止／恢复，以及单独的真实模型任务／回执／草稿恢复子集通过；结果不代表全部系统功能或通知真机验收。最新进度见[九阶段记录](acceptance/android-stages.md)。

先通过 `pnpm exec node scripts/mobile/gradle.mjs :android:assembleDebug :android:assembleDebugAndroidTest` 构建，明确指定用户授权的手机，再运行 `ANDROID_SERIAL=<device> ANDROID_ALLOW_PHYSICAL=1 pnpm exec node scripts/mobile/android-physical-smoke.mjs`。真实模型入口另外设置 `ANDROID_PHYSICAL_THREAD=<owned-dev-thread>` 与 `ANDROID_PHYSICAL_PROJECT_ROOT=<dedicated-dev-project>`，运行 `pnpm exec node scripts/mobile/android-physical-model-live.mjs`；只接受工作树 `.local/mobile-acceptance/A4` 中的专用项目及其准确原 owner。

两个入口通过已安装 base.apk 的实际字节 SHA-256 判断是否复用，变更的包使用保留数据的覆盖安装并在安装后再次核对。厂商确认由手机正常完成；同一包反复验收不重复安装。MIUI 的后台 Activity 启动限制由前台启动的验收规则处理，不修改后台权限；原生用例采用 v2 Compose 测试时钟。证据与截图保持私有，功能通过后仍需人工逐张核图。

## A4 真实运行失败验收

`MobileLiveRuntimeFailureTest` 验收原桌面实际失败 Turn、带准确 Turn ID 的失败回执、原生提示关闭持久化、独立草稿恢复，以及同一聊天显式提交的真实成功任务。工具失败被模型正常处理为 completed、提交拒绝或未知结果均不能代替这条证明。

`android-runtime-failure-live.mjs` 是需要明确授权的本机专用入口，不纳入自动 CI。它临时在用户 Codex 配置末尾添加独立提供方，保持默认提供方不变；仅准备零 Turn 的专用项目聊天，确认准备进程正常退出后，由原桌面接管。脚本输出准确聊天 ID 后，通过 Codex 正常打开该聊天。当前执行聊天的 `CODEX_THREAD_ID` 用于只读核对原 owner、当前 Turn 和提供方均未变化。

```zsh
# 仅在已获准修改本机用户配置、并让专用聊天认证头经过本机代理后运行。
A4_CONTROLLED_PROVIDER_APPROVED=1 ANDROID_SERIAL=<emulator> \
  pnpm exec node scripts/mobile/android-runtime-failure-live.mjs
# 已获准的实体手机另加 ANDROID_ALLOW_PHYSICAL=1。
```

独立代理先断开模型连接，取得真实 failed Turn 后才转发下一条显式恢复输入到固定 OpenAI 官方端点。认证头只在内存中转发，证据仅保存请求数量、指纹与实际任务结果。代理使用本机现有网络代理；不会修改系统网络、默认模型、登录存储或已有聊天设置，也不会重启原 Codex。验收结尾按精确新增段移除配置、关闭独立代理和 Host、清理 ADB 转发，并核对配置恢复及当前聊天运行。并行配置编辑保留。运行证据和新增配置段保存在忽略的私有验收目录，人工核图后才封存通过；这些结果不替代 A5 的 EasyWork 失败／恢复验收。
