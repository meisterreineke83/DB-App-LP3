package io.github.lightbahn

import java.time.LocalDate
import java.time.format.DateTimeFormatter
import java.util.Locale

/** Eine Server-Antwortzeile samt angehängter Literal-Blöcke ({n}\r\n<n Bytes>). */
internal class ImapResponse(val text: String, val literals: List<ByteArray>)

/**
 * Minimaler IMAP-Client (nur lesend).
 * Bewusst ohne Fremdbibliothek, weil die Light SDK nur eine kleine Liste
 * erlaubter Abhängigkeiten zulässt. Unterstützt genau das, was wir brauchen:
 * STARTTLS, LOGIN, EXAMINE (Postfach nur lesen), UID SEARCH, UID FETCH BODY.PEEK[] und LOGOUT.
 */
internal class ImapReader(
    private val settings: MailSettings,
    private val password: String,
) : MailReader {

    private val conn = MailSocket(settings.effectiveHost, settings.effectivePort)
    private var tagCounter = 0

    override fun open() {
        conn.open(settings.security)
        val greeting = conn.readLine()
        if (!greeting.startsWith("* OK")) throw MailException("Das ist kein IMAP-Server (Antwort: ${greeting.take(60)})")
        if (settings.security == MailSecurity.STARTTLS) {
            try {
                command("STARTTLS")
            } catch (e: MailException) {
                throw MailException("Der Server unterstützt kein STARTTLS. Versuche SSL/TLS.")
            }
            conn.upgradeToTls()
        }
        try {
            command("LOGIN ${quote(settings.email)} ${quote(password)}")
        } catch (e: MailException) {
            throw MailException(
                "Anmeldung fehlgeschlagen. Prüfe Adresse und App-Passwort und ob der " +
                    "IMAP-Zugriff beim Mail-Anbieter eingeschaltet ist."
            )
        }
        try {
            // EXAMINE öffnet nur lesend – nichts wird verändert oder als gelesen markiert.
            command("EXAMINE ${quote(settings.effectiveFolder)}")
        } catch (e: MailException) {
            throw MailException("Ordner „${settings.effectiveFolder}“ nicht gefunden.")
        }
    }

    override fun candidateKeys(lookbackDays: Long): List<String> {
        val since = LocalDate.now().minusDays(lookbackDays)
            .format(DateTimeFormatter.ofPattern("dd-MMM-yyyy", Locale.US))
        return uidSearch("SINCE $since FROM \"bahn.de\"")
            .sortedDescending()
            .map { keyFor(it) }
    }

    override fun fetch(key: String): ByteArray? {
        val uid = key.substringAfterLast('|').toLongOrNull() ?: return null
        return fetchMessage(uid)
    }

    override fun quit() {
        runCatching { command("LOGOUT") }
    }

    override fun close() = conn.close()

    private fun keyFor(uid: Long) = "imap|${settings.effectiveHost}|${settings.email}|${settings.effectiveFolder}|$uid"

    fun uidSearch(criteria: String): List<Long> =
        command("UID SEARCH $criteria")
            .filter { it.text.startsWith("* SEARCH") }
            .flatMap { response ->
                response.text.removePrefix("* SEARCH").trim()
                    .split(' ')
                    .mapNotNull { it.trim().toLongOrNull() }
            }

    /** Lädt die komplette Mail (Header + alle Teile), ohne das "Gelesen"-Flag zu setzen. */
    fun fetchMessage(uid: Long): ByteArray? =
        command("UID FETCH $uid BODY.PEEK[]")
            .firstOrNull { it.text.contains("FETCH") && it.literals.isNotEmpty() }
            ?.literals
            ?.maxByOrNull { it.size }

    private fun command(cmd: String): List<ImapResponse> {
        val tag = "A${++tagCounter}"
        conn.write("$tag $cmd")

        val responses = mutableListOf<ImapResponse>()
        while (true) {
            val builder = StringBuilder()
            val literals = mutableListOf<ByteArray>()
            var line = conn.readLine()
            // Literal-Blöcke: Zeile endet auf {n} → danach folgen genau n Bytes, dann geht die Zeile weiter.
            while (true) {
                val match = LITERAL_REGEX.find(line)
                if (match == null) {
                    builder.append(line)
                    break
                }
                builder.append(line, 0, match.range.first)
                literals += conn.readBytes(match.groupValues[1].toInt())
                builder.append("{literal}")
                line = conn.readLine()
            }
            val text = builder.toString()
            if (text.startsWith("$tag ")) {
                val status = text.removePrefix("$tag ").substringBefore(' ')
                if (status.equals("OK", ignoreCase = true)) return responses
                throw MailException(text.removePrefix("$tag ").take(200))
            }
            responses += ImapResponse(text, literals)
        }
    }

    private fun quote(value: String): String =
        "\"" + value.replace("\\", "\\\\").replace("\"", "\\\"") + "\""

    private companion object {
        val LITERAL_REGEX = Regex("""\{(\d+)\}$""")
    }
}

