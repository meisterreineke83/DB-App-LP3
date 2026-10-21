package io.github.lightbahn

import java.time.LocalDate

/** Was wir aus Fahrkarte und Mail über die Reise herauslesen können. Alles optional. */
internal data class TicketInfo(
    val date: LocalDate? = null,
    val time: String? = null,
    val from: String? = null,
    val to: String? = null,
)

/**
 * Liest Reisedatum, Abfahrtszeit sowie Start- und Zielbahnhof aus dem Text
 * einer Bahn-Fahrkarte (PDF) bzw. der Buchungsmail. Reine Heuristik:
 * Was nicht sicher erkannt wird, bleibt leer.
 */
internal object TicketInfoParser {

    private val MONTHS = mapOf(
        "jan" to 1, "feb" to 2, "mär" to 3, "mar" to 3, "märz" to 3, "apr" to 4, "mai" to 5, "jun" to 6,
        "jul" to 7, "aug" to 8, "sep" to 9, "okt" to 10, "oct" to 10, "nov" to 11, "dez" to 12, "dec" to 12,
    )

    private const val NUM_DATE = """(\d{1,2})\.\s?(\d{1,2})\.\s?(20\d{2})"""
    private const val WORD_DATE = """(\d{1,2})\.\s?([A-Za-zÄÖÜäöü]{3,9})\.?\s+(20\d{2})"""

    /** Datumsangaben, die nichts mit der Fahrt zu tun haben (Kauf, Rechnung …). */
    private val NOT_TRAVEL = Regex(
        """(?i)(buchung|gebucht|bestell|rechnung|auftragsdatum|kauf|erstellt|ausgestellt|ausgabe|druck|zahlung|""" +
            """storn|erstattung|geburt|stand)[^\n]{0,30}$""",
    )

    /** Datumsangaben direkt hinter diesen Wörtern sind sehr wahrscheinlich das Reisedatum. */
    private val TRAVEL_HINT = Regex(
        """(?i)(hinfahrt|fahrtantritt|reisedatum|reisetag|fahrtag|gültig(?:keit)?|gilt|fahrt am|abfahrt)""",
    )

    fun parse(pdfText: String, mailText: String, bookedOn: LocalDate? = null): TicketInfo {
        val pdf = normalize(pdfText)
        val mail = normalize(mailText)
        val leg = itinerary(pdf) ?: itinerary(mail)
        val route = leg?.let { it.from to it.to } ?: vonNach(pdf) ?: arrow(pdf) ?: vonNach(mail) ?: arrow(mail)
        val date = travelDate(pdf, bookedOn) ?: travelDate(mail, bookedOn) ?: leg?.date?.let { yearFor(it, bookedOn) }
        return TicketInfo(
            date = date,
            time = leg?.time,
            from = route?.first?.let(::cleanStation),
            to = route?.second?.let(::cleanStation),
        )
    }

    private fun normalize(t: String) = t
        .replace(' ', ' ')
        .replace(Regex("[ \\t]+"), " ")
        .replace(Regex(" *\\r?\\n *"), "\n")

    // ---------------------------------------------------------------- Datum

    private fun travelDate(text: String, bookedOn: LocalDate?): LocalDate? {
        val candidates = ArrayList<Pair<Int, LocalDate>>() // Position, Datum
        Regex(NUM_DATE).findAll(text).forEach { m ->
            date(m.groupValues[1], m.groupValues[2].toIntOrNull(), m.groupValues[3])?.let { candidates += m.range.first to it }
        }
        Regex(WORD_DATE).findAll(text).forEach { m ->
            val month = MONTHS[m.groupValues[2].lowercase().take(3)] ?: MONTHS[m.groupValues[2].lowercase()]
            date(m.groupValues[1], month, m.groupValues[3])?.let { candidates += m.range.first to it }
        }
        if (candidates.isEmpty()) return null
        candidates.sortBy { it.first }

        fun before(pos: Int) = text.substring(maxOf(0, pos - 45), pos)

        // 1. Datum direkt hinter "Hinfahrt", "Gültigkeit" usw.
        candidates.firstOrNull { (pos, _) ->
            val b = before(pos)
            TRAVEL_HINT.containsMatchIn(b.substringAfterLast('\n')) && !NOT_TRAVEL.containsMatchIn(b)
        }?.let { return it.second }
        // 2. Sonst das erste Datum, das nicht nach Kauf/Rechnung aussieht und nicht vor der Buchung liegt.
        return candidates.firstOrNull { (pos, d) ->
            !NOT_TRAVEL.containsMatchIn(before(pos)) && (bookedOn == null || !d.isBefore(bookedOn))
        }?.second
    }

