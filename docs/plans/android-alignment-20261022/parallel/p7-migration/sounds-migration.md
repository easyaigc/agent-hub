# P7 接入冻结 F53 声音迁移

依赖精确提交 `f9e935b37f3f49e5a689f34397163a63ab13dc82`，在共同阅读/通知迁移提交 `77458db4ce68501df868c5c23976dff2a197736a` 后合入。合并保留已验证依赖的 `mayPresent` 锁内重查和事件去重；声音生产者仅合入该冻结版本，没有另建迁移器。

`NativeMigrationSounds` 将 `native-chat-notification-sounds-v1` 和 `native-chat-notification-sound-v1:<UUID>.caf` 接入原有导出、候选和激活图。目录、必需文件集合和音频分别使用 F53 提供的 `validateEncoded`、`binaryInventory`、`requiredBinaries`、`validateBinary`。严格 JSON 入口先检查重复字段、深度和 UTF-8；未来版本、未知字段、无主二进制和损坏文件拒绝整个操作。

活动条目的音频必须存在；staged/retired 描述符原样保存，允许其音频尚未写入或已删除，存在的音频必须完整有效。导入与冷读不调用 `settlePending`，不激活 staged、不重新选择 retired。内置三个声音由版本化 APK 资源提供，迁移不复制另一份内置声音或设备密钥。

每条归一化 WAV 最大 1,278,944 字节、单声道 22050 Hz/16 bit、29 秒。文件流入口先校验头部目录和清单，音频在目标加密暂存后按每次一条的有界数组交给生产验证器，全部通过后才发布唯一候选头；同 ID 重试校验原候选和输入摘要。不会同时解码整个声音库，不提高既有 8 MiB 文本头、192 MiB 聚合二进制或 256 MiB 外部文件上限。描述符最大容量不等于聚合满额成功。

当前通知偏好只能引用 default/none、三个冻结内置 ID 或目录中的活动 ID。缺失、已退役、尚未提交或未知引用会拒绝导出/候选。源设备必须明确选择默认或其他有效声音后重新导出；迁移不自动替换。该修复只改变声音偏好，原始事件和未知操作回执不变。历史审计中的旧声音引用是非执行原文，无需重新指向活动声音。

激活沿用共同迁移的通知关闭、新偏好/注册代次、空 lease、不可点击旧 ticket、永久事件去重墓碑和原文审计。源 URI、授权、provider 身份、播放器/音频焦点、系统渠道和通知权限均不作为迁入资料。目标 URI 由现有声音库使用目标 applicationId 与经过验证的归一化摘要生成；系统 provider 注册、实际跨进程读取、通知声音和送达由主统筹另行验收。

`NativeMigrationSoundTest` 的 7 个原生场景已随 app/runner 编译，尚未在本线设备运行。没有 ADB、安装、instrument、播放、通知、Host 或模型操作。Android 与 iOS 的声音格式、URI/权限和恢复路径仍分别验收；本阶段不修改 iOS，A6 保持暂停。中央功能/设计/验收登记由主统筹合并本目录 delta，不把已有 iOS 或 fixture 结果计为 Android 通过。
