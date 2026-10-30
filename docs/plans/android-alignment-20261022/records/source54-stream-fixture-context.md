# Source54：流式验收的准确安装目录

2026-10-30；基于安卓检查点 `9e482d73`。本阶段只修验收夹具的 Context 选择，产品迁移、原件保护和 iOS 实现不变。

## 已知失败与修正

Source53r5 的完整流式方法首次实际执行得到 JUnit -2、overall -1、ADB 0；父侧完整保护相等且锁已释放，不能记为迁移通过。首次导出在 `NativeVoiceRawOwnership.requireRoot` 拒绝，尚未暂存或激活候选；后续 owned 分析因目的存储未建立而失败，分别保留。

夹具用 canonical `coordinationKey` 对 lexical `Context.noBackupFilesDir.path` 做字符串前缀匹配。API26 中 `/data/data` 与 `/data/user/0` 是同一安装的两种路径表示，该判断误选 destination Context。修正为父目录 canonical 相等，并要求 source/destination 中唯一匹配；不放宽生产 `requireRoot`、锁、只读规则或六个方法主体断言。旧 run、日志和原件不改写或重跑。

## 证据与待验

失败 lease：`b73d9ea0-35a5-4213-b32b-e8cffa4462a2`；独立 fixture run：`e4c70f10-269f-4fd5-b3ce-f2f8ac1920a9`。summary SHA-256 `d157023f6511c218ae7386e0aa8ba523ef7df8e7f776569c5eafb97b323cc2f6`，原 Native stdout `bb9a399aabc384ba3a56a0685234aca3635c39799990fa74ac6a2e9243ba1671`，前后保护 snapshot 均为 `95006f5d4ab3cba5b63b294c680a9f39a50d2a6ed063c80a4698eccec238437d`。准确平台与构建见[阶段范围](../parallel/fifty-fourth-stage-stream-fixture-context.json)。

修正后 SDK、格式/一致性及三包签名/Manifest/完整源码读回通过，783/792输入；App与examiner完全相同，仅runner改变。新编号六个Native方法均得到唯一方法0、overall-1、OK1和完整默认保护；其中五项父侧完整原件闭合。完整迁移/后续草稿、损坏/未知schema/中断、原候选writer、另一PID激活、第三PID原件与幂等冷读分别通过。刻意符号链接方法只有Native与默认保护通过，独立NOFOLLOW元数据闭合仍待验。Source53 既有15次有界通过按原指纹保留，不自动承接到新 runner。刻意符号链接项单列 metadata 采集待验；原5566 unknown不查询、重放或恢复。API37原cold-router、Source52父PID、API26模型实际安装/识别、UI与真实DEV均保持各自待验。真实Host/模型输入仍为221／P4 205及1条unknown。

任务/项目管理与 iOS A6 暂缓。旧unknown的原worker和资源继续保留；本阶段只保留必要失败原件、当前包及新验收依赖，执行资源按阶段收尾。

## 当前有界结果

三段链实际PID为8717、9466、10015；每次接续前，父侧从完整kernel PS核对前一PID及目标主/子进程缺席，并核实际shell readproc组与/proc可见性，不通过停止/安装制造退出。候选JSON原字节、原row、完整131073字节raw及全部源密文在三段间相同；激活后冷读的完整目的密文树与激活回执相同，Native断言后续草稿保留。Root分别独立核原始调用、成员清单、完整tar内容/哈希/footer和原回执关联。准确摘要见阶段JSON，不能扩大为完整P7或真实DEV通过。

退出诊断首轮因API26 legacy id输出格式明确失败；第二版只在主机异常输入检查中暴露正则性能问题、未执行设备；第三版改为固定前缀和有界数字分词，原失败与未执行记录保留。没有重放writer、重复暂存或原业务操作。六项均known终态并释放锁；旧5566 unknown仍原判。

本轮是API26 512MiB堆的新独立场景，不构成48MiB满额、API37、物理手机或非空默认数据证明。后续Source55使用独立冻结/包，不继承本次Native通过。
