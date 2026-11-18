package com.codingpitch.aircrafttracker

import com.sun.net.httpserver.HttpServer
import io.ktor.client.request.*
import io.ktor.client.statement.*
import io.ktor.http.*
import io.ktor.server.config.*
import io.ktor.server.testing.*
import kotlin.test.*

class DisplayTest {
    @Test
    fun `display and API handle ground altitude stale positions and unavailable feed`() {
        val server = HttpServer.create(java.net.InetSocketAddress("127.0.0.1", 0), 0)
        var broken = false
        server.createContext("/skyaware/data/aircraft.json") { exchange ->
            val body =
                if (broken) "unavailable"
                else
                    """
                {"now":1700000000.5,"messages":100,"aircraft":[
                 {"hex":"near","dbFlags":1,"flight":"TEST123  ","lat":53.417,"lon":-6.148,"alt_baro":"ground","gs":0,"ias":0,"seen":0,"seen_pos":0,"extra":true},
                 {"hex":"stale","lat":53.417,"lon":-6.148,"seen":0,"seen_pos":60},
                 {"hex":"within10","lat":53.47,"lon":-6.148,"alt_baro":3000,"gs":142.5,"ias":135,"seen":0},
                 {"hex":"far","lat":54.0,"lon":-6.0,"alt_baro":10000,"gs":-1,"ias":-2,"baro_rate":800,"seen":0},
                 {"hex":"no-position","seen":0}
                ]}
            """
                        .trimIndent()
            exchange.responseHeaders.add("Content-Type", "application/json")
            val bytes = body.toByteArray()
            exchange.sendResponseHeaders(if (broken) 503 else 200, bytes.size.toLong())
            exchange.responseBody.use { it.write(bytes) }
        }
        server.start()
        try {
            testApplication {
                environment {
                    config =
                        MapApplicationConfig(
                            "history.enabled" to "false",
                            "external.services.flight-data-provider.host" to "127.0.0.1",
                            "external.services.flight-data-provider.port" to
                                server.address.port.toString(),
                            "external.services.route-provider.url" to
                                "http://127.0.0.1:${server.address.port}/v0")
                }
                application { serviceModule() }
                val page = client.get("/")
                assertEquals(HttpStatusCode.OK, page.status)
                assertContains(page.bodyAsText(), "<h1>St Marnock's Airspace</h1>")
                assertContains(page.bodyAsText(), "<title>St Marnock's Airspace</title>")
                for (asset in
                    listOf(
                        "app.js",
                        "styles.css",
                        "traffic.js",
                        "radar.js",
                        "layout.js",
                        "airlines.js",
                        "city-photos.js",
                        "history.js",
                        "city-fallback.svg",
                        "logos/EIN.png")) {
                    assertEquals(HttpStatusCode.OK, client.get("/$asset").status, asset)
                }
                val options = client.get("/api/data/logbook/options")
                assertEquals(HttpStatusCode.OK, options.status)
                assertContains(options.bodyAsText(), "\"airlines\"")
                val logbook = client.get("/api/data/logbook")
                assertEquals(HttpStatusCode.OK, logbook.status)
                assertContains(logbook.bodyAsText(), "\"retentionDays\":30")
                assertEquals(HttpStatusCode.BadRequest, client.get("/api/data/logbook?from=invalid").status)
                assertEquals(HttpStatusCode.BadRequest, client.get("/api/data/logbook?gsMin=bad").status)
                assertEquals(HttpStatusCode.OK, client.get("/api/data/logbook.csv?nightOnly=true").status)
                val history = client.get("/api/data/history?limit=1000")
                assertEquals(HttpStatusCode.OK, history.status)
                assertContains(history.bodyAsText(), "\"enabled\":false")
                val military = client.get("/api/data/military-history")
                assertEquals(HttpStatusCode.OK, military.status)
                assertContains(military.bodyAsText(), "\"radiusKm\":10.0")
                val response = client.get("/api/data/aircraft")
                assertEquals(HttpStatusCode.OK, response.status)
                val data =
                    kotlinx.serialization.json.Json.parseToJsonElement(response.bodyAsText())
                        as kotlinx.serialization.json.JsonArray
                assertEquals(3, data.size)
                fun speed(index: Int) = (data[index] as kotlinx.serialization.json.JsonObject)["groundSpeedKnots"].toString()
                assertEquals("0.0", speed(0), "A stationary aircraft has a valid zero speed")
                assertEquals("142.5", speed(1))
                fun ias(index: Int) = (data[index] as kotlinx.serialization.json.JsonObject)["indicatedAirspeedKnots"].toString()
                assertEquals("0.0", ias(0))
                assertEquals("135.0", ias(1))
                assertEquals("null", ias(2))
                assertTrue(speed(2) == "null", "Invalid speeds must not be displayed")
                assertEquals(
                    "true",
                    (data[1] as kotlinx.serialization.json.JsonObject)["isWithinRange"].toString())
                assertEquals(
                    "false",
                    (data[2] as kotlinx.serialization.json.JsonObject)["isWithinRange"].toString())
                val first = data[0] as kotlinx.serialization.json.JsonObject
                assertEquals("\"TEST123\"", first["flight"].toString())
                assertEquals("\"military\"", first["militaryStatus"].toString())
                assertEquals("true", first["isWithinRange"].toString())
                assertTrue(
                    first["altitudeFeet"] == null || first["altitudeFeet"].toString() == "null")
                broken = true
                assertEquals(HttpStatusCode.BadGateway, client.get("/api/data/aircraft").status)
            }
        } finally {
            server.stop(0)
        }
    }
}