/**
 * Minimaler POP3-Client (nur lesend: UIDL, TOP, RETR – niemals DELE).
 * POP3 kennt keine Suche, deshalb werden zuerst nur die Kopfzeilen (TOP n 0) geholt
 * und nur Mails von bahn.de komplett geladen.
 */
internal class Pop3Reader(
    private val settings: MailSettings,
    private val password: String,
) : MailReader {

    private val conn = MailSocket(settings.effectiveHost, settings.effectivePort)
    private val numberByKey = mutableMapOf<String, Int>()

    override fun open() {
        conn.open(settings.security)
        val greeting = conn.readLine()
        if (!greeting.startsWith("+OK")) throw MailException("Das ist kein POP3-Server (Antwort: ${greeting.take(60)})")
        if (settings.security == MailSecurity.STARTTLS) {
            try {
                command("STLS")
            } catch (e: MailException) {
                throw MailException("Der Server unterstützt kein STARTTLS. Versuche SSL/TLS.")
            }
            conn.upgradeToTls()
        }
        try {
            command("USER ${settings.email}")
            command("PASS $password")
        } catch (e: MailException) {
            throw MailException(
                "Anmeldung fehlgeschlagen. Prüfe Adresse und App-Passwort und ob der " +
                    "POP3-Zugriff beim Mail-Anbieter eingeschaltet ist."
            )
        }
    }

    override fun candidateKeys(lookbackDays: Long): List<String> {
        command("UIDL")
        val entries = readMultiline()
            .mapNotNull { line ->
                val parts = line.trim().split(' ', limit = 2)
                val number = parts.getOrNull(0)?.toIntOrNull() ?: return@mapNotNull null
                val uidl = parts.getOrNull(1)?.trim() ?: return@mapNotNull null
                number to "pop3|${settings.effectiveHost}|${settings.email}|$uidl"
            }
            .sortedByDescending { it.first } // höhere Nummer = neuer
        entries.forEach { (number, key) -> numberByKey[key] = number }
        return entries.map { it.second }
    }

    /** Prüft nur die Kopfzeilen; lädt die Mail nur, wenn sie von bahn.de kommt. */
    override fun fetch(key: String): ByteArray? {
        val number = numberByKey[key] ?: return null
        command("TOP $number 0")
        val headers = readMultiline().joinToString("\n")
        val from = Regex("(?im)^From:(.*(?:\\n[ \\t].*)*)").find(headers)?.groupValues?.get(1).orEmpty()
        if (!from.contains("bahn.de", ignoreCase = true)) return null
        command("RETR $number")
        val lines = readMultiline()
        return lines.joinToString("\r\n", postfix = "\r\n").toByteArray(Charsets.ISO_8859_1)
    }

    override fun quit() {
        // QUIT ohne vorheriges DELE: Der Server löscht nichts.
        runCatching { command("QUIT") }
    }

    override fun close() = conn.close()

    private fun command(cmd: String): String {
        conn.write(cmd)
        val line = conn.readLine()
        if (!line.startsWith("+OK")) throw MailException(line.take(200))
        return line
    }

    /** Liest eine mehrzeilige Antwort bis zur Zeile ".", mit Entfernen des Punkt-Stuffings. */
    private fun readMultiline(): List<String> {
        val lines = mutableListOf<String>()
        while (true) {
            val line = conn.readLine()
            if (line == ".") return lines
            lines += if (line.startsWith("..")) line.substring(1) else line
        }
    }
}
