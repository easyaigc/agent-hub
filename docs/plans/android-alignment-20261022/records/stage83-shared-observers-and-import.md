# 阶段83：共用诊断、导入前置与P9重锁

阶段82源88181243已推送安卓分支，PR133源CI通过并已合入主线d79e85bb，源/主线CI及公开同步通过，阶段82本地/远端分支已清理；本阶段从同一准确源码继续，前一阶段工装与完整P7通过分开。任务/项目CRUD、F75与iOS A6暂停，全部既有UNKNOWN按各自限制保留，不重放。

## 共用范围

- P7只对112个既有reject调用的80种自有固定类别提供有界原因码；未知异常仍类名，四处failureClass保持IOException。所有guard、IO、ACK、关闭与成功条件不改，不推断原15b拒绝原因。
- P8仅缓存原绑定循环已完成读取的固定phase、计数和布尔值；失败时最多2048字节，无额外窗口/AX读取。原owner、binding-v2、5秒/64次、四输入和14字段断言不改，未发生的当前样本不冒充完整样本。
- IMPORT运行器只消费独立完成producer的不可变原件/真实回执，输入写入方、RX ACK、consumer FD关闭和实际Native解密预览分别核对。未知写入只关闭本机原stdin，不重发后缀，不宣称远端取消。原IMPORT元数据与preview(0,1,0,0,0)保持；EXPORT样例不能冒充该输入，producer-v1须单独准入。
- P9保持独立三APK/API36目的。普通入口拒绝诊断；DIAGNOSTIC_ONLY在PNG、点击、编辑和openShade前终止，只观察首个拒绝节点及至多一个后续同列直接子节点的6/18有限事实，无祖先、其他行或内容放宽。bootstrap会修改本次合成store/通知，只有observer只读，不能合并宣称全流程只读。

## 源码与辅助检查

11个仓库路径已采用：60 overlay、59固定+self、905输入；唯一新增输入为IMPORT脚本。Capture078e918d，旧119顶层节点/18配方保序，新增后140/20。共享两APK目的p7-main-saf-framework-observers-import-v1与P9三APK诊断v2独立，Native协议仍v4、私有namespace仍v3，旧全部目的冻结。

Root逐项复核49份候选资料、11组before/after指纹及905投影；独立最终审查a31381c8、投影b4054dff通过。P7四Java类只用android-37.0/android.jar独立编译实际rc0/双EOF，8个class已清理；P8及P9/IMPORT仍只属源码审查，不是SDK或交互通过。IMPORT三helper在导入前核对Root字节指纹，修订前的阻塞R1资料不覆盖。

P9外部链为Capture078e918d→bridge87fef203→baseea6b0ec3→wrapperb6a00f33；桥接链不在repo源码库存，按外部Root指纹另行绑定，不能形成自指。源码/运行模板中的SDK、API36启动映像、UID、工具、producer及实际运行信息均UNBOUND，后续必须按准确环境填写。

## 本阶段执行次序与退出门槛

1. 完成60/905实际源码准入、文档一致性和新共享两APK构建；原App/Test证书、包/UID、ADB与独占VM重新核对。
2. 全新独立run验收P7准备/导出，读取有限拒绝原因后只修复真实门槛；旧15b/c609不重放。P8新case逐项绑定、短视口/长字段/双语/AX与菜单恢复，不从前次不同失败推断原因。
3. 独立producer原件完成真实准入后再验收IMPORT，原stdin/文件/进程/回执/解密预览分开，不补跑producer或重放未知操作。
4. 新三APK/API36 SDK和启动映像分别准入，再运行P9有限诊断；其结果不关闭SystemUI实际点击、真实Host/供应商/OEM/手机矩阵。
5. 完整辅助回归、源码/提交指纹对照、安卓分支提交和总表登记，再继续后续优先项。完整24组迁移、F59双端缺口、语音/未读/生命周期与产品DEV保持开放。

## 保留与待验

producer/IMPORT和物理DEV尚未执行；P9本轮失败单列；本阶段SDK及P7/P8实际结果如下。旧863/fab4禁止查询/接管/重放/清除；c609与15b只可按原回执只读核对且禁止重放，1419/bc14从未派发。原VM89265实际wait/EOF未确认；后续Root自持VM的正常退出不能替代该原事实。API37横屏、蜂窝/VPN、人工owner归档及其他环境项继续单列。

