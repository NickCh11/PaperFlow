# Actions execution and spending controls

PaperFlow is public as of 2026-10-25. Its workflows use standard Ubuntu runners;
the owner's existing Actions budget is $0 with usage blocking. Public compute
eligibility does not remove storage limits or change Sonar/Greptile subscriptions.

## Normal PR update

- CI tooling and translation validators, JVM tests, Android Lint.
- Sonar uses compiled inputs and real JVM execution reports from that same run.
- No native compiler setup, release APK matrix or emulator startup.
- The unchanged Sonar quality gate still applies. JVM-only coverage is explicitly
  identified; a fast run does not claim full device coverage or 100% coverage.

## Final validation

Add the repository label `full-validation` to the intended PR revision, or use
Android PR checks → Run workflow → select its branch → `full_validation=true`.
Main/master pushes and merge-queue entries also request full validation.

This runs the three native ABIs, both emulator API suites, Rust tests, all three
debug/release APK variants and Sonar with same-run device coverage. New PR pushes
return to fast checks even if the label remains attached. Remove/reapply it to
validate a later revision. No background automation repeatedly requests full runs.

Native artifacts are reused only on an exact source/lockfile/build-toolchain key.
The full matrix still executes their tests. A single source/workflow change
invalidates that key; no other revision's test coverage is reused.

## Release

Publishing requires successful full acceptance jobs on the exact `main` commit.
A successful fast workflow is insufficient. An incomplete validation makes the
release fail immediately instead of consuming up to 45 runner minutes waiting.
This change does not publish a release or merge the development PR.

## Removed repetition and retention

- Resource translation validation lives in Android CI; the duplicate workflow was
  removed from this PR.
- Native compilation is reusable-only; independent native push triggers were
  removed from this PR.
- Weekly model availability checks remain. Downloading and checking every model
  is an explicit manual option, not a monthly scheduled download.
- Test reports/native artifacts are retained for 3 days. Release APK inputs use
  7 days so the publishing workflow can consume them.

Workflow changes are delivered on PR #1 targeting `feature/offline-translator`.
Other branches adopt those files when the change is merged; repository visibility
and the account budget apply immediately.

## Pre-publication check

Gitleaks 8.30.1 checked all 36 commits reachable from fetched branches/tags after
unshallowing the repository. All 801 generic-key detections were in historical
cpuinfo test fixtures. Every flagged source line was compared with the public
`pytorch/cpuinfo` source archive and matched after whitespace normalization.
There were no findings outside those fixtures. This automated review is evidence
of that scan, not a guarantee that arbitrary past data can never be sensitive.

References:
- https://docs.github.com/en/billing/concepts/product-billing/github-actions
- https://docs.github.com/en/billing/how-tos/set-up-budgets
