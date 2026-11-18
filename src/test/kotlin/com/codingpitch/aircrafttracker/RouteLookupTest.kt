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
                        "MISS123", "RYR2704", "MULTI12" -> 404
                        "FAIL123" -> 503
                        else -> 200
                    }
                val body =
                    when (callsign) {
                        "FAIL123",
                        "MISS123" -> "{\"response\":\"unknown callsign\"}"
                        "BAD123" -> "not JSON"
                        "RYR9BP" -> """{"response":{"flightroute":{"airline":{"name":"Ryanair","icao":"RYR"},"origin":{"icao_code":"EDLW"},"destination":{"icao_code":"LEMG"}}}}"""
                        "BAW123" -> """{"response":{"flightroute":{"origin":{"icao_code":"EDLW"},"destination":{"icao_code":"LEMG"}}}}"""
                        "EIN234" -> """{"response":{"flightroute":{"origin":{"icao_code":"EIDW"},"destination":{"icao_code":"LFPG"}}}}"""
                        "EAI85Y" -> """{"response":{"flightroute":{"origin":{"icao_code":"EGGD"},"destination":{"icao_code":"EGGD"}}}}"""
                        else ->
                            """{"response":{"flightroute":{"callsign":"RYR7WU","callsign_iata":"FR7WU","airline":{"name":"Ryanair","icao":"RYR","iata":"FR"},"origin":{"name":"Eindhoven Airport","municipality":"Eindhoven","iata_code":"EIN","icao_code":"EHEH"},"destination":{"name":"Dublin Airport","municipality":"Dublin","iata_code":"DUB","icao_code":"EIDW"}}}}"""
                    }
                val bytes = body.toByteArray()
                exchange.sendResponseHeaders(status, bytes.size.toLong())
                exchange.responseBody.use { it.write(bytes) }
            }
            server.createContext("/v0/airline/") { exchange ->
                val code = exchange.requestURI.path.substringAfterLast('/')
                val body = when (code) {
                    "RYR" -> """{"response":[{"name":"Ryanair","icao":"RYR","iata":"FR"}]}"""
                    "BAW" -> """{"response":[{"name":"British Airways","icao":"BAW","iata":"BA"}]}"""
                    else -> """{"response":[]}"""
                }
                val bytes = body.toByteArray()
                exchange.sendResponseHeaders(200, bytes.size.toLong())
                exchange.responseBody.use { it.write(bytes) }
            }
            server.createContext("/routes/") { exchange ->
                val callsign = exchange.requestURI.path.substringAfterLast('/').removeSuffix(".json")
                val airports = if (callsign == "EAI85Y") """{"icao":"EGGD","location":"Bristol","iata":"BRS"},{"icao":"EIDW","location":"Dublin","iata":"DUB"}""" else """{"icao":"LIPQ","location":"Trieste","iata":"TRS"},{"icao":"EIDW","location":"Dublin","iata":"DUB"}"""
                val body = """{"callsign":"$callsign","_airports":[$airports${if (callsign == "MULTI12") ",{\"icao\":\"EGLL\"}" else ""}]}"""
                val bytes = body.toByteArray()
                exchange.sendResponseHeaders(if (callsign in listOf("RYR2704", "RYR9BP", "EAI85Y", "EIN234", "MULTI12")) 200 else 404, bytes.size.toLong())
                exchange.responseBody.use { it.write(bytes) }
            }
            server.createContext("/hex/") { exchange ->
                val callsign = exchange.requestURI.path.substringAfterLast('/')
                val body = """{"callsign":"$callsign","airports":[{"icao":"EGLL","name":"Heathrow","iata":"LHR"},{"icao":"EIDW","name":"Dublin","iata":"DUB"}]}"""
                val bytes = body.toByteArray()
                exchange.sendResponseHeaders(if (callsign == "BAW123") 200 else 404, bytes.size.toLong())
                exchange.responseBody.use { it.write(bytes) }
            }
            server.start()
            val now = AtomicLong(1000)
            val lookup =
                RouteLookupService(
                    HttpClient { install(HttpTimeout) { requestTimeoutMillis = 500 } },
                    "http://127.0.0.1:${server.address.port}/v0",
                    now::get,
                    "http://127.0.0.1:${server.address.port}/routes",
                    "http://127.0.0.1:${server.address.port}/hex")
            suspend fun result(callsign: String, aircraft: com.codingpitch.aircrafttracker.model.Aircraft? = null): RouteResult =
                withTimeout(3000) {
                    var value = lookup.routeFor(callsign, aircraft)
                    while (value.status == "pending") {
                        delay(10)
                        value = lookup.routeFor(callsign, aircraft)
                    }
                    value
                }
            try {
                val regional = result("EAI85Y")
                assertEquals("EGGD", regional.origin?.icao_code)
                assertEquals("EIDW", regional.destination?.icao_code)
                assertEquals("adsb.lol / Virtual Radar Server", regional.source)
                val third = result("BAW123")
                assertEquals("EGLL", third.origin?.icao_code)
                assertEquals("EIDW", third.destination?.icao_code)
                assertEquals("HexDB", third.source)
                assertEquals("British Airways", third.airline?.name, "Airline lookup enriches route-only providers")
                val fallback = result("RYR2704")
                assertEquals("found", fallback.status)
                assertEquals("LIPQ", fallback.origin?.icao_code)
                assertEquals("EIDW", fallback.destination?.icao_code)
                assertEquals("adsb.lol / Virtual Radar Server", fallback.source)
                assertEquals("not-found", result("MULTI12").status)
                assertEquals("EIDW", result("RYR9BP").destination?.icao_code)
                assertEquals("Ryanair", result("RYR9BP").airline?.name, "Fallback retains primary airline")
                assertEquals("adsb.lol / Virtual Radar Server", result("RYR9BP").source)
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
                val wrongDirection = result("EIN234", arriving)
                assertEquals("EIDW", wrongDirection.destination?.icao_code)
                assertEquals("adsb.lol / Virtual Radar Server", wrongDirection.source)
                val departing = result("EIN234", arriving.copy(verticalRate = 800))
                assertEquals("EIDW", departing.origin?.icao_code)
                assertEquals("adsbdb", departing.source)
                val unrelated = route.copy(destination = route.origin)
                assertEquals("uncertain", unrelated.statusFor(arriving.copy(track = 275.0)))
                for (sample in listOf(
                    arriving.copy(track = 90.0), arriving.copy(track = null, verticalRate = null),
                    arriving.copy(verticalRate = 0), arriving.copy(altitudeFeet = null),
                    arriving.copy(altitudeFeet = 35000))) {
                    assertEquals("uncertain", unrelated.statusFor(sample), "Unrelated routes must never be confirmed")
                }
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
