#!/usr/bin/env python3
"""regnexe-cli eval runner: feeds dataset/cases.yaml into `rex`, scores each run, logs the
whole thing as an Opik Experiment (dataset + per-item traces + scores), so iterations can be
compared in the Opik UI.

Usage:
    source .venv/bin/activate
    export DEEPSEEK_KEY=...                # official DeepSeek API key
    python runner.py --tag baseline-v1
    python runner.py --tag baseline-v1 --only tool_use,file_edit   # filter by category
    python runner.py --tag baseline-v1 --case-id edit-001-create-file  # single case

Prerequisites: DEEPSEEK_KEY, local Opik on :28173, and a freshly built local JAR.
"""
from __future__ import annotations

import argparse
import collections
import os
import json
import hashlib
import subprocess
import shutil
import sys
import tempfile
from pathlib import Path

import yaml
import opik
from opik.evaluation import evaluate
from opik.evaluation.metrics import base_metric, score_result

import rex_client
import scorer

DATASET_NAME = "regnexe-cli-eval-v1"
PROJECT_NAME = "regnexe-cli"
CASES_PATH = Path(__file__).parent / "dataset" / "cases.yaml"


def configure_local_opik():
    os.environ.setdefault("OPIK_URL_OVERRIDE", "http://localhost:28173/api/")
    os.environ.setdefault("OPIK_WORKSPACE", "default")
    os.environ["OPIK_TRACK_DISABLE"] = "true"
    os.environ["NO_PROXY"] = ",".join(filter(None, [os.environ.get("NO_PROXY"), "localhost,127.0.0.1,::1"]))


class CaseScore(base_metric.BaseMetric):
    """Reads the score the task already computed (each case has its own rule/rubric, so
    scoring happens inside the task, not as a uniform formula here) and surfaces it as a
    normal Opik feedback score."""

    def __init__(self):
        super().__init__(name="case_score")

    def score(self, case_score: float, case_reason: str = "", **ignored) -> score_result.ScoreResult:
        return score_result.ScoreResult(value=case_score, name=self.name, reason=case_reason)


def load_cases(path: Path = CASES_PATH) -> list[dict]:
    with open(path) as f:
        return yaml.safe_load(f)


def build_dataset(client: opik.Opik, cases: list[dict], name: str = DATASET_NAME):
    # Opik dataset items require a UUID "id" (or none, auto-assigned) — our own readable
    # case ids go in "case_id" instead. Content-based deduplication (insert's default) keeps
    # repeated runs from piling up duplicate items as long as a case's turns/category don't change.
    ds = client.get_or_create_dataset(name=name, description="regnexe-cli capability eval set")
    # Store complete fixture/scoring definitions so the UI shows what was actually evaluated.
    records = [{"case_id": c["id"], **{key: value for key, value in c.items() if key != "id"}}
               for c in cases]
    ds.insert(records, num_threads=1)
    return ds


def case_id_to_dataset_item_id(ds) -> dict[str, str]:
    mapping = {}
    for item in ds.get_items():
        cid = item.get("case_id")
        if cid:
            mapping[cid] = item["id"]
    return mapping


def make_task(cases_by_id: dict, run_tag: str, opik_client: opik.Opik, keep_workdirs: bool, rex_jar: Path, results_dir: Path):
    def task(dataset_item: dict) -> dict:
        case = cases_by_id[dataset_item["case_id"]]
        sandbox = Path(tempfile.mkdtemp(prefix=f"regnexe-eval-{case['id']}-"))
        def save(output):
            output["sandbox"] = str(sandbox) if keep_workdirs else None
            (results_dir / (case["id"] + ".json")).write_text(
                json.dumps(output, ensure_ascii=False, indent=2, default=str))
            print(f"[{case['id']}] score={output['case_score']} {output['case_reason']}", flush=True)
            return output
        try:
            workdir = rex_client.prepare_case_workspace(sandbox, case)
            try:
                result = rex_client.run_case(
                    case["id"], case["turns"], workdir, run_tag,
                    opik_client=opik_client, project_name=PROJECT_NAME,
                    rex_jar=rex_jar, isolated_home=sandbox / "home",
                )
            except Exception as e:
                # A raw exception here (rex hangs past RUN_TIMEOUT_S, an Opik API hiccup) would
                # otherwise escape task() and abort the *entire* evaluate() run, losing every
                # other case's results — score it as a hard 0 instead and keep going.
                return save({
                    "case_score": 0.0,
                    "case_reason": f"harness error: {e}",
                    "answer": "",
                    "trace_id": None,
                    "elapsed_s": None,
                    "category": case["category"],
                    "case_id": case["id"],
                    "outcome": "harness_error",
                })
            (results_dir / (case["id"] + ".stdout.txt")).write_text(result.stdout)
            (results_dir / (case["id"] + ".stderr.txt")).write_text(result.stderr)
            case_score, case_reason = scorer.score_case(case, workdir, result.answer_text)
            evidence_score, evidence_reason = scorer.score_tool_evidence(case, result.tool_observations)
            case_score = min(case_score, evidence_score)
            case_reason += "; " + evidence_reason
            return save({
                "case_score": case_score,
                "case_reason": case_reason,
                "answer": result.answer_text,
                "trace_id": result.trace_id,
                "trace_ids": result.trace_ids,
                "tool_observations": result.tool_observations,
                "trace_status": result.trace_status,
                "evidence_verified": bool(evidence_score),
                "outcome": "observed" if evidence_score else "evidence_missing",
                "session_name": result.session_name,
                "elapsed_s": round(result.elapsed_s, 1),
                "token_summaries": result.token_summaries,
                "observation_error": result.observation_error,
                "category": case["category"],
                "case_id": case["id"],
            })
        finally:
            if not keep_workdirs:
                shutil.rmtree(sandbox, ignore_errors=True)
    return task


