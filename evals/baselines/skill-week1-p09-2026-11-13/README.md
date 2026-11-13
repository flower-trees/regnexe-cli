# P09 首轮事实核验修复与反思回放（2026-11-13）

已实现可选的逐项事实核验协议，并用一次真实反思回放拦截 P07 的旧错误答案。生成质量未重新测试，逐条语义判断仍有偏保守问题，暂不进入 Auto 或数据库闭环。

## 定位与单变量修复

P07 的 Skill 正文完整注入、调用和预算正常、输出与 Trace 一致，已排除加载、工具、预算和观察缺失。本次问题属于事实约束的指令遵循及 Reflector 错误接受：它检查了参数和显式未知字段，却放过“结构紧凑”“无需复杂编程”等无依据描述。关于模型倾向于只检查显式字段的判断来自输出与反思理由，是分析推断，不是对内部推理机制的证明。

本轮只改 Reflector 的事实核验协议：

- TaskRequest.authoritativeFacts 保存调用方明确提供的正向事实快照；本例从既有 facts 提取，排除 unknown 列表，不把 before/答案或普通常识当成新增产品事实。
- 框架按输出文本标点生成有编号片段，要求反思返回每个编号的 SUPPORTED/UNSUPPORTED/UNCERTAIN 及来源 JSON pointer。
- 缺项、重复编号、未知判定、空/无效/容器指针均视为未完成核验；UNSUPPORTED/UNCERTAIN 会阻止 FINISH，保存具体修改提示。
- 保存 FACTUAL_REVIEW_INCOMPLETE 或 UNSUPPORTED_CLAIMS，避免外层轮数耗尽时被 ROUND_LIMIT 掩盖。
- 默认不启用，普通任务保持旧行为。CLI 的 require_factual_review 与 factual_review_source 支持评测配置；要求核验却缺少来源时明确失败。

Skill 内容、任务目标、事实、P07 原始错误答案、Planner/Executor 提示词、模型及常规 P07 预算均保持。新的事实块和片段清单只进入 Reflector，已用完整主链测试验证。

## 无真实模型验证

Agent 本地 72 项通过，新增 FactualGroundingTest 8 项：旧 FINISH 无核验不能通过、无依据声明覆盖 FINISH、正确参考表达可通过、覆盖/判定/引用异常被拒绝、事实快照与默认兼容、完整主链只改反思且保留停止原因。CLI Java 87 项通过，本地模拟 Runner 1 项通过；实际 SQLite 任务包含正向事实，参考输出在完整核验下仍可完成。

另外对实际打包 Reflector 做固定响应回放：直接返回 P07 原来的 FINISH（没有新核验记录），被框架改为 CONTINUE / FACTUAL_REVIEW_INCOMPLETE，没有真实模型或工具请求。

## 真实反思回放

只重新判断已有错误答案，没有重新生成产品文案、没有调用 Planner/Skill/工具。原 Goal、Plan narrative 与答案冻结在 [输入](../../fixtures/skill-week1/p09-reflection-input.json)。使用实际打包 Reflector 与同一 deepseek-flash，硬限一次框架模型调用，进程截止时间 120 秒，不自动重试。

| 指标 | 结果 |
|---|---|
| 模型调用 | reflect 1，其他阶段 0 |
| Token | 输入 1826、输出 607、合计 2433；缓存输入 256 已计入输入，非估算 |
| 耗时 | 进程约 5.06 秒；反思约 3.31 秒 |
| 模型原始判断 | CONTINUE |
| 框架最终诊断 | UNSUPPORTED_CLAIMS |
| 覆盖 | 13 个编号片段全部返回检查 |
| 工具、数据库、Auto、完整 P07 重跑 | 均为 0 |

新版识别出了“结构紧凑”“无需编写复杂程序”“上手门槛低”及“覆盖桌面级需求”等原漏检内容。原始模型自己返回 CONTINUE，不只是框架把缺字段的 FINISH 改掉。[原始模型判断](model-review.json)与[框架结果](result.json)分别保留。

[Opik 项目](http://localhost:28173/default/redirect/projects?name=regnexe-skill-week1)，本次 Trace：`01a1d3be-7b37-7bcd-9124-082c3ff23948`，关联原 P07 Trace：`01a1d364-d88f-77fd-89d7-ed540a0334d8`。回读确认一个 LLM Span、零工具 Span。反馈 `p09_historical_failure_rejected=1` 仅表示旧失败样例被拒绝，不是业务任务成功分数。

## 未关闭的边界

模型还拒绝了“适合课堂演示、教学实验以及轻量物件搬运”和“手动引导机械臂完成动作示教”，这些可能分别属于正常用途转述和能力术语解释。协议虽然允许术语解释，实际模型仍偏保守。[校准记录](review-notes.json)保留了这些候选误报，用户人工结论仍待完成。

JSON pointer 存在只证明引用真实，不能证明语义蕴含；片段拆分也不是通用语义解析。若模型给每项错误地标 SUPPORTED 并引用真实但不相关的事实，结构检查不能独立识别。因此不宣称完全解决编造或提供准确率/稳定性结论，也不根据单例输出建立业务词语黑名单。

下一步先明确正常转述/术语解释与新增卖点的边界，再做一次正常 P07 指定任务验收。仍不进行 Auto、数据库写入或连续真实重测。若以后扩大事实核验到写库任务，还需独立验证工具执行及数据库回读。

## 证据与入口

- [baseline.json](baseline.json)：实际源码、JAR、模型配置和回放输入的统一清单，ID `a2e6ec777323fdb5b530d64a3b55dc54971c11558437b53203ab6eb43ba0c186`。采集时两仓库有未提交改动。
- [verification.json](verification.json)：测试、单变量范围、内嵌 Agent/缓存及 73 份源码一致的证据。
- [opik.json](opik.json)、[prepared.json](prepared.json)：关联与单次额度；原始事件、输出和编译产物在 `target/p09/reflector-replay-20261113/`。
- [replay_factual_review.py](../../replay_factual_review.py) + [FactualReviewReplay.java](../../java/FactualReviewReplay.java)：可复现的独立反思回放，prepare 不调用模型，run 默认会调用一次真实模型；--mock 只在 prepare 时指定。本次真实额度已使用，不自动再运行。


补充：[反思输入与 Token 审计](prompt-audit.md)记录了实际重复、缺失与处理问题，附未应用的精简布局草稿。本次审计没有新增模型调用。
