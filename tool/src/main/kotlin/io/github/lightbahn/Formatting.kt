package io.github.lightbahn

import java.time.Duration
import java.time.LocalDate
import java.time.LocalTime
import java.time.OffsetDateTime
import java.time.ZoneId
import java.time.ZonedDateTime
import java.time.format.DateTimeFormatter
import java.time.format.DateTimeParseException
import java.util.Locale

internal val BERLIN: ZoneId = ZoneId.of("Europe/Berlin")
private val TIME = DateTimeFormatter.ofPattern("HH:mm")
private val DAY = DateTimeFormatter.ofPattern("EE dd.MM.", Locale.GERMAN)

internal fun parseIso(value: String?): ZonedDateTime? =
    value?.let {
        try {
            OffsetDateTime.parse(it).atZoneSameInstant(BERLIN)
        } catch (e: DateTimeParseException) {
            null
        }
    }

internal fun formatTime(value: String?): String = parseIso(value)?.format(TIME) ?: "--:--"

internal fun formatDay(value: String?): String = parseIso(value)?.format(DAY).orEmpty()

/** Verspätung in Sekunden → "+5" (nur ab einer Minute). */
internal fun formatDelay(seconds: Int?): String {
    val minutes = (seconds ?: 0) / 60
    return if (minutes > 0) " +$minutes" else ""
}

internal fun formatPlatform(actual: String?, planned: String?): String {
    val platform = actual ?: planned ?: return ""
    val changed = actual != null && planned != null && actual != planned
    return if (changed) "Gl. $platform (statt $planned)" else "Gl. $platform"
}

internal fun Journey.firstDeparture(): Leg? = legs.firstOrNull { !it.walking } ?: legs.firstOrNull()
internal fun Journey.lastArrival(): Leg? = legs.lastOrNull { !it.walking } ?: legs.lastOrNull()

internal fun Journey.durationText(): String {
    val start = parseIso(legs.firstOrNull()?.departure ?: legs.firstOrNull()?.plannedDeparture)
    val end = parseIso(legs.lastOrNull()?.arrival ?: legs.lastOrNull()?.plannedArrival)
    if (start == null || end == null) return ""
    val minutes = Duration.between(start, end).toMinutes()
    return "%d:%02d h".format(minutes / 60, minutes % 60)
}

internal fun Journey.changes(): Int = (legs.count { !it.walking } - 1).coerceAtLeast(0)

internal fun Journey.isCancelled(): Boolean = legs.any { it.cancelled }

internal fun Journey.linesText(): String =
    legs.filterNot { it.walking }
        .mapNotNull { it.line?.name?.trim() }
        .distinct()
        .joinToString(", ")

internal fun Journey.summaryLine(): String {
    val changes = changes()
    val changeText = when (changes) {
        0 -> "direkt"
        1 -> "1 Umstieg"
        else -> "$changes Umstiege"
    }
    return listOf(durationText(), changeText, linesText())
        .filter { it.isNotBlank() }
        .joinToString(" · ")
}

/**
 * Versteht: "" (jetzt), "17:30", "8", "morgen 7:15", "24.10. 9:00", "24.10.2026 9:00".
 * Uhrzeiten, die heute schon vorbei sind, werden auf morgen gelegt.
 */
internal fun parseDeparture(input: String, now: ZonedDateTime = ZonedDateTime.now(BERLIN)): ZonedDateTime? {
    val text = input.trim().lowercase(Locale.GERMAN)
    if (text.isEmpty() || text == "jetzt") return null

    var date: LocalDate? = null
    var rest = text
    if (rest.startsWith("morgen")) {
        date = now.toLocalDate().plusDays(1)
        rest = rest.removePrefix("morgen").trim()
    } else if (rest.startsWith("heute")) {
        date = now.toLocalDate()
        rest = rest.removePrefix("heute").trim()
    } else {
        Regex("""^(\d{1,2})\.(\d{1,2})\.(\d{2,4})?\s*""").find(rest)?.let { m ->
            val day = m.groupValues[1].toInt()
            val month = m.groupValues[2].toInt()
            var year = m.groupValues[3].toIntOrNull() ?: now.year
            if (year < 100) year += 2000
            date = LocalDate.of(year, month, day).let { d ->
                if (m.groupValues[3].isEmpty() && d.isBefore(now.toLocalDate())) d.plusYears(1) else d
            }
            rest = rest.substring(m.range.last + 1).trim()
        }
    }

    val time = Regex("""^(\d{1,2})(?:[:.](\d{2}))?$""").find(rest.removeSuffix("uhr").trim())
        ?.let { LocalTime.of(it.groupValues[1].toInt(), it.groupValues[2].toIntOrNull() ?: 0) }
        ?: if (rest.isEmpty() && date != null) LocalTime.of(6, 0) else null
    require(time != null) { "Zeit nicht erkannt. Beispiele: 17:30 · morgen 7:15 · 24.10. 9:00" }

    var result = ZonedDateTime.of(date ?: now.toLocalDate(), time, BERLIN)
    if (date == null && result.isBefore(now.minusMinutes(5))) result = result.plusDays(1)
    return result
}

internal fun describeDeparture(value: ZonedDateTime?): String {
    if (value == null) return "Jetzt"
    val today = ZonedDateTime.now(BERLIN).toLocalDate()
    val day = when (value.toLocalDate()) {
        today -> "Heute"
        today.plusDays(1) -> "Morgen"
        else -> value.format(DAY)
    }
    return "$day ${value.format(TIME)}"
}
