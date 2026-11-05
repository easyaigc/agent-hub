# 阶段106：续接 eae10a60 主线

安卓源为810ad03（前一阶段已登记5203513的942 SDK及15项API33 Native）。目标是Gitea实际main eae10a60，三方基线db977684；这是把主线续接进安卓专用分支，PR145和安卓后续阶段尚未合入main。

| 主线节点         | iOS／共享变化                                                  | Android对照                                                                                      |
| ---------------- | -------------------------------------------------------------- | ------------------------------------------------------------------------------------------------ |
| PR141 / 8b282435 | Windows原桌面建议Responses采集与受保护Host升级；新增依赖及回归 | 保留Host源码和检查，不将Windows采集或旧手机结果变成Android P5/P6/F69通过                         |
| PR148 / 322c07fc | iOS消费后的原回执、晚到结果与恢复用例                          | 维持Android原意图/原ID、UNKNOWN不重放；原回执/严格outbox仍单列                                   |
| PR149 / f7728d7e | EasyWork已有调用端与配对参数说明                               | 按实际入口/能力对照；文档不升级Android UI、LAN或Relay验证                                        |
| PR150 / 8ec86eca | iOS真实LAN设备撤权；原绑定保护                                 | Android需旧通道/新握手/同身份拒绝和原连接保持的独立验收                                          |
| PR151 / 4c55e1a1 | 同原ID改变正文/设置/附件/method等十类冲突                      | 显式速度候选、原回执设备/终态/内层绑定与真实十类逐项验收，不重放原请求                           |
| PR152 / 25207328 | iOS原回执等待与新发送owner隔离                                 | Android已具备原operation与draft/busy owner保护的局部证据，实际旧A/新B链路待验                    |
| PR153 / eae10a60 | iOS连接编辑后旧查询隔离；SessionStore生产差异为DEBUG验收计数   | Android已定位连接代次/旧查询结果发布缺口，须同时阻止旧持久化/界面和释放新owner；不碰普通导航语义 |

六份冲突文档按语义合并：Windows与iOS主线证据、Android阶段档案分别保留；manifest按功能及变更ID合并，F04/F61合并修订为30/12，Android F61仍in-progress/not-verified，生成矩阵由合并manifest重建。根check保留两侧91项，mobile-parity保留10项，无旧回归删除。

新合并改变package.json/pnpm-lock两个已绑定SDK输入，旧942/15仍仅属于520原指纹，不作为此候选APK或真实DEV通过。新依赖、全pnpm check及相关iOS构建按实际结果另登记；新的Android SDK scope将与后续Long inner framing组合，不使用旧purpose宽松接纳新字节。

不发布或部署Host，不新增真实模型，不重放旧操作。P8速度和旧查询、P9 API36诊断另行并行准备；任务/项目、F75、iOS A6继续暂停，Main界面/真机/完整迁移保持开放。
