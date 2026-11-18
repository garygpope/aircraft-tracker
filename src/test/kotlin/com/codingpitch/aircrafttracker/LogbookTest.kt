package com.codingpitch.aircrafttracker

import com.codingpitch.aircrafttracker.history.*
import com.codingpitch.aircrafttracker.model.Aircraft
import java.nio.file.Files
import java.time.Instant
import java.util.concurrent.atomic.AtomicLong
import kotlin.test.*
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json

class LogbookTest {
    private fun at(time: String) = Instant.parse(time).toEpochMilli()
    private fun plane(hex: String = "test", distance: Double = 1.0) = Aircraft(
        hex, "EAI85Y", "Bristol (BRS)", "Dublin (DUB)", 270.0, null, "1234", "none", "A1",
        53.417, -6.148, distance, distance * .54, true, altitudeFeet = 1300,
        groundSpeedKnots = 140.0, indicatedAirspeedKnots = 135.0, routeStatus = "found",
        routeSource = "Fixture", airlineName = "Regional airline"
    )

    @Test fun `night dates use Dublin clock boundaries and DST rather than fixed UTC hours`() {
        assertNull(Logbook.nightDate(at("2026-11-13T22:59:59Z")))
        assertEquals("2026-11-13", Logbook.nightDate(at("2026-11-13T23:00:00Z")))
        assertEquals("2026-11-13", Logbook.nightDate(at("2026-11-14T06:59:59Z")))
        assertNull(Logbook.nightDate(at("2026-11-14T07:00:00Z")))
        assertEquals("2026-07-12", Logbook.nightDate(at("2026-07-12T22:00:00Z")), "23:00 summer Dublin")
        assertNull(Logbook.nightDate(at("2026-07-13T06:00:00Z")), "07:00 summer Dublin")
        for ((date, hours) in listOf("2026-03-28" to 7, "2026-10-24" to 9, "2026-11-13" to 8)) {
            val (start, end) = Logbook.nightBounds(date)
            assertEquals(hours * 3600000L, end - start)
            assertEquals(date, Logbook.nightDate(start))
            assertEquals(date, Logbook.nightDate(end - 1))
            assertNull(Logbook.nightDate(end))
        }
    }

    @Test fun `unknown pickers distinguish missing fields and share CSV and statistics`() {
        val now = AtomicLong(at("2026-11-13T18:00:00Z"))
        val history = PassageHistory(null, radiusKm = 10.0, clock = now::get)
        history.observe(listOf(plane("known"), plane("missing").copy(airlineName = null, routeStatus = "not-found", origin = null, destination = null),
            plane("missing-origin").copy(airlineName = "", origin = null),
            plane("missing-destination").copy(origin = "Dublin (DUB)", destination = null)))
        val log = Logbook(history)
        for ((field, count) in listOf("airline" to 2, "origin" to 2, "destination" to 2, "route" to 1)) {
            val params = mapOf(field to if (field == "route") "unknown" else "__unknown__", "exact" to "true")
            assertEquals(count, log.query(params).total, field)
            assertEquals(count, log.query(params).stats.passages, field)
            assertEquals(count + 1, log.csv(params).trim().lines().size, field)
        }
        assertEquals(1, log.query(mapOf("airline" to "__unknown__", "origin" to "__unknown__", "destination" to "__unknown__")).total)
    }

    @Test fun `busiest hour counts dated hours in the complete filtered selection with latest tie`() {
        val now = AtomicLong(at("2026-11-13T10:00:00Z"))
        val history = PassageHistory(null, radiusKm = 10.0, clock = now::get)
        for ((i, date) in listOf("2026-11-13T10:00:00Z", "2026-11-13T10:30:00Z", "2026-11-13T12:00:00Z", "2026-11-14T10:00:00Z", "2026-11-14T10:30:00Z").withIndex()) {
            now.set(at(date)); history.observe(listOf(plane("peak$i").copy(airlineName = if (i < 3) "Early airline" else "Later airline")))
        }
        val log = Logbook(history)
        val peak = log.query(mapOf("pageSize" to "1")).stats.busiestHour!!
        assertEquals(2, peak.movements)
        assertEquals(at("2026-11-14T10:00:00Z"), peak.startsAt)
        assertEquals(at("2026-11-13T10:00:00Z"), log.query(mapOf("airline" to "Early airline", "exact" to "true")).stats.busiestHour!!.startsAt)
        assertNull(log.query(mapOf("airline" to "Absent airline")).stats.busiestHour)
    }

