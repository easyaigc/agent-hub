# Source78：真实 seal 具名中断后的原候选三 PID 核验

2026-11-01，阶段53，关联 F47/F55/F56/F64／UI21。本轮仅新增 Android 独立原生验收类，产品算法、Source75 v2格式和界面不变。SDK、writer／activate原件与原publication、第三PID只读audit均经主统筹独立核验，本范围限定通过。安装100份及三个Native方法各99份，共397份原始捕获全部读回；writer与activate的两个原collector exit1保持，audit原collector退出0。完整P7仍未关闭。

## 流程与不重放边界

writer使用独立synthetic PCM调用真实seal，标准化后的131116字节WAV及已加密audio已写入，row提交前的具名callback抛单次已知IOException。原row/index保持，raw保护基线为seal标准化之后；不能声称seal前raw字节保持，也不代表实际row I/O失败、断电或进程死亡。

writer仅导出、暂存一次，保存原候选、原回执和不可变证明。第二个原生调用仅以既有key激活该候选一次，写独立新草稿；第三个调用只读核对同回执／已发布代次、原候选及草稿，不stage、激活或创建key。激活前先存不可变intent，未知结果保持原操作，不用再激活解决不确定性。三个不同Native PID及startTicks仅证明不同调用身份，不证明旧进程退出、私有handle或物理executor已关闭。

## 两个原采集失败与准确续接

writer Native方法终态0，采集器已保存91次设备＋8次本机守卫共99份完整捕获，随后因未计入准确空的 `source/installation-migration-candidates` 目录而退出1。该目录由真实export的候选验证构造器创建，准确为空、0700、原owner、nlink2、4096字节且无新增source key。原collector、exit1、原证明和全部原始流保持，不能改称原collector通过。

主统筹独立重读原99份收据／198条完整流、regular-only tar字节与九stat、44旧根／两FIFO元数据、38份本地APK及原source／candidate／envelope／receipt，形成只读对账基线。r2只修这个精确空目录和锁定此原失败的Root契约，36项纯检查通过属于辅助证据；不放宽任意目录／key，不重跑writer、export或stage。

第二个原生activate方法也返回0，99份完整捕获已保存，但原collector把文本加密pointer错误要求为binary的四字节版本前缀，实际文本前缀为单字节03，因此在解析后退出1。生产NativeSessionStorage的文本与binary版本编码有明确区别；此项是辅助采集器校验错误，不能改写为产品激活失败，也不能将原collector升格通过。原intent／已发布代次／原回执均保留，主统筹已对原99份采集独立只读核验；禁止重激活。实际文本前缀对应NativeSessionStorage第170行，binary的int版本对应第1075行。r3仅提供同已知publication的只读audit；33项纯检查辅助通过后执行真实第三PID，未重激活、未新建候选。r2的36项与r3的33项均为辅助检查，不能计入三个Native方法或替代迁移验收。

第二阶段仅允许准确原候选目录mtime／ctime单调变化：真实withPrepared会创建并移除变换scratch。目录其余字段、完整成员和所有candidate regular字节／九stat仍须相同；audit没有此时间例外。原回执recordCount为9，回执内容hash不等于加密envelope字节hash。

## 当前指纹与逐项结果

