# 任务验证

目标：发现框架做错了什么，以同一任务验证修复效果，并观察实际 Token。这里保留一个任务 Runner、明确规则初筛和本地回归，不维护阶段基线或额外模型评分协议。

## 运行一个任务

先构建 CLI 并安装所引用的 Agent 快照，准备 Python 依赖：

```bash
mvn -o verify
python3 -m venv evals/.venv
evals/.venv/bin/pip install -r evals/requirements.txt
```

当前任务是离线产品介绍改写，使用相邻 `../salt-robot-skills` 中的 `robot-description-style`，官方模型 Key 来自 `DEEPSEEK_KEY`。不注册数据库、上传、Shell 或修改文件工具。配置见 `task-config.yml`。

```bash
evals/.venv/bin/python evals/task_runner.py prepare --output target/task-review/run-1
evals/.venv/bin/python evals/task_runner.py run --output target/task-review/run-1
```

prepare 只准备输入，不调用模型；run 经正常 CLI 的指定 Skill 执行链运行一次，不自动重试整个任务。输出目录不能重复使用。运行有模型费用，按已经讨论确认的范围执行。

查看 `answer.txt`、`score.json`、`result.json`；必要时看 `stdout.txt`、`stderr.txt`、`state.json`。准备记录只检查本次 JAR、配置、输入和所选 Skill 是否在 prepare/run 之间改变，不扫描全部代码、Skill 或 Python 环境。

规则初筛只检查格式、明确污染片段及事实关键词，不代表语义正确。人工检查有没有编造、是否满足用户要求；FINISHED 也不等于任务做对。`cases/description/example.json` 是表达示例，不用于逐字评分，不传给模型。

Opik 用于查看过程和实际 Token，默认本机项目 `regnexe-skill-week1`。采集失败记录为 `observation_error`，与任务执行失败分开；未采集到 Token 就标为缺失，不估算成真实用量，不补写 Trace。需要比较效果时保持任务、模型、Skill 和配置一致。

## 本地回归

```bash
PYTHONDONTWRITEBYTECODE=1 evals/.venv/bin/python -m unittest discover -s evals -p 'test_*.py' -v
```

这些测试使用本机模拟模型或临时进程，不产生真实模型费用。保留执行超时/进程终止、明确输出规则、Runner 接线、配置更新和 PTY 交互回归。测试通过只证明相应代码行为，不证明任务准确率。

## 已知失败样例

`cases/description/historical-failure.txt` 保存首次真实输出：包含“结构紧凑”“无需编写复杂程序，上手门槛低”等没有输入依据的描述，Reflector 当时仍返回 FINISH。原 Trace：`01a1d364-d88f-77fd-89d7-ed540a0334d8`；当次总用量 6979 Token。

此问题尚未解决。之后尝试的逐条事实协议和离线专用压缩已撤出核心，不作为有效修复；后续围绕正常任务的具体问题讨论最小改动。
