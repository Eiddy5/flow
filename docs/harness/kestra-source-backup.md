# 源码替换前的完整备份与恢复

对应 [ADR 0116](../decisions/0116-replace-flow-root-with-kestra-sources.md)。

## 本地备份凭证

- 原目录：`/System/Volumes/Data/workspace/java/flow`
- 完整副本：`/System/Volumes/Data/workspace/java/flow-backups/before-kestra-root-20261013-094341/flow`
- 创建时间：2026-10-13 09:43:41 +08:00
- 校验完成：2026-10-13 09:46:41 +08:00
- 分支：`codex/kestra-core-migration`
- 原 HEAD：`0d78cc23998d7b5a6327c62d1e1c580a25741ec0`
- 范围：全部工作区，含 `.git`、未提交文件、忽略文件、本地配置、旧构建产物与原子工程，无排除项。
- 校验：38,426 个文件/链接、24,139,205,170 字节；普通文件逐一比对 SHA-256，链接比对目标，目录验证存在。
- 备份根目录权限：仅当前用户可访问；内含本地凭据，保持私有，不提交或上传。
- 凭证文件：备份父目录的 `backup.json` 与 `sha256-manifest.json`。
- 清单 SHA-256：`08145b7e174f32a3e56c05c0169bfb06fddf9702fd6b8c1c7df33b081210bed8`。

采用 APFS copy-on-write 克隆保留文件内容与权限；后续修改当前工程不会改变副本。
副本中的 `.git` 保留此前提交历史，未提交内容直接保存在文件树中；本次没有为备份
创建新的 Git 提交，也没有推送。

## 恢复检查

先从私有副本复制到一个**尚不存在**的新目录，核对内容后再决定是否切回；不覆盖
当前源码二开工作。示例目标使用 `flow-restored-before-kestra`，若已存在应另选名字。

```bash
test ! -e /System/Volumes/Data/workspace/java/flow-restored-before-kestra && \
  cp -cRp /System/Volumes/Data/workspace/java/flow-backups/before-kestra-root-20261013-094341/flow \
    /System/Volumes/Data/workspace/java/flow-restored-before-kestra && \
  git -C /System/Volumes/Data/workspace/java/flow-restored-before-kestra status --short --branch
```

清单可用于恢复后逐文件重新计算 SHA-256。切换原工作目录、IDE 和任务绑定前，应先
保留当时新根工程的未提交工作；本次只完成备份和新根替换，没有执行恢复。
