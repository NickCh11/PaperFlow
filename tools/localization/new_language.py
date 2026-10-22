#!/usr/bin/env python3
"""Create an empty Android translation file; register it only after actual translations exist."""
import argparse
import pathlib
import re
import xml.etree.ElementTree as ET
from check_translations import ANDROID, read_values, validate


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("tag", help="BCP-47 language tag, e.g. fr, pt-BR, zh-Hant")
    parser.add_argument("--register", action="store_true", help="Enable an existing translation in the picker")
    parser.add_argument("--res", type=pathlib.Path, default=pathlib.Path(__file__).resolve().parents[2] / "app/src/main/res")
    args = parser.parse_args()
    if not re.fullmatch(r"[a-z]{2,3}(?:-[A-Za-z0-9]{2,8})*", args.tag):
        parser.error("Use a valid BCP-47 tag with a lowercase language code")
    if args.tag == "en":
        parser.error("English is the source language in values/strings.xml")
    parts = args.tag.split("-")
    qualifier = "values-" + parts[0] if len(parts) == 1 else "values-" + parts[0] + "-r" + parts[1] if len(parts) == 2 and re.fullmatch(r"[A-Z]{2}|[0-9]{3}", parts[1]) else "values-b+" + "+".join(parts)
    folder = args.res / qualifier
    file = folder / "strings.xml"
    if not args.register:
        folder.mkdir(parents=True, exist_ok=True)
        if file.exists():
            print(f"Already exists; kept unchanged: {file}")
        else:
            file.write_text('<?xml version="1.0" encoding="utf-8"?>\n<resources>\n    <!-- Add translated entries from ../values/strings.xml.\n         Keep resource names, numbered placeholders and formatted attributes.\n         Missing entries use the English fallback. Do not copy translatable=false entries. -->\n</resources>\n', encoding="utf-8")
            print(f"Created draft: {file}")
        print("Not added to the language picker. Translate entries, then run this command with --register.")
        return
    errors = []
    if not read_values(folder, errors):
        parser.error("Translate at least one entry before registering the language")
    current_errors, _ = validate(args.res)
    if errors or current_errors:
        parser.error("Fix resource validation errors first:\n" + "\n".join(errors + current_errors))
    config = args.res / "xml/app_locales.xml"
    content = config.read_text(encoding="utf-8")
    root = ET.fromstring(content)
    if any(entry.get(ANDROID + "name") == args.tag for entry in root):
        print(f"Already registered: {args.tag}")
        return
    config.write_text(content.replace("</locale-config>", f'    <locale android:name="{args.tag}" />\n</locale-config>'), encoding="utf-8")
    print(f"Registered {args.tag}. Run check_translations.py and include both files in your PR.")


if __name__ == "__main__":
    main()
