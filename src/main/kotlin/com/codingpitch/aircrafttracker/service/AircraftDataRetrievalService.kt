package com.codingpitch.aircrafttracker.service

import com.codingpitch.aircrafttracker.model.Aircraft
import com.codingpitch.aircrafttracker.model.RawAircraftData
import com.codingpitch.aircrafttracker.model.LatLng
import kotlinx.coroutines.*
import org.springframework.beans.factory.annotation.Value
import org.springframework.scheduling.annotation.Scheduled
import org.springframework.stereotype.Service
import org.springframework.web.reactive.function.client.WebClient
import org.springframework.web.reactive.function.client.awaitBody

@Service
class AircraftDataRetrievalService(
    private val webClient: WebClient,
    @Value("\${aircraft-tracker.json-filepath}")
    private val jsonFilePath: String,
    @Value("\${aircraft-tracker.interval}")
    private val interval: Long) {

    private final val here = LatLng(53.417032269941224, -6.14787466897715)
    private final val nauticalMile = 0.539956803

    var rawAircraftData: RawAircraftData? = null
    var aircraftData: MutableList<Aircraft> = mutableListOf()
    var current: Aircraft? = null

    @Scheduled(fixedRate = 1000)
    fun getAircraftData() {
        runBlocking {
            rawAircraftData = webClient
                .get()
                .uri("/aircraft.json")
                .retrieve()
                .awaitBody<RawAircraftData>()

            aircraftData.clear()
            rawAircraftData?.aircraft?.forEach {
                if (it.lat != null && it.lon != null) {
                    val distanceInKm = calculateHaversineDistance(here, LatLng(it.lat, it.lon))
                    aircraftData.add(
                        Aircraft(
                            hex = it.hex,
                            flight = it.flight,
                            track = it.track,
                            mag_heading = it.mag_heading,
                            squawk = it.squawk,
                            emergency = it.emergency,
                            category = it.category,
                            lat = it.lat,
                            lon = it.lon,
                            distanceKm = String.format("%.2f", distanceInKm).toDouble(),
                            distanceNm = String.format("%.2f", distanceInKm * nauticalMile).toDouble(),
                            isWithinRange = isWithinRadius(here, LatLng(it.lat, it.lon), 4.0)
                        )
                    )
                }
            }
        }
    }

    fun isWithinRadius(staticLatLng: LatLng, targetLatLng: LatLng, radiusInKilometers: Double): Boolean {
        val distance = calculateHaversineDistance(staticLatLng, targetLatLng)
        return distance <= radiusInKilometers
    }

    fun calculateHaversineDistance(point1: LatLng, point2: LatLng): Double {
        val earthRadius = 6371.0 // Radius of the Earth in kilometers

        val lat1Rad = Math.toRadians(point1.latitude)
        val lon1Rad = Math.toRadians(point1.longitude)
        val lat2Rad = Math.toRadians(point2.latitude)
        val lon2Rad = Math.toRadians(point2.longitude)

        val dLat = lat2Rad - lat1Rad
        val dLon = lon2Rad - lon1Rad

        val a = Math.sin(dLat / 2) * Math.sin(dLat / 2) +
                Math.cos(lat1Rad) * Math.cos(lat2Rad) *
                Math.sin(dLon / 2) * Math.sin(dLon / 2)

        val c = 2 * Math.atan2(Math.sqrt(a), Math.sqrt(1 - a))

        return earthRadius * c
    }
}