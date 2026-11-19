# Gitea Actions 运行指南

依赖安装保持 `--frozen-lockfile`，下载超时设为 300 秒、并发为 4，安装步骤最多 20 分钟。工作区检查和 iOS 构建分别先记录目标卷可用空间，低于 4 GiB 时停止本阶段新增写入。构建前从已安装的 Turbo 包解析本平台二进制，直接执行并核对版本；缺失或不可执行时明确失败，避免 Turbo 启动器在构建步骤内调用 npm 静默补装。此设置用于处理慢速下载，不改变锁定版本、完整检查范围或原生构建要求。

Gitea 的工作区使用 Linux，会跳过 macOS 专属 Swift 回归；因此 macOS 的 iOS 任务在构建后补跑建议读取全套与下载原生边界检查。GitHub 的 macOS 工作区仍在完整 `pnpm check` 中执行这些检查。建议回归的每个原生可执行用例有 120 秒上限，受控请求不依赖固定等待窗口。上述检查是辅助证据，不代替真实手机或原桌面场景。

GitHub 同步任务总时限为 30 分钟，容纳完整源历史与公开父提交的初次读取；发布仍使用原独立公开父历史、来源祖先检查、普通推送和远端 SHA 核对。超时没有发布确认时先读取 GitHub 实际状态，再决定恢复，不能把任务提交或重试当作同步成功。

Relay 原生通信夹具以服务器观察屏障结束空闲轮询：先核对两批信封的真实 ACK，再观察三次空闲请求，保持最后一个 HTTP 请求未响应，由 Swift 取消并独立核对连接关闭。轮询间隔仍使用生产真实时钟，保留 500 ms 预算及服务器观测的 450 ms 下限、准确 ACK 快照和关闭后读取拒绝。旧夹具在固定等待 1250 ms 后要求总请求数为 4～5；首个空闲响应受控延迟 1500 ms 时，三次复现均只有 3 次请求并触发原断言，即使频率符合限制也会误报。当前夹具可用 `EASYWORK_RELAY_FIXTURE_IDLE_DELAY_MS=1500 node scripts/easywork-relay-transport-native-smoke.mjs` 重验慢响应（允许 0～5000 ms）；控制屏障请求限时 10 秒、原生执行总计限时 20 秒，失败输出实际次数、间隔及 ACK 状态。该修复只调整 macOS 本地 Swift/HTTP 辅助检查，不改变产品轮询逻辑，也不等同于真实账号或手机验收；GitHub 原失败日志未记录次数，无法据此还原当次调度或网络延迟。

工作流保留在 `.github/workflows/ci.yml`，由 Gitea Actions 读取。当前工作区任务使用明确配置在 Linux 容器上的 `agent-hub-linux`，原生构建使用 `macos-latest`；旧工作流还使用 `ubuntu-latest`。这些名称都是任务匹配标签，Gitea 不会因为名称自动创建 GitHub 托管机器。仓库及组织均没有可用运行器时，任务保持 queued，尚未执行任何检查。

## 运行环境

本仓库使用 Gitea Runner 5.2.2，按 Linux 和 macOS 两类环境注册仓库级运行器，不注册为实例级运行器。每个实例独立保存身份、工作区和工具缓存：

- Linux：美国 Gitea 部署中的仓库专用容器运行器，例如 `agent-hub-linux-us-gitea`。每个容器以 UID1000、2 CPU／3 GiB、非 privileged 模式运行，不挂载宿主 Docker socket；`agent-hub-linux:host` 在这个隔离 Linux 容器内部执行，每个实例容量为 1。扩容先核对整机负载和内存，使用独立身份及数据卷，避免并发写同一个工具缓存或 pnpm 安装目录。
- macOS：`macos-latest:host`，在安装 Xcode 的 Mac 上原生构建，容量为 1。

