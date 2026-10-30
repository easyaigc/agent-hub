# Source55：收件箱原候选冷恢复与实际 owner

2026-10-30；基于 `d840f598`，保持当前805输入及SDK55三包。限定证据见[阶段范围](../parallel/fifty-fifth-stage-inbox-owner-cold-native.json)。7个精确Native方法为6通过、1已知失败；Root独立核419份原生阶段调用与124份进程观察调用，共543份。

## 原候选三进程闭环

实际PID15983写入原envelope/候选，19406激活同一原候选，20752读取同一回执并再次导出。后两阶段先完整采样同一旧run，再核原source所有正规文件、候选全部密文和envelope不变；最后保留activation回执及更晚草稿的完整活动树。三份JSON原回执和完整stat/nlink/tar分别关联，未重stage、重建或重放原操作。

两次下一阶段前的kernel核对各有49份原始调用，完整PS为120/121行；真实shell readproc组、proc挂载、init/system_server/self及不同UID的test包均正向确认，原准确PID和目标App主/子进程缺席，双完整默认/包/boot/权限保护28a不变。第一次观察缺少test包正见证，只有26份调用且没有after快照，原失败保持；随后独立owner UI自然产生的标准test EmptyActivity使实际条件变化，才用新lease执行新的只读核对，没有启动或维持见证进程。

## 实际 Main / Controller / Session / inbox

前三项通过：完整导出及原inbox对象交接；忙碌kernel owner拒绝迁移且原清理继续；实际UI retry/add在Provider/metadata IO未结束时阻止迁移。完整私有run的所有文件逐项采集，固定cache父目录只读列直接项，准确provider scratch与临时export终端均缺席。

中途Provider文件/envelope由Native自身After删除，父层没有独立采集这些中间原件；完整UI/SAF/像素与真实Provider/DEV保持待验，不能用结果JSON代替原字节。未新增Host、模型或麦克风调用。

## 生命周期夹具失败与修正方向

第四项最终JUnit -2：ActivityScenario.close仍认为生命周期为RESUMED，整项不接受，第五项未运行。Root读取本机实际androidx.test:core:1.7.0字节码：监听器先比较启动Intent与Activity当前Intent的action/data/type/package/component/categories，变化时直接忽略事件；本测试从ACTION_MAIN启动，再通过真实Main.onNewIntent正确替换为ACTION_SEND。原AM回执显示Launcher resumed，不把监听器缓存值当真实Activity仍存活。

下一步只修测试收尾：先保存原launchIntent，全部回调/owner断言与清理完成后，在After finally恢复本地Activity Intent，使原Scenario.close能观察销毁。产品receive、原输入/回执/操作ID及全部断言不改，不重放onNewIntent；修正及新包重验尚未完成，旧失败不覆盖。

Source55当前23项为17限定通过、2已知失败（含上一组硬链接夹具）、4未运行（owner第五项、voiceUI3）。完整24组迁移、48MiB/AX/像素/物理/双端真实DEV仍开放；任务项目/iOS A6暂停，未知原操作不查询或重放。
