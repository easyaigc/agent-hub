# P9：原回执跨进程只读消费

2026-11-01，Source81／阶段56，F51/F53/F54/F55/F56／UI11。当前**不同PID只读消费限定通过**；方法、原指纹、逐阶段实测及Root证明见[阶段档案](../../records/source81-p9-existing-receipt-read.md)。

- 功能：读取原pending nonce、terminal ACK、未知v2原intent、夹具metadata草稿字段、隐私和完整合成声音；原namespace不写入，只增加consumer proof。分别准入、同run不同操作，不重放writer。
- 交互：不重新claim／ACK、查询远端或导航，不新增发送／重试权；不同PID不是死亡证明，显式now不是实际TTL。
- 界面：无Main／设置页／通知中心操作或实际播放，无截图／AX；Source77组件原结果保持，完整UI／OS字体／TalkBack和物理DEV未关闭。
- iOS：准确target、隐私和声音为共同断言；普通通知pendingTap路由与Android持久nonce／recovery journal不同构，无新增iOS验收。

后续真实Main消费入口／OS点击与完整通知生命周期、低API／供应商／OEM单列；本用例不关闭P7完整迁移／48MiB。任务／项目业务、F75及iOS A6暂缓；未知不重放，旧失败及退出未知保持。
