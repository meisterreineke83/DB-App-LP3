package io.github.lightbahn

import io.ktor.client.HttpClient
import io.ktor.client.call.body
import io.ktor.client.engine.okhttp.OkHttp
import io.ktor.client.plugins.HttpTimeout
import io.ktor.client.plugins.contentnegotiation.ContentNegotiation
import io.ktor.client.request.get
import io.ktor.client.request.header
import io.ktor.client.request.parameter
import io.ktor.client.statement.HttpResponse
import io.ktor.client.statement.bodyAsText
import io.ktor.http.isSuccess
import io.ktor.serialization.kotlinx.json.json
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import java.time.OffsetDateTime
import java.time.format.DateTimeFormatter

/**
 * Fahrplandaten über die öffentliche db-rest-Instanz (v6.db.transport.rest).
 * Kein API-Schlüssel nötig; Limit laut Betreiber ca. 100 Anfragen pro Minute.
 * Die Antworten folgen dem "Friendly Public Transport Format" (FPTF).
 */
private const val API_BASE = "https://v6.db.transport.rest"
private const val USER_AGENT = "LightBahn/0.1 (Light Phone III community tool)"

@Serializable
data class Station(
    val type: String? = null,
    val id: String? = null,
    val name: String? = null,
)

@Serializable
data class Line(
    val name: String? = null,
    val productName: String? = null,
    val mode: String? = null,
)

@Serializable
data class StopRef(
    val id: String? = null,
    val name: String? = null,
)

@Serializable
data class Leg(
    val origin: StopRef? = null,
    val destination: StopRef? = null,
    val departure: String? = null,
    val plannedDeparture: String? = null,
    val departureDelay: Int? = null,
    val departurePlatform: String? = null,
    val plannedDeparturePlatform: String? = null,
    val arrival: String? = null,
    val plannedArrival: String? = null,
    val arrivalDelay: Int? = null,
    val arrivalPlatform: String? = null,
    val plannedArrivalPlatform: String? = null,
    val line: Line? = null,
    val direction: String? = null,
    val walking: Boolean = false,
    val cancelled: Boolean = false,
    val distance: Int? = null,
)

@Serializable
data class Journey(
    val legs: List<Leg> = emptyList(),
)

@Serializable
internal data class JourneysResponse(
    val journeys: List<Journey> = emptyList(),
    val laterRef: String? = null,
)

data class JourneyPage(
    val journeys: List<Journey>,
    val laterRef: String?,
)

internal class BahnApi {
    private val json = Json {
        ignoreUnknownKeys = true
        coerceInputValues = true
        explicitNulls = false
    }

    private val client = HttpClient(OkHttp) {
        install(ContentNegotiation) { json(json) }
        install(HttpTimeout) {
            requestTimeoutMillis = 25_000
            connectTimeoutMillis = 15_000
        }
    }

    suspend fun searchStations(query: String): List<Station> {
        val response = client.get("$API_BASE/locations") {
            header("User-Agent", USER_AGENT)
            parameter("query", query.trim())
            parameter("results", 8)
            parameter("stops", true)
            parameter("addresses", false)
            parameter("poi", false)
            parameter("language", "de")
        }
        response.ensureOk()
        val stations: List<Station> = response.body()
        return stations.filter { !it.id.isNullOrBlank() && !it.name.isNullOrBlank() }
    }

    /**
     * @param departure Abfahrtszeit, null = jetzt
     * @param laterRef  Fortsetzungs-Token für "spätere Verbindungen"
     */
    suspend fun searchJourneys(
        fromId: String,
        toId: String,
        departure: OffsetDateTime?,
        laterRef: String? = null,
    ): JourneyPage {
        val response = client.get("$API_BASE/journeys") {
            header("User-Agent", USER_AGENT)
            parameter("from", fromId)
            parameter("to", toId)
            if (laterRef != null) {
                parameter("laterThan", laterRef)
            } else if (departure != null) {
                parameter("departure", departure.format(DateTimeFormatter.ISO_OFFSET_DATE_TIME))
            }
            parameter("results", 6)
            parameter("stopovers", false)
            parameter("remarks", false)
            parameter("language", "de")
        }
        response.ensureOk()
        val page: JourneysResponse = response.body()
        return JourneyPage(page.journeys, page.laterRef)
    }

    fun close() = client.close()

    private suspend fun HttpResponse.ensureOk() {
        if (!status.isSuccess()) {
            val text = bodyAsText().take(200)
            throw IllegalStateException(
                when (status.value) {
                    429 -> "Zu viele Anfragen. Bitte kurz warten."
                    in 500..599 -> "Fahrplandienst gerade nicht erreichbar (${status.value})."
                    else -> "Fehler ${status.value}: $text"
                }
            )
        }
    }
}
