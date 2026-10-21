package io.github.lightbahn

import android.security.keystore.KeyGenParameterSpec
import android.security.keystore.KeyProperties
import kotlinx.serialization.Serializable
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import java.io.File
import java.security.KeyStore
import java.time.LocalDate
import java.time.format.DateTimeFormatter
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
)

@Serializable
private data class TicketIndex(
    val tickets: List<TicketEntry> = emptyList(),
    val checkedUids: List<Long> = emptyList(),
)

/**
 * Holt Fahrkarten-PDFs aus Bahn-Mails und legt sie lokal im Tool-Speicher ab.
 * Nach dem ersten Abruf sind die Tickets auch offline verfügbar.
 */
internal class TicketStore(filesDir: File) {
    private val dir = File(filesDir, "tickets").apply { mkdirs() }
    private val indexFile = File(dir, "index.json")
    private val json = Json { ignoreUnknownKeys = true }

    fun tickets(): List<TicketEntry> = readIndex().tickets.sortedByDescending { it.uid }

    fun file(name: String): File = File(dir, name)

    /** @return Anzahl neu gefundener Fahrkarten */
    fun refresh(email: String, password: String, lookbackDays: Long = 120): Int {
        val index = readIndex()
        val known = index.checkedUids.toMutableSet()
        val tickets = index.tickets.toMutableList()
        var added = 0

        ImapClient(ImapClient.hostFor(email)).use { imap ->
            imap.connect()
            imap.login(email, password)
            imap.examine("INBOX")
            val since = LocalDate.now().minusDays(lookbackDays)
                .format(DateTimeFormatter.ofPattern("dd-MMM-yyyy", Locale.US))
            val uids = imap.uidSearch("SINCE $since FROM \"bahn.de\"")
                .filter { it !in known }
                .sortedDescending()
                .take(25)

            for (uid in uids) {
                val raw = imap.fetchMessage(uid) ?: continue
                known += uid
                val mail = MimeParser.parse(raw)
                if (mail.pdfs.isEmpty()) continue
                val files = mail.pdfs.mapIndexed { i, (_, bytes) ->
                    val name = "${uid}_$i.pdf"
                    File(dir, name).writeBytes(bytes)
                    name
                }
                tickets += TicketEntry(
                    uid = uid,
                    subject = mail.subject.ifBlank { "Fahrkarte" },
                    date = mail.date,
                    text = mail.text,
                    files = files,
                )
                added++
            }
            imap.logout()
        }

        writeIndex(TicketIndex(tickets, known.toList()))
        return added
    }

    /** Prüft nur die Anmeldung, ohne etwas zu laden. */
    fun testLogin(email: String, password: String) {
        ImapClient(ImapClient.hostFor(email)).use { imap ->
            imap.connect()
            imap.login(email, password)
            imap.examine("INBOX")
            imap.logout()
        }
    }

    fun delete(entry: TicketEntry) {
        entry.files.forEach {
            File(dir, it).delete()
            File(dir, "$it.code.png").delete()
        }
        val index = readIndex()
        writeIndex(index.copy(tickets = index.tickets.filterNot { it.uid == entry.uid }))
    }

    fun deleteAll() {
        dir.listFiles()?.forEach { it.delete() }
    }

    /**
     * Sucht Fahrkarten, deren Mail beide Orte der Verbindung erwähnt.
     * Einfache Heuristik: verglichen wird der Ortsname ohne Zusätze wie "Hbf".
     */
    fun matching(fromName: String?, toName: String?): List<TicketEntry> {
        val from = placeKey(fromName) ?: return emptyList()
        val to = placeKey(toName) ?: return emptyList()
        return tickets().filter { entry ->
            val haystack = (entry.subject + " " + entry.text).lowercase(Locale.GERMAN)
            from in haystack && to in haystack
        }
    }

    private fun placeKey(name: String?): String? {
        if (name.isNullOrBlank()) return null
        return name.lowercase(Locale.GERMAN)
            .replace(Regex("\\(.*?\\)"), " ")
            .split(Regex("[\\s,/-]+"))
            .firstOrNull { it.length >= 3 && it !in setOf("bahnhof", "hbf", "s+u", "u+s") }
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
