#!/usr/bin/env python3
"""Validate translation PRs with the Python standard library; no Android SDK needed."""
from __future__ import annotations

import argparse
from collections import Counter
from dataclasses import dataclass
import pathlib
import re
import sys
import xml.etree.ElementTree as ET

ANDROID = "{http://schemas.android.com/apk/res/android}"
PRINTF = re.compile(r"%(?:(\d+)\$)?[-#+ 0,(]*(?:\d+)?(?:\.\d+)?([tT][a-zA-Z]|[a-zA-Z%])")
TOKENS = re.compile(r"\{(?:prefix|date|time|n)\}")
QUALITIES = {"zero", "one", "two", "few", "many", "other"}


@dataclass
class Resource:
    kind: str
    items: dict[str, str]
    translatable: bool
    formatted: bool


def text(element: ET.Element) -> str:
    return "".join(element.itertext())


def android_escapes(value: str) -> str | None:
    """Catch the common XML-valid but AAPT-invalid apostrophe/escape mistakes."""
    index = 0
    quoted = False
    while index < len(value):
        char = value[index]
        if char == "\\":
            index += 1
            if index >= len(value):
                return "trailing Android string escape"
            if value[index] == "u":
                if not re.fullmatch(r"[0-9a-fA-F]{4}", value[index+1:index+5]):
                    return "invalid Android Unicode escape"
                index += 4
        elif char == '"':
            quoted = not quoted
        elif char == "'" and not quoted:
            return "escape apostrophes as \\' or quote the Android string"
        index += 1
    return None


def placeholders(value: str) -> Counter:
    """Number and type matter; positional parameters may be reordered."""
    result: Counter = Counter()
    sequential = 1
    for match in PRINTF.finditer(value):
        index, conversion = match.groups()
        if conversion in {"%", "n"}:
            continue
        if conversion.lower() not in {"s", "d", "o", "x", "f", "e", "g", "a", "b", "c", "h"} and not conversion.startswith(("t", "T")):
            raise ValueError(f"unsupported format conversion %{conversion}")
        position = int(index) if index else sequential
        if position < 1:
            raise ValueError("format argument numbers start at 1")
        if not index:
            sequential += 1
        result[(position, conversion.lower())] += 1
    return result


def read_values(folder: pathlib.Path, errors: list[str]) -> dict[str, Resource]:
    result = {}
    for file in sorted(folder.glob("*.xml")):
        try:
            root = ET.parse(file).getroot()
        except (ET.ParseError, OSError) as error:
            errors.append(f"{file}: invalid XML: {error}")
            continue
        if root.tag != "resources":
            errors.append(f"{file}: root element must be resources")
            continue
        for entry in root:
            if entry.tag not in {"string", "plurals", "string-array"}:
                continue
            name = entry.get("name", "")
            if not re.fullmatch(r"[a-z][a-z0-9_]*", name):
                errors.append(f"{file}: invalid resource name {name!r}")
                continue
            if name in result:
                errors.append(f"{file}: duplicate resource {name}")
                continue
            if entry.tag == "string":
                items = {"value": text(entry)}
            else:
                items = {}
                for index, item in enumerate(entry):
                    key = item.get("quantity", "") if entry.tag == "plurals" else str(index)
                    if item.tag != "item" or key in items:
                        errors.append(f"{file}: {name}: invalid or duplicate item {key}")
                    if entry.tag == "plurals" and key not in QUALITIES:
                        errors.append(f"{file}: {name}: invalid plural quantity {key}")
                    items[key] = text(item)
                if entry.tag == "plurals" and "other" not in items:
                    errors.append(f"{file}: {name}: plurals require quantity=other")
            result[name] = Resource(entry.tag, items, entry.get("translatable") != "false", entry.get("formatted") != "false")
            for key, value in items.items():
                error = android_escapes(value)
                if error:
                    errors.append(f"{file}: {name}/{key}: {error}")
    return result


def directory_tag(name: str) -> str | None:
    # Locale-only directories, rather than density/night/layout variants.
    if re.fullmatch(r"values-[a-z]{2,3}(?:-r[A-Z]{2}|-r[0-9]{3})?", name):
        return name[7:].replace("-r", "-")
    if re.fullmatch(r"values-b\+[a-z]{2,3}(?:\+[A-Za-z0-9]{2,8})*", name):
        return name[9:].replace("+", "-")
    return None


