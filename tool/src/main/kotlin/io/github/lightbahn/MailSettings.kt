package io.github.lightbahn

import java.io.BufferedInputStream
import java.io.ByteArrayOutputStream
import java.io.Closeable
import java.io.IOException
import java.io.OutputStream
import java.net.InetSocketAddress
import java.net.Socket
import java.net.UnknownHostException
import javax.net.ssl.SSLSocket
import javax.net.ssl.SSLSocketFactory

enum class MailProtocol(val label: String) { IMAP("IMAP"), POP3("POP3") }

enum class MailSecurity(val label: String) { TLS("SSL/TLS"), STARTTLS("STARTTLS") }

class MailException(message: String) : IOException(message)

/**
 * Alle Angaben für den Abruf. Leere Felder für Server und Port bedeuten "automatisch":
 * Der Server wird dann aus der Adresse abgeleitet, der Port aus Protokoll und Verschlüsselung.
 */
data class MailSettings(
    val email: String = "",
    val protocol: MailProtocol = MailProtocol.IMAP,
    val security: MailSecurity = MailSecurity.TLS,
    val host: String = "",
    val port: Int? = null,
    val folder: String = "",
) {
    val effectiveHost: String get() = host.trim().ifEmpty { autoHost(email, protocol) }
    val effectivePort: Int get() = port ?: defaultPort(protocol, security)
    val effectiveFolder: String get() = folder.trim().ifEmpty { "INBOX" }

    companion object {
        fun defaultPort(protocol: MailProtocol, security: MailSecurity): Int = when (protocol) {
            MailProtocol.IMAP -> if (security == MailSecurity.TLS) 993 else 143
            MailProtocol.POP3 -> if (security == MailSecurity.TLS) 995 else 110
        }

        /** Posteingangs-Server gängiger Anbieter; sonst imap.<domain> bzw. pop.<domain>. */
        fun autoHost(email: String, protocol: MailProtocol): String {
            val domain = email.substringAfterLast('@', "").trim().lowercase()
            if (domain.isEmpty()) return ""
            val imap = protocol == MailProtocol.IMAP
            return when (domain) {
                "web.de" -> if (imap) "imap.web.de" else "pop3.web.de"
                "gmx.de", "gmx.net", "gmx.at", "gmx.ch" -> if (imap) "imap.gmx.net" else "pop.gmx.net"
                "gmail.com", "googlemail.com" -> if (imap) "imap.gmail.com" else "pop.gmail.com"
                "posteo.de", "posteo.net" -> "posteo.de"
                "mailbox.org" -> if (imap) "imap.mailbox.org" else "pop3.mailbox.org"
                "t-online.de" -> if (imap) "secureimap.t-online.de" else "securepop.t-online.de"
                "icloud.com", "me.com", "mac.com" -> "imap.mail.me.com" // iCloud kennt kein POP3
                else -> if (imap) "imap.$domain" else "pop.$domain"
            }
        }
    }
}

/**
 * Zeilenbasierte Verbindung zu einem Mailserver, wahlweise direkt verschlüsselt (SSL/TLS)
 * oder unverschlüsselt mit anschließendem STARTTLS. Unverschlüsselte Anmeldung gibt es bewusst nicht.
 */
internal class MailSocket(private val host: String, private val port: Int) : Closeable {
    private var socket: Socket? = null
    private lateinit var input: BufferedInputStream
    private lateinit var output: OutputStream

    fun open(security: MailSecurity) {
        if (host.isBlank()) throw MailException("Kein Mailserver angegeben.")
        try {
            val plain = Socket()
            plain.connect(InetSocketAddress(host, port), 15_000)
            attach(plain)
            // Direkt verschlüsselt: sofort TLS darüberlegen (mit Servername für SNI und Zertifikatsprüfung).
            if (security == MailSecurity.TLS) upgradeToTls()
        } catch (e: UnknownHostException) {
            throw MailException("Mailserver $host nicht gefunden.")
        } catch (e: java.net.SocketTimeoutException) {
            throw MailException("Keine Antwort von $host:$port. Stimmen Server und Port?")
        } catch (e: java.net.ConnectException) {
            throw MailException("Verbindung zu $host:$port abgelehnt. Stimmen Server und Port?")
        } catch (e: javax.net.ssl.SSLException) {
            throw MailException("Verschlüsselung mit $host:$port fehlgeschlagen. Passt die Einstellung SSL/TLS oder STARTTLS zum Port?")
        }
    }

    /** Nach STARTTLS bzw. STLS: bestehende Verbindung auf TLS umstellen. */
    fun upgradeToTls() {
        val plain = socket ?: throw MailException("Keine Verbindung")
        val factory = SSLSocketFactory.getDefault() as SSLSocketFactory
        val ssl = factory.createSocket(plain, host, port, true) as SSLSocket
        secure(ssl)
        attach(ssl)
    }

    private fun secure(ssl: SSLSocket) {
        // SSLSocketFactory prüft den Hostnamen nicht von selbst – hier ausdrücklich einschalten.
        val params = ssl.sslParameters
        params.endpointIdentificationAlgorithm = "HTTPS"
        ssl.sslParameters = params
        ssl.soTimeout = 30_000
        ssl.startHandshake()
    }

    private fun attach(s: Socket) {
        s.soTimeout = 30_000
        socket = s
        input = BufferedInputStream(s.inputStream)
        output = s.outputStream
    }

    fun write(line: String) {
        output.write("$line\r\n".toByteArray(Charsets.UTF_8))
        output.flush()
    }

    fun readLine(): String {
        val out = ByteArrayOutputStream()
        while (true) {
            val b = input.read()
            if (b == -1) throw MailException("Verbindung zum Mailserver unterbrochen")
            if (b == '\n'.code) break
            if (b != '\r'.code) out.write(b)
        }
        return String(out.toByteArray(), Charsets.ISO_8859_1)
    }

    fun readBytes(size: Int): ByteArray {
        val buffer = ByteArray(size)
        var read = 0
        while (read < size) {
            val n = input.read(buffer, read, size - read)
            if (n == -1) throw MailException("Verbindung zum Mailserver unterbrochen")
            read += n
        }
        return buffer
    }

    override fun close() {
        // TLS wartet beim Schließen auf die Abschlussmeldung des Servers – nicht ewig.
        runCatching { socket?.soTimeout = 1_000 }
        runCatching { socket?.close() }
        socket = null
    }
}

/** Gemeinsame Schnittstelle für IMAP und POP3: nur lesen, nichts löschen. */
internal interface MailReader : Closeable {
    /** Verbinden und anmelden. */
    fun open()

    /** Schlüssel aller Mails, die von bahn.de stammen könnten (neueste zuerst). */
    fun candidateKeys(lookbackDays: Long): List<String>

    /** Komplette Mail zu einem Schlüssel aus [candidateKeys]. */
    fun fetch(key: String): ByteArray?

    fun quit()

    companion object {
        fun create(settings: MailSettings, password: String): MailReader = when (settings.protocol) {
            MailProtocol.IMAP -> ImapReader(settings, password)
            MailProtocol.POP3 -> Pop3Reader(settings, password)
        }
    }
}
