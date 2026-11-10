package com.codingpitch.aircrafttracker.http

import com.codingpitch.aircrafttracker.aircraft.AircraftService
import io.ktor.server.application.call
import io.ktor.server.response.respond
import io.ktor.server.routing.*

fun Route.aircraftRoutes(aircraft: AircraftService) {
    get("/api/data/aircraft") { call.respond(aircraft.getAircraftData()) }
}
