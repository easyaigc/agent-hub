# 阶段84：平台目录别名与原控件归属

阶段83已提交809abd82并推送安卓分支，11源码/8文档、905提交blob与原SDK逐项吻合；两包/三包SDK和完整辅助回归通过。P7原prepare6e818c8d仍UNKNOWN/禁止重放，P8 AX缺失及P9原行未找到保持原失败。此阶段优先恢复真实SAF链，再核对P8原控件，不放宽成功标准。任务/项目管理、F75及iOS A6暂缓。

## 实施范围

- 仅认证Test Context的框架data/files根允许规范化系统祖先别名。原路径及规范路径的files必须均为对应data的精确子项；叶节点禁止符号链接，lstat前后比较device/inode/UID/GID/完整权限。既有data700、files700/771、同设备及原FD检查保持，通用namespace700/regular/same/ACK不变。不宣称抵御同UID恶意并发或路径检查无竞态。
- DocumentsProvider.queryRoots也经同一认证parentOrNull解析；只有本类直接ABSENT转空列表，不把其他guard/IO错误吞成空。四类路径调用已逐项审查，避免只修prepare后列表仍走原别名。
- P8仅在原失败诊断中连接原SemanticsNode.root/ViewRootForTest.view与保留decor，并记录最多8层父ID/几何/layout及现有遍历已经读取的至多32个语义ID。无新增AX根获取、窗口/anchor替代或输入；原5秒/64次、4项输入、14字段及成功断言保持。新Kotlin API可见性须真实编译。
- 新独立两包目的p7-main-saf-platform-alias-ax-v1，仍60overlay/固定59+self/905输入。Capture旧140顶层节点、旧前缀/配方/静态map原样保留；EXPORT仅目的/三guard引用，IMPORT仅两常量，P8旧目的保留并增加新分支。P9冻结三包链独立，不重锁为本目的。

## 审查与执行状态

七个准确源码路径已采用；独立审查f461e76d通过，905投影为7项新源码+898不变，Capture8b6c18a4。P7四Java只用android.jar的独立编译wait0/双EOF，8个class核对后已回收。共享SDK6368dbfb本次构建104.805秒、实际wait0/双EOF；Kotlin和Java编译任务确实执行，905源码保持，App1abfab33/Test52024ee4。包/UID/证书及新API33准入已核，实际结果见下；编译不关闭产品或物理验收。

接下来用新的run/operation验证prepare、系统选择器真实回调、原FD/ACK/关闭和导出密文；独立producer/IMPORT随后完成，既有EXPORT样例不能冒充IMPORT输入。P8再用新独立case核对原Compose控件是否确属保留窗口与真实AX发布。未知原操作和旧原件只按各自已授权只读范围核对。

## 对照及待验

F02/F03/F04/F55/F56为Android测试路径修复，未改变发布版UI、token/文案或iOS产品行为；实施/整项验收状态不升级。原P9操作752eeff3失败与权限恢复独立保留，通知栏前置状态待准确准备；其离线独立AVD因合成Keystore/加密fixture原件审计保留，不能靠新场景改判旧结果。完整24域、F59双端迁移缺口、语音/未读/生命周期、API37横屏/蜂窝VPN/手机DEV继续单列并推进。

## 本次实际结果

P7新run9201e7bd/controller8e4d4440一次prepare，原子56161实际wait0/双EOF但返回ErrnoException、无PREPARED或remote exit frame，效果UNKNOWN。Native8f9b8227从未派发，不重放。仅对原run只读核对：Test控制进程4799/UID10175/start49146，bootb3ef5cbb，namespace/run均700；scope.claimed与intent.pending均600，pending inode148912与同PID SELinux enforcing AVC denied{link}完全关联。原文件已写到发布硬链接步骤，原件保持；没有取得具体errno数字，也不借此改判旧6e/15b/c609的原因或效果。

P8原外层65975在run第一条本地输出目录校验失败，尚未创建admission、Device对象或派发instrumentation。该泛型外层UNKNOWN保留；Root按准确源码/堆栈独立确认前置失败，创建必需空目录后，以原意图c4e441dc进行了首次Native调用，另存外层回执而未重写旧结果。新outerdb9f7d04与inner201ca682实际等待并保留双流；内层工具rc0只证明采集完成，JUnit1 test/1 failure。

新v4观察确认原anchor232确属原保留Dialog13，merged/unmerged同View、已附着/有焦点、layout attached/placed且无pending measure/layout；实际clipped boundsInRoot/Window均0，原生19节点/15项ID extras中没有232。父ID链231/226/225/144/143/141/140/139与原窗口保持；因此不能按换窗口/ID不同解释，也不能关闭完整可见性。名称替换/IME此前有限结果保持，中文后续字段及英文未完成。原App5455在停止VM前独立确认/proc消失；scope/alias cleanup为NOT_STARTED、retained UNKNOWN，原数据/密钥不清理。

Root自持R66 QEMU51806正常wait0/原双EOF及端口关闭另核，旧未知退出不补写。原R66累计基线/512MiB费用保持；下一UI阶段在38GB可用空间下显式追加128MiB分配预算，累计上限2279235584字节，不重置历史费用或放宽产品门槛。R66仍用于后续修复和原失败审计，P9独立离线AVD/原合成密钥保持原保留条件。

## 下一阶段

优先替换Android SELinux拒绝的硬链接发布。候选为新namespace v4、稳定空锁、进程内先占用再FileChannel tryLock、最终目标明确不存在后同文件系统rename与原FD witness/fsync；保证仅限全部合法参与者遵守锁协议，不能宣称内核NOREPLACE或抵御同UID恶意并发。当前仅树外提案/独审，不是已采用或原生通过；旧namespace v3和8e原件不碰。

P8候选调整为自动保存确认后再准备原字段视口，并在原5秒/64次边界内核对同anchor可见几何；没有证据证明具体IME或产品布局根因，须新场景验证，保留原成功断言和旧失败。完整pnpm check本次745.964秒通过：实际wait0/双EOF，905源码/15路径前后快照一致，46组Node共1544项、1476通过/68跳过/0失败，smoke另记。文档生成/限定格式/parity/diff检查均通过；阶段提交随后执行。完整交互/视觉仍按上述失败与待验保留。
