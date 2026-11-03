#!/usr/bin/env python3
"""regnexe-cli eval runner: feeds dataset/cases.yaml into `rex`, scores each run, logs the
whole thing as an Opik Experiment (dataset + per-item traces + scores), so iterations can be
compared in the Opik UI.

Usage:
    source .venv/bin/activate
    export LITELLM_MASTER_KEY=...          # same key rex/llm-router use
    python runner.py --tag baseline-v1
    python runner.py --tag baseline-v1 --only tool_use,file_edit   # filter by category
    python runner.py --tag baseline-v1 --case-id edit-001-create-file  # single case

Prerequisites: llm-router running on :4000, local Opik running on :28173, `rex` on PATH.
"""
from __future__ import annotations

import argparse
import collections
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


class CaseScore(base_metric.BaseMetric):
    """Reads the score the task already computed (each case has its own rule/rubric, so
    scoring happens inside the task, not as a uniform formula here) and surfaces it as a
    normal Opik feedback score."""

    def __init__(self):
        super().__init__(name="case_score")

    def score(self, case_score: float, case_reason: str = "", **ignored) -> score_result.ScoreResult:
        return score_result.ScoreResult(value=case_score, name=self.name, reason=case_reason)


def load_cases() -> list[dict]:
    with open(CASES_PATH) as f:
        return yaml.safe_load(f)


def build_dataset(client: opik.Opik, cases: list[dict]):
    # Opik dataset items require a UUID "id" (or none, auto-assigned) — our own readable
    # case ids go in "case_id" instead. Content-based deduplication (insert's default) keeps
    # repeated runs from piling up duplicate items as long as a case's turns/category don't change.
    ds = client.get_or_create_dataset(name=DATASET_NAME, description="regnexe-cli capability eval set")
    ds.insert([{"case_id": c["id"], "category": c["category"], "turns": c["turns"]} for c in cases])
    return ds


def case_id_to_dataset_item_id(ds) -> dict[str, str]:
    mapping = {}
    for item in ds.get_items():
        cid = item.get("case_id")
        if cid:
            mapping[cid] = item["id"]
    return mapping


def make_task(cases_by_id: dict, run_tag: str, opik_client: opik.Opik, keep_workdirs: bool):
    def task(dataset_item: dict) -> dict:
        case = cases_by_id[dataset_item["case_id"]]
        workdir = Path(tempfile.mkdtemp(prefix=f"regnexe-eval-{case['id']}-"))
        try:
            rex_client.write_fixtures(workdir, case.get("fixture_files"))
            try:
                result = rex_client.run_case(
                    case["id"], case["turns"], workdir, run_tag,
                    opik_client=opik_client, project_name=PROJECT_NAME,
                )
            except Exception as e:
                # A raw exception here (rex hangs past RUN_TIMEOUT_S, an Opik API hiccup) would
                # otherwise escape task() and abort the *entire* evaluate() run, losing every
                # other case's results — score it as a hard 0 instead and keep going.
                return {
                    "case_score": 0.0,
                    "case_reason": f"harness error: {e}",
                    "answer": "",
                    "trace_id": None,
                    "elapsed_s": None,
                    "category": case["category"],
                    "case_id": case["id"],
                }
            case_score, case_reason = scorer.score_case(case, workdir, result.answer_text)
            return {
                "case_score": case_score,
                "case_reason": case_reason,
                "answer": result.answer_text,
                "trace_id": result.trace_id,
                "elapsed_s": round(result.elapsed_s, 1),
                "category": case["category"],
                "case_id": case["id"],
            }
        finally:
            if not keep_workdirs:
                shutil.rmtree(workdir, ignore_errors=True)
    return task


def main():
    ap = argparse.ArgumentParser()
    ap.add_argument("--tag", required=True, help="short label for this run, e.g. baseline-v1")
    ap.add_argument("--only", help="comma-separated category filter")
    ap.add_argument("--case-id", help="run a single case by id")
    ap.add_argument("--keep-workdirs", action="store_true", help="don't delete scratch dirs (debugging)")
    args = ap.parse_args()

    all_cases = load_cases()
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
    ds = build_dataset(client, all_cases)

    item_ids = None
    if args.only or args.case_id:
        id_map = case_id_to_dataset_item_id(ds)
        item_ids = [id_map[c["id"]] for c in cases]

    task = make_task(cases_by_id, args.tag, client, args.keep_workdirs)
    result = evaluate(
        dataset=ds,
        task=task,
        scoring_metrics=[CaseScore()],
        experiment_name=f"regnexe-cli-{args.tag}",
        task_threads=1,  # sequential: shared llm-router + rex's local session db, not safe to parallelize yet
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
    print(f"\nExperiment URL: {result.experiment_url}")


if __name__ == "__main__":
    main()