    @Test fun `aircraft type enrichment fills every retained movement for a hex and persists`() {
        val now = AtomicLong(at("2026-11-13T18:00:00Z"))
        val file = Files.createTempDirectory("aircraft-type-test").resolve("log.json")
        val history = PassageHistory(file, radiusKm = 10.0, clock = now::get)
        history.observe(listOf(plane("4ca001"), plane("4ca002")))
        now.addAndGet(11 * 60_000)
        history.observe(listOf(plane("4ca001")))
        val before = history.snapshot(all = true).entries
        val type = com.codingpitch.aircrafttracker.aircraft.AircraftTypeResult("found", "Boeing 737-800", "B738", "Fixture")
        assertTrue(history.enrichType("4CA001", type))
        assertFalse(history.enrichType("4ca001", type.copy(label = "Wrong overwrite")))
        assertFalse(history.enrichType("4ca002", type.copy(status = "unavailable")))
        val after = history.snapshot(all = true).entries
        assertEquals(2, after.count { it.aircraftType == "Boeing 737-800" })
        for (p in after) assertEquals(before.single { it.hex == p.hex && it.firstSeenAt == p.firstSeenAt }, p.copy(aircraftType = null, aircraftTypeCode = null, aircraftTypeSource = null))
        history.close()
        PassageHistory(file, radiusKm = 10.0, clock = now::get).use { assertEquals(after, it.snapshot(all = true).entries) }
    }

    @Test fun `metadata enrichment preserves observations known routes and survives reload`() {
        val now = AtomicLong(at("2026-11-13T18:00:00Z"))
        val file = Files.createTempDirectory("enrichment-test").resolve("log.json")
        val history = PassageHistory(file, radiusKm = 10.0, clock = now::get)
        history.observe(listOf(plane().copy(routeStatus = "not-found", origin = null, destination = null, airlineName = null)))
        val original = history.snapshot(all = true).entries.single()
        val route = com.codingpitch.aircrafttracker.routes.RouteResult("found",
            com.codingpitch.aircrafttracker.routes.RouteAirport("Bristol", iata_code = "BRS", icao_code = "EGGD"),
            com.codingpitch.aircrafttracker.routes.RouteAirport("Dublin", iata_code = "DUB", icao_code = "EIDW"),
            com.codingpitch.aircrafttracker.routes.RouteAirline("Regional airline"), "EI3281", "Fixture")
        assertTrue(history.enrich(original, route, false))
        val airlineOnly = history.snapshot(all = true).entries.single()
        assertEquals(original.copy(airlineName = "Regional airline"), airlineOnly, "Unusable routes cannot change saved observations")
        assertTrue(history.enrich(original, route, true))
        val enriched = history.snapshot(all = true).entries.single()
        assertEquals("found", enriched.routeStatus)
        assertEquals("EI3281", enriched.reportedFlightNumber)
        assertEquals(original.closestAt, enriched.closestAt)
        assertEquals(original.closestDistanceKm, enriched.closestDistanceKm)
        assertEquals(original.groundSpeedKnots, enriched.groundSpeedKnots)
        assertFalse(history.enrich(original, route.copy(origin = route.destination, destination = route.origin), true), "Existing routes are preserved")
        assertFalse(history.enrich(original.copy(flight = "DIFFERENT"), route, true), "Changed callsigns cannot receive old matches")
        history.close()
        PassageHistory(file, radiusKm = 10.0, clock = now::get).use { reloaded ->
            assertEquals(enriched, reloaded.snapshot(all = true).entries.single())
        }
    }

    @Test fun `relative windows share table stats and export with precise boundaries`() {
        val now = AtomicLong(at("2026-11-13T18:00:00Z"))
        val history = PassageHistory(null, radiusKm = 10.0, clock = now::get)
        val samples = listOf("2026-10-30T18:00:00Z", "2026-11-06T18:00:00Z",
            "2026-11-12T22:59:59Z", "2026-11-12T23:00:00Z", "2026-11-13T00:00:00Z",
            "2026-11-13T07:00:00Z", "2026-11-13T15:00:00Z", "2026-11-13T17:00:00Z")
        samples.forEachIndexed { i, time -> now.set(at(time)); history.observe(listOf(plane("sample$i"))) }
        now.set(at("2026-11-13T18:00:00Z"))
        val log = Logbook(history, now::get)
        for ((period, count) in listOf("hour" to 1, "3hours" to 2, "today" to 4,
                "last-night" to 2, "7days" to 7, "14days" to 8)) {
            val params = mapOf("period" to period)
            val result = log.query(params)
            assertEquals(count, result.total, period)
            assertEquals(count, result.stats.passages, period)
            assertEquals(count + 1, log.csv(params).trim().lines().size, period)
        }
        now.set(at("2026-11-13T19:00:00Z"))
        assertEquals(0, log.query(mapOf("period" to "hour")).total, "rolling window advances")
    }

