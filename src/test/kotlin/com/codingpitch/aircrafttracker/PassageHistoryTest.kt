package com.codingpitch.aircrafttracker

import com.codingpitch.aircrafttracker.config.ApplicationServices
import com.codingpitch.aircrafttracker.history.PassageHistory
import com.codingpitch.aircrafttracker.model.Aircraft
import com.sun.net.httpserver.HttpServer
import io.ktor.server.config.MapApplicationConfig
import java.nio.file.Files
import java.util.concurrent.atomic.AtomicLong
import kotlin.test.*
import kotlinx.coroutines.*

class PassageHistoryTest {
    private fun aircraft(hex: String = "near", distance: Double = 1.0, altitude: Int = 2000) = Aircraft(
        hex = hex, flight = "TEST123", origin = "Paris (CDG)", destination = "Dublin (DUB)",
        track = 250.0, mag_heading = null, squawk = null, emergency = null, category = null,
        lat = 53.417, lon = -6.148, distanceKm = distance, distanceNm = distance * 0.54,
        isWithinRange = true, altitudeFeet = altitude, routeStatus = "found"
    )

    @Test
    fun `unrelated historical routes are hidden without losing sightings`() {
        val history = PassageHistory(null)
        history.observe(listOf(aircraft().copy(origin = "Venice (VCE)", destination = "Bournemouth (BOH)")))
        val passage = history.snapshot().entries.single()
        assertEquals("TEST123", passage.flight)
        assertEquals("uncertain", passage.routeStatus)
        assertNull(passage.origin)
        assertNull(passage.destination)
        assertEquals(1.0, passage.closestDistanceKm)
        history.close()
    }

    @Test
    fun `history orders by closest observation before applying result limit`() {
        val now = AtomicLong(1_000_000)
        val history = PassageHistory(null, clock = now::get)
        history.observe(listOf(aircraft("first", distance = 0.3)))
        now.addAndGet(1000)
        history.observe(listOf(aircraft("second", distance = 0.4)))
        now.addAndGet(1000)
        history.observe(listOf(aircraft("first", distance = 0.8)))
        assertEquals(listOf("second", "first"), history.snapshot().entries.map { it.hex })
        assertEquals("second", history.snapshot(1).entries.single().hex)
        now.addAndGet(1000)
        history.observe(listOf(aircraft("first", distance = 0.1)))
        assertEquals("first", history.snapshot(1).entries.single().hex)
    }

    @Test
    fun `history records passages inside two km with closest observation and bounded summaries`() {
        val now = AtomicLong(1_000_000)
        val history = PassageHistory(null, clock = now::get)
        history.observe(listOf(aircraft(), aircraft("edge", 2.0), aircraft("outside", 2.01)))
        assertEquals(2, history.snapshot().entries.size)
        now.addAndGet(5000)
        history.observe(listOf(aircraft(distance = 0.4, altitude = 1600)))
        val closestTime = now.get()
        now.addAndGet(5000)
        history.observe(listOf(aircraft(distance = 0.8, altitude = 1700)))
        val pass = history.snapshot().entries.first()
        assertEquals("near", pass.hex)
        assertEquals(0.4, pass.closestDistanceKm)
        assertEquals(1600, pass.altitudeFeet)
        assertEquals(closestTime, pass.closestAt)
        assertEquals(1_000_000, pass.firstSeenAt)
        assertEquals(now.get(), pass.lastSeenAt)
        history.observe(listOf(aircraft().copy(routeStatus = "uncertain")))
        assertNull(history.snapshot().entries.first().origin, "Uncertain routes must not become historical facts")
        now.addAndGet(10 * 60_000)
        history.observe(listOf(aircraft()))
        assertEquals(3, history.snapshot().entries.size, "A later passage by the same aircraft is a separate summary")
    }

