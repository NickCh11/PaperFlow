"""Inventory packaging evidence; does not certify license or F-Droid compliance."""
import hashlib
import json
from collections import Counter
from pathlib import Path

ROOT = Path(__file__).resolve().parents[2]


def inventory():
    catalog = json.loads((ROOT / "translation/src/main/assets/index_v6.json").read_text(encoding="utf-8"))
    lock = json.loads((ROOT / "scanengine/upstream-lock.json").read_text(encoding="utf-8"))
    models = []
    for entry in lock["models"]:
        path = ROOT / entry["path"]
        actual = hashlib.sha256(path.read_bytes()).hexdigest()
        if actual != entry["sha256"] or path.stat().st_size != entry["size"]:
            raise ValueError(f"Bundled model differs from source lock: {entry['path']}")
        models.append({**entry, "hash_verified": True})
    uvdoc = ROOT / "app/src/main/assets/models/uvdoc.onnx"
    digest = hashlib.sha256(uvdoc.read_bytes()).hexdigest()
    if digest != "41796fa7aef61d96b7e053d652d0e9e5992f4134ed11daa3bbd3179db5069200":
        raise ValueError("UVDoc differs from documented conversion")
    models.append({"path": str(uvdoc.relative_to(ROOT)), "sha256": digest, "hash_verified": True})
    packs = catalog["packs"]
    files = [
        {"pack": key, "feature": pack["feature"], **entry}
        for key, pack in sorted(packs.items()) for entry in pack.get("files", [])
    ]
    return {
        "scope": "Whole bundled catalog, including descriptors for features not exposed by PaperFlow",
        "compliance_verified": False,
        "bundled_models": models,
        "packs_by_feature": dict(Counter(pack["feature"] for pack in packs.values())),
        "files_without_explicit_license": sum(not entry.get("license") for entry in files),
        "files_without_sha256": sum(not entry.get("sha256") for entry in files),
        "files": files,
    }


if __name__ == "__main__":
    print(json.dumps(inventory(), ensure_ascii=True, indent=2))
