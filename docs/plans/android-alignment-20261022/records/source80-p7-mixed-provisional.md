# Source80：mixed v1/v2惰性原件目录迁移

2026-11-01，阶段55，关联F47/F55/F56/F64／UI21。新增一个Android独立原生验收类，产品实现／协议和页面不变。SDK辅助门及本轮独立API33单方法Native／完整数据核对限定通过；不关闭完整P7、UI或物理DEV。

## 范围与iOS共同断言

已有生产规则读取既有惰性目录，再合入当前未完成原件；本用例补“既有v1原件＋新未提交audio窗口”的组合覆盖。只有一份逻辑记录`migration-provisional-voice-v1`，其JSON version从1变为2；不是两份目录，也不改变文本v3／二进制v4封装。旧row/index保持原文，旧项显式`uncommittedAudio:null`，新项保存准确audio descriptor；两份raw与一份audio分别计量／核验。

共同断言是完整原件及准确目标／操作身份保持，导入不授予发送、重试、URI、录音或通知权限；原未知结果按原回执核对，不盲目重放。iOS Payload已有SavedSession、引用附件和通知／声音范围，但未包含PendingVoiceStore音频／metadata，缺口继续单列。Android的惰性目录不是iOS同格式或双端完整迁移通过的证明；本轮iOS无代码变化或新增验收。

## 选定方法与本轮断言

`NativeMigrationMixedProvisionalVersionTest#v1InertCatalogAndSeededLiveAudioRoundTripAsOneV2Catalog`。

四个角色分别用于v1来源、激活v1后加入新窗口的mixed来源、mixed目标及reexport只读核对。六个export／stage操作ID预先保存在不可变intent，两次激活各用不同新候选／目的地／原回执；最后reexport只stage，不第三次激活。原结果未知时保留原件并核对，不重跑方法或另造候选修复证据。

| 断言                                                                                    | 当前结果                     |
| --------------------------------------------------------------------------------------- | ---------------------------- |
| v1旧原件、原row/index及完整raw；v2旧null＋新descriptor、排序与三份binary共393305字节    | 本次限定通过                 |
| 六类坏图拒绝：降级v1、漏旧null、伪旧audio、删旧row留raw、audio别名重映射、v2全null      | 本次限定通过                 |
| 不可变操作、submission／draft-file原文与后续独立草稿保持；恢复只读／uncertain、无重试权 | 本次限定通过                 |
| 目标无旧活动voice row／audio／raw；完整原件及九stat保持；reexport原回执可读             | 本次限定通过                 |
| 所有页面、交互、AX和系统呈现                                                            | 本用例无UI操作，完整矩阵待验 |

新窗口明确是seeded的binary写后／row前状态，没有调用真实seal或Source78具名checkpoint；两次激活在同一方法内warm完成。上述本轮限定结果不证明真实row I/O故障、断电、进程死亡、旧PID退出或48MiB低堆。

## 当前候选与SDK辅助证据

- 新Native源码SHA256：`234592704bb171643f96b961d4600aae69d4782800070e9ef4b1eb36e51e75dc`。
- canonical：`fb52f454ae8085399395fd3527f65ea30423a6ed8b8e9afe6ffe06090d7375a9`（858输入）；expanded：`7633ad0ddb8cc91b560de74eea2920e714cef665e69b3c683151e8683d402257`（871输入）。
- App：`91aa986821b390771265ca07c2754fea0edcf9b026726875a2752058052eef34`；Test：`b10ffdc8ea03f7e906e3a764241e3970b6214f72cfbbb22ac168826288ecfe22`。
- Root SDK核对：`a7a40eef3363d4b99dc982f417e755daa7d5ad5eb9c450f78dc634c173538294`。实际build-slot／wrapper／pnpmEntry均退出0，内部构建约9.83秒；实际／冻结输入、三包完整ZIP／CRC／v2原签名、12份SDK原始工具输出、42旧APK及12149旧冻结文件保持已读回。
- SDK只证明构建、包身份和这些输入／保护门；不计Native、UI、真实Host／模型或物理手机通过。examiner未安装。

## 本轮执行与完整数据核对

