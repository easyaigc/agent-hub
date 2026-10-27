# GitHub 主线自动同步

源仓库是 Gitea 的 `BCAI/agent-hub-app`，公开仓库是 GitHub 的 `easyaigc/agent-hub`，两端主分支均为 `main`。

`.github/workflows/sync-github.yml` 仅在 Gitea 执行：主线推送后同步，每小时第 17 分钟补查，也支持手动运行。任务使用仓库级 `agent-hub-linux` 运行器。GitHub 上同名任务自动跳过，原 CI 继续运行。

同步读取 Gitea 已提交的最新 `main`，生成文件树完全相同的公开提交；新提交只以 GitHub 现有主线为父提交。私有开发提交历史、未提交改动和忽略文件不进入公开历史。提交消息中的 `Gitea-Source-Commit` 记录来源指纹，两端提交 SHA 因历史独立而不同。发布范围只有 `main`，不推送其他分支、标签或 Release。

同步使用独立 SSH 部署密钥：公钥只在 GitHub 的 `easyaigc/agent-hub` 授予仓库写入权限，私钥只保存为 Gitea 本仓库的 Actions Secret `GH_PUBLIC_SYNC_SSH_KEY`。不得保存个人 GitHub 令牌或其他电脑的身份；不得提交私钥。SSH 使用 443 端口，并通过 GitHub 的 HTTPS 元数据接口取得主机公钥、强制校验。

`scripts/ci/sync-github.mjs` 检查上次来源提交的祖先关系，拒绝旧版本或无关来源覆盖；目标使用普通快进推送，遇到并发提交失败而不强推。文件树相同则跳过，不创建重复提交。公开仓库的源码修改应先回到 Gitea 主线，避免被后续源码快照替换。

辅助检查：`node --test scripts/ci/sync-github.test.mjs`。本地只读预览：

```bash
git fetch origin main
node scripts/ci/sync-github.mjs --dry-run
```

配置完成后必须手动运行一次工作流，并核对 Gitea Job 成功、公开提交的来源指纹及两端 `main` 的 Git tree SHA 相同。工作流文件存在或 Secret 已创建不代表自动同步已通过；失败记录与尚未完成的配置应单独报告。
