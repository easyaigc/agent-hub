# 原授权 reader 的纯校验上下文修复

父线准确组合的原生结果为普通 19 项中 11 通过、8 失败，cold writer 失败、reader 未启动。共同堆栈落在授权 wrapper 组装的 `metadata.selected`：此前填了空字符串，而共享 Metadata Schema 正确要求非空。此处是迁移 wrapper 的错误，不是原件损坏。原失败集不改写，未在本线重放原 writer。

修复只将这份临时、不可执行的纯校验上下文设为原 `client.id`。共享 Schema 保持严格；原 journal、token、receipt、target、epoch、authorization 和原始 JSON 字节均不改写，也不产生 active authority、identity、operation 或注册。

新增 `NativeMigrationAuthorityReaderTest` 的 2 方法独立调用真实 P9 `SavedConnection.capture`、`Authorizations.retain/validateEncoded` 生成与校验输入，再调用迁移 reader。正例核对实际加密记录／密文不变、目标／指纹一致、无身份或 journal 初始化。反例覆盖未来版本／未知字段／凭据、epoch、指纹、空 client ID 和目标改变，双方均拒绝；展示名称改变继续接受，空 selected 的共享 Metadata 反例仍拒绝。没有复制 wrapper 的指纹公式来制造自证正例。

组合从父线当前 native 源码快照生成，只覆盖修复 reader 和新测试，未混入本线正在准备的 UI／生命周期草稿。组合 App／runner 编译通过，输入前后摘要一致，具体指纹见 `authority-wrapper-fix-source-audit.json`。两方法尚未在设备执行；父线需冻结新组合，复验完整 19 项、2 个 producer 边界及独立授权的原件 cold 流程，原失败集继续独立保存。没有设备、Host 或真实模型操作，完整 P7、实际系统入口、8MiB 大原件、EasyWork authority 和 A6 状态未因此关闭。
