# Host bridge 超快模式（2026-10-28）

关联 F36、S06、UI08。用户要求在模拟器通过真实任务后推送远程主分支。

## 实现

模型目录的稳定 ID 决定速度能力；兼容 serviceTiers 与 additionalSpeedTiers，fast 统一映射 priority，ultrafast 保持原值。不按展示名称推断未知服务档位。提交仍校验所选模型的能力，同操作 ID 的意图不可变，未知结果不重放。

原 owner 当前 latestServiceTier 优先于保留的 latestThreadSettings；明确 null 表示标准，未知值保持未知。iOS 已有多档选择改为显式原生菜单，只有模型提供 ultrafast 才显示极速选项；旧 fast 开关保持。

Android 复用 Host 契约，本次未修改 Android 原生实现、未新增 Android 验收；A6 保持暂停。

## 验收

本轮使用独立工作树、Host 状态、构建目录和专用 iOS 模拟器，原 Electron 独占执行。未修改正式 Host、其他模拟器或用户原有未提交工作。私有状态、完整回执、操作 ID、源码与 Adapter 指纹、Xcode 日志和 xcresult 保存于工作树外的本机私有验收目录。

执行入口：构建后设置 IOS_SIMULATOR_ID、GENERATION_ULTRAFAST=1、GENERATION_PROJECT_ROOT 和 GENERATION_ACCEPTANCE_DIRECTORY，运行 node scripts/generation-settings-desktop-ios-acceptance.mjs。目录只提供真实连接元数据，不模拟回复；逐项独立核对原 owner、真实完成回复、每个操作只执行一次、模型／强度／权限／serviceTier 和 Host 回读。

本次通过：原生 XCTest 1 项，0 失败、0 跳过；准确 iPhone 17 Pro／iOS 27.0 arm64 模拟器已由 xcresult 复核。真实原桌面版本为 26.1023.50603，模型 GPT-6.1-Sol／low。每条任务均由独立 IPC 核对原 owner、唯一用户输入、完成回复、模型／强度和完全访问权限；Host 回读速度一致。两张设置截图已人工核对。

| 场景 | 原 owner serviceTier | 结果 |
| --- | --- | --- |
| 无项目首条选择极速 | ultrafast | 本次通过，输入一次 |
| 新项目继承、重启 App 后首条 | ultrafast | 本次通过，输入一次 |
| 切回快速 | priority | 本次通过，输入一次 |
| 切回标准并核对下次新建偏好 | null（桌面将标准规范化为空值） | 本次通过，输入一次 |

`pnpm check` 完整通过，包含超快 IPC 透传、同 ID 幂等／冲突、无能力拒绝、未知 tier 名称不冒充能力及当前速度优先级；Windows 原生平台专属项在 macOS 明确跳过，不计 Windows 通过。`pnpm build:ios ONLY_ACTIVE_ARCH=YES ARCHS=arm64` 构建通过。

Adapter SHA-256：`f54e69521f8910dec44453790ca08e6855943ee01da95b113dbfcba562e0be6a`。原生核心源码摘要：`4face0812c1b4be243919d2b3d9ff80cc119f807c6dc5afc431b0734ce8702c8`。未修改正式 Host 或手机版本；Android、Windows、物理 iPhone 与其他模型的本次真实执行未验收。

## 失败保留与只读复核

首次预检的验收能力标签断言失败、模拟器启动期间的 Xcode 架构／模块失败，以及收集巨大文档差异时触发默认缓冲上限，均发生在零模型输入阶段，报告／日志保留。模拟器启动曾等待系统剪贴板读取，之后自行恢复，没有重启该共享系统服务。

原生测试完整通过后，旧核对器把标准只写为 default，未接受当前桌面的 null 规范化，故原始报告保留为失败（前三条已核对通过）。核对器改用 Adapter 的速度归一规则，并新增 `GENERATION_VERIFY_DIRECTORY=<original-run-directory>` 只读恢复入口：必须已有 1／0／0 原生测试证明，复核准确设备、相同 Adapter／原生源码／断言指纹，保留原报告摘要并写独立验证报告；不启动 XCTest、不重置运行状态、不新建或发送输入。四条原输入已全部只读复核通过，没有重复模型任务。验收脚本同时改为调用服务完整 close 释放通知锁及计时器。

## 主线集成与清理

推送前已合入同期远程 Android 录音二进制身份／冷恢复更新；保留远程功能清单并差异加入 F36，重新生成表格。七个 Host／iOS／专项测试文件与上述通过指纹逐字节相同，因此复用四条真实任务，不再发送。集成后的全仓格式、lint、双端契约／功能清单和 TypeScript 类型检查通过；此前完整 `pnpm check` 的原始日志继续绑定其原输入版本。专用模拟器已关闭并删除，树外证据保留；正式 App／Host 与其他模拟器未改。
