# Codespace for ARGUS (no PC needed)

Open the GitHub mirror on your phone, then Code › Codespaces › Create codespace on main. Pick a 4-core machine (Gradle needs the memory; it uses free hours twice as fast as 2-core).

- **Desktop:** when it's ready, open the Ports tab and tap port 6080 (Desktop). It opens a Linux desktop in the browser through noVNC. Password: `vscode`. Tap "Connect", then use "Scaling: Remote resizing" in the side panel so it fits the phone.
- **Build:** in the terminal, `./gradlew assembleDebug` (only when the owner says build). The APK lands in `app/build/outputs/apk/`.
- **Tests:** `./gradlew testDebugUnitTest`.
- Stop the Codespace when you're done (it auto-stops after 30 idle minutes) so free hours last.
