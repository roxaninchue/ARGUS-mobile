# UI rules (Finn)

Standing rules for how ARGUS screens behave. Each one is Finn's word and has a test that reads the source; change the
rule here first, with Finn, before changing the code or the test.

| Rule | Since | Where | Guarded by |
|---|---|---|---|
| The model selector sheet closes **only with its X** (system Back too). Never from a swipe, a drag, a list pulled past its top, or a tap on the scrim. | 2026-11-24, 0.55.12 | `ArgusSheet(dragToClose = false)` in `ui/argus/kit/KitOverlays.kt`, used by `ModelSheet` | `SheetScrollTest` |
| A list inside a sheet scrolls on a **slow** drag as well as a fast one, and its rows stay tappable. Nothing above a list may consume pointer moves before touch slop. | 2026-11-23 / 24, 0.55.12 | `holdSheet` in `KitOverlays.kt` | `SheetScrollTest` |
| The intro animation **fills the screen**: no letterbox, no black bars. The clip sits on its own deep blue and its edges fade into it; the logo is never cut. | 2026-11-24, 0.55.12 | `IntroStep` in `ui/space/SpaceIntro.kt`, `IntroClip` in `SpaceLogic.kt` | `OnboardingLandscapeTest`, `SpaceLogicTest` |

How the sheet rule is kept: Material's `ModalBottomSheet` (material3 1.3) has its own drag on the sheet surface that
cannot be switched off, so a no-drag `ArgusSheet` (1) vetoes the Hidden state unless its X asked for it, (2) leaves no
pixel outside the hold (no handle strip, no inset band: the insets are padded inside it), and (3) holds the sheet with a
do-nothing nested draggable that loses to any list under the finger, and that also starts at once while the sheet opens
or closes, the only time Material's drag does.
