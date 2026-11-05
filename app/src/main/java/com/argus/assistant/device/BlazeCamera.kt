package com.argus.assistant.device


import com.argus.assistant.BrandName
import android.app.ActivityManager
import android.content.Context
import android.content.Intent
import android.net.Uri
import com.argus.assistant.agent.Agent
import com.argus.assistant.agent.Attachments
import com.argus.assistant.agent.Settings
import com.argus.assistant.device.CameraModes.Mode
import com.argus.assistant.device.CameraModes.Origin
import com.argus.assistant.device.CameraModes.Route
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.MainScope
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.File
import java.util.Locale

/** Opening Blaze Camera and handing its result to the right place ([CameraModes.route]). */
object BlazeCamera {
    const val EXTRA_ORIGIN = "blaze_camera_origin"
    const val EXTRA_CHAT = "blaze_camera_chat"
    const val EXTRA_PROMPT = "blaze_camera_prompt"
    private const val LAST = "camera_mode"

    var lastMode: String
        get() = try { Settings.str(LAST, "") } catch (_: Throwable) { "" }
        set(v) { try { Settings.setStr(LAST, v) } catch (_: Throwable) { } }

    fun lowRam(ctx: Context): Boolean = try { ctx.getSystemService(ActivityManager::class.java)?.isLowRamDevice == true } catch (_: Exception) { false }

    fun intent(ctx: Context, mode: Mode?, origin: Origin, chatId: String? = null, prompt: String? = null): Intent =
        Intent(ctx, com.argus.assistant.ui.CameraActivity::class.java)
            .putExtra(com.argus.assistant.ui.CameraActivity.EXTRA_MODE, mode?.id)
            .putExtra(EXTRA_ORIGIN, origin.name)
            .putExtra(EXTRA_CHAT, chatId)
            .putExtra(EXTRA_PROMPT, prompt)

    /** Opens Blaze Camera. False (with the reason logged) when this phone has no camera or Android refused. */
    fun open(ctx: Context, mode: Mode?, origin: Origin, chatId: String? = null, prompt: String? = null): Boolean {
        if (!CameraSupport.hasCamera(ctx)) { CameraSupport.log("${BrandName.NAME} Camera: no camera on this device (FEATURE_CAMERA_ANY)"); return false }
        return try {
            ctx.startActivity(intent(ctx, mode, origin, chatId, prompt).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)); true
        } catch (e: Exception) { CameraSupport.log("${BrandName.NAME} Camera didn't open", e); false }
    }

    /** The phone language's English name, for "Translate this into …". */
    fun language(): String = try { Locale.getDefault().getDisplayLanguage(Locale.ENGLISH).ifBlank { "English" } } catch (_: Exception) { "English" }

    /**
     * Hands a capture over. [photo] is the upright ~1600 px JPEG (or null for a code), [doc] a scanned PDF / page,
     * [ocr] the words read on the phone, [code] a scanned code's value.
     */
    fun deliver(ctx: Context, mode: Mode, origin: Origin, chatId: String?, asked: String?,
                photo: File? = null, doc: Uri? = null, ocr: String? = null, code: String? = null, routeOverride: Route? = null) {
        val app = ctx.applicationContext
        val route = routeOverride ?: CameraModes.route(mode, origin)
        val effectiveMode = if (routeOverride == Route.SEND_TURN && mode == Mode.TEXT && asked == null && ocr != null) Mode.TRANSLATE else mode
        MainScope().launch {
            val atts = withContext(Dispatchers.IO) {
                listOfNotNull(
                    photo?.let { Attachments.load(app, Uri.fromFile(it), "image/jpeg") },
                    doc?.let { Attachments.load(app, it) })
            }
            val text = CameraModes.prompt(effectiveMode, asked, language()) + CameraModes.payload(effectiveMode, ocr, code)
            CameraSupport.log("${BrandName.NAME} Camera result: mode=${mode.id} origin=${origin.name} route=$route photo=${photo != null} doc=${doc != null} ocr=${ocr?.length ?: 0} code=${code != null}")
            when (route) {
                Route.SEND_TURN -> Agent.fromCamera(chatId, text.trim().ifEmpty { "Take a look at this." }, atts)
                Route.DRAFT -> { Agent.sharedDraft.value = Agent.SharedDraft(text.trim(), atts.firstOrNull()) }
                else -> return@launch
            }
            bringBlazeForward(app)
        }
    }

    fun bringBlazeForward(ctx: Context) {
        try { ctx.startActivity(Intent(ctx, com.argus.assistant.MainActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_REORDER_TO_FRONT)) }
        catch (e: Exception) { CameraSupport.log("couldn't bring ${BrandName.NAME} forward", e) }
    }

    /** For the model's blaze_camera tool and open_camera. */
    fun openFromTool(ctx: Context, modeArg: String?, chatId: String?, asked: String?): String {
        val mode = CameraModes.parse(modeArg) ?: Mode.ASK
        if (!CameraSupport.hasCamera(ctx)) return "This phone has no camera, so ${BrandName.NAME} Camera can't open. The user can attach a photo with + → Photos instead."
        val origin = if (chatId.isNullOrBlank()) Origin.STANDALONE else Origin.TOOL
        return if (open(ctx, mode, origin, chatId, asked)) "${BrandName.NAME} Camera is open in ${mode.label} mode. When the user takes the picture it comes back into this chat as their next message; wait for it and don't describe a picture you haven't seen."
        else "${BrandName.NAME} Camera couldn't open (it's in ${BrandName.NAME}'s log). Ask the user to attach a photo with + → Photos instead."
    }
}
