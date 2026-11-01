# Plan: giving Blaze real, reliable use

**Status:** proposal · **Branch:** `feat/0.49.0-mem1-ui-browse` · **Base:** `0.49.0-mem1` (`feat/tiered-memory`)

The goal is narrow and testable: a person should be able to hand Blaze a real task — "find the cheapest
of these three, and book the one with a free slot" — and have it either finish or say clearly why it
couldn't. Today it can do impressive things and also fail in ways that look like success. This plan
closes that gap.

---

## 1. Where we actually are

Three things are true right now, and the plan is built on them rather than on ambition.

**Browsing fires when it shouldn't.** The trigger was a keyword match: any message starting with
`is`, `are`, `do`, `can`, `check`, `tell me`, `latest`, `current`, `where` or `why` flipped Blaze into
a browsing state. "Check the time" and "where am I" both went to the web. This branch replaces that
with `BrowseIntent`, which asks a different question — *can the answer change?* — and only browses
when it can. That is the foundation; everything below assumes it.

**Browsing returns pages, not answers.** Even when the trigger is right, the failure is relevance:
the fetched page is about the topic but not the question. A search for a specific error message
returns the vendor's marketing page. The fix is not a better search engine, it is a verification step
between fetching and answering.

**Phone control works but is not trustworthy.** The pointer, the glide, the ripple and the risky-label
check are all real. What is missing is recovery: when a screen doesn't look the way the plan expected,
Blaze has no reliable way to notice and re-orient, so it taps into the wrong place and reports success.

---

## 2. The four things that have to be true

In the order that unblocks the most work. Each has a measurable exit condition, because "feels better"
is not a plan.

### 2.1 Browsing decides, then verifies — not just decides

**Already done on this branch:** the decision. `BrowseIntent` classifies a message into
`NO_WEB` / `MAYBE` / `NEEDS_WEB` using whether the answer is time-sensitive, whether it names a
specific entity that must be looked up, and whether the user asked for a source. It also filters
candidate pages by whether they actually contain the query's key terms, which kills the
"right topic, wrong page" failure.

**Still to do:** the verification loop. After fetching, Blaze should check that the page answers the
question before it writes a sentence from it:

1. Extract the passage that would answer the question.
2. Ask a cheap model: *does this passage answer this question, yes or no?*
3. If no, try the next result. After three misses, say so — "I couldn't find a page that answers
   this" — rather than answering from a loosely related page.

**Exit condition:** on a fixed set of 50 questions with known answers, ≥90% of answers cite a page
that genuinely contains the answer, and 0% of answers are drawn from a page that fails the check.
The second number matters more than the first.

### 2.2 Every claim carries its source, or is marked as a guess

Right now a browsing answer and a model-memory answer look identical. That is the single biggest
reason Blaze is hard to trust: you cannot tell which sentences are grounded.

**Do:** every sentence that came from a page gets a source marker; every sentence that came from the
model's own memory gets none, and the reply says so once at the top when the mix is significant.
`ReplySources` already normalises URLs — this is wiring, not new machinery.

**Exit condition:** a reader can tell, without opening anything, which parts of a reply are grounded.

### 2.3 Navigation recovers instead of assuming

The current loop is: look at the screen, decide a target, glide, tap, assume it worked. When the
assumption is wrong the run continues confidently in the wrong place.

**Do:** after every action, re-read the screen and compare it to what the action was supposed to
cause. Three outcomes:

- **Changed as expected** → continue.
- **Changed differently** → re-orient: re-read, re-plan from the new screen, and say so in the step
  line ("that opened Settings instead — going back").
- **Didn't change** → retry once with a longer settle, then stop and ask, rather than tapping again.

**Exit condition:** on a scripted 20-step task across five apps, zero silent wrong-taps — every
divergence is either recovered or reported.

### 2.4 A task has a definition of done, and Blaze checks it

The most damaging failure is a confident "Done!" on an unfinished task. A task should carry its own
success condition, written when the task is planned, and checked before the reply.

**Do:** when Blaze plans, it states what "done" looks like ("the confirmation screen shows an order
number"). Before replying, it verifies that condition against the screen. If it can't verify, it says
"done as far as I can tell, but I couldn't confirm the order number" — which is honest and useful.

**Exit condition:** on the same 20-step task, no reply claims completion without a verified condition.

---

## 3. Order of work

Deliberately sequential: each step makes the next one measurable.

| # | Step | Why here | Effort |
|---|---|---|---|
| 1 | Browse decision (`BrowseIntent`) | **Done on this branch.** Everything else assumes it. | — |
| 2 | Page verification loop (§2.1) | Turns browsing from "a page was fetched" into "an answer was found". | ~2 days |
| 3 | Source marking (§2.2) | Makes step 2 visible to the user; cheap once 2 exists. | ~1 day |
| 4 | Navigation recovery (§2.3) | The highest-risk area; needs 2's verification pattern to reuse. | ~3 days |
| 5 | Definition of done (§2.4) | Needs 3 and 4 to have something to check against. | ~2 days |

Steps 2 and 3 together are what make browsing trustworthy. Steps 4 and 5 together are what make
handing over a real task safe. Neither pair is useful alone.

---

## 4. What "reliable" means, concretely

A task is reliable when, across ten runs of the same request:

- it completes at least eight times;
- when it fails, it says so, and the reason is true;
- it never claims success it can't verify;
- it never spends money, sends a message or deletes anything without asking.

That last one is already partly true — `Pointer.risky()` catches labels that spend, send or delete.
The gap is that it checks the *label*, not the *effect*: a button labelled "Continue" on a checkout
page is risky and isn't caught. Extending the check to the screen's context, not just the label, is
part of step 4.

---

## 5. What this plan deliberately does not do

- **No new agent framework.** The existing loop is adequate; the failures are in verification, not
  in orchestration.
- **No model upgrade as a fix.** A better model makes the same unverified loop fail more
  articulately.
- **No new permissions.** Everything above runs inside the accessibility service and the existing
  network path.
- **No release work.** This is a debug-build plan; signing and distribution are out of scope.

---

## 6. The first thing to build

Step 2, the page verification loop, because it is the smallest change that converts the largest
number of current failures into either a correct answer or an honest "I couldn't find it". It needs
no new permissions, no UI, and it is measurable on day one with a 50-question set.

`BrowseIntent` on this branch already exposes the two pieces it needs: the classification, and the
page-relevance filter that decides which candidates are worth verifying at all.
