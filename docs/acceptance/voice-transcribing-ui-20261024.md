# 转写输入条 UI（2026-10-24）

功能 F64，场景 S04／S11／S17／S18，页面 UI06／UI21。iOS 和 Android 已同步实现：转写时原波形区域显示居中的状态文案，下方不再重复；原停止键位置显示原生加载动画并禁用重复触发。录音、失败重试、结束等待和原草稿回填沿用既有规则。

本次通过：

- `pnpm build:ios`、`pnpm build:android`；Xcode 27.0。沙箱中的 Gradle 本机套接字限制导致首次构建退出，随后在授权环境构建通过，未改动构建配置。
- Node 24.19.0／pnpm 11.19.0 下的 `pnpm format:check`、`pnpm check:mobile-parity` 和 `pnpm check:ios:localization`；1553 条双语资源与四项权限提示通过。
- 独立签名 iPhone App 的 `build-for-testing` 及原有三项 XCTest／XCUIAutomation 回归：取消／完成／手动发送，切换目标／后台保留原草稿，权限拒绝／深色模式。iPhone 15 Pro Max，通过 Xcode 已配对无线连接，3 项通过、0 失败、0 跳过。此组使用合成语音 fixture，不替代真实麦克风或真实 ASR；也未单独截取短暂的转写中画面。

私有证据目录 `.local/voice-transcribing-ui-20261024/` 保存构建与检查日志、`physical-ui.xcresult`、官方摘要 `physical-ui-summary.json`、独立 Runner 签名回执和源码／原生产物 SHA-256。原 Runner 未修改，验收仅操作独立 bundle。

补充真实电脑 ASR 未通过：旧基线会话无可核对的原 owner，首轮在录音前退出；随后用当前实际会话完成了原 owner 与原轮次核对，但原生用例在点击语音入口后未找到录音停止控件，录音未启动、未生成转写回执。原轮次列表保持相同，零普通消息提交；此失败原因尚未确定，不能写成真实转写或麦克风通过。两次私有报告分别为 `.local/voice-native-dev/5de410b9-be4e-45a4-a842-58dd994241d6/report.json` 与 `.local/voice-native-dev/5a0799d5-ebe2-4931-a43b-b3137cda2400/report.json`；临时 Host 均已关闭，没有重放音频操作。

PR 的远端 workspace 检查在 Android 工具安装阶段因 runner 缺少 `unzip` 退出，尚未执行源码检查；[原始 CI 运行](https://git.51easyai.com/BCAI/agent-hub-app/actions/runs/10232)。本地构建与原生结果不冒充远端 CI 通过。

历史语音证据保持原范围。正式 App、其他电脑及 Android 真机本次尚未更新或验收。未创建临时工作树，A6 其余暂停项不扩展。
