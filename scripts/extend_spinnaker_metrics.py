#!/usr/bin/env python3
import argparse
import csv
import datetime as dt
import itertools
import json
import pathlib
import re
import subprocess
from collections import defaultdict
from typing import Any, Dict, List, Tuple

import pandas as pd

try:
    from openpyxl import load_workbook
except ImportError:  # pragma: no cover
    load_workbook = None


def run(cmd: List[str], cwd: pathlib.Path) -> str:
    return subprocess.check_output(cmd, cwd=str(cwd), text=True)


def iso_week_label(date_str: str) -> str:
    year, week, _ = dt.date.fromisoformat(date_str).isocalendar()
    return f"{year}-W{week:02d}"


def git_commits(repo: pathlib.Path, start_date: str, end_date: str) -> List[Dict[str, Any]]:
    log = run(
        [
            "git",
            "log",
            "--reverse",
            "--pretty=format:%H|%cI",
            f"--since={start_date}T00:00:00Z",
            f"--until={end_date}T23:59:59Z",
        ],
        repo,
    )
    commits: List[Dict[str, Any]] = []
    for line in log.splitlines():
        commit, iso = line.split("|", 1)
        timestamp = dt.datetime.fromisoformat(iso.replace("Z", "+00:00"))
        date_str = timestamp.date().isoformat()
        commits.append(
            {
                "commit": commit,
                "date": date_str,
                "datetime": timestamp,
                "week": iso_week_label(date_str),
            }
        )
    return commits


def parse_ir_snapshots(ir_dir: pathlib.Path, commits: List[Dict[str, Any]]) -> List[Dict[str, Any]]:
    snapshots: List[Dict[str, Any]] = []
    commit_map = {c["commit"]: c for c in commits}

    for path in sorted(ir_dir.glob("IR*_*.json")):
        match = re.match(r"IR(\d+)_([0-9a-fA-F]+)\.json", path.name)
        if not match:
            continue

        idx = int(match.group(1))
        short = match.group(2).lower()
        commit = next((full for full in commit_map if full.startswith(short)), None)
        if not commit:
            continue

        with path.open("r", encoding="utf-8") as fh:
            data = json.load(fh)

        snapshots.append(
            {
                "index": idx,
                "path": path,
                "short": short,
                "commit": commit,
                "date": commit_map[commit]["date"],
                "datetime": commit_map[commit]["datetime"],
                "week": commit_map[commit]["week"],
                "ir": data,
            }
        )

    snapshots.sort(key=lambda s: (s["index"], s["datetime"]))
    return snapshots


def extract_service_paths(snapshot: Dict[str, Any]) -> List[Tuple[str, str]]:
    rules = []
    for ms in snapshot["ir"].get("microservices", []) or []:
        name = ms.get("name")
        path = (ms.get("path") or "").strip("/")
        if name and path:
            rules.append((name, path))
    rules.sort(key=lambda item: len(item[1]), reverse=True)
    return rules


def map_file_to_service(file_path: str, rules: List[Tuple[str, str]]) -> str:
    normalized = file_path.strip("/")
    for service, prefix in rules:
        if normalized == prefix or normalized.startswith(prefix + "/"):
            return service
    if "/" in normalized:
        return normalized.split("/", 1)[0]
    return "ROOT"


def parse_numstat(repo: pathlib.Path, commit: str) -> List[Dict[str, Any]]:
    out = run(["git", "show", "--numstat", "--format=", commit], repo)
    rows = []
    for line in out.splitlines():
        parts = line.split("\t")
        if len(parts) != 3:
            continue
        add, delete, file_path = parts
        add_n = 0 if add == "-" else int(add)
        del_n = 0 if delete == "-" else int(delete)
        rows.append(
            {
                "file": file_path,
                "loc_added": add_n,
                "loc_deleted": del_n,
                "loc_delta": add_n - del_n,
            }
        )
    return rows


def list_service_classes(ms: Dict[str, Any]) -> List[Dict[str, Any]]:
    classes = []
    for key in ["controllers", "services", "repositories", "entities", "feignClients"]:
        classes.extend(ms.get(key, []) or [])
    return classes


def operation_from_method(service_name: str, method: Dict[str, Any]) -> Dict[str, Any]:
    params = [p.get("type") for p in (method.get("parameters") or []) if p.get("type")]
    anns = [f"{a.get('name')} - {a.get('contents')}" for a in (method.get("annotations") or []) if a.get("name")]
    return {
        "name": f"{service_name}::{method.get('name', '')}",
        "responseType": method.get("returnType") or "",
        "params": params,
        "usingTypes": anns,
    }


