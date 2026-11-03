# 发布验证 / Release validation

## 本地检查 / Local checks

```bash
mvn clean verify
bash scripts/smoke-test.sh --skip-build
bash scripts/package-release.sh --skip-build
```

依赖已缓存时 Maven 可添加 `-o`。回归测试使用临时数据库，不访问真实 `~/.rex/rex.db`，也不调用模型：

- 数字会话名与旧哈希的歧义、哈希碰撞、目标主键冲突。
- 迁移只执行一次、失败回滚及重试、其他 app/user 的记录隔离。
- 会话消息重启后读取与隔离、任务步骤真实消息类型和摘要的恢复。
- 可恢复任务状态集合及会话隔离。

Use `-o` when Maven dependencies are cached. Tests use temporary databases without real model calls or access to your own rex database. They cover migration ambiguity, collisions, rollback, restart behavior, message restoration, and resumable task isolation.

冒烟脚本默认先执行离线 Maven verify；`--skip-build` 复用刚验证的产物。它从其他目录调用安装脚本，在临时目录安装，检查 `--version`、`--help`、JAVA_OPTS 转发，结束后清理。

The smoke script runs offline Maven verification unless `--skip-build` is specified. It installs from a different working directory into a temporary location, checks version/help/JAVA_OPTS forwarding, and cleans up.

发布脚本默认先执行 `mvn clean verify`，`--skip-build` 复用当前构建。生成 `target/release/regnexe-cli-0.1.0.tar.gz` 及 `.sha256`；包内包含可执行 JAR、安装脚本、中英文 README、协议、文档、开发脚本和 SHA256SUMS。脚本仅生成本地产物，不上传、不创建标签。

The release script builds first unless `--skip-build` is specified, then creates a local archive and SHA-256 files. It neither uploads nor tags a release.

## 人工验收 / Manual acceptance

用本机终端和一个临时工作项目，配置可用模型后检查：

1. 启动 `rex --session release-check`，确认终端输入、输出、颜色及退出正常。
2. 发起只读问题，验证规划、工具调用和最终输出。
3. 请求一次文件编辑，检查 diff 与接受/拒绝行为；确认 Shell 工具的确认流程。
4. 重启后 `/history release-check`，确认消息保留。
5. 对运行中的任务发送 Ctrl+C，确认实际任务状态，再 `/resume release-check` 并补充指令；失败任务也需验收恢复，不能只检查数据库反序列化。
6. 按需验收 Skill、MCP、Opik 的真实服务连接。Opik 默认关闭。

Use a local terminal and temporary workspace to validate interactive rendering, real model calls, edit and shell approvals, history across restarts, interrupted/failed task recovery, and configured Skill/MCP/Opik connections. Database tests are not end-to-end recovery acceptance.

旧数据升级前退出全部 rex 进程，备份整个 `~/.rex`（包括可能存在的 WAL/SHM 文件）。首次打开旧库会在事务中迁移可明确归属的记录并写入 `rex_schema_metadata` 标记。歧义、冲突和找不到归属的键不自动分配；不要删除标记反复尝试迁移，也不要直接编辑原库猜测记录归属。先在备份副本中核实。旧版本已经错误归属或混合的历史不能自动还原，不支持降级后继续共用同一个库。

Exit all rex processes and back up the complete `~/.rex` directory before upgrading, including any WAL/SHM files. Migration runs once in a transaction, recording a metadata marker. Ambiguous, conflicting, or unmatched keys remain unassigned. Do not delete the marker to rerun migration; inspect a backup copy for manual recovery. Previously misassigned history cannot be automatically reconstructed. Sharing the same database with downgraded builds is unsupported.