def validate(res: pathlib.Path, strict: bool = False) -> tuple[list[str], list[str]]:
    errors: list[str] = []
    coverage: list[str] = []
    base = read_values(res / "values", errors)
    if not base:
        errors.append("No default string resources found")
    for name, resource in base.items():
        if resource.formatted:
            for value in resource.items.values():
                try:
                    placeholders(value)
                except ValueError as error:
                    errors.append(f"values/{name}: {error}")
    locale_file = res / "xml" / "app_locales.xml"
    registered: list[str] = []
    try:
        locale_root = ET.parse(locale_file).getroot()
        if locale_root.tag != "locale-config":
            errors.append(f"{locale_file}: root must be locale-config")
        for locale in locale_root:
            tag = locale.get(ANDROID + "name", "")
            if not re.fullmatch(r"[a-z]{2,3}(?:-[A-Za-z0-9]{2,8})*", tag):
                errors.append(f"{locale_file}: invalid BCP-47 tag {tag!r}")
            if tag in registered:
                errors.append(f"{locale_file}: duplicate locale {tag}")
            registered.append(tag)
    except (OSError, ET.ParseError) as error:
        errors.append(f"{locale_file}: {error}")
    if "en" not in registered:
        errors.append("app_locales.xml must include the English fallback (en)")
    available = {}
    for directory in sorted(res.glob("values-*")):
        tag = directory_tag(directory.name)
        if tag is None:
            continue
        translation = read_values(directory, errors)
        if tag in available:
            errors.append(f"{directory}: duplicate locale directory for {tag}")
        available[tag] = translation
        for name, localized in translation.items():
            original = base.get(name)
            location = f"{directory.name}/{name}"
            if original is None:
                errors.append(f"{location}: unknown resource key")
                continue
            if not original.translatable:
                errors.append(f"{location}: resource is translatable=false")
            if original.kind != localized.kind:
                errors.append(f"{location}: expected {original.kind}, found {localized.kind}")
                continue
            if original.formatted != localized.formatted:
                errors.append(f"{location}: preserve the formatted attribute")
            if original.kind == "string-array" and len(original.items) != len(localized.items):
                errors.append(f"{location}: array length changed; item order must also be preserved")
            for key, value in localized.items.items():
                expected = original.items.get(key, original.items.get("other", ""))
                if not value.strip() and expected.strip():
                    errors.append(f"{location}/{key}: empty translation")
                try:
                    if original.formatted and placeholders(value) != placeholders(expected):
                        errors.append(f"{location}/{key}: format placeholders changed")
                except ValueError as error:
                    errors.append(f"{location}/{key}: {error}")
                if Counter(TOKENS.findall(value)) != Counter(TOKENS.findall(expected)):
                    errors.append(f"{location}/{key}: naming tokens changed")
                if "\ufffd" in value:
                    errors.append(f"{location}/{key}: replacement character indicates broken text encoding")
        required = {name for name, value in base.items() if value.translatable}
        done = required & translation.keys()
        coverage.append(f"{tag}: {len(done)}/{len(required)} resources ({100*len(done)/max(1,len(required)):.1f}%)" + ("; registered" if tag in registered else "; draft, not in picker"))
        if strict and tag in registered and required - done:
            errors.append(f"{tag}: {len(required-done)} missing translations (English fallback is permitted without --strict)")
    for tag in registered:
        if tag != "en" and not available.get(tag):
            errors.append(f"{tag}: registered locale has no translations; do not advertise an empty language")
    return errors, coverage


def main() -> int:
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--res", type=pathlib.Path, default=pathlib.Path(__file__).resolve().parents[2] / "app/src/main/res")
    parser.add_argument("--strict", action="store_true", help="Require complete translations for registered languages")
    args = parser.parse_args()
    errors, coverage = validate(args.res, args.strict)
    for line in coverage:
        print(line)
    if not coverage:
        print("English source only; no translations have been added.")
    for error in errors:
        print(f"ERROR: {error}", file=sys.stderr)
    print(f"Translation validation: {'FAILED' if errors else 'OK'} ({len(errors)} errors)")
    return bool(errors)


if __name__ == "__main__":
    raise SystemExit(main())
