package io.github.lightbahn

import java.io.BufferedInputStream
import java.io.ByteArrayOutputStream
import java.io.Closeable
import java.io.IOException
import java.io.OutputStream
import javax.net.ssl.SSLSocket
import javax.net.ssl.SSLSocketFactory

class ImapException(message: String) : IOException(message)

/** Eine Server-Antwortzeile samt angehängter Literal-Blöcke ({n}\r\n<n Bytes>). */
internal class ImapResponse(val text: String, val literals: List<ByteArray>)

/**
 * Minimaler IMAP-Client (nur lesend) über TLS.
 * Bewusst ohne Fremdbibliothek, weil die Light SDK nur eine kleine Liste
 * erlaubter Abhängigkeiten zulässt. Unterstützt genau das, was wir brauchen:
 * LOGIN, EXAMINE (Postfach nur lesen), UID SEARCH, UID FETCH BODY.PEEK[] und LOGOUT.
 */
internal class ImapClient(
    private val host: String,
    private val port: Int = 993,
) : Closeable {

    companion object {
        /** IMAP-Server gängiger Anbieter; sonst wird imap.<domain> versucht. */
        fun hostFor(email: String): String {
            val domain = email.substringAfterLast('@').trim().lowercase()
            return when (domain) {
                "web.de" -> "imap.web.de"
                "gmx.de", "gmx.net", "gmx.at", "gmx.ch" -> "imap.gmx.net"
                "gmail.com", "googlemail.com" -> "imap.gmail.com"
                "posteo.de", "posteo.net" -> "posteo.de"
                "mailbox.org" -> "imap.mailbox.org"
                "t-online.de" -> "secureimap.t-online.de"
                "icloud.com", "me.com", "mac.com" -> "imap.mail.me.com"
                else -> "imap.$domain"
            }
        }

        private val LITERAL_REGEX = Regex("""\{(\d+)\}$""")
    }

    private var socket: SSLSocket? = null
    private lateinit var input: BufferedInputStream
    private lateinit var output: OutputStream
    private var tagCounter = 0

    fun connect() {
        val factory = SSLSocketFactory.getDefault()
        val s = try {
            factory.createSocket(host, port) as SSLSocket
        } catch (e: java.net.UnknownHostException) {
            throw ImapException("Mailserver $host nicht gefunden. Ist die Adresse richtig?")
        }
        // SSLSocketFactory prüft den Hostnamen nicht von selbst – hier ausdrücklich einschalten.
        val params = s.sslParameters
        params.endpointIdentificationAlgorithm = "HTTPS"
        s.sslParameters = params
        s.soTimeout = 30_000
        s.startHandshake()
        socket = s
        input = BufferedInputStream(s.inputStream)
        output = s.outputStream
        val greeting = readLine()
        if (!greeting.startsWith("* OK")) throw ImapException("Unerwartete Server-Begrüßung")
    }

    fun login(user: String, password: String) {
        try {
            command("LOGIN ${quote(user)} ${quote(password)}")
        } catch (e: ImapException) {
            throw ImapException(
                "Anmeldung fehlgeschlagen. Prüfe Adresse und App-Passwort und ob der " +
                    "IMAP-Zugriff beim Mail-Anbieter eingeschaltet ist."
            )
        }
    }

    /** Öffnet ein Postfach nur lesend – es werden keine Mails verändert oder als gelesen markiert. */
    fun examine(mailbox: String = "INBOX") {
        command("EXAMINE ${quote(mailbox)}")
    }

    /** @param criteria z. B. `SINCE 01-Aug-2026 FROM "bahn.de"` */
    fun uidSearch(criteria: String): List<Long> {
        return command("UID SEARCH $criteria")
            .filter { it.text.startsWith("* SEARCH") }
            .flatMap { response ->
                response.text.removePrefix("* SEARCH").trim()
                    .split(' ')
                    .mapNotNull { it.trim().toLongOrNull() }
            }
    }

    /** Lädt die komplette Mail (Header + alle Teile), ohne das "Gelesen"-Flag zu setzen. */
    fun fetchMessage(uid: Long): ByteArray? {
        return command("UID FETCH $uid BODY.PEEK[]")
            .firstOrNull { it.text.contains("FETCH") && it.literals.isNotEmpty() }
            ?.literals
            ?.maxByOrNull { it.size }
    }

    fun logout() {
        runCatching { command("LOGOUT") }
    }

    override fun close() {
        runCatching { socket?.close() }
        socket = null
    }

    private fun command(cmd: String): List<ImapResponse> {
        val tag = "A${++tagCounter}"
        output.write("$tag $cmd\r\n".toByteArray(Charsets.UTF_8))
        output.flush()

        val responses = mutableListOf<ImapResponse>()
        while (true) {
            val builder = StringBuilder()
            val literals = mutableListOf<ByteArray>()
            var line = readLine()
            // Literal-Blöcke: Zeile endet auf {n} → danach folgen genau n Bytes, dann geht die Zeile weiter.
            while (true) {
                val match = LITERAL_REGEX.find(line)
                if (match == null) {
                    builder.append(line)
                    break
                }
                builder.append(line, 0, match.range.first)
                val size = match.groupValues[1].toInt()
                literals += readBytes(size)
                builder.append("{literal}")
                line = readLine()
            }
            val text = builder.toString()
            if (text.startsWith("$tag ")) {
                val status = text.removePrefix("$tag ").substringBefore(' ')
                if (status.equals("OK", ignoreCase = true)) return responses
                throw ImapException(text.removePrefix("$tag ").take(200))
            }
            responses += ImapResponse(text, literals)
        }
    }

    private fun readLine(): String {
        val out = ByteArrayOutputStream()
        while (true) {
            val b = input.read()
            if (b == -1) throw ImapException("Verbindung zum Mailserver unterbrochen")
            if (b == '\n'.code) break
            if (b != '\r'.code) out.write(b)
        }
        return String(out.toByteArray(), Charsets.ISO_8859_1)
    }

    private fun readBytes(size: Int): ByteArray {
        val buffer = ByteArray(size)
        var read = 0
        while (read < size) {
            val n = input.read(buffer, read, size - read)
            if (n == -1) throw ImapException("Verbindung zum Mailserver unterbrochen")
            read += n
        }
        return buffer
    }

    private fun quote(value: String): String =
        "\"" + value.replace("\\", "\\\\").replace("\"", "\\\"") + "\""
}
