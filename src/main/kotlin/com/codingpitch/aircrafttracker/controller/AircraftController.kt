package com.codingpitch.aircrafttracker.controller

import com.codingpitch.aircrafttracker.model.Aircraft
import com.codingpitch.aircrafttracker.model.RawAircraftData
import com.codingpitch.aircrafttracker.service.AircraftDataRetrievalService
import org.springframework.web.bind.annotation.GetMapping
import org.springframework.web.bind.annotation.RestController


@RestController
class AircraftController(
    val aircraftDataRetrievalService: AircraftDataRetrievalService
) {

    @GetMapping("/")
    suspend fun getAircraftData(): Any? {
        return aircraftDataRetrievalService.aircraftData
    }

    @GetMapping("/raw")
    suspend fun getRawAircraftData(): RawAircraftData? {
        return aircraftDataRetrievalService.rawAircraftData
    }

    @GetMapping("/current")
    suspend fun getCurrent(): Aircraft? {
        return aircraftDataRetrievalService.current
    }
}