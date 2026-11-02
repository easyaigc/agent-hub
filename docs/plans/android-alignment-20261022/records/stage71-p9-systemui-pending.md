# 阶段71：P9 SystemUI 与传输待验

状态：待验，不关闭 P9。独立环境失败继续单列，P7 开发照常推进。

原 API36 合成通知 scope 的 examiner READY、producer 原 instrumentation `OK (1 test)` 有实际回执；observer 在点击前被“可见子节点必须位于所选通知行裁剪内”的边界拒绝。没有点击，也没有 AX/PNG。原子节点几何未被记录，原因仍未确认；不通过放宽边界、抓全屏或重放原通知来消除失败。

私有候选仅在原失败分支补有限 index/parent/depth/ordinal、根/子矩形、resource ID/class 诊断，不采集正文或描述。修正诊断的惰性求值及异常保留后，R2 三包 SDK 辅助通过；这不是通知 UI 通过，也不把私有候选视为主线实现。

新独立 scope 安装三包后，读取 App APK 的原 adb pull 实际 exit=1、双流 EOF，仅留下87%进度；在 bootstrap、observer、producer、权限操作与点击之前结束，未评估 crop。25 个原 Host worker 已实际退出。随后独立只读核对同 boot/user/UID/路径和三个安装 APK 完整 SHA，均与封存包一致；新只读 pull 一次成功，仅证明此次传输正常，原失败原因和原 scope 的失败状态保持。

原首轮 scope 的60个 Host worker及原设备进程已核退出；Root 在同环境下单次撤回本次授予的通知权限，denied 与 flags 精确回到初始状态。后续安装失败 scope 没有权限操作，初始权限未被驱动记录，不能称为“恢复通过”。旧操作、意图、回执和 one-shot ledger 均保留。

两个已封存、无后续执行依赖的自有 API36 AVD 均正常停机，Root 亲自等到原执行会话返回0；按各自私有 AVD_HOME 经官方工具删除。删除前分配空间上限分别为 1,302,118,400 与 1,319,354,368 字节；必要 SDK/source/raw/权限/关闭回执在磁盘外保留，原始指纹已独立核对。其他模拟器、手机、Host 和既有 API33 P7 现场保持。

后续：以新的具名场景继续 SystemUI 实际点击与裁剪诊断；真实 Host/供应商/OEM、权限/声音/隐私/路由/角标、低 API/字体/TalkBack、API37 横屏及物理 DEV 仍分列。未知真实操作不重放，任务/项目管理及业务通知继续暂停。
