package com.codingpitch.aircrafttracker.routes

import kotlinx.serialization.Serializable

@Serializable
data class RouteAirport(
    val name: String = "",
    val municipality: String? = null,
    val iata_code: String? = null,
    val icao_code: String = ""
) {
    fun label(): String {
        val city = municipality?.takeIf { it.isNotBlank() } ?: name
        val code = iata_code?.takeIf { it.isNotBlank() } ?: icao_code
        return if (code.isBlank()) city else "$city ($code)"
    }
}

@Serializable internal data class RouteEnvelope(val response: RouteResponse)

@Serializable internal data class RouteResponse(val flightroute: FlightRoute? = null)

@Serializable
internal data class FlightRoute(
    val origin: RouteAirport,
    val destination: RouteAirport,
    val callsign_iata: String? = null,
    val airline: RouteAirline? = null
)

@Serializable internal data class AirlineEnvelope(val response: List<RouteAirline> = emptyList())

@Serializable
data class RouteAirline(val name: String = "", val icao: String? = null, val iata: String? = null)

data class RouteResult(
    val status: String,
    val origin: RouteAirport? = null,
    val destination: RouteAirport? = null,
    val airline: RouteAirline? = null,
    val reportedFlightNumber: String? = null,
    val source: String = "adsbdb"
) {
    fun hasDublinRoute() = status == "found" && origin?.icao_code != destination?.icao_code &&
        (origin?.icao_code == "EIDW" || destination?.icao_code == "EIDW")

    fun statusFor(aircraft: com.codingpitch.aircrafttracker.model.Aircraft): String {
        if (status != "found") return status
        // This display describes Dublin traffic. A callsign match elsewhere is
        // never a usable route, regardless of missing/level movement readings.
        if (!hasDublinRoute())
            return "uncertain"
        if (aircraft.distanceKm > 30 || aircraft.altitudeFeet == null ||
            aircraft.altitudeFeet > 10000) return status
        val rate = aircraft.verticalRate ?: return status
        return if ((origin?.icao_code == "EIDW" && rate < -300) ||
            (destination?.icao_code == "EIDW" && rate > 300))
            "uncertain"
        else status
    }
}

@Serializable
internal data class StandingRoute(
    val callsign: String = "",
    @kotlinx.serialization.SerialName("_airports") val airports: List<StandingAirport> = emptyList()
)

@Serializable
internal data class StandingAirport(
    val name: String = "", val location: String? = null,
    val icao: String = "", val iata: String? = null
) {
    fun airport() = RouteAirport(name, location, iata, icao)
}

@Serializable
internal data class HexRoute(
    val callsign: String = "",
    val airports: List<StandingAirport> = emptyList()
)
