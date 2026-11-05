# 设计文档索引

文件采用两位序号加主题名的命名方式，后续新增从 `08` 起追加，已有编号保持稳定。当前编号用于组织阅读顺序，不表示全部文档的创建时间。

| 编号 | 文档 | 阅读说明 |
|---|---|---|
| 01 | [CLI 总体设计](01-design.md) | 初始架构与分步实施方案；部分命令、目录及依赖已演进 |
| 02 | [Agent 改进方向](02-agent-improvement.md) | 2026-07-07 的问题诊断与提案；部分已修复，任务分解提案曾决定不实施 |
| 03 | [Skill 斜杠调用](03-skill-slash-invocation.md) | 直接调用 Skill 的方案与追加修订 |
| 04 | [任务恢复设计](04-resume-task-design.md) | 当前 `/resume` 的设计与已知边界 |
| 05 | [CLI 交互皮肤](05-cli-ui-theme.md) | 终端主题、渲染和确认交互方案 |
| 06 | [Opik 链路追踪](06-opik-tracing.md) | 追踪接入、事件关系和使用限制 |
| 07 | [会话执行与恢复安全](07-session-execution-and-resume-safety.md) | 本地执行互斥、指定任务恢复、版本校验及边界 |

这些文档保留各自阶段的设计背景，不应整体当作当前待办清单。当前功能以代码和[使用配置说明](../guide/configuration.md)为准；后续方向见[开发规划](../plan/README.md)。

历史诊断的落地情况可结合 [Session 与 Plan 性能开发日志](../log/2026-07-10-session-and-plan-perf.md)阅读。文中指向 `regnexe-agent` 或 `j-langchain` 的设计编号属于对应仓库，不随本仓库重编号。