def lomlc(operations: List[Dict[str, Any]]) -> float:
    if len(operations) <= 1:
        return 1.0
    pairs = 0
    acc = 0.0
    for i in range(len(operations)):
        for j in range(i + 1, len(operations)):
            op1, op2 = operations[i], operations[j]
            p1, p2 = set(op1["params"]), set(op2["params"])
            union_size = len(p1 | p2)
            ids = 1.0 if union_size == 0 else len(p1 & p2) / union_size
            ods = 1.0 if op1["responseType"] == op2["responseType"] else 0.0
            acc += 1 - ((ids + ods) / 2)
            pairs += 1
    return acc / pairs if pairs else 1.0


def compute_service_weekly_metrics(snapshots: List[Dict[str, Any]]) -> List[Dict[str, Any]]:
    by_week: Dict[str, Dict[str, Any]] = {}
    for snapshot in snapshots:
        by_week[snapshot["week"]] = snapshot

    rows = []
    for week in sorted(by_week):
        snapshot = by_week[week]
        for ms in snapshot["ir"].get("microservices", []) or []:
            service_name = ms.get("name")
            if not service_name:
                continue

            classes = list_service_classes(ms)
            methods = [m for c in classes for m in (c.get("methods") or [])]
            controllers = ms.get("controllers", []) or []
            operations = [operation_from_method(service_name, m) for c in controllers for m in (c.get("methods") or [])]
            dep_targets = {
                mc.get("objectType")
                for c in classes
                for mc in (c.get("methodCalls") or [])
                if mc.get("objectType")
            }

            rows.append(
                {
                    "week": week,
                    "commit": snapshot["commit"],
                    "service": service_name,
                    "LOMLC": round(lomlc(operations), 6),
                    "internal_dependency_count": len(dep_targets),
                    "function_count": len(methods),
                }
            )

    return rows


def compute_service_pair_coupling(trace_rows: List[Dict[str, Any]]) -> List[Dict[str, Any]]:
    trace_df = pd.DataFrame(trace_rows)
    if trace_df.empty:
        print("[debug] no trace rows available, coupling output will be empty")
        return []

    trace_df = trace_df.copy()
    trace_df["week"] = trace_df["date"].map(iso_week_label)
    trace_df = trace_df[trace_df["service"].notna() & (trace_df["service"] != "")]

    commit_services = (
        trace_df[["week", "commit", "service"]]
        .drop_duplicates()
        .groupby(["week", "commit"], as_index=False)["service"]
        .agg(lambda s: sorted(set(s)))
    )

    print(f"[debug] coupling commit groups: {len(commit_services)}")

    service_commit_counts: Dict[Tuple[str, str], int] = defaultdict(int)
    pair_commit_counts: Dict[Tuple[str, str, str], int] = defaultdict(int)

    for row in commit_services.itertuples(index=False):
        week = row.week
        services = list(row.service)
        for service in services:
            service_commit_counts[(week, service)] += 1

        unique_services = sorted(set(services))
        if len(unique_services) < 2:
            continue
        for s1, s2 in itertools.combinations(unique_services, 2):
            pair_commit_counts[(week, s1, s2)] += 1

    print(f"[debug] raw service-pair counts: {len(pair_commit_counts)}")

    rows = []
    previous_week_ssic: Dict[Tuple[str, str], float] = {}

    for week in sorted({k[0] for k in pair_commit_counts.keys()}):
        week_pairs = sorted([k for k in pair_commit_counts.keys() if k[0] == week])
        for _, s1, s2 in week_pairs:
            cochange = pair_commit_counts[(week, s1, s2)]
            s1_count = service_commit_counts[(week, s1)]
            s2_count = service_commit_counts[(week, s2)]
            union = s1_count + s2_count - cochange
            ssic = (cochange / union) if union > 0 else 0.0

            prev = previous_week_ssic.get((s1, s2), 0.0)
            delta = ssic - prev

            rows.append(
                {
                    "week": week,
                    "service_1": s1,
                    "service_2": s2,
                    "ssic": round(ssic, 6),
                    "ssic_delta": round(delta, 6),
                    "cochange_commit_count": int(cochange),
                }
            )
            previous_week_ssic[(s1, s2)] = ssic

    print(f"[debug] final coupling rows: {len(rows)}")
    return rows


def write_csv(path: pathlib.Path, rows: List[Dict[str, Any]], headers: List[str]) -> None:
    path.parent.mkdir(parents=True, exist_ok=True)
    with path.open("w", encoding="utf-8", newline="") as fh:
        writer = csv.DictWriter(fh, fieldnames=headers)
        writer.writeheader()
        for row in rows:
            writer.writerow({k: row.get(k, "") for k in headers})


