"""Choose explicit full validation without repeating the matrix on every PR push."""
import json
import os
import subprocess


def plan(event_name, event, ref_name):
    full = event_name == "merge_group" or (event_name == "push" and ref_name in ("main", "master"))
    if event_name == "workflow_dispatch":
        full = str(event.get("inputs", {}).get("full_validation", "true")).lower() == "true"
    if event_name == "pull_request":
        full = event.get("action") == "labeled" and event.get("label", {}).get("name") == "full-validation"
    pr = event.get("pull_request", {})
    return {"full": str(full).lower(), "pr_number": str(pr.get("number", "")),
            "pr_branch": pr.get("head", {}).get("ref", ""), "pr_base": pr.get("base", {}).get("ref", "")}


if __name__ == "__main__":
    event = json.load(open(os.environ["GITHUB_EVENT_PATH"], encoding="utf-8"))
    result = plan(os.environ["GITHUB_EVENT_NAME"], event, os.environ["GITHUB_REF_NAME"])
    if os.environ["GITHUB_EVENT_NAME"] == "workflow_dispatch":
        # Manual validation of an open PR must analyze that PR, not invent a Sonar branch.
        rows = json.loads(subprocess.check_output(["gh", "api", "-X", "GET", f"repos/{os.environ['GITHUB_REPOSITORY']}/pulls",
            "-f", "state=open", "-f", f"head={os.environ['GITHUB_REPOSITORY_OWNER']}:{os.environ['GITHUB_REF_NAME']}"], text=True))
        if len(rows) > 1:
            raise ValueError("More than one PR for this branch; use the full-validation label on the intended PR")
        if rows:
            result.update(pr_number=str(rows[0]["number"]), pr_branch=rows[0]["head"]["ref"], pr_base=rows[0]["base"]["ref"])
    with open(os.environ["GITHUB_OUTPUT"], "a", encoding="utf-8") as out:
        for name, value in result.items():
            if "\n" in value or "\r" in value:
                raise ValueError("Invalid workflow output")
            out.write(f"{name}={value}\n")
