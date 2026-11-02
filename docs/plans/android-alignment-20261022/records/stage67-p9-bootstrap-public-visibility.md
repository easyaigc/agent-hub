# 阶段67：P9 scoped bootstrap与public进程可见性

日期：2026-11-02。P9独立组10份源码WIP尚未提交或合入main；集成阶段66的a87基线。实现、SDK、scoped bootstrap和public observer失败分别记载，不以CLI0、安装或编译替代SystemUI/真实Host验收。

## SDK与scoped bootstrap限定结果

集成SDK原wait exit0、542.840383秒，源码前后一致；Root实读57份index摘要。私有file-index.json 8134B / `34e996d2bdc978d64fde16d1ce58be6a1782383684cde35e3ce70081ec7aa09a`。App 66808183B / `b2075ca8ce54967da1a2438722919db6884b763dde8df12d6d53873efc2ea989`；Test 7471292B / `2c99519fbedf7cafbcf88c9e3246de5b888f45a9287a0a83b2da18c6c18b6952`；examiner 1047874B / `7562d992ff49c1cc391b098a91dba06cd70a4d6a3fbd2fe8cdb51ff5caa466b5`。这组App与阶段68即使字节数相同也有不同SHA，不互相替代。

fresh API33的NativeP9SystemUiScopedBootstrapTest.initializeScopedOwnedInstallOnce原JUnit OK (1 test)、STATUS_CODE0、结束码-1。lease `3e95057a-c992-4b94-8802-24c18120d7c8`，PID5852/UID10174；正常产品Storage只做一次独占scope加密写入，ciphertext155B，key由正常Storage创建；defaultFamiliesAbsent前后均true，keyAliasEnumerated false。此为scoped key/write准入，不是OS零进程、SystemUI通知点击或完整默认family验收。

私有 `stage67-fresh-bootstrap-execution/bootstrap.stdout` 798B / `84cde773703889ecd9583a612147e5241893fd19ed07770e7eeb4e6db60e2807`；原intent/complete与安装/进程核对分别保留。所有真实Host/供应商/OEM、完整UI、低API/AX及物理DEV仍待验。

## 校正public observer的真实阻塞

真正public producer为NativePublicProcessNaturalOsProducerTest；旧OS-coldwriter调用与当前契约不兼容，由只读检查发现，没有执行旧命令或重放原操作。

校正后新lease `3fdc2fe1-f5cb-471f-905e-b6457f9cdcf9`、attempt `dbb162dd-2467-43b6-adc8-0663f95b5c4c`，observer PID6621/UID10176在ready前失败：NaturalProcessVisibility$Refused / cross-UID system_server missing。原PS完整122B只列observer与同UID ps，没有system_server或其他系统进程。public capture的子进程wait exit0、stdout/stderr完整/排空分别记录；这些不等于具备跨UID可见性，child UID亦没有独立确认，不能声称shell身份。

原typed结果status failed、preClickSystemUi not-started/clickBlocked true、systemUiClickAccepted false。producer未启动，没有ready、通知post、SystemUI点击或草稿编辑；外层RuntimeError原文为observer not ready: producer never started。observer命令CLI0不计Native/SystemUI通过，不能用scoped bootstrap结果升级本次失败。

临时POST_NOTIFICATIONS原先denied，试验结束已恢复并读回原权限状态，不为继续测试静默改权限或放宽guard。API31–33 public路径所需跨UID可见性单列待验；本次API33失败不冒充所有设备/版本的结论。RWE34+与API36环境另待具名准入，不自动转成生产可用或SystemUI通过。

私有 `stage67-public-natural-corrected-execution/`：observer-result.stdout 1531B / `e6cd2408364476595d031d0f050fce262a668c5dc9bcef0a6d41844c00c3e237`；observer-ps-raw.stdout 122B / `1cf8fdb8bfe7420f15ed0c7cb02465759eeb55ddb96286e1269d2b1462622e76`；failure.json 79B / `3c2db99e5ef9caf7a9237b97eead23503cb71dcf029ed03e412f21cefedc41e8`。Root已原始typed/raw核对，原始资料与机器路径不入Git。

## 资源与后续

P9私有Gradle cache 1176621056B已按正常停止/无占用核对退役，wrapper/init保留。fresh P9 AVD已由官方avdmanager删除，回收1063739392B；原QEMU7841正常wait exit0、Root原工具session已closed，最小raw保存在树外。此前三次失败的API36 AVD亦官方删除，回收33083392B，三份原wait/log保留；镜像28文件CRC匹配，缺userdata/devices属正常，不归因为镜像损坏。

阶段68重启的原Native66独占API33已正常console停止、原工具wait exit0，本任务无运行VM；磁盘仅为源码提交/证据收尾及下一具名P7非空域复用暂留，未删除。公共可见性阻塞与P9完整SystemUI待验不阻塞P7非空资料完整迁移。P8既有缺口、API37横屏、蜂窝/VPN、原owner和其他pending全保留；任务/项目CRUD、F75、iOS A6暂停。Host/model本轮+0、累计221，P4 205另1未知原操作封存不查询或重放。
