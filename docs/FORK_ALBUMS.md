# 阅读 NG 图集分支

入口：设置 → 封面设置 → 管理图集。支持新建、重命名、删除、本地多选图片、日夜图片同步、预览，以及独立图集 ZIP 导入导出。选中图集后，沿用 NG 原有的封面分配规则。

添加图片时默认优化到长边不超过 1600 像素；可关闭优化保留原图。独立图集 ZIP 的压缩大小和解压后总大小均不超过 512 MiB，单张图片不超过 32 MiB，最多 4096 个 ZIP 条目。主题包继续使用上游限制；大图集请单独分享，不必重新打包主题。此 ZIP 格式不保证兼容其他阅读分支。

应用名为“阅读 NG 图集”，包名独立，可与官方版并存。首次迁移使用阅读的备份与恢复。应用内更新指向本 fork 的 Release。

## GitHub 自动构建

- `Fork - Album checks` 在 PR 中运行设计规范检查和相关单元测试。由于当前上游其他单元测试存在编译错误，使用 `-PFORK_FOCUSED_TESTS` 仅编译图集、主题包、更新校验四个测试类及其 fixture；这不是全仓测试通过。普通开发构建不受该选项影响。
- `Fork - Follow upstream release` 每小时第 17 分钟检查上游最新稳定 Release。GitHub 调度可能延迟，fork 首次使用需在 Actions 页面启用工作流。
- 只合并上游已发布的 tag，不跟随上游 main 的未发布提交。保留本 fork 的 `.github/workflows`，上游工作流变更须人工评估同步（默认 GitHub 令牌不能写入工作流文件）。先在临时候选提交上测试和构建，成功后才更新 main 并发布签名 APK。
- 合并冲突、测试失败、签名缺失或构建失败均停止发布。构建期间 main 有新提交也会停止，避免覆盖修改。
- Actions 支持手动运行，勾选 force 可在上游版本未变化时发布本分支修改。同一小时不覆盖已有 Release；失败后可以下一小时重试。发布服务故障可能留下草稿，已发布版本不受影响。
- GitHub 可能暂停长期无活动仓库的定时工作流，需要在 Actions 中重新启用。此工作流不保证每个上游中间版本都构建；每次取最新稳定版。

## 首次签名设置

在 Settings → Secrets and variables → Actions 添加以下 Repository secrets：

| 名称 | 内容 |
| --- | --- |
| RELEASE_KEY_STORE | 固定 Android 签名 keystore 文件的 Base64 文本 |
| RELEASE_KEY_ALIAS | keystore 内的密钥别名 |
| RELEASE_KEY_PASSWORD | 密钥密码 |
| RELEASE_STORE_PASSWORD | keystore 密码 |

请离线备份该密钥和密码。后续版本必须使用同一密钥才能覆盖安装；不要把密钥或密码提交到仓库，也不要每次构建重新生成。首次配置后在 Actions 手动运行 `Fork - Follow upstream release`。

APK 和测试报告由 GitHub 构建提供；自动检查不能代替安卓真机验收。建议首次安装后检查多选添加、日夜切换、ZIP 往返、备份恢复及覆盖更新。
