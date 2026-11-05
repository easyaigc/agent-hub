# 阶段87A：同步已合入主线的共同修复

阶段86已提交并推送0dca0c8a。准备下一轮FD/metadata/几何共包期间，主线已推进到78b9dda6，包含PR136的F73共享额度解析与PR137的Host采样诊断/迟到重复回包验收。先同步再绑定新SDK，不把旧905源码或旧包的通过迁到新指纹。

## 冲突取舍

主线已实现相同迟到回复目标：完整逻辑读取完成后才释放唯一重复回包，独立确认原Native worker退出，再下一次读取；cold64另加第二帧150ms确定性回归，原750ms/1800ms和拒绝/退休断言保持。因此采用主线fixture/test，不叠加本地未发布的SAB替代方案；本地1c36ad4d保留在CI分支祖先，不强推/丢提交。

parity manifest按共同祖先合并全部sourceAliases、按ID合并features/changes，既有阶段84/85/86与主线F69/F73说明都保留。acceptance/design保留两侧记录，product从合并后的manifest和主线template重新生成，不手工裁切大表。

F73只拒绝已知百分比字段的非number、非有限或负数；合法超过100%的值仍保留，不擅自增加上限。产品UI、文案和布局保持。Host诊断/空文件采集规则按主线来源保留，此次没有部署Host或联系其他电脑，不将Android建议UI/A6改判通过。

## 新验收指纹

相对SDK86的905输入，当前同步改变package.json以及UsageRules.kt/UsageRulesTest.kt三项；主线新增Host源码在其原边界内维护。旧SDK86不能作为当前源码的新包证明。已审查的原八文件FD/metadata/P8提案保留，下一步只重锁新目的的62overlay/61fixed+self与905输入，既有旧Capture目的/映射/配方不改。原来8源/60overlay的静态提案并未执行或冒充新合并树通过。

P7原0b2/880/e6写入及UNKNOWN保留不重放；Root正常关闭原VM后保留全部原目录/ACK/密文/密钥。新轮需新SDK/冷启动/UUID/Root/runtime。P8仅失败几何诊断，成功门不放宽；F73设备显示、新Host真实来源及完整24域/F59/手机矩阵另记待验。任务/项目/F75/iOS A6继续暂缓。

## 校验与交付

当前语义冲突由主会话处理，无unmerged；限定生成/格式、完整pnpm check及同步merge提交随后登记。CI专用树与Android开发树分别记录，不互相冒充完整树通过；源CI/主线CI/公开同步仍按准确提交实际结果验收。
