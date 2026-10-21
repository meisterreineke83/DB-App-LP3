package io.github.lightbahn

import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.text.input.rememberTextFieldState
import androidx.compose.foundation.text.input.setTextAndPlaceCursorAtEnd
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.setValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.FilterQuality
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import com.thelightphone.sdk.InitialScreen
import com.thelightphone.sdk.LightScreen
import com.thelightphone.sdk.SealedLightActivity
import com.thelightphone.sdk.rememberKeyboardOptions
import com.thelightphone.sdk.ui.LightBarButton
import com.thelightphone.sdk.ui.LightBottomBar
import com.thelightphone.sdk.ui.LightIcon
import com.thelightphone.sdk.ui.LightIcons
import com.thelightphone.sdk.ui.LightScrollView
import com.thelightphone.sdk.ui.LightText
import com.thelightphone.sdk.ui.LightTextField
import com.thelightphone.sdk.ui.LightTextInputEditor
import com.thelightphone.sdk.ui.LightTextVariant
import com.thelightphone.sdk.ui.LightTheme
import com.thelightphone.sdk.ui.LightThemeController
import com.thelightphone.sdk.ui.LightThemeTokens
import com.thelightphone.sdk.ui.LightTopBar
import com.thelightphone.sdk.ui.LightTopBarButton
import com.thelightphone.sdk.ui.LightTopBarCenter
import com.thelightphone.sdk.ui.gridUnitsAsDp
import com.thelightphone.sdk.ui.lightClickable

