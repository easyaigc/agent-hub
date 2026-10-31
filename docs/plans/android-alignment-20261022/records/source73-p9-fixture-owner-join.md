# Source73：通知回调夹具的 owned 工作收尾

2026-11-01，阶段47。仅修Android Native测试夹具，不改产品、协议、UI或iOS。35个方法及全部业务断言保留，Source73 SDK通过；新runner安装、十项回调Native和完整P9仍待验。

## 问题与边界

旧@After仅dispose/cancel和离开前台，未join精确coordinator、parent scope及Session，也未排空持久化；某步骤抛错会跳过后续核对。新内层TestRule保留原测试异常与suppressed，记录所有当前／旧绑定owner、释放本夹具gate、逐项cancel/join/IO barrier；已确认owner结束才请求writer shutdown，未确认时留原件并失败，不把超时当成功。

使用既有窄终态API，不开始迁移、恢复、配对或重放业务。保留真实Main入口，不用ComponentActivity替代P9；默认启动runtime、私有默认只读handle、全部物理executor和外层JUnit超时退出仍未证实，证据明确false。没有新增生产API或默认目录删除。普通Native绿测仍不能替代这些开放门槛。

## SDK本次结果

单次构建内层退出0／7.802秒、外层0／7.94秒；3次freeze及9次SDK工具输出完整，由主统筹读回。全部854／867实际与冻结源、三APK ZIP/CRC、单v2同证书及manifest核对通过，Source69–72共3466份旧冻结源码和12APK保持。

- canonical：`5c65ed035c75e5e41a0cbcc618ea7603f5a315b51f57c7639014c1b45f63aeda`
- expanded：`62c52653ce46dfff4070d1f4a571d9f5a79c839617c5aa98797bc3e0a4a08746`
- App：`532ab10e9efc0c18f5577938434c01286476c63b31935d2c358d61d4975beb69`（与Source72相同）
- Test：`027d2ce50d110881e3f4ff83242b94cdfaa1fd026eb55ec40aab8b71926b3d39`
- 主统筹SDK核对：`4a0ec0efdc45d990801b8063cf4d04c50591265fce1a7495c64c858aab372a3f`

构建前一次守卫把交付unified patch哈希与git diff --binary文本哈希误作同类，未执行任何构建／SDK；核对原文件base/new和交付patch字节一致后，仅修辅助核对方式再进入唯一构建。旧中止记录保留，不算编译失败或重跑构建。

## 后续验收与资源

先完成本阶段安卓分支提交，再只更新Test runner（App字节不变），独立核对UID／boot／权限／完整原件。后续逐个准入十项原导航claim／v1兼容／过期／凭据和偏好轮换／旧ACK／失效回调；首次Main可初始化本任务空环境的默认族，须逐项声明新增生产路径，保护既有Cold62、reading13及P8六原件，不覆盖未知资料。

本轮P8使用Source72已安装runner，Source73新Test从未运行，不将两候选指纹混为一次通过。真实OS通知点击／冷PID／供应商／系统权限及完整界面继续待验。封存未知操作不查、不重放；旧已发布失败代次只留只读核对待验，任务／项目与A6暂停。当前APK／源码和必要证据留作接下来的runner更新与P9验收，已替代中间物按准确归属即时回收。

阶段48续验与当前结果见[原导航 Native 记录](source73-p9-native-navigation.md)；本档案保留阶段47 SDK时点的原边界。
