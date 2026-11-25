package com.zhuli.core

import android.Manifest
import android.content.ContentUris
import android.content.ContentValues
import android.content.Context
import android.content.pm.PackageManager
import android.provider.CalendarContract
import com.zhuli.core.ZhuliLog as Log
import java.util.TimeZone

/**
 * 系统日历双写（需求 C4，默认开启、可在壳里关掉）。
 *
 * 为什么双写：助理自己的库只服务助理；写进系统日历后，手表、平板、别的日历 App 都能看到，
 * 换机也不至于全丢。写的时候顺带在事件上加「提前 10 分钟」提醒，双保险。
 *
 * 只写不改：全程不删别人的事件，自己写的也只在自己的 [Reminder.calendarEventId] 记着。
 *
 * v0.9.1：描述里带 [MARKER] 标记 —— 万一哪天又出现「日历通知回来变成提醒」的回声，
 * 靠它能一眼认出这条日程是我们自己写的，而不是用户真排的。
 */
object CalendarWriter {

    private const val TAG = "zhuli.calendar"
    private const val EVENT_MINUTES = 30L
    private const val ALARM_MINUTES = 10
    /** 自建日程的指纹，写在 DESCRIPTION 里 */
    const val MARKER = "（由手机助理自动写入）"

    fun canWrite(ctx: Context): Boolean =
        ctx.checkSelfPermission(Manifest.permission.WRITE_CALENDAR) == PackageManager.PERMISSION_GRANTED &&
            ctx.checkSelfPermission(Manifest.permission.READ_CALENDAR) == PackageManager.PERMISSION_GRANTED

    /** 写一条日程，返回 eventId；没权限/没日历/已写过则返回 0。 */
    fun write(ctx: Context, rem: Reminder): Long {
        if (!canWrite(ctx)) return 0
        if (rem.calendarEventId > 0) return 0

        val calId = writableCalendarId(ctx) ?: run {
            Log.w(TAG, "no writable calendar")
            return 0
        }

        val start = rem.dueAt
        val end = start + EVENT_MINUTES * 60_000L
        val values = ContentValues().apply {
            put(CalendarContract.Events.CALENDAR_ID, calId)
            put(CalendarContract.Events.TITLE, rem.title)
            put(CalendarContract.Events.DESCRIPTION, "${rem.body}\n\n$MARKER")
            put(CalendarContract.Events.DTSTART, start)
            put(CalendarContract.Events.DTEND, end)
            put(CalendarContract.Events.EVENT_TIMEZONE, TimeZone.getDefault().id)
            put(CalendarContract.Events.HAS_ALARM, 1)
        }
        return try {
            val uri = ctx.contentResolver.insert(CalendarContract.Events.CONTENT_URI, values)
                ?: return 0
            val eventId = ContentUris.parseId(uri)
            // 事件级提醒（提前 10 分钟），与本 App 的准点提醒互补
            try {
                ctx.contentResolver.insert(
                    CalendarContract.Reminders.CONTENT_URI,
                    ContentValues().apply {
                        put(CalendarContract.Reminders.EVENT_ID, eventId)
                        put(CalendarContract.Reminders.MINUTES, ALARM_MINUTES)
                        put(CalendarContract.Reminders.METHOD, CalendarContract.Reminders.METHOD_ALERT)
                    }
                )
            } catch (t: Throwable) {
                Log.w(TAG, "insert reminder failed (event kept)", t)
            }
            Log.i(TAG, "event written id=$eventId")
            eventId
        } catch (t: Throwable) {
            Log.e(TAG, "write event failed", t)
            0
        }
    }

    /** 完成提醒时把自己写的那条日程删掉（别人的一律不碰）。 */
    fun remove(ctx: Context, rem: Reminder) {
        if (rem.calendarEventId <= 0) return
        if (!canWrite(ctx)) return
        try {
            val uri = ContentUris.withAppendedId(CalendarContract.Events.CONTENT_URI, rem.calendarEventId)
            ctx.contentResolver.delete(uri, null, null)
            Log.i(TAG, "event removed id=${rem.calendarEventId}")
        } catch (t: Throwable) {
            Log.w(TAG, "remove event failed", t)
        }
    }

    /** 找一个能写的日历：优先主日历，其次任一可写日历。 */
    private fun writableCalendarId(ctx: Context): Long? {
        val projection = arrayOf(
            CalendarContract.Calendars._ID,
            CalendarContract.Calendars.CALENDAR_ACCESS_LEVEL,
            CalendarContract.Calendars.IS_PRIMARY
        )
        val selection = "${CalendarContract.Calendars.VISIBLE} = 1 AND " +
            "${CalendarContract.Calendars.SYNC_EVENTS} = 1"
        return try {
            ctx.contentResolver.query(
                CalendarContract.Calendars.CONTENT_URI, projection, selection, null,
                "${CalendarContract.Calendars.IS_PRIMARY} DESC"
            )?.use { c ->
                while (c.moveToNext()) {
                    val id = c.getLong(0)
                    val access = c.getInt(1)
                    if (access >= CalendarContract.Calendars.CAL_ACCESS_CONTRIBUTOR) return id
                }
                null
            }
        } catch (t: Throwable) {
            Log.w(TAG, "query calendars failed", t)
            null
        }
    }
}
