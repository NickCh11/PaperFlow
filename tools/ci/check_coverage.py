"""Report whole-project coverage and reject missing measurements or unmet targets."""
from __future__ import annotations

import argparse
from dataclasses import dataclass
from decimal import Decimal
import json
from pathlib import Path
import sys
import xml.etree.ElementTree as ET

ROOT = Path(__file__).resolve().parents[2]
MODULES = ("app", "scanengine", "translation")


@dataclass(frozen=True)
class Counter:
    covered: int
    missed: int

    @property
    def total(self):
        return self.covered + self.missed

    def passes(self, target):
        return self.total == 0 or Decimal(self.covered) * 100 >= Decimal(self.total) * target

    def data(self):
        return dict(covered=self.covered, missed=self.missed, total=self.total,
                    percent=round(100 * self.covered / self.total, 2) if self.total else None)


def counters(element):
    result = {}
    for kind in ("LINE", "BRANCH"):
        found = element.findall(f"counter[@type='{kind}']")
        if len(found) != 1:
            raise ValueError(f"Expected exactly one {kind} counter")
        try:
            covered, missed = int(found[0].get("covered", "")), int(found[0].get("missed", ""))
        except ValueError as error:
            raise ValueError(f"Invalid {kind} counter") from error
        if min(covered, missed) < 0:
            raise ValueError(f"Negative {kind} counter")
        result[kind.lower()] = Counter(covered, missed)
    return result


def read_jacoco(path):
    root = ET.parse(path).getroot()
    if root.tag != "report":
        raise ValueError(f"Not a JaCoCo report: {path}")
    totals = counters(root)
    if totals["line"].total == 0:
        raise ValueError(f"Empty production coverage report: {path}")
    files = []
    for package in root.findall("package"):
        for source in package.findall("sourcefile"):
            row = dict(path=package.get("name", "") + "/" + source.get("name", ""))
            # A file with no branches may legitimately omit its BRANCH counter.
            for kind in ("LINE", "BRANCH"):
                found = source.findall(f"counter[@type='{kind}']")
                if len(found) > 1:
                    raise ValueError(f"Duplicate {kind} counter in {row['path']}")
                counter = Counter(int(found[0].get("covered", "")), int(found[0].get("missed", ""))) if found else Counter(0, 0)
                if min(counter.covered, counter.missed) < 0:
                    raise ValueError(f"Negative file counter: {row['path']}")
                row[kind.lower()] = counter.data()
            row["uncovered_lines"] = [int(line.get("nr")) for line in source.findall("line") if int(line.get("ci", "0")) == 0]
            files.append(row)
    if not files:
        raise ValueError(f"Report has no production source files: {path}")
    return totals, files


def read_native(path, expected_sources):
    """Require all owned Rust sources and genuine line/branch execution records."""
    if path is None:
        return dict(status="unmeasured", missing_sources=sorted(expected_sources), line=None, branch=None, files=[])
    if not path.is_file():
        raise ValueError(f"Native coverage report does not exist: {path}")
    records = {}
    current = None
    for raw in path.read_text("utf-8").splitlines():
        if raw.startswith("SF:"):
            source = raw[3:].replace("\\", "/")
            matches = [name for name in expected_sources if source == name or source.endswith("/" + name)]
            current = records.setdefault(matches[0], dict(lines={}, branches={})) if len(matches) == 1 else None
        elif current is not None and raw.startswith("DA:"):
            fields = raw[3:].split(",")
            if len(fields) not in (2, 3):
                raise ValueError("Invalid native line record")
            number, hits = int(fields[0]), int(fields[1])
            if number <= 0 or hits < 0:
                raise ValueError("Invalid native line record")
            current["lines"][number] = max(current["lines"].get(number, 0), hits)
        elif current is not None and raw.startswith("BRDA:"):
            fields = raw[5:].split(",")
            if len(fields) != 4:
                raise ValueError("Invalid native branch record")
            line, block, branch = map(int, fields[:3])
            if line <= 0 or min(block, branch) < 0:
                raise ValueError("Invalid native branch location")
            hits = 0 if fields[3] == "-" else int(fields[3])
            if hits < 0:
                raise ValueError("Negative native branch hit count")
            key = tuple(fields[:3])
            current["branches"][key] = max(current["branches"].get(key, 0), hits)
        elif raw == "end_of_record":
            current = None
    missing = sorted(name for name in expected_sources if not records.get(name, {}).get("lines"))
    lines = [hits for row in records.values() for hits in row["lines"].values()]
    branches = [hits for row in records.values() for hits in row["branches"].values()]
    files = []
    for name, row in sorted(records.items()):
        file_lines, file_branches = list(row["lines"].values()), list(row["branches"].values())
        files.append(dict(path=name,
                          line=Counter(sum(hit > 0 for hit in file_lines), sum(hit == 0 for hit in file_lines)).data(),
                          branch=Counter(sum(hit > 0 for hit in file_branches), sum(hit == 0 for hit in file_branches)).data(),
                          uncovered_lines=sorted(number for number, hits in row["lines"].items() if hits == 0),
                          uncovered_branch_lines=sorted({int(key[0]) for key, hits in row["branches"].items() if hits == 0})))
    return dict(status="measured" if not missing and branches else "partial", missing_sources=missing,
                line=Counter(sum(hit > 0 for hit in lines), sum(hit == 0 for hit in lines)).data(),
                branch=Counter(sum(hit > 0 for hit in branches), sum(hit == 0 for hit in branches)).data(), files=files)


