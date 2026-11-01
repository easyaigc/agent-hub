# Source79：共用电脑行按钮角色与原保存连接回归

2026-11-01，阶段54，关联 F06/F09／UI03。共用发现电脑行增加显式Role.Button，iOS EasyWork配对与原Codex电脑行均使用Button；保留现有布局、文案、尺寸与原callback。原online保存连接方法只增加角色断言，原4个Native方法、离线和迟到隔离业务断言均保留。本轮SDK、安装与3个原Native方法／4夹具／397份完整捕获经Root独立核验限定通过；第4方法仅有Native终态0，原采集失败保留，完整数据读回待验。

## 实现、双端影响与证据分层

`NativeSheets.kt`的共用电脑行显式声明Button语义，EasyWork和Codex使用处均受影响；不是只改EasyWork专用页面。iOS参照为EasyWorkPairing的两处Button及Sheets的两处Button。iOS本轮未改代码，也无新增iOS验收。

此前只由源码确认缺少角色声明，未复现实际TalkBack／AX系统类失败。新增Compose角色断言可以证明当前节点声明，不能替代系统读屏、系统字号、截图原点、完整视觉或真实服务端联调。Callback、Relay-only候选、密文及迟到隔离原断言保持，不能因新增role而放松。

## 本次候选与安装

- NativeSheets源SHA256：`dfc1fe6c90911e8486de1da597dea569bbd4fed9e2f1c68f1f8bb72b71f389f3`。
- NativeEasyWorkSavedSelectionTest源SHA256：`61d63d0a5ff963c15671a7a37e8579c760206ab504f4bae873c103aa0e32182e`。
- canonical：`7ab0feffadf1ca2eacd546c40c982f5c7d4e6dfc7793c5fa293871782aafa927`（857输入）；expanded：`da2309b215a7f4202ff6aeb18b02e4db42ea9459aa7cc53158741d52b3d90064`（870输入）。
- App：`91aa986821b390771265ca07c2754fea0edcf9b026726875a2752058052eef34`；Test：`b853aca253eac5b6eae0a167537b412922d115e1137090ccca66b1fb11b67880`。
- SDK Root核对：`bf4d337f62c73239b2019cc7af288f57a095ded6156b1244abe667046e58f496`；实际build-slot／wrapper／pnpmEntry均退出0，857／870实际与冻结输入、三包完整ZIP／CRC／v2原签名、原始工具流及39旧APK／13候选11279冻结文件保持已核验。
- 安装Root核对：`805101d6bf08e61d47d99e40badc585a55df252e1eaf8d938e6f467c1e22ee11`，100份原始捕获；不卸载／清数据，files525／no_backup100、delta0，45旧根及41份old＋new APK保持。

## 本轮逐项结果

| 原Native方法／范围                                         | 当前状态                                                          | 不替代的门槛                             |
| ---------------------------------------------------------- | ----------------------------------------------------------------- | ---------------------------------------- |
| online Relay-only保存行实际touch／原callback／新Button角色 | 完整限定通过，99份原捕获；files534／no_backup100、delta10         | 系统TalkBack、真实服务、Codex实际桌面DEV |
| offline准确Relay候选与偏好、提交前读取                     | 完整限定通过，99份原捕获；files543／no_backup100、delta10         | 真实双通道／系统信任                     |
| 原排队读取在后台往返／新草稿后不能选择                     | 完整限定通过，两夹具／99份原捕获；files561／no_backup100、delta19 | 完整生命周期／原生界面矩阵               |
| 原排队读取在目标切换／保存连接替换后不能选择               | Native终态0；原collector退出1，完整数据读回待验                   | 完整路由与Provider回归                   |
| 采集器纯检查29项                                           | 实际child退出0、Root逐项读回，辅助通过                            | 不计作Native方法／完整P8通过             |

完整通过计数为安装100＋上述三个方法各99，共397份捕获与4个新夹具；最后完整基线为files561／no_backup100，不把第4方法的未完成496份总量或六夹具预测算作通过。既有45根、41份old＋new APK、两FIFO元数据及Source78原候选均按已完成阶段逐项保持；Source78两个旧collector失败仍为失败，封存未知原操作不查询或重放；其余未知结果按原回执核对，操作不重放。

