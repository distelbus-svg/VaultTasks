package app.vaulttasks.data.alarms

import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.net.Uri
import app.vaulttasks.domain.TaskId
import app.vaulttasks.domain.alarms.AlarmKind
import app.vaulttasks.domain.alarms.AlarmSpec
import java.time.LocalDate
import java.time.LocalDateTime
import java.time.LocalTime

/**
 * Intent plumbing shared by the scheduler, the notification and the receivers. An alarm intent carries the whole
 * [AlarmSpec] as extras, so firing needs no database, no vault access and no live process.
 * PendingIntent identity is (component, action, data URI); extras are ignored, which is what lets [cancelFor] work
 * without knowing the original extras.
 */
object AlarmIntents {
    const val ACTION_FIRE = "app.vaulttasks.action.FIRE"
    const val ACTION_DONE = "app.vaulttasks.action.DONE"
    const val ACTION_SNOOZE = "app.vaulttasks.action.SNOOZE"
    const val EXTRA_SNOOZE_MINUTES = "snooze_minutes"

    private const val X_CODE = "code"
    private const val X_KIND = "kind"
    private const val X_PATH = "path"
    private const val X_TEXT = "text"
    private const val X_OCC = "occ"
    private const val X_AT = "at"
    private const val X_TITLE = "title"
    private const val X_DATE = "date"
    private const val X_TIME = "time"
    private const val X_SPACE = "space"

    private const val IMMUTABLE_UPDATE = PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT

    fun put(intent: Intent, s: AlarmSpec): Intent = intent
        .putExtra(X_CODE, s.code)
        .putExtra(X_KIND, s.kind.name)
        .putExtra(X_PATH, s.id.path)
        .putExtra(X_TEXT, s.id.normalizedText)
        .putExtra(X_OCC, s.id.occurrence)
        .putExtra(X_AT, s.fireAt.toString())
        .putExtra(X_TITLE, s.title)
        .putExtra(X_DATE, s.dueDate?.toString())
        .putExtra(X_TIME, s.dueTime?.toString())
        .putExtra(X_SPACE, s.spaceName)

    /** Null if the intent does not carry a complete spec (e.g. a stale or foreign intent). */
    fun read(intent: Intent): AlarmSpec? = runCatching {
        AlarmSpec(
            code = intent.getIntExtra(X_CODE, 0),
            kind = AlarmKind.valueOf(intent.getStringExtra(X_KIND) ?: return null),
            id = readId(intent) ?: return null,
            fireAt = LocalDateTime.parse(intent.getStringExtra(X_AT) ?: return null),
            title = intent.getStringExtra(X_TITLE).orEmpty(),
            dueDate = intent.getStringExtra(X_DATE)?.let(LocalDate::parse),
            dueTime = intent.getStringExtra(X_TIME)?.let(LocalTime::parse),
            spaceName = intent.getStringExtra(X_SPACE).orEmpty(),
        )
    }.getOrNull()

    /** Only the task identity, used by "open this task" intents. */
    fun readId(intent: Intent): TaskId? {
        val path = intent.getStringExtra(X_PATH) ?: return null
        val text = intent.getStringExtra(X_TEXT) ?: return null
        return TaskId(path, text, intent.getIntExtra(X_OCC, 0))
    }

    private fun base(context: Context, action: String, path: String, code: Int): Intent =
        Intent(action, Uri.parse("vaulttasks://$path/$code"))
            .setClass(context, AlarmReceiver::class.java)

    fun firePending(context: Context, s: AlarmSpec): PendingIntent = PendingIntent.getBroadcast(
        context, s.code, put(base(context, ACTION_FIRE, "alarm", s.code), s), IMMUTABLE_UPDATE,
    )

    /** The pending alarm for [code], if the system still holds one. */
    fun cancelFor(context: Context, code: Int): PendingIntent? = PendingIntent.getBroadcast(
        context, code, base(context, ACTION_FIRE, "alarm", code), PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_NO_CREATE,
    )

    fun donePending(context: Context, s: AlarmSpec): PendingIntent = PendingIntent.getBroadcast(
        context, s.code, put(base(context, ACTION_DONE, "done", s.code), s), IMMUTABLE_UPDATE,
    )

    fun snoozePending(context: Context, s: AlarmSpec, minutes: Long): PendingIntent = PendingIntent.getBroadcast(
        context,
        s.code,
        put(base(context, ACTION_SNOOZE, "snooze/$minutes", s.code), s).putExtra(EXTRA_SNOOZE_MINUTES, minutes),
        IMMUTABLE_UPDATE,
    )

    /** Tapping the notification: opens the task's edit sheet. */
    fun openPending(context: Context, s: AlarmSpec): PendingIntent {
        val open = Intent(context, app.vaulttasks.ui.MainActivity::class.java)
            .setAction(Intent.ACTION_VIEW)
            .setData(Uri.parse("vaulttasks://open/${s.code}"))
            .setFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_SINGLE_TOP)
            .putExtra(X_PATH, s.id.path)
            .putExtra(X_TEXT, s.id.normalizedText)
            .putExtra(X_OCC, s.id.occurrence)
        return PendingIntent.getActivity(context, s.code, open, IMMUTABLE_UPDATE)
    }
}
