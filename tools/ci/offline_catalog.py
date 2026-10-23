"""Pinned upstream catalog helpers for CI fixtures and model archives, not app inference."""
from pathlib import Path

CATALOG = "translation/src/main/assets/index_v6.json"


def core_packs(catalog, languages):
    roots = set()
    for code in languages:
        assets = catalog["languages"][code].get("assets", {})
        if code != "en":
            roots.update(assets.get("translate", []))
        roots.update(assets.get("ocr", {}).values())
        roots.update(assets.get("support", []))
    roots.update(key for key, pack in catalog["packs"].items()
                 if pack.get("feature") == "support" and pack.get("kind") == "doc_detect")
    found = set()

    def visit(key):
        if key in found:
            return
        found.add(key)
        for dependency in catalog["packs"][key].get("dependsOn", []):
            visit(dependency)

    for root in sorted(roots):
        visit(root)
    return sorted(found)


def active_files(catalog, pack_ids):
    result = {}
    for key in pack_ids:
        roles = {}
        for item in catalog["packs"][key]["files"]:
            role = item["role"]
            if role not in roles or item.get("priority", 0) > roles[role].get("priority", 0):
                roles[role] = item
        for item in roles.values():
            path = item["installPath"]
            if path in result and result[path] != item:
                raise ValueError("Conflicting shared model paths")
            result[path] = dict(item, pack=key)
    return result


def resolve(root, relative):
    root = Path(root).resolve()
    target = (root / relative).resolve()
    if not target.is_relative_to(root) or target == root:
        raise ValueError("Package path escapes its install directory")
    return target