与 Gitea 同机的 Linux Runner 在各自 Compose 中将 `git.51easyai.com` 映射到 `host-gateway`（`extra_hosts`）。继续使用原 HTTPS 域名、证书、Nginx 和仓库认证，仅将同机 API／Git 访问改走 Docker 到宿主机的内网路径；外部 macOS Runner 和其他访问方保持原解析。更新时逐台通过正式 API 禁止领取新任务，等待已有任务自然结束并复核空闲后，只重建该 Runner 容器，再恢复领取。保留原身份、挂载、工具缓存、标签和资源配置，不重启 Gitea 或数据库。验收需核对容器实际解析／连接地址、TLS、私有仓库未授权拒绝和真实 CI 检出；回滚只移除该映射，按相同空闲流程重建。

网络较慢时可用 `scripts/ci/Dockerfile` 构建专用小镜像：`docker build -t agent-hub-ci:node24-bookworm -f scripts/ci/Dockerfile scripts/ci`。它基于锁定摘要的官方 Node Debian 镜像并安装检查所需工具；标签可配置为 `agent-hub-linux:docker://agent-hub-ci:node24-bookworm`。不得把本地镜像可用写成服务器已部署或检查已通过。

完整检查还执行原有 stdio 研究夹具，其进程路径和 bundle 版本读取原先依赖 macOS CLI。Linux CI 镜像安装 `research-posix-tools.py`：仅在明确启用、仓库匹配、私有且同 UID 的 `transport-poc` 配置存在时，为该夹具的 `ps -p PID -o comm=` 返回真实 `/proc/PID/exe`，并从配置对应的真实 XML／二进制 `Info.plist` 读取版本。它不回显配置中的期望路径或版本，不提供写入／任意 plist 读取；其他 `ps` 调用仍执行保存的原 procps 二进制。代码只安装在 CI 容器，不安装到电脑／服务器宿主，不声明 Linux 原桌面接入能力，不跳过夹具。`research-posix-tools.test.py` 覆盖真实内核路径、错误 PID、实际版本与预期不同、格式损坏、范围及原工具透传；原 `desktop-stdio-entry-fixture.mjs` 的十五项断言保持不变并单独执行。

