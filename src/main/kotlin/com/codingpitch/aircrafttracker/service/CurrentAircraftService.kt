package com.codingpitch.aircrafttracker.service

import com.codingpitch.aircrafttracker.model.Aircraft
import org.springframework.stereotype.Service

@Service
class CurrentAircraftService(val aircraftDataRetrievalService: AircraftDataRetrievalService) {

    suspend fun getCurrentAircraft(): Aircraft? {
        return aircraftDataRetrievalService.current
    }
}