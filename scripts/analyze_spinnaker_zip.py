#!/usr/bin/env python3
"""Extract Spring endpoint inventory from a Spinnaker source zip.

The script expands a repository archive, discovers probable service modules,
and performs a lightweight scan for Spring MVC mapping annotations inside
Java, Groovy, and Kotlin sources. Detected endpoints are written as CSV rows
to standard output. This utility is intentionally self-contained so that it
can be executed in constrained CI environments without invoking Maven.
"""

from __future__ import annotations

import argparse
import csv
import re
import sys
import tempfile
import zipfile
from pathlib import Path
from typing import Iterable, List, Optional, Sequence, Tuple


MAPPING_ANNOTATIONS = {
    "RequestMapping": None,
    "GetMapping": "GET",
    "PostMapping": "POST",
    "PutMapping": "PUT",
    "DeleteMapping": "DELETE",
    "PatchMapping": "PATCH",
}


def parse_args(argv: Optional[Sequence[str]] = None) -> argparse.Namespace:
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("zip_path", type=Path, help="Path to the Spinnaker zip archive")
    parser.add_argument(
        "--include-tests",
        action="store_true",
        help="Include src/test sources when scanning for endpoints",
    )
    return parser.parse_args(argv)


def extract_zip(archive: Path) -> Path:
    if not archive.exists():
        raise FileNotFoundError(f"Archive not found: {archive}")

    tmpdir = tempfile.TemporaryDirectory(prefix="spinnaker-src-")
    with zipfile.ZipFile(archive) as zf:
        zf.extractall(tmpdir.name)
    return Path(tmpdir.name)


def discover_modules(root: Path) -> List[Path]:
    modules: List[Path] = []
    for candidate in root.iterdir():
        if not candidate.is_dir():
            continue
        if (candidate / "pom.xml").exists() or (candidate / "build.gradle").exists() or (
            candidate / "build.gradle.kts"
        ).exists():
            modules.append(candidate)
            continue
        nested = discover_modules(candidate)
        modules.extend(nested)
    return modules


def source_roots(module: Path, include_tests: bool) -> Iterable[Path]:
    main_roots = [
        module / "src" / "main" / "java",
        module / "src" / "main" / "groovy",
        module / "src" / "main" / "kotlin",
    ]
    test_roots = []
    if include_tests:
        test_roots = [
            module / "src" / "test" / "java",
            module / "src" / "test" / "groovy",
            module / "src" / "test" / "kotlin",
        ]

    for path in main_roots + test_roots:
        if path.exists():
            yield path


def normalize_path(base: str, path: str) -> str:
    combined = "/".join(filter(None, [base.strip("/"), path.strip("/")]))
    return f"/{combined}" if combined else "/"


def extract_paths(annotation: str) -> List[str]:
    matches = re.findall(r'"([^"\\]*(?:\\.[^"\\]*)*)"', annotation)
    if matches:
        return [m.replace("\\/", "/") for m in matches]
    if "path" in annotation or "value" in annotation:
        return [""]
    return [""]


def extract_methods(annotation: str, explicit: Optional[str]) -> List[str]:
    if explicit:
        return [explicit]
    methods = re.findall(r"RequestMethod\.([A-Z]+)", annotation)
    if methods:
        return methods
    return ["ALL"]


def parse_annotations(block: Sequence[str]) -> List[Tuple[str, str]]:
    parsed: List[Tuple[str, str]] = []
    for line in block:
        match = re.match(r"\s*@([A-Za-z0-9_.]+)(.*)", line)
        if not match:
            continue
        name = match.group(1).split(".")[-1]
        rest = match.group(2)
        parsed.append((name, rest))
    return parsed


def scan_file(path: Path) -> List[Tuple[str, str]]:
    endpoints: List[Tuple[str, str]] = []
    try:
        content = path.read_text(encoding="utf-8", errors="ignore")
    except OSError:
        return endpoints

    lines = content.splitlines()
    pending_annotations: List[str] = []
    class_paths: List[str] = [""]

    for line in lines:
        stripped = line.strip()
        if stripped.startswith("@"):
            pending_annotations.append(stripped)
            continue

        if re.match(r"(public\s+)?(class|interface|enum|object)\b", stripped):
            class_paths = [""]
            for name, args in parse_annotations(pending_annotations):
                if name not in MAPPING_ANNOTATIONS:
                    continue
                methods = extract_methods(args, MAPPING_ANNOTATIONS[name])
                if methods != ["ALL"]:
                    # Ignore HTTP verbs on classes; method-level takes precedence
                    pass
                class_paths = extract_paths(args)
            pending_annotations = []
            continue

        if re.match(r".*\b(fun|def|public|private|protected).*\(.*\).*", stripped):
            annotations = parse_annotations(pending_annotations)
            pending_annotations = []
            if not annotations:
                continue
            method_paths = [""]
            methods: List[str] = []
            matched = False
            for name, args in annotations:
                if name not in MAPPING_ANNOTATIONS:
                    continue
                matched = True
                method_paths = extract_paths(args)
                methods = extract_methods(args, MAPPING_ANNOTATIONS[name])
                if methods:
                    break
            if not matched:
                continue
            if not methods:
                methods = ["ALL"]
            for base in class_paths:
                for path_fragment in method_paths:
                    url = normalize_path(base, path_fragment)
                    for verb in methods:
                        endpoints.append((verb, url))
    return endpoints


def collect_endpoints(root: Path, include_tests: bool) -> List[Tuple[str, str, str]]:
    inventory: List[Tuple[str, str, str]] = []
    for module in discover_modules(root):
        service_name = str(module.relative_to(root))
        seen = set()
        for src_root in source_roots(module, include_tests):
            for file_path in src_root.rglob("*"):
                if file_path.suffix.lower() not in {".java", ".kt", ".kts", ".groovy"}:
                    continue
                for method, url in scan_file(file_path):
                    key = (method, url, str(file_path.relative_to(module)))
                    if key in seen:
                        continue
                    seen.add(key)
                    inventory.append((service_name, method, url))
    return inventory


def write_csv(rows: Iterable[Tuple[str, str, str]]) -> None:
    writer = csv.writer(sys.stdout)
    writer.writerow(["service", "http_method", "url"])
    for row in rows:
        writer.writerow(row)


def main(argv: Optional[Sequence[str]] = None) -> int:
    args = parse_args(argv)
    try:
        extracted_root = extract_zip(args.zip_path)
    except FileNotFoundError as exc:
        print(exc, file=sys.stderr)
        return 1

    endpoints = collect_endpoints(extracted_root, include_tests=args.include_tests)
    write_csv(endpoints)
    return 0


if __name__ == "__main__":
    raise SystemExit(main())