- Native源：`NativeMigrationSealCheckpointThreePidTest.kt`，SHA256 `b0cdaf21cb2afee1ea60c3e0135f560dd073fac765edff69aa10830df6f8598c`。
- canonical：`4b612dd071b911cd51dfea399393594080bdc6ac55aff740cd7b69908c37b752`（857输入）；expanded：`1ec29b367c7df0b328d8401706a75e3f7c715f8cfcd3e4cd44317ebfaeddf87d`（870输入）。
- App：`4a8fcdeff4e07c3d536fd9a465ad63bfaea4a71c400952a758a44d97205006cb`；Test：`9428aedcd4ce30d16247f337d5e5a126c6825de95019d3a75a26d2162835fe2c`。
- SDK主统筹核对：`74e8c6e9f92ccb48f0a9210afb5da658feb0e5326d0fa07532175b77601085d6`。实际／冻结输入、三APK／ZIP／CRC／单v2签名和原始工具流已核对。实际build-slot退出0并核对返回码透传；pnpm子PID未单独捕获、额外build-worker包装目录缺失，如实分列，未为补证重跑。
- writer主统筹原件重核：`a089dfac16742ff36c23d95bda7fdea3ce313ad6cc75e958771528b0b634b6c1`，确认方法与准确原候选，不升级原collector失败。
- activate主统筹原publication重核：`106e83b4abda4eb9a082e26e439c28b8637a38dafb9bfd874479f429e5a5e249`；只读对账基线：`a2ef55ca03e3b8320def7aa4ab056ee3ad71fb8904a3a33b769163fa5ea3b815`。两项原collector exit1与各自原因保持，不改称采集器通过。
- audit主统筹核对：`54ffcfecf941e90a0c5061c652433c89da504f8ed61c8374ede8adc568f13fb9`；最终基线：`a5acd5f9cdf02e844da2c9bfa6c0e84b86a0a620dc20df06afbeb4db27381250`；audit原证明：`4d7a6aaf17bda770f0bbeb7f153da4460e4bab7ec1474e226461b25c601908c6`。

| 方法／证据                             | 当前结果                                                           | 限定与剩余                                                   |
| -------------------------------------- | ------------------------------------------------------------------ | ------------------------------------------------------------ |
| writer真实seal具名中断、一次导出／暂存 | Native方法0；原collector退出1；Root原件只读核对完成                | 原source、原candidate、原回执保持；不重跑                    |
| 第二PID原候选激活                      | Native方法0；原collector退出1；Root原publication／回执只读核验完成 | 不换候选／key，不重激活、重放stage或原操作                   |
| 第三PID只读回执及草稿核对              | Native方法0；原collector退出0／已释放；Root完整核验通过            | 原receipt／publication／惰性双音频／独立草稿保持；无再次激活 |
| r2纯检查36项、r3纯检查33项             | 各自实际子进程退出0，Root逐项读回，辅助通过                        | 不计作三个Native方法、UI或真实DEV通过                        |

旧44根和两FIFO保持，FIFO只核对元数据，不读、不提供peer、不删除。writer新增准确夹具后files511／no_backup100；新夹具31成员及acceptance父目录链接变化均已对账。activate新增已发布代次与证明，delta16，最终files524／no_backup100；44旧根、38APK、全部source与candidate regular字节及九stat保持，准确candidate目录仅有批准的mtime／ctime变化。audit只新增自身proof和fixture根元数据，delta2，最终files525／no_backup100；原candidate／source／destination／旧proof的regular字节及九stat保持。主统筹逐条核对安装100＋writer99＋activate99＋audit99共397份原始捕获，44旧根／38APK完整保持。

真实Native PID为writer 16762、activate 22977、audit 28945，startTicks分别为2183953、2280232、2325723；三次调用身份不同。实际已编译Native断言验证原回执、解密后的两份惰性audio、独立后来草稿及恢复状态uncertain／不可重试；外部采集只可独立核对加密frame、文件集合、hash和九stat，不能冒称外部已解密。上述结果均不证明旧进程退出、真实崩溃、全局FD或完整UI／物理DEV。

## 双端与后续计划

Android本轮无生产UI、设计token或文案变更。iOS当前迁移Payload未包含PendingVoiceStore音频／metadata，缺口单列，本轮不新增iOS实现或通过。UI21完整流程、实际row I/O／断电／进程死亡、全局FD、mixed v1/v2、多份quota／48MiB低堆、24组完整迁移、系统界面与物理手机真实DEV继续待验；Source69失败代次保持只读对账，不重放。

本阶段提交并更新总表后继续Source79 P8保存连接行按钮角色，Source80 mixed方案并行准备，再推进其余P7、P9独立项。业务／模型输入维持221，P4为205及另1个封存未知原操作；任务／项目管理、F75原生项目UI与iOS A6暂缓。当前准确APK、冻结源、夹具及模拟器因后续同原件核验保留，阶段收尾按准确依赖回收自产可重建产物。
