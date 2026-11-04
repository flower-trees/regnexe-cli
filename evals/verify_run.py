#!/usr/bin/env python3
"""Read back actual experiments and optionally fill absent trace output from local run artifacts."""
import argparse
import json
from pathlib import Path
from urllib.parse import parse_qs, urlsplit

import opik

from prepare_hardening import SUITES
from runner import configure_local_opik


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--root", type=Path, default=Path("evals/results"))
    parser.add_argument("--repair-missing-output", action="store_true")
    args = parser.parse_args()
    configure_local_opik()
    client = opik.Opik(project_name="regnexe-cli", batching=False)
    root = args.root
    inputs = {
        "regression": json.loads((root / "full-20261104/regression-results.json").read_text()),
        "interactive": json.loads((root / "full-20261104/interactive-reviewed.json").read_text()),
        "agent": json.loads((root / "deepseek-flash-full-20261104/experiment.json").read_text()),
    }
    summaries = {}
    for kind, source in inputs.items():
        if kind == "agent":
            url = source["url"]
            rows = {}
            for path in (root / "deepseek-flash-full-20261104").glob("*.json"):
                row = json.loads(path.read_text())
                if "case_id" in row:
                    rows[row["case_id"]] = row
        else:
            url = source["experiment_url"]
            rows = source["results"]
        experiment_id = json.loads(parse_qs(urlsplit(url).query)["experiments"][0])[0]
        experiment = client.get_experiment_by_id(id=experiment_id)
        items = experiment.get_items(num_threads=1)
        dataset = client.get_dataset(SUITES[kind][1])
        by_id = {row["id"]: row["case_id"] for row in dataset.get_items()}
        if len(items) != len(rows):
            raise RuntimeError(f"{kind}: expected {len(rows)} items, got {len(items)}")
        repaired = 0
        before_missing = 0
        for item in items:
            case_id = by_id[item.dataset_item_id]
            trace = client.get_trace_content(id=item.trace_id)
            if trace.output is None:
                before_missing += 1
                if args.repair_missing_output:
                    # Only fill missing output from recorded evidence. Use a synchronous
                    # PATCH with the real project ID to avoid enqueueing another async update.
                    client.rest_client.traces.update_trace(id=item.trace_id, project_id=trace.project_id,
                                                          output=rows[case_id])
                    updated = client.get_trace_content(id=item.trace_id)
                    if not updated.output:
                        raise RuntimeError("Actual trace output patch did not persist")
                    repaired += 1
        if repaired and not client.flush(timeout=30):
            raise RuntimeError("Trace output repair did not flush")
        items = experiment.get_items(num_threads=1)
        saved = []
        for item in items:
            feedback = {score["name"]: score["value"] for score in item.feedback_scores}
            if feedback.get("case_score") != rows[by_id[item.dataset_item_id]]["case_score"]:
                raise RuntimeError("Remote case score differs from actual recorded result")
            if kind == "agent":
                fidelity = json.loads((root / "deepseek-flash-full-20261104/input-fidelity.json").read_text())
                expected = fidelity["results"][by_id[item.dataset_item_id]]["score"]
                if feedback.get("task_input_fidelity") != expected:
                    raise RuntimeError("Supplemental fidelity score is missing or incorrect")
            saved.append({"case_id": by_id[item.dataset_item_id], "trace_id": item.trace_id,
                          "feedback_scores": feedback, "output_available": item.evaluation_task_output is not None})
        if not all(row["output_available"] for row in saved):
            raise RuntimeError("Experiment compare view still lacks actual output")
        summaries[kind] = {"experiment_url": url, "items": saved,
                           "missing_trace_output_before": before_missing, "repaired_outputs": repaired}
        print(kind, len(saved), "items verified; missing output before=", before_missing,
              "; repaired=", repaired, "; compare outputs=", sum(row["output_available"] for row in saved), flush=True)
    (root / "full-20261104/opik-readback.json").write_text(json.dumps(summaries, ensure_ascii=False, indent=2))


if __name__ == "__main__":
    main()
