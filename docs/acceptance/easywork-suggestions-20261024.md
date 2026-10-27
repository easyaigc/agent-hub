# EasyWork 下一轮建议接入（2026-10-24）

## 已实现

EasyWork 桌面每个成功根轮次自动生成下一条用户输入建议，完整继承父轮最终有效 Provider 上下文、模型和推理配置。Host 仅从同一 runtime 缓存读取；手机轮询不触发新的模型调用。设置默认开启，关闭后生成及读取均失效。

新增具名 `thread/suggestion/read`，参数为当前 threadId 和 sourceTurnId；沿用 EasyWork v2 加密会话的设备授权和完整目标校验。返回 source=runtime、validity=runtime-version、SHA-256、稳定内容 ID 和 6 秒租期。运行中、来源改变、禁用或没有缓存均不返回建议。

iOS EasyWork 适配器校验原连接完整目标，读取经会话验证的响应并复用现有建议 UI、草稿保护和撤销；刷新租期不改变建议身份。与 Codex native-trace／desktop-display 来源分别校验，不允许静默降级。Android 建议功能尚无生产实现，本次登记影响及待办，不记双端完成。

## 验收状态

- 真实 EasyWork Electron、Qwen3.8-Flash 模型及桌面 Tab／一次撤销／引导持久化：已通过；父请求 43,016 字符，建议请求完整保留其 5 条消息前缀，再追加 2 条。模型不支持推理档位选择，使用其原生默认配置。
- iOS 模拟器目标应用构建：通过（显式 DEVELOPER_DIR 使用已安装 Xcode）。
- Swift runtime-version source, lease renewal, stale preview and draft boundaries: passed.
- Physical iPhone 15 Pro Max / iOS 27.0.1: passed, 1 XCTest / zero failures. Independent bundle com.easyai.agenthub.suggestions.acceptance. Seven real encrypted LAN read calls, matching source turn and suggestion text; local draft adoption and undo; zero turn/start, queue/enqueue or plan/action; parent message count stays 2.
- Android、Windows 和其他电脑部署：本次未验收、未部署。

所有真实模型输入仅在独立 DEV 验收工作区发生；不读取或公布凭据，不变更正式 Host 身份、手机正式版或其他电脑配置。

## Final evidence (2026-10-24)

Private evidence: `.local/prompt-suggestions-ios-device/run-3/report.json`, XCResult and exported screenshots. App binary SHA-256: `f0cecd5662fef1e436f2df2ee989da8a630a55b8bc11742e5a1b029d4af1c13a`. Desktop independent verification: EasyWork repository `release-e2e/prompt-suggestions-host/2026-10-24T05-49-58.536Z/acceptance.json`.

Run 1 found missing verified readTarget on EasyWork workspace/history; fixed and rebuilt. Run 2 used an expired five-minute pairing offer and did not connect. Run 3 used a fresh offer and passed. Prior failures remain available; no business input was replayed. The task-owned Host supervisor has stopped. Formal apps and other computers were not updated.

Full `pnpm check` did not pass: unchanged `scripts/task-hub-service.test.mjs:502` expected a TLS fingerprint rejection and failed again when isolated. All earlier completed stages remain evidence; the stages after that failure were run separately and passed. This is not recorded as a full-check pass. Final parity/digest checks and iOS builds are recorded separately.
