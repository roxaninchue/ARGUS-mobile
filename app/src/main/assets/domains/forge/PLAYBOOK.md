# Forge playbook

## Principles of the field
- Main stays green. Every change goes through a merge request with a passing pipeline and a review; nothing is pushed straight to a protected branch.
- Read the log before guessing: the first real error in a failed CI job usually names the cause; later errors are fallout.
- Small, reversible changes beat big ones. One concern per merge request, a clear title and a rollback path.
- Secrets never appear in code, logs, chat or memory; CI variables and keystores are off limits.
- Observability closes the loop: Sentry crash groups, worker health checks and release notes say whether a deploy worked.

## Quality bar
A good CI diagnosis names the pipeline and job, quotes the first failing line, identifies the commit or dependency that introduced it and proposes the smallest fix with the file and line. A good daily note lists merged MRs, red pipelines and new crash groups with links, in five lines or fewer.

## Best practices
- Fetch the job trace through the GitLab API and search for "error:", "FAILED" and "Caused by" before reading the whole log.
- Reproduce locally or in the Ubuntu workspace with the same JDK and Gradle versions as CI.
- Bisect between the last green and first red commit when the cause is not obvious.
- Hand code changes to the coder worker and reviews to the reviewer worker; open the MR as a draft for the founder.
- Watch Cloudflare worker health endpoints and alert on failure or latency more than twice the baseline.

## Common mistakes to avoid
- Retrying a flaky job until it passes without recording the flake.
- Fixing the symptom (bumping a timeout) instead of the cause.
- Merging, tagging, deploying or editing CI variables without an explicit yes on an approval card.
- Changing applicationId, signing config, versionCode or the keystore as part of an unrelated fix.

## Self-check
- [ ] The diagnosis quotes the first real error line and names the job and commit.
- [ ] The proposed fix is the smallest change that addresses the cause, on a branch with an MR.
- [ ] No merge, tag, deploy, protected push or secret access happened without approval.
- [ ] Links to the pipeline, MR or Sentry issue are included.
- [ ] Release-critical files (signing, versions, CI config) are untouched unless the task was about them.

## Output format
Status line (green/red, pipeline link). Then: cause (one line), evidence (quoted log line), fix (file, change), next step (MR link or approval request). Daily note: shipped / broke / watch, one bullet each.
