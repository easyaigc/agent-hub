# P4 主线增量与当前候选复验

本档案保存同步主线后的差异、对照与实际结果；当前范围和优先级以总表为准。

### P4-4o.25 同步主线性能与附件增量，重验现有 P4

2026-10-25：23dde56先提交、远端/恢复包/生命周期核对后继续。只读发现主线已从fd9bb721前进到60802b11，含PR21/22/23；7条实现/集成节点按父提交、路径、完整patch SHA逐条登记N012–N018。不是只看最终diff：da2e8971性能、946d1f2e附件首批、01245003性能入主线、f8a70b0a附件分支整合性能、a849a632最近项折叠、7983e0f2附件入主线、60802b11折叠入主线均保留。新增F71/UI04/07/12与S04/05/11/13/18纳入总表和增量索引，沿用专项详细计划的所有权限/状态/取消/槽位/草稿代次/先本地准备后发送上传断言；Android系统入口、外部分享/收件箱与P7保持暂停，未用本次P4结果记为通过。

合入同一主线候选，生产源码没有文本冲突。五处文档/JSON冲突差异合并：设计/验收保留两侧段落，README保留两个主题入口并去旧重复摘要，manifest变化记录取稳定ID并集，双方新增sourceNotes都追加，revision按两侧增量相加；生成产品表重新生成。不覆盖历史失败/暂停/平台状态。既有iOS快速附件与双端优化保留原证据；本轮只为当前Android/P4增加限定结果。

主线优化影响完整目标key、静默订阅/有用进度补读预算、历史投影/Markdown复用、无变化片段认证复用写入、侧栏隐藏动画、iOS状态发布/持久化和共享线程修订；合并后构建82任务通过。当前同包三环境六组横竖屏/主题/字号专项12项、各10项审批UI30项、各16项实际原生历史/缓存/加密/订阅/投影边界48项，共90项最终选定矩阵通过；额外API37单例诊断1项通过。窗口75%、六控件逐一至少44dp完整触达、分页/关闭/重开草稿、真实SDK选择及原HTTP提交断言保持。性能边界为原生辅助/合成工况，不宣称复用了上游热量/CPU数字。36张当前原生选图与11张真实/预检自有窗口图已看，其他图保留。

首轮API37整类10项中出现一次问题提交仍禁用，完整失败日志、已核SDK选择/掩码证明和该轮默认存储检查保留；同包单例和整类各一次重跑通过，未确定输入/读取状态或测试时序根因，不按重跑抹除失败。此稳定性缺口列下一优先级，P4完整题型/AX/视觉仍开放。截图辅助误用系统Python缺Pillow后切回既有专用Python，不涉及产品输入。

最终手机零输入预检与真实原owner整例通过，独立Electron和canonical核3唯一输入、3完成Turn、真实旧问题409、新请求替换、0待答；Host旧进程退出/新PID、原回答completed及原操作指纹/Turn匹配，所有验收Host退出、端口关闭。新增3输入，累计195/P4累计181；所有旧未答/失败操作封存，零重放。共享核心JVM/iOS simulator测试、三框架与跨语言向量及pnpm build:ios通过；都是辅助编译/规则结果，不新增iPhone或P7/任务项目业务通过。pnpm check完整生产/工具链检查通过，最终文档再核parity/format。正式Host未部署，当前安卓分支待PR，不代替Android完整阶段退出门槛。

证据：[P4-4o.25 结构化记录](../../../acceptance/mobile-evidence/android-alignment-P4.json#mainIncrementAlignmentCheckpoint)。精确阶段提交/远端与树外恢复包另行核对；不改写历史失败。

### 2026-10-25 第二次主线增量与连续执行

已接受的安卓菜单、长字段两个功能提交及格式收尾在原专用分支保留。当前main新增9个节点已逐一登记至upstream-20261025-evening.json，保留完整父提交、路径与首父补丁指纹；来源冻结217项、P4既有20功能/27节点不改写。跨项目建议、具体资源授权、空会话原owner恢复按P1/P4/P5分别对照；仅生成表冲突，依据合并后的manifest重新生成，双方产品/验收/设计记录均保留。Host/Adapter统一构建，既有Android产品证据与新Host组合预检分开，不用旧Host证明新实现。

用户本次明确要求连续完成P4、P5、P6、Android P7及后续阶段，不逐检查点等待确认。Android P7恢复排程，包括其系统/语音/存储/未读及F71范围；历史暂停证明不删除，任务/项目业务仍暂缓，也不扩大到无关iOS A6实验。受环境限制的API37横屏、蜂窝/VPN、人工归档及其他系统门槛继续单列。每阶段仍先更新总表/对应档案、提交推送与核远端，再继续下一阶段。

### PR #27 main delivery

Prior accepted commits352f9ff/85f22fd and formatting6f04da1 were preserved in candidatec573f127, merged at9b4b3265 after workspace/iOS CI success. Candidate/main trees match; exact old local and remote Android branch refs are removed. Continuation branch codex/android-p4-four-decisions-20261025 retains the same accepted tree. Recovery bundle SHA256: 85db281a43b3a14f5f04d37b910feba1a7a8ee1031e54bfda39782de24734573. Actual PR: [#27](https://git.51easyai.com/BCAI/agent-hub-app/pulls/27). Private-forge URL attachment is unsupported by the app tool; actual merge and remote result independently verified. New development is separate from this frozen merge.

### Main8c7e3716 source reconciliation after P4-4o.30

f7018a63 was committed/pushed and independently saved before this merge. Main advanced by4 nodes (N028-N031):a gated physical-iOS loaded-chat lifecycle scaffold, a merge retaining accepted Android work, review documentation and PR28. Source contracts/product Host/Adapter/Android remain unchanged by this increment; the added native method is not executed here and carries no new physical/Android/Windows pass. Both change-record tails are preserved, generated parity is regenerated. The existing iOS question waveform failure was compared against pre-change dc2cc53 in the same isolated simulator; later scaffold-only source does not retroactively become that baseline. Business task/project deferral and unrelated iOS A6 pause remain; AndroidP7 follows current human authorization. Whole-source-node closures remain false; proceed with original-draft file import alignment after this source reconciliation.
