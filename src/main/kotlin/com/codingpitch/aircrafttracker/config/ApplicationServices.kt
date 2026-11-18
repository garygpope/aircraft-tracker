package com.codingpitch.aircrafttracker.config

import com.codingpitch.aircrafttracker.aircraft.AircraftService
import com.codingpitch.aircrafttracker.aircraft.AircraftTypeLookup
import com.codingpitch.aircrafttracker.routes.RouteLookupService
import io.ktor.client.HttpClient
import io.ktor.client.engine.cio.CIO
import io.ktor.client.plugins.*
import io.ktor.client.plugins.contentnegotiation.ContentNegotiation
import io.ktor.http.URLProtocol
import io.ktor.serialization.kotlinx.json.json
import io.ktor.server.config.ApplicationConfig
import java.io.Closeable
import java.nio.file.Path
import com.codingpitch.aircrafttracker.history.PassageHistory
import com.codingpitch.aircrafttracker.history.LogEnrichment
import kotlinx.coroutines.*
import kotlinx.serialization.json.Json

/** Application-owned clients: no mutable singleton state shared between server instances. */
class ApplicationServices(config: ApplicationConfig) : Closeable {
    private val aircraftClient =
        HttpClient(CIO) {
            expectSuccess = true
            install(DefaultRequest) {
                url {
                    protocol = URLProtocol.HTTP
                    host =
                        config.property("external.services.flight-data-provider.host").getString()
                    port =
                        config
                            .property("external.services.flight-data-provider.port")
                            .getString()
                            .toInt()
                }
            }
            install(ContentNegotiation) { json(Json { ignoreUnknownKeys = true }) }
            install(HttpTimeout) {
                requestTimeoutMillis = 8000
                connectTimeoutMillis = 3000
            }
        }
    private val routeLookup =
        RouteLookupService(
            HttpClient(CIO) {
                install(HttpTimeout) {
                    requestTimeoutMillis = 4000
                    connectTimeoutMillis = 2000
                }
                install(UserAgent) { agent = "aircraft-tracker/0.1 (local flight display)" }
            },
            config.propertyOrNull("external.services.route-provider.url")?.getString()
                ?: "https://api.adsbdb.com/v0",
            fallbackUrl = config.propertyOrNull("external.services.route-provider.fallback-url")?.getString()
                ?: "https://vrs-standing-data.adsb.lol/routes",
            thirdUrl = config.propertyOrNull("external.services.route-provider.third-url")?.getString()
                ?: "https://hexdb.io/api/v2/routes")

    private val typeLookup = AircraftTypeLookup(HttpClient(CIO) {
        install(HttpTimeout) { requestTimeoutMillis = 4000; connectTimeoutMillis = 2000 }
        install(UserAgent) { agent = "aircraft-tracker/0.1 (local flight display)" }
    }, baseUrl = config.propertyOrNull("external.services.aircraft-provider.url")?.getString()
        ?: config.propertyOrNull("external.services.route-provider.url")?.getString() ?: "https://api.adsbdb.com/v0",
        fallbackUrl = config.propertyOrNull("external.services.aircraft-provider.fallback-url")?.getString()
            ?: "https://hexdb.io/api/v1/aircraft")
    val aircraft = AircraftService(aircraftClient, routeLookup, typeLookup)
    private val historyEnabled = config.propertyOrNull("history.enabled")?.getString()?.toBooleanStrict() ?: true
    private val legacyFile = Path.of(config.propertyOrNull("history.file")?.getString() ?: "data/overflights.json")
    val history = PassageHistory(
        file = Path.of(config.propertyOrNull("history.log-file")?.getString()
            ?: legacyFile.toAbsolutePath().resolveSibling("flight-log.json").toString()),
        radiusKm = 10.0,
        enabled = historyEnabled
    )
    private val historyScope = CoroutineScope(SupervisorJob() + Dispatchers.IO)

    init {
        history.importLegacy(listOf(legacyFile, Path.of(config.propertyOrNull("history.military-file")?.getString()
            ?: legacyFile.toAbsolutePath().resolveSibling("military-sightings.json").toString())))
        val enrichment = LogEnrichment(history, routeLookup, typeLookup)
        if (historyEnabled) historyScope.launch { enrichment.run() }
        if (historyEnabled) historyScope.launch { enrichment.runTypes() }
        if (historyEnabled) historyScope.launch {
            while (isActive) {
                try {
                    val sample = aircraft.getAircraftData()
                    ensureActive()
                    history.observe(sample)
                } catch (e: CancellationException) {
                    throw e
                } catch (_: Exception) {
                    history.feedUnavailable()
                }
                history.flush()
                delay(5000)
            }
        }
    }

    override fun close() {
        historyScope.cancel()
        history.close()
        typeLookup.close()
        routeLookup.close()
        aircraftClient.close()
    }
}
