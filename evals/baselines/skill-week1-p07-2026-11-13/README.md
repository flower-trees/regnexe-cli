# P07 首次真实指定 Skill 诊断（2026-11-13）

已执行 **1 次**真实任务。执行链、预算与观测正常，自动初筛通过；助手复核发现缺少事实依据的产品描述，业务质量需要修正，用户人工审阅仍待完成。P08 尚未运行，下一步进入 P09 分析，不自动重测。

## 结果

| 项目 | 实测 |
|---|---|
| 模式 | 指定 robot-description-style，共享主任务链 |
| 模型 | deepseek-flash，规划/执行/反思均相同 |
| 任务状态 | FINISHED；没有触及预算上限 |
| 模型调用 | 3 次：plan 1、execute 1、reflect 1 |
| Token | 输入 6120、输出 859、合计 6979；其中缓存输入 768，已包含在输入数中；服务端计数，非估算 |
| 耗时 | 进程约 9.29 秒；Agent 约 6.19 秒 |
| 业务工具调用 | 0 |
| 数据库/搜索/上传 | 0；网络仅模型 API 与本机 Opik |
| 预算 | 外层 1 轮、执行最多 4 次、累计最多 6 次调用；进程 120 秒，不自动重跑 |

实际模型注册工具仅 read_file/list_files/search_files 和内部 write_todos，不包含 bash/write_file/edit_file；相同 JAR 的本地模拟请求已核验工具列表。独立 home/workspace 无 MCP 配置，子进程仅继承必需系统变量和模型 Key，未继承 DB/上传凭据。完整目标 Skill 正文在执行 Span 中逐字匹配；Trace 最终输出与任务账本一致。

[Opik 项目](http://localhost:28173/default/redirect/projects?name=regnexe-skill-week1)，Trace：`01a1d364-d88f-77fd-89d7-ed540a0334d8`，任务：`6ac860c8-0b0f-42de-97fe-b0d9f6a3b772`，会话：`p07-3d5c3340ebbe`。

## 发现的问题

[原始回答](answer.txt)中的以下表述没有输入事实支撑：

- “结构紧凑”：没有提供尺寸、结构或紧凑性信息。
- “无需编写复杂程序，上手门槛低”：拖拽示教不能证明具体产品的编程要求和易用程度。
- “能够覆盖常见的桌面级教学与轻型作业需求”：用途和负载/半径不足以证明桌面适配及需求覆盖。

六轴、3kg、600mm、用途与拖拽示教均被保留，“官网”和内部笔记已清除。自动初筛仅检查 JSON、禁用片段、参数堆砌与词面事实存在；它无法证明其他宣传语没有编造。反思模型也给出“仅使用提供事实”的 FINISH，漏掉了这些问题。不得将 FINISHED 或初筛 1 分解释为任务质量成功。

[复核记录](review.json)明确标注为助手复核，没有新增付费 Judge 请求。Opik 分别写入 `p07_automatic_initial_checks=1` 和 `p07_assistant_grounding_review=0`；用户人工结论仍为 pending，不冒称人工已通过。

## 文件与复现

- [result.json](result.json)：运行状态、次数、耗时、Token 与 Trace 关联。
- [score.json](score.json)：自动初筛及待人工事项；[review.json](review.json)：事实支撑问题。
- [opik.json](opik.json)、[feedback.json](feedback.json)：轨迹回读和反馈写入记录。
- [baseline.json](baseline.json)：真实运行时内容清单，ID `fb05e9decf4be56d9156582d7e8a7295f63ad0a233ce44ca15f895e22cc3255e`。
- [review-manifest.json](review-manifest.json)：归档时清单，ID `b9e0a128aa85b9ab85f81f1fb958887ebad7fc6fcfd825c2f11fe1bda3afaf73`。仅本地模拟测试文件在真实运行后拆分同行语句；运行逻辑、评分器、配置、Skill 和 JAR 均未变化。本地模拟测试已复跑，未重复真实模型调用。
- [verification.json](verification.json)：Java 87 项、规则测试 4 项、本地模拟 Runner 1 项，以及内嵌 Agent 与缓存/71 份源码一致的证据。

原始本机产物在 `target/skill-eval-week1/p07-20261113/`，含独立会话库、终端输出、实际 stdin、账本和执行时测试文件备份。基线采集时 CLI 新实现尚未提交，不能以 Git HEAD 代表全部运行源码。报告只包含合成产品与环境摘要，没有密钥值。

单例入口：[skill_week1_runner.py](../../skill_week1_runner.py)。prepare 不调用模型；run 会调用真实模型，且同一目录有 attempt.json 后禁止再次执行。新建目录不会自动获得重测授权；先分析本次问题，不以反复创建运行目录刷通过率。

P09 应先检查事实约束和反思检查为何漏掉泛化宣传语，再选择一个变量修正；不根据本次答案机械追加通用禁用词，不同时改 Skill、任务、模型和预算。参考文风最终确认及数据库闭环仍未完成。
