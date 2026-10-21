package io.github.lightbahn

import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.stringPreferencesKey
import androidx.lifecycle.viewModelScope
import com.thelightphone.sdk.LightViewModel
import com.thelightphone.sdk.SimpleLightScreen
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.File
import java.time.ZonedDateTime

enum class Field { FROM, TO, TIME, EMAIL, PASSWORD, SERVER, PORT, FOLDER }

sealed interface Mode {
    data object Home : Mode
    data class EditText(val field: Field, val title: String, val initial: String) : Mode
    data class PickStation(val field: Field, val results: List<Station>) : Mode
    data object Results : Mode
    data class Detail(val journey: Journey) : Mode
    data class Tickets(val highlight: List<Long> = emptyList()) : Mode
    data class TicketView(val entry: TicketEntry, val fileIndex: Int) : Mode
    data object Settings : Mode
}

data class BahnUiState(
    val stack: List<Mode> = listOf(Mode.Home),
    val busy: String? = null,
    val message: String? = null,
    val from: Station? = null,
    val to: Station? = null,
    val departure: ZonedDateTime? = null,
    val journeys: List<Journey> = emptyList(),
    val laterRef: String? = null,
    val loadingMore: Boolean = false,
    val tickets: List<TicketEntry> = emptyList(),
    val mail: MailSettings = MailSettings(),
    val hasPassword: Boolean = false,
    val editSession: Int = 0,
) {
    val mode: Mode get() = stack.last()
    val email: String get() = mail.email
}

private object Keys {
    val FROM_ID = stringPreferencesKey("from_id_v2")
    val FROM_NAME = stringPreferencesKey("from_name_v2")
    val TO_ID = stringPreferencesKey("to_id_v2")
    val TO_NAME = stringPreferencesKey("to_name_v2")
    val EMAIL = stringPreferencesKey("imap_email")
    val PASSWORD = stringPreferencesKey("imap_password_enc")
    val PROTOCOL = stringPreferencesKey("mail_protocol")
    val SECURITY = stringPreferencesKey("mail_security")
    val HOST = stringPreferencesKey("mail_host")
    val PORT = stringPreferencesKey("mail_port")
    val FOLDER = stringPreferencesKey("mail_folder")
}

private fun Preferences.mailSettings() = MailSettings(
    email = this[Keys.EMAIL].orEmpty(),
    protocol = MailProtocol.entries.firstOrNull { it.name == this[Keys.PROTOCOL] } ?: MailProtocol.IMAP,
    security = MailSecurity.entries.firstOrNull { it.name == this[Keys.SECURITY] } ?: MailSecurity.TLS,
    host = this[Keys.HOST].orEmpty(),
    port = this[Keys.PORT]?.toIntOrNull(),
    folder = this[Keys.FOLDER].orEmpty(),
)