def enrich_workbook(workbook_path: pathlib.Path, trace_rows, service_rows, pair_rows) -> None:
    if load_workbook is None:
        raise RuntimeError("openpyxl is required to enrich workbook.")

    wb = load_workbook(workbook_path)

    definitions = [
        (
            "Commit-Service-Trace",
            ["commit", "date", "file", "service", "loc_added", "loc_deleted", "loc_delta"],
            trace_rows,
        ),
        (
            "Service-Weekly-Metrics",
            ["week", "commit", "service", "LOMLC", "internal_dependency_count", "function_count"],
            service_rows,
        ),
        (
            "Service-Pair-Coupling",
            ["week", "service_1", "service_2", "ssic", "ssic_delta", "cochange_commit_count"],
            pair_rows,
        ),
    ]

    for sheet_name, headers, rows in definitions:
        if sheet_name in wb.sheetnames:
            del wb[sheet_name]
        ws = wb.create_sheet(title=sheet_name)
        ws.append(headers)
        for row in rows:
            ws.append([row.get(h, "") for h in headers])

    wb.save(workbook_path)


def main() -> None:
    parser = argparse.ArgumentParser(description="Extend Spinnaker metrics output with service trace/coupling tables.")
    parser.add_argument("--repo", required=True, type=pathlib.Path)
    parser.add_argument("--ir-dir", required=True, type=pathlib.Path)
    parser.add_argument("--workbook", required=True, type=pathlib.Path)
    parser.add_argument("--output-dir", required=True, type=pathlib.Path)
    parser.add_argument("--start-date", required=True)
    parser.add_argument("--end-date", required=True)
    args = parser.parse_args()

    commits = git_commits(args.repo, args.start_date, args.end_date)
    if not commits:
        raise SystemExit("No commits found for the requested date window.")

    snapshots = parse_ir_snapshots(args.ir_dir, commits)
    if not snapshots:
        raise SystemExit("No IR snapshots found to build weekly service metrics.")

    snapshot_rules = sorted([(snap["datetime"], extract_service_paths(snap)) for snap in snapshots], key=lambda t: t[0])

    trace_rows = []
    for commit in sorted(commits, key=lambda c: c["datetime"]):
        rules = snapshot_rules[0][1]
        for snap_time, snap_rules in snapshot_rules:
            if snap_time <= commit["datetime"]:
                rules = snap_rules
            else:
                break

        for numstat in parse_numstat(args.repo, commit["commit"]):
            trace_rows.append(
                {
                    "commit": commit["commit"],
                    "date": commit["date"],
                    "week": commit["week"],
                    "file": numstat["file"],
                    "service": map_file_to_service(numstat["file"], rules),
                    "loc_added": numstat["loc_added"],
                    "loc_deleted": numstat["loc_deleted"],
                    "loc_delta": numstat["loc_delta"],
                }
            )

    print(f"[debug] commits: {len(commits)}")
    print(f"[debug] snapshots: {len(snapshots)}")
    print(f"[debug] trace rows: {len(trace_rows)}")

    service_rows = compute_service_weekly_metrics(snapshots)
    pair_rows = compute_service_pair_coupling(trace_rows)

    out = args.output_dir
    write_csv(
        out / "commit_service_trace.csv",
        trace_rows,
        ["commit", "date", "week", "file", "service", "loc_added", "loc_deleted", "loc_delta"],
    )
    write_csv(
        out / "service_weekly_metrics.csv",
        service_rows,
        ["week", "commit", "service", "LOMLC", "internal_dependency_count", "function_count"],
    )

    coupling_headers = ["week", "service_1", "service_2", "ssic", "ssic_delta", "cochange_commit_count"]
    if pair_rows:
        write_csv(out / "service_pair_coupling.csv", pair_rows, coupling_headers)
    else:
        print("[debug] no coupling rows found; preserving any existing service_pair_coupling.csv")
        existing = out / "service_pair_coupling.csv"
        if not existing.exists():
            write_csv(existing, [], coupling_headers)

    summary = {
        "commits": len(commits),
        "trace_rows": len(trace_rows),
        "weeks": len({row["week"] for row in service_rows}),
        "service_rows": len(service_rows),
        "pair_rows": len(pair_rows),
    }
    with (out / "extended_metrics_summary.json").open("w", encoding="utf-8") as fh:
        json.dump(summary, fh, indent=2)

    enrich_workbook(args.workbook, trace_rows, service_rows, pair_rows)


if __name__ == "__main__":
    main()
