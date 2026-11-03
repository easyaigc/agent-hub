# 阶段75：附件收件箱与文档、照片选择原件迁移

功能 ID：F55、F56。状态：API33 两个命名 Native 方法有限通过，SDK与完整工作区检查通过，准备提交。完整P7及真实Provider/SAF/UI未关闭。

## 功能、交互与界面对照

本轮复用普通Controller、首次导入和同boot只读cold管线，聚合五个真实本地store producer：external-inbox、document-results、photo-results及后两者的payloads。每组建立pending-bound、failed-partial、consumed原件；文档/照片使用实际NativeDocumentJournal与NativeAttachmentInboxStore，照片由实际Bitmap生成PNG并经生产prepare产生缩略图。

输入字节、content URI和retired generation为隔离合成资料，读取回调不访问真实Provider。保存原绑定、阶段、清理回执、payload和thumb；迁移不恢复旧URI授权、注册回调、自动导入、上传或重试。相机非空输出的外部FD关闭证明不足，原拒绝不放宽。没有任务/项目业务或真实模型输入。

严格portable为26 text、7 binary，共33条；child部分为18 text、6 binary，基底为8 text、1 CAF。目的端新增device audit/tombstone后为28 text、7 binary；sourceRoot-only hash图的8/1不等于完整child graph，不能混计。原root及五child的密文、全部候选资料与目标原件保持，独立PID只读核对同一候选、回执和受保护图；两reader关闭。

本次登记32个store、4个Session，取得4次实际join、30次外层writer退休返回、2次lease释放；unknown acquisition和禁用port尝试均为0。三组正式actor以精确1+2+2句柄登记attempt，完整返回后才登记退休；另外两个root由生产restore退休，不混成外层调用。tasks.cancel不是actor Job.join，本轮不声称全部actor scope disposal或全局FD证明。

iOS迁移Payload并不穷举这些平台picker/preparation journal；本轮只读对照其引用附件、清除许可及不重放的边界，没有执行或修改iOS。Android独立原件归档不等于双端整包互通。产品页面、token、文案未改；组件和生命周期数据证据不能代替Main迁移密码/预览/取消/错误/成功、SAF、短视口/方向/字体/AX或手机验收。

## SDK与本次Native

- 最终SDK profile为p7-child-originals：890项输入、26项明确overlay；原Voice/P8/P7/P9范围不扩大。两项固定assemble任务完成，17原wait=0/rawComplete，外层实际wait=0/双EOF/fsync。seal：`757a2aa75b3a7f8e471412c5cff555c36e35afe0e0403723386275a28689f7e2`；guard：`3be0104f1075e7be957953a4f6d8b5cbf5b61a00280d626c453d18062630672f`。Root重哈希全部92份封存材料及890源。
- App SHA：`11ffcec2c03b43aee54e06c8169ee5cad776eade72b0ec25626e10c80a1620c4`；Test SHA：`f5f02ab2ba52fdebbcf1980d692b72514df5a03224d8168701da128877cebcfd`。实际安装App/Test与证书、UID、boot、live PID/start/cmdline均核对，App匹配未重装，仅更新新Test。
- 新run：`ea968c8b-8e5e-44c5-86a8-a5912fa62bfc`；selectors为`NativeMigrationNonemptyDeviceSessionTest#childOriginalsControllerProducesPortableArchives`及`#childOriginalsReadOnlyColdAfterExactOriginalExit`。原始stdout各有准确方法与`OK (1 test)`，原adb wait=0且rawComplete；原pair实际wait=0/双EOF/fsync及正式工具finalexit=0。
- 首次PID/start为4040/13092，自然退出后cold为4215/15447；同boot `96e5bbbf-ca6b-429c-ac0b-451d53d02c9c`。没有强停App制造退出；原proc与目录缺失独立确认，cold原件和alias保持。Host wait不当作OS对App的wait。
- first proof：`54bbc5e5dc90947d98ab17b3846cfbfa64d1d6ae3a8eaf97c02d3e25301adda7`；cold proof：`2b6a538738c0765d30ddf1db436d0a0a3c9ddfbf47f05d8e000f2c38edf7f994`；paired review：`9a246a94e9ecc5fdd2891bcc5358ca82958de95ced4c0f7a8c424bf8c606a6fa`。Root另核56项原始材料并保存树外索引，不靠执行方结论判通过。

