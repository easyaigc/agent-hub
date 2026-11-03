# 阶段76：P9当前主线构建与验收链

关联功能：F52、F54、F56。状态：工具与源码整合辅助通过；真实SystemUI Native未通过，不关闭P9。原生细节另见[原生记录](stage76-p9-native.md)。

本轮保留已提交P7闭合26源与原P9十源，构成显式36 overlay/893项输入，新profile p9-current-main-v2。旧profile不借新范围扩大；共享SDK capture只增加独立新入口。十项P9 debug/test/examiner资料从原开发现场差异引入，原现场未覆盖或清空。生产UI/网络/模型和任务项目功能未变。

第一套当前主线三包SDK seal为534976c8a2ddc4234c78f39991e59ed4d34d9d9f0b5805a22be7d07b5c2383aa；Root复核103份封存材料、893项源码、19条原wait=0/rawComplete、外层实际wait=0/双EOF/fsync。App f496cd9b、Test b098ed91、Examiner74c2d11e，均为独立验收包，同证书。此为SDK辅助证据。

R3桥正式正向发现原模块路径冲突：SDK执行工作树的原capture/first，桥加载另一目录同字节副本。原验证正确拒绝，未发生Native。Root R4改用实际封存路径，从同一FD校验/编译两项固定公共源码；其他SDK/回执/准入/ledger继续私有读取。没有伪装__file__、降低来源校验或重跑SDK。独立静态审查通过，原B正式verify_sdk与current_source_guard均接受；42个已执行纯变体符合预期。

九类纯向量未执行：旧SDK、混合tasks/packages、旧source pin、旧admission、protocol pin、future HEAD、actual status/path、未列入build input、legacy operation碰撞。不得把其静态实现或正向通过写成全部37类动态覆盖。原R3一项正向失败与未执行范围保留，不以R4结果回填。

R5已重新封存三包SDK，seal为ecd210dbb5c92a42eda62765970ffafef093fd81b808f6fe28021025f43fb3f7，103材料/19实际wait及893/36当前源码由Root复核；同FD真实T模块验证通过。旧R4的App/Test与R5字节相同，核对后只退役两份重复包，旧5349 bundle成为历史部分资料，独有Examiner及原证据保留，不能声称仍可完整复用。

R5原生采到Normal角色的18项事实，随后在Dimmed背景拒绝，见原生档案。R6将准确Normal/Dimmed两背景作为有限family，每role各一clip、总最多2，保留原几何及每role18项新鲜事实，policy升级v3。独立源码审查及230项独立literal合成Host用例通过；另271项proposal驱动执行仅为辅助证据。R6重新封存三包SDK，seal为4aab9395cd81e60c0177f19723d32110ed04774ce1363605c0aca00c690d527d，Root复核103材料/19原wait/893源，并核同FD实际T路径；不是沿用ecd2。

R6实际原生采到两背景各自的新事实后，在expanded内容容器被严格拒绝，仍无PNG/点击，完整P9未通过。本阶段只收束已验证的源码工具、契约和限定原生诊断范围，完整SystemUI视觉/AX包含关系单列待验，按优先级继续P7；不新增任意容器例外、不把失败转通过。全部原失败/诊断/回执保留，过程原流在私有验收证据，公开文档不含凭据或真实聊天。

最终完整 `pnpm check` 仅执行一次，实际wait=0、双EOF/fsync，耗时727.553秒；42组共1518项，1456通过、62跳过、0失败/取消。独立parity 10/10、生成、格式和两类diff检查通过。Root重新核对原流摘要/计数、893项源码及HEAD/index/21项状态前后一致，并从本任务官方子Agent记录核实实际模型为GPT-6.1 Terra、medium；入口没有可核验速度参数。此检查不替代失败的原生场景或完整产品DEV。

R5的App/Test与当前R6逐字节相同，原失败scope/权限/VM均收尾后仅回收这两份重复包，保留其独有Examiner与原始证据；旧ecd2 bundle成为历史部分资料。当前完整R6三包与阶段75 R3成功回退包继续保留供后续有界验收，旧包不再声称可完整复用。

交付：[PR126](https://git.51easyai.com/BCAI/agent-hub-app/pulls/126) 源22e236ab48f938000bb752501e8627f36cdf446f合入main 3dde5760015f84d1be7f1b1c583e32d78cf3b505；Root核21文件及893构建源码与已验输入相符，merge双父/tree一致。源CI11258（workspace27121/ios27122）、主线CI11260（27124/27125）与自动同步11261/27126均成功；公开提交879f19e084bf0f4d85989d86bfe4ad4f053762d3的tree与来源trailer匹配。stage76本地/origin/tracking引用已清理，工作树后续由阶段77复用，原失败不升级。
