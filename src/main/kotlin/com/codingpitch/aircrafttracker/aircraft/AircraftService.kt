package com.codingpitch.aircrafttracker.aircraft

import com.codingpitch.aircrafttracker.model.Aircraft
import com.codingpitch.aircrafttracker.model.LatLng
import com.codingpitch.aircrafttracker.routes.RouteLookupService
import io.ktor.client.HttpClient
import io.ktor.client.call.body
import io.ktor.client.request.get
import kotlinx.serialization.json.intOrNull

class AircraftService(
    private val dataClient: HttpClient,
    private val routeLookup: RouteLookupService
) {
    private val here = LatLng(53.417032269941224, -6.14787466897715)

    suspend fun getAircraftData(): List<Aircraft> {
        val response: AircraftResponse = dataClient.get("/skyaware/data/aircraft.json").body()
        val results = mutableListOf<Aircraft>()
        response.aircraft.forEach { sourceAircraft ->
            if (sourceAircraft.lat != null &&
                sourceAircraft.lon != null &&
                (sourceAircraft.seen ?: 0.0) <= 30.0 &&
                (sourceAircraft.seen_pos ?: 0.0) <= 30.0) {
                val distanceInKm = here.distanceKmTo(LatLng(sourceAircraft.lat, sourceAircraft.lon))
                results.add(
                    Aircraft(
                        hex = sourceAircraft.hex,
                        flight = sourceAircraft.flight?.trim(),
                        origin = "",
                        destination = "",
                        track = sourceAircraft.track,
                        mag_heading = sourceAircraft.mag_heading,
                        squawk = sourceAircraft.squawk,
                        emergency = sourceAircraft.emergency,
                        category = sourceAircraft.category,
                        lat = sourceAircraft.lat,
                        lon = sourceAircraft.lon,
                        distanceKm = kotlin.math.round(distanceInKm * 100) / 100,
                        distanceNm =
                            kotlin.math.round(distanceInKm * NAUTICAL_MILES_PER_KM * 100) / 100,
                        isWithinRange = distanceInKm <= HOME_RADIUS_KM,
                        altitudeFeet = sourceAircraft.alt_baro?.intOrNull,
                        verticalRate = sourceAircraft.baro_rate,
                        seenSeconds = sourceAircraft.seen))
            }
        }
        // Enrich traffic in the 10 km zone; prefetch the nearest eight within 30 km.
        val sorted = results.sortedBy { it.distanceKm }
        return sorted.mapIndexed { index, aircraft ->
            if ((index >= 8 && !aircraft.isWithinRange) ||
                index >= 32 ||
                aircraft.distanceKm > 30.0)
                aircraft
            else {
                val route = routeLookup.routeFor(aircraft.flight)
                aircraft.copy(
                    origin = route.origin?.label(),
                    destination = route.destination?.label(),
                    originCode = route.origin?.icao_code,
                    destinationCode = route.destination?.icao_code,
                    routeStatus = route.statusFor(aircraft),
                    routeSource = if (route.status == "found") "adsbdb" else null,
                    airlineName = route.airline?.name,
                    airlineIcao = route.airline?.icao,
                    airlineIata = route.airline?.iata,
                    reportedFlightNumber = route.reportedFlightNumber)
            }
        }
    }

    companion object {
        const val HOME_RADIUS_KM = 10.0
        private const val NAUTICAL_MILES_PER_KM = 0.539956803
    }
}
