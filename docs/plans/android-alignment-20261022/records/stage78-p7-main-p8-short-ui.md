# 阶段78：Main迁移四路径与P8短视口准备

关联F02/F03/F04/F55/F56、UI01/UI03/UI06/UI12。当前：共用修订SDK及实际guarded Main四方法有限通过；P8首中文短视口原生AX定位失败，英文未做，不升级P8。完整检查与阶段提交继续；不关闭完整P7/P8/P9。任务/项目CRUD、F75和iOS A6继续暂停，未知操作不重放。

共性构建使用独立 `p7-main-p8-short-ui-v1` 两包profile，固定40项overlay/896项输入，沿用七字段generic guard，无宽泛dirty推导或旧P9三包借用。P8消费时额外要求外部SDK seal和workspace review原始SHA；旧profile不接受新参数。候选按阶段77最终源码重新绑定，原失败和旧SDK指纹保留。

功能：P7四方法核实际Main迁移入口与密码/关闭路由、已有连接禁用导入、加密候选预览取消/重开、确认后发布与仅一次新Session恢复。Main创建新Session前后的窄hook及发现隔离只在DEBUG、独立验收包和准确私有Context组合生效，生产默认创建/启动发现不改。初始夹具为已恢复Session，不证明shipping首次启动；预备候选不代表SAF选择或OS grant。

交互：每次原生动作先核准确App窗口、Activity/Session和私有owner，密码不保存值层级/截图/日志；两个新鲜AX样本后才点击。关闭/系统Back/取消/重开、已有连接保持、核对original/imported两份seeded本地草稿、publication receipt及fresh owner不重复恢复；P7四方法未新增确认后的编辑草稿，P8另保护其originalDraft与unknown journal。每次acquisition登记实际句柄，只有真实join/close/退出回执才能关闭归账；不以成功计数覆盖未知owner。

界面：P8以固定横屏和Activity内fontScale 2的中浅/英深两case，量实际短视口、长名字保存与新Activity/Session恢复、五类长字段TextLayout多行且无ellipsis，14项完整AX标签/值及准确semantics/testTag对应。popup/sheet系统Back和无效地址编辑不连接。重建或新Intent超出准确owner时拒绝，保留原进程现场。P8仍nativeRuntime=false/Demo，不证明shipping Main运行时、真实issuer/网络、TalkBack操作、全局系统字号或物理设备。

iOS对照分别按原字段、文案、密码/预览/取消/确认、主题/语言及返回路由核对；iOS源码/设计token不改，Android恢复授权策略差异仍单列。直接源码核对：iOS EncryptedMigration.swift:129–154当前选文件后直接解密并restoreMigration，没有Android的候选预览/二次确认/取消恢复页；后者是Android附加恢复保障和当前交互差异，不能记为iOS已有对应UI或双端一致性已关闭。iOS EasyWorkConnectionForm:289–350无Android六身份只读区；这是Android平台附加信息/当前界面对照缺口，不记为iOS已有同构。P7 guarded Main nativeRuntime=true，P8=false。当前是验收实现，不把静态源码对照当双方UI已通过。完整方向/字号/AX、SAF、真实Host模型、双端整包互通、完整24域和物理DEV继续待验。

执行时另记新review/guard/SDK seal、当前源与包指纹、设备/boot/UID、准确方法和实际JUnit、原worker wait/双EOF及owner计数；辅助构建、原生合成场景、实际视觉和真实产品DEV分别结论。历史Source99只保留为提案标签，不沿用旧发号或scope。

首套SDK实际完成：seal `907a5a8b7c5af0f65b19b2ffe690dc501911a8325c86b5cd65adad9c79815e30`，review `a656b40d`，896输入/40overlay/七字段guard；Root核92封存材料、17原wait=0/rawComplete及外层wait=0/双EOF/fsync。App `1e2f1fcf`、Test `9e296ab0`，同调试证书。两包各一次更新后原UID保持10174/10175、boot不变、PID为空；没有卸载/清数据。

首Native run `5ec2c585-39fc-4458-b4e9-df5d63b0180c` 实际失败：第一个精确方法1项/1失败，原runner wait=0只证明命令结束。原始栈指向旧test SHA6b871c4b的338行，即BACK DOWN注入返回false；其前准确App窗口检查已通过，不能把失败位置描述成窗口检查。已完成的密码阈值、Settings/Done/Cancel与重开、22条AX等待事实仅作失败诊断，不拆出来升级整方法通过。其他三个P7方法和P8尚未执行，旧分配UUID全部结束该轮，不重放。

