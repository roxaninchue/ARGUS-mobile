---
name: ci-triage
description: Diagnose a failed GitLab CI pipeline for ARGUS - fetch the job trace, find the first real error, link it to the commit and propose the smallest fix as a merge request. Use when a pipeline goes red or the founder asks "why did CI fail".
tags: [ci, gitlab, debugging, devops]
version: 1
---

# CI triage

1. Get the pipeline and failed job ids from the event or the MR; fetch the job trace through the GitLab API.
2. Search the trace for the first "error:", "FAILED", "Caused by" or non-zero exit; quote that line and ten lines around it.
3. Find the last green pipeline on the same branch and list the commits between it and the red one.
4. Decide the class: code error, test failure, flaky test, dependency or cache problem, runner or infrastructure.
5. For code or test errors, hand the fix to the coder worker on a new branch and the review to the reviewer worker; open a draft MR.
6. For flakes, record the test name and rerun once; report it either way.
7. Never push to a protected branch, merge, tag, deploy or touch CI variables: those wait for the founder's approval.
