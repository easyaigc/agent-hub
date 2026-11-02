# 阶段70：P7同包首次迁移与只读冷恢复

2026-11-03，F55/F56；[总表](../progress.md)维护计划，[待验清单](../pending-acceptance.md)维护缺口。本阶段只增加验收入口、Host证据和受控SDK采集，不改产品UI/iOS或放宽existing-only规则；完整implementation/verification保持。

## 本次有限通过

API33专用验收包以新UUID、同一App/Test/源码/SDK封存，完成普通实际Session的非空export/stage/source restore、已初始化空目的激活及恢复，然后核原进程自然退出，由不同PID/start的只读cold读取原资料。首次8TEXT+1normalized CAF共9条；不是完整24项系统范围、新安装无root KEY或严格Controller闭环。

功能：原阅读账本、通知偏好/leases/unknown receipts和声音资料、实际Session恢复、旧未知回答只读核对与新草稿独立。cold使用两个实际ExistingView reader；不创建Session、key或writer，不重新激活、查询真实Host或重放原操作。

交互：首次与cold共享不可变原intent/proof与SDK、原进程活跃期独立观察→原worker actual wait→同boot下/proc stat ENOENT及目录不存在→新PID/start活跃期独立观察。不是手工终止后伪造cold。界面：本阶段没有Main/UI/截图验收；iOS UI和双端整包互通均未新增通过。

## 原始证据与指纹

私有相对入口 `stage70-r10-paired-native-diagnostic-execution/run`，范围`32b8e471-e225-4f41-abbf-8d4afe9d5e44`。first PID5466/start183104，自然退出后cold PID5592/start185172；两份具名JUnit均OK(1 test)，worker15131/15497及外层15112实际wait均0。Root逐项核原raw/receipt和36份run文件摘要，见`stage70-root-primary-paired-review.json`。

- first-proof `5527cc926c097c7f0f909b11452b92c5fb9b5a9ba13872cc50604109b762aab4`；cold-proof `5368eb07277f636476b357f5e5c4b3e188559686cbbce763e28da8eabe09c048`。
- 两阶段anchor/binding、active text/binary及clear/sealed摘要相同；readerCloseReturns=2，protectedDataUnchanged/fixtureAliasesUnchanged均true。保护范围仅run/no_backup、cache、files及原first intent/proof；新增cold控制文件单列，不承诺Keystore内部物理磁盘全同。
- SDK `stage70-controlled-sdk-r10-execution/capture`，seal `e92d441c3dbb1c37ffcf46d496d9b76685fc45ad93c7a31695ca80897230eebd`；874项构建前后guard `764a605df8a456ec00edb2e2bd28df8a2251b49604677f00bae453197f46ac1d` 相同。Root重算92份封存文件，17个实际子wait及82项Gradle任务通过；独立实际verify_build_bundle通过。
- App `39c99bfd17dadf7947ad708f8e49eea2108eb294e277fed62d6acfbfc5dc7fc7`；Test `67351134d4d500b122fad9e9c95bbcc992bbfb9aed99f621abfbc9c55a5b075e`，两者同原证书；Test声明版本缺省不借用App版本。

SourceGuard覆盖完整受控输入与新增文件；SDK只允许固定p7/p9任务，原Popen所有权先登记，错误保持UNKNOWN直至原wait，不将晚到退出升级通过。新采集适配实际android-37.0平台名、apksigner37 scheme标签和aapt完整字段边界/重复字段，旧失败均保留。Host proc cmdline仅允许准确package后纯NUL填充，拒绝额外argv；实际输入在断言前有界保存，四128KiB输入hex的合成容量边界通过。

## 失败与辅助结果单列

`0e311011-1a7e-439d-8268-4a673c58aa0c`首次JUnit通过，但Host live复合身份观察失败，永久UNKNOWN；原runner actual wait0、外层wait1，未进cold，不重放。原cmdline未保存且原PID已退出，不能把后续NUL兼容通过作为旧失败根因证明。

`00907454-c1fc-4543-8989-92020d383527`首次迁移/独立live与自然退出通过，cold JUnit在Fixture.existing的run/binding/anchor复合要求失败，缺cold proof/after pins，配对未通过。R10仅拆分原要求与增加16KiB差项诊断，没有规范化或放宽anchor，随后新UUID意外通过，旧失败原因仍待复现核对；namespace投影仅树外静态建议，未采用。

