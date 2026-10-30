# Source64：发现、连接请求与阅读夹具归属

状态：Source64r2 组合 SDK、完整源码读回及三 APK 验证通过；Native、系统交互和真实 DEV 尚未执行，不沿用 Source63 构建或 Source62 原生结果为本候选通过。

## 本阶段范围

- 发现协程与连接表单由原 Session 的同一 IO 账本持有。请求执行器、取消断连和正常 finally 竞争同一连接句柄；取消先登记清理，真实执行与断连退出后才清空归属。
- 复用现有 NSD 生命周期对象，stop 只是请求；旧 discover/resolve/network 句柄保留到实际终态。新一代不抢占旧句柄，失败/超时不冒充闲置。迁移等待协程与 SDK 两条边界，原超时和未知不重放规则保持。
- 九处 Session 接线覆盖发现准入、准备冻结、拆除、退役，以及发现连接/已有连接/二维码的目标与代次检查。MainActivity/NativeSheets 无整文件替换；原账号历史元数据策略保持，没有新增登录账号拒绝。
- 阅读夹具把实际 assertion worker/线程/Executor/Future 归属单独交给 fixture，退出未知时保留准确句柄、原存储和 key，不为结束测试退役存储。清理步骤先持久化意图，再记录真实已完成/未知状态；不重试清理，首个异常保留并附加退出失败。
- 主统筹另外保留了发现的原两类错误文案，并修正 Store Gate 关闭时 secondary exception 覆盖 holder 首错的问题。生产 UI 页面、token 与原交互流程无新增变化。

## 检查与退出门槛

子会话报告的 32 项 IO JVM 辅助和 11 项阅读辅助只作为交付线索；主统筹已做独立源码审查和逐文件摘要核对，不能替代本组合 SDK/Native。后续分别核新 APK、真实 NSD/HTTP 生命周期、实际 Store reset、账本 worker 异常，以及菜单/长字段/系统生命周期逐项交互。

Private-claim 惰性审计 codec 仍是另一独立接线项，尚未开启生产迁移。P7 完整 24 存储组、原声、完整系统/UI/物理/真实 DEV 均未关闭。任务/项目管理和 iOS A6 保持暂停。

## 独立环境与原声待验

API33 独立环境完成五次固定只读采集：205 个完整包列表中三个产品包均不存在；记录了精确 boot/AVD、原始 PS/AM 与系统正例。独立审阅仅认定原样本未检出 active instrumentation，不把采集完成当 Native、App/Test UID 自然离场或下一阶段自动准入。

原 API26 的原声流程保留原记录：revision1 仅一次 discovery，已知 Pending，0 Native/0 原件读取；原始 proc SELinux 标签恰带单个终止 NUL，Enforcing/角色没有改变。严格解码修正的 revision2 通过 12 项辅助，但历史 processes dump 的 dumpAll 尾部格式尚不匹配，因前置条件未执行新 wrapper，0 新设备调用。原 WAV/key/回执均保持，不用重录或重放补验。

Source63 已交付 `4a5bb90f` 的 SDK/DEX 证据保持；仓库主线新增 PR65 仅 Host 协调工具，没有新增移动源码对齐范围。本轮主线尚未纳入 Source64。

## 首次构建失败与窄修

首次 Source64 构建实际 exit 1，16.13 秒，两条流完整、无截断；两处测试夹具引用公开 SDK 未提供的 `O_DIRECTORY`，未冻结 APK 或执行 Native。后续候选改用共享的测试目录同步 helper：公开 `O_RDONLY/O_NOFOLLOW/O_NONBLOCK` 打开，核 `fstat` 的目录类型/本 UID/0700 与当前 `lstat` 同一设备、inode、模式、UID，再 fsync；不跟随链接、不重试清理、不改变业务行为。原失败独立保留，新输入另行冻结和构建。

## 修正后构建与核对

Source64r2 一致性与文档检查通过；canonical 830 / `41777c46`、expanded 843 / `9297b7fc`。新构建实际 exit 0，17.8778 秒，stdout 146,485 字节、stderr 0，双 EOF 且无 cap/reader error。App `3329b85d`、Test `9288a36c`、examiner `f6c813b5`，同一 v2 证书；主统筹独立读回全部 843 个当前/冻结文件、完整三个 ZIP/CRC 与九份 SDK 原回执。首次验签缺授权变量在创建输出前退出，补变量后完成，不覆盖旧结果。

尚无本候选 Native。API33 后续安装与 11 项纯 fence/受控 IO 单方法工具正在准备，需按本候选指纹与各阶段回执独立准入；不触碰原 API26 声音或旧未知操作。源码复核、离线辅助、SDK、原生与真实 DEV 分别记证。