class BahnViewModel internal constructor(
    private val dataStore: DataStore<Preferences>,
    filesDir: File,
) : LightViewModel<Unit>() {

    private val api = BahnApi()
    private val ticketStore = TicketStore(filesDir)

    private val _state = MutableStateFlow(BahnUiState())
    val state: StateFlow<BahnUiState> = _state

    /** Wird nur auf dem Ticket-Bildschirm gesetzt (Bitmaps gehören nicht in den Daten-State). */
    internal val rendered = MutableStateFlow<RenderedTicket?>(null)

    private var loaded = false
    private var lastTicketRefresh = 0L

    override fun onScreenShow(screen: SimpleLightScreen<Unit>) {
        super.onScreenShow(screen)
        if (loaded) return
        loaded = true
        viewModelScope.launch {
            val prefs = dataStore.data.first()
            val from = station(prefs[Keys.FROM_ID], prefs[Keys.FROM_NAME])
            val to = station(prefs[Keys.TO_ID], prefs[Keys.TO_NAME])
            val tickets = withContext(Dispatchers.IO) { ticketStore.tickets() }
            _state.update {
                it.copy(
                    from = from,
                    to = to,
                    mail = prefs.mailSettings(),
                    hasPassword = !prefs[Keys.PASSWORD].isNullOrEmpty(),
                    tickets = tickets,
                )
            }
        }
    }

    private fun station(id: String?, name: String?) =
        if (id.isNullOrBlank() || name.isNullOrBlank()) null else Station("station", id, name)

    // ---------- Navigation ----------

    private fun push(mode: Mode) = _state.update { it.copy(stack = it.stack + mode, message = null) }

    private fun pop() = _state.update {
        if (it.stack.size > 1) it.copy(stack = it.stack.dropLast(1), message = null) else it
    }

    override fun onBackPressed(): Boolean {
        val s = _state.value
        if (s.busy != null) return true
        if (s.stack.size <= 1) return false
        if (s.mode is Mode.TicketView) rendered.value = null
        pop()
        return true
    }

    fun back() {
        onBackPressed()
    }

    fun clearMessage() = _state.update { it.copy(message = null) }

    private fun fail(e: Throwable) {
        _state.update { it.copy(busy = null, loadingMore = false, message = e.message ?: "Unbekannter Fehler") }
    }

    // ---------- Eingabe ----------

    fun edit(field: Field) {
        val s = _state.value
        val (title, initial) = when (field) {
            Field.FROM -> "Von" to ""
            Field.TO -> "Nach" to ""
            Field.TIME -> "Abfahrt (z. B. 17:30)" to ""
            Field.EMAIL -> "Ticket-Mailadresse" to s.email
            Field.PASSWORD -> "App-Passwort" to ""
            Field.SERVER -> "Server (leer = automatisch)" to s.mail.host
            Field.PORT -> "Port (leer = automatisch)" to (s.mail.port?.toString() ?: "")
            Field.FOLDER -> "Ordner (leer = INBOX)" to s.mail.folder
        }
        _state.update { it.copy(editSession = it.editSession + 1) }
        push(Mode.EditText(field, title, initial))
    }

    fun submitText(text: CharSequence) {
        val mode = _state.value.mode as? Mode.EditText ?: return
        val value = text.toString().trim()
        pop()
        when (mode.field) {
            Field.FROM, Field.TO -> if (value.isNotEmpty()) searchStations(mode.field, value)
            Field.TIME -> try {
                val departure = parseDeparture(value)
                _state.update { it.copy(departure = departure) }
            } catch (e: Exception) {
                fail(e)
            }
            Field.EMAIL -> viewModelScope.launch {
                dataStore.edit { it[Keys.EMAIL] = value }
                _state.update { it.copy(mail = it.mail.copy(email = value)) }
            }
            Field.SERVER -> viewModelScope.launch {
                dataStore.edit { it[Keys.HOST] = value }
                _state.update { it.copy(mail = it.mail.copy(host = value)) }
            }
            Field.PORT -> {
                val port = value.toIntOrNull()
                if (value.isNotEmpty() && (port == null || port !in 1..65535)) {
                    _state.update { it.copy(message = "Ungültiger Port: $value") }
                } else viewModelScope.launch {
                    dataStore.edit { if (port == null) it.remove(Keys.PORT) else it[Keys.PORT] = port.toString() }
                    _state.update { it.copy(mail = it.mail.copy(port = port)) }
                }
            }
            Field.FOLDER -> viewModelScope.launch {
                dataStore.edit { it[Keys.FOLDER] = value }
                _state.update { it.copy(mail = it.mail.copy(folder = value)) }
            }
            Field.PASSWORD -> if (value.isNotEmpty()) viewModelScope.launch {
                val encrypted = withContext(Dispatchers.IO) { CredentialCipher.encrypt(value) }
                dataStore.edit { it[Keys.PASSWORD] = encrypted }
                _state.update { it.copy(hasPassword = true, message = "App-Passwort gespeichert.") }
            }
        }
    }

    private fun searchStations(field: Field, query: String) {
        _state.update { it.copy(busy = "Suche Bahnhöfe …") }
        viewModelScope.launch {
            try {
                val results = withContext(Dispatchers.IO) { api.searchStations(query) }
                _state.update { it.copy(busy = null) }
                when {
                    results.isEmpty() -> fail(IllegalStateException("Kein Bahnhof zu „$query“ gefunden."))
                    else -> push(Mode.PickStation(field, results))
                }
            } catch (e: Exception) {
                fail(e)
            }
        }
    }

    fun pickStation(station: Station) {
        val mode = _state.value.mode as? Mode.PickStation ?: return
        pop()
        _state.update { if (mode.field == Field.FROM) it.copy(from = station) else it.copy(to = station) }
        persistRoute()
    }

    fun swap() {
        _state.update { it.copy(from = it.to, to = it.from) }
        persistRoute()
    }

    fun resetDeparture() = _state.update { it.copy(departure = null) }

    private fun persistRoute() {
        val s = _state.value
        viewModelScope.launch {
            dataStore.edit { prefs ->
                s.from?.let { prefs[Keys.FROM_ID] = it.id.orEmpty(); prefs[Keys.FROM_NAME] = it.name.orEmpty() }
                s.to?.let { prefs[Keys.TO_ID] = it.id.orEmpty(); prefs[Keys.TO_NAME] = it.name.orEmpty() }
            }
        }
    }

    // ---------- Verbindungen ----------

    fun search() {
        val s = _state.value
        val from = s.from?.id
        val to = s.to?.id
        if (from == null || to == null) {
            _state.update { it.copy(message = "Bitte Start und Ziel wählen.") }
            return
        }
        _state.update { it.copy(busy = "Suche Verbindungen …") }
        viewModelScope.launch {
            try {
                val page = withContext(Dispatchers.IO) {
                    api.searchJourneys(from, to, s.departure?.toOffsetDateTime())
                }
                _state.update { it.copy(busy = null, journeys = page.journeys, laterRef = page.laterRef) }
                if (page.journeys.isEmpty()) {
                    _state.update { it.copy(message = "Keine Verbindungen gefunden.") }
                } else {
                    push(Mode.Results)
                }
            } catch (e: Exception) {
                fail(e)
            }
        }
    }

    fun loadLater() {
        val s = _state.value
        val ref = s.laterRef ?: return
        val from = s.from?.id ?: return
        val to = s.to?.id ?: return
        if (s.loadingMore) return
        _state.update { it.copy(loadingMore = true) }
        viewModelScope.launch {
            try {
                val page = withContext(Dispatchers.IO) { api.searchJourneys(from, to, null, ref) }
                _state.update {
                    it.copy(loadingMore = false, journeys = it.journeys + page.journeys, laterRef = page.laterRef)
                }
            } catch (e: Exception) {
                fail(e)
            }
        }
    }

    fun openJourney(journey: Journey) = push(Mode.Detail(journey))

    /** Fahrkarte zur aktuellen Verbindung: passt genau eine, wird sie direkt geöffnet. */
    fun openTicketFor(journey: Journey) {
        val from = journey.legs.firstOrNull()?.origin?.name ?: _state.value.from?.name
        val to = journey.legs.lastOrNull()?.destination?.name ?: _state.value.to?.name
        val matches = ticketStore.matching(from, to)
        if (matches.size == 1) openTicket(matches.first()) else push(Mode.Tickets(matches.map { it.uid }))
    }

    // ---------- Fahrkarten ----------

    /** Öffnet die Ticket-Liste und schaut dabei nach neuen Mails (höchstens alle 10 Minuten). */
    fun openTickets() {
        push(Mode.Tickets())
        val s = _state.value
        val due = System.currentTimeMillis() - lastTicketRefresh > 10 * 60_000
        if (s.email.isNotBlank() && s.hasPassword && due) refreshTickets()
    }

    fun refreshTickets() {
        viewModelScope.launch {
            val prefs = dataStore.data.first()
            val mail = prefs.mailSettings()
            val password = prefs[Keys.PASSWORD]?.let { CredentialCipher.decrypt(it) }
            if (mail.email.isBlank() || password.isNullOrEmpty()) {
                push(Mode.Settings)
                _state.update { it.copy(message = "Bitte zuerst Ticket-Mailadresse und App-Passwort eintragen.") }
                return@launch
            }
            _state.update { it.copy(busy = "Hole Fahrkarten aus dem Postfach …") }
            lastTicketRefresh = System.currentTimeMillis()
            try {
                val added = withContext(Dispatchers.IO) { ticketStore.refresh(mail, password) }
                val tickets = withContext(Dispatchers.IO) { ticketStore.tickets() }
                _state.update {
                    it.copy(
                        busy = null,
                        tickets = tickets,
                        message = when (added) {
                            0 -> "Keine neuen Fahrkarten."
                            1 -> "1 neue Fahrkarte."
                            else -> "$added neue Fahrkarten."
                        },
                    )
                }
            } catch (e: Exception) {
                fail(e)
            }
        }
    }

    fun openTicket(entry: TicketEntry, fileIndex: Int = 0) {
        val name = entry.files.getOrNull(fileIndex) ?: return
        _state.update { it.copy(busy = "Öffne Fahrkarte …\nBeim ersten Mal dauert die Barcode-Suche ein paar Sekunden.") }
        viewModelScope.launch {
            try {
                val result = withContext(Dispatchers.Default) { TicketRenderer.render(ticketStore.file(name)) }
                rendered.value = result
                _state.update { it.copy(busy = null) }
                push(Mode.TicketView(entry, fileIndex))
            } catch (e: Exception) {
                fail(e)
            }
        }
    }

    fun deleteTicket(entry: TicketEntry) {
        viewModelScope.launch {
            val tickets = withContext(Dispatchers.IO) {
                ticketStore.delete(entry)
                ticketStore.tickets()
            }
            rendered.value = null
            _state.update { it.copy(tickets = tickets) }
            pop()
        }
    }

    // ---------- Einstellungen ----------

    fun openSettings() = push(Mode.Settings)

    /** Wechselt zwischen IMAP und POP3. Ein eingetragener Port wird zurückgesetzt, damit er zum Protokoll passt. */
    fun toggleProtocol() = saveMail { it.copy(protocol = if (it.protocol == MailProtocol.IMAP) MailProtocol.POP3 else MailProtocol.IMAP, port = null) }

    fun toggleSecurity() = saveMail { it.copy(security = if (it.security == MailSecurity.TLS) MailSecurity.STARTTLS else MailSecurity.TLS, port = null) }

    private fun saveMail(change: (MailSettings) -> MailSettings) {
        val updated = change(_state.value.mail)
        _state.update { it.copy(mail = updated) }
        viewModelScope.launch {
            dataStore.edit {
                it[Keys.PROTOCOL] = updated.protocol.name
                it[Keys.SECURITY] = updated.security.name
                if (updated.port == null) it.remove(Keys.PORT) else it[Keys.PORT] = updated.port.toString()
            }
        }
    }

    fun testLogin() {
        viewModelScope.launch {
            val prefs = dataStore.data.first()
            val mail = prefs.mailSettings()
            val password = prefs[Keys.PASSWORD]?.let { CredentialCipher.decrypt(it) }
            if (mail.email.isBlank() || password.isNullOrEmpty()) {
                _state.update { it.copy(message = "Adresse und App-Passwort fehlen noch.") }
                return@launch
            }
            _state.update { it.copy(busy = "Teste Anmeldung bei ${mail.effectiveHost}:${mail.effectivePort} …") }
            try {
                withContext(Dispatchers.IO) { ticketStore.testLogin(mail, password) }
                _state.update { it.copy(busy = null, message = "Anmeldung klappt.") }
            } catch (e: Exception) {
                fail(e)
            }
        }
    }

    fun forgetMailAccess() {
        viewModelScope.launch {
            dataStore.edit {
                listOf(Keys.EMAIL, Keys.PASSWORD, Keys.PROTOCOL, Keys.SECURITY, Keys.HOST, Keys.PORT, Keys.FOLDER)
                    .forEach { key -> it.remove(key) }
            }
            withContext(Dispatchers.IO) { ticketStore.deleteAll() }
            _state.update {
                it.copy(mail = MailSettings(), hasPassword = false, tickets = emptyList(), message = "Zugang und Fahrkarten gelöscht.")
            }
        }
    }

    override fun onCleared() {
        super.onCleared()
        api.close()
    }
}