## 本轮实际结果与后续修复

- 两包共享SDK seal93552e54，构建217.766秒；三包诊断SDK sealfe332b0d，构建105.329秒。两者905源码均逐项核对、实际等待rc0/双EOF；App1abfab33和Test10d35644一致，诊断Examiner0ce8996a。目的与seal分别保留，不混用准入。
- API33全新P7 prepare操作6e818c8d/run44305e0d实际子进程wait0/双EOF，但返回P7V4_REJECTED_IOException_GUARD_PLATFORM_DIRECTORY、无PREPARED；效果UNKNOWN。Native2343eb35从未派发，不重放原prepare。原Test进程4658/UID10175的mount视图中/data/user/0为系统别名，run-as视图不同；Android13r1原始实现支持该差异。当前Java getter/具体谓词未直接观测，路径修复和新原生验收尚待执行，不能按推断改判原结果。
- P8新操作1b82ff3f/run97f9ad9d一次运行，原App5104/窗口13 binding-v2确认；名称替换和IME两项输入CONFIRMED。随后easywork-name原AX锚232在原5秒预算内缺失，JUnit1项失败，英文/其他字段未完成。完整样本15实际遍历19节点/15项ID extras，失败诊断亦完整，exact ID/tag均0；末次顶层计数0不是未遍历的证据。绑定成功，故新增绑定失败观察未触发。保持原窗口/控件与断言，继续定位。
- Root在VM停止前独立确认原App5104的/proc目录/stat均ENOENT。自持API33 VM80089正常wait0、原双流EOF及摘要已核，5558/5559端口关闭；这不补写旧89265的缺失退出回执。合成数据和所有UNKNOWN现场保留。
- 下一阶段仅在认证框架包根目录规范化系统祖先别名，保留叶节点禁符号链接、inode/UID/GID/完整权限/同设备及原FD检查；通用namespace目录700/文件与ACK门槛不放宽。候选先独审/编译，再以新operation/run验证，不接管未知操作。
- 完整pnpm check本次通过：812.579秒，实际wait0/双EOF，905源码及19路径前后快照一致；46组Node共1544项，1476通过、68跳过、0失败，smoke/Swift辅助另记。文档生成/格式/parity/diff检查通过；本阶段安卓分支提交随后执行。两包/三包可用于各自后续准入；当前构建上下文和最小证据仍有具体依赖，废弃中间产物按阶段回收。

## P9 实际诊断与资源收尾

新三包API36从独立空白v2环境一次运行，boot ab09f5e5、原QEMU36272；操作752eeff3、lease6dc1c3db、attempte5504484。Driver自行按examiner/app/test安装，随后仅运行合成bootstrap/observer/producer。原外层子38605实际wait1、双EOF，三原worker38721/38757/38777实际wait0/双EOF；Native观察器返回REFUSED“original UUID row unavailable; no fallback”，Host因闭合字段校验返回FAILED_OR_UNRESOLVED。未取得合格通知行，通知栏已打开的前置状态没有证据；不改断言，不重放，不计辅助诊断或产品通过。实际截图、点击、草稿编辑、发送和shade action均0。

首次准备时Root提前安装了三包，与driver要求空白并自行安装的顺序不符；那台VM未提交Native操作，正常wait0/双EOF后通过avdmanager正常删除。原安装、配置与删除回执保留，不冒充有效验收。v2未做手工预装；其系统前置状态仍待下一次准确准备。

原Native owner4764/4807/4875在VM停止前均确认/proc不存在，无活动instrumentation。原App UID10215/包1abfab33、boot/count/user及QEMU命令保持；仅一次revoke恢复POST_NOTIFICATIONS到原拒绝状态，原权限flags完全一致。修复前两个只读采集失败无权限副作用，原sealed失败结果不改写。Root正常停止v2 QEMU，实际wait0/双EOF及5560/5561端口关闭分别核对。

v2约1.20GB合成Keystore/加密fixture仍保留在原独立AVD中，无完整userdata重复副本；原因是原密钥尚无可验证外部恢复证据，释放条件为完成原失败密钥/资料审计并解除该依赖，24小时复核。P7的R66资料和当前构建/签名上下文仍有后续修复依赖。完整回归的131根可重建fixture及独立短TMP已完成归属与最小证据准备，提交节点刷新占用后回收；不删除共享缓存或未知资料。