    private fun date(day: String, month: Int?, year: String): LocalDate? = runCatching {
        LocalDate.of(year.toInt(), month ?: return null, day.toInt())
    }.getOrNull()

    private fun yearFor(dm: Pair<Int, Int>, bookedOn: LocalDate?): LocalDate? = runCatching {
        val ref = bookedOn ?: LocalDate.now()
        var d = LocalDate.of(ref.year, dm.second, dm.first)
        if (d.isBefore(ref.minusDays(1))) d = d.plusYears(1)
        d
    }.getOrNull()

    // ---------------------------------------------------------------- Bahnhöfe

    private class Leg(val from: String, val to: String, val time: String?, val date: Pair<Int, Int>?)

    /**
     * Fahrplantabelle der Fahrkarte:
     *   "Rheinfelden(Baden) Fr. 23.10. ab 06:12 2 RE 17040"
     *   "Freiburg(Breisgau) Hbf Fr. 23.10. an 07:05 …"
     * Erster Halt mit "ab" = Start, letzter Halt mit "an" = Ziel.
     */
    private fun itinerary(text: String): Leg? {
        val row = Regex(
            """(?m)^(.{2,60}?)\s+(?:(?:Mo|Di|Mi|Do|Fr|Sa|So)\.?,?\s+)?(?:(\d{1,2})\.(\d{1,2})\.(?:20\d{2})?\s+)?(ab|an)\s+(\d{1,2}:\d{2})""",
        )
        val rows = row.findAll(text).mapNotNull { m ->
            val station = m.groupValues[1].trim()
            if (!looksLikeStation(station)) return@mapNotNull null
            val dm = m.groupValues[2].toIntOrNull()?.let { d -> m.groupValues[3].toIntOrNull()?.let { d to it } }
            Triple(station, m.groupValues[4], m.groupValues[5]) to dm
        }.toList()
        val first = rows.firstOrNull { it.first.second == "ab" } ?: return null
        val last = rows.lastOrNull { it.first.second == "an" } ?: return null
        if (first.first.first.equals(last.first.first, ignoreCase = true)) return null
        return Leg(first.first.first, last.first.first, first.first.third, first.second)
    }

    /** "Von Rheinfelden(Baden) nach Freiburg(Breisgau) Hbf" (auch über zwei Zeilen). */
    private fun vonNach(text: String): Pair<String, String>? {
        val m = Regex("""(?i)\bvon:?\s+([^\n]{2,60}?)\s*\n?\s*\bnach:?\s+([^\n]{2,60})""").find(text) ?: return null
        val from = m.groupValues[1].trim()
        val to = cutTail(m.groupValues[2])
        return if (looksLikeStation(from) && looksLikeStation(to)) from to to else null
    }

    /** "Hinfahrt: Rheinfelden(Baden) → Freiburg(Breisgau) Hbf, mit ICE" */
    private fun arrow(text: String): Pair<String, String>? {
        val m = Regex("""(?m)(?:^|:\s*)([A-ZÄÖÜ][^\n:→]{1,60}?)\s*(?:→|->|⟶|➔|►|▶|⇒|>)\s*([A-ZÄÖÜ][^\n]{1,60})""")
            .find(text) ?: return null
        val from = m.groupValues[1].trim()
        val to = cutTail(m.groupValues[2])
        return if (looksLikeStation(from) && looksLikeStation(to)) from to to else null
    }

    /** Schneidet Zusätze wie ", mit ICE", " am 23.10.2026", " Gleis 2" ab. */
    private fun cutTail(s: String): String = s
        .split(Regex("""(?i),|\s+(?:mit|über|via|am|ab|an|gleis|klasse|hinfahrt|rückfahrt|\d{1,2}\.\d{1,2}\.)\b|\s{2,}"""))
        .first()
        .trim()

    private fun looksLikeStation(s: String): Boolean {
        val t = s.trim()
        if (t.length !in 2..50) return false
        if (t.count { it.isLetter() } < 2) return false
        if (t.count { it.isDigit() } > 3) return false
        val lower = t.lowercase()
        val bad = listOf(
            "halt", "datum", "zeit", "gleis", "ihre", "fahrkarte", "ticket", "reservierung", "preis", "betrag",
            "auftrag", "buchung", "klasse", "erwachsen", "hinweis", "seite", "produkte", "bahncard", "summe",
        )
        return bad.none { lower == it || lower.startsWith("$it ") || lower.startsWith("$it:") }
    }

    /** Macht "Freiburg(Breisgau) Hbf" lesbarer: "Freiburg (Breisgau) Hbf". */
    fun cleanStation(s: String): String = s.trim()
        .replace(Regex("""(\S)\("""), "$1 (")
        .replace(Regex("\\s+"), " ")
        .trimEnd(',', '.', ':', ';', '-')
}
