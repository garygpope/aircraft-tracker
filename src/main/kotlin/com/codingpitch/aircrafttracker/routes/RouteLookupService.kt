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
    private val clock: () -> Long = System::currentTimeMillis
) : Closeable {
    private data class Entry(val result: RouteResult, val expires: Long)

    private val cache = LinkedHashMap<String, Entry>()
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private val permits = Semaphore(2)
    private val json = Json { ignoreUnknownKeys = true }

    // Returns immediately: an internet outage must never delay local aircraft updates.
    @Synchronized
    fun routeFor(rawCallsign: String?): RouteResult {
        val callsign = rawCallsign?.trim()?.uppercase(Locale.ROOT).orEmpty()
        if (!callsign.matches(Regex("[A-Z0-9]{3,8}"))) return RouteResult("no-callsign")
        val now = clock()
        cache[callsign]
            ?.takeIf { it.expires > now }
            ?.let {
                return it.result
            }
        val pending = RouteResult("pending")
        if (cache.size >= 512 && !cache.containsKey(callsign)) {
            val candidate = cache.entries.firstOrNull { it.value.result.status != "pending" }
            if (candidate == null) return RouteResult("pending")
            cache.remove(candidate.key)
        }
        cache[callsign] = Entry(pending, Long.MAX_VALUE)
        scope.launch {
            permits.withPermit {
                val result =
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
                                    route.origin.icao_code.isBlank() ||
                                    route.destination.icao_code.isBlank())
                                    RouteResult("not-found")
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
                val ttl =
                    when (result.status) {
                        "found" -> 6 * 60 * 60_000L
                        "not-found" -> 30 * 60_000L
                        else -> 60_000L
                    }
                synchronized(this@RouteLookupService) {
                    cache[callsign] = Entry(result, clock() + ttl)
                }
            }
        }
        return pending
    }

    override fun close() {
        scope.cancel()
        client.close()
    }
}
