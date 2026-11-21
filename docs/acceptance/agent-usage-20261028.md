# F73 额度实现与验收记录

任务开始于 2026-10-28；跨午夜验收继续记录实际结果。用户已确认 UI 修订 6 并要求实现。本记录仅覆盖新功能 F73，不恢复其他 A6 暂停项。正式 Host、既有手机版本和 EasyWork 正式服务未替换。

## 已实现

- 公共 usage v1 合同、严格 Schema／语义校验、精确十进制、独立额度 bucket、余额／窗口／总量／扩展指标、来源／有效期和不可用原因。
- Codex 元数据通道的 account/read 与 account/rateLimits/read 只读方法；读取前后核对来源，Host 提供鉴权、机器目标隔离的 GET /v1/usage，响应 no-store。没有新建／恢复／提交模型轮。
- EasyWork 标准适配器和固定 usage/read 桥接增量，使用桌面账户摘要余额服务；标准分发及增量指纹见 [集成目录](../integrations/easywork-usage-v1/README.md)。
- KMP 统一校验、精确余额摘要、数量比例、窗口选择、过期和未知类型投影；iOS／Android 接入各自原生传输及内存缓存。客户端列表与侧栏显示，设置固定左侧，右侧用量环／两行余额位于收起按钮左边。详情保留原始精确值、适用范围、来源观察和重置时间。
- 仅可见页面且前台时按 30 秒轮询，同一目标合并并发读取，普通刷新至少 5 秒。额度缓存与会话／草稿独立，连接／目标变化使用不同键，迟到响应不覆盖另一个目标。

## 首轮证据（历史快照，最新结果见后文）

| 项目                                  | 结果              | 范围                                                                                                                      |
| ------------------------------------- | ----------------- | ------------------------------------------------------------------------------------------------------------------------- |
| Node 协议与适配器测试                 | 通过，7 组        | 同一跨语言向量、严格非法数据、十进制、真实窗口时长、来源切换、固定 EasyWork GET、旧元数据服务不支持原因保留               |
| KMP JVM 测试                          | 通过，89 项总测试 | 含新增额度向量和精度／目标／过期断言；是辅助证据                                                                          |
| iOS 模拟器构建                        | 通过              | 原生源码与共享框架，不代表真机通过                                                                                        |
| Android debug 构建                    | 通过              | 原生源码与共享框架，不代表 Android 真实额度通过                                                                           |
| EasyWork 桥接与控制器测试             | 通过，21 项       | 新增固定 usage/read 的严格请求及目标错误拒绝                                                                              |
| EasyWork 全仓 typecheck               | 本次通过          | 最新远程主线基线及锁定依赖通过；早期依赖漂移失败见下文                                                                    |
| 实际 Codex Host GET /v1/usage         | 通过              | 本机真实桌面元数据，HTTP 200、no-store；返回 7 天窗口和 balance，无 5 小时窗口；实际余额及目标仅留私有证据                |
| iPhone 独立 App build-for-testing     | 早期候选通过      | 准确独立 bundle，保留其他 App／数据；最终源码须重新构建后再验收                                                           |
| iPhone XCTest 首次执行                | 环境失败          | Runner 启用自动化模式超时，业务测试尚未执行；不归因锁屏，不当成功                                                         |
| 原生 iOS 模拟器实际 Host UI           | 通过              | 独立 iPhone 15 Pro Max 模拟器，真实 Host 数据；侧栏两行余额／环、窗口与余额详情、客户端列表、设置及收起按钮独立点击均通过 |
| 实体 iPhone 重试                      | 环境失败          | 明确激活准确独立 App 后，Runner 再次启用自动化模式超时；未执行业务断言                                                    |
| EasyWork 原 Electron／手机 LAN／Relay | 未验收            | 不以构建或测试替代原认证余额结果                                                                                          |

全部实际额度、Host Key、设备身份、完整 xctestrun 和原生截图仅保存在私有验收目录，不进入 Git。后续结果在本文件追加，不重写历史失败。