    @Test fun `today and last night follow Dublin date even before midnight UTC in summer`() {
        val now = AtomicLong(at("2026-07-13T23:30:00Z")) // 00:30 on 14 July in Dublin
        val history = PassageHistory(null, radiusKm = 10.0, clock = now::get)
        now.set(at("2026-07-13T21:59:59Z")); history.observe(listOf(plane("before")))
        now.set(at("2026-07-13T22:00:00Z")); history.observe(listOf(plane("night")))
        now.set(at("2026-07-13T23:00:00Z")); history.observe(listOf(plane("midnight")))
        now.set(at("2026-07-13T23:30:00Z"))
        val log = Logbook(history, now::get)
        assertEquals(listOf("midnight"), log.query(mapOf("period" to "today")).entries.map { it.passage.hex })
        assertEquals(2, log.query(mapOf("period" to "last-night")).total)
    }

    @Test fun `all statistics and CSV cover full selection while table is paginated`() {
        val now = AtomicLong(at("2026-11-13T23:00:00Z"))
        val history = PassageHistory(null, radiusKm = 10.0, clock = now::get)
        history.observe((0..149).map { plane("night$it", distance = if (it % 2 == 0) 1.0 else 7.0)
            .copy(militaryStatus = if (it == 0) "possible" else "unknown", militaryBasis = if (it == 0) "Callsign hint" else null) })
        now.set(at("2026-11-14T07:00:00Z"))
        history.observe(listOf(plane("day").copy(groundSpeedKnots = null, indicatedAirspeedKnots = null)))
        val log = Logbook(history)
        val result = log.query(emptyMap())
        assertEquals(151, result.total)
        assertEquals(50, result.entries.size)
        assertEquals(151, result.stats.passages)
        assertEquals(150, result.stats.nightPassages)
        assertEquals(151, result.stats.uniqueAircraft)
        assertEquals(150, result.stats.groundSpeedKnots.count)
        assertEquals(140.0, result.stats.groundSpeedKnots.average)
        assertEquals(1, result.stats.possibleMilitary)
        assertEquals(76, log.query(mapOf("scope" to "home")).total)
        assertEquals(1, log.query(mapOf("military" to "possible")).total)
        assertEquals(150, log.query(mapOf("night" to "2026-11-13")).total)
        assertEquals(150, log.query(mapOf("nightOnly" to "true")).total)
        assertEquals(150, log.query(mapOf("from" to "2026-11-13T23:00:00", "to" to "2026-11-14T07:00:00")).total)
        assertEquals(150, log.query(mapOf("gsMin" to "130")).total, "Unknown speed is excluded from numeric filters")
        assertEquals(151, log.query(mapOf("airline" to "REGIONAL", "origin" to "BRS", "route" to "arrival")).total)
        assertEquals(0, log.query(mapOf("route" to "departure")).total)
        assertEquals(4, log.query(mapOf("page" to "999")).page)
        assertEquals(51, log.query(mapOf("pageSize" to "100", "page" to "2")).entries.size)
        val csv = log.csv(mapOf("night" to "2026-11-13"))
        assertEquals(151, csv.lineSequence().filter { it.isNotBlank() }.count(), "Header plus every matching record")
        assertContains(csv, "\"Night starting\"")
        assertContains(csv, "\"2026-11-13\"")
        assertEquals("\"'=SUM(1,2)\"", Logbook.csvCell("=SUM(1,2)"))
        assertEquals("\"a\"\"b\"", Logbook.csvCell("a\"b"))
        assertEquals("\"-700\"", Logbook.csvCell(-700))
        for (sort in listOf("asc", "desc"))
            assertEquals("day", log.query(mapOf("sort" to "gs", "order" to sort, "pageSize" to "200")).entries.last().passage.hex)
        for (bad in listOf(mapOf("gsMin" to "NaN"), mapOf("distanceMin" to "-1"), mapOf("altitudeMin" to "3000", "altitudeMax" to "1000"),
            mapOf("from" to "2026-11-14T08:00:00", "to" to "2026-11-14T07:00:00"), mapOf("sort" to "bad")))
            assertFailsWith<IllegalArgumentException> { log.query(bad) }
        val empty = log.query(mapOf("search" to "NO SUCH AIRCRAFT"))
        assertEquals(0, log.query(mapOf("airline" to "Regional", "exact" to "true")).total)
        assertEquals(151, log.query(mapOf("airline" to "Regional airline", "exact" to "true")).total)
        assertEquals(151, log.query(mapOf("flight" to "85Y", "exact" to "true")).total)
        val choices = log.facets()
        assertEquals(listOf("Regional airline"), choices.airlines)
        assertEquals(listOf("Bristol (BRS)"), choices.origins)
        assertEquals(listOf("Dublin (DUB)"), choices.destinations)
        assertEquals(0, empty.total)
        assertNull(empty.stats.groundSpeedKnots.average)
        assertEquals(0, empty.stats.groundSpeedKnots.count)
    }

