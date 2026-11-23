# Argus as a PC replacement

Owner goal (Finn, 2026-11-23): **make Argus a replacement for his PC.** Plug the phone into a monitor or TV, or cast to
one, and work the way he would on a desktop: windows, keyboard and mouse, real files, a code editor, office documents,
a browser with downloads. What the phone cannot do itself runs on his PC or a cloud box and shows up in a window.

This file is the plan and the record of decisions. Status of each phase is in [STATUS.md](../STATUS.md).

## 1. What we are building

### a. Argus Desk: a native desktop on the second screen
- When a monitor, TV or cast screen connects, Argus opens **Argus Desk** on that display: a native Jetpack Compose
  desktop with a taskbar, a launcher, and resizable, movable windows.
- Built on Android's own pieces: `DisplayManager` display listeners, `Presentation` (or an activity launched on the
  secondary display with `ActivityOptions.setLaunchDisplayId`) for the desk itself, and **Android 16 desktop windowing**
  where the phone has it (freeform windows for Argus's own activities). On phones without it, Argus Desk draws its own
  window manager inside one full-screen Compose surface.
- The phone becomes the input device while the desk is up: a full-screen **touchpad** (move, tap, two-finger scroll,
  right-click by two-finger tap) and an on-screen **keyboard** that types into the focused desk window. A real
  Bluetooth or USB keyboard and mouse always win when connected.
- **Not** the old VNC / XFCE Linux desktop. That path was removed and stays removed: no X server, no VNC client, no
  Linux desktop environment. Linux stays where it is useful, in the Alpine terminal.

### b. Keyboard and mouse first
Everything on the desk must work without touching the phone:
- Shortcuts: Alt+Tab (switch windows), Ctrl+C / Ctrl+V / Ctrl+X / Ctrl+Z, Ctrl+W (close), Ctrl+T (new tab),
  Meta (open the launcher), Meta+arrows (snap left / right / maximise), Ctrl+F (find).
- Right-click context menus everywhere a long-press menu exists today.
- Hover states and tooltips, a pointer that changes over links, text and window edges.
- Mouse-wheel and two-finger scrolling with the right speed; horizontal scroll with Shift.
- Drag and drop between windows (files into the editor, a picture into the chat, a link into the browser).

### c. Desktop-class apps, as desk windows
- **File manager**: tabs, list and grid views, copy / move / rename across tabs, zip and unzip, the SD card and USB
  drives, and network drives (SMB / WebDAV / SFTP). Built on all-files access (`MANAGE_EXTERNAL_STORAGE`), which stays.
- **Code editor** beside the Alpine terminal: syntax highlighting, a file tree, search, git (status, diff, commit,
  push, pull) and a Run button that goes through the existing `run_code` tool.
- **Office documents**: open and edit Word (.docx) and Excel (.xlsx), open PDFs (view, search, annotate).
- **Browser in desktop mode**: Argus's built-in browser as a window with tabs, a desktop user agent, and a downloads
  list that lands in the file manager. The browser VPN (`wgproxy` / WARP) stays and works in desk windows.

### d. Heavy work offloaded, shown in a window
- Anything the phone is too small for (big builds, large models, Windows-only apps) runs on Finn's PC or a cloud box
  through the existing **Argus Desktop link** (`link/DesktopLink.kt`), and its screen or output shows in a desk window.
- The phone stays the controller: same keyboard, mouse and clipboard; files move through the file manager.

## 2. Phases

| Phase | What ships | Version |
|---|---|---|
| **0** | Slim-down (the removals below, one per branch / merge request) and a gated CI pipeline: every change goes through a merge request whose pipeline (compile + unit tests) must be green before it merges into `main` | 0.55.x patches |
| **1** | Argus Desk on the second display + keyboard and mouse (section 1a and 1b) | next minor (Finn's plan said 0.55.0; `main` had already reached 0.55.10 when this was written, so this is 0.56.0) |
| **2** | File manager and code editor as desk windows (section 1c, first half) | following minor |
| **3** | Office documents (Word / Excel / PDF) and the remote PC window over Argus Desktop link (1c second half, 1d) | following minor |

## 3. Phase 0 decisions (Finn, 2026-11-23)

### Remove (one removal per branch and merge request, in this order)
1. **Phone control**: the AccessibilityService in `phone/` ("Argus phone control", `BIND_ACCESSIBILITY_SERVICE`,
   `res/xml/accessibility_config.xml`), its read-screen / tap / type tools, Magic Hand (`phone/MagicHand*.kt`), and the
   FastActions / SettingsJump paths that depend on it. Settings toggles, setup steps, prompt lines and skills that
   mention it go too, with a one-time clean-up of any keys it leaves behind.
2. **SMS and calls**: `READ_SMS`, `SEND_SMS`, `READ_CALL_LOG`, `CALL_PHONE`, `READ_PHONE_STATE`, `ANSWER_PHONE_CALLS`
   and the messaging / call tools.
3. **Overlays**: the floating orb and `SYSTEM_ALERT_WINDOW`.
4. **Usage stats**: `PACKAGE_USAGE_STATS`.
5. **Secure-settings hooks**: `WRITE_SECURE_SETTINGS` and the related protected-settings code.
6. **Full-screen intents**: `USE_FULL_SCREEN_INTENT`.
7. **The in-app app store** ("Argus Store"): the `appstore/` package and the root `store/` catalog folder
   (`store/catalog.json` and its scripts).

What "the store" is, checked 2026-11-23, so the right thing goes:
- `store/` (repo root) = the in-app app store's catalog. **Remove** with removal 7.
- `app/.../appstore/` = the in-app app store's code. **Remove** with removal 7.
- `storeapi/` (Gradle module) = the source interface for the **Music and Video stores** and the YouTube plug-in
  (`mediaapps/store/StoreApi.kt`). **Keep.**
- `app/.../store/` (Kotlin package) = the app's database (`AndroidSql.kt` and the Db class). **Keep**; it is not a store.

### Keep
- The YouTube plug-in (`:youtube`, optional and private), Movies / Video and Music.
- The browser VPN (`wgproxy`, WARP).
- All-files access (`MANAGE_EXTERNAL_STORAGE`): the file manager needs it.
- The auto-updater (`update/`).

## 4. How changes land from now on

- Every change goes on its own branch, then a merge request into `main`. The GitLab pipeline (`.gitlab-ci.yml`)
  compiles the app and runs `:app:testDebugUnitTest`; the merge request merges only when it is green. Whether the
  pipeline can run right now (runner minutes, project settings) is recorded in STATUS.md "Current state".
- No `[skip ci]` on these branches: the point is that the pipeline runs.
- One removal per merge request; each removal is a patch version with CHANGELOG and STATUS entries.
- The full build procedure is in [BUILDING.md](../BUILDING.md); the current CI state is in STATUS.md.
