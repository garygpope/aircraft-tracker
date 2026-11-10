package com.codingpitch.aircrafttracker.model

import kotlinx.serialization.Serializable

@Serializable
data class Aircraft(
    val hex: String,
    val flight: String?,
    val origin: String?,
    val destination: String?,
    val track: Double?,
    val mag_heading: Double?,
    val squawk: String?,
    val emergency: String?,
    val category: String?,
    val lat: Double,
    val lon: Double,
    val distanceKm: Double,
    val distanceNm: Double,
    val isWithinRange: Boolean,
    val altitudeFeet: Int? = null,
    val verticalRate: Int? = null,
    val seenSeconds: Double? = null,
    val originCode: String? = null,
    val destinationCode: String? = null,
    val routeStatus: String = "not-requested",
    val routeSource: String? = null,
    val airlineName: String? = null,
    val airlineIcao: String? = null,
    val airlineIata: String? = null,
    val reportedFlightNumber: String? = null
)
