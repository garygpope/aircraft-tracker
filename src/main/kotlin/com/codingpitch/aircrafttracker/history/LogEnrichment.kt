package com.codingpitch.aircrafttracker.history

import com.codingpitch.aircrafttracker.model.Aircraft
import com.codingpitch.aircrafttracker.aircraft.AircraftTypeLookup
import com.codingpitch.aircrafttracker.routes.RouteLookupService
import kotlinx.coroutines.*

/** Slow, bounded metadata retries independent of the live receiver loop. */
class LogEnrichment(private val history: PassageHistory, private val routes: RouteLookupService, private val types: AircraftTypeLookup) {
    suspend fun runTypes() {
        val attempted = mutableMapOf<String, Long>()
        while (currentCoroutineContext().isActive) {
            val entries = history.snapshot(all = true).entries
            attempted.keys.retainAll(entries.map { it.hex }.toSet())
            for (hex in entries.filter { it.aircraftType.isNullOrBlank() }.map { it.hex }.distinct()) {
                val now = System.currentTimeMillis()
                if (now - (attempted[hex] ?: 0) < 6 * 60 * 60_000L) continue
                attempted[hex] = now
                try {
                    val type = withTimeoutOrNull(20_000) {
                        var value = types.typeFor(hex)
                        while (value.status == "pending") { delay(200); value = types.typeFor(hex) }
                        value
                    }
                    if (type != null) history.enrichType(hex, type)
                    history.flush()
                } catch (e: CancellationException) { throw e } catch (_: Exception) { }
                delay(1000)
            }
            delay(60_000)
        }
    }

    suspend fun run() {
        val attempted = mutableMapOf<Pair<String, Long>, Long>()
        while (currentCoroutineContext().isActive) {
            val entries = history.snapshot(all = true).entries
            val keys = entries.map { it.hex to it.firstSeenAt }.toSet()
            attempted.keys.retainAll(keys)
            for (p in entries) {
                currentCoroutineContext().ensureActive()
                val now = System.currentTimeMillis()
                val key = p.hex to p.firstSeenAt
                if (p.flight?.matches(Regex("[A-Za-z0-9]{3,8}")) != true ||
                    (p.airlineName != null && p.routeStatus == "found") ||
                    now - (attempted[key] ?: 0) < 6 * 60 * 60_000L) continue
                attempted[key] = now
                // Only a callsign is sent to providers; saved positions are never transmitted.
                val context = Aircraft(p.hex, p.flight, null, null, p.track, null, p.squawk,
                    p.emergency, p.category, 0.0, 0.0, p.closestDistanceKm, 0.0, true,
                    altitudeFeet = p.altitudeFeet, verticalRate = p.verticalRate)
                try {
                    val route = withTimeoutOrNull(20_000) {
                        var result = routes.routeFor(p.flight, context)
                        while (result.status == "pending") {
                            delay(200)
                            result = routes.routeFor(p.flight, context)
                        }
                        result
                    }
                    if (route != null) {
                        // Callsign databases are not dated flight plans: do not apply today's
                        // route to older movements. Operator names can still be recovered.
                        val recent = now - p.lastSeenAt in 0..24 * 60 * 60_000L
                        history.enrich(p, route, recent && route.statusFor(context) == "found")
                        history.flush()
                    }
                } catch (e: CancellationException) { throw e } catch (_: Exception) {
                    // A provider outage must not affect recording or serving saved movements.
                }
                delay(1000)
            }
            history.flush()
            delay(60_000)
        }
    }
}
