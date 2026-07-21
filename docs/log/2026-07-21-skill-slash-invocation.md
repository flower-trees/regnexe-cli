# 开发日志：`/skill名` 直接调用 + Claude Code Skill 兼容性

- 日期：2026-07-21
- 涉及仓库：`j-langchain`（`../j-langchain`）→ `regnexe-agent`（`../regnexe-agent`）→ `regnexe-cli`（本仓库），依赖方向从左到右，改动也应按此顺序 `mvn install`
- 设计文档：`docs/design/skill-slash-invocation.md`（本仓库）——本 log 是该设计的落地实现记录，包含设计阶段没预料到的几个真实 bug
- 面向读者：下一个接手这部分代码的人或 AI；目的是让你不用重新看完整个对话就知道"改了什么、为什么改、顺序是什么"

## 0. 一句话概括

目标是让 `regnexe-cli` 支持 Claude Code 风格的 `/skill名 参数` 直接调用，并尽量原样兼容真实的 Claude Code 插件目录（用 `skill-creator` 反复实测验证）。过程中发现——**"支持 SKILL.md 格式"和"让一个真实 Claude Code skill 在这里正常工作"之间还有五个坑**，都是靠实机跑 `skill-creator` 建技能才暴露出来的，不是纸面设计能想到的。

## 1. 改动的逻辑主线（按依赖顺序 + 发现顺序）

```
① 功能骨架：manifest 双格式兼容 + executeSkill() 直调入口 + CLI 侧 /skill 分发、/skills 列表
        ↓ 用真实 skill-creator 插件实测
② bug：脚本被抽到隔离临时目录执行 → 包内相对 import 和 CWD 相对查找全部失效
        ↓ 继续实测
③ bug：无 allowed-tools 的技能（真实 Claude Code skill 的常态）拿到 0 个工具，
       模型把库文件 utils.py/__init__.py 误当 shell 乱调
        ↓ 继续实测
④ bug：脚本多参数字符串没有按 argv 切分，传给 argparse 的是一整坨乱码
        ↓ 继续实测（这次是"编辑已有技能"场景）
⑤ bug：claude-compat 沙盒工作区是匿名临时目录，跟项目真实的
       .rex/marketplaces/ 完全不通，skill-creator 看不到已建的技能，
       建的新技能也进不了 /skills 能扫到的地方
        ↓ 继续实测
⑥ bug：SKILL.md 缺 "---" frontmatter 围栏时 name 解析成空字符串，
       生成一个 capabilityId 带尾部句点、短名为空、注册了但永远调不到的技能
```

①是设计阶段规划的功能；②～⑥都是同一个根因链条的连续暴露——每修一层，实测就往前推进一步，露出下一层问题，全部集中在 `j-langchain` 的 `Skill`/`ScriptTool`/`SkillConfig` 那几个文件。

---

## 2. j-langchain（技能执行引擎，改动量最大）

### 2.1 ScriptTool 原地执行（对应 ②）

**现象**：`skill-creator` 的 `run_eval.py` 报 `ModuleNotFoundError: No module named 'scripts'`；`quick_validate.py` 报 `"SKILL.md not found"`（明明文件就在）。

**根因**：`ScriptTool.from()` 原来无论如何都把脚本源码抽取到一个全新的隔离临时目录执行——脚本包内部 `from scripts.utils import xxx` 这种相对导入找不到同目录的 `utils.py`；脚本内部按相对路径找 `SKILL.md`/`references/` 也找不到，因为 CWD 是随机临时目录，不是技能真实所在目录。

**修法**：
- `ScriptDef.java` 加两个字段：`sourcePath`（脚本真实磁盘路径）、`workDir`（该用作 CWD 的目录）
- `ScriptTool.from()`：有这两个字段就原地执行（`cwd` 设为 `workDir`），是 Python 包（同目录有 `__init__.py`）就用 `python -m scripts.xxx` 而不是 `python scripts/xxx.py`，这样包内相对 import 才能解析
- `FileSystemSkillConfigLoader.loadScripts()` 填充这两个新字段（`workDir` = 技能根目录，不是 `scripts/` 本身）
- 没有 `sourcePath`/`workDir` 的场景（classpath 加载、代码里手写的 `ScriptConfig`）保留原来的临时拷贝执行方式，行为不变

### 2.2 入口点检测 + Claude-compatible 模式（对应 ③）