Python3.9辅助：R3所有权/大小/超时边界、R4 adb与SDK工具绑定、真实cert/badging/APK解析14项、R8 exact argv0及mock live正反例、R9四128KiB原输入完整保留与额外argv拒绝，分别保留原raw/actual wait。错误的合成空格/Path类型、旧解释器API假设等前置脚本失败不算产品结果。P9 private当前C的三包SDK及共享封存/桥接校验通过，只是后续RWE34+/SystemUI输入，不是P9 Native通过。

执行/查证Agent官方journal实际模型与medium档位由Root核对；无速度参数、实际速度未核，不声称极速。普通Session27 stores/4 joins/25 explicit retires+2生产旧root退役/2lease releases的语义保持；不证明OS FD/executor/Controller worker终止。

## 资源与后续

同scope既有sealed原件只读导出21479B，摘要`f83c856f5a9090e34989bf2c168d92b66d463e0185c7773d6d20632cea039563`与first-proof archiveSha一致，入口`stage70-native-paired-export-artifact`，仅后续跨语言合成输入。正常console关闭唯一Root5558，Root亲自poll原exec99782确认原QEMU93637实际wait0；端口释放，AVD盘为下一新scope pristine/完整范围及旧R9现场保留，24h复核；其他设备不动。

精确回收C已完成SDK的android及examiner两intermediates，allocated上界344662016B；103项封存/874源码/当前三包与Git均未变。实际工具exit0，未单存Popen wait原件的限制保留；free波动不全归因于清理。当前包、回退和原失败证据保留具体依赖，后续按指纹淘汰重复包。

下一步：pristine无root KEY导入、24项资料/通知owner/语音偏好与原件、真实自然故障/崩溃/SAF、严格Controller及完整Main/UI/字体/方向/AX/物理DEV继续；iOS补偿失败恢复和双端整包互通继续。F65–F67/UI14–19、F75与A6仍暂停。未知真实操作不重放。

## 阶段交付

完整`pnpm check`以子进程umask022实际退出0/733.06s，42组汇总1518 tests、1456pass、62skip、0fail；Root直接重算原198579B日志，H`d12786b1b6f14de22cb57c0b231482f5eb780ff76dbb97f02215f43ed85a7eaf`。全检查后仅补交付段/总表，聚焦格式、parity与diff检查通过。

源`95e9628159359bb92e86ed267ce88bf5590c7186`经[PR118](https://git.51easyai.com/BCAI/agent-hub-app/pulls/118)普通合入main`6f9fa9cfab00b91bb337da95a02b7283dfaa6862`，双parent为原main`f4d389f1`和source；tree`690f165a6cf40ea3d20b6958f00a62f1f2270849`与source相同。Root逐个读17 Git blobs核六源码/manifest状态及实际graph，非仅采信提交摘要。PR与remote核对、原操作wait/不可变intent在`stage70-pr118-merge-cleanup-execution`，三类stage70分支引用均已清，T切下一stage71，生命周期check通过。

准确main的CI11199成功：workspace27015在runner6、iOS模拟器27016在runner4；公开同步11200/27017在runner7成功。GitHub官方ref/commit独立确认公开`62494057737d4e501a5ff26bf9d98919072fb3d3`的Gitea来源trailer为本main且tree相同。Root逐项核原gzip响应/H和终态，入口`pr118-ci-sync-terminal-review`与`stage70-root-ci-terminal-review.json`；不将iOS CI模拟器结果迁成暂停的A6真机通过。

提交节点后正常停止仅本任务私有IDLE Gradle daemon，正式`--stop`调用实际wait0；不把daemon观察退出写成child wait。精确回收4个已完T编译/transform缓存（另2个原不存在），allocated上界1223696384B，六源码/92封存/36Native文件及当前两APK不变，见`stage70-owned-cache-retirement-after-idle`。完整旧8GiB全attempt allocated基线缺失如实保留，不以卷free变化伪称任务累计或因改名重置；下一已合并后新phase的预算/依赖另登记。
