"""Reject empty, failed, skipped, or partial JUnit suites, even if the runner exited successfully."""
from pathlib import Path
import re
import sys
import xml.etree.ElementTree as ET


def expected_tests(source):
    expected = set()
    for file in Path(source).rglob("*"):
        if file.suffix not in (".java", ".kt"):
            continue
        text = file.read_text("utf-8")
        package = re.search(r"\bpackage\s+([\w.]+)", text)
        methods = re.findall(r"@Test\s+(?:public\s+void|(?:public\s+)?fun)\s+(\w+)\s*\(", text)
        if len(methods) != len(re.findall(r"@Test\b", text)):
            raise ValueError(f"Unsupported test declaration; update source discovery: {file}")
        for method in methods:
            if not package:
                raise ValueError(f"Test has no package: {file}")
            expected.add((package[1] + "." + file.stem, method))
    if not expected:
        raise ValueError("No source tests discovered")
    return expected


def check(directory, source=None):
    files = list(Path(directory).rglob("TEST-*.xml"))
    if not files:
        raise ValueError("No XML test reports")
    cases = []
    for file in files:
        root = ET.parse(file).getroot()
        for suite in root.iter("testsuite"):
            if any(int(suite.get(name, "0")) for name in ("failures", "errors", "skipped")):
                raise ValueError(f"Unsuccessful suite: {file.name}")
        device_cases = list(root.iter("testcase"))
        if not device_cases:
            raise ValueError(f"Runner executed zero tests: {file.name}")
        cases.extend(device_cases)
    if not cases:
        raise ValueError("Runner executed zero tests")
    for case in cases:
        if any(case.find(tag) is not None for tag in ("failure", "error", "skipped")):
            raise ValueError(f"Unsuccessful test: {case.get('classname')}.{case.get('name')}")
    if source:
        actual = {(case.get("classname"), case.get("name")) for case in cases}
        missing = expected_tests(source) - actual
        if missing:
            raise ValueError("Tests missing from report: " + ", ".join(f"{c}.{m}" for c,m in sorted(missing)))
    return len(cases)


if __name__ == "__main__":
    try:
        source = sys.argv[2] if len(sys.argv) > 2 else None
        print(f"Passed {check(sys.argv[1], source)} tests; none skipped.")
    except (ValueError, ET.ParseError, IndexError) as error:
        print(error, file=sys.stderr)
        sys.exit(1)
