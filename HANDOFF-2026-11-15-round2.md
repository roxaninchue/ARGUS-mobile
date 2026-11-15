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

To do next (Living UI, owner screenshots 08:56/08:58 + mockups 1000177232/1000177234):
1. `LivingScreen`: add `.clipToBounds()` on the root BoxWithConstraints (cards draw over the top bar and the Chat|Living
   switch today). Keep cards clear of the pill.
2. `BrowserCard`: draw the picture (`LivingShots.shots` + `LivingPage.pick(refs, card.url, card.at, nextBrowserCard.at)`),
   else a masthead (site icon `LivingShots.icon(host)` + site name) with title and lead text, like the mockups;
   "Reading…" chip while running.
3. `LivingLayout`: two columns like the mockups: left = prompt then page cards stacked with down arrows; right = answer
   (headline style), files, steps as a checklist; connector from prompt to right column and from page chain to answer.
   Update LivingLayoutTest (old tests assert the side-by-side row layout and TRUNK_X).
4. Bump `LivingSpec.VERSION` to 0.2.0 (LivingLayoutTest checks it), CHANGELOG, then one push without `[skip ci]`.

Verification used: kotlinc 2.0.20 on the pure files + JUnit (all new tests pass). Full-module type-check baseline:
`/workspace/work/tc.sh` (base = 9216 error lines from missing libs; compare error sets, not counts).

Gateway (separate ask): Apinex free models are out of rotation because Apinex now needs a daily check-in
(402 "Daily check-in required… apinex.bond/airdrop?tab=quests"). The gateway classes it as no_balance/provider-wide,
so the provider rests and probes back off. Fix ideas: check in daily (owner), or treat that 402 as a short
`checkin` cooldown (15 min) instead of no_balance in `gateway/src/router.ts` + `failure.ts` (Blaze-agent repo).
