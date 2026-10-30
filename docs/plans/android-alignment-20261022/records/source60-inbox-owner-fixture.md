# Source60：原 owner Intent 生命周期收尾与补验

2026-10-30；基于 `38d08ec5`，阶段范围与逐项指纹见[验收索引](../parallel/sixtieth-stage-inbox-owner-fixture-native.json)。本次5个实际 Main/controller/Session/inbox Native 方法全部限定通过；Root独立核对305份原生阶段调用及72份安装阶段调用。

## 修正与对照

Source55第四项的失败来自本机 androidx.test:core:1.7.0：ActivityScenario 监听器按原启动 Intent 匹配生命周期事件，真实 Main.onNewIntent 将 ACTION_MAIN 正确更新为 ACTION_SEND 后，监听器忽略销毁事件。本次保存完整 launch Intent，在原有全部断言、回调及清理之后，以 NonCancellable/Main finally 恢复测试持有的 Activity Intent；恢复失败保留原异常并附加 suppressed 错误。没有重新分发 Intent，五个测试方法正文及断言、产品 Main 和 iOS 均未更改。

796规范输入及805展开输入编译通过，Root逐项读回源码和三包指纹。新版 App/runner 实际 APK 均不同，未假定旧包等价或仅替换 runner。旧 Source55 失败和第五项未运行记录保持原样。

## 安装与原件保护

App/test 各执行一次 -r 安装，均取得明确成功；未安装 examiner。安装前后完整核对 boot、身份、UID、默认资料与麦克风权限，同时采集两棵具名原树的完整 stat/nlink 和全部 tar 正文：收件箱原候选109成员、4,062,576正规文件字节；语音恢复15成员、16,641字节。升级前后原字节及元数据完全不变。此证据不证明 Keystore alias 已可解密，原声读回仍是下一项。

## 五项结果

| 场景                                             | 本次结果                     |
| ------------------------------------------------ | ---------------------------- |
| 原 inbox owner 与部分清理交接                    | 通过；76成员完整采集         |
| 忙碌 kernel owner 拒绝迁移，原清理继续           | 通过；20成员完整采集         |
| Provider/metadata IO 期间实际 retry/add 阻止迁移 | 通过；23成员完整采集         |
| 获取 owner 期间原输入拒绝暂停并回到原 owner 一次 | 补验通过；23成员；旧失败留档 |
| 迟到输入使冻结导出失效，再交接原 inbox 一次      | 首次执行通过；26成员         |

每项均使用新的 lease/run、精确单方法 Native，取得唯一 method 0、OK (1 test)、overall -1；完整 before/after 默认保护不变，所有 run 正规文件正文与实际 stat/nlink 对齐，固定 cache 终端无残留，实际资源锁已释放。未新增 Host、模型或麦克风调用，没有重放旧操作。

## 当前范围与后续

按各自明确候选，23方法库存现有22项限定通过，剩余1项硬链接夹具 EACCES 待验；这是 Source55 的17个非 owner 结果与 Source60 的5个 owner 结果，不表示 SDK60 全23项重新通过。中途由 Native After 删除的 Provider/envelope 原件、完整 UI/SAF/AX/像素、48MiB、API37、真机和双端真实 DEV 均未升级为通过。

接续选择原件/最近照片及权限尝试的完整迁移、语音原声补证，再推进未读和生命周期。P8/P9并行，完整24组迁移仍开放；任务/项目和 iOS A6 继续暂停。冻结包和树外必要证据保留供下一阶段，构建副本按阶段核对回收。
