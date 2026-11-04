#!/usr/bin/env python3
"""Publish actual JUnit or PTY results against prepared datasets; never invent executions."""
import argparse
import json
import sqlite3
import re
from pathlib import Path
import xml.etree.ElementTree as ET

import opik
from opik.evaluation import evaluate

from prepare_hardening import SUITES, load_suite
from runner import CaseScore, configure_local_opik


def enrich_interactive(client, results):
    for row in results.values():
        segments = []
        for path in Path(row["sandbox"]).glob("*.pty.txt"):
            for match in re.finditer(r"Tokens: (\d+) in -> (\d+) out(?: · cache hit (\d+))? · tools (\d+)", path.read_text()):
                segments.append({"prompt_tokens": int(match[1]), "completion_tokens": int(match[2]),
                                 "cached_tokens": int(match[3] or 0), "tool_calls": int(match[4])})
        row["terminal_token_summaries"] = segments
        database = Path(row["sandbox"]) / "home/.rex/rex.db"
        if not database.is_file():
            continue
        with sqlite3.connect(database) as db:
            tasks = db.execute("SELECT task_id, status FROM task_execution_states ORDER BY updated_at").fetchall()
        row["task_states"] = [{"task_id": task_id, "status": status} for task_id, status in tasks]
        row["traces"] = []
        for task_id, _ in tasks:
            traces = client.search_traces(project_name="regnexe-cli",
                    filter_string=f'metadata.task_id = "{task_id}"', truncate=False,
                    wait_for_at_least=1, wait_for_timeout=5)
            for trace in traces:
                summary = (trace.metadata or {}).get("token_summary")
                if isinstance(summary, str):
                    try:
                        summary = json.loads(summary)
                    except ValueError:
                        pass
                row["traces"].append({"trace_id": trace.id, "task_id": task_id,
                                      "token_summary": summary, "output": trace.output})


def read_junit(directory: Path, cases: list[dict]) -> dict:
    executed = {}
    for report in directory.glob("TEST-*.xml"):
        suite = ET.parse(report).getroot()
        for test in suite.findall("testcase"):
            class_name = test.get("classname", suite.get("name", "")).rsplit(".", 1)[-1]
            case_id = class_name + "." + test.get("name", "")
            failed = test.find("failure")
            error = test.find("error")
            skipped = test.find("skipped")
            problem = failed if failed is not None else error
            status = "skipped" if skipped is not None else "failed" if problem is not None else "passed"
            executed[case_id] = {
                "case_id": case_id, "case_score": 1.0 if status == "passed" else 0.0,
                "case_reason": status if problem is None else problem.get("message", status),
                "status": status, "elapsed_s": float(test.get("time", 0)),
                "execution_mode": "actual_junit_report", "report_file": str(report),
                "report_mtime": report.stat().st_mtime,
            }
    missing = [case["id"] for case in cases if case["id"] not in executed]
    if missing:
        raise ValueError("JUnit reports do not contain all prepared tests: " + str(missing))
    return {case["id"]: executed[case["id"]] for case in cases}


def publish(kind: str, results: dict, tag: str, output_dir: Path):
    configure_local_opik()
    cases = load_suite(kind)
    expected = {case["id"] for case in cases}
    if set(results) != expected:
        raise ValueError("Result IDs must exactly match the prepared dataset")
    client = opik.Opik(project_name="regnexe-cli")
    if kind == "interactive":
        enrich_interactive(client, results)
    dataset = client.get_dataset(SUITES[kind][1])
    result = evaluate(
        dataset=dataset, task=lambda item: results[item["case_id"]],
        scoring_metrics=[CaseScore()], task_threads=1,
        experiment_name=f"regnexe-cli-{tag}-{kind}",
        experiment_config={"suite": kind, "tag": tag, "execution": "recorded_actual_results"},
        verbose=1,
    )
    output_dir.mkdir(parents=True, exist_ok=True)
    summary = {"experiment_url": result.experiment_url, "results": results,
               "passed": sum(row["case_score"] == 1 for row in results.values()), "total": len(results)}
    (output_dir / (kind + "-results.json")).write_text(json.dumps(summary, ensure_ascii=False, indent=2))
    print(json.dumps({key: value for key, value in summary.items() if key != "results"}, ensure_ascii=False), flush=True)
    return summary


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--suite", choices=["regression", "interactive"], required=True)
    parser.add_argument("--tag", required=True)
    parser.add_argument("--junit-dir", type=Path, default=Path("target/surefire-reports"))
    parser.add_argument("--results-file", type=Path)
    parser.add_argument("--output-dir", type=Path, required=True)
    args = parser.parse_args()
    if args.suite == "regression":
        results = read_junit(args.junit_dir, load_suite("regression"))
    else:
        if not args.results_file:
            parser.error("Interactive results must come from an actual PTY run")
        results = json.loads(args.results_file.read_text())
    publish(args.suite, results, args.tag, args.output_dir)


if __name__ == "__main__":
    main()
