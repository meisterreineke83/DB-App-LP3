package io.github.lightbahn

import android.security.keystore.KeyGenParameterSpec
import android.security.keystore.KeyProperties
import kotlinx.serialization.Serializable
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import java.io.File
import java.security.KeyStore
import java.util.Base64
import java.util.Locale
import javax.crypto.Cipher
import javax.crypto.KeyGenerator
import javax.crypto.SecretKey
import javax.crypto.spec.GCMParameterSpec

@Serializable
data class TicketEntry(
    val uid: Long,
    val subject: String,
    val date: String,
    val text: String,
    val files: List<String>,
    /** Kennung der Mail im Postfach – damit ein gelöschtes Ticket beim Aktualisieren wiederkommt. */
    val key: String? = null,
    /** Aus Fahrkarte/Mail gelesen (ISO-Datum, "06:12", Bahnhofsnamen). */
    val travelDate: String? = null,
    val departureTime: String? = null,
    val arrivalTime: String? = null,
    val fromStation: String? = null,
    val toStation: String? = null,
    val infoVersion: Int = 0,
) {
    val route: String?
        get() = if (fromStation != null && toStation != null) "$fromStation → $toStation" else null

    val orderNumber: String?
        get() = Regex("""(?i)auftrag(?:snummer)?\s*:?\s*([A-Z0-9]{6,})""").find(subject)?.groupValues?.get(1)

    fun travelDay(): java.time.LocalDate? = travelDate?.let { runCatching { java.time.LocalDate.parse(it) }.getOrNull() }
}

@Serializable
private data class TicketIndex(
    val tickets: List<TicketEntry> = emptyList(),
    /**
     * Bereits geprüfte Mails, z. B. "imap|imap.web.de|adresse|INBOX|123" oder "pop3|…|<UIDL>".
     * (Neuer Feldname ab 0.7.0: Alle Mails werden einmal neu geprüft, damit jedes Ticket
     * seine Mail-Kennung bekommt. Vorhandene Tickets werden dabei erkannt, nicht doppelt angelegt.)
     */
    val checkedKeys: List<String> = emptyList(),
    /** Mails, deren Ticket auf dem Telefon gelöscht wurde. Beim Aktualisieren werden sie neu geladen. */
    val deletedKeys: List<String> = emptyList(),
)

/** Erhöhen, wenn sich das Auslesen von Datum/Bahnhöfen verbessert → alte Tickets werden neu gelesen. */
private const val INFO_VERSION = 2

/**
 * Holt Fahrkarten-PDFs aus Bahn-Mails und legt sie lokal im Tool-Speicher ab.
 * Nach dem ersten Abruf sind die Tickets auch offline verfügbar.
 */
internal class TicketStore(filesDir: File) {
    private val dir = File(filesDir, "tickets").apply { mkdirs() }
    private val indexFile = File(dir, "index.json")
    private val json = Json { ignoreUnknownKeys = true }

    /**
     * Reihenfolge: anstehende Fahrten zuerst (die nächste oben), danach vergangene
     * (die jüngste oben). Tickets ohne erkanntes Datum stehen nach Eingang dazwischen.
     */
    fun tickets(): List<TicketEntry> {
        val today = java.time.LocalDate.now(BERLIN)
        val all = readIndex().tickets.sortedByDescending { it.uid }
        val upcoming = all.filter { it.travelDay()?.let { d -> !d.isBefore(today) } == true }.sortedBy { it.travelDay() }
        val undated = all.filter { it.travelDay() == null }
        val past = all.filter { it.travelDay()?.isBefore(today) == true }.sortedByDescending { it.travelDay() }
        return upcoming + undated + past
    }

    fun file(name: String): File = File(dir, name)

    data class RefreshResult(val checked: Int, val bahnMails: Int, val withoutPdf: Int, val added: Int)

    /**
     * @param restoreDeleted true beim Tippen auf Aktualisieren: Tickets, die nur auf dem Telefon
     *   gelöscht wurden, kommen zurück, solange die Mail noch im Postfach liegt.
     */
    @Synchronized
    fun refresh(
        settings: MailSettings,
        password: String,
        restoreDeleted: Boolean,
        lookbackDays: Long = 365,
    ): RefreshResult {
        val index = readIndex()
        val checked = index.checkedKeys.toMutableSet()
        val deleted = index.deletedKeys.toMutableSet()
        var checkedCount = 0
        var bahnMails = 0
        var withoutPdf = 0
        val tickets = index.tickets.toMutableList()
        var nextId = (tickets.maxOfOrNull { it.uid } ?: 0L) + 1
        var added = 0

        MailReader.create(settings, password).use { reader ->
            reader.open()
            val candidates = reader.candidateKeys(lookbackDays)
            if (restoreDeleted) {
                // Aus dem Postfach gelöschte Mails können nicht mehr zurückkommen.
                val present = candidates.toSet()
                deleted.retainAll(present)
            }
            val keys = candidates
                .filter { it !in checked || (restoreDeleted && it in deleted) }
                .take(40)
                .reversed() // älteste zuerst, damit neuere Mails die höheren Nummern bekommen

            for (key in keys) {
                val raw = reader.fetch(key)
                checked += key
                if (raw == null) continue
                checkedCount++
                if (!mentionsBahn(raw)) continue
                bahnMails++
                val mail = MimeParser.parse(raw)
                if (mail.pdfs.isEmpty()) {
                    withoutPdf++
                    continue
                }
                deleted -= key
                if (tickets.any { it.key == key }) continue
                // Ticket aus einer älteren Version (noch ohne Kennung)? Dann nur die Kennung nachtragen.
                val legacy = tickets.indexOfFirst { it.key == null && it.subject == mail.subject.ifBlank { "Fahrkarte" } && it.date == mail.date }
                if (legacy >= 0) {
                    tickets[legacy] = tickets[legacy].copy(key = key)
                    continue
                }
                val id = nextId++
                val files = mail.pdfs.mapIndexed { i, (_, bytes) ->
                    val name = "${id}_$i.pdf"
                    File(dir, name).writeBytes(bytes)
                    name
                }
                tickets += withInfo(
                    TicketEntry(
                        uid = id,
                        subject = mail.subject.ifBlank { "Fahrkarte" },
                        date = mail.date,
                        text = mail.text,
                        files = files,
                        key = key,
                    ),
                )
                added++
            }
            reader.quit()
        }

        writeIndex(TicketIndex(tickets, checked.toList(), deleted.toList()))
        return RefreshResult(checkedCount, bahnMails, withoutPdf, added)
    }

