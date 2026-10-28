"""Run full validation for ready PRs and keep draft iteration fast."""
import json
import os
import subprocess


def plan(event_name, event, ref_name):
    pr = event.get("pull_request", {})
    full = event_name == "merge_group" or (event_name == "push" and ref_name in ("main", "master"))
    if event_name == "workflow_dispatch":
        full = str(event.get("inputs", {}).get("full_validation", "true")).lower() == "true"
    if event_name == "pull_request":
        if event.get("action") == "labeled":
            full = event.get("label", {}).get("name") == "full-validation"
        else:
            # Missing readiness metadata must not downgrade a PR to fast checks.
            full = not pr.get("draft", False)
    return {"full": str(full).lower(), "pr_number": str(pr.get("number", "")),
            "pr_branch": pr.get("head", {}).get("ref", ""), "pr_base": pr.get("base", {}).get("ref", "")}


def resolve_manual_pr(result, rows):
    if len(rows) > 1:
        raise ValueError("More than one PR for this branch; use the full-validation label on the intended PR")
    if rows:
        pr = rows[0]
        result.update(pr_number=str(pr["number"]), pr_branch=pr["head"]["ref"], pr_base=pr["base"]["ref"])
        # A manual fast run must not replace a ready PR's required full gate.
        if not pr.get("draft", False):
            result["full"] = "true"
    return result


if __name__ == "__main__":
    with open(os.environ["GITHUB_EVENT_PATH"], encoding="utf-8") as source:
        event = json.load(source)
    result = plan(os.environ["GITHUB_EVENT_NAME"], event, os.environ["GITHUB_REF_NAME"])
    if os.environ["GITHUB_EVENT_NAME"] == "workflow_dispatch":
        # Manual validation of an open PR must analyze that PR, not invent a Sonar branch.
        rows = json.loads(subprocess.check_output(["gh", "api", "-X", "GET", f"repos/{os.environ['GITHUB_REPOSITORY']}/pulls",
            "-f", "state=open", "-f", f"head={os.environ['GITHUB_REPOSITORY_OWNER']}:{os.environ['GITHUB_REF_NAME']}"], text=True))
        result = resolve_manual_pr(result, rows)
    with open(os.environ["GITHUB_OUTPUT"], "a", encoding="utf-8") as out:
        for name, value in result.items():
            if "\n" in value or "\r" in value:
                raise ValueError("Invalid workflow output")
            out.write(f"{name}={value}\n")