@InitialScreen
class BahnScreen(sealedActivity: SealedLightActivity) :
    LightScreen<Unit, BahnViewModel>(sealedActivity) {

    override val viewModelClass: Class<BahnViewModel>
        get() = BahnViewModel::class.java

    override fun createViewModel(): BahnViewModel =
        BahnViewModel(lightContext.dataStore, lightContext.filesDir)

    @Composable
    override fun Content() {
        val themeColors by LightThemeController.colors.collectAsState()
        val state by viewModel.state.collectAsState()
        val rendered by viewModel.rendered.collectAsState()
        val textFieldState = rememberTextFieldState("")
        val keyboardOptions = rememberKeyboardOptions()

        LightTheme(colors = themeColors) {
            Box(
                modifier = Modifier
                    .fillMaxSize()
                    .background(LightThemeTokens.colors.background),
            ) {
                val busy = state.busy
                val mode = state.mode
                when {
                    busy != null -> BusyContent(busy)

                    mode is Mode.EditText -> {
                        LaunchedEffect(state.editSession) {
                            textFieldState.setTextAndPlaceCursorAtEnd(mode.initial)
                        }
                        LightTextInputEditor(
                            title = mode.title,
                            state = textFieldState,
                            onSubmit = viewModel::submitText,
                            onBack = viewModel::back,
                            keyboardOptionsFlow = keyboardOptions,
                            editorKey = state.editSession,
                            singleLine = true,
                            submitIcon = if (mode.field == Field.FROM || mode.field == Field.TO) {
                                LightIcons.SEARCH
                            } else {
                                LightIcons.ACCEPT
                            },
                            modifier = Modifier.fillMaxSize(),
                        )
                    }

                    mode is Mode.Home -> HomeContent(state)
                    mode is Mode.PickStation -> PickStationContent(mode)
                    mode is Mode.PickDay -> PickDayContent()
                    mode is Mode.ConfirmDelete -> ConfirmDeleteContent(mode.entry)
                    mode is Mode.Results -> ResultsContent(state)
                    mode is Mode.Detail -> DetailContent(mode.journey, state.message)
                    mode is Mode.Tickets -> TicketsContent(state, mode)
                    mode is Mode.TicketView -> TicketViewContent(mode, rendered)
                    mode is Mode.Settings -> SettingsContent(state)
                }
            }
        }
    }

    // ---------------------------------------------------------------- Bausteine

    @Composable
    private fun Page(
        title: String,
        showBack: Boolean = true,
        rightButton: LightTopBarButton? = null,
        message: String? = null,
        bottomItems: List<LightBarButton?> = emptyList(),
        scroll: Boolean = true,
        content: @Composable ColumnScope.() -> Unit,
    ) {
        Column(modifier = Modifier.fillMaxSize()) {
            LightTopBar(
                leftButton = if (showBack) LightBarButton.LightIcon(LightIcons.BACK, onClick = viewModel::back) else null,
                center = LightTopBarCenter.Text(title),
                rightButton = rightButton,
                modifier = Modifier.padding(bottom = 1f.gridUnitsAsDp()),
            )
            if (message != null) {
                LightText(
                    text = message,
                    variant = LightTextVariant.Detail,
                    lighten = true,
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(horizontal = 2f.gridUnitsAsDp())
                        .padding(bottom = 1f.gridUnitsAsDp())
                        .lightClickable { viewModel.clearMessage() },
                )
            }
            val bodyModifier = Modifier
                .weight(1f)
                .fillMaxWidth()
                .padding(horizontal = 2f.gridUnitsAsDp())
            if (scroll) {
                LightScrollView(modifier = bodyModifier, content = content)
            } else {
                Column(modifier = bodyModifier, content = content)
            }
            if (bottomItems.isNotEmpty()) {
                LightBottomBar(items = bottomItems)
            }
        }
    }

    @Composable
    private fun Row2(top: String, bottom: String?, onClick: (() -> Unit)?, strike: Boolean = false) {
        val base = Modifier
            .fillMaxWidth()
            .padding(vertical = 1f.gridUnitsAsDp())
        Column(modifier = if (onClick != null) base.lightClickable(onClick = onClick) else base) {
            LightText(text = top, variant = LightTextVariant.Copy, strikethrough = strike)
            if (!bottom.isNullOrBlank()) {
                LightText(
                    text = bottom,
                    variant = LightTextVariant.Detail,
                    lighten = true,
                    maxLines = 2,
                    overflow = TextOverflow.Ellipsis,
                )
            }
        }
    }

    /** Großer, gut tippbarer Text-Knopf im Inhalt (zusätzlich zur Leiste unten). */
    @Composable
    private fun ActionLink(text: String, onClick: () -> Unit, modifier: Modifier = Modifier, enabled: Boolean = true) {
        LightText(
            text = text,
            variant = LightTextVariant.Subheading,
            lighten = !enabled,
            modifier = modifier
                .lightClickable(enabled = enabled, onClick = onClick)
                .padding(vertical = 1f.gridUnitsAsDp()),
        )
    }

    @Composable
    private fun BusyContent(text: String) {
        Box(modifier = Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
            LightText(
                text = text,
                variant = LightTextVariant.Copy,
                align = TextAlign.Center,
                modifier = Modifier.padding(horizontal = 2f.gridUnitsAsDp()),
            )
        }
    }

    // ---------------------------------------------------------------- Start

    @Composable
    private fun HomeContent(state: BahnUiState) {
        Page(
            title = "Gleis",
            showBack = false,
            rightButton = LightBarButton.LightIcon(LightIcons.SETTINGS, onClick = viewModel::openSettings),
            message = state.message,
            scroll = false,
        ) {
            LightTextField(
                label = "Von",
                value = state.from?.name.orEmpty(),
                placeholder = "Start wählen",
                onClick = { viewModel.edit(Field.FROM) },
            )
            LightTextField(
                label = "Nach",
                value = state.to?.name.orEmpty(),
                placeholder = "Ziel wählen",
                onClick = { viewModel.edit(Field.TO) },
                modifier = Modifier.padding(top = 1.5f.gridUnitsAsDp()),
            )
            LightTextField(
                label = "Abfahrt",
                value = describeDeparture(state.departure),
                placeholder = "Jetzt",
                onClick = viewModel::editDeparture,
                modifier = Modifier.padding(top = 1.5f.gridUnitsAsDp()),
            )
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(top = 2.5f.gridUnitsAsDp()),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically,
            ) {
                ActionLink(
                    text = "Suchen",
                    enabled = state.from != null && state.to != null,
                    onClick = viewModel::search,
                )
                ActionLink(text = "Tickets", onClick = viewModel::openTickets)
            }
        }
    }

    @Composable
    private fun PickDayContent() {
        val today = java.time.LocalDate.now(BERLIN)
        Page(title = "Tag") {
            Row2(top = "Jetzt", bottom = null, onClick = { viewModel.pickDay(null) })
            (0L until 14L).forEach { offset ->
                val day = today.plusDays(offset)
                val label = when (offset) {
                    0L -> "Heute"
                    1L -> "Morgen"
                    else -> dayLabel(day)
                }
                Row2(top = label, bottom = null, onClick = { viewModel.pickDay(day) })
            }
        }
    }

    @Composable
    private fun PickStationContent(mode: Mode.PickStation) {
        Page(title = if (mode.field == Field.FROM) "Start" else "Ziel") {
            mode.results.forEach { station ->
                Row2(top = station.name.orEmpty(), bottom = station.area, onClick = { viewModel.pickStation(station) })
            }
        }
    }

    // ---------------------------------------------------------------- Verbindungen

    @Composable
    private fun ResultsContent(state: BahnUiState) {
        val title = listOfNotNull(state.from?.name, state.to?.name).joinToString(" → ")
        Page(title = "Verbindungen", message = state.message) {
            LightText(
                text = title,
                variant = LightTextVariant.Detail,
                lighten = true,
                maxLines = 2,
                modifier = Modifier.padding(bottom = 1f.gridUnitsAsDp()),
            )
            val first = state.journeys.firstOrNull()?.firstDeparture()?.departure
            var lastDay = formatDay(first)
            state.journeys.forEach { journey ->
                val dep = journey.firstDeparture()
                val arr = journey.lastArrival()
                val day = formatDay(dep?.departure ?: dep?.plannedDeparture)
                if (day != lastDay) {
                    LightText(
                        text = day,
                        variant = LightTextVariant.Detail,
                        modifier = Modifier.padding(top = 1f.gridUnitsAsDp()),
                    )
                    lastDay = day
                }
                val top = formatTime(dep?.plannedDeparture ?: dep?.departure) + formatDelay(dep?.departureDelay) +
                    "  →  " +
                    formatTime(arr?.plannedArrival ?: arr?.arrival) + formatDelay(arr?.arrivalDelay)
                val bottom = if (journey.isCancelled()) "Fällt (teilweise) aus · " + journey.summaryLine() else journey.summaryLine()
                Row2(top = top, bottom = bottom, onClick = { viewModel.openJourney(journey) }, strike = journey.isCancelled())
            }
            if (state.laterRef != null) {
                LightText(
                    text = if (state.loadingMore) "Lade …" else "Spätere Verbindungen",
                    variant = LightTextVariant.Copy,
                    underline = true,
                    modifier = Modifier
                        .padding(vertical = 2f.gridUnitsAsDp())
                        .lightClickable { viewModel.loadLater() },
                )
            }
        }
    }

    @Composable
    private fun DetailContent(journey: Journey, message: String?) {
        Page(
            title = journey.durationText().ifBlank { "Verbindung" },
            message = message,
        ) {
            journey.legs.forEach { leg ->
                if (leg.walking) {
                    val minutes = java.time.Duration.between(
                        parseIso(leg.departure ?: leg.plannedDeparture) ?: return@forEach,
                        parseIso(leg.arrival ?: leg.plannedArrival) ?: return@forEach,
                    ).toMinutes()
                    LightText(
                        text = "Fußweg · $minutes Min" + (leg.distance?.let { " · $it m" } ?: ""),
                        variant = LightTextVariant.Detail,
                        lighten = true,
                        modifier = Modifier.padding(vertical = 1f.gridUnitsAsDp()),
                    )
                    return@forEach
                }
                Column(modifier = Modifier.padding(vertical = 1f.gridUnitsAsDp())) {
                    LightText(
                        text = listOfNotNull(leg.line?.name, leg.direction?.let { "→ $it" }).joinToString(" "),
                        variant = LightTextVariant.Detail,
                        strikethrough = leg.cancelled,
                    )
                    LightText(
                        text = formatTime(leg.plannedDeparture ?: leg.departure) + formatDelay(leg.departureDelay) +
                            "  " + leg.origin?.name.orEmpty(),
                        variant = LightTextVariant.Copy,
                    )
                    LightText(
                        text = formatPlatform(leg.departurePlatform, leg.plannedDeparturePlatform),
                        variant = LightTextVariant.Detail,
                        lighten = true,
                    )
                    LightText(
                        text = formatTime(leg.plannedArrival ?: leg.arrival) + formatDelay(leg.arrivalDelay) +
                            "  " + leg.destination?.name.orEmpty(),
                        variant = LightTextVariant.Copy,
                        modifier = Modifier.padding(top = 0.5f.gridUnitsAsDp()),
                    )
                    LightText(
                        text = formatPlatform(leg.arrivalPlatform, leg.plannedArrivalPlatform),
                        variant = LightTextVariant.Detail,
                        lighten = true,
                    )
                    if (leg.cancelled) {
                        LightText(text = "Fällt aus", variant = LightTextVariant.Detail)
                    }
                }
            }
        }
    }

    // ---------------------------------------------------------------- Fahrkarten

    @Composable
    private fun TicketsContent(state: BahnUiState, mode: Mode.Tickets) {
        val highlighted = state.tickets.filter { it.uid in mode.highlight }
        val others = state.tickets.filterNot { it.uid in mode.highlight }
        Page(
            title = "Fahrkarten",
            rightButton = LightBarButton.LightIcon(LightIcons.REFRESH, onClick = viewModel::syncTickets),
            message = state.message,
        ) {
            if (state.tickets.isEmpty()) {
                LightText(
                    text = "Noch keine Fahrkarten. Tippe oben rechts auf Aktualisieren, " +
                        "um Bahn-Mails aus deinem Ticket-Postfach zu holen.",
                    variant = LightTextVariant.Paragraph,
                    lighten = true,
                )
            }
            if (highlighted.isNotEmpty()) {
                LightText(text = "Passend zur Verbindung", variant = LightTextVariant.Detail)
                highlighted.forEach { TicketRow(it) }
                if (others.isNotEmpty()) {
                    LightText(
                        text = "Weitere",
                        variant = LightTextVariant.Detail,
                        modifier = Modifier.padding(top = 2f.gridUnitsAsDp()),
                    )
                }
            }
            others.forEach { TicketRow(it) }
        }
    }

    @Composable
    private fun TicketRow(entry: TicketEntry) {
        Row(
            modifier = Modifier.fillMaxWidth(),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Box(modifier = Modifier.weight(1f)) {
                Row2(
                    top = entry.route ?: entry.subject,
                    bottom = ticketDetailLine(entry),
                    onClick = { viewModel.openTicket(entry) },
                )
            }
            LightIcon(
                icon = LightIcons.TRASH,
                contentDescription = "Ticket löschen",
                modifier = Modifier
                    .lightClickable { viewModel.confirmDelete(entry) }
                    .padding(start = 1.5f.gridUnitsAsDp(), top = 1f.gridUnitsAsDp(), bottom = 1f.gridUnitsAsDp()),
            )
        }
    }

    @Composable
    private fun ConfirmDeleteContent(entry: TicketEntry) {
        Page(title = "Löschen?", scroll = false) {
            LightText(
                text = entry.route ?: entry.subject,
                variant = LightTextVariant.Copy,
                maxLines = 3,
                overflow = TextOverflow.Ellipsis,
            )
            LightText(
                text = ticketDetailLine(entry),
                variant = LightTextVariant.Detail,
                lighten = true,
                modifier = Modifier.padding(top = 1f.gridUnitsAsDp()),
            )
            LightText(
                text = "Das Ticket wird nur vom Telefon gelöscht. Die Mail bleibt im Postfach – " +
                    "beim Aktualisieren kommt es zurück, solange die Mail dort liegt.",
                variant = LightTextVariant.Detail,
                lighten = true,
                modifier = Modifier.padding(top = 2f.gridUnitsAsDp()),
            )
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(top = 3f.gridUnitsAsDp()),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically,
            ) {
                ActionLink(text = "Abbrechen", onClick = viewModel::back)
                ActionLink(text = "Löschen", onClick = { viewModel.deleteTicket(entry) })
            }
        }
    }

    @Composable
    private fun TicketViewContent(mode: Mode.TicketView, rendered: RenderedTicket?) {
        val entry = mode.entry
        // Nur der Barcode, so groß wie möglich. Ein Tipp darauf blendet Details und Knöpfe ein.
        var showDetails by remember(entry.uid, mode.fileIndex) { mutableStateOf(rendered?.code == null) }
        val bottom = buildList<LightBarButton?> {
            if (entry.files.size > 1) {
                add(LightBarButton.Text("WEITER", onClick = {
                    viewModel.back()
                    viewModel.openTicket(entry, (mode.fileIndex + 1) % entry.files.size)
                }))
            }
        }
        Column(modifier = Modifier.fillMaxSize()) {
            LightTopBar(
                leftButton = LightBarButton.LightIcon(LightIcons.BACK, onClick = viewModel::back),
                center = LightTopBarCenter.Text("Fahrkarte"),
            )
            // Barcode immer schwarz auf weiß, egal welches Farbschema gerade aktiv ist.
            Box(
                modifier = Modifier
                    .weight(1f)
                    .fillMaxWidth()
                    .background(Color.White)
                    .lightClickable { showDetails = !showDetails },
                contentAlignment = Alignment.Center,
            ) {
                when {
                    rendered == null -> LightText("Kein Ticket geladen", LightTextVariant.Copy, color = Color.Black)
                    rendered.code != null -> Image(
                        bitmap = rendered.code.asImageBitmap(),
                        contentDescription = "Barcode der Fahrkarte",
                        contentScale = ContentScale.Fit,
                        filterQuality = FilterQuality.None,
                        modifier = Modifier
                            .fillMaxSize()
                            .padding(0.5f.gridUnitsAsDp()),
                    )
                    else -> LightScrollView(modifier = Modifier.fillMaxSize()) {
                        Image(
                            bitmap = rendered.page.asImageBitmap(),
                            contentDescription = "Fahrkarte (ganze Seite)",
                            contentScale = ContentScale.FillWidth,
                            modifier = Modifier.fillMaxWidth(),
                        )
                    }
                }
            }
            if (showDetails) {
                LightText(
                    text = listOfNotNull(entry.route, ticketDetailLine(entry)).joinToString("\n"),
                    variant = LightTextVariant.Detail,
                    maxLines = 3,
                    overflow = TextOverflow.Ellipsis,
                    modifier = Modifier.padding(horizontal = 2f.gridUnitsAsDp(), vertical = 1f.gridUnitsAsDp()),
                )
                if (rendered != null && rendered.code == null) {
                    LightText(
                        text = "Barcode nicht automatisch gefunden – ganze Seite wird angezeigt.",
                        variant = LightTextVariant.Fine,
                        lighten = true,
                        modifier = Modifier.padding(horizontal = 2f.gridUnitsAsDp()),
                    )
                }
                if (bottom.isNotEmpty()) LightBottomBar(items = bottom)
            }
        }
    }

    // ---------------------------------------------------------------- Einstellungen

    @Composable
    private fun SettingsContent(state: BahnUiState) {
        val mail = state.mail
        val gap = Modifier.padding(top = 2f.gridUnitsAsDp())
        Page(
            title = "Postfach",
            message = state.message,
            bottomItems = listOf(
                LightBarButton.Text("TESTEN", onClick = viewModel::testLogin),
                LightBarButton.Text("LÖSCHEN", onClick = viewModel::forgetMailAccess),
            ),
        ) {
            LightTextField(
                label = "Ticket-Mailadresse",
                value = mail.email,
                placeholder = "tickets@web.de",
                onClick = { viewModel.edit(Field.EMAIL) },
            )
            LightTextField(
                label = "App-Passwort",
                value = if (state.hasPassword) "••••••••" else "",
                placeholder = "eintragen",
                onClick = { viewModel.edit(Field.PASSWORD) },
                modifier = gap,
            )

            LightText(
                text = "Posteingangs-Server",
                variant = LightTextVariant.Subheading,
                modifier = Modifier.padding(top = 3f.gridUnitsAsDp()),
            )
            LightTextField(
                label = "Protokoll (tippen zum Wechseln)",
                value = mail.protocol.label,
                placeholder = "IMAP",
                onClick = viewModel::toggleProtocol,
                modifier = gap,
            )
            LightTextField(
                label = "Server",
                value = mail.host,
                placeholder = mail.effectiveHost.ifEmpty { "automatisch" }.let { "automatisch: $it" },
                onClick = { viewModel.edit(Field.SERVER) },
                modifier = gap,
            )
            LightTextField(
                label = "Port",
                value = mail.port?.toString().orEmpty(),
                placeholder = "automatisch: ${mail.effectivePort}",
                onClick = { viewModel.edit(Field.PORT) },
                modifier = gap,
            )
            LightTextField(
                label = "Verschlüsselung (tippen zum Wechseln)",
                value = mail.security.label,
                placeholder = "SSL/TLS",
                onClick = viewModel::toggleSecurity,
                modifier = gap,
            )
            if (mail.protocol == MailProtocol.IMAP) {
                LightTextField(
                    label = "Ordner",
                    value = mail.folder,
                    placeholder = "INBOX (Posteingang)",
                    onClick = { viewModel.edit(Field.FOLDER) },
                    modifier = gap,
                )
            }

            LightText(
                text = buildString {
                    append("Nutze eine eigene Adresse nur für Bahn-Tickets und trage sie bei bahn.de ein. ")
                    append("So sieht das Tool nie dein normales Postfach.\n\n")
                    append("Server und Port findest du auf der Hilfeseite deines Anbieters unter ")
                    append("„IMAP“ oder „POP3“. SMTP brauchst du nicht – das ist nur zum Senden. ")
                    append("Bei IMAP und POP3 muss der Zugriff beim Anbieter oft erst eingeschaltet werden ")
                    append("(web.de/GMX: Einstellungen → POP3/IMAP).\n\n")
                    if (mail.protocol == MailProtocol.POP3) {
                        append("POP3: Das Tool löscht nichts. Manche Anbieter haben aber eine eigene Einstellung ")
                        append("„Mails nach Abruf löschen“ – die sollte aus sein. IMAP ist meist die bessere Wahl.\n\n")
                    }
                    append("Das Tool liest nur, lädt ausschließlich Mails von bahn.de und speichert deren ")
                    append("PDF-Fahrkarten auf dem Telefon. Das Passwort liegt verschlüsselt.")
                },
                variant = LightTextVariant.Detail,
                lighten = true,
                modifier = Modifier.padding(top = 3f.gridUnitsAsDp(), bottom = 2f.gridUnitsAsDp()),
            )
        }
    }
}