## 修复与复验

原生 UI 验收发现 iOS 连接 JSON 的默认键顺序不能用作稳定缓存键，现已排序规范化；同一客户端两处展示实际数据通过。详情采用可滚动列表，验收会滚动到下方精确余额后检查。零、微小非零、负余额与大额的摘要由共享字符串规则处理，详情仍保留原始值。

全量检查过程同步了读取上限参数对应的旧 Swift 测试桩，并修正其既有 systemRoutes 桩；下载和网络读取消辅助回归分别通过。默认系统 developer directory 指向 CommandLineTools，后续检查仅对本任务设置 DEVELOPER_DIR 使用已安装 Xcode，没有修改全局 Xcode 选择。物理验收的 xctestrun 在私有目录复制时先修正 TESTROOT 展开，再记录上述自动化模式超时；没有据此推断锁屏或要求重新配对。

EasyWork 增量补丁已在准确当前现场通过反向 apply --check，证明差异与该现场匹配；未向其他电脑分发，也未重启其正式 Electron。完整源码前后摘要位于集成 manifest，真实余额与会话标题不进入公开记录。

最终主仓库 `pnpm check` 完整通过；双端构建、KMP JVM 89 项及额度 7 组边界测试通过。补充的 EasyWork 控制器回归连同桥接共 21 项通过。源代码指纹与本次证据分类见 [结构化记录](mobile-evidence/agent-usage-20261028.json)。临时 Host 与独立模拟器在证据导出后关闭并清理；独立实体验收 App 保留，自动化可用后须按最终源码重新构建再重试，正式 App 和服务保持。

全量检查通过后，仅补充了共享百分比原值文本的极值防溢出处理；针对最终源码重新执行 KMP JVM、双端构建及原生实际 Host 点击复验，不把此前结果冒充不同指纹的新结果。

## 2026-10-29 双仓库交付复验

用户明确要求两个仓库都创建 PR 并合入各自远程主线。Agent Hub 以 Gitea main、EasyWork 以 Gitea master 为基线分别隔离交付；原工作区其他未提交内容保持。

早期将四处类型错误记为“既有错误”不准确：新增 workspace 后的一次非冻结依赖安装把锁定的模型依赖升级，造成模型循环类型不匹配。本次恢复原锁定依赖，仅增加额度 workspace 解析项，冻结安装及 EasyWork 全仓 typecheck 已通过；未通过修改模型循环源码消除报错。

本次独立原 Electron 已通过原桌面认证服务读取 https://51easyai.com 的真实账户余额，并与既有 Workbench 账户摘要独立核对。手机 Relay 使用显式 loopback DEV 设备身份；该拓扑不代表生产 OIDC 或公网 Relay 部署验收。读取额度不提交模型轮。只有余额、没有窗口时，两端无障碍朗读包含余额及单位，不补百分比或错误提示。

最新远程主线基线复验：主仓库 `pnpm check` 完整通过；KMP JVM 95 项、Android debug 构建通过；EasyWork 桥接／控制器 22 项及授权失效边界 2 项通过，类型检查通过。标准运行时 7 个产物在独立导出目录重建后指纹完全一致，增量补丁在目标工作树反向检查通过。

本次真实 EasyWork 原 Electron → 原生 iOS 模拟器的 LAN／Relay UI 场景全部通过：两处展示、实时精确余额、设置固定左侧、收起位于右侧以及独立点击均通过。桌面独立记录两种传输上的 `usage/read` 成功回执；没有提交模型任务。活跃钱包可随其他业务消费变化，详情断言使用同一桌面实际返回的额度快照值，不要求整个测试期间余额固定不变。Relay 配对前重新生成临时配对码。

