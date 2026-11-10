package com.codingpitch.aircrafttracker.config

import com.codingpitch.aircrafttracker.aircraft.AircraftService
import com.codingpitch.aircrafttracker.routes.RouteLookupService
import io.ktor.client.HttpClient
import io.ktor.client.engine.cio.CIO
import io.ktor.client.plugins.*
import io.ktor.client.plugins.contentnegotiation.ContentNegotiation
import io.ktor.http.URLProtocol
import io.ktor.serialization.kotlinx.json.json
import io.ktor.server.config.ApplicationConfig
import java.io.Closeable
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
                ?: "https://api.adsbdb.com/v0")

    val aircraft = AircraftService(aircraftClient, routeLookup)

    override fun close() {
        routeLookup.close()
        aircraftClient.close()
    }
}
