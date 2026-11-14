# Skill 调用与统一执行链

用户通过 `/<skill名> 参数` 指定 Skill；`/skills` 查看可用项。CLI 将名称解析为 capability ID，交给 Agent 的 `executeSkill`，最终构造带 `requiredSkillId` 的普通 TaskRequest。

指定 Skill 与 Auto 共用计划、执行、反思、状态持久化、取消及恢复流程。指定模式约束候选和计划选择，不再维护独立的 Skill 子 Agent 执行路径；未知、禁用或加载失败的 Skill 明确报错，不静默转为 Auto。

Skill 指令注入当前执行上下文，基础工具与 Skill 自带工具在同一循环中使用。只有指令而没有工具的 Skill 可以完成纯文本任务，不要求为通过反思而虚构工具调用。

已知限制：当前指定模式未解析 Skill 声明的额外 allowedTools，与 Auto 存在差异。后续需先讨论工具规则再修改。单 Skill 约束也不代表已完成多 Skill 协作验证。

## 执行预算

可通过 Agent 默认配置或 TaskRequest 的 `ExecutionBudget` 设置 `hardMaxIterations`、`maxModelCalls`；零表示不增加相应限制，未提供预算保持原执行路径。Planner 估计的迭代次数受配置硬上限约束。

模型调用尝试覆盖计划、执行、反思及已接入的摘要路径，包含失败尝试和解析重试；计数随任务保存，恢复后累计。它不承诺涵盖 SDK 内部 HTTP 重试，也不是计费 Token 硬限制。

已知限制：当前预算的 `retryExecution` 默认 false，影响整个执行循环和工具重试；接近迭代上限时会插入提醒。这两项行为仍需与预算上限解耦讨论，本次清理未修改。

状态与停止原因用于区分完成、中断、上限和错误。FINISHED 只表示框架流程完成，不能替代对实际任务结果的检查。
