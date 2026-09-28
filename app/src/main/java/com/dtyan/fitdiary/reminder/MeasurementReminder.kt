package com.dtyan.fitdiary.reminder

import android.Manifest
import android.app.AlarmManager
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.media.AudioAttributes
import android.media.RingtoneManager
import android.os.Build
import androidx.core.app.NotificationCompat
import androidx.core.app.NotificationManagerCompat
import androidx.core.content.ContextCompat
import com.dtyan.fitdiary.MainActivity
import com.dtyan.fitdiary.appContainer
import com.dtyan.fitdiary.domain.measurementReminderDue
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import java.time.LocalDate
import java.time.LocalTime
import java.time.ZoneId

/**
 * Напоминание «давно не было замеров»: раз в сутки в заданный час будильник (AlarmManager)
 * будит [MeasurementReminderReceiver], тот сверяет дату последнего замера с порогом
 * и при необходимости показывает уведомление со звуком. Без сторонних зависимостей.
 */
object MeasurementReminder {

    const val CHANNEL_ID = "measurement_reminder"
    const val NOTIFICATION_ID = 7031
    const val EXTRA_OPEN_TAB = "open_tab"
    const val EXTRA_ATHLETE_ID = "athlete_id"
    const val TAB_MEASUREMENTS = "measurements"
    private const val REQUEST_CODE = 7031

    /** Канал с уровнем HIGH — звук и всплывающий баннер по умолчанию (пользователь может убавить в системе). */
    fun ensureChannel(context: Context) {
        val manager = context.getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
        if (manager.getNotificationChannel(CHANNEL_ID) != null) return
        val channel = NotificationChannel(CHANNEL_ID, "Напоминание о замерах", NotificationManager.IMPORTANCE_HIGH).apply {
            description = "Сообщает, когда замеры тела давно не записывались"
            enableVibration(true)
            setSound(
                RingtoneManager.getDefaultUri(RingtoneManager.TYPE_NOTIFICATION),
                AudioAttributes.Builder()
                    .setUsage(AudioAttributes.USAGE_NOTIFICATION)
                    .setContentType(AudioAttributes.CONTENT_TYPE_SONIFICATION)
                    .build(),
            )
        }
        manager.createNotificationChannel(channel)
    }

    fun hasNotificationPermission(context: Context): Boolean =
        Build.VERSION.SDK_INT < 33 ||
            ContextCompat.checkSelfPermission(context, Manifest.permission.POST_NOTIFICATIONS) == PackageManager.PERMISSION_GRANTED

    private fun alarmIntent(context: Context): PendingIntent = PendingIntent.getBroadcast(
        context,
        REQUEST_CODE,
        Intent(context, MeasurementReminderReceiver::class.java).setAction(MeasurementReminderReceiver.ACTION_CHECK),
        PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
    )

    /**
     * (Пере)ставит ежедневную проверку на ближайшие [hour]:00. Неточный будильник:
     * точность до нескольких минут нас устраивает, зато не нужны спецразрешения.
     */
    fun schedule(context: Context, enabled: Boolean, hour: Int) {
        val alarms = context.getSystemService(Context.ALARM_SERVICE) as AlarmManager
        val pending = alarmIntent(context)
        if (!enabled) {
            alarms.cancel(pending)
            return
        }
        val zone = ZoneId.systemDefault()
        var next = LocalDate.now(zone).atTime(LocalTime.of(hour.coerceIn(0, 23), 0)).atZone(zone)
        if (!next.toInstant().isAfter(java.time.Instant.now())) next = next.plusDays(1)
        alarms.setInexactRepeating(
            AlarmManager.RTC_WAKEUP,
            next.toInstant().toEpochMilli(),
            AlarmManager.INTERVAL_DAY,
            pending,
        )
    }

    /** Показывает уведомление (если разрешено). daysSilent — сколько дней без замеров. */
    fun notify(context: Context, daysSilent: Long, athleteId: Long = 1L, athleteName: String = "Я") {
        if (!hasNotificationPermission(context)) return
        ensureChannel(context)
        val open = PendingIntent.getActivity(
            context,
            REQUEST_CODE + athleteId.toInt(),
            Intent(context, MainActivity::class.java)
                .putExtra(EXTRA_OPEN_TAB, TAB_MEASUREMENTS)
                .putExtra(EXTRA_ATHLETE_ID, athleteId)
                .addFlags(Intent.FLAG_ACTIVITY_SINGLE_TOP or Intent.FLAG_ACTIVITY_CLEAR_TOP),
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
        )
        val text = if (daysSilent <= 0) "Пора снять мерки" else "Замеров не было $daysSilent ${pluralDays(daysSilent)} — пора снять мерки"
        val notification = NotificationCompat.Builder(context, CHANNEL_ID)
            .setSmallIcon(android.R.drawable.ic_menu_edit)
            .setContentTitle("Замеры тела · $athleteName")
            .setContentText(text)
            .setStyle(NotificationCompat.BigTextStyle().bigText(text))
            .setPriority(NotificationCompat.PRIORITY_HIGH)
            .setDefaults(NotificationCompat.DEFAULT_ALL)
            .setCategory(NotificationCompat.CATEGORY_REMINDER)
            .setAutoCancel(true)
            .setContentIntent(open)
            .build()
        try {
            NotificationManagerCompat.from(context).notify(NOTIFICATION_ID + athleteId.toInt(), notification)
        } catch (_: SecurityException) {
            // разрешение отозвали между проверкой и показом — молча пропускаем
        }
    }

    fun pluralDays(n: Long): String {
        val m10 = n % 10
        val m100 = n % 100
        return when {
            m10 == 1L && m100 != 11L -> "день"
            m10 in 2..4 && m100 !in 12..14 -> "дня"
            else -> "дней"
        }
    }
}

/**
 * Получатель будильника и перезагрузки. На BOOT_COMPLETED будильники сбрасываются системой,
 * поэтому переставляем; на ACTION_CHECK — сверяем дату последнего замера и уведомляем.
 */
class MeasurementReminderReceiver : BroadcastReceiver() {

    override fun onReceive(context: Context, intent: Intent) {
        val container = context.appContainer
        val athleteId = container.profiles.activeId.value
        val settings = container.settings.settingsFor(athleteId)
        when (intent.action) {
            Intent.ACTION_BOOT_COMPLETED, Intent.ACTION_MY_PACKAGE_REPLACED, Intent.ACTION_TIME_CHANGED ->
                MeasurementReminder.schedule(context, settings.measurementReminderEnabled, settings.measurementReminderHour)
            ACTION_CHECK -> {
                if (!settings.measurementReminderEnabled) return
                val result = goAsync()
                CoroutineScope(Dispatchers.IO).launch {
                    try {
                        val athlete = container.database.athleteDao().getById(athleteId)
                        if (athlete == null || athlete.isArchived) return@launch
                        val last = container.measurementRepository.lastEpochDay(athleteId)
                        val today = LocalDate.now().toEpochDay()
                        val due = measurementReminderDue(
                            lastEpochDay = last,
                            sinceEpochDay = settings.measurementReminderSinceDay,
                            todayEpochDay = today,
                            thresholdDays = settings.measurementReminderDays,
                        )
                        if (due) MeasurementReminder.notify(context, today - (last ?: settings.measurementReminderSinceDay), athleteId, athlete.name)
                    } finally {
                        result.finish()
                    }
                }
            }
        }
    }

    companion object {
        const val ACTION_CHECK = "com.dtyan.fitdiary.action.MEASUREMENT_REMINDER_CHECK"
    }
}