**现象**：`skill-creator` 反复尝试把 `utils`（`scripts/utils.py`，纯工具函数库）和 `__init__`（空的包标记文件）当成通用 shell 调用，传各种 `ls`/`find`/`mkdir` 进去，全部空返回，模型陷入重复试错直到打满 `max_agent_iterations`。

**根因两层**：
1. `utils.py`/`__init__.py` 这类没有 `if __name__ == "__main__":` 的库文件，之前也被无差别注册成了可调用 Tool——模型看到一个像模像样的工具名，猜它是 shell，猜错了还没有报错提示，只是安静地不返回任何东西
2. 更根本的问题：真实 Claude Code skill 几乎从不声明 `allowed-tools`（那本来就是 j-langchain 自己加的扩展字段，Claude Code 原生skill 靠"和宿主 agent 共享全部工具"运作），所以这类 skill 在 j-langchain 的隔离子执行器里天生拿到 0 个工具，除了瞎猜没有别的办法建目录、写文件

**修法**：
- `ScriptTool.hasEntrypoint(type, content)`：正则检测 `if __name__ == "__main__":`，Python 文件没有就不注册成 Tool（其他语言无此约定，一律当作可执行）；`FileSystemSkillConfigLoader`/`ClasspathSkillConfigLoader` 的 `loadScripts()` 接入这个过滤
- `SkillConfig` 新增 `claudeCompatMode`（默认 `false`）——`FileSystemSkillConfigLoader`/`ClasspathSkillConfigLoader` 加载出来的一律置 `true`（真实目录来源），代码里手写的 `SkillConfig.builder()...build()` 保持 `false`，行为不变
- 新建 `SkillWorkspaceTools.java`：5 个沙盒文件系统工具（`create_directory`/`write_file`/`read_file`/`list_directory`/`file_exists`），所有路径相对固定 `root` 解析，拒绝 `..` 穿越、拒绝越界绝对路径、拒绝指向 root 外的软链接；另有一个默认不开启的 `scopedBashTool()`（cwd 锁定但非硬沙盒），只能显式 opt-in
- `Skill.java`：`claudeCompatMode == true` 且该技能没声明 `allowed-tools` 时，`collectTools()` 才把上面 5 个工具（以及可选 bash）加进去；`Skill.Builder` 新增 `claudeCompatMode(boolean)`（显式覆盖）、`claudeCompatWorkspace(Path)`（不设就懒加载建临时目录）、`claudeCompatBash(boolean)`；`buildSystemPrompt()` 在该模式激活时把沙盒根目录的绝对路径写进 system prompt，模型不用再 `pwd`/`ls -la /` 摸索

### 2.3 脚本多参数拆分（对应 ④）

**现象**：`skill-creator` 调 `generate_report.py --skill-name "xxx" -o /tmp/out.html` 这种带多个 flag 的命令，脚本内部 `argparse` 直接炸 Traceback。

**根因**：`ScriptTool` 把整个 `args` 字符串当**一个** argv token 传给 `ProcessBuilder`，脚本自己的 `argparse` 只看到一个乱码定位参数，不是模型想要的 `input --skill-name X -o Y` 三四个独立参数。之前没暴露是因为之前只调过单参数命令（`quick_validate <path>`），长度上"蒙对"了。

**修法**：`ScriptTool.splitShellArgs()`——按空白分词，识别单/双引号包裹的值（保留成一个 token），双引号内认 `\"`/`\\` 转义；**不走 `bash -c`**，分号/管道/`$()`/反引号一律当普通字符，不会被解释执行（`args` 来自模型输出，避免命令注入）。`commandFor()`/`moduleCommand()` 接入这个拆分。

### 2.4 SKILL.md 缺失 frontmatter 围栏时的兜底（对应 ⑥）

**现象**：`skill-creator` 有一次直接把 `name: tang-shi\ndescription: ...` 当纯文本写进 `SKILL.md`，没加 `---` 围栏。`FileSystemSkillConfigLoader.parseSkillMd()` 检测不到围栏时整份文件退化成 body，`name` 变成空字符串——下游 `capabilityId` 拼成 `"tang-shi." + ""` = `tang-shi.`（尾部多个点），短名是空的，`/skills` 列得出来但既没法按短名调用，列表里也是空白一行。

