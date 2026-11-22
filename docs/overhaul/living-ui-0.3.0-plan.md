# Living UI 0.3.0 plan

Status: proposed 2026-11-22, not started. No APK until Finn says build.
Goal: get Living close to Finn's Zephium-style mockups (status page task: prompt and browser chain on the left, summary, comparison table, chart and checklist on the right, frosted pill with Next).

## Today (Living UI 0.2.4)
- One vertical tree per turn (newest 3 turns, up to 48 cards).
- Cards: prompt, browser (one per tab, updated in place, glass view), file, steps log, answer, and result cards (weather, place, alarm, event, image, table).
- Status pill with Next; placeholder agent dot.

## Plan
1. **Two columns.** Work on the left (prompt, then browser cards as a numbered chain with page thumbnails). Results on the right (summary, table, chart, checklist, notes). Elbow connectors between them. `LivingLayout`.
2. **Task header.** Task title with a dropdown of recent tasks. Pill reads "Gatus is up, with 4 tasks · Next ^" and opens the task list.
3. **`show_card` tool.** The model puts a card on the canvas itself: summary, comparison table, chart, checklist or sticky note. Nothing parses the user's words; the model decides. Rides every lean turn (`LeanTurn.CORE`).
4. **New cards** (`ResultCards` + `LivingScreen`):
   - Summary with 2 to 4 big stats (4/4, 30 s, 2)
   - Bar chart with legend dots
   - Checklist ("Before it goes live"); ticking a box tells the agent
   - Pale-yellow sticky notes for standing rules, routines and alerts
   - Comparison table with logos and the pick's column highlighted
5. **Browser chain.** Every card gets a real thumbnail (`LivingShots`), a step badge and the agent orb. After 3 cards, older ones collapse to dots.
6. **Terminal card.** Monospace, green ✓, status codes (200 OK) highlighted.
7. **Tests and shots.** LivingModelTest, LivingLayoutTest, ResultCardsTest plus new cases; Robolectric shots in light and dark.

## Rules that hold
- Living does not replace Chat; Chat stays the default.
- No trigger words or text routing.
- Nothing added inside `Agent.runLoop`.
- Major change, so the version becomes Living UI 0.3.0 (`LivingSpec.VERSION`).

## Suggested order
Steps 1 and 3 first (layout + show_card), then 4, 5, 6, then 2 and 7.
