package com.inmc.shop.price

import org.bukkit.configuration.ConfigurationSection
import java.time.DayOfWeek
import java.time.Instant
import java.time.LocalTime
import java.time.ZoneId
import java.time.ZonedDateTime

/**
 * "언제 다시 하나" — 변동 가격의 다시 굴림과 회전이 같이 쓴다. 간격(초) 또는 요일 × 시각.
 */
sealed interface Schedule {

    /** [now] 뒤의 다음 시각(밀리초). 못 정하면 null(요일·시각이 비었음). */
    fun next(now: Long, zone: ZoneId): Long?

    fun save(section: ConfigurationSection)

    fun describe(): String

    data class Interval(val seconds: Long) : Schedule {
        override fun next(now: Long, zone: ZoneId): Long = now + seconds.coerceAtLeast(1) * 1000L
        override fun save(section: ConfigurationSection) {
            section.set("type", "INTERVAL")
            section.set("seconds", seconds)
        }
        override fun describe(): String = kr.inmc.core.util.Durations.formatShort(seconds) + "마다"
    }

    data class Weekly(val days: Set<DayOfWeek>, val times: List<LocalTime>) : Schedule {
        override fun next(now: Long, zone: ZoneId): Long? {
            if (times.isEmpty()) return null
            val activeDays = days.ifEmpty { DayOfWeek.entries.toSet() }
            val start = ZonedDateTime.ofInstant(Instant.ofEpochMilli(now), zone)
            val sorted = times.sorted()
            for (offset in 0..7) {
                val date = start.toLocalDate().plusDays(offset.toLong())
                if (date.dayOfWeek !in activeDays) continue
                for (time in sorted) {
                    val at = date.atTime(time).atZone(zone).toInstant().toEpochMilli()
                    if (at > now) return at
                }
            }
            return null
        }
        override fun save(section: ConfigurationSection) {
            section.set("type", "WEEKLY")
            section.set("days", days.sortedBy { it.value }.map { it.name })
            section.set("times", times.sorted().map { "%02d:%02d".format(it.hour, it.minute) })
        }
        override fun describe(): String {
            val dayText = if (days.isEmpty() || days.size == 7) "매일" else days.sortedBy { it.value }.joinToString("·") { DAY_LABEL.getValue(it) }
            return dayText + " " + times.sorted().joinToString(", ") { "%02d:%02d".format(it.hour, it.minute) }
        }
    }

    companion object {
        val DAY_LABEL: Map<DayOfWeek, String> = mapOf(
            DayOfWeek.MONDAY to "월", DayOfWeek.TUESDAY to "화", DayOfWeek.WEDNESDAY to "수", DayOfWeek.THURSDAY to "목",
            DayOfWeek.FRIDAY to "금", DayOfWeek.SATURDAY to "토", DayOfWeek.SUNDAY to "일",
        )

        fun load(section: ConfigurationSection?, fallbackSeconds: Long): Schedule {
            if (section == null) return Interval(fallbackSeconds)
            return when (section.getString("type", "INTERVAL")!!.uppercase()) {
                "WEEKLY", "FIXED" -> Weekly(
                    section.getStringList("days").mapNotNull { runCatching { DayOfWeek.valueOf(it.uppercase()) }.getOrNull() }.toSet(),
                    section.getStringList("times").mapNotNull(::parseTime).distinct(),
                )
                else -> Interval(section.getLong("seconds", fallbackSeconds).coerceAtLeast(1))
            }
        }

        /** "9:00" · "21:30" → 시각. */
        fun parseTime(raw: String): LocalTime? {
            val parts = raw.trim().split(':')
            val hour = parts.getOrNull(0)?.trim()?.toIntOrNull() ?: return null
            val minute = parts.getOrNull(1)?.trim()?.toIntOrNull() ?: 0
            if (hour !in 0..23 || minute !in 0..59) return null
            return LocalTime.of(hour, minute)
        }
    }
}
