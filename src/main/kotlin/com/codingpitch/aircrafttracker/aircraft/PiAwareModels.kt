package com.codingpitch.aircrafttracker.aircraft

import kotlinx.serialization.Serializable
import kotlinx.serialization.json.JsonPrimitive

@Serializable internal data class AircraftResponse(val aircraft: List<SourceAircraft>)

@Serializable
internal data class SourceAircraft(
    val hex: String,
    val flight: String? = null,
    val lat: Double? = null,
    val lon: Double? = null,
    val track: Double? = null,
    val mag_heading: Double? = null,
    val squawk: String? = null,
    val emergency: String? = null,
    val category: String? = null,
    val alt_baro: JsonPrimitive? = null,
    val baro_rate: Int? = null,
    val seen: Double? = null,
    val seen_pos: Double? = null
)