原cleanup明确1个Session/private writer退休、Activity关闭、installation释放、provider attempts0；原PID4473/start52966同boot自然proc ENOENT/目录不存在。原QEMU49879正常wait=0、双EOF/fsync，原supervisor和5558/5559已退出/释放。R66数据、密钥与失败scope保留；提取APK重复副本在与封存SDK精确匹配后回收，原byteproof/cert/raw保留。

修正仅test与对应SDK fixed pin：单次BACK对使用实际uptime和同downTime，显式虚拟键盘/source；finally只释放同键UP，两个接受结果仍必须true并记录时序，不忽略false或重试DOWN，不降低App/owner/窗口护栏，也未改产品UI。[Android13 KeyEvent源码](https://android.googlesource.com/platform/frameworks/base/+/refs/tags/android-13.0.0_r1/core/java/android/view/KeyEvent.java)的二参构造留下零时间，[InputDispatcher](https://android.googlesource.com/platform/frameworks/native/+/refs/tags/android-13.0.0_r1/services/inputflinger/dispatcher/InputDispatcher.cpp)有stale拒绝路径；与现象相符但镜像未匹配、没有本次派发日志，不能认定唯一因果。修正版新SDK、新boot/操作与完整四方法/P8仍待验。

修订R2 SDK seal `4f4b2865d57532679345f502cdf61d0da8b6c3d7536bb3bae7250a5e9b3dd041`，review `b1f91dcc`，896/40/七字段；Root再核92封存材料、17实际wait/raw和全部当前源码，外层wait0/双EOF/fsync。App仍 `1e2f1fcf`，只更新Test为 `33f250a1`；同证书/UID，未重装App或清数据。

API33新boot cb5c8eb8-616f-4995-bcaf-b1655fdb0431，四方法各新run、各一次、各named JUnit OK(1 test)，inner/outer原Popen wait0/rawComplete/双流和自然proc ENOENT均独立核对：d8cb777d/PID4438/start42979，4f35432e/PID4661/start45859，66f3e954/PID4836/start47396，f9346d1c/PID5000/start48962。共5个Session登记，规则退休4、Main真实交接原owner1，Activity/installation均关闭，44组准确窗口AX等待、typed provider attempts/overflow均0。首次BACK downTime/eventTime439392、UP439421，两个accepted为true且原关闭/清空/同owner断言通过。该新通过不改变旧run5ec2失败，也不证明旧失败唯一因果。

P8首外层在任何Device/Native之前因缺empty output目录退出1，原捕获status UNKNOWN/rawComplete=false保持；只修新目录并换全新run，不重放旧外层。新run84febd34-f1e8-4698-9861-44ab67dcc2fd实际进入Native，中文case cdb282b8在nativeAxComplete的easywork-name原生锚点0/1失败，Named JUnit1项/1失败；英文case尚未执行。原Native wait0/rawComplete=true不等于JUnit通过，第二外层同样UNKNOWN/rawComplete=false均保留，不能补标成功或双EOF通过。

P8实际两Session的join/I/O/migration/writer退休均CONFIRMED，一个Activity实例销毁、完整实例集/准确acquisition和最终默认family缺省均核；失败scope/alias未删除。原PID5278自然ENOENT，随后唯一QEMU73767正常wait0/双EOF/fsync，Root重算完整VM流hash并独立核PID/端口不存在。R66数据、密钥和全部旧新scope保留；提取APK重复件只在与封存B逐字匹配后回收。

最终范围：四条Main路径为本次有限通过；P8当前失败、SAF/真实Provider grant、密码错误/篡改/UNKNOWN交互、完整方向/字体/视觉/TalkBack/物理及shipping首次bootstrap仍待验。首套窗口/时间问题、外层预检UNKNOWN与本轮AX失败各自保留，不用编译、组件或iOS结果替代。下一阶段继续P8准确AX等待/导出映射及P7真实Main SAF前置；已有通过先按安卓专用分支提交。

Final pnpm check ran once: original PID91880, 754.248 seconds, actual wait0/dual EOF/fsync; 42 groups, 1518 tests, 1456 pass, 62 skip, zero fail/cancel. Generate, nine-doc format and parity passed. Root rehashed raw streams, 896 inputs/eight bindings and unchanged HEAD/index/17 paths. The execution agent later hit model capacity, while its original collector completed normally; Root recovered existing receipts and official tool returns without rerunning or substituting another model. Actual Terra/medium turn_context was verified; speed is not exposed.
