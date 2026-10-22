"""Check every pinned URL; weekly full mode also verifies all downloaded bytes."""
import argparse
from concurrent.futures import ThreadPoolExecutor, as_completed
import json
from pathlib import Path
import tempfile
import urllib.request
import time
import sys
sys.path.insert(0, str(Path(__file__).resolve().parent))
from model_distribution import check_tag, mirror_url
from prepare_models import fetch


def check_url(url):
    last = None
    for attempt in range(3):
        try:
            # A one-byte range verifies GET availability without downloading all weights.
            # Some CDNs ignore ranges; read only one byte and immediately close.
            request = urllib.request.Request(url, headers={"Range": "bytes=0-0", "User-Agent": "PaperFlow-Model-Health/1"})
            with urllib.request.urlopen(request, timeout=30) as response:
                if response.status not in (200, 206) or not response.read(1):
                    raise ValueError("Model endpoint returned no data")
            return
        except (OSError, ValueError) as problem:
            last = problem
            if attempt < 2:
                time.sleep(2 ** attempt)
    raise last


def main():
    parser = argparse.ArgumentParser()
    parser.add_argument("--repository", default="")
    parser.add_argument("--verify", action="store_true", help="Download and verify every official model")
    parser.add_argument("--report", type=Path, default=Path("build/model-health.json"))
    args = parser.parse_args()
    tag, entries = check_tag()
    jobs = []
    for name, entry in entries.items():
        jobs.append((name, "official", entry["url"]))
        if args.repository:
            jobs.append((name, "mirror", mirror_url(args.repository, tag, name)))
    results = []
    with tempfile.TemporaryDirectory(prefix="paperflow-model-health-") as directory:
        def check(job):
            name, source, url = job
            try:
                if args.verify:
                    entry = dict(entries[name])
                    if source == "mirror":
                        entry["url"] = url
                        entry.pop("gzip", None)
                    fetch(entry, Path(directory) / (source + "-" + name))
                else:
                    check_url(url)
                return dict(asset=name, source=source, ok=True)
            except Exception as problem:
                return dict(asset=name, source=source, ok=False, error=str(problem))
        with ThreadPoolExecutor(max_workers=4) as pool:
            for future in as_completed([pool.submit(check, job) for job in jobs]):
                results.append(future.result())
    args.report.parent.mkdir(parents=True, exist_ok=True)
    args.report.write_text(json.dumps(dict(tag=tag, verifiedBytes=args.verify, results=results), indent=2) + "\n")
    failed = [r for r in results if not r["ok"]]
    for row in failed:
        print(f"{row['source']} {row['asset']}: {row['error']}")
    print(f"{len(results)-len(failed)}/{len(results)} endpoints passed; report: {args.report}")
    if failed:
        raise SystemExit(1)


if __name__ == "__main__":
    main()
