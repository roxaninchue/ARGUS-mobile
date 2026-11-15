# Handoff: 0.53.4 round 2 (15 Nov 2026, stopped by owner)

Shipped and building: `5bc24c44` Build 0.53.4 (versionCode 196) = All files access card, GitHub through the connection,
Music/Video Import, no mode swiping. CI run: Build APK #61 on roxaninchue/ARGUS-mobile.

Pushed WIP, NOT built (`2ea1a89f`, `[skip ci]`), on main:
- Done: turn fold over all tool rows (`ui/team/thread/TurnFlow.kt` FoldRow, `ChatToolRows.folds/foldTitle/sameRow`; open while
  live, folded when done; same-label rows merge). Test: ChatToolRowsTest.sameLabelsShareARowAndClustersFold.
- Done: attach any file (`agent/AnyFile.kt`, `Attachments.load` else-branch copies to `~/attachments/` and lists zip entries;
  picker `*/*`; manifest SEND `*/*`). Test: AnyFileTest.
- Started, Living UI 0.2.0:
  - Done: `LivingModel.pageTools` (only open/read/fetch/show make page cards; click/type/scroll go to the steps log),
    `browser_read` with no url updates the open page's card, `LivingPage.read` cleans titles/text.
  - Done, not drawn yet: `LivingShots` (page pictures) + `BrowserEngine.agentShot()` called after browser steps in
    `BrowserTools.run`; `LivingPage.pick` chooses a card's picture by time window + URL.

Living UI 0.2.0, DONE (15 Nov 2026) in `a960c3bc`, pushed with `[skip ci]`: **pushed, not built; awaiting owner go-ahead to build**
(owner screenshots 08:55–08:58, mockups 1000177232/1000177233/1000177234):
1. Done: `LivingScreen` root BoxWithConstraints has `.clipToBounds()`: cards no longer draw over the top bar or the
   Chat | Living switch / composer (those are siblings below the chat pane's `Box(weight(1f))` in ChatScreen). Follow keeps
   24 dp above the bottom edge; the status pill ("Done · N steps", "Next →") stays on top of the cards.
2. Done: `BrowserCard` like the mockup: "Browsing"/"Searching" header with the live dot, a "Reading…" chip while running,
   then a small browser frame (tab: site icon `LivingShots.icon(host)` + page title; address bar: the host only) holding
   the page picture (`LivingShots.shots` + `LivingPage.pick(refs, card.url, card.at, until)`, until = the next page card
   or the next prompt), else a masthead (icon + site name, title, `LivingPage.lead`). No raw URLs or tool words.
   `LivingPage.read` fix: with no tool header the title line is no longer repeated in the body (LivingModelTest was red).
3. Done: `LivingLayout` two columns: left = prompt then page cards stacked with down arrows (`Link.arrow`); right = the
   answer first when the turn ended on it (`headline()`: first sentence, 17 sp semibold), files, steps as a checklist
   (`$ cmd` with its first result line under a green check, `result()`, `RESULT_H`); connectors prompt → right column
   and last page → answer through `GUTTER_X`. `follow()` centres the active card's column at reading zoom, the other
   column peeking in from the side (mockup). LivingLayoutTest rewritten (no TRUNK_X / side-by-side row); new LivingPageTest.
4. Done: `LivingSpec.VERSION` = "0.2.0", CHANGELOG entry. NOT built (owner: no build without the go-ahead). The next
   build push must drop `[skip ci]` (or re-run Build APK) once the owner says go.

Check on the phone after the build: pictures appear on browser cards after a browser step (fetch_page cards get the
masthead); icons load (Google favicon service); the two columns at 1x on a ~400 dp screen (one column fills the width);
nothing draws over the top bar / switch while pinching; arrowheads and the gutter connectors line up.

Verification used: kotlinc 2.0.20 on the pure files + JUnit (all new tests pass). Full-module type-check baseline:
`/workspace/work/tc.sh` (base = 9216 error lines from missing libs; compare error sets, not counts).

Gateway (separate ask): Apinex free models are out of rotation because Apinex now needs a daily check-in
(402 "Daily check-in required… apinex.bond/airdrop?tab=quests"). The gateway classes it as no_balance/provider-wide,
so the provider rests and probes back off. Fix ideas: check in daily (owner), or treat that 402 as a short
`checkin` cooldown (15 min) instead of no_balance in `gateway/src/router.ts` + `failure.ts` (Blaze-agent repo).
