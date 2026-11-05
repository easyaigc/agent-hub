# 阶段101：F59 current-generation 只读源

阶段100已提交推送9db08958；完整回归绑定此前aeda809b，47组1594项/1526通过/68跳过/0失败。9db纯文档之后的iOS framework/App编译实际通过（164秒、双架构和签名核对），没有iPhone/界面/iOS A6验收；其KMP源和当前Native规则一致。

## 实现

三份生产文件提供准确当前generation单个F59 child的只读快照。NativeSessionStorage仅追加F59专用入口、既有key解码与窄codec，其他旧字节保持；不prepare child/key、不借用普通writer、不移植源设备key。入口检查实际Session owner/raw Frozen/root/operation/generation，callback限线程/lease范围，返回前和出版前重新校验。

目录FD保持，regular单FD完整读取、UID/GID/mode/nlink/NOFOLLOW、前后identity和全cipher SHA一致；格式3/4、名称/AAD与原journal完整闭合。控制lock只lstat，不额外打开/关闭；未知条目、sidecar、孤立块和不明缺失均拒绝。

COMPLETED缺失必须先让原journal只读FD实际fsync、close以及child/parent目录同步返回；不支持或失败拒绝，不以普通decode充当durable证明。close未知保留实际owner，不重试关闭或提前释放原freeze。该有限观察不声称原子filesystem snapshot或恶意同UID保护。

Root独审修正两点：DirectoryStream逐项计数到限即拒绝，避免list全量物化后才检查；保留当前generation目录对象，在后续pointer/source核对持续检查其路径与持有FD身份。

## 编译与测试资格

普通Android App/Test编译已通过，实际wait0/双EOF/关闭，生产/Gradle/Android/KMP/protocol输入前后保持，APK v2签名核对；这是普通编译，不是本轮sealed SDK或Native。

八个新具名方法使用真实Session.restore/quiesce与Frozen、唯一UUID的私有Context/KeyStore和端口deny断言：目录边界/FD归还、实际record替换、既有namespace/key不创建、UNKNOWN原件/回调生命周期、sidecar/orphan、错误key/真实generation替换、COMPLETED部分退休的实际barrier、不明缺失/坏终态receipt。测试源码补齐消费者主动异常的assertSame/FD与源状态/同owner重开，以及live owner失效后真正执行的blob/text拒绝、旧/现场blob零输出。所有八方法尚未运行，25场景保留待验；未使用Assume或把平台错误吞为通过。

追加独立SDK purpose `p7-f59-frozen-source-v1`：准确940输入、123覆盖、122固定after+self，仅两App/Test assemble和八个具名资格。原214 AST中213非终结节点逐个保持，原main相同后移；旧所有purpose/930/31门槛不放宽。静态审阅通过后仍须以提交HEAD/干净状态重新guard、工具/self/after/review pins、实际封存包和新boot/UID/原数据准入；准备不是执行授权。

## 功能、交互、界面与后续

当前只是单child读取入口，旧generation family仍未纳入，未接新版流式大容量封包/完整源库存、candidate/pointer lineage、目的端惰性原件/冷恢复或iOS消费者。Export/Activation整包F59拒绝与旧v4额度保持；不能用本入口或通过编译关闭完整迁移/24域。

没有新增页面或Main交互；F59入口/UNKNOWN、原receipt、迁移preview/激活/失败、语言/方向/字号/AX及真实Host/手机逐项继续。Android只读内核与现有iOS功能差异保留；iOS prepared/UNKNOWN404安全候选未采用，A6暂停。任务/项目、F75暂停不变。

## 资源

开始编译前按实际空间门槛回收本任务已完成产物：iOS预编译缓存306,163,712字节；精确118个已标记合成fixture及私有已完成缓存合计1,222,410,240已分配字节。原私有Gradle daemon按准确PID/birth/home/currentDir/IDLE核对后正常停止；其他服务不动。全盘可用空间变化未全部归因本次清理，旧charge不退款。App/framework/符号、当前回退包、未知目录和必要证据保护；当前新包用于后续准入。
