#!/usr/bin/env python3
"""Compare submitted turns with saved task goals; optionally append a metric to an existing run."""
import argparse
import json
from pathlib import Path
import sqlite3
from urllib.parse import parse_qs, urlsplit

import opik

from prepare_hardening import load_suite
from runner import configure_local_opik


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--results-dir", type=Path, required=True)
    parser.add_argument("--update-opik", action="store_true")
    args = parser.parse_args()
    reviewed = {}
    for case in load_suite("agent"):
        result = json.loads((args.results_dir / (case["id"] + ".json")).read_text())
        database = Path(result["sandbox"]) / "home/.rex/rex.db"
        with sqlite3.connect(database) as db:
            states = [json.loads(row[0]) for row in db.execute(
                "SELECT data FROM task_execution_states ORDER BY updated_at")]
        actual = [state["request"]["goal"] for state in states]
        exact = actual == case["turns"]
        reviewed[case["id"]] = {
            "score": 1.0 if exact else 0.0,
            "reason": "Submitted turns equal saved goals" if exact else "Input changed between submitted turns and task ledger",
            "submitted": case["turns"], "saved_goals": actual,
        }
    report = {"passed": sum(row["score"] == 1 for row in reviewed.values()),
              "total": len(reviewed), "results": reviewed}
    (args.results_dir / "input-fidelity.json").write_text(json.dumps(report, ensure_ascii=False, indent=2))
    print("Task input fidelity:", report["passed"], "/", report["total"], flush=True)
    for case_id, row in reviewed.items():
        if row["score"] == 0:
            print(case_id + ": " + row["reason"], flush=True)
    if args.update_opik:
        configure_local_opik()
        manifest = json.loads((args.results_dir / "manifest.json").read_text())
        experiment = json.loads((args.results_dir / "experiment.json").read_text())
        experiment_id = json.loads(parse_qs(urlsplit(experiment["url"]).query)["experiments"][0])[0]
        client = opik.Opik(project_name="regnexe-cli")
        remote = client.get_experiment_by_id(id=experiment_id)
        items = remote.get_items(num_threads=1)
        info = client.rest_client.experiments.get_experiment_by_id(id=experiment_id)
        print("Read-back:", len(items), "items; backend trace_count=", info.trace_count,
              "feedback_scores=", info.feedback_scores, flush=True)
        if len(items) != len(reviewed):
            raise RuntimeError("Existing experiment item read-back is incomplete")
        dataset = client.get_dataset(manifest["dataset"])
        by_id = {row["id"]: row["case_id"] for row in dataset.get_items()}
        scores = []
        for item in items:
            case_id = by_id[item.dataset_item_id]
            row = reviewed[case_id]
            scores.append({"id": item.trace_id, "name": "task_input_fidelity",
                           "value": row["score"], "reason": row["reason"]})
        client.log_traces_feedback_scores(scores=scores, project_name="regnexe-cli")
        if not client.flush(timeout=30):
            raise RuntimeError("Supplemental feedback did not flush")
        print("Supplemental fidelity metric appended; original case_score and model outputs preserved.", flush=True)


if __name__ == "__main__":
    main()
