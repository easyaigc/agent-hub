# P0 环境、隔离包与执行现场

历史过程档案，保留原日期、失败、限定通过与当时的下一步；**当前状态以[计划与进度总表](../progress.md)为准**。旧分支、门槛及优先级不作为当前执行指令。

<a id="progress-27-40"></a>

## 原进度记录 · 原文第 27–40 行

## P0 完成记录

- 新工作树从冻结主线建立，独立 pnpm 安装、原生输出及验收目录；没有覆盖主检出或旧工作树。Gradle 只复用既有下载缓存，构建输出和工程状态独立。
- Android 增加显式 `agentHubAcceptance=true` 构建选项，独立 application ID 为 `com.easyai.agenthub.android.acceptance`；默认 DEV/正式身份保持原值。APK 与测试 APK 已成功构建，实际签名和包名已读回。
- 修正 F04 的 Codex 机器身份断言、S14 的 EWDAP/1 免手机登录、S17 双语音路线和 S18 全页面范围；X01–X09 登记为既有 F 的设计子项。未把规范修正记为产品验收通过。
- 新手机第一次独立产品安装返回 `INSTALL_FAILED_USER_RESTRICTED`，原失败保留；用户再次允许后，产品和测试 runner 安装成功，包名、数据目录及实际原生流程核对通过，最终 `MobilePhysicalSmokeTest#foregroundNativeControls` 为 1/1。没有真实模型输入。
- 真机初次原生用例找不到恢复消息：验收器现在先核对恢复只增加一条原文，再实际滚动并确认可见；断言更严格。后续截图发现输入法账号提示遮挡，补上 App 自有收起键盘按钮操作后重验；最终恢复正文原图已查看。原失败与遮挡原图单列保留，不作为业务登录或产品通过证明。
- 最终验收器候选在独立 API26 的 `MobileFoundationTest` 5/5 通过；默认 DEV 包另行构建并读回包名不变。API26 早期图中的启动过渡/键盘引导和新手机自定义字体/输入法属于 P6 后续完整视觉矩阵，P0 不据此宣称视觉一致。
- 新建独立 API37 曾冷启动挂起并以 signal 11 退出；设备/内存、短路径、正常 AVD 清单、只读实例、独立公共镜像等原失败保留。按 [Android 官方发布说明](https://developer.android.google.cn/studio/releases/emulator?hl=en)，API37 手机 AVD 至少配置 4GB，本次配置为 4096 MiB。
- 用户明确允许关闭旧 `AgentHub_API37_Arm64` 后，已执行正常关闭并核磁盘资料仍在、配置摘要不变。随后独立 API37 成功冷启动、安装最终候选并完成 `MobileFoundationTest` **5/5**；运行的是本阶段独立包和独立 AVD。该结果保留并行实例影响线索，不宣称已隔离出模拟器内部根因。
- 一致性检查通过；首次缺少 protocol dist 的失败在独立工作空间构建后重验通过。完整仓库检查的 **59 个命令按原顺序分段通过**：首次新 Gradle 缓存下载等待被本任务取消，复用已有同版本缓存/编译器后，从 `check:agent-contract` 续跑至末尾全部返回 0；原取消记录保留，非一次无中断通过。Windows 原生专用项在 macOS 的跳过不作为 Windows 通过。最终原生验收器变化另由真机/API26 与一致性检查覆盖。
- [P0 脱敏证据](../../../acceptance/mobile-evidence/android-alignment-P0.json)登记最终包指纹、API26 5 项/API37 5 项/真机 1 项和全部原失败。**P0 适用门槛已关闭**；这些是工程/原生合成基线，不增加真实模型、双端全功能或 A6 通过项。
- 初始检查点 `8a3fa9f3ce78fa6e82625653f16e83fba0c0b866` 已正常推送并独立读回远端一致。P0 关闭记录另作阶段提交；完成远端核对后才开始 P1，精确关闭 SHA 在 P1 开始记录回填。
