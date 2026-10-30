# Windows 私有状态验收的 PS5 子进程环境补充

本补充以 PR79 冻结包 `host-connect-native-first-directory-20261031` 为唯一运行输入，ZIP SHA-256 `439463b0c1c69e885a4d64ca3712c0227883283a774caaef991a5dc5e9fd2e51`。不重建、不替换 Host／Adapter／Native 或 bundle.json，不为文档追踪后续运行 tip。规范源码仍在 Gitea main 合并及现有官方 GitHub 同步链交付。

## 来源实测范围

来源 Windows 已完成该包真实普通 Main Native 预检：退出0、11630ms，实际五组件和八资源匹配；backendClosed、targetMetadataUnchanged、callerBootstrapUnchanged 均为true。首次全缺失后缀的产品 makePrivateDirectorySync／protect 与独立 PS5 先 warm BCL 再加载同一 C# 的 create-directory／check 场景也通过：退出0、6227ms，目录 User owner／protected，预算未改。其输入、进程与正式保护快照由来源独立核对；这些是相应场景证据，不代表所有 CLI／serve 单元测试或全部更新恢复通过。

官方 `windows-private-state-acceptance.mjs` 的原始 SHA-256 为 `e2cfe7adc7397364a76df70fa240199f1e58cdd31a9d3828d4c052c7d5af6749`。来源第一次 inspectLegacy/Get-Acl 出现 Microsoft.PowerShell.Security autoload 失败，尚未到首次 RuntimeSession 和 16MiB 附件场景。具体环境／系统根因未确认，不能把该失败计为产品缺陷或完整 legacy 通过。原失败保留；独立 warm 场景使用正式环境 API 时 Get-Acl 成功。

## 最小源码差异

只修改验收脚本：从既有编译 Adapter 导入 `windowsPowerShellEnvironment`，给两处 PS5 execFileSync 传入该 API 返回的子进程环境。该 API 克隆父环境并排除不分大小写的 PSMODULEPATH，由 PS5 构建自身模块路径；不修改父进程环境，不输出环境值／凭据，不临时改 Windows 全局环境。所有原 PS 命令、JSON／断言、timeout、状态转换和安全边界保持。原 runtime.json、Admin-owner 历史／叶附件、实际 resolve/download、owner／DACL／ctime／字节／引用保持断言不删不改。

源码差异、脚本新摘要、PR／实际合并／公开提交、补充 ZIP 与基线运行指纹均由本次补充 manifest 绑定。仅脚本补充不包含运行组件；原五组件、Native14 和预检8始终从上述 PR79 冻结包解析。新补充不能被当作独立 Host 发行版安装或用于恢复较旧运行目录。

## Windows 使用与验收

1. 独立核对补充 tag／源码树／ZIP／manifest 摘要，再核对原 PR79 ZIP、实际五组件和八资源与已通过证据完全匹配。发现并行正式变更先按来源实际基线对账，不覆盖程序或配置。
2. 在任务专用的 PR79 隔离解包树中，只以新脚本替换 `scripts/windows-private-state-acceptance.mjs`。替换前必须匹配原脚本 SHA，已是新 SHA 则不重复写入；其他值停止。保留原脚本的私有回滚来源。不能直接从补充包单独执行，因为其相对 imports 依赖原完整 PR79 程序树。
3. 用原 Node 的正常 Main 入口执行 `node <isolated-program>/scripts/windows-private-state-acceptance.mjs`，工作目录仍为任务独占目录。无需在目标安装 TypeScript 或整套 dev dependencies，不使用 Node --test 的未加载原生单元 case 代替此入口。
4. 确认完整 legacy 验收实际到达 runtime.json、首次 Session、真实 Admin-owner 叶文件及16MiB产品 resolve/download，保护 owner／DACL／ctime／字节／引用。没有运行的分支仍未验。原未进入产品代码的 import URL 错误和模块 autoload 失败记录保持。
5. 来源继续真实只读 reader／launcher 的既定可执行项，保留 legacy 状态门禁及每次 PID＋启动时间＋映像＋父进程核对。正式 R10、原桌面／CLI、Native config／observer／retention、身份／证书／设备授权／草稿／回执均不因该补充改变。六路 HTTP 成功仍仅证明该时刻可用。

Native／首次目录／warm BCL 的匹配输入证据可以复用；新脚本的 PS5 Get-Acl、完整 legacy、reader 和 launcher 通过必须由来源实测补齐。macOS 仅做脚本语法／环境隔离辅助，不冒充 Windows 模块加载通过。回滚只恢复这一验收脚本；无需回滚、重启或重装 Host，不能回写旧配置或删除私有数据。最终若同步正式安装的 Skill，只按同一新脚本及摘要同步其相应副本，保持所有运行字节。
