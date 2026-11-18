package com.codingpitch.aircrafttracker.routes

import io.ktor.client.*
import io.ktor.client.request.*
import io.ktor.client.statement.*
import java.io.Closeable
import java.util.Locale
import kotlinx.coroutines.*
import kotlinx.coroutines.sync.Semaphore
import kotlinx.coroutines.sync.withPermit
import kotlinx.serialization.json.Json

/** Callsign database matches, not proof of a particular day's actual flight plan. */
class RouteLookupService(
    private val client: HttpClient,
    private val baseUrl: String = "https://api.adsbdb.com/v0",
    private val clock: () -> Long = System::currentTimeMillis,
    private val fallbackUrl: String = "https://vrs-standing-data.adsb.lol/routes",
    private val thirdUrl: String = "https://hexdb.io/api/v2/routes"
) : Closeable {
    private data class Entry(val result: RouteResult, val expires: Long)

    private val cache = LinkedHashMap<String, Entry>()
    private val airlines = LinkedHashMap<String, Pair<RouteAirline?, Long>>()
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private val permits = Semaphore(2)
    private val json = Json { ignoreUnknownKeys = true }

    // Returns immediately: an internet outage must never delay local aircraft updates.
    @Synchronized
    fun routeFor(rawCallsign: String?, aircraft: com.codingpitch.aircrafttracker.model.Aircraft? = null): RouteResult {
        val callsign = rawCallsign?.trim()?.uppercase(Locale.ROOT).orEmpty()
        if (!callsign.matches(Regex("[A-Z0-9]{3,8}"))) return RouteResult("no-callsign")
        val movement = if (aircraft != null && aircraft.distanceKm <= 30 &&
            aircraft.altitudeFeet != null && aircraft.altitudeFeet <= 10000) {
            when { (aircraft.verticalRate ?: 0) < -300 -> "arrival"
                (aircraft.verticalRate ?: 0) > 300 -> "departure"
                else -> "unknown" }
        } else "unknown"
        val cacheKey = "$callsign|$movement"
        fun usable(route: RouteResult) = route.hasDublinRoute() &&
            (aircraft == null || route.statusFor(aircraft) == "found")
        val now = clock()
        cache[cacheKey]
            ?.takeIf { it.expires > now }
            ?.let {
                return it.result
            }
        val pending = RouteResult("pending")
        if (cache.size >= 512 && !cache.containsKey(cacheKey)) {
            val candidate = cache.entries.firstOrNull { it.value.result.status != "pending" }
            if (candidate == null) return RouteResult("pending")
            cache.remove(candidate.key)
        }
        cache[cacheKey] = Entry(pending, Long.MAX_VALUE)
        scope.launch {
            permits.withPermit {
                val primary =
                    try {
                        val response = client.get("${baseUrl.trimEnd('/')}/callsign/$callsign")
                        when (response.status.value) {
                            200 -> {
                                val route =
                                    json
                                        .decodeFromString<RouteEnvelope>(response.bodyAsText())
                                        .response
                                        .flightroute
                                if (route == null ||
                                    !route.origin.icao_code.matches(Regex("[A-Z0-9]{4}")) ||
                                    !route.destination.icao_code.matches(Regex("[A-Z0-9]{4}")) ||
                                    route.origin.icao_code == route.destination.icao_code)
                                    RouteResult("not-found", airline = route?.airline)
                                else
                                    RouteResult(
                                        "found",
                                        route.origin,
                                        route.destination,
                                        route.airline,
                                        route.callsign_iata?.takeIf {
                                            it.matches(Regex("[A-Z0-9]{2}[0-9]{1,4}"))
                                        })
                            }
                            404 -> RouteResult("not-found")
                            else -> RouteResult("unavailable")
                        }
                    } catch (e: CancellationException) {
                        throw e
                    } catch (e: Exception) {
                        RouteResult("unavailable")
                    }
                // Apply exactly the same coverage policy to every airline.
                val secondary = if (!usable(primary)) lookupStandingData(callsign) else null
                val third = if (!usable(primary) && (secondary == null || !usable(secondary)))
                    lookupHexDb(callsign) else null
                val selected = listOfNotNull(primary, secondary, third).firstOrNull { usable(it) }
                    ?: primary
                // Route-only fallbacks must not discard the operator supplied by the primary source.
                val airline = if (callsign.startsWith("EAI"))
                    RouteAirline("Aer Lingus Regional (Emerald Airlines)", "EAI", "EI")
                else selected.airline?.takeIf { it.name.isNotBlank() }
                    ?: primary.airline?.takeIf { it.name.isNotBlank() }
                    ?: lookupAirline(callsign)
                val result = selected.copy(airline = airline)
                val ttl = when {
                    usable(result) -> 60 * 60_000L
                    result.status == "not-found" || result.status == "found" -> 5 * 60_000L
                    else -> 60_000L
                }
                synchronized(this@RouteLookupService) {
                    cache[cacheKey] = Entry(result, clock() + ttl)
                }
            }
        }
        return pending
    }

    private suspend fun lookupAirline(callsign: String): RouteAirline? {
        // Only an ICAO operator prefix followed by a digit is an airline identifier.
        if (!callsign.matches(Regex("[A-Z]{3}[0-9][A-Z0-9]*"))) return null
        val code = callsign.take(3)
        synchronized(airlines) { airlines[code]?.takeIf { it.second > clock() } }?.let { return it.first }
        val airline = try {
            val response = client.get("${baseUrl.trimEnd('/')}/airline/$code")
            if (response.status.value == 200)
                json.decodeFromString<AirlineEnvelope>(response.bodyAsText()).response
                    .singleOrNull { it.icao == code && it.name.isNotBlank() }
            else null
        } catch (e: CancellationException) { throw e } catch (_: Exception) { null }
        synchronized(airlines) {
            if (airlines.size >= 256 && code !in airlines) airlines.remove(airlines.keys.first())
            airlines[code] = airline to (clock() + if (airline == null) 60 * 60_000L else 24 * 60 * 60_000L)
        }
        return airline
    }

    private suspend fun lookupStandingData(callsign: String): RouteResult? {
        try {
            val response = client.get("${fallbackUrl.trimEnd('/')}/${callsign.take(2)}/$callsign.json")
            if (response.status.value != 200) return null
            val route = json.decodeFromString<StandingRoute>(response.bodyAsText())
            // Multi-leg routes are ambiguous: never guess which leg is current.
            if (route.callsign != callsign || route.airports.size != 2) return null
            val airports = route.airports
            if (airports.any { !it.icao.matches(Regex("[A-Z0-9]{4}")) } || airports[0].icao == airports[1].icao) return null
            return RouteResult("found", airports[0].airport(), airports[1].airport(),
                source = "adsb.lol / Virtual Radar Server")
        } catch (e: CancellationException) {
            throw e
        } catch (_: Exception) {
            return null
        }
    }

    private suspend fun lookupHexDb(callsign: String): RouteResult? {
        try {
            val response = client.get("${thirdUrl.trimEnd('/')}/$callsign")
            if (response.status.value != 200) return null
            val route = json.decodeFromString<HexRoute>(response.bodyAsText())
            if (route.callsign != callsign || route.airports.size != 2 ||
                route.airports.any { !it.icao.matches(Regex("[A-Z0-9]{4}")) } ||
                route.airports[0].icao == route.airports[1].icao) return null
            return RouteResult("found", route.airports[0].airport(), route.airports[1].airport(), source = "HexDB")
        } catch (e: CancellationException) {
            throw e
        } catch (_: Exception) {
            return null
        }
    }

    override fun close() {
        scope.cancel()
        client.close()
    }
}
