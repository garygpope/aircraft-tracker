package com.codingpitch.aircrafttracker.model

import com.fasterxml.jackson.annotation.JsonIgnoreProperties
import java.time.Instant

data class RawAircraftData(
    val now: Instant = Instant.now(),
    val messages: Int = 0,
    val aircraft: List<RawAircraft> = emptyList()
)

@JsonIgnoreProperties(ignoreUnknown = true)
data class RawAircraft(
    val hex: String = "",
    val flight: String? = null,
    val alt_baro: Int = 0,
    val alt_geom: Int = 0,
    val gs: Double = 0.0,
    val ias: Int = 0,
    val mach: Double = 0.0,
    val track: Double = 0.0,
    val mag_heading: Double = 0.0,
    val baro_rate: Int = 0,
    val geom_rate: Int = 0,
    val squawk: String = "",
    val emergency: String = "",
    val category: String = "",
    val nav_qnh: Double = 0.0,
    val nav_heading: Double = 0.0,
    val lat: Double? = null,
    val lon: Double? = null,
    val seen_pos: Double = 0.0,
    val version: Int = 0,
    val messages: Int = 0,
    val nav_altitude_mcp: Int = 0,
    val nic: Int = 0,
    val rc: Int = 0,
    val nic_baro: Int = 0,
    val nac_p: Int = 0,
    val nac_v: Int = 0,
    val sil: Int = 0,
    val sil_type: String = "",
    val gva: Int = 0,
    val sda: Int = 0,
    val mlat: List<Any> = emptyList(),
    val tisb: List<Any> = emptyList(),
    val seen: Double = 0.0,
    val rssi: Double = 0.0
)

data class Aircraft(
    val hex: String,
    val flight: String?,
    val track: Double,
    val mag_heading: Double,
    val squawk: String?,
    val emergency: String?,
    val category: String?,
    val lat: Double,
    val lon: Double,
    val distanceKm: Double,
    val distanceNm: Double,
    val isWithinRange: Boolean
)