**修法**：`FileSystemSkillConfigLoader`/`ClasspathSkillConfigLoader` 在 `parsed.name()` 为空时兜底用目录名——跟 `DefaultPluginManager` 里 `pluginId` 缺失时兜底用目录名的逻辑保持一致，不引入新概念。

### 2.5 诊断信息（顺手做的，不对应具体 bug）

`McpAgentExecutor` 达到 `maxIterations` 上限抛 `MAX_STEPS` 异常时，原来只有一句"Max iterations (20) reached..."，现在附带最近 10 次工具调用+返回结果的摘要（`summarizeStepsForDiagnostic()`），方便定位卡在哪个重复调用上，不用去翻完整事件流。

### 2.6 j-langchain 新增文件清单

- 新建：`core/skill/SkillWorkspaceTools.java`
- 新建测试：`ScriptToolInPlaceExecutionTest`、`ScriptToolArgSplittingTest`、`SkillClaudeCompatModeTest`、`SkillWorkspaceToolsTest`、`SkillCreatorClaudeCompatRegressionTest`（用本机真实安装的 `skill-creator` 插件跑端到端回归）、`loader/FileSystemSkillConfigLoaderScriptsTest`
- 改动：`McpAgentExecutor.java`、`ScriptDef.java`、`ScriptTool.java`、`Skill.java`、`SkillConfig.java`、`loader/ClasspathSkillConfigLoader.java`、`loader/FileSystemSkillConfigLoader.java`

---

## 3. regnexe-agent（能力市场 + 直调入口）

### 3.1 manifest 双格式兼容 + pluginId 冲突降级（对应①，设计阶段规划内）

`DefaultPluginManager.loadPlugin()`：
- 支持 `.claude-plugin/plugin.json`（真实 Claude Code 插件清单格式）作为 `plugin.yaml` 的等价物，两者都是合法 YAML，复用同一个解析器，不引入 JSON 依赖
- pluginId 冲突（比如项目级和用户级目录都有同名插件）从抛异常降级成 `log.warn` 跳过——先扫到的赢，不会把整个加载过程炸掉

### 3.2 扁平技能布局识别（对应⑤的一部分）

`DefaultPluginManager.loadFlatSkillPlugin()`（新方法）：一个被扫描目录如果没有任何 manifest，但根下直接有 `SKILL.md`，就当作单技能插件加载（pluginId = 目录名）。这是 Claude Code 的"个人/项目技能"扁平布局（`<name>/SKILL.md` 直接在根，无 `plugin.yaml`、无嵌套 `skills/` 子目录）——**也正是 `skill-creator` 自己创建独立技能时的输出格式**。没这一层，`skill-creator` 建出来的任何技能都进不了市场扫描范围。

`loadTools()` 也接入 j-langchain 那边的 `ScriptTool.hasEntrypoint()` 过滤，跟 `Skill` 侧保持一致。

### 3.3 `RegnexeAgent.executeSkill()` 直调入口（对应①）

绕开 Search/Plan/Reflect，直接按 `capabilityId` 解析 marketplace 里的 SKILL 能力并跑；事件走原有 `eventListener`（自动带 token 汇总）；`sessionId` 非空时复用已有的 `storeSessionRound` 把这轮存进会话历史。新增 `getMarketplace()` 只读访问，供 CLI 侧列出/解析技能用。

### 3.4 claude-compat 工作区透传（对应⑤）

**现象**：`skill-creator` 每次调用都在系统临时目录下建一个全新空目录当工作区，跟项目里真实的 `.rex/marketplaces/` 毫无关系——`list_directory "."` 永远是空的，看不到已建技能；新建的技能也走丢在临时目录里，`/skills` 永远扫不到。

**修法**：新增 `ContextBusKeys.CLAUDE_COMPAT_WORKSPACE`，`RegnexeAgentBuilder.withClaudeCompatWorkspace(Path)` 一路透传：`RegnexeAgent`（`executeSkill()` 直调路径 + `buildTransmitMap()` 跨轮路径）→ `CapabilityExecutor.resolveCapabilities()`（planner 自主选中路径）→ `Skill.Builder.claudeCompatWorkspace(...)`。两条调用路径（直调 `/skill名` 和 planner 自主选中）都覆盖到，不会有一条路径生效一条不生效的不一致。

### 3.5 regnexe-agent 新增文件清单