    /**
     * Liest bei Tickets aus älteren Versionen Datum und Bahnhöfe nach.
     * @return true, wenn sich etwas geändert hat.
     */
    @Synchronized
    fun fillMissingInfo(): Boolean {
        val index = readIndex()
        if (index.tickets.none { it.infoVersion < INFO_VERSION }) return false
        val updated = index.tickets.map { if (it.infoVersion < INFO_VERSION) withInfo(it) else it }
        writeIndex(index.copy(tickets = updated))
        return true
    }

    private fun withInfo(entry: TicketEntry): TicketEntry {
        val pdfText = entry.files.joinToString("\n\n") { name ->
            runCatching { PdfText.extract(File(dir, name).readBytes()) }.getOrDefault("")
        }
        val booked = runCatching {
            java.time.ZonedDateTime.parse(
                entry.date.substringBefore(" ("),
                java.time.format.DateTimeFormatter.RFC_1123_DATE_TIME,
            ).toLocalDate()
        }.getOrNull()
        val info = runCatching { TicketInfoParser.parse(pdfText, entry.text, booked) }.getOrDefault(TicketInfo())
        return entry.copy(
            travelDate = info.date?.toString(),
            departureTime = info.time,
            arrivalTime = info.arrival,
            fromStation = info.from,
            toStation = info.to,
            infoVersion = INFO_VERSION,
        )
    }

    /** Prüft nur Verbindung und Anmeldung, ohne etwas zu laden. */
    fun testLogin(settings: MailSettings, password: String) {
        MailReader.create(settings, password).use { reader ->
            reader.open()
            reader.quit()
        }
    }

    @Synchronized
    fun delete(entry: TicketEntry) {
        entry.files.forEach {
            File(dir, it).delete()
            File(dir, "$it.code.png").delete()
            File(dir, "$it.code2.png").delete()
        }
        val index = readIndex()
        writeIndex(
            index.copy(
                tickets = index.tickets.filterNot { it.uid == entry.uid },
                deletedKeys = (index.deletedKeys + listOfNotNull(entry.key)).distinct(),
            ),
        )
    }

    fun deleteAll() {
        dir.listFiles()?.forEach { it.delete() }
    }

    private fun readIndex(): TicketIndex =
        runCatching { json.decodeFromString<TicketIndex>(indexFile.readText()) }
            .getOrDefault(TicketIndex())

    private fun writeIndex(index: TicketIndex) {
        val tmp = File(dir, "index.json.tmp")
        tmp.writeText(json.encodeToString(index))
        tmp.renameTo(indexFile)
    }
}

/**
 * Verschlüsselt das App-Passwort mit einem Schlüssel aus dem Android Keystore,
 * bevor es im Tool-Speicher abgelegt wird.
 */
internal object CredentialCipher {
    private const val ALIAS = "lightbahn_imap"
    private const val TRANSFORMATION = "AES/GCM/NoPadding"

    private fun key(): SecretKey {
        val keyStore = KeyStore.getInstance("AndroidKeyStore").apply { load(null) }
        (keyStore.getKey(ALIAS, null) as? SecretKey)?.let { return it }
        val generator = KeyGenerator.getInstance(KeyProperties.KEY_ALGORITHM_AES, "AndroidKeyStore")
        generator.init(
            KeyGenParameterSpec.Builder(
                ALIAS,
                KeyProperties.PURPOSE_ENCRYPT or KeyProperties.PURPOSE_DECRYPT,
            )
                .setBlockModes(KeyProperties.BLOCK_MODE_GCM)
                .setEncryptionPaddings(KeyProperties.ENCRYPTION_PADDING_NONE)
                .setKeySize(256)
                .build()
        )
        return generator.generateKey()
    }

    fun encrypt(plain: String): String {
        val cipher = Cipher.getInstance(TRANSFORMATION)
        cipher.init(Cipher.ENCRYPT_MODE, key())
        val encrypted = cipher.doFinal(plain.toByteArray(Charsets.UTF_8))
        return Base64.getEncoder().encodeToString(cipher.iv + encrypted)
    }

    fun decrypt(stored: String): String? = runCatching {
        val data = Base64.getDecoder().decode(stored)
        val cipher = Cipher.getInstance(TRANSFORMATION)
        cipher.init(Cipher.DECRYPT_MODE, key(), GCMParameterSpec(128, data, 0, 12))
        String(cipher.doFinal(data, 12, data.size - 12), Charsets.UTF_8)
    }.getOrNull()
}
