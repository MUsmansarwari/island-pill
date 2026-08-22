package com.nahope.island.island.sources

import android.app.Notification
import android.app.PendingIntent
import android.app.Person
import android.content.Context
import android.graphics.Bitmap
import android.os.Build
import android.os.Bundle
import android.service.notification.StatusBarNotification
import android.util.Log
import androidx.core.graphics.drawable.toBitmap
import com.nahope.island.island.IslandEvent
import com.nahope.island.island.IslandRepository

/**
 * Calls, read off the dialer's own notification rather than out of the telephony stack.
 *
 * The obvious route is READ_PHONE_STATE, but it only ever answers RINGING / OFFHOOK / IDLE: no
 * caller name (that needs READ_CALL_LOG *and* READ_CONTACTS since Android 10), no avatar, and no
 * way to pick up. An InCallService gets all three, but only if the app becomes the default dialer,
 * which is far too much for an overlay to take over.
 *
 * The notification already carries the lot. CallStyle (API 31+) publishes the caller as a [Person],
 * the call type, and the answer / decline / hang-up PendingIntents. It costs no new permission —
 * the listener is already bound so media can read sessions — and it picks up WhatsApp, Telegram and
 * Signal calls, which telephony never sees at all.
 */
object CallSource {

    private const val TAG = "IslandCall"

    // CallStyle extras. Public constants only from API 31, and the three intents are @hide even
    // there, so the keys are spelled out. A missing key degrades to the action-title scan below.
    private const val EXTRA_TEMPLATE = "android.template"
    private const val EXTRA_CALL_TYPE = "android.callType"
    private const val EXTRA_CALL_PERSON = "android.callPerson"
    private const val EXTRA_CALL_IS_VIDEO = "android.callIsVideo"
    private const val EXTRA_ANSWER_INTENT = "android.answerIntent"
    private const val EXTRA_DECLINE_INTENT = "android.declineIntent"
    private const val EXTRA_HANG_UP_INTENT = "android.hangUpIntent"

    private const val CALL_TYPE_INCOMING = 1
    private const val CALL_TYPE_ONGOING = 2
    private const val CALL_TYPE_SCREENING = 3

    private val ANSWER_WORDS = listOf("answer", "accept", "pick up", "pickup")
    private val DECLINE_WORDS = listOf("decline", "reject", "dismiss", "ignore")
    private val HANG_UP_WORDS = listOf("hang up", "hangup", "end call", "end")

    /** Key of the notification we published from, so a stale removal cannot clear a live call. */
    private var activeKey: String? = null

    /** @return true if this was a call and the island now owns it, so the caller should stop here. */
    fun handle(context: Context, sbn: StatusBarNotification): Boolean {
        val n = sbn.notification
        if (!isCall(n)) {
            // Incoming calls always carry a full-screen intent. If one shows up that we did *not*
            // classify as a call, this line is where you find out why.
            if (n.fullScreenIntent != null) {
                Log.d(
                    TAG,
                    "not a call: " + sbn.packageName +
                        " category=" + n.category +
                        " template=" + n.extras.getString(EXTRA_TEMPLATE),
                )
            }
            return false
        }

        val extras = n.extras
        val state = stateOf(n)
        val person = personOf(extras)

        val name = person?.name?.toString()
            ?: extras.getCharSequence(Notification.EXTRA_TITLE)?.toString()
            ?: extras.getCharSequence(Notification.EXTRA_TEXT)?.toString()
            ?: appLabel(context, sbn.packageName)

        val answer = intentAt(extras, EXTRA_ANSWER_INTENT) ?: actionMatching(n, ANSWER_WORDS)
        val decline = intentAt(extras, EXTRA_DECLINE_INTENT) ?: actionMatching(n, DECLINE_WORDS)
        val hangUp = intentAt(extras, EXTRA_HANG_UP_INTENT) ?: actionMatching(n, HANG_UP_WORDS)

        Log.i(TAG, state.toString() + " from " + sbn.packageName + " - " + name)

        activeKey = sbn.key
        IslandRepository.publish(
            IslandEvent.Call(
                packageName = sbn.packageName,
                appName = appLabel(context, sbn.packageName),
                callerName = name,
                callerAvatar = avatarOf(context, person, extras),
                state = state,
                isVideo = extras.getBoolean(EXTRA_CALL_IS_VIDEO, false),
                // A dialer sets `when` to the moment the call connected and flips usesChronometer
                // so the shade can count up. While it is still ringing there is nothing to count.
                startedAtMs = n.`when`.takeIf { state == IslandEvent.Call.State.ACTIVE && it > 0L },
                answer = answer,
                decline = decline,
                hangUp = hangUp,
                open = n.contentIntent,
            )
        )
        return true
    }

