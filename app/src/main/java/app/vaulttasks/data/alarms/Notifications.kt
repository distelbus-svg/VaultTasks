package app.vaulttasks.data.alarms

import android.Manifest
import android.annotation.SuppressLint
import android.app.NotificationChannel
import android.app.NotificationManager
import android.content.Context
import android.content.pm.PackageManager
import androidx.core.app.NotificationCompat
import androidx.core.app.NotificationManagerCompat
import androidx.core.content.ContextCompat
import app.vaulttasks.R
import app.vaulttasks.domain.alarms.AlarmSpec
import java.time.format.DateTimeFormatter
import java.time.format.FormatStyle

object Notifications {
    const val CHANNEL_ID = "task_reminders"

    private val dateFmt = DateTimeFormatter.ofLocalizedDate(FormatStyle.MEDIUM)
    private val timeFmt = DateTimeFormatter.ofLocalizedTime(FormatStyle.SHORT)

    /** Spec §8.4: "Task reminders", high importance. Creating an existing channel is a no-op. */
    fun ensureChannel(context: Context) {
        val nm = context.getSystemService(NotificationManager::class.java)
        nm.createNotificationChannel(
            NotificationChannel(CHANNEL_ID, "Task reminders", NotificationManager.IMPORTANCE_HIGH).apply {
                description = "Reminders at the due time of your tasks"
            },
        )
    }

    fun permissionGranted(context: Context): Boolean =
        ContextCompat.checkSelfPermission(context, Manifest.permission.POST_NOTIFICATIONS) == PackageManager.PERMISSION_GRANTED

    fun cancel(context: Context, id: Int) = NotificationManagerCompat.from(context).cancel(id)

    /** Returns false if it could not be posted (permission missing), so the diagnostics log can say so. */
    @SuppressLint("MissingPermission") // checked via permissionGranted
    fun showReminder(context: Context, spec: AlarmSpec): Boolean {
        if (!permissionGranted(context)) return false
        ensureChannel(context)
        val due = spec.dueDate?.let { d ->
            d.format(dateFmt) + (spec.dueTime?.let { " " + it.format(timeFmt) } ?: "")
        }
        val text = listOfNotNull(due, spec.spaceName.takeIf { it.isNotEmpty() }).joinToString(" · ")
        val n = NotificationCompat.Builder(context, CHANNEL_ID)
            .setSmallIcon(R.drawable.ic_stat_task)
            .setContentTitle(spec.title)
            .setContentText(text)
            .setCategory(NotificationCompat.CATEGORY_REMINDER)
            .setPriority(NotificationCompat.PRIORITY_HIGH)
            .setAutoCancel(true)
            .setContentIntent(AlarmIntents.openPending(context, spec))
            .addAction(0, "Done", AlarmIntents.donePending(context, spec))
            .addAction(0, "10 min", AlarmIntents.snoozePending(context, spec, 10))
            .addAction(0, "1 h", AlarmIntents.snoozePending(context, spec, 60))
            .build()
        NotificationManagerCompat.from(context).notify(spec.code, n)
        return true
    }

    /** Replaces the reminder with a short message when a notification action could not do its job. */
    @SuppressLint("MissingPermission")
    fun showProblem(context: Context, spec: AlarmSpec, message: String) {
        if (!permissionGranted(context)) return
        ensureChannel(context)
        val n = NotificationCompat.Builder(context, CHANNEL_ID)
            .setSmallIcon(R.drawable.ic_stat_task)
            .setContentTitle(spec.title)
            .setContentText(message)
            .setAutoCancel(true)
            .setContentIntent(AlarmIntents.openPending(context, spec))
            .build()
        NotificationManagerCompat.from(context).notify(spec.code, n)
    }
}