    @Test
    fun `history persists and limits age count file bytes and disk write frequency`() {
        val directory = Files.createTempDirectory("overtheroof-history-test")
        try {
            val file = directory.resolve("history.json")
            val now = AtomicLong(1_000_000)
            val history = PassageHistory(file, clock = now::get, maxEntries = 3)
            history.observe(listOf(aircraft()))
            history.flush()
            val saved = Files.readString(file)
            assertFalse(saved.contains("\"lat\""))
            assertFalse(saved.contains("\"lon\""))
            now.addAndGet(5000)
            history.observe(listOf(aircraft(distance = 0.3)))
            history.flush()
            assertEquals(saved, Files.readString(file), "Polling must not write to disk every five seconds")
            now.addAndGet(5 * 60_000)
            history.flush()
            assertNotEquals(saved, Files.readString(file))
            val restarted = PassageHistory(file, clock = now::get, maxEntries = 3)
            assertEquals(0.3, restarted.snapshot().entries.single().closestDistanceKm)
            assertEquals("saved", restarted.snapshot().storageStatus)
            repeat(5) { now.incrementAndGet(); history.observe(listOf(aircraft("new$it"))) }
            assertEquals(3, history.snapshot(1000).entries.size)
            history.close()
            val capacity = PassageHistory(file, clock = now::get, maxEntries = 3)
            assertTrue(capacity.snapshot().capacityLimited, "Capacity warnings must survive restart")
            capacity.close()
            val bounded = PassageHistory(directory.resolve("small.json"), clock = now::get, maxFileBytes = 600)
            repeat(10) { now.incrementAndGet(); bounded.observe(listOf(aircraft("small$it"))) }
            bounded.close()
            assertTrue(Files.size(directory.resolve("small.json")) <= 600)
            assertTrue(bounded.snapshot().entries.size < 10)
            now.addAndGet(31 * 86_400_000L)
            assertTrue(history.snapshot().entries.isEmpty())
            history.flush(force = true)
            assertEquals("[]", Files.readString(file))
        } finally { directory.toFile().deleteRecursively() }
    }

    @Test
    fun `disk failures preserve existing files and never stop observations`() {
        val directory = Files.createTempDirectory("overtheroof-history-failure")
        try {
            val corrupt = directory.resolve("corrupt.json")
            Files.writeString(corrupt, "unreadable history")
            val history = PassageHistory(corrupt)
            history.observe(listOf(aircraft()))
            history.close()
            assertEquals(1, history.snapshot().entries.size)
            assertEquals("unavailable", history.snapshot().storageStatus)
            assertEquals("unreadable history", Files.readString(corrupt))
            val parentFile = directory.resolve("not-a-directory")
            Files.writeString(parentFile, "keep")
            val unwritable = PassageHistory(parentFile.resolve("history.json"))
            unwritable.observe(listOf(aircraft()))
            unwritable.flush()
            assertEquals("unavailable", unwritable.snapshot().storageStatus)
            assertEquals(1, unwritable.snapshot().entries.size)
            unwritable.feedUnavailable()
            assertEquals("offline", unwritable.snapshot().receiverStatus)
            assertEquals(1, unwritable.snapshot().entries.size)
            unwritable.observe(emptyList())
            assertEquals("live", unwritable.snapshot().receiverStatus)
            assertEquals(1, unwritable.snapshot().entries.size)
        } finally { directory.toFile().deleteRecursively() }
    }

    @Test
    fun `recorder samples the receiver without an open browser`() = runBlocking {
        val server = HttpServer.create(java.net.InetSocketAddress("127.0.0.1", 0), 0)
        val directory = Files.createTempDirectory("overtheroof-recorder-test")
        server.createContext("/skyaware/data/aircraft.json") { exchange ->
            val body = """{"now":1700000000.5,"messages":100,"aircraft":[{"hex":"overhome","dbFlags":1,"lat":53.417,"lon":-6.148,"alt_baro":2000,"seen":0,"seen_pos":0}]}""".toByteArray()
            exchange.responseHeaders.add("Content-Type", "application/json")
            exchange.sendResponseHeaders(200, body.size.toLong())
            exchange.responseBody.use { it.write(body) }
        }
        server.start()
        val services = ApplicationServices(MapApplicationConfig(
            "external.services.flight-data-provider.host" to "127.0.0.1",
            "external.services.flight-data-provider.port" to server.address.port.toString(),
            "history.file" to directory.resolve("legacy-passes.json").toString()
        ))
        try {
            withTimeout(3000) {
                while (services.history.snapshot().entries.isEmpty()) delay(10)
            }
            assertEquals("overhome", services.history.snapshot().entries.single().hex)
            assertEquals("military", services.history.snapshot().entries.single().militaryStatus)
        } finally {
            services.close()
            server.stop(0)
            assertTrue(Files.exists(directory.resolve("flight-log.json")))
            directory.toFile().deleteRecursively()
        }
    }
}
