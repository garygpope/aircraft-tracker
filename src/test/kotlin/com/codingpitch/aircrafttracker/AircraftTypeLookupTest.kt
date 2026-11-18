package com.codingpitch.aircrafttracker

import com.codingpitch.aircrafttracker.aircraft.AircraftTypeLookup
import com.codingpitch.aircrafttracker.aircraft.AircraftTypeResult
import com.sun.net.httpserver.HttpServer
import io.ktor.client.HttpClient
import io.ktor.client.plugins.HttpTimeout
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.atomic.AtomicLong
import kotlinx.coroutines.*
import kotlin.test.*

class AircraftTypeLookupTest {
    @Test fun `types match hex identities use fallback cache and tolerate unavailable sources`() = runBlocking {
        val hits = ConcurrentHashMap<String, Int>()
        val server = HttpServer.create(java.net.InetSocketAddress("127.0.0.1", 0), 0)
        server.createContext("/primary/aircraft/") { exchange ->
            val hex = exchange.requestURI.path.substringAfterLast('/')
            hits.merge(hex, 1, Int::plus)
            val body = if (hex == "4CA004") "malformed" else """{"response":{"aircraft":{"mode_s":"${if (hex == "4CA002") "FFFFFF" else hex}","manufacturer":"Boeing","type":"737-800","icao_type":"B738"}}}"""
            val bytes = body.toByteArray()
            exchange.sendResponseHeaders(if (hex == "4CA003") 404 else 200, bytes.size.toLong())
            exchange.responseBody.use { it.write(bytes) }
        }
        server.createContext("/fallback/") { exchange ->
            val hex = exchange.requestURI.path.substringAfterLast('/')
            val body = """{"ModeS":"$hex","Manufacturer":"Airbus","Type":"Airbus A320-200","ICAOTypeCode":"A320"}"""
            val bytes = body.toByteArray()
            exchange.sendResponseHeaders(if (hex == "4CA002") 200 else 404, bytes.size.toLong())
            exchange.responseBody.use { it.write(bytes) }
        }
        server.start()
        val now = AtomicLong(1000)
        val lookup = AircraftTypeLookup(HttpClient { install(HttpTimeout) { requestTimeoutMillis = 500 } },
            "http://127.0.0.1:${server.address.port}/primary", "http://127.0.0.1:${server.address.port}/fallback", now::get)
        suspend fun result(hex: String): AircraftTypeResult = withTimeout(3000) {
            var value = lookup.typeFor(hex)
            while (value.status == "pending") { delay(10); value = lookup.typeFor(hex) }
            value
        }
        try {
            assertEquals("not-found", lookup.typeFor("../bad").status)
            val primary = result("4ca001")
            assertEquals("Boeing 737-800", primary.label)
            assertEquals("B738", primary.code)
            assertEquals("adsbdb", primary.source)
            repeat(5) { assertEquals(primary, result("4CA001")) }
            assertEquals(1, hits["4CA001"])
            val fallback = result("4CA002")
            assertEquals("Airbus A320-200", fallback.label, "Do not duplicate the manufacturer")
            assertEquals("HexDB", fallback.source, "Reject the primary's mismatched aircraft identity")
            assertEquals("not-found", result("4CA003").status)
            assertEquals("unavailable", result("4CA004").status)
            now.addAndGet(24 * 60 * 60_000L + 1)
            assertEquals(primary, result("4CA001"))
            assertEquals(2, hits["4CA001"])
        } finally { lookup.close(); server.stop(0) }
    }
}
