# Source75：未提交 audio 的 v2 惰性迁移

2026-11-01，阶段50，关联 F47/F55/F56/F64、UI21。Android 已实现本窗口的明确格式和闭合原件投影；API33 本轮 新四场景4项限定通过、原两项warm2项限定通过、纯内存契约3项辅助通过。当前 7/7 次 Native instrument、9/9 个 selected 方法有对应独立核对；不能据此计算 P7 完成率或关闭 24 组完整迁移。

## 实现与双端影响

旧路径在音频 binary 已写入、原 row 的 manifest 尚未提交时拒绝导出。新路径保留原 row／原 index 字符串、raw 文件和已认证已落盘 audio；只有 raw 为常规原文件，且两份大小／SHA-256 完全一致才投影。raw 与 audio 分别进入两个 binary frame，均占用既有 quota，不合并成一份或绕过计数。

同一惰性 catalog record 显式支持 version 1／2；含未提交 audio 时输出 v2，每项具有 `uncommittedAudio`（旧项可 null，至少一项非 null），新 descriptor 只包含 size／SHA-256，必须与 raw 描述一致。v1 读取保留；未知版本反向样本改为 3，因为 2 现有严格定义。所有声明与帧集合精确相等，继续拒绝缺帧、额外帧、别名、非法 descriptor、活动身份冲突及未知字段。

目的设备原件留在惰性 catalog，活动 voice index 不恢复旧 recorder；新 `copyUncommittedAudio` 仅只读复制保存字节，不补 manifest、不 seal、不激活旧录音，不恢复上传／重试权限或重放原操作。这里的“原子激活”指迁移代次发布，不是恢复活动语音。

iOS 当前 `EncryptedMigration.Payload` 未包含 PendingVoiceStore 的音频与 metadata，同类完整语音迁移仍有缺口；本轮没有实现 iOS 对应格式或新增 iOS 通过。迁移 UI21 与语音界面的产品交互、token 和文案未改，完整双端 UI 仍待验。

## 指纹与安装

- SDK canonical：`f978ee4cc54eafe24c58eac3c73d0a996f5728ba821b09eda295c7bb07c2938d`（855 输入）；expanded：`5451233618ec2ec09caeb50f3f5bd0f07d3abe2cf1c3038ee7ec6170df95765b`（868 输入）。
- App：`12c96cc8b4b75568d5a9f6ee917c363b52bea8ef4958fe4a7702ac8a469363fc`。
- Test：`9f78eb9e6334bf9f98a8efb74f9df4b752c52f0df0d320bf7f9dda6e18a3922b`。
- 主统筹 SDK 核对：`949e06e3f85ba220a087434508df8257c420d935bf0b740093b5d99ae9c34baf`；实际 Gradle 退出 0，全部实际／冻结输入、三 APK ZIP／CRC／单 v2 签名及 12 份 SDK 工具流读回；旧 24 APK 和 6934 份冻结源码保持。
- 安装主统筹核对：`8e22089c0cf3be81dd550311b55a4ad7e5c99e8cdf25b444bbd0920532cd226c`；92 次设备、8 次本机守卫共 100 捕获。准确 App／Test 更新，原包身份、权限与原资料按项核对；无卸载、清数据或密钥删除。

## 逐场景验收

每次 instrument 固定 91 次设备与 8 次本机守卫，原始流、完整类型化目录、regular 原件字节与九 stat 由主统筹独立读回；当前 693 份 Native 捕获。表中前四项均是显式构造已落盘、未提交的状态，证明 JSON 固定 `stateSeeded=true`、`realSealCrashProved=false`。不把 warm 实际迁移流程等同于真实 seal 在该指令点崩溃。

| 场景                                                                     | 结果               | 范围                   | 主统筹核对 SHA-256                                                 |
| ------------------------------------------------------------------------ | ------------------ | ---------------------- | ------------------------------------------------------------------ |
| 合成已落盘未提交状态：export／stage／activate／re-export保留两份惰性原件 | 限定通过           | 合成状态，真实生产路径 | `e2fadefb063896d32df555ea1d521822d124bd7eefbc58e8df75c5d967373ac5` |
| v2 descriptor、帧和活动身份冲突：14类反向拒绝                            | 限定通过           | 合成状态，真实生产路径 | `1acea3e4c4be3cc523d6978052848bfa36c5ea235d5125c31b8f732981220e0f` |
| raw与已落盘audio不一致：拒绝且不发布／不改原件                           | 限定通过           | 合成状态，真实生产路径 | `ad4c5ce817dcc970f34774cc8ef5a55a6dff83f242b8564a7bfde06df4dd43a5` |
| 已独立认证的旧存储binary v2缺少已提交row alias：拒绝且不猜测             | 限定通过           | 合成状态，真实生产路径 | `97ad5cf0ea90246543f9917b3cd2ba9bb4b3bbafd47a3e063cde9799e8b0e0f0` |
| 原未封口完整流式候选、原子激活和后续草稿保持                             | 限定通过           | 原 warm 实际流程       | `7b959ba92d3795d8214262040ca93d59740294db55a10d16a9288fbe4335a085` |
| 原损坏／未知schema及中断激活拒绝                                         | 限定通过           | 原 warm 实际流程       | `553ace1306efc98d309b8c573db3ad839dfeab3f4b704d1b06fba19155585236` |
| 三项纯内存生产契约：状态／schema／身份／逐帧完整性                       | 辅助通过（纯内存） | 纯内存，不是迁移流程   | `1d873ba7497eacce5a20c1e3f0456765bed02c91dce6cf3ddb4bb785a5fef97a` |

新建原件在迁移前后的比对由核定源码中的 Native 断言执行；外层独立读取后置完整字节，并逐项复核旧保护根的前后字节与九 stat。

新 roundtrip 实际执行 export／stage／activate／re-export，逐份比较惰性 raw/audio、原 row/index、源密文、原文件九 stat 与后续独立草稿。schema 项逐一核对 14 类反向；mismatch 项核对导出拒绝和原输出 sentinel；legacy 项先独立认证合法旧存储 binary v2，再证明缺 row alias 时仍拒绝，不能把损坏头拒绝当成这个场景通过。旧 fullstream／corrupt 为各自原 warm 场景；最后三方法只运行纯内存契约，不新增真实迁移／发布或进程恢复证明。

原基线 31 个准确受保护根继续保持，六个迁移夹具逐个加入后续保护，全部完成时为 37 个准确根；旧两个 FIFO 仅做元数据核对，不读取内容、不供 peer、不删除。已知旧失败、全进程 FD 观察和未知操作保留原状态。本次不增加模型输入，不查询或重放封存未知操作；不宣称全部私有 handle 或物理 executor 终止。

## 仍未验收与后续优先级

- mixed v1/v2 原件合并／多录音组合、双份 frame 的累计 quota／48 MiB 低堆与满额边界。
- 优先：实际 seal 的 row 提交失败窗口与冷读取；真实 checkpoint 中断／崩溃、原进程退出后的冷 PID 恢复及迟到 callback／writer 故障矩阵仍待验。
- 全 24 组迁移资料和子存储、iOS PendingVoiceStore 音频／metadata 缺口及跨端规则／格式。
- 产品导入导出、只读原件／语音 UI21 的完整交互、主题／字体／方向／无障碍，以及真实手机／桌面／模型 DEV。

任务和项目管理、F75 项目原生 UI 及 iOS A6 继续暂停。其余待验单列，不阻塞独立开发；原件、当前 APK／冻结输入和准确模拟器仅按后续保护／冷恢复的具体依赖保留，阶段中已无用途的可重建产物按归属回收。
