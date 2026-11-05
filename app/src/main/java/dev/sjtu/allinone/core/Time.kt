package dev.sjtu.allinone.core

import java.time.Instant
import java.time.LocalDate
import java.time.LocalTime
import java.time.ZoneId
import java.time.format.DateTimeFormatter

val SCHOOL_ZONE: ZoneId = ZoneId.of("Asia/Shanghai")

fun schoolToday(): LocalDate = LocalDate.now(SCHOOL_ZONE)
fun nowMillis(): Long = Instant.now().toEpochMilli()
fun Long.toSchoolDate(): LocalDate = Instant.ofEpochMilli(this).atZone(SCHOOL_ZONE).toLocalDate()
fun String.parseMinute(): Int? = runCatching {
    val time = LocalTime.parse(trim(), DateTimeFormatter.ofPattern("H:mm"))
    time.hour * 60 + time.minute
}.getOrNull()
fun Int.minuteText(): String = "%02d:%02d".format(this / 60, this % 60)
fun Long.epochDayText(): String = LocalDate.ofEpochDay(this).toString()

/** Display defaults for the undergraduate timetable; IDS always uses its own section response. */
val DEFAULT_SECTIONS = listOf(
    1 to (8 * 60 to 8 * 60 + 45),
    2 to (8 * 60 + 55 to 9 * 60 + 40),
    3 to (10 * 60 to 10 * 60 + 45),
    4 to (10 * 60 + 55 to 11 * 60 + 40),
    5 to (12 * 60 to 12 * 60 + 45),
    6 to (12 * 60 + 55 to 13 * 60 + 40),
    7 to (14 * 60 to 14 * 60 + 45),
    8 to (14 * 60 + 55 to 15 * 60 + 40),
    9 to (16 * 60 to 16 * 60 + 45),
    10 to (16 * 60 + 55 to 17 * 60 + 40),
    11 to (18 * 60 to 18 * 60 + 45),
    12 to (18 * 60 + 55 to 19 * 60 + 40),
    13 to (20 * 60 to 20 * 60 + 45),
    14 to (20 * 60 + 55 to 21 * 60 + 40),
)

fun sectionStart(section: Int): Int = DEFAULT_SECTIONS.firstOrNull { it.first == section }?.second?.first
    ?: ((section.coerceIn(1, 14) - 1) * 55 + 8 * 60)
fun sectionEnd(section: Int): Int = DEFAULT_SECTIONS.firstOrNull { it.first == section }?.second?.second
    ?: sectionStart(section) + 45

const val WEEKDAY_NAMES = "一二三四五六日"
fun weekdayName(dayOfWeek: Int): String = WEEKDAY_NAMES.getOrElse(dayOfWeek - 1) { '?' }.toString()
