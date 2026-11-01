# Source81：P9原回执跨进程只读消费

2026-11-01，阶段56，F51/F53/F54/F55/F56／UI11。当前：**不同PID只读消费限定通过**。本轮只新增Android独立Native验收类，不改产品实现、页面或协议；SDK、安装、每次原生调用及Root完整数据核对分别记证，不关闭P9整体。

## iOS共同断言与平台差异

iOS普通通知生产流程是`TaskNotificationController.pendingTap`→route→`SessionStore.openNotification`，持久资料使用shared JSON／Keychain。`NotificationModels.swift`、`NotificationSettings.swift`与`TaskNotifications.swift`提供隐私、分类及默认声音、准确目标、已见事件与导航交接的参照。本轮共同断言限于准确target、隐私标题／摘要、声音偏好和原件保持。

Android持久化consumed navigation nonce／ACK journal与recovery-v2原intent账本是附加恢复保障；iOS普通通知生产路径未见同构实现，不能将Android两个方法称为同构流程的双端验收。iOS无代码变化或本轮新增验收。Source73原导航10项与Source77中浅／英深组件结果仍按各自指纹保留。准确目标参照NotificationModels.swift:304/408、TaskNotifications.swift:364及SessionStore.swift:1774；隐私／声音参照NotificationModels.swift:167/208与TaskNotifications.swift:259/389。双端源码对照报告SHA256：`acbd7f9b06d65ebc07eb42253f04567875ce589f49e364c919503e2646550b1b`；Root逐项源码指纹及关键路由直接复核：`1a20a6de08000f31c95aa6ba5324d8c4ded44b462beba775745a81a462240d28`。

## 两次分别准入的方法

1. `NativeChatNotificationReceiptExistingReadTest#writerPersistsPendingNavigationAndUnknownRecoveryWithImportedSound`。
2. `NativeChatNotificationReceiptExistingReadTest#differentPidReadOnlyConsumerPreservesOriginalClaimsOperationsPrivacyAndSound`。

两次使用同一新canonical run、不同阶段operation ID，分别启动instrumentation，禁止class合批或重跑原writer。writer先保存不可变intent，拒绝已存在的根或同名alias，仅创建新owned安装资料。consumer须在原writer独立通过后读取同一run，有不同Native PID／process nonce及原writer证明；这不证明旧PID退出、自然死亡、断电或全部FD关闭。

writer使用生产Store的importNormalized导入10628字节合成声音（不验收URI解码、归一化UI、picker、预览或播放），选择默认／分类声音，保存不显示标题及不显示角标的一组偏好，不包含全部隐私组合或实际render。两合成通知分别保留pending导航nonce及已ACK的terminal consumed状态；posted=false，无系统投递。生产recovery journal只reserve一个unknown v2 register原intent，无submit／query／网络；metadata包含一段人工生成的夹具后续草稿串；没有使用真实ComposerDraftStore，不证明实际编辑器草稿冷恢复。

consumer只用`existingKeyOnly`／`existingNamespaceOnly`／`existingReadOnly`读取原namespace的通知、Identity、Recovery、声音与metadata。它不claim、finish、ACK、reserve、settle、同步、重放、导航或播放；唯一新写入是namespace外consumer proof。缺失／损坏或不匹配均失败，不创建默认值、替换key或新操作修补证据。private identity不进入proof。

## 功能、交互与界面核对

| 核对项                                                                 | 本次边界                                   |
| ---------------------------------------------------------------------- | ------------------------------------------ |
| pending原nonce／receipt hash、准确target／thread／operation／lease     | 按writer及consumer Root报告分别登记        |
| terminal原ACK／consumed且无nonce、未知v2原intent及夹具metadata草稿字段 | 同上；不重新claim／ACK或远端查询／重放     |
| 隐私presentation、默认／分类声音、完整10628字节合成声音                | 生产Native读取断言；未实际播放或发系统通知 |
| 安装原记录ciphertext、九stat、原writer／intent                         | 外部完整读回；外部不解密身份或声音         |
| 页面、触摸、AX、系统呈现                                               | 本用例不操作UI，完整矩阵待验               |

`QUERY_NOW=1793000000000`只传给已有接受now的Store API，没有改系统时钟、续租或延长有效期。同一合成基准只读检查原pending及准确expiresAt边界null；不是实际经过TTL、时钟变化或真实死亡验收。未挂载Main、Session、coordinator或全局router，无Host／模型或真实通知／播放。

