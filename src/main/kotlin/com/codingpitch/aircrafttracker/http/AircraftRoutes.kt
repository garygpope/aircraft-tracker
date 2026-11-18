package com.codingpitch.aircrafttracker.http

import com.codingpitch.aircrafttracker.aircraft.AircraftService
import com.codingpitch.aircrafttracker.history.PassageHistory
import io.ktor.server.application.call
import io.ktor.server.response.respond
import io.ktor.server.response.respondText
import io.ktor.http.*
import com.codingpitch.aircrafttracker.history.Logbook
import io.ktor.server.routing.*

fun Route.aircraftRoutes(aircraft: AircraftService, history: PassageHistory) {
    val logbook = Logbook(history)
    get("/api/data/logbook/options") {
        call.response.headers.append(HttpHeaders.CacheControl, "no-store")
        call.respond(logbook.facets())
    }
    for (csv in listOf(false, true)) get(if (csv) "/api/data/logbook.csv" else "/api/data/logbook") {
        try {
            val params = call.request.queryParameters.entries().associate { it.key to it.value.first() }
            call.response.headers.append(HttpHeaders.CacheControl, "no-store")
            if (csv) {
                call.response.headers.append(HttpHeaders.ContentDisposition, "attachment; filename=overtheroof-logbook.csv")
                call.respondText(logbook.csv(params), ContentType.parse("text/csv; charset=utf-8"))
            } else call.respond(logbook.query(params))
        } catch (e: IllegalArgumentException) {
            call.respond(HttpStatusCode.BadRequest, mapOf("error" to (e.message ?: "Invalid filter")))
        } catch (_: java.time.DateTimeException) {
            call.respond(HttpStatusCode.BadRequest, mapOf("error" to "Invalid Dublin date/time"))
        }
    }
    get("/api/data/aircraft") { call.respond(aircraft.getAircraftData()) }
    get("/api/data/military-history") {
        val snapshot = history.snapshot(all = true)
        call.respond(snapshot.copy(entries = snapshot.entries.filter { it.militaryStatus in setOf("military", "possible") }
            .take((call.request.queryParameters["limit"]?.toIntOrNull() ?: 20).coerceIn(1, 100))))
    }
    get("/api/data/history") {
        val snapshot = history.snapshot(all = true)
        call.respond(snapshot.copy(radiusKm = 2.0, entries = snapshot.entries.filter { it.closestDistanceKm <= 2.0 }
            .take((call.request.queryParameters["limit"]?.toIntOrNull() ?: 20).coerceIn(1, 100))))
    }
}
