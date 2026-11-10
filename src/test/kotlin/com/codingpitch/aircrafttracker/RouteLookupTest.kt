package com.codingpitch.aircrafttracker

import com.codingpitch.aircrafttracker.routes.RouteLookupService
import com.codingpitch.aircrafttracker.routes.RouteResult
import com.sun.net.httpserver.HttpServer
import io.ktor.client.*
import io.ktor.client.plugins.*
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.atomic.AtomicLong
import kotlin.test.*
import kotlinx.coroutines.*

class RouteLookupTest {
    @Test
    fun `routes normalize callsigns cache results expire and survive provider errors`() =
        runBlocking {
            val hits = ConcurrentHashMap<String, Int>()
            val server = HttpServer.create(java.net.InetSocketAddress("127.0.0.1", 0), 0)
            server.createContext("/v0/callsign/") { exchange ->
                val callsign = exchange.requestURI.path.substringAfterLast('/')
                hits.merge(callsign, 1, Int::plus)
                val status =
                    when (callsign) {
                        "MISS123" -> 404
                        "FAIL123" -> 503
                        else -> 200
                    }
                val body =
                    when (callsign) {
                        "FAIL123",
                        "MISS123" -> "{\"response\":\"unknown callsign\"}"
                        "BAD123" -> "not JSON"
                        else ->
                            """{"response":{"flightroute":{"callsign":"RYR7WU","callsign_iata":"FR7WU","airline":{"name":"Ryanair","icao":"RYR","iata":"FR"},"origin":{"name":"Eindhoven Airport","municipality":"Eindhoven","iata_code":"EIN","icao_code":"EHEH"},"destination":{"name":"Dublin Airport","municipality":"Dublin","iata_code":"DUB","icao_code":"EIDW"}}}}"""
                    }
                val bytes = body.toByteArray()
                exchange.sendResponseHeaders(status, bytes.size.toLong())
                exchange.responseBody.use { it.write(bytes) }
            }
            server.start()
            val now = AtomicLong(1000)
            val lookup =
                RouteLookupService(
                    HttpClient { install(HttpTimeout) { requestTimeoutMillis = 500 } },
                    "http://127.0.0.1:${server.address.port}/v0",
                    now::get)
            suspend fun result(callsign: String): RouteResult =
                withTimeout(3000) {
                    var value = lookup.routeFor(callsign)
                    while (value.status == "pending") {
                        delay(10)
                        value = lookup.routeFor(callsign)
                    }
                    value
                }
            try {
                assertEquals("no-callsign", lookup.routeFor(null).status)
                assertEquals("no-callsign", lookup.routeFor("../unsafe").status)
                repeat(10) { lookup.routeFor(" ryr7wu ") }
                val route = result("RYR7WU")
                assertEquals("found", route.status)
                assertEquals("Ryanair", route.airline?.name)
                assertNull(
                    route.reportedFlightNumber,
                    "An alphanumeric radio callsign is not a passenger flight number")
                assertEquals("Eindhoven (EIN)", route.origin?.label())
                assertEquals("EIDW", route.destination?.icao_code)
                val arriving =
                    com.codingpitch.aircrafttracker.model.Aircraft(
                        hex = "test",
                        flight = "RYR7WU",
                        origin = null,
                        destination = null,
                        track = null,
                        mag_heading = null,
                        squawk = null,
                        emergency = null,
                        category = null,
                        lat = 53.4,
                        lon = -6.1,
                        distanceKm = 2.0,
                        distanceNm = 1.1,
                        isWithinRange = true,
                        altitudeFeet = 2000,
                        verticalRate = -800)
                assertEquals("found", route.statusFor(arriving))
                assertEquals("uncertain", route.statusFor(arriving.copy(verticalRate = 800)))
                assertEquals(
                    "uncertain",
                    route
                        .copy(origin = route.destination, destination = route.origin)
                        .statusFor(arriving))
                assertEquals(
                    "found",
                    route.statusFor(arriving.copy(altitudeFeet = 35000, verticalRate = 800)))
                assertEquals(1, hits["RYR7WU"])
                assertEquals("not-found", result("MISS123").status)
                assertEquals("not-found", result("MISS123").status)
                assertEquals(1, hits["MISS123"])
                assertEquals("unavailable", result("FAIL123").status)
                assertEquals("unavailable", result("BAD123").status)
                assertEquals("unavailable", result("FAIL123").status)
                assertEquals(1, hits["FAIL123"])
                now.addAndGet(61_000)
                assertEquals("unavailable", result("FAIL123").status)
                assertEquals(2, hits["FAIL123"])
                assertEquals("found", result("RYR7WU").status)
                assertEquals(1, hits["RYR7WU"])
                now.addAndGet(6 * 60 * 60_000L)
                assertEquals("found", result("RYR7WU").status)
                assertEquals(2, hits["RYR7WU"])
            } finally {
                lookup.close()
                server.stop(0)
            }
        }
}
