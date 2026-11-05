# 阶段 107：主线续接后的打包夹具恢复

2026-11-05。Android 主线续接点 `61a135e4bf49f4867b29476e6eeb8aa3d50824c0` 已推 Gitea Android 专用分支，主线仍为 `eae10a60`。主线同步前的 F59 SDK 与 API33 十五项 Native 继续绑定原源码 `5203513`。

完整 `pnpm check` 在该干净续接点实际执行：内层原进程退出码 1，双流 EOF 及关闭已核对，源码前后相同。失败组为 78 项中 77 通过、1 失败；`package-host-connect.test.mjs` 的隔离仓库缺少主线 PR141 新增的 `smol-toml`，在原本应验证的 orphan 拒绝前报 `MODULE_NOT_FOUND`。后续检查项未到达，旧失败保留。独立 iOS 27 模拟器已正常关停并确认从官方清单移除；短 TMP 已在原进程退出后回收。原结果 SHA-256：`ae9afb5a9cd4a739379e2cc6c108442a4c55575a563fcea0006bb70169d4e669`。

本阶段只修测试夹具：隔离 adapter 声明同一 catalog 依赖，并提供隐藏 manifest 子路径的合成模块。真实打包函数仍走现有 exports 回退；测试从 zip 核对实际打入的 manifest 与入口字节，再核对原 orphan 拒绝。生产打包器、锁文件及产品代码未改。

锁定 Node 24 对 `scripts/package-host-connect.test.mjs` 执行 13/13 通过，0 跳过；含原 orphan 拒绝和真实 ditto 路径。原 wait、双流 EOF、关闭和源文件指纹的证据索引 SHA-256：`b2526d1bd4c16f266e3a5b4dcd58c163e77f406c805eb7b0bfb5dec08cabe228`。这是辅助检查；当前完整回归、iOS 构建及 Android 新候选 Native 仍待核对。没有手机、原桌面或模型写入，也没有重放原操作。
