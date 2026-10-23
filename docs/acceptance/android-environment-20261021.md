# 更换 Android 真机后的开发环境核验

核查开始于 2026-10-21，构建及整理完成于 2026-10-22（Asia/Shanghai）。本次检查开发工具、真实设备连接、已有独立样例安装/启动/调试和现有产品源码构建；**没有安装 Agent Hub 产品或运行产品功能、真实模型验收**。后续范围见 [Android 新计划](../plans/android-alignment-20261022.md)。

## 新设备

| 项目             | 本次实际结果                                                            |
| ---------------- | ----------------------------------------------------------------------- |
| 厂商 / 型号      | Xiaomi / M2007J1SC；与旧 M2011K2C 不同                                  |
| 系统 / API / ABI | Android 13 / API 33 / arm64-v8a                                         |
| 连接             | USB，ADB `device`，shell 与 boot completed 正常；已与在线 emulator 区分 |
| 屏幕 / 密度      | 1080 × 2340 / 440 dpi                                                   |
| 字体 / 语言      | 1.0 / zh-CN                                                             |
| 存储盘点         | 查询时 `/data` 可用约 17 GiB；只记录当时值，不改用户资料                |
| 系统服务         | GMS/Google framework 与 Xiaomi 服务包存在；未验证推送注册或实际到达     |
| 产品安装         | 检查未发现 Agent Hub 产品包；本轮不安装产品                             |

设备序列号和调试端口只用于准确选择目标，不写入仓库。旧 API34 手机的产品通过结果不能转记到此设备。

## 工具与构建

实际盘点的 15 项全部通过：Node 24.19.0、pnpm 11.19.0、JDK/javac 17.0.20.1、Android SDK、command-line tools 22.0、Android CLI 1.0.16500706、SDK platforms 36/37.0、build-tools 36.0.0/37.0.0、ADB 37.0.1、Emulator 37.2.12.0、AVD、许可证记录与 Android Studio。API26/API37 ARM64 AVD 已配置，本次未重新运行其产品测试。

首次沙箱内 ADB/CLI/模拟器查询受本机访问限制，随后在真实本机环境重查通过；保留两次结果，没有升级工具或改变手机安全设置。

| 检查                                     | 结果及边界                                                                                                                               |
| ---------------------------------------- | ---------------------------------------------------------------------------------------------------------------------------------------- |
| `pnpm check:mobile-env --android --json` | 真实本机环境全部通过；不等同产品验收                                                                                                     |
| 锁定构建工具                             | Gradle Wrapper 9.5.0、AGP 9.3.1、Kotlin 2.4.21；compile/target API37、min API26                                                          |
| 首次产品离线构建                         | 失败：主目录新 Gradle 缓存缺少锁定 AGP 插件。Wrapper 下载完成不代表插件已缓存；不记为源码编译错误                                        |
| 标准缓存下构建                           | 使用已有标准 Gradle 缓存与工程既定依赖，debug 与 androidTest APK **构建成功**；2m26s，82 项任务执行                                      |
| 构建源码                                 | 本地 `a57a50fed4a798bf3ceb0bfd56bdb17537501503`；与对照主线 `6d81284e9c352e977140c09197526296d57b9669` 的 Android/KMP/原生构建输入无差异 |
| 警告                                     | 保留 x64 Native 目标在 ARM64 主机不可运行、弃用/host-test 配置、部分原生库不能 strip 等原输出；不声称无警告                              |
| 原生测试执行                             | 未执行；只构建 androidTest APK，未将构建写成测试通过                                                                                     |

可复查命令：

```sh
GRADLE_USER_HOME="$HOME/.gradle" pnpm exec node scripts/mobile/gradle.mjs \
  :android:assembleDebug :android:assembleDebugAndroidTest --no-daemon
```

机器私有 SDK/JDK/代理配置不纳入此文档，工程依赖版本未改。

## 独立样例真机结果

使用既有 `Mobile Environment Smoke`，包名 `com.codexmanage.environmentsmoke`，没有网络或其他权限；原生 Activity 调用公共 Kotlin 模块并显示环境提示。

| 步骤       | 本次结果                                                                                       |
| ---------- | ---------------------------------------------------------------------------------------------- |
| 初次安装   | `INSTALL_FAILED_USER_RESTRICTED: Install canceled by user`；只记录系统返回，不推断人为取消原因 |
| 安装恢复   | 用户明确回复已允许 USB 安装；重试返回 `Success`                                                |
| 冷启动     | `Status: ok`、`LaunchState: COLD`；TotalTime 349 ms、WaitTime 366 ms                           |
| 准确前台   | 当前焦点和 Activity 均为样例包的 `MainActivity`                                                |
| 画面 / KMP | 原图已查看，显示 `Android + KMP environment ready`                                             |
| 调试权限   | `run-as` 返回样例自身 UID；目标进程存在                                                        |
| 实际调试器 | 临时 JDWP 转发绑定准确进程，JDB 成功连接并枚举 main/system 线程                                |
| 清理       | JDB 正常退出，本任务唯一转发已移除，样例进程仍在；样例保留作环境工具                           |

## 证据与后续门槛

| 产物                 | SHA-256                                                            |
| -------------------- | ------------------------------------------------------------------ |
| 独立样例 APK         | `96f7896d7dd98a7d9de1a37d04e1f0866bb44da678e306d8abe03f3743b106f1` |
| 本次样例原图         | `8fc8ec61a8932afc417aa1ed88103e85191c9cc6648c6f96bf5c05780e7cd3d8` |
| 产品 debug APK       | `30b980229a8482b267cb7f1a70972262a891ab3a95bb077a6a5f2684f6a8b30a` |
| 产品 androidTest APK | `d23ac4f7ca3fa85809fc3a13e24e2a0aff38206dbc41f84964dca735262ad624` |
| 成功构建原日志       | `7f5b4477bb043bc5ec2fea93f29b83a15d459d3fbe73dbd320632c263775b23d` |

私有原件和失败/恢复记录保存在忽略目录 `.local/android-plan-20261021/`。公开文档不含聊天导出、手机序列或凭据。工具链与真机开发通路已验证；产品包身份/数据隔离、runner 执行、真实系统功能和 Host/模型链路仍属后续 P0/P1–P10。A6 继续暂停，Task Hub 项目/任务管理暂缓。
