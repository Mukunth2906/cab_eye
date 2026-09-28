package com.cabeye.rider.dialogue

import com.cabeye.rider.auth.SpokenNumbers
import java.time.DayOfWeek
import java.time.LocalDate
import java.time.LocalTime
import java.time.ZoneId
import java.time.ZonedDateTime
import java.time.temporal.ChronoUnit
import java.time.temporal.TemporalAdjusters

/**
 * "tomorrow at 8 30", "in two hours", "5 pm", "on Monday morning at 9" → a moment in time.
 *
 * For scheduling the next journey by voice. Deliberately conservative: anything it cannot read
 * with confidence comes back null and the app asks again, because a ride booked for the wrong
 * time is worse than a second question. The result is always read back before it is saved.
 */
object SpokenTime {

    /** Scheduling further ahead than this is not a "next journey". */
    const val MAX_DAYS_AHEAD = 7L

    private val IN_RELATIVE = Regex("\\bin\\s+(.+?)\\s*(minutes?|mins?|hours?|hrs?)\\b")
    private val HALF_HOUR = Regex("\\bin\\s+(half an hour|half hour|30 minutes)\\b")
    private val AN_HOUR = Regex("\\bin\\s+(an|one|1)\\s+hour\\b")
    private val MORNING = Regex("\\b(am|a m|morning)\\b")
    private val EVENING = Regex("\\b(pm|p m|evening|night|tonight|afternoon)\\b")
    private val NOON = Regex("\\b(noon|midday)\\b")
    private val MIDNIGHT = Regex("\\bmidnight\\b")
    private val HALF_PAST = Regex("\\bhalf past\\b")
    private val QUARTER_PAST = Regex("\\bquarter past\\b")
    private val QUARTER_TO = Regex("\\bquarter to\\b")

    private val FILLER = Regex(
        "\\b(at|for|to|by|around|about|on|the|o'?clock|book|schedule|a|ride|cab|auto|please|me|" +
            "today|tonight|tomorrow|day after tomorrow|morning|evening|night|afternoon|am|pm|a m|p m|" +
            "monday|tuesday|wednesday|thursday|friday|saturday|sunday|next|this|half past|quarter past|quarter to)\\b"
    )

    fun parse(text: String, now: ZonedDateTime): ZonedDateTime? {
        val t = text.lowercase().replace(Regex("[^a-z0-9: ]"), " ").replace(Regex("\\s+"), " ").trim()
        if (t.isEmpty()) return null

        // ---- relative: "in 2 hours", "in half an hour" ------------------------------------
        if (HALF_HOUR.containsMatchIn(t)) return now.plusMinutes(30).truncatedTo(ChronoUnit.MINUTES)
        if (AN_HOUR.containsMatchIn(t)) return now.plusHours(1).truncatedTo(ChronoUnit.MINUTES)
        IN_RELATIVE.find(t)?.let { m ->
            val amount = SpokenNumbers.digits(m.groupValues[1]).toIntOrNull() ?: return null
            val unit = m.groupValues[2]
            val result = if (unit.startsWith("h")) now.plusHours(amount.toLong()) else now.plusMinutes(amount.toLong())
            return result.truncatedTo(ChronoUnit.MINUTES).takeIf { inRange(it, now) }
        }

        // ---- the day ----------------------------------------------------------------------
        val today = now.toLocalDate()
        val day: LocalDate? = when {
            t.contains("day after tomorrow") -> today.plusDays(2)
            Regex("\\btomorrow\\b").containsMatchIn(t) -> today.plusDays(1)
            Regex("\\b(today|tonight)\\b").containsMatchIn(t) -> today
            else -> DayOfWeek.entries.firstOrNull { Regex("\\b${it.name.lowercase()}\\b").containsMatchIn(t) }
                ?.let { dow ->
                    val next = today.with(TemporalAdjusters.next(dow))
                    if (Regex("\\bnext\\b").containsMatchIn(t) || dow != today.dayOfWeek) next else next
                }
        }

        // ---- the time of day --------------------------------------------------------------
        var time: LocalTime? = when {
            NOON.containsMatchIn(t) -> LocalTime.NOON
            MIDNIGHT.containsMatchIn(t) -> LocalTime.MIDNIGHT
            else -> null
        }
        if (time == null) {
            val stripped = FILLER.replace(t, " ").replace(":", " ")
            val digits = SpokenNumbers.digits(stripped)
            var hour: Int
            var minute: Int
            when (digits.length) {
                1, 2 -> { hour = digits.toInt(); minute = 0 }
                3 -> { hour = digits.substring(0, 1).toInt(); minute = digits.substring(1).toInt() }
                4 -> { hour = digits.substring(0, 2).toInt(); minute = digits.substring(2).toInt() }
                else -> return null
            }
            when {
                HALF_PAST.containsMatchIn(t) && digits.length <= 2 -> minute = 30
                QUARTER_PAST.containsMatchIn(t) && digits.length <= 2 -> minute = 15
                QUARTER_TO.containsMatchIn(t) && digits.length <= 2 -> { minute = 45; hour -= 1 }
            }
            if (hour !in 0..23 || minute !in 0..59) return null

            val saysMorning = MORNING.containsMatchIn(t)
            val saysEvening = EVENING.containsMatchIn(t)
            if (saysEvening && hour in 1..11) hour += 12
            if (saysMorning && hour == 12) hour = 0
            time = LocalTime.of(hour, minute)

            // No am/pm and a 12-hour-looking hour: take the next time that is still ahead.
            if (!saysMorning && !saysEvening && hour in 1..11) {
                val base = day ?: today
                val am = ZonedDateTime.of(base, LocalTime.of(hour, minute), now.zone)
                val pm = am.plusHours(12)
                val pick = when {
                    am.isAfter(now) -> am
                    pm.isAfter(now) -> pm
                    day == null -> am.plusDays(1)
                    else -> null
                } ?: return null
                return pick.takeIf { inRange(it, now) }
            }
        }

        val base = day ?: today
        var result = ZonedDateTime.of(base, time, now.zone)
        // "at 7" said at 9 PM with no day means tomorrow at 7.
        if (day == null && !result.isAfter(now)) result = result.plusDays(1)
        return result.takeIf { inRange(it, now) }
    }

    private fun inRange(t: ZonedDateTime, now: ZonedDateTime): Boolean =
        t.isAfter(now) && t.isBefore(now.plusDays(MAX_DAYS_AHEAD))

    /** "today at 5 PM", "tomorrow at 8 30 AM", "on Monday at 9 AM". */
    fun spoken(t: ZonedDateTime, now: ZonedDateTime): String {
        val h12 = if (t.hour % 12 == 0) 12 else t.hour % 12
        val mm = if (t.minute == 0) "" else " " + t.minute.toString().padStart(2, '0')
        val clock = "$h12$mm ${if (t.hour < 12) "AM" else "PM"}"
        val days = ChronoUnit.DAYS.between(now.toLocalDate(), t.toLocalDate())
        val day = when (days) {
            0L -> "today"
            1L -> "tomorrow"
            else -> "on " + t.dayOfWeek.name.lowercase().replaceFirstChar { it.uppercase() }
        }
        return "$day at $clock"
    }

    fun zone(): ZoneId = ZoneId.systemDefault()
}
