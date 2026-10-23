# Agent Hub Android

原生 Kotlin / Jetpack Compose 工程，Android 8.0 / API 26 起，compile / target API 37。当前为 A1 工程与双端复用验证，只有本地示例与基础原生页面；Host、EasyWork、系统附件、语音、持久恢复和通知尚未接入，完整状态见 `docs/acceptance/android-stages.md`。

开发包为 `com.easyai.agenthub.android.debug`，发布包为 `com.easyai.agenthub.android`。两者安装身份分离；发布签名尚未配置。品牌图标直接复用现有 iOS PNG；后续产品图标采用语义相同的自有矢量，平台系统控件差异单列。

使用 JDK 17、Android SDK 和根 Gradle Wrapper，Node 工作空间仍只使用 pnpm。

```sh
pnpm build:android
ANDROID_SERIAL=<dedicated-emulator> MOBILE_PHASE=A1 pnpm test:android
```

测试入口要求明确的 `emulator-` 序列号，仅对该模拟器安装 APK 和仪器测试 APK。不会向其他已连接手机安装。日志、原生截图、API／ABI／APK 摘要保存在忽略的 `.local/mobile-acceptance`。

`scripts/mobile/gradle.mjs` 支持 `JAVA_HOME`、`ANDROID_HOME` / `ANDROID_SDK_ROOT`、`KOTLIN_NATIVE_HOME`，也可读取忽略的 `.local/mobile-toolchain.json`，其中仅保存本机工具路径。依赖缓存在独立工作树的 `.local/gradle-home`。不要提交 SDK 路径、签名秘密或其他本机配置。