def evaluate(reports, target, native_path=None, native_sources=()):
    if set(reports) != set(MODULES):
        raise ValueError(f"Coverage must include every JVM module: {', '.join(MODULES)}")
    paths = [Path(path).resolve() for path in reports.values()]
    if len(set(paths)) != len(paths):
        raise ValueError("The same report cannot stand in for multiple modules")
    result = dict(target=str(target), modules={}, native=read_native(native_path, native_sources), passed=True)
    combined = {kind: Counter(0, 0) for kind in ("line", "branch")}
    for name, path in reports.items():
        totals, files = read_jacoco(path)
        result["modules"][name] = dict(**{kind: counter.data() for kind, counter in totals.items()}, files=files)
        for kind, counter in totals.items():
            previous = combined[kind]
            combined[kind] = Counter(previous.covered + counter.covered, previous.missed + counter.missed)
            result["passed"] &= counter.passes(target)
    result["jvm"] = {kind: counter.data() for kind, counter in combined.items()}
    native = result["native"]
    result["passed"] &= native["status"] == "measured"
    if native["status"] == "measured":
        for kind in ("line", "branch"):
            counter = native[kind]
            result["passed"] &= Counter(counter["covered"], counter["missed"]).passes(target)
    return result


def markdown(result):
    def cell(counter):
        return "Not measured" if counter is None else (f"{counter['percent']:.2f}% ({counter['covered']}/{counter['total']})" if counter["total"] else "No branches")
    rows = ["# Whole-project coverage", "", f"Target: {result['target']}% of executable lines and branches in every module.", "",
            "| Scope | Lines | Branches |", "| --- | ---: | ---: |"]
    for name, row in result["modules"].items():
        rows.append(f"| {name} | {cell(row['line'])} | {cell(row['branch'])} |")
    rows.append(f"| JVM total | {cell(result['jvm']['line'])} | {cell(result['jvm']['branch'])} |")
    rows.append(f"| Owned native Rust ({result['native']['status']}) | {cell(result['native']['line'])} | {cell(result['native']['branch'])} |")
    rows.extend(["", "Result: " + ("PASS" if result["passed"] else "TARGET NOT MET"), "",
                 "Native coverage is separate from JaCoCo. Missing native files or branch data prevent a whole-project pass.",
                 "Dependencies, generated artifacts and test code are outside these production coverage reports.", "", "## Largest remaining JVM gaps", ""])
    gaps = sorted(((row["line"]["missed"], name, row) for name, module in result["modules"].items() for row in module["files"]), reverse=True, key=lambda item: item[0])
    for missed, name, row in gaps[:25]:
        if missed:
            rows.append(f"- `{name}/{row['path']}`: {missed} uncovered executable lines, {row['branch']['missed']} uncovered branches.")
    if result["native"]["missing_sources"]:
        rows.extend(["", "Native files without complete measurement:", ""] + [f"- `{path}`" for path in result["native"]["missing_sources"]])
    if result["native"].get("files"):
        rows.extend(["", "## Remaining owned Rust gaps", ""])
        for row in sorted(result["native"]["files"], key=lambda item: item["line"]["missed"], reverse=True):
            rows.append(f"- `{row['path']}`: {row['line']['missed']} uncovered executable lines, {row['branch']['missed']} uncovered branches.")
    return "\n".join(rows) + "\n"


def main(argv=None):
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--root", type=Path, default=ROOT)
    parser.add_argument("--target", type=Decimal, default=Decimal(100))
    parser.add_argument("--native-lcov", type=Path)
    parser.add_argument("--json", type=Path)
    parser.add_argument("--markdown", type=Path)
    args = parser.parse_args(argv)
    if not args.target.is_finite() or not Decimal(0) <= args.target <= Decimal(100):
        parser.error("target must be between 0 and 100")
    reports = {module: args.root / module / "build/reports/jacoco/coverageReport/coverageReport.xml" for module in MODULES}
    native_sources = {file.relative_to(args.root).as_posix() for file in (args.root / "translation/native/src").rglob("*.rs")}
    try:
        if not native_sources:
            raise ValueError("No native production sources found; scope discovery failed")
        result = evaluate(reports, args.target, args.native_lcov, native_sources)
    except (OSError, ValueError, ET.ParseError) as error:
        print(f"Coverage measurement failed: {error}", file=sys.stderr)
        return 2
    for path, text in ((args.json, json.dumps(result, indent=2) + "\n"), (args.markdown, markdown(result))):
        if path is not None:
            path.parent.mkdir(parents=True, exist_ok=True)
            path.write_text(text, encoding="utf-8")
    print(markdown(result))
    return 0 if result["passed"] else 1


if __name__ == "__main__":
    raise SystemExit(main())
