package com.codingpitch.aircrafttracker

import org.springframework.boot.autoconfigure.SpringBootApplication
import org.springframework.boot.runApplication
import org.springframework.scheduling.annotation.EnableScheduling

@SpringBootApplication
@EnableScheduling
class AircraftTrackerApplication

fun main(args: Array<String>) {
	runApplication<AircraftTrackerApplication>(*args)
}
