package com.blaze.assistant.ui

import com.blaze.assistant.BrandName

// Owner 2026-11-15: moved out of Shell.kt (unchanged apart from the Scan label) so the + menu's entries are pure and the
// JVM tests (PlusMenuSpecTest, MenuIconsTest) build them without the Compose shell.

/**
 * What the + menu offers. [id] is handled by the screen. [tile] rows are the big buttons in the sheet's top strip
 * (Camera, Photos, Files, Call…); the rest are list rows under it.
 */
data class PlusItem(val id: String, val label: String, val sub: String = "", val on: Boolean = false, val tile: Boolean = false)

/**
 * Owner 2026-11-15 (Kimi-style + sheet): a strip of big tiles for what you attach or start, then a short list of rows.
 * No Voice row (the composer's mic is the voice button), no Views row and no Clear screen (removed everywhere).
 */
fun plusItems(work: String, approval: String, model: String, hasClipImage: Boolean, inProject: Boolean = false, hasCamera: Boolean = true): List<PlusItem> = buildList {
    // v0.28.x: camera entries only when the phone really has a camera (PackageManager.FEATURE_CAMERA_ANY).
    if (hasCamera) {
        add(PlusItem("camera", "Camera", "Take a photo", tile = true))
        // Owner 2026-11-15: "Scan", not "Argus Camera" (it was cut to "Argus C…" on a 360 dp phone); the sub line says what it does.
        add(PlusItem("blazecam", "Scan", "${BrandName.NAME} Camera: ask, read text, translate, scan", tile = true))
    }
    add(PlusItem("photos", "Photos", "Pick pictures", tile = true))
    add(PlusItem("files", "Files", "Photos, PDFs, text", tile = true))
    add(PlusItem("call", "Call", "Hands-free voice call with ${BrandName.NAME}", tile = true))
    if (hasClipImage) add(PlusItem("paste", "Paste image", "The picture on your clipboard"))
    add(PlusItem("apps", "Apps & connectors", "Connect apps so ${BrandName.NAME} can act in them"))
    add(PlusItem("skills", "Skills", "Reusable skills for specific tasks"))
    add(PlusItem("atfile", "@ files", "Mention a file from ${BrandName.NAME}'s home"))
    add(PlusItem("plan", if (work == "plan") "Plan mode" else "Build mode", if (work == "plan") "Read and look only \u00b7 tap for Build" else "Full access \u00b7 tap for Plan", on = work == "plan"))
    // Unreleased (owner): no Auto / Autopilot row; ARGUS is always on Autopilot.
    // v0.25.0: no Model row; Argus Cloud picks the model for every message.
    if (inProject) add(PlusItem("new", "New chat", "A new thread in this project"))
}