本次只执行一个新method，实际方法终态0、新worker及正式工具会话退出0。Root独立重算89个设备调用＋8个本地保护调用，共97收据／194完整stdout与stderr流，全部实际退出／EOF／字节数／SHA、动态stat／tar分片、完整9stat、51旧根／44APK与两FIFO元数据保持。97新成员含20目录／77 regular，连同parent恰98差异；完整files676／no_backup100。三份envelope、九份proof、三张原stage回执、两代激活、旧row/index与后续草稿逐项核对。Root证明：`423a02f75cf948cdcd444e0748d9195e8135a10b94093fc7853302dfccd1b8d8`。80项当前采集／恢复纯边界检查为辅助证据。

安装阶段两installer明确成功，90设备＋8本地的98收据／196完整流及完整579／100前后数据经Root核对，差异为零；原worker实际wait0，但调用方记录代码异常导致managed工具会话／外层退出回执丢失。该外层仍unknown，安装执行不升级通过，Root证明`ed1bd5c2ab4a49544908db9b712879a14d10892918dd0956ceb4947f180541c8`。原PID不存在和fence实际可取得后释放仅为具名本地观察，不推自然退出0。没有重复安装；Root以严格绑定原回执、已核安装状态、原目标和下一独立run的许可继续新Native，存在失败／未知业务结果或保护不完整均不得续行。新Native原始外层退出0另外取得证明，旧未知字段原样保留。

regular-only tar与stat按完整成员动态分片，单body≤20KiB、转义后ADB service／argv≤24KiB；实际调用总量独立重算。旧FIFO只核元数据，不读取内容；未枚举Keystore别名，外部读回不解密ciphertext，认证内容和六类坏图断言依赖固定已编译Native实际执行。examiner未安装，无Host／模型／手机输入或UI操作。

## Source79只读恢复依赖

原target／replacement方法Native终态0，但原tar255／collector1及76份原收据保留，原Native没有重跑。Root独立读取原76收据／152流与恢复104个已知退出0的读取子操作／208完整流，并完成全部tar／九stat／proof数据级核对：files579／no_backup100，405／73个regular，4＋1 tar分片两次采样共17320960字节；P3到当前恰为两套9成员夹具及parent共19项差异，51根／41APK／两FIFO元数据保持。Root证明`de8434fe7c33a833026fdf7105806ea761d03f3d6eb78ebad3effe84ee5503db`。

`currentDataReadbackVerified`及`fullOriginalMethod4DataReadback`为true，但`currentCaptureExecutionAccepted`为false；监督／worker实际退出回执缺失，两项exit仍unknown，accepted P8组保持3。当前进程不存在、fence独立可取得后释放不等于自然退出0。不能把旧397完整捕获升级为496、四方法完整通过或将恢复采集器写成成功。

后续Source80可以此数据级基线准入，仍须完成新阶段完整前后保护。采集器按有界分片regular-only tar和完整stat设计，不读旧FIFO内容；静态成功图97成员（20目录／77 regular），184仅失败诊断上界，不能放宽通过。单tar旧调用量预估作废，实际请求／字节／总数必须重新封闭。

## 后续与保留项

本阶段限定结果登记后提交安卓专用分支，再推进P9消费进程冷读及P7多原件quota／48MiB低堆；Source81方案语义已审但未采用／构建。不同PID只证明不同调用身份，不证明旧进程退出或真实死亡。

完整24组、真实seal／row I/O、系统原声、未读／生命周期、真实通知、完整界面／TalkBack／OS字体、API26／API37／物理DEV和iOS音频资料缺口继续待验。任务／项目管理、F75原生项目UI及iOS A6保持暂停。业务／模型输入保持221，P4为205及1个封存未知原操作；封存操作不查询／重放，其余未知只按原回执核对而不盲重放。

PR99的阶段54源`dd087e2d`已合入主线`c9b6edd3`，CI10996的workspace／iOS均成功，原本地／远端阶段54分支已清理。当前候选和构建目录仍是本阶段具体回归依赖，待阶段验收／交付后按准确指纹回收已替代副本；原失败、未知回执与必要证据继续保留。实际速度／service tier未核验。
