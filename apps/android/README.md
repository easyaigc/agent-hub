# Agent Hub Android

原生 Kotlin / Jetpack Compose 工程，Android 8.0 / API 26 起，compile / target API 37。A1–A4 及 UM01–UM05 已有实现与历史适用验收；EasyWork A5 整体尚未封存，后续 iOS 优化尚未全部同步，系统能力与 Android 通知的完整门槛未关闭。当前以 [2026-10-22 后续计划](../../docs/plans/android-alignment-20261022.md)及逐项矩阵为准；Task Hub 业务项目/任务暂缓，A6 保持暂停。更换后的真机仅完成开发环境核验，未执行产品功能验收。

开发包为 `com.easyai.agenthub.android.debug`，发布包为 `com.easyai.agenthub.android`。两者安装身份分离；发布签名尚未配置。品牌图标直接复用现有 iOS PNG；后续产品图标采用语义相同的自有矢量，平台系统控件差异单列。

开发验收使用 `-PagentHubAcceptance=true` 构建，debug 源码保持相同，安装身份为 `com.easyai.agenthub.android.acceptance`，测试包自动追加 `.test`。该包与 DEV/正式版的数据目录、UID 及 Keystore 隔离，不复制真实连接或凭据。脚本必须核对 APK 的实际 application ID 和 runner，旧脚本硬编码的 `.debug` 不能直接用于此包。

```sh
pnpm exec node scripts/mobile/gradle.mjs :android:assembleDebug :android:assembleDebugAndroidTest -PagentHubAcceptance=true
```

阶段进度及门槛见[分步台账](../../docs/plans/android-alignment-20261022/progress.md)。

使用 JDK 17、Android SDK 和根 Gradle Wrapper，Node 工作空间仍只使用 pnpm。

```sh
pnpm build:android
ANDROID_SERIAL=<dedicated-emulator> MOBILE_PHASE=A1 pnpm test:android
```

测试入口要求明确的 `emulator-` 序列号，仅对该模拟器安装 APK 和仪器测试 APK。不会向其他已连接手机安装。日志、原生截图、API／ABI／APK 摘要保存在忽略的 `.local/mobile-acceptance`。

`scripts/mobile/gradle.mjs` 支持 `JAVA_HOME`、`ANDROID_HOME` / `ANDROID_SDK_ROOT`、`KOTLIN_NATIVE_HOME`，也可读取忽略的 `.local/mobile-toolchain.json`，其中仅保存本机工具路径。依赖缓存在独立工作树的 `.local/gradle-home`。不要提交 SDK 路径、签名秘密或其他本机配置。
