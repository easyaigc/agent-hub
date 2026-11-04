# 阶段81：原窗口归属修复与AX后续

从PR131实际主线fda80542继续。该主线tree与阶段80源0ba8d903一致，Source79/80失败与UNKNOWN保持；任务/项目CRUD、F75和iOS A6暂停。

P8原绑定在ContextWrapper链找不到准确Activity。本轮实际元数据确认原Activity decor为DecorContext→ContextImpl，链中没有Activity；另一个type2对话框包含准确Activity，两个params token为同一非空引用。原拒绝是错误的context假设；不能用Activity的AX窗口替代原Dialog。

新增首次失败时的本进程公开元数据：至多4个root、每个16层Context类、原500ms合作期限及8192字节；每个公开getter读前/后检查同一期限，记录类/类型、原对象关系、token存在和引用相等、焦点/附着状态。不取content/children/AX节点、不输出token值或隐藏属性，不增加输入。期限不是硬中断保证；耗尽只留UNKNOWN，不继续观察。原归属拒绝仍抛出原primary；secondary仅class，失败可选详情不改变Host通过schema。

原Native单源及三源集成均已独审通过。独立p8-window-owner-metadata-v1构建目的准确54 overlay、53固定+self、899预期源；旧SAF/MainUI地图、recipe、guard保持。诊断SDK223a5b3d构建通过，899源/92材料/17工具wait独立复核；App包1abfab33、Test635008e3。唯一一次严格Native为JUnit1项失败，首中文case在字段输入前保留原拒绝，英文未执行。实际失败元数据OBSERVED，AX获取0，elapsed0ms；诊断不能计作产品通过。

原outer实际wait1、runner实际wait0但JUnit失败；原App进程同boot已退出，Test进程在VM关闭前仍cached，不宣称自然退出。原VM79100正常wait0、双流EOF与raw摘要复核，5558/5559自然释放；失败scope/alias和两条历史UNKNOWN仍保留。

后续修复保留准确Activity/window/decor/非空params token强引用。只允许原decor对象按身份通过；其他attached root仍须准确Activity context链、TYPE_APPLICATION与原token同引用。Dialog焦点、原decor引用、AX window ID和每次输入前后检查保持。新binding-v2明确owner policy；独立p8-activity-decor-owner-v1固定54/899构建目的，旧目的全部冻结。源码独审、重建和实际长字段/AX/UI结果分别登记，不因诊断或静态修复关闭P8。

## 修复后的实际运行

新SDK7fbfee97构建通过，App1abfab33不变，Test8b2ed4ea；899源、92材料与17工具wait独立复核。严格Native唯一一次新运行已建立binding-v2：准确Dialog window13，原Activity window12，原Activity/decor/token保留策略通过；名称replacement和IME两个原输入均CONFIRMED。

随后easywork-name AX检查在原5000ms期限耗尽：18个fresh root样本，最后完整样本19节点/15个ID extra，原Compose id217始终缺失；merged/unmerged id仍217。失败诊断UNKNOWN，没有完成树遍历，不能据此认定根因或放宽锚点。JUnit1项失败、英文和后续13字段未执行，严格Host通过证明未完成。继续核对安装的Compose1.12.1与精确字段身份，不重试旧输入。

原outer99300实际wait1，runner wait0但JUnit失败；原VM98790正常wait0、双流EOF和raw摘要已独立核对，5558/5559自然释放。原App3975在停VM前已退出，Test4124仍存在，不计自然退出。新失败scope/alias保留；原两条UNKNOWN及数据/密钥不查询、不重放、不清除。当前仅原窗口身份及两个输入有界通过，完整P8/AX/UI/真实DEV仍开。

## 辅助检查与阶段收尾

最终唯一一次pnpm check实际wait0，795.314秒，双流EOF、无溢出；Node24 spec为46组、1544 tests＝1476 pass＋68 skip，0 fail/cancel/todo，其他smoke单列。前后HEAD/branch/status/index、899源、93封存材料和14工具一致，Root独立复核原raw/统计/全部当前源。parity生成/检查、格式、Python内存编译及diff检查通过。四个本次Swift/fixture目录已按准确清单核对无占用后删除并复核。该检查为辅助证据，AX失败和物理/真实DEV待验不变。