    @Test fun `migration merges overlapping aircraft preserves original archives and runs once`() {
        val dir = Files.createTempDirectory("logbook-migration")
        try {
            val now = at("2026-11-13T23:01:00Z")
            val near = Passage("one", "WOLF1", now - 30000, now - 10000, now - 20000, 0.4, 1000)
            val wide = near.copy(firstSeenAt = now - 60000, lastSeenAt = now, closestDistanceKm = 0.5,
                militaryStatus = "military", militaryBasis = "Database flag")
            val oldHome = dir.resolve("old-home.json"); val oldMilitary = dir.resolve("old-military.json")
            Files.writeString(oldHome, Json.encodeToString(listOf(near)))
            Files.writeString(oldMilitary, Json.encodeToString(listOf(wide)))
            val original = Files.readString(oldHome)
            val file = dir.resolve("flight-log.json")
            val history = PassageHistory(file, radiusKm = 10.0, clock = { now })
            history.importLegacy(listOf(oldHome, oldMilitary))
            val entry = history.snapshot().entries.single()
            assertEquals(0.4, entry.closestDistanceKm)
            assertEquals("military", entry.militaryStatus)
            assertEquals(wide.firstSeenAt, entry.firstSeenAt)
            assertEquals(wide.lastSeenAt, entry.lastSeenAt)
            assertEquals(original, Files.readString(oldHome))
            history.close()
            val restart = PassageHistory(file, radiusKm = 10.0, clock = { now + 3600000 })
            restart.importLegacy(listOf(oldHome, oldMilitary))
            assertEquals(1, restart.snapshot().entries.size)
            assertEquals(now, restart.snapshot().allAircraftSince, "Coverage start survives restart")
            restart.close()
            val broken = dir.resolve("broken.json"); Files.writeString(broken, "not JSON")
            val incomplete = dir.resolve("incomplete.json")
            val failed = PassageHistory(incomplete, radiusKm = 10.0, clock = { now })
            failed.importLegacy(listOf(oldHome, broken)); failed.close()
            assertFalse(Files.exists(incomplete), "A partial import must not replace the old archive")
            assertEquals("unavailable", failed.snapshot().storageStatus)
        } finally { dir.toFile().deleteRecursively() }
    }

    @Test fun `one log retains thirty days over two thousand passages with consistent closest measurements`() {
        val now = AtomicLong(at("2026-10-15T23:00:00Z"))
        val history = PassageHistory(null, radiusKm = 10.0, clock = now::get)
        history.observe((0..2500).map { plane("old$it") })
        now.addAndGet(20 * 86400000L)
        history.observe(listOf(plane("new", 7.0).copy(militaryStatus = "possible", militaryBasis = "Callsign hint")))
        assertEquals(2502, history.snapshot(all = true).entries.size)
        now.addAndGet(5000)
        history.observe(listOf(plane("new", 6.0).copy(groundSpeedKnots = 120.0, indicatedAirspeedKnots = 115.0)))
        now.addAndGet(5000)
        history.observe(listOf(plane("new", 8.0).copy(groundSpeedKnots = 180.0, routeStatus = "pending")))
        val p = history.snapshot().entries.first()
        assertEquals(120.0, p.groundSpeedKnots)
        assertEquals(115.0, p.indicatedAirspeedKnots)
        assertEquals("possible", p.militaryStatus)
        assertEquals("Callsign hint", p.militaryBasis)
        assertEquals("found", p.routeStatus, "Temporary lookup latency must not erase a saved route")
        now.addAndGet(10 * 86400000L)
        assertEquals(1, history.snapshot(all = true).entries.size)
    }
}