def main():
    ap = argparse.ArgumentParser()
    ap.add_argument("--tag", required=True, help="short label for this run, e.g. baseline-v1")
    ap.add_argument("--only", help="comma-separated category filter")
    ap.add_argument("--case-id", help="run a single case by id")
    ap.add_argument("--keep-workdirs", action="store_true", help="don't delete scratch dirs (debugging)")
    ap.add_argument("--cases", type=Path, default=CASES_PATH)
    ap.add_argument("--dataset-name", default=DATASET_NAME)
    ap.add_argument("--results-dir", type=Path)
    ap.add_argument("--jar", type=Path, default=Path(__file__).resolve().parent.parent / "target/regnexe-cli.jar")
    args = ap.parse_args()
    if not os.environ.get("DEEPSEEK_KEY", "").strip():
        ap.error("Export DEEPSEEK_KEY before running an actual model evaluation")
    configure_local_opik()
    if not args.jar.is_file():
        ap.error("Build the local JAR first with mvn verify, or specify --jar")
    if args.cases != CASES_PATH and args.dataset_name == DATASET_NAME:
        ap.error("Custom cases need a separate --dataset-name to preserve the baseline dataset")

    all_cases = load_cases(args.cases)
    if any(case.get("mode", "agent") != "agent" for case in all_cases):
        ap.error("runner.py executes agent cases only; deterministic and interactive suites are separate")
    cases = all_cases
    if args.case_id:
        cases = [c for c in cases if c["id"] == args.case_id]
    elif args.only:
        wanted = set(args.only.split(","))
        cases = [c for c in cases if c["category"] in wanted]
    if not cases:
        print("No matching cases.", file=sys.stderr)
        sys.exit(1)

    cases_by_id = {c["id"]: c for c in all_cases}
    client = opik.Opik(project_name=PROJECT_NAME)
    ds = build_dataset(client, all_cases, args.dataset_name)

    item_ids = None
    if args.only or args.case_id:
        id_map = case_id_to_dataset_item_id(ds)
        item_ids = [id_map[c["id"]] for c in cases]

    results_dir = args.results_dir or Path(__file__).resolve().parent / "results" / args.tag
    results_dir.mkdir(parents=True, exist_ok=True)
    manifest = {
        "tag": args.tag, "dataset": args.dataset_name,
        "git_revision": subprocess.check_output(["git", "rev-parse", "HEAD"], text=True).strip(),
        "jar_sha256": hashlib.sha256(args.jar.read_bytes()).hexdigest(),
        "model": "deepseek-flash", "case_ids": [case["id"] for case in cases],
    }
    (results_dir / "manifest.json").write_text(json.dumps(manifest, indent=2))
    task = make_task(cases_by_id, args.tag, client, args.keep_workdirs, args.jar, results_dir)
    result = evaluate(
        dataset=ds,
        task=task,
        scoring_metrics=[CaseScore()],
        experiment_name=f"regnexe-cli-{args.tag}",
        experiment_config=manifest,
        task_threads=1,  # Keep the initial model baseline sequential for predictable load and observation.
        dataset_item_ids=item_ids,
        verbose=1,
    )

    by_category = collections.defaultdict(list)
    overall = []
    failures = []
    for item in result.test_results:
        out = item.test_case.task_output
        cat = out.get("category", "unknown")
        s = out.get("case_score")
        if s is not None:
            by_category[cat].append(s)
            overall.append(s)
            if s < 1.0:
                failures.append((out.get("case_id", item.test_case.dataset_item_id), s, out.get("case_reason", "")))

    print("\n=== regnexe-cli eval:", args.tag, "===")
    for cat in sorted(by_category):
        scores = by_category[cat]
        print(f"  {cat:24s} {sum(scores)/len(scores):.2f}  ({len(scores)} cases)")
    if overall:
        print(f"  {'OVERALL':24s} {sum(overall)/len(overall):.2f}  ({len(overall)} cases)")
    if failures:
        print("\n--- cases below 1.0 ---")
        for case_id, s, reason in failures:
            print(f"  [{s:.2f}] {case_id}: {reason}")
    (results_dir / "experiment.json").write_text(json.dumps(
        {"url": result.experiment_url, "scores": dict(by_category)}, indent=2))
    print(f"\nExperiment URL: {result.experiment_url}", flush=True)


if __name__ == "__main__":
    main()