## SDK及候选

- Native源码：`300a1529d8bb53acbf2fb9910d6aa6205410737886d0e5a197d03602690552bb`。
- canonical：`08d5ebb9042cda81cdf38a3313f98176c88b38a4b75aa593dbb0ec02cd58c45c`（859输入）；expanded：`f5a04fa7595219af15590f4294f6b4352294cb1ed4604036de2ad3e244d045df`（872输入）。
- App：`91aa986821b390771265ca07c2754fea0edcf9b026726875a2752058052eef34`；Test：`728e857806415a365c9ced44ed72bb6767b16e39553f325af6bbebb85f5ac564`。
- Root SDK：`c90fdeee8c8524523b76a0b926620054bffd15c0982cdf2578d8ffb692f8c823`。build-slot／wrapper／pnpmEntry实际退出0，内部约6.38秒；实际／冻结输入、旧13020冻结文件／45APK、三包ZIP／CRC／v2签名及12份SDK原始流完整核对。examiner未安装，SDK不替代Native／UI／真实DEV。

## 分阶段原回执与完整读回

| 阶段     | 本次结果 | 设备＋本地调用 | files／no_backup成员 | 差异 | Root核对SHA256                                                     |
| -------- | -------- | -------------- | -------------------- | ---- | ------------------------------------------------------------------ |
| install  | 限定通过 | 90＋8          | 676／100             | 0    | `9796ccd2755602cb5f4a98c075f65370f0ab44be648f0797217c7b3b4676527a` |
| writer   | 限定通过 | 89＋8          | 686／100             | 11   | `6d800c0df62c63becf08f1e98dbf365a4832e18047207d205baf82fe02e75987` |
| consumer | 限定通过 | 89＋8          | 687／100             | 2    | `0dea8e8c96d8f615af4a9b4bdd447e06deeb6559c0828fcaf078f83524d66a60` |

安装managed工具会话99459实际退出0，supervisor68397、worker68398实际wait0。writer原managed工具会话70401实际退出0，supervisor73158、worker73159实际wait0。原工具回执与journal由Root绑定，不靠成功输出推断外层退出。consumer原managed工具会话87343实际退出0，supervisor77042、worker77044实际wait0。writer与consumer的Native PID分别为31881与7494，process nonce分别不同；所有完整流／原件／九stat与精确差异均由Root独立通过，Native终态或自报proof不单独作为通过。

静态成功图writer为10成员（root、installation、六记录、intent、writer proof），consumer只增加一个proof为11成员。六记录为metadata、通知、声音目录、binary声音、identity、recovery；五text-v3、一binary-v4。预期writer使files676→686，consumer到687，no_backup保持100；预期不替代上表实测。consumer要求namespace七成员树及原字节／九stat保持，只允许proof新增造成夹具根mtime／ctime变化；额外sidecar或原件变化不放宽为通过。

52旧根、47本机APK与两旧FIFO元数据完整保护，FIFO不读内容。stat／regular-only tar按成员动态分片，body≤20KiB，转义后ADB service／argv≤24KiB；实际调用／字节独立重算，16成员／2MiB新增regular预算仅适用于本类。67项纯边界检查只作辅助。

Source80旧安装外层未知、Source79原collector1／tar255及恢复监督／worker退出未知、Source78原失败链均保持，不因新阶段通过而升级。封存未知操作不查询／重放；业务／模型输入仍221，P4 205及另1个封存未知。

## 后续与资源

本阶段登记后继续P7多原件quota／48MiB低堆，采用新独立环境及单独准入；真实row I/O／seal故障、24组完整迁移、语音／未读／生命周期、iOS音频资料缺口继续开放。P9真实Main冷入口、实际OS点击／权限／渠道／试听、低API、OS字体／TalkBack、OEM／供应商及完整视觉／物理DEV单列。任务／项目管理、F75及iOS A6暂缓。

阶段55源`2ba69a87`经PR100合入主线`749ef042`；CI11012 workspace／iOS两项成功，原本地／远端分支已清理。本阶段准确资料／回执因后续完整保护链保留，已替代重复源码／构建与静态中间物按归属及时回收。当前入口无速度／service tier字段，实际速度未核验。未新增用户独立对话或跨电脑协调。