/** z. B. "Fr. 23.10.2026 · ab 06:12 · Auftrag 925125168779" – ohne erkanntes Datum das Maildatum. */
private fun ticketDetailLine(entry: TicketEntry): String {
    val day = entry.travelDay()
    val parts = buildList {
        if (day != null) {
            add(formatTravelDay(day))
            entry.departureTime?.let { add("ab $it") }
        } else {
            add("Mail vom " + mailDate(entry.date))
        }
        if (entry.route != null) entry.orderNumber?.let { add("Auftrag $it") }
        if (entry.files.size > 1) add("${entry.files.size} PDFs")
    }
    return parts.joinToString(" · ")
}

private fun formatTravelDay(day: java.time.LocalDate): String {
    val today = java.time.LocalDate.now(BERLIN)
    val date = day.format(java.time.format.DateTimeFormatter.ofPattern("EE dd.MM.yyyy", java.util.Locale.GERMAN))
    return when (day) {
        today -> "heute, $date"
        today.plusDays(1) -> "morgen, $date"
        else -> date
    }
}

private fun mailDate(raw: String): String = runCatching {
    java.time.ZonedDateTime.parse(raw.substringBefore(" ("), java.time.format.DateTimeFormatter.RFC_1123_DATE_TIME)
        .withZoneSameInstant(BERLIN)
        .format(java.time.format.DateTimeFormatter.ofPattern("dd.MM.yyyy"))
}.getOrDefault(raw.take(16))
