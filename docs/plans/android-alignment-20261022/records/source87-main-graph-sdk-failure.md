# Source87：Main 冷入口首次编译失败

状态：已知失败，保留原始证据；不是 Native 或产品验收通过。

- 四个 P7/P9 共用 Main 入口验收源文件首次进入真实 SDK 构建。Root 另外收紧了归档前 `completed.afterMembers` 与原始目录成员的核对。
- 原操作 `d2bb6003-0832-4d36-9eb6-7c423b339c00` 仅执行一次；worker、构建槽和 pnpm 实际退出均为 1。
- Debug helper 使用了公开 Android SDK 未暴露的 `O_DIRECTORY`。失败发生在编译阶段，没有安装或运行 Native。
- 原 stdout 19,836 字节，SHA-256 `e563a5d445fdba304e196f1604f56e2907138715d5117bca5247479ae176aa54`；stderr 1,081 字节，SHA-256 `678f46d65c75e7853a6d24de63268d09f0454ee9bd9628e087ffeef6db64e6e5`。

后续 Source88 采用已有存储实现使用的公开标志组合，保持 `NOFOLLOW`，在 fsync 前后核对目录类型、UID、权限和相同 inode。原失败不改写、不迁为通过；后续结果另见 [Source88](source88-main-graph-sdk-and-preflight.md)。