实体 iPhone 本次独立签名／安装通过，CoreDevice 确认为已配对 Wi-Fi 连接。XCUI 再次在自动化初始化超时，未执行其业务断言；随后应用内原生 XCTest 已实际执行，网络调用明确返回 `Denied over Wi-Fi interface` 与 `Local network prohibited`。已激活专用“Agent Hub 额度验收”并请求人类开启其无线网络及本地网络权限。该项目前为阻塞，不记为通过；两个仓库尚未提交合并，保留验收工作树等待复验。

## 2026-10-29 真机复验通过与主线整合

系统联网拒绝解除后，配对的物理 iPhone 15 Pro Max 上独立原生 App 的应用内 XCTest 实际执行并通过（1 项）。手机返回的 snapshotId 与原 Electron 独立记录的 `usage/read` 回执准确匹配，精确余额逐值匹配；额度页、余额组件及客户端列表均在真实设备渲染并保存截图。该证据覆盖生产原生读取、共享投影及视图，触控流程由已通过的原生模拟器 LAN／Relay 场景单列覆盖；不把历史 XCUI 初始化失败改记为真机触控通过。整个额度验收没有提交模型任务。

两个仓库已分别保存额度功能提交，并整合远程当前主线。合并保留最新 Host 诊断、Android 和语音实现，不覆盖原工作区的其他未提交内容。Agent Hub 合并后的类型、构建、额度测试及全量 `pnpm check` 通过；KMP JVM 95 项、Android debug、iOS 模拟器及实体设备构建通过，LAN／Relay 点击与实体 iPhone 应用内读取在整合后再次通过。此后仅为本地联调启动器补充原 Electron ABI 准备，针对该脚本的格式、lint 和真实启动通过。EasyWork 完整推送门禁继续按交付输入核对。

## 2026-11-12 W5 源码核实与轻量回归

候选基线 `276bf420b8545b9c1389fac857586c469bc8eff9`，分支 `codex/android-parallel-20261112-w5-usage`。本记录是具名证据，不是当前进度总表；唯一当前计划仍由主会话维护。

Codex 的 `account/read`、`account/rateLimits/read` → `GET /v1/usage`，EasyWork 原桌面认证账户摘要 → 固定 `usage/read`，以及共享 KMP、Android 两处原生展示均已有生产路径。读取没有新增模型输入。来源实现以 `packages/codex-adapter/src/usage.ts`、`packages/easywork-adapter/src/index.ts` 为准，本线未修改 Host、Adapter 或正式配置。

### 本次修复

- 共享投影不再将合法的 bucket/metric ID 用 `/` 拼接：`a/b` + `c` 和 `a` + `b/c` 原会撞成同一个详情行 ID。改为 JSON 字符串对编码，两端共同受益；wire ID 和数量原值不变。
- 共享投影直接限制 `observedAt <= now + 120s`，避免 sampledAt 与 observedAt 的两次时钟容差叠加，让未来约四分钟的值显示成当前数据。原 observed/sample、valid/observed 检查仍保留。
- Android 详情同时保留精确余额与 `entitlement=unlimited` 的“无限额”说明；原先只显示余额。iOS 已有相同权益说明，本次无需修改 iOS 视图。
- Android 缺省 bucket 标签使用指标标签，不再输出字面量 `null`。

### 辅助验证

使用已缓存 Kotlin 2.4.21/JDK17/JUnit4/serialization 1.11.0，直接编译实际 `DomainContract.kt`、`UsageRules.kt` 和实际测试，未运行 Gradle、完整 Android/Xcode 构建、模拟器、服务或真机。

新增共享测试在修复前运行 7 项、失败 2 项，分别精确复现 ID 冲突和叠加时钟容差；修复后 7 项通过。最终加上 Android 纯展示回归共 10 项通过（编译和执行约 4 秒）。Android 三项只抽取生产文件中的原样纯函数并使用 JVM org.json 20250517，不证明 Compose、Android Framework 或真机通过。测试源码保存在 `UsageRulesTest.kt` 和 `MobileUsagePresentationTest.kt`。