- 新建测试：`RegnexeAgentExecuteSkillTest`（含 claude-compat 工作区路由验证）、`market/DefaultPluginManagerManifestCompatTest`
- 改动：`RegnexeAgent.java`、`RegnexeAgentBuilder.java`、`market/DefaultPluginManager.java`、`task/worker/CapabilityExecutor.java`、`task/worker/ContextBusKeys.java`

---

## 4. regnexe-cli（CLI 接线层，改动量最小但用户直接感知的部分）

### 4.1 `/skill名` 分发 + `/skills` 列表（对应①）

`CliMain.java`：
- `buildAgent()` 接入自动发现——扫描 `.rex/marketplaces/*/plugins`（项目级 + 用户级），外加 `RexConfig.skills.extra_dirs` 里配置的额外目录（`resolveSkillDirectories()`/`listMarketplacePluginDirs()`），不写死单一 `default` marketplace 名字，新建一个 marketplace 子目录即可被自动发现
- `SlashResult` 从纯 enum 改成小类，多带一个 `RUN_SKILL` 变体（`capabilityId`/`args`/原始输入），`CONTINUE`/`EXIT`/`AGENT_REBUILT` 三个既有分支完全不用改
- `handleSlashCommand` 的 `default` 分支：未知命令先按短名查表，唯一命中直接执行；多个 plugin 撞同一个短名就报歧义，要求用户改用全称 `pluginId.skillName`，不做静默选第一个
- 新增 `/skills` 命令，列出所有已发现技能的短名/全称/描述

### 4.2 claude-compat 工作区绑定项目真实目录（对应⑤）

`buildAgent()` 追加 `.withClaudeCompatWorkspace(workspace.primaryRoot().resolve(".rex/marketplaces/default/plugins"))`——**特意只给到 plugins 目录，不是整个项目根目录**，这样任何 claude-compat 模式的技能（包括从第三方 marketplace 目录加载的）都只能读写技能/插件内容，碰不到源码、`.git`、`.env`。

### 4.3 `.gitignore`

追加 `*.rex`——排除 `.rex/`（session 数据库、历史记录、市场技能等运行时/工作区数据），这些不该进 git。

### 4.4 regnexe-cli 新增/改动文件清单

- 新建文档：`docs/design/skill-slash-invocation.md`（设计文档，含五个 bug 后续追加的修订记录）
- 改动：`CliMain.java`、`config/RexConfig.java`（新增 `SkillsConfig.extraDirs`）、`.gitignore`

---

## 5. 验证记录（供提交信息/PR 描述参考）

- j-langchain：39 个单测（含 1 个用本机真实安装的 `skill-creator` 插件跑的端到端回归），全过，无回归
- regnexe-agent：19 个既有 + 12 个新增（含 claude-compat 工作区路由的真实 LLM 调用验证），全过，无回归
- regnexe-cli：无独立单测（项目现状如此），全部靠实机跑 `java -jar target/regnexe-cli.jar` 手工验证——`/skills`、短名调用、跨 marketplace 发现、`skill-creator` 编辑已有技能并新建技能、`/skills` 能扫到新建结果，均已实测通过

## 6. 给下一个人的提醒

1. **三仓库改动必须按 `j-langchain → regnexe-agent → regnexe-cli` 顺序 `mvn install`**，跟上一份 log（`2026-07-10-session-and-plan-perf.md`）踩过的坑一样；这次额外踩了一次新坑：`mvn package`（不带 `clean`）在改了 SNAPSHOT 依赖后，Spring Boot repackage 步骤有时不会重新拉取最新 jar，务必用 `mvn clean package`，用 `unzip -p target/regnexe-cli.jar BOOT-INF/lib/xxx.jar | md5` 和 `~/.m2` 里的对一下再验证。
2. `claudeCompatMode` 相关的改动全部是"加法"——只在 `claudeCompatMode == true && allowedTools 为空` 时才生效，且默认值是 `false`（只有文件系统 loader 会置 `true`）；代码里手写的 `SkillConfig`/`@AgentSkill` 一律不受影响。回归测试里也各自有一条用例专门盯着这个边界。
3. `SkillWorkspaceTools` 的路径校验（拒绝 `..`/绝对路径越界/软链接越界）是抛 `SecurityException`，会被 `McpAgentExecutor` 的工具调用重试逻辑吃掉变成 `"Tool execution error: ..."` 观测结果给模型看，属于预期行为，不是 bug——之前有一轮实测差点被这个正常拦截误判成回归。
