# 阶段117：三方核心优先与原回执身份校验

日期：2026-11-07。用户要求每个功能同时对齐iOS/Android及EasyWork等桌面来源，核心功能优先；多工作线并行，总控验收、合并和收尾。当前首功能未完成在线验收，保持活动，不宣称整项通过或直接进入下一核心功能。

本档保留签名构建完成时的阶段快照。随后实际执行的iPhone只读失败、当前源码Android 18项组件结果，以及计划PR164主线交付/清理，另存[阶段118](stage118-three-way-native.md)；最新状态见[总表](../../android-ios-alignment-20261107/progress.md)。

## 实际来源和并行

已只读拉取AgentHub `origin/main 7f67c371bb4378a1d4f07ddc25b96b3d229eda71`和EasyWork `origin/master e393fcc72015dd613ca41d93469c6c3a8c64054a`。EasyWork通过Git对象读取准确提交，未覆盖较旧工作树或另一独立WIP。当前契约和近期限定first-parent提交见[上游来源](../../android-ios-alignment-20261107/easywork-upstream.json)；首次缺少可核验单一旧pin，未宣称EasyWork全仓逐提交已审完。

五条工作线为来源/契约、核心控制、阅读/UI、原生验收、矩阵/交付，实际入口最多总控+3执行，轮转运行。五个初始调研子任务均直接完成，未递归分派；测试由独立执行方完成。请求和实际模型/推理从正式运行记录核对，入口未提供速度参数，速度未核实。未创建正式侧栏聊天，亦无正式聊天归档工具；不能冒充已归档或改写官方历史。

计划调整为核心连接/聊天/原回执、实时完整展示与控制/审批、附件/语音、未读/生命周期/通知、完整迁移、其余增强及冻结交付。每项仍执行功能/交互/界面三门，按[四格范围](../../android-ios-alignment-20261107/provider-matrix.md)登记；共通/专属/不适用/暂停有依据。原75F、迁移24组、所有旧失败和UNKNOWN保留，不把EasyWork仅留在N5，也不让组件完成代替完整功能收口。

## 已证实差异和本次实现

W2发现iOS原回执在归一化前缺少可选wire target/host校验，Android已有。当前iOS补齐：捕获client领域目标通过产品既有`readingTarget`映射与会话原传输目标核对；对command/status外层、response.result、queue/status逐层校验可选account/设备/workspace/runtime及host类型/值。wire host对应`desktopDeviceId`，domain host仍是本地client key；字段缺省或NSNull兼容旧Host。原operation/message/thread、原队列查询、缺result仍uncertain、授权拒绝及只读不重放语义保留。

W3另核实Android EasyWork子代理未接共享投影、公共投影两处`takeLast(65536)`会丢前文；已排C2首批，当前未实现/未验。其余发送/队列/审批已有生产入口，不能按旧文档把已实现能力再列缺失。`command/status`由EasyWork外层命令服务处理，内部bridge拒绝不代表端到端缺接口。

## 检查与失败保留

- 首轮提取实际方法的Host Swift fixture 52/52通过，但测试简化类型掩盖了领域目标/传输目标不能直接比较的问题；真实iOS编译在该guard失败。失败日志保留，首轮辅助通过不抵销原生失败。
- 主会话修复为产品已有映射；fixture改用真实`AgentRuntimeTarget`及原映射，再跑当前R2：52/52、0失败，约5.64秒。覆盖三层合法/旧null、authority/subject/设备/workspace/runtime、非法类型/host/本地别名、捕获owner以及原未知队列恢复；核对只发原查询，无execute/send。它仍是辅助验证。
- 当前R2物理iPhone目标`build-for-testing`签名构建成功，应用签名校验通过。独立附件扩展缺provisioning已通过正常Xcode自动签名恢复；未安装候选、未启动XCTest，不声称真机运行通过。候选App可执行SHA-256为`74188411ec1f6e3cd030fe71b4f9c8a03e56806c4855b5c996f5bb4003e65136`；测试bundle可执行为`8027ed99a6baba8be2323feb5ee4dfcab2999c8dff87083a2e7e880309ab7567`。
- 原DEV记录进程均已退出。当前旧Electron不能证明同一target/profile/原owner，未向其猜测发送请求。原数据库只读查到唯一原operation及thread/turn；它不是在线结果，也不能把数据库completed直接当归一化回执的期望state。已请求当前独立DEV地址/私有配置路径。

补充实际网络复测：依据原私有配置，原Embedded Host端口TCP返回Connection refused且无监听；原Relay/observer代理仍存活。原主机名DNS失败，核对本机代理owner后用原hostname/SNI及配置证书严格校验，TLS和Relay health通过。此结果不是绑定/桌面owner在线证明；只读bindings/presence需要已鉴权设备凭据，未使用未知凭据。旧observer硬编码历史PID不当权威在线证据；全程无业务写操作。

## 当前验收状态

| 子场景 / 格             | 当前结果                                                                                        |
| ----------------------- | ----------------------------------------------------------------------------------------------- |
| iOS×EasyWork C1-RID     | 生产修复、R2辅助及签名编译通过；物理原回执只读、UI和在线DEV未执行，保持待验                     |
| Android×EasyWork C1-RID | 已有生产守卫未改；本轮源码对照，不借iOS结果填通过，旧原生证据按指纹复核                         |
| iOS/Android×Codex       | 此wire子例为EasyWork专属；Codex执行路径未改，基本聊天骨架四格仍按各自协议验收，不虚填此子例通过 |
| 完整C1 / 75F            | 未整项关闭；本轮矩阵是范围，未增加完整产品通过数量                                              |

当前C1-RID代码不具备正常合并交付门槛；三方计划、矩阵和调度文档独立交付主线，不增加产品通过数量。源代码提交`867b0f2ba82c356aee1c264c88db8d378b1fe457`已推送，创建[草稿PR #163](https://git.51easyai.com/BCAI/agent-hub-app/pulls/163)，正式回执确认draft=true/当前不可合并。格式、parity覆盖及6项验证、52项回执辅助和新链接/75×4范围检查通过，15个基线已有失效链接单列未改。CI按PR实际状态独立核对；CI通过仍不合并，待真实DEV证据完整后再按正常Gitea流程交付、删分支。

## 资源

本次未停止任何共享Host/Electron/其他任务模拟器、未清手机数据、未发送模型输入或重放写操作。原生构建中间/模块缓存、SourcePackages已精确清理，保留约208.5MiB签名候选/测试运行文件与最小失败/检查证据，待准确运行实例恢复后复用；本工作树约39MiB当前iphoneos MobileCore产物单列依赖复核，不整目录复制备份。未合并源分支/源码仍需待验，不提前删除。完整路径、SHA、留存原因、释放条件和24小时内复核点保存在本任务私有资源台账；正式配置/系统签名资料和旧唯一证据保护。