共同回归保留跨语言 9 向量、60/24 位精度域内的字符串原值、零/微小/负/大额、超额百分比、零分母、未知子类型、多个单位与窗口、首个真实窗口选择、过期/已越重置时刻仍不本地重置。全部是辅助证据。

### 历史证据与未关闭项

历史来源见 [2026-10-28/29 记录](agent-usage-20261028.md)及[结构化证据](mobile-evidence/agent-usage-20261028.json)。Codex Adapter、usage Schema 与共享向量当前仍匹配历史 sourceFiles 指纹；当前 KMP/双端视图已变化，历史构建和 iOS/来源实测不能替代当前 Android 原生或真实 DEV 验收。原 iPhone 自动化初始化失败、后续 App 内读取通过及物理触控未覆盖仍分别保留。

代码覆盖仍有明确差异，不能据本次小修认定 F73 全部已实现或整项关闭：Android 详情没有手动刷新及不可用原因，双端未完整显示来源 scope/plan 和负余额“欠额”，Android 读取并发没有可见行最多 2 的总闸。另需专项核对读取异常时缓存来源连续性与认证失败撤下旧值，现有 catch-all 会保留旧样本；此线未改身份或传输策略。

后续由总控分配独占原生时段：Android 运行本次三项原生方法，并覆盖真实 Codex/EasyWork 只读读适配、客户端列表/侧栏/详情、切目标、断网缓存、过期/未知、长数值/多窗多单位、浅深/大字号/TalkBack及独立按钮。真实 DEV 只读核对准确目标、snapshotId 与原桌面同次响应；不得为验用量新增模型任务。共享规则变化还需对应 iOS 构建及具名辅助/原生复验；不恢复 A6。

本次未运行 `pnpm check`、完整原生构建和设备验收，原因是明确的磁盘与独占时段限制，不记产品失败，也不记通过。产品/设计共享文档的 F73/UI02/UI03/UI12 更新由总控合并，避免跨线整文件采用；W5 交付附精确建议。

## 2026-11-12 R2：补齐具名缺口的源码候选

在本轮冻结基线`0e3b1ccc61bdbfd7ed27b9a6fe4cd547ce3d7867`补Android详情只读手动刷新/不可用原因、双端来源scope/plan/精确负余额“欠额”。共同KMP投影增加本地debt标志和失败/节流规则；保留原ID、时间容差、数值+无限权益及null桶标签回归。没有修改usage v1 wire、Host或实际设备授权。

Android与iOS均由Store提供进程级两槽；同目标summary/footer/details合并，最后一个消费者离开才取消，隐藏的排队请求不会进入transport。完成仍检查原读取代际，取消后迟到的旧返回不能覆盖新样本。Android另修TargetScope对象字符串不稳定导致的相同机器重复读取；只作用usage键，不扩展Codex账号拒绝条件。

严格区分HTTP401/403与200内某指标forbidden。当前失败wire没有source，Store不把同endpoint或历史scopeRef当作来源连续性证明，撤下旧数值并显示连续性及实际失败原因；合法partial快照保留兄弟指标。已有有效样本过期仍按原时刻标缓存，不归零、不本地重置。

定向检查直接执行实际KMP和Android Store控制流、宿主Swift Store并调用真实编译KMP策略。旧Swift源码复现force越过5秒、HTTP401旧值留存、缺两槽及隐藏排队读取四条失败断言；修复后同类反例、同目标合并/一消费者取消、旧回调、来源/目标变化、过期和单指标forbidden均作具名辅助验证。它们不是手机Framework/Compose/XCTest或真实DEV通过；具体方法数、源码指纹、原失败与恢复日志以本task W5/r2-f73交付记录为准。

当前暂不提交/推送；产品和设计关联已同步，唯一progress由总控更新。全量Gradle/Xcode、安装/设备与真实来源场景待总控独占时段；本线没有发模型或操作真实grant、登录/Host配置。已完成源码不得据此升级为整项或实际部署通过。

