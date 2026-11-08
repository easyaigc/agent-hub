# Windows 受保护 Host 增量升级

此入口适用于已有 `windows-host-regular-installation-v1` 安装。它使用本仓库从干净提交冻结的 Windows 运行闭包，不是 macOS `ditto` 生成的完整跨平台 Skill ZIP；原跨平台打包及 macOS 安装入口保持不变。部署记录必须同时保存 source commit、runtime manifest SHA-256 和本机验收回执，不能把 Windows 结果复制到其他电脑。

先执行构建与相关检查，从干净提交运行 `node scripts/windows-host-regular-runtime.mjs freeze <node.exe> <私有操作目录>`。冻结包包含同一提交的程序、Node 和依赖闭包，不包含身份、密钥或聊天。安装先验证完整清单，在同卷私有 staging 创建全部文件、再次验证后发布到按摘要命名的目录；已有目标必须完整匹配，不能覆盖未知部分。

`node scripts/windows-host-regular-upgrade.mjs prepare <runtime目录> <manifest SHA-256> <原launcher路径> <原launcher SHA-256>` 只准备候选及私有快照，不停止服务。保留 Host ID、状态、配置路径、端口和已有环境配置，候选只剔除已退役的 Windows Responses 转发设置。原 launcher 实现有变化时拒绝此入口，不能隐式重编译或修复旧安装。原 launcher 路径与摘要须来自已核对的实际私有安装，不能来自任意下载或未核实的命令行。

检查准备结果后用 `apply <operation目录>` 切换。入口核对旧指针、配置和 Task 快照，先停止准确旧进程，再保持 Task 的触发器、Settings、Principal 和安全描述符，只替换 Exec action。配置与指针分别采用原子替换及实际备份摘要核对。它们不是一个原子事务，也不对非协作 writer 提供内核 CAS；每阶段有独立 intent 和确认回执。启动后验证认证 HTTP 的原 Host 身份，以及监听端口对应的新 child／supervisor／launcher 三层 PID、启动时间、用户、会话、路径和命令。

`inspect` 可查看准备中途失败的阶段；仅在尚无 stop intent 且原指针与配置未改变时，`abandon-preparation` 可解除本次准备锁，保留产物与回执。进入切换后先用 `status <operation目录>` 核对实际状态，不自动重放。`rollback <operation目录>` 仅接受仍匹配本次旧／新摘要和 Task 的未完成事务，停止准确服务后恢复原文件及 Task。若用户或 worker 的正常启动恢复已改写配置，摘要冲突会保留现场，不能覆盖这些变化；需先核对具体差异。已成功结束的操作不会被旧 operation 自动重放或回滚。

升级和回退只会清理有归属回执的旧 Responses URL。若入口移除了该行，它会在停止 Host 前结束本次尝试；按[退役说明](runtime.md#windows-原生建议-responses-转发退役)正常重开 Codex 后，再从同一事务继续。曾准备的旧计划可查询或回退，带旧转发设置的计划不能继续应用。回退也剔除旧设置，保留原备份与回执。

升级准备只读核对原状态的 Host 身份。配置与回执保留 2 MiB 上限；包含聊天缓存的 `runtime.json` 单独允许至 64 MiB，使用保留的文件句柄及有界读取，读取期间变化则拒绝。不会缩减、迁移或改写运行状态来适配升级器。

辅助覆盖包括运行包闭包、大状态身份与预算隔离、并发配置摘要、独立 Scheduler XML／安全描述符保留和真实隐藏进程执行。完整 protected CLI 和实际服务升级须在目标机单独验收。