    /** @return true if this removal belonged to the call we are showing. */
    fun handleRemoved(sbn: StatusBarNotification): Boolean {
        if (sbn.key != activeKey) return false
        activeKey = null
        Log.i(TAG, "CLEARED - " + sbn.packageName)
        IslandRepository.clear(IslandEvent.Call.ID)
        return true
    }

    /** Called when the listener unbinds — a call we can no longer track must not stay on screen. */
    fun reset() {
        if (activeKey == null) return
        activeKey = null
        IslandRepository.clear(IslandEvent.Call.ID)
    }

    private fun isCall(n: Notification): Boolean =
        n.category == Notification.CATEGORY_CALL ||
            n.extras.getString(EXTRA_TEMPLATE)?.endsWith("CallStyle") == true

    private fun stateOf(n: Notification): IslandEvent.Call.State =
        when (n.extras.getInt(EXTRA_CALL_TYPE, 0)) {
            CALL_TYPE_INCOMING, CALL_TYPE_SCREENING -> IslandEvent.Call.State.RINGING
            CALL_TYPE_ONGOING -> IslandEvent.Call.State.ACTIVE
            // Pre-31, or a dialer that rolled its own layout: a full-screen intent means the screen
            // is being taken over right now, which only happens while it is still ringing.
            else -> if (n.fullScreenIntent != null) {
                IslandEvent.Call.State.RINGING
            } else {
                IslandEvent.Call.State.ACTIVE
            }
        }

    private fun personOf(extras: Bundle): Person? {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.P) return null
        @Suppress("DEPRECATION")
        return extras.getParcelable(EXTRA_CALL_PERSON) as? Person
    }

    private fun avatarOf(context: Context, person: Person?, extras: Bundle): Bitmap? {
        val icon = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) person?.icon else null
        runCatching { icon?.loadDrawable(context)?.toBitmap() }.getOrNull()?.let { return it }

        // Dialers that predate CallStyle still put the contact photo in the large icon.
        @Suppress("DEPRECATION")
        return runCatching {
            when (val large = extras.get(Notification.EXTRA_LARGE_ICON)) {
                is Bitmap -> large
                is android.graphics.drawable.Icon -> large.loadDrawable(context)?.toBitmap()
                else -> null
            }
        }.getOrNull()
    }

    private fun intentAt(extras: Bundle, key: String): PendingIntent? {
        @Suppress("DEPRECATION")
        return extras.getParcelable(key) as? PendingIntent
    }

    /**
     * Last resort when the intent extras are absent: read the buttons the dialer drew. Matching on
     * label text is obviously language-bound, so a miss only costs the button, never the pill.
     */
    private fun actionMatching(n: Notification, words: List<String>): PendingIntent? =
        n.actions?.firstOrNull { action ->
            val title = action.title?.toString()?.lowercase().orEmpty()
            words.any { title.contains(it) }
        }?.actionIntent

    private fun appLabel(context: Context, pkg: String): String = try {
        val pm = context.packageManager
        pm.getApplicationLabel(pm.getApplicationInfo(pkg, 0)).toString()
    } catch (_: Exception) {
        pkg
    }
}

/** Fires one of the call's PendingIntents. A cancelled intent means the call already moved on. */
object CallCommands {
    fun fire(intent: PendingIntent?) {
        intent ?: return
        runCatching { intent.send() }
            .onFailure { Log.w("IslandCall", "call action intent could not be sent", it) }
    }
}
