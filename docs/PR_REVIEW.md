# Pull request review integrations

The existing **Android PR checks / PR gate** remains the build and correctness
gate. Greptile provides contextual AI review; SonarQube Cloud provides static
analysis and a new-code quality gate. Neither replaces device, native, leak,
camera, battery or performance testing.

## Greptile

The GitHub App has submitted three inline review comments on PR #1. Its earlier
comment reported exhausted review credits; account credits and automatic review
settings are managed in [Greptile](https://app.greptile.com/). A committed
workflow does not purchase credits or activate a subscription.

## SonarQube Cloud onboarding

Configured on 2026-10-24: private project `NickCh11_PaperFlow` in organization
`nickch11`, on the Free plan. Both Actions variables and the encrypted
`SONAR_TOKEN` secret are installed. Automatic analysis is off; Gradle CI is used.
At the owner's request, the Sonar main branch is `feature/offline-translator`;
GitHub's default branch remains `main`. Free PR analysis therefore applies to
PRs targeting `feature/offline-translator`. Change the Sonar main branch and the
workflow's push filter together when development moves back to `main`.

The initial baseline was scanned from target commit
`1c9eeaf265481018c7795169bf0d13dc5d1a0adb`, using a temporary copy of the scanner
build configuration without changing its application sources. PR #1 was then
analyzed from GitHub commit `b11136310d83a4718a3de7aed36850c6ce8c8241` against
that target. This verifies actual Cloud analysis and PR decoration, not only
authentication. The first PR quality gate **failed**: ten reported issues and
0% reported coverage on 241 new lines to cover. No JaCoCo XML was supplied, so
test execution must not be confused with measured coverage. Reports include
autoboxing and volatile-counter findings that need semantic review; they are
not all confirmed runtime bugs. No findings have been dismissed or quality-gate
thresholds weakened to make the check green. The existing Sonar way gate remains.

The workflow also generates the Android Lint reports expected by the scanner,
uses the release version from `version.properties`, and ignores generated
`.sonar` / `.scannerwork` files.

PaperFlow is currently a **private GitHub repository**. The documented Cloud Free
plan supports private projects up to **50,000 analyzed LOC across the organization**
and PR analysis targeting the project's main branch. The separate OSS plan is for
public projects. Keep the GitHub repository and Cloud project private; making the
repository public is not part of this setup. Plan eligibility must be confirmed
in the actual Sonar account. Excluded vendor files/tests/comments do not count
toward Sonar's analyzed production LOC; a local source line count is only an estimate.

1. Sign in to [SonarQube Cloud](https://sonarcloud.io/) with GitHub. Create or bind
   the organization for `NickCh11`, choosing the **Free** plan for a private project.
   If the UI requests payment, stop and review eligibility; do not start a paid plan.
2. Install/configure SonarQube Cloud's GitHub App for **only PaperFlow**, then import
   `NickCh11/PaperFlow` as a **private** project. Set `feature/offline-translator`
   as its Sonar main branch for the current development workflow.
3. Choose **CI-based analysis / GitHub Actions**, and disable automatic analysis
   for this project if enabled. Android Java/Kotlin analysis uses compiled outputs
   via the pinned SonarScanner for Gradle plugin, not the generic scan action.
4. Copy the exact **organization key** and **project key** from Sonar. Do not guess
   them from the repository name. In GitHub **Settings → Secrets and variables →
   Actions → Variables**, add:

   | Variable | Value |
   | --- | --- |
   | `SONAR_ORGANIZATION` | The actual Sonar organization key |
   | `SONAR_PROJECT_KEY` | The actual Sonar project key |

5. Generate an analysis token with the minimum supported analysis permissions
   and an expiration date in Sonar. Store it directly in GitHub **Actions secrets**
   as `SONAR_TOKEN`. Never paste it into source, a PR, chat, or a workflow log.
6. Establish a baseline from the actual `feature/offline-translator` source
   before the first PR analysis. After this setup PR is merged into that branch,
   its pushes refresh the baseline automatically. PRs targeting other branches require a plan that
   supports those targets. The workflow does not buy a plan or disguise a PR
   as a main-branch analysis.
7. Verify **SonarQube analysis** runs, the Cloud dashboard has all three JVM modules,
   and a Sonar quality-gate result appears on the PR. The workflow waits for the
   gate, so a failed gate fails the analysis job.
8. Only after successful end-to-end verification, consider requiring the analysis
   check in branch protection. Do not require the configuration check as a quality
   gate. Fork/dependabot secret availability must be considered before making a
   conditionally skipped check mandatory.

Android PR checks calls the reusable Sonar workflow for **every PR**, after the
JVM and device suites pass. Pushes to `feature/offline-translator` and manual
dispatch through Android PR checks refresh the baseline. The analysis downloads
coverage artifacts from the same run; it never imports another revision’s data.
For trusted PRs it reports a failing **setup required** configuration check when
keys/token are missing; the analysis job cannot start until onboarding is done.
The target branch must also be supported by the Cloud subscription. Triggering
a workflow is not evidence of successful analysis or a passing quality gate.
Fork PR code never receives the analysis token; the ordinary secret-free Android
PR checks still apply. There is no `pull_request_target` execution of PR code.

Scope: Java/Kotlin and Android sources selected by Gradle across `app`, `scanengine`
and `translation`. Vendored `third-party`, generated build directories and binary
assets are excluded. C/C++ analysis is disabled until a compilation-database/capture
pipeline is added; this setup makes no native-analysis claim. JaCoCo 0.8.15 merges real JVM execution data and instrumented device execution
data into one XML report per module. Enable collection with `-PpaperflowCoverage=true`
and generate reports with `coverageReport`. Sonar imports these XML files.
Generated classes and native code are outside this JVM coverage measurement;
production Java/Kotlin sources are not excluded to improve the percentage.
The Sonar way quality gate, including 80% coverage on new code, is unchanged.

Cloud API credentials now require recent device authentication. The optional Cloud
conversion route confirms the screen lock before reading or saving credentials;
devices without a secure lock cannot persist/use Cloud credentials. Existing
ciphertext migrates to an authentication-bound v2 key after confirmation, and the
legacy key is removed only after the new ciphertext is committed. Keystore and
preference IO run on a worker. Local conversion does not require this confirmation.

References: [plans and limits](https://docs.sonarsource.com/sonarqube-cloud/administering-sonarcloud/managing-subscription/subscription-plans),
[Gradle scanner](https://docs.sonarsource.com/sonarqube-cloud/analyzing-source-code/scanners/sonarscanner-for-gradle).