## 第4方法的部分采集与只读恢复

目标切换／保存连接替换方法已取得原Native终态0；随后after-files-tar作为第72次设备调用返回exit255与`error: closed`，stdout为0，原collector worker退出1。实际只有76份收据（72设备＋4本机守卫）／152条流，Root逐份完整读回这些已有资料，不能因此补成99份完整证据或关闭数据／UI验收。原失败和原执行身份保持，不重跑Native、不派生新候选。

原tar命令body为66060 UTF-8字节，前一成功为64250字节；Android官方ADB协议使用四位十六进制长度，最大65535，exec-out还会拼接并转义命令服务。此限制对失败形成有一手依据的强推断，尚未证明是唯一原因，也未做设备边界复验；不能写成已证实根因。[官方协议](https://android.googlesource.com/platform/packages/modules/adb/+/refs/heads/main/docs/dev/protocol.md)与[原生长度编码实现](https://android.googlesource.com/platform/packages/modules/adb/+/972ee9e3d7c0ba4f90a3cff349a5b46b76fbaf3d/adb_io.cpp#41)为依据。原共享ADB与QEMU监听身份保持，未以重启共享服务修复。

下一项先准备限定只读、分片regular-only tar的恢复采集器，核对原执行后的同一资料；原Native、候选和失败回执不重放或改写。只读恢复本身须重新验证完整目录、字节与九stat后才能转为通过。

- online Root核对：`5f1d828bd36d215894340e4eca95d62e6f8ca6c287662be36bd235b7ce26add1`。
- offline Root核对：`4348a72a6199d184d12b0a29c6473673ec3e6aff3708ec5de84792f6403d705a`。
- background／draft Root核对：`662ea614f278e21467ae4d136a45a87cb3ff89d05d6cd16b2cba24b1c0a279c2`。
- target／replacement部分资料Root核对：`11e2349a9f1b547c8fa02d102d91ed2a6a310e8196717263ed93a7dc69c60d9e`；此证明只覆盖既有部分收据／流，不能替代缺失的完整数据采集。

## 后续与资源

完整P8七组、截图原点／API26、系统TalkBack／OS字体、真实issuer／桌面、LAN／Relay系统信任、完整默认清单／物理手机DEV与Codex回归仍待验。共用行角色声明不会关闭这些门槛。

本阶段先提交合格改动，随后完成上述只读取证恢复，再推进Source80 mixed方案及Source81 P9消费进程冷读方案。Source80静态97／184诊断预算已审但未采用或运行，后续也需使用有界分片tar；两项方案均无SDK／Native通过，不计产品验收。业务／模型输入保持221，P4为205及1个封存未知原操作；任务／项目管理、F75原生项目UI和iOS A6仍暂停。当前准确候选、冻结源和独立模拟器因下一轮回归保留，阶段结束仅按准确依赖清理自产可重建产物。

## 阶段55接续：只读恢复与剩余状态

Source79原target／replacement Native及collector不重跑、不改写：原方法终态0、tar255与collector1、76份原收据／152条流仍按原范围保存。新独立只读恢复取得104个已知退出0的读取子操作（96设备读＋8本机守卫）及208完整流，Root已核对字节／摘要／EOF／子操作退出。

恢复采集监督／worker实际退出记录缺失，不能从stdout或known-release推断退出0；当前进程已不存在，fence已由Root独立取得后释放，不宣称仍活跃、自然退出或采集器成功。Root已独立读取原76收据／152流和恢复104收据／208流，完整tar／九stat／proof数据级核对通过：files579／no_backup100、两次9成员夹具与parent共19项差异，51根／41APK／两FIFO元数据保持；Root证明`de8434fe7c33a833026fdf7105806ea761d03f3d6eb78ebad3effe84ee5503db`。accepted P8组仍为3，完整执行／方法验收不升级；不能把397补成496或宣称四方法完整通过。无install、Native、Host／模型调用或原操作重放。

上述“下一项”随后已推进：Source80源码采用及SDK通过、安装／Native待验；Source81方案已审但未采用／构建。结果和下一步统一见[阶段55记录](source80-p7-mixed-provisional.md)，本记录保留阶段54原失败与当时边界。
