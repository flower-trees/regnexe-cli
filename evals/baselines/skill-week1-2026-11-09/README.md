# Skill 首周 P00 / P01 基线（2026-11-09）

此目录是文件与实际 JAR 加载器的快照，不是模型测试集、测评分数或业务成功率。

- `summary.json`：版本、产物/依赖摘要、计数、目标 Skill 与启用开关对照。
- `inventory.csv`：145 个源文件逐项对应注册、启用、候选状态、元数据状态与正文摘要。
- 完整本机证据：`target/skill-eval-week1/20261109T132544Z/`，包括 `baseline.json`、`inventory.json`、`loader.log`、隔离配置/home/workspace 与实际 JAR 解包类路径。该目录由 Git 忽略，不依赖它作为唯一的长期结果记录。

结果：145 文件 → 145 注册 → 145 启用 → 145 Skill 候选；注册差异、冲突和短名重复均为 0。53 个运行时描述为空：52 个缺 frontmatter、1 个 YAML 格式异常；不自动修复原材料。目标 robot-description-style 的 ID、描述、正文及禁用/恢复行为通过核对。

探针复用 CLI 的实际目录解析、RegnexeAgentBuilder 的技能注册与启用状态方法，但不调用 CLI main、Agent build 或任何工具。计数不包含 CLI 基础工具或 MCP 工具，不代表模型会正确选择这 145 个 Skill。

复现（仅离线构建和加载，不执行模型）：

```bash
mvn -o -DskipTests package
PYTHONDONTWRITEBYTECODE=1 evals/.venv/bin/python evals/prepare_skill_week1.py
```

默认读取相邻 `salt-robot-skills` 仓库，可用 `--skills-root` 指定另一份源码。每次运行创建新的隔离目录；原始技能文件不修改，不继承全局 Skill/MCP/模型凭证。JAR 的构建时间可能使完整 SHA-256 改变，应以每次新记录为准；不要修改本快照冒充旧基线。
