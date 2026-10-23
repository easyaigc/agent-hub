# ADR-001：原生 iOS 与 pnpm Monorepo

状态：已接受。日期：2026-10-07。

## 背景

产品包含 iOS APP、每台电脑的连接程序、中继服务和共享协议。电脑端需调用 Codex 子进程与本地传输，iOS 需处理原生后台生命周期、Files、图片、Keychain 和推送。

用户已明确选择继续使用 pnpm。

## 决策

- 使用 pnpm Workspaces 管理 JavaScript/TypeScript 工作空间与单一锁文件。
- 使用 Turborepo 管理依赖顺序、构建缓存与开发任务。
- Node.js 24 运行 Host Agent 与 Relay，保持运行时与包管理器的职责独立。
- iOS 使用 SwiftUI 与原生 Xcode 工程，接入统一仓库，但不把原生构建强行并入 Linux 默认任务。
- `workspace:*` 链接内部依赖，Catalog 集中版本；所有包为 private，当前不发布 npm 包。
- 共享领域契约与 Codex 版本相关协议分开，避免手机和中继耦合上游细节。

## Bun 的比较

Bun 已支持 Workspaces、`workspace:*`、Catalogs 和隔离安装，因此它能管理该 Monorepo。Bun 作为包管理器也不要求服务必须改用 Bun 运行时。

当前优先减少可行性验证中的变量：pnpm 管理包，Node 运行服务，Turborepo 管理任务。没有为本项目执行 Bun 与 pnpm 安装速度对比，不以官方单个 benchmark 作为该仓库的实测结论。

若未来切换 Bun，包管理器迁移和运行时迁移分别评估。前者验证 workspace、锁文件、CI 与打包；后者验证子进程、Unix socket、SQLite、网络与文件传输。不要将两种迁移混为一个选择。

## 影响

优点：应用与协议在同一仓库迭代，Node 工具链和 iOS 工程各有清晰边界，跨平台 CI 可以分别验证。

代价：iOS 与 TypeScript 领域模型未来需要 Schema 生成或契约一致性验证；目前尚未实现。Host Agent 的最终安装打包方案也需在桌面接入门槛通过后确定。

参考：[pnpm Workspaces](https://pnpm.io/workspaces)、[Turborepo 仓库结构](https://turborepo.dev/docs/crafting-your-repository/structuring-a-repository)、[Bun Workspaces](https://bun.sh/docs/pm/workspaces)、[Bun Node.js 兼容说明](https://bun.sh/docs/runtime/nodejs-compat)。
