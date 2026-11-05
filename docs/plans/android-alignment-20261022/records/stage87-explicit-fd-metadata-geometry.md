# 阶段87B：显式 FD 所有权与完整元数据基线

阶段87A已同步主线78b9dda6，完整pnpm check实际899.14秒、wait0及双EOF，3010路径前后相同；同步提交f522ffa5已推送。主线随后推进到1c91b5e3，PR135源CI11382两项已通过，但新的文档冲突仍由主会话处理，尚未合并或清理源分支。

## 修复与断言

上一轮普通FileInput/OutputStream(FileDescriptor)借用原Os.open的FD，关闭wrapper/channel不关闭原FD。辅助Lease现在在open前强登记明确owner，先关闭借用wrapper再单次Os.close；close结果不确定时保留owner，不以复用FD数字重试。Publication只在lock.release、stream.close和raw close均确认后释放token；未能返回的dup也有异常owner。已正确显式关闭的payload sink、receiver PFD、Pending witness路径保持。目录finally-close及Docs IMPORT异常覆盖的既有限度仍使调用失败，另列未修复，不改成成功。

合成metadata初值按生产普通完整根字段及顺序构造，保留全部原始字节比较、owner和回执断言。MainUi记录真实providerRelativeDirectory或null，避免顶层硬编码旧v1；生产产品布局、设计token、文案和iOS不变。这不表示24迁移域已验收。

P8仅在原失败诊断内记录原anchor与最多8个祖先的公开几何、滚动、IME、page/sheet和owner前后状态；超16KiB时仅省略新增geometry。成功门、5秒/64预算、输入次数、窗口与原anchor保持，不增加滚动/输入重试或替换节点。上轮19节点/15ID仅属失败后诊断；原Native AX sample未发生，唯一根因待查。

## 新输入与隔离

Root独立读回30材料及905项当前输入后采用8文件，897项不变。Native四个after与原独审完全一致；新explicit-FD目的绑定62overlay、61固定项及外部capture-self，纳入主线package.json和两份UsageRules。Capture原170个AST节点、旧前缀/map/profile/recipe保持，新目的共180节点。独立静态复核3159项无阻塞；源码采用不等于构建或Native通过。

新SDK、Root、runtime与新UUID另行准入；旧0b2dd747/880e07d9/e6cdc5a7及全部历史UNKNOWN原件、回执和意图保持，不接管、不重放。独立producer/IMPORT仍未绑定，EXPORT密文不冒充IMPORT输入。P9冻结五件不变，但旧SDK不证明当前主线输入。

## 验收和收尾

下一步按实际新SDK核对唯一系统选择/保存、callback/grant、metadata完全相同、payload/ACK、原owner退役及原始FD关闭，再推进独立IMPORT、完整24域与双端互通。功能、交互和界面分别登记，API26/37与物理手机、完整F59及P8/P9仍待验；任务/项目、F75、iOS A6保持暂停。

已正常移除不再依赖的PR135 CI工作树，最小986份合成证明及准确源提交保留；并行其他分支变化独立登记，不声称全部refs未变。阶段86的132个fixture/TMP目标已核对不存在。构建复用现有隔离目录，保留当前包与一个回退包，不复制AVD/密钥/完整环境。后续构建、实际Native及临时产物回收结果追加本档案，总表只维护范围与完成状态。

## 本次真实结果

新SDK f5aacf74实际118.903秒、wait0及双EOF/closed，82项Gradle任务与五项Java/Kotlin编译均实际执行；905输入前后相同。App b45c5e32、Test 9821b263，签名一致。格式化guard 74b5a149与Native使用的规范guard 0ce908c3分别保存，不能混用。构建按新目的独立20GiB起始/4GiB净增量预算执行，未改写或复用旧SDK86的30GiB审批；实际最低可用21787328512字节，不声称已测量历次构建峰值。

第一次外层Host因未预建空输出目录而FileNotFoundError/wait50/UNKNOWN，原回执保留。Root核对源码顺序和实际输出：目录lstat早于admitted、Device、controller与Native派发，两个业务操作均NEVER_DISPATCHED。补齐准确0700空目录后，另存外层捕获，按原未变意图完成首次实际业务派发；没有重放未知业务操作。

唯一实际EXPORT取得原PREPARED回执，再完成真实Main系统选择、唯一设置/保存、callback/grant和1项Native方法，外层/内部均实际wait0/双EOF。原metadata字节级和owner断言通过，privateSession/activity/installation均释放；4328字节密文、原ACK、双稳定audit和原进程关闭结果已关联，Root独立读回147份原wait/流摘要。原App自然退出，两个provider按协议继续存活，不声称provider自然退出或远端shell exit frame已观察。

独立只读/proc复核两个准确provider的boot/UID/start/argv；各65个FD的130次readlink全部成功，未见本轮UUID路径。只证明此刻链接状态，不能倒推ACK前所有关闭或grant终止。首次只读取证的shell quoting失败单列不确定；修正后新只读操作未执行任何业务或audit写入。原业务UNKNOWN保持。

Root随后正常停止本轮自有VM，原QEMU实际wait0、双EOF和5558/5559端口关闭；全部旧/新目录、ACK、密文与密钥保留，累计R66分配2164482048字节未越过原2279235584上限。P8本轮未执行，失败几何新增代码仅编译/静态通过；完整IMPORT/producer、24域、F59、双端互通、真实模型/手机DEV和P8/P9全矩阵保持开放。

阶段87完整pnpm check及提交结果随后追加本档案。下一阶段优先准备独立producer的真实14材料与进程退出，再独立Root准入Main IMPORT；不拿本次EXPORT当导入原件。F59防遗漏/密钥覆盖的有限生产保护提案已独审，尚未采用，不冒充完整F59或Android实现。

## 完整辅助回归

唯一一轮完整pnpm check实际778.075秒、wait0/stdin关闭/双EOF与closed，无溢出；3011路径、16项改动、索引及905输入前后相同。Node24 spec报告46组1558项：1490通过、68跳过、0失败；它们是辅助检查，与上文唯一API33实际导出方法分列，不代替Linux CI/手机/完整P7。

原始前后目录清单显示.local新增3113项、删除0项、改变36项，专用TMP有944项。先按任务标记/源码调用/实际身份与占用提取最小证明，再清理本轮可重建fixture/TMP；共享缓存、当前SDK87/回退86、P9三包及全部加密状态/密钥仍保护。阶段提交和远端SHA在树外交付回执中独立核对，不把待完成PR135或完整迁移记为已合并。
