# 待验与暂停

更新：2026-11-09。以下项独立登记，只阻塞依赖它的验收。优先级见[计划](plan.md)，产品状态见[进度](progress.md)。旧[待验档案](../android-alignment-20261022/pending-acceptance.md)及[阶段记录](../android-alignment-20261022/records/README.md)保留每次失败、原意图和 UNKNOWN，不因新清单改写。

| ID     | 待验范围                                           | 关闭条件 / 继续方式                                                                                                    |
| ------ | -------------------------------------------------- | ---------------------------------------------------------------------------------------------------------------------- |
| ENV-01 | API37 横屏、长字段/短视口及IME/读屏组合            | 当前匹配包逐页面实际操作与原图/AX，旧API33/手机结果不能替代；其他环境继续                                              |
| ENV-02 | 物理Android手机蜂窝/VPN及路线切换                  | 重新检查当前手机SIM、Tailscale/路由和身份，真实目标完成端到端核验；最新档案已有SIM及Tailscale，不沿用最早“均无”的结论  |
| ENV-03 | 归档/未加载原owner发送前打开                       | 用户已要求单列；在实际未加载目标核对原owner及真实发送，保持历史专用聊天和原操作，不为取证反复归档或重放                |
| ENV-04 | 实际系统选择/权限/复制/分享/通知UI                 | 系统相册/文件/相机/外部打开、选区剪贴板、系统返回和权限撤销；原生/系统组件证据与普通Controller分开                     |
| ENV-05 | 真录音/本地Speech能力、电脑转写与中断              | 实际麦克风/本地语种服务、Host原录音与回执、蓝牙/离线/授权/恢复；deterministic PCM producer不能替代                     |
| ENV-06 | EasyWork短视口/长字段/准确AX和真实网络             | 按当前scope复核旧锚点/原窗口失败，中文/英文、大字号、菜单/IME/Back及真实issuer/LAN/Relay；不拿新scope改写旧失败        |
| ENV-07 | 普通推送供应商/OEM/手机实际到达和冷点击            | Host事件→供应商接受→设备到达→SystemUI点击分别取证；无GMS路径按实际Provider核验，接受不等于到达                         |
| ENV-08 | 自然I/O失败、低磁盘/内存、真正进程死亡和Host冷重启 | 在安全独立环境按准确源/包/owner执行；受控异常注入通过保留原范围，特别锁/reader/sink关闭与发布后补偿                    |
| ENV-09 | 双端迁移资料域/格式/容量互通                       | 明确平台格式和来源/安装授权规则，对共同已支持格式真实交叉导入；不支持须准确拒绝、不丢原件、不自动授权                  |
| OP-01  | 历史写入后结果未知/外层执行UNKNOWN                 | 保留原operation ID、不可变意图、原件及回执，仅只读核对权威状态；明确未执行之前不得重放。全部实例仍由原阶段档案逐项管理 |

## 当前新增待验

ENV-10：原身份DEV已安全恢复；物理iPhone经原保存路径的原回执只读核对1项通过，缺失ID被拒，未重放。该限定阻塞已解除；hostname DNS及原owner控制不由readiness证明，仍须各自验收。完整C1和四格保持开放；前轮连接失败及辅助检查保留于[阶段118](../android-alignment-20261022/records/stage118-three-way-native.md)，恢复结果见[阶段119](../android-alignment-20261022/records/stage119-c1-original-receipt-restored.md)。

**ENV-11 EasyWork 新域 DEV 绑定**。用户确认全面切换到 `51easyai.com`，旧网络管理员登录已撤下。双端地址校验已接纳专用 mesh 电脑名。iOS 27 与 Android API 33 模拟器经新域两次读取同一真实 DEV，原保存的完整目标、桌面公钥和配对证书指纹匹配。Android 真机新域候选包仍被系统拒绝安装，手机实际路由、原 grant 刷新及原内容读取继续待验。阶段122原授权与原历史结果、旧 issuer 与未知操作均按原记录保留。详见[阶段126](../android-alignment-20261022/records/stage126-c2-new-domain-and-android-permissions.md)。

**C2-UI-01 系统选择与界面组合**。双端模拟器系统选词复制保留通过。Android 安装恢复后，用户开放安全调试权限，普通触摸与独立新用例 1/1 通过长按选词、系统 Copy、本次剪贴板基线恢复和读回核对。Android API33 与 iOS27 模拟器的跨行精确子串复制及本次基线恢复已通过。Android 受限短横屏/大字的长标题与展开控件可达通过，iOS 展开与菜单 AX 存在通过。实体跨行、iOS 长标题的导航覆盖与滚动关系、完整视口组合及 API37 继续待验。阶段125复制与恢复结果保持未知。详见[阶段126](../android-alignment-20261022/records/stage126-c2-new-domain-and-android-permissions.md)。

**C2-CODEX-01 原桌面长详情**。真实 Host 父卡片与刷新、iOS 模拟器生产卡片交互、Android 真机的稳定读取与实际显示通过。Android 实体安全调试权限已开放，待实体展开与界面刷新。iPhone 真机及有实时 owner 的子输出后续独立验。详见[阶段125](../android-alignment-20261022/records/stage125-c2-ios-simulator-and-dns-authority.md)。

**C2-SPEED-01 EasyWork真实速度闭环**。本轮Android目录/原轮次/显式设置和双端fast-only规则按[阶段128](../android-alignment-20261022/records/stage128-easywork-generation-speed.md)实现与分层验收。真实DEV原授权scope、当前模型目录/能力、唯一输入/回执与原模型生效仍待核对；物理Android固定包安装受限。当前中文浅色竖屏fast-only/多档菜单及完整速度卡片原图已补验，详见[阶段130](../android-alignment-20261022/records/stage130-c2-speed-menu-visual.md)。Android菜单锚点待对齐，材质/滑块、API37、其余语言/主题/字体/横屏与完整AX组合继续待验，不把组件通过扩大为整页一致。不得导出旧包Keystore凭据、自动新配对/刷新，或重放既有未知操作。其余本机验收继续。

**C2-CONTROL-01 原队列与控制真实闭环**。双端加密合成对端已取得[阶段129](../android-alignment-20261022/records/stage129-c2-native-queue-receipt.md)的原生辅助结果；真实DEV的队列执行/取消、追加/停止、原Electron/模型和原回执、当前UI/刷新/短视口及iOS错误线程查询时机仍需逐项验证。结果未知只查原ID，不能把辅助对端或预置回执当真实入账。

## 暂停项

- F65–F67：Task Hub 的业务项目、任务管理与业务通知；UI14–UI19。已有普通聊天项目选择/最近项目/分支继续开发。
- F75：原生项目Git管理与详情；保留当前iOS需求和提交来源，不在本轮实现。
- iOS A6：保持人类暂停，不以Android计划恢复该真机任务。

仅新增实际缺失的权限、人工动作或外部条件需要请求用户介入；已有“待验单列，继续”授权持续有效。待验关闭必须回填准确子场景和证据，不能整段删除历史失败。
