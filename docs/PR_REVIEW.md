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
   `NickCh11/PaperFlow` as a **private** project with `main` as its main branch.
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
6. Merge this setup PR. Run **Actions → SonarQube Cloud → Run workflow** on `main`
   to establish the target-branch baseline. Then open a PR targeting `main`. For the current PR targeting
   `feature/offline-translator`, Free only supports it if that is the Sonar
   project's main branch. PRs targeting other branches require a plan that
   supports those targets. The workflow does not buy a plan or disguise a PR
   as a main-branch analysis.
7. Verify **SonarQube analysis** runs, the Cloud dashboard has all three JVM modules,
   and a Sonar quality-gate result appears on the PR. The workflow waits for the
   gate, so a failed gate fails the analysis job.
8. Only after successful end-to-end verification, consider requiring the analysis
   check in branch protection. Do not require the configuration check as a quality
   gate. Fork/dependabot secret availability must be considered before making a
   conditionally skipped check mandatory.

The workflow triggers for **every PR**, pushes to `main`, and manual dispatch.
For trusted PRs it reports a failing **setup required** configuration check when
keys/token are missing; the analysis job cannot start until onboarding is done.
The target branch must also be supported by the Cloud subscription. Triggering
a workflow is not evidence of successful analysis or a passing quality gate.
Fork PR code never receives the analysis token; the ordinary secret-free Android
PR checks still apply. There is no `pull_request_target` execution of PR code.

Scope: Java/Kotlin and Android sources selected by Gradle across `app`, `scanengine`
and `translation`. Vendored `third-party`, generated build directories and binary
assets are excluded. C/C++ analysis is disabled until a compilation-database/capture
pipeline is added; this setup makes no native-analysis claim. Test results are
available, but no JaCoCo coverage report is generated and no coverage percentage is
claimed. Configure coverage separately if needed by the chosen quality gate.

References: [plans and limits](https://docs.sonarsource.com/sonarqube-cloud/administering-sonarcloud/managing-subscription/subscription-plans),
[Gradle scanner](https://docs.sonarsource.com/sonarqube-cloud/analyzing-source-code/scanners/sonarscanner-for-gradle).