R2原生方法准备：`EasyWorkUsageStoreNativeTests`的6个`testF73*`直接调用App生产`UsageStore`，仅注入读取边界与测试时钟，已登记到`EasyWorkNativeTests`；未复制Store。Android `MobileUsageUITest.footerDetailsRefreshReasonsSourcePlanAndDebt`渲染实际生产footer/dialog并调用实际Store，准备刷新/来源/套餐/精确欠额/HTTP401/单指标forbidden与缓存断言。原生执行结果以冻结候选的后续具名证据为准。

R2 Android本次结果：完整JVM/assembleDebug/assembleDebugAndroidTest正式构建通过，29 suite/138 JVM测试零失败/错误/跳过。专用空白API33 AVD首轮12方法中11通过、1 UI驱动因从App上下文读取test-only fixture而在业务断言前失败，原失败保留；仅改测试资源上下文，产品断言不变。后续完整同12方法（Store7/展示4/实际Compose1）12/12、零失败/跳过，核原始AndroidJUnitRunner方法集合与OK输出；3张原始系统截图已读回，覆盖来源/套餐/精确欠额、HTTP401撤下及partial合法兄弟项。该结果是原生辅助/合成读取，不是真实Electron或模型，也未触碰实体手机/原grant。iOS6方法仅准备/语法和target登记，正式iOS构建及实际运行由总控合成后另验。

总控限定本页UI返修：在已通过12/12后，原英文图仍含F73页的固定中文标签；现将summary/footer/详情及AX标签（包括all-of/any-of、缓存/部分/Unavailable、完成）接通原双语资源，新增“用量/Usage”回退键，provider标签/原始值/单位不翻译。保留前两次冻结/原输出和旧包证据，最终英文UI的完整12方法另记；KMP源码不变时138项按准确指纹复用。

英文最终复验：冻结3的App/runner正式构建通过，同一API33独占空白AVD完整12方法通过，包含英文title/Updated/Applies/All models/Provider rules/Observed/Resets/百分比说明、Any-of/All-of、Cached usage和Done的实际查找/点击/刷新。第一次英文12方法虽通过，原图被该AVD Bluetooth进程崩溃弹窗遮挡，未计无遮挡视觉通过；原图和系统失败保留。仅在该空白AVD用svc关闭未用蓝牙并关闭其崩溃弹窗，不全局隐藏错误；同一冻结3、同一App/runner、同12方法再次12/12零失败/跳过，3张原图读回确认无浮层且固定标签为英文，源数值/单位/provider标签保持。源KMP与此前正式138项逐文件SHA匹配复用，不因文字改动重跑JVM。iOS6生产Store方法仍待总控合成后的正式构建与运行；未操作实体、真实grant或模型。

稳定身份追加反例与最终候选：旧真实Android Store对非空Codex配对及从持久JSON重建的EasyWork连接各新增1方法，均原生失败（期望1读取，实际2），原源码/hash/runner日志保留。修正连接确定性编码与生产副作用key后，同独占API33 AVD完整15方法15/15、零失败/跳过（Store9/展示4/Compose2，runner 6.45秒）；新增生产summary/footer/details真实重组在3次metadata重建期间保持1flight、0取消并正常发布。pin/routes/systemEndpoints及EasyWork credentialRef/account/peer变化隔离仍通过。新App/runner独立读取已安装包核对SHA，三张新原图已读回无遮挡，旧12项结果与原失败不替代新候选证据。JVM138仅按KMP准确源码匹配复用。

iOS新增第7个生产Store原生方法，非空Codex配对的legacy account变化仍合并/保缓存/遵5秒、真实fingerprint变化隔离且原Client不变。宿主实际Store抽取加KMP桥接13辅助断言通过、7方法宿主严格类型检查通过，仅为辅助；iOS正式NativeTests执行由总控合成后交W4，不冒称本次XCTest通过。实体手机、真实Electron/来源/DEV四格、原生主题/字体/AX完整验收继续未验；未操作真实grant/model。冻结4及最终交付source/package指纹以本task W5/r2-f73外部证据为准。