从 [Gitea 官方下载站](https://dl.gitea.com/gitea-runner/5.2.2/) 下载与运行器电脑匹配的二进制并核对官方 SHA-256。以该版本的 `config generate` 和 `register --help` 为准。每个进程独立保存注册身份，通过仓库的注册令牌和 `--token-file` 注册；令牌、`.runner` 和运行器配置均保存在私有目录，不提交到 Git，不打印到命令行或日志。

服务安装在临时检出之外，由登录自启动服务持续运行。Linux 服务必须明确指定自己的 `DOCKER_HOST`；创建或恢复专用 Colima VM 时使用 `--activate=false`，避免切换开发者的默认 Docker context。容器保持非 privileged，不挂载 Docker socket，不开放任意主机目录。macOS 运行器仅领取本仓库可信代码的任务，不复用 Host Agent、Codex 或手机的登录凭据。

为每个进程配置独立工作区、工具缓存和日志目录，设置容量 1、有限任务超时及优雅退出期限。运行器端保存一周任务日志，并仅在回环地址开放健康端点。启动服务时仅传入所需环境，避免继承桌面执行器的变量。

原生环境需要有效的 `DEVELOPER_DIR`。工作流安装 Node、Java 17 和 Android SDK；Xcode 构建通过 Gradle 生成共享 MobileCore。命令行默认 Xcode 或 Java 路径为空时应修正运行器环境，不应跳过共享核心或把原生构建替换为静态检查。运行器使用独立 Android SDK 和 Gradle 缓存；预热工具必须核对官方归档摘要。`setup-java@v6` 将 ARM64 缓存目录规范化为 `aarch64`，不要沿用 Node 工具缓存的 `arm64` 名称。网络代理只继承已有、核实可用的本机配置，不继承 Codex 执行环境。

## 干净检出与历史提交

`pnpm check:mobile-parity` 导入 `packages/protocol/dist/index.js`，因此工作流在完整检查之前显式运行 `pnpm build`。不依赖上次任务留下的 dist 或开发工作区缓存。Node 版本和 pnpm 版本分别来自 `.node-version` 和 `package.json`，依赖使用冻结锁文件安装。

大会话夹具在启动运行时读取之前准备完整 70 MiB JSON 报文，随后仍通过真实 socket 发送、解码和分页。合成 owner 与 follower 共用 Node 事件循环，不能把测试数据的 JSON 构造耗时计入生产读取的 1 秒期限；服务器 CPU 配额和 GC 会使这种耦合产生偶发降级。原有 live、分页、未知操作不重放和被动读取期限断言均保留，日志分别记录编码与实际读取耗时；产品期限和旧提交源码保持不变。

Windows 预备读取的合成夹具也在 Linux 执行。测试元数据变化围栏时，同长度重写后显式推进真实文件 mtime 并断言变化，避免同一文件系统时钟 tick 内重写而未产生预期元数据变化；原失败断言保留。另一个 `same-stat log rewrite` 用例继续验证元数据完全相同时，提交阶段仍须通过完整内容校验拒绝变化。两类边界分开覆盖，不修改生产读取或安全规则。

Task Hub 围栏测试在所有平台直接核对真实围栏文件均拒绝启动；入口在 macOS 继续断言围栏错误，在其他平台断言原有的“不支持主服务平台”拒绝，保持生产平台限制。旧提交的这项测试无条件预期 macOS 错误，无法在 Linux 原样通过。需要回补旧提交检查时，使用明确命名的临时 macOS 运行器领取旧 `ubuntu-latest` 标签，准确记录实际 OS，不能宣称旧检查在 Linux 通过。当前 `agent-hub-linux` 任务始终由真实 Linux 容器执行。回补窗口由负责人协调旧标签的领取归属，保留运行记录，结束后注销临时运行器并恢复旧标签的常规匹配。

已合并的旧提交不能通过改写历史补上工作流步骤。必要时可为明确列出的旧提交配置 `scripts/ci/legacy-bootstrap.bash`：运行器以 `BASH_ENV` 加载该脚本，并用私有配置中的 `AGENT_HUB_CI_LEGACY_SHAS` 指定完整 SHA。脚本只对本仓库、准确 SHA、缺少协议构建的 `pnpm check` 补跑 `pnpm build`，然后执行原完整检查；构建失败直接失败。所有构建仍使用实际检出的源码。容器只读挂载该脚本；当前工作流自身完成构建，不使用此兼容分支。日志须记录旧提交的前置构建，不手工写入成功状态，不更改原提交或检查断言。

Android SDK Platform 37 的实际包 ID 是 `platforms;android-37.0`，旧工作流的 `platforms;android-37` 会导致 SDK 安装失败。当前工作流使用正确 ID。对同一明确 SHA 白名单，可在独立 CI SDK 的 `bin/sdkmanager` 放置 `scripts/ci/legacy-sdkmanager.bash`，将该目录未经修改的官方启动脚本保存在相邻的 `sdkmanager.real`。兼容程序只把这个旧参数映射到真实官方包，记录映射，然后调用原工具并传递其退出码；不改 SDK 元数据、不跳过安装、构建或失败断言，不修改开发者原 SDK。固定命令行工具版本，升级后重新核对原工具摘要并安装该兼容程序。`legacy-bootstrap.test.mjs` 在 macOS 和 Linux 验证两种兼容处理的范围、参数和失败传播。

## 核对与恢复

工作区 Job 在完整 `pnpm check` 前选择移动端一致性比较基准：PR 使用事件中的准确 base SHA，主线 push 使用 `before`，并通过环境文件传入后续全部检查。公开仓库保留独立快照历史，不要求它包含 Gitea manifest 中的历史提交。手动审计可指定 `mobile_parity_base`；省略时优先保留可读取的 manifest 全量基准，公开开发分支使用与 `origin/main` 的共同祖先，公开主线使用前一快照。明确指定但缺失的 PR、push 或手动基准直接失败，不降级为只检查最后一次提交；没有可比较历史时也须补齐基准。`mobile-parity-base.test.mjs` 用真实独立 Git 历史核对多提交 PR 范围、环境传递和失败边界。

1. 通过 API 读取仓库及组织运行器，确认标签、在线状态和容量。queued 且没有 `runner_id`、没有开始时间表示尚未领取，不能视为测试失败或通过。
2. 核对 PR 最终 head 和远端 `main` 的准确 SHA，分别读取对应 push、pull_request 运行及各 Job 的结论。PR 的分支检查不能代替合并后主线检查。
3. 运行器上线后，从任务日志区分下载 action、准备工具、依赖安装、构建和断言失败。下载或工具链失败先修复环境，再重跑原任务；保持失败尝试和日志可读。
4. 仅取消已被新提交取代且尚未执行的本仓库旧任务，不打断其他项目或正在运行的任务，不删除历史。Gitea 1.27 的 runs DELETE 接口实际删除运行；`tea actions runs delete` 的 cancel 别名也不是保留记录的取消。取消应使用平台提供的取消操作。
5. 以服务器实际运行日志、执行时间、运行器和 success 结论验收，记录 run/job 链接。`pnpm check` 与 iOS Simulator 构建分别保留结果；模拟器编译不代表真机验收或发布成功。

## GitHub 公开主线同步

Gitea `main` 推送后由独立工作流发布到 GitHub `main`，每小时补查，保留独立公开历史。部署密钥、来源指纹和核验方法见 [GitHub 同步指南](github-sync.md)。工作流与源码检查分别记录结果。

公开 macOS 工作区中的下载原生回归先构建共享 iOS simulator framework，再创建 HTTP 和模拟器夹具。Kotlin/Native 冷链接包含工具链准备，独立预算为 20 分钟；Swift 编译仍限 120 秒、实际下载断言仍限 60 秒。原先 3 分钟的构建子进程期限曾在链接阶段触发 SIGTERM；这属于构建未完成，不是下载或完整回归通过。所有真实共享身份、完整目标、缓存版本、取消和错误拒绝断言保持，不用现成 framework 存在代替本次 Gradle 构建。

PR3 公开 macOS 的独占模拟器准备与下载业务断言分列：冷 boot/bootstatus 共用10分钟，listener就绪由主机和模拟器独立nonce健康请求核对，错误健康标记必须拒绝且下载计数保持0；仅健康阶段的瞬态传输错误有界等待90秒。原19处Swift业务前置条件、完整下载场景、120秒编译和60秒业务执行保持；fixture元数据请求使用与生产路由一致的专用ephemeral session。失败保留阶段/连接/请求计数、迁移输出尾部和无敏感值代理诊断，不绕过失败或自动重放业务。产品及1201原生源不变，Android冷会话精确源匹配复用。 首轮公开回环请求超时不能据此归因于权限或代理；单次受控job重跑在LaunchServices数据迁移中撞到旧120秒bootstatus期限，作为独立准备失败保留，不当作下载回归通过。健康预检仅诊断当前fixture连通性，不保证后续业务请求；所有原业务断言仍须真正执行成功。

2026-11-15 PR3后续：公开003bfd5的模拟器冷准备、nonce正负向及完整下载断言已通过，workspace后续在Worker旧budget用例失败：1800ms预算预先等1680ms后，调度超时可在发帧前耗尽，正确的零请求被旧“必须1帧”断言拒绝。后续仅修夹具：真实等待到过期（含2400ms调度超限场景），核对未修改的真实时钟决策、原1800ms总预算、零帧/零Native等待和退休；其他在途未知失败严格原计数、750ms批次和300ms跨帧预算断言保持。它不改变生产期限，也不把Mac辅助检查升级为Windows原生验收。准确候选与最终CI回执继续关联Gitea PR181及其后续修复。
