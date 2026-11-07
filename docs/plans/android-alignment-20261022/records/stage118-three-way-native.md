# 阶段118：三方回执原生回归与实际只读失败

日期：2026-11-07。接续[阶段117](stage117-three-way-core.md)。当前子功能C1-RID，涉及F43/F44/F46/F61的EasyWork原回执身份校验；未扩为整项C1、75F或完整四格通过。代码实现提交为`867b0f2ba82c356aee1c264c88db8d378b1fe457`，本阶段原生构建/运行时工作树HEAD为文档提交`57ce9e29a236537649f929e3161a21f26a975149`，源码与后续进度文档分开绑定。

## iPhone实际只读运行

在既有配对的物理iPhone上安装独立验收版，通过Xcode/CoreDevice/XCTest实际执行`EasyWorkHostedNativeTests/testOriginalOperationReceiptAndMissingQueryWithoutReplay`一项；返回失败，原保存Host的各路地址不可达，最终原本机Host地址拒绝连接。未取得原operation或缺失ID的线上回执；不能把本地目标/凭据预检、Relay health或签名编译当作原owner在线证明，也不能声称失败后未执行的UI/状态断言已通过。

本轮预算和实际均为零新模型输入、零业务写命令、零原操作重放。独立桌面只读数据库核对原operation唯一、thread/turn一致，三张受保护表前后计数/摘要一致；静态数据库completed不等于规范化线上回执状态，原期望不改。原签名App/Test可执行指纹与阶段117一致，最小失败xcresult、摘要和只读保护证据保存在私有档案。

关闭条件仍为恢复同一准确target/profile/原owner及受支持路由，核实鉴权和绑定后，用新的测试case查询同一原operation；不重新发送原输入、不借身份不明的旧Electron或另一个桌面实例顶替。当前需要新的独立EasyWork DEV接入地址或私有连接参数路径，已集中请求；此环境项单列ENV-10。

## Android当前源码原生组件

旧冻结SDK两次预检均未运行测试：一轮旧包与当前完整输入不匹配；另一轮旧包1923项输入匹配，但最新typed-source guard仍有2项差异。原拒绝和各自指纹保留，未修改guard、未宣称旧SDK通过，也未把旧APK改标为当前包。

随后按仓库正式入口从当前源码重新构建：

```sh
pnpm exec node scripts/mobile/gradle.mjs :android:assembleDebug :android:assembleDebugAndroidTest -PagentHubAcceptance=true --rerun-tasks
```

使用官方Android SDK和`androidx.test.runner.AndroidJUnitRunner`，仅在本任务新建的API33 arm64独占模拟器具名运行`com.agenthub.android.MobileEasyWorkQueueReceiptTest`：声明18、执行18、通过18、失败0，runner返回`OK (18 tests)`和成功instrumentation code，约3.543秒。此类使用合成加密peer，属于原生组件验证，未连接真实Host/模型，不增加真实DEV或完整功能通过数。各用例按需断言原查询1或2次，未采集总查询次数，不能声称总体运行计数。

| 当前候选            | SHA-256                                                            |
| ------------------- | ------------------------------------------------------------------ |
| 独立Android App APK | `a1fd486c35fd22f2217156374097363c2aa551bcc7ef3f71bff65618bcd72a2f` |
| Android Test APK    | `ed89dea19991420c8e8d5ff8da1ee6eb8fd99be5221f29f758d3dfe8b3ce5bfb` |

安装后实际包读回指纹匹配；43项源assets（含31项protocol fixture）与包内一致，Git tracked文件构建前后完整摘要相同。**Gradle缓存依赖仅在构建后采集摘要，缺构建前完整依赖证据，不能宣称完整构建输入前后一致或可重现性已通过。** 包、设备boot/API/ABI/user、runner与安装读回证据逐项保存在私有档案；总控独立复核运行工具回执、候选摘要及清理状态。

## 两个方面、三道验收门

| 范围                    | 功能                                                  | 交互 / 界面                              | 当前结论         |
| ----------------------- | ----------------------------------------------------- | ---------------------------------------- | ---------------- |
| iOS×EasyWork C1-RID     | 守卫实现、52项辅助及签名构建通过；实际只读1项连接失败 | 原回执状态/UI/错误与恢复的在线核对未完成 | 待验，不合代码   |
| Android×EasyWork C1-RID | 既有守卫；当前18项原生组件通过                        | 真实桌面/原owner及手机UI未在本轮关闭     | 限定组件通过     |
| iOS/Android×Codex       | 此wire校验为EasyWork专属；Codex生产路径未改           | 共同聊天功能仍须按各自契约验收           | 不虚填本子例通过 |
| 完整C1、75F、迁移24组   | 本轮未整项关闭                                        | 每项仍按功能/交互/界面及四格范围执行     | 0完整四格新通过  |

## 交付与收尾

三方计划、矩阵和优先级通过[PR164](https://git.51easyai.com/BCAI/agent-hub-app/pulls/164)独立合入Gitea main：源`540038d2d7610e598c8065a172c47465c642bad7`，实际合并及核实远端main为`289b1c6f6a58d06799c202e669d5344c89b7761b`，iOS/workspace两项CI实际成功。计划工作树和本地/远端计划分支已正常删除并关闭生命周期登记。

代码[PR163](https://git.51easyai.com/BCAI/agent-hub-app/pulls/163)保持draft、未合并；`57ce9e29a236537649f929e3161a21f26a975149`的两项CI成功，后续进度提交独立核对，不把CI等同真实DEV验收。同步main计划时仅四个文档冲突，由总控保留“计划已交付、代码仍草稿”的区分并更新最新结果；未改产品语义或测试预期。当前未决源码树保持，待准确DEV恢复后继续本功能；其他不相交工作线不因ENV-10暂停。

本轮独占Android模拟器已正常关闭并删除，PID/端口释放；iPhone runner、窄域代理及临时脚本/配置已退出或清理。签名候选、当前Android候选和原stage115唯一APK对保留，旧stage115位置另写可读指针并校验，未改写旧证明。当前候选不冒充正式产品包。必要失败/运行证据、依赖限制、精确路径/字节数、负责人、释放条件和24小时内复核点存私有台账；共享构建环境与其他任务进程不因本次使用而删除。未创建正式侧栏聊天，不声称完成聊天归档。
