# Regnexe CLI 评测

默认评测配置使用 DeepSeek 官方 `deepseek-flash`，执行、规划、反思以及旧数据集中可选的 LLM Judge 均使用 Flash；Key 通过 `DEEPSEEK_KEY` 引用。新加固用例采用规则和真实工具证据评分，不调用 Judge。

## 本次测试集

| 文件 | 数量 | 用途 |
|---|---:|---|
| `dataset/hardening-regression-2026-11.yaml` | 31 | 对应已有 Java 回归测试，不交给模型判断 |
| `dataset/hardening-agent-2026-11.yaml` | 12 | 真实模型下的正常工具调用、超时、路径边界与多轮任务 |
| `dataset/hardening-interactive-2026-11.yaml` | 4 | 真实 PTY 自动验收 Ctrl+C、会话隔离、Agent 重建与重启 |
| `dataset/cases.yaml` | 28 | 保留的旧综合用例，不与本次结果混合 |

计划与观察顺序见 [加固功能评测计划](../docs/plan/02-hardening-evaluation-plan.md)。导入数据集只保存定义，不表示已执行或通过。

首轮完整评测已执行，见[评测报告](../docs/log/2026-11/2026-11-04.md#7-首次完整评测结果)：既定断言 47/47，但补充输入保真检查仅 8/12，应先修复 Shell 输入转义问题再针对性复测。

## 环境

```bash
python3 -m venv evals/.venv
evals/.venv/bin/python -m pip install -r evals/requirements.txt
```

需要 Java 17+、Maven、运行中的本机 Opik（`http://localhost:28173`）及环境变量 `DEEPSEEK_KEY`。不再需要 `llm-router` 或 `LITELLM_MASTER_KEY`。

本地交互配置可先预览，再更新；更新会先备份原文件，只替换模型配置，Key 不写成明文：

```bash
evals/.venv/bin/python scripts/configure-deepseek-flash.py
evals/.venv/bin/python scripts/configure-deepseek-flash.py --apply
```

## 只准备，不执行模型

```bash
# 离线校验测试定义、Java 测试存在性、评分规则及临时夹具
PYTHONDONTWRITEBYTECODE=1 evals/.venv/bin/python evals/prepare_hardening.py

# 检查评测框架；不调用模型或 Opik
PYTHONDONTWRITEBYTECODE=1 evals/.venv/bin/python -m unittest discover -s evals -p test_hardening.py -v

# 将三组定义导入本机 Opik，并回读检查；不创建实验分数
PYTHONDONTWRITEBYTECODE=1 evals/.venv/bin/python evals/prepare_hardening.py --upload
```

重复导入相同定义不增加记录；已存在定义发生改变时要求换数据集版本，避免静默混用结果。

## 后续执行真实模型测试

以下命令会真实调用官方 API 并创建 Opik Experiment。本次准备阶段不执行。

```bash
mvn -o verify

# 先只跑一个冒烟用例，核对模型、Trace、工具证据与评分链路
PYTHONDONTWRITEBYTECODE=1 evals/.venv/bin/python evals/runner.py \
  --cases evals/dataset/hardening-agent-2026-11.yaml \
  --dataset-name regnexe-cli-hardening-2026-11-agent-v1 \
  --tag deepseek-flash-smoke-20261104 --case-id smoke-utf8 --keep-workdirs

# 冒烟确认后再运行完整 12 例
PYTHONDONTWRITEBYTECODE=1 evals/.venv/bin/python evals/runner.py \
  --cases evals/dataset/hardening-agent-2026-11.yaml \
  --dataset-name regnexe-cli-hardening-2026-11-agent-v1 \
  --tag deepseek-flash-hardening-v1 --keep-workdirs
```

支持 `--only smoke,normal_files` 等分类过滤和 `--jar /absolute/path/to/regnexe-cli.jar`。默认使用仓库 `target/regnexe-cli.jar`，避免误用未更新的已安装 wrapper；测试前必须构建最新产物。

每例在独立临时父目录内建立 `workspace/`、`outside/` 和 `home/`。`outside/` 只是测试框架拥有的合成夹具，不是用户数据；`home/` 用于隔离 Java 会话库和用户插件。所有夹具和链接必须位于该临时父目录。

`rex-eval-config.yml` 关闭写入和 Shell 确认以适配管道输入，不能据此验证 `always`。真实程序并未运行在操作系统沙箱中，测试目录隔离不等于限制 Shell 的所有能力。新用例只使用受控合成内容，不运行旧数据集中的外发请求或删除任务。

## 结果与观察

### Java 回归结果回写与真实 PTY

先执行最新的 `mvn -o verify`，再将实际 JUnit 报告中的 31 项加固结果关联到已有数据集：

```bash
evals/.venv/bin/python evals/publish_results.py \
  --suite regression --tag my-run --output-dir evals/results/my-run

# 以下命令会真实调用模型并驱动 PTY，不是准备操作
evals/.venv/bin/python evals/interactive_runner.py --output-dir evals/results/my-run

evals/.venv/bin/python evals/publish_results.py \
  --suite interactive --tag my-run \
  --results-file evals/results/my-run/interactive-executions.json \
  --output-dir evals/results/my-run
```

PTY 使用独立 Java 用户目录并开启确认，保留终端日志、会话库和真实进程检查。回写实验的 Trace 时长是记录上传耗时，实际测试时长看输出中的 `elapsed_s`；Token 应包含暂停前后的全部终端分段汇总。

`review_input_fidelity.py` 可比较完整模型运行的输入与任务账本，并向已有实验追加 `task_input_fidelity`，不重新调用模型。`verify_run.py` 是本轮固定产物的回读检查工具，可核对分数与输出，按需补齐缺失的真实结果。

- 评分同时检查文件结果/最终回答和实际工具 Span；没有工具证据，不能仅凭“成功”“已拦截”判通过。
- 多轮用例等待所有任务 Trace 完成，跨轮收集工具结果；不随意选取会话的一条 Trace。
- `evidence_missing` 表示证据未收齐，应先区分模型未调用工具与 Opik 观察链路问题。
- 实验输出包含会话名、Trace ID、工具观察、任务状态和耗时，原始模型 Token 汇总在任务 Trace 的元数据中。
- 超时用例看工具 Span 的耗时与真实超时结果，不能把包含模型推理的总耗时当作 Shell 的 30 秒。
- 不把持久化 `always` 当作已实现能力：当前重启后仍需确认，该需求记录在月度 TODO。

Opik 默认使用本机 API 和 `default` workspace，可通过 `OPIK_URL_OVERRIDE` / `OPIK_WORKSPACE` 覆盖。入口设置 localhost 的 `NO_PROXY`，避免本机 Trace 被转发到外部代理。

旧综合数据集仍可使用原来的 `--tag` / `--only` / `--case-id` 入口；其部分用例使用 Judge，因此不与本次规则评测直接比较。