## 辅助交付检查

本次生成、限定文档格式化、工作区与暂存差异检查、mobile parity及完整`pnpm check`六步均取得原进程实际wait=0和双EOF。完整检查687.098秒，原始Node汇总42组、1518项、1456通过、62跳过、0失败/取消/todo；不是复用阶段74结果。生成及格式化前后890/26 SourceGuard保持，九项暂存源码不变，最终19项任务路径。Root重哈希原流并核对正式finalexit=0；首次只读工具返回未保存的证据缺口仍登记，后续原返回保留。

执行Agent的官方own-child turn_context分别核实SDK、Native、完整检查及独立CI核对使用Terra/medium；入口没有速度参数、实际tier为空，未宣称极速生效。上述检查是辅助证据，不升级真实Provider/SAF/UI、物理设备或完整P7。

## 原问题和UNKNOWN

R1静态发现actor已退休五个child writer，外层Owners却再次await其shutdown队列，属于夹具生命周期P2，未执行该版本。R2以正式调用前登记的精确actor/句柄/operation ticket修复，部分错误保留UNKNOWN且不重复等待或退休；旧9/13默认路径和33条图未放宽。

R2 SDK通过，但原first run `e9e11856-34bc-4320-aef6-0c65c8e8ca52` 在300秒后被控制器封为UNKNOWN，只有JUnit开始，cold未执行。只读调试观察到一个IllegalArgumentException进入原永久hold，保存的原异常栈为空；尚未取得Frozen lease。Root静态确认另一个错误前提：夹具把另一聊天保存的一个附件，误当作当前未选中聊天的可见附件。R3在Main捕获完整target/selection/text/有序manifest与photoSourceId，再核前后一致，并保持独立持久metadata及全部原件图；不制造附件、不改selection，也不只是把断言计数改成0。

R3只对child-purpose的既有onHold加固定tag/原Throwable观测；原strong hold、不可确认状态和旧9/13行为不变。新SDK和新run两方法通过，不据此改判旧UNKNOWN或宣称唯一原始throw位置。

原实例后来不可见，Root准备受控收尾前已经没有该VM、原主机PID或监听；没有执行emu kill。原owner及Root查询原两个工具session均返回Unknown process id，无可恢复原wait/EOF或晚到final。退出原因仍待验；进程缺失不能补作wait=0。原UNKNOWN SHA `9dc6730b4c6e031e0784f2d8f6241ca3b248c427044b1bd2723959dca85eabed`、原数据/KEY与诊断保留。临时只读调试的准确forward及编译classes已回收，没有重放原scope或业务操作。

## 收尾及剩余

本次R3 VM正常emu kill，原launcher持有Popen实际wait=0、双EOF完成；相关原进程和监听释放，5559仅TCP TIME_WAIT。R66原基准1,440,178,176B，当前1,613,541,376B，累计173,363,200B低于512MiB cap；free为22,522,290,176B。当前包、必要回退/失败资料、R66数据及最小原证据按具体依赖保留，24小时内复核；预算不因新目录或退役包重置。

阶段74源5242f173已由PR123合入main 48884eea；源CI11240、准确main CI11242均成功，stage74三类引用已清理。自动同步11243成功，公开提交77f6e144的tree及Gitea来源trailer匹配。没有手推公开主线。

下一顺序：其余系统迁移域与已有资料合并/SAF、Main交互/界面，再语音/未读/生命周期；P8/P9独立项并行。P9当前36源整合候选尚须重锁本轮新两源、SDK及Host链，不能冒用旧包。真实Provider/OS授权/相机输出、完整24项、自然I/O/崩溃、UI/手机和整包互通继续待验。真实模型账本221+0及P4 205+1 sealed unknown保持；任务/项目CRUD、F75、iOS A6暂停。
