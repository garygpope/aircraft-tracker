package com.codingpitch.aircrafttracker.history

import java.time.*
import java.time.format.DateTimeFormatter
import kotlinx.serialization.Serializable

@Serializable data class LogFacets(val airlines: List<String>, val origins: List<String>, val destinations: List<String>)
@Serializable data class LogEntry(val passage: Passage, val nearHome: Boolean, val militaryNearby: Boolean, val nightDate: String?)
@Serializable data class Measure(val count: Int, val average: Double?, val minimum: Double?, val maximum: Double?)
@Serializable data class Tally(val label: String, val count: Int)
@Serializable data class BusiestHour(val startsAt: Long, val movements: Int)
@Serializable data class LogStats(
    val passages: Int, val uniqueAircraft: Int, val nightPassages: Int,
    val arrivals: Int, val departures: Int, val routeMatches: Int,
    val military: Int, val possibleMilitary: Int,
    val distanceKm: Measure, val altitudeFeet: Measure, val groundSpeedKnots: Measure, val indicatedAirspeedKnots: Measure,
    val airlines: List<Tally>, val routes: List<Tally>, val nights: List<Tally>, val days: List<Tally>, val busiestHour: BusiestHour? = null
)
@Serializable data class LogHealth(val label: String, val retained: Int, val storageStatus: String, val receiverStatus: String,
    val lastSampleAt: Long?, val enabled: Boolean, val capacityLimited: Boolean)
@Serializable data class LogResponse(val entries: List<LogEntry>, val total: Int, val page: Int, val pageSize: Int,
    val stats: LogStats, val health: List<LogHealth>, val retentionDays: Int = 30,
    val timeZone: String = "Europe/Dublin", val oldestAt: Long? = null, val newestAt: Long? = null, val allAircraftSince: Long? = null)

/** Filtering, statistics and CSV all use the same deduplicated, complete window. */
class Logbook(private val history: PassageHistory, private val clock: () -> Long = System::currentTimeMillis) {
    companion object {
        val zone: ZoneId = ZoneId.of("Europe/Dublin")
        fun nightDate(time: Long): String? {
            val local = Instant.ofEpochMilli(time).atZone(zone)
            return when {
                local.hour < 7 -> local.toLocalDate().minusDays(1).toString()
                local.hour >= 23 -> local.toLocalDate().toString()
                else -> null
            }
        }
        fun nightBounds(date: String): Pair<Long, Long> {
            val day = LocalDate.parse(date)
            return day.atTime(23, 0).atZone(zone).toInstant().toEpochMilli() to
                day.plusDays(1).atTime(7, 0).atZone(zone).toInstant().toEpochMilli()
        }
        private fun dublin(label: String?) = label?.endsWith(" (DUB)") == true || label?.endsWith(" (EIDW)") == true
        private fun measure(values: List<Double>): Measure {
            val valid = values.filter { it.isFinite() }
            return Measure(valid.size, valid.takeIf { it.isNotEmpty() }?.average(), valid.minOrNull(), valid.maxOrNull())
        }
        private fun tallies(values: List<String>, limit: Int = 5) = values.groupingBy { it }.eachCount()
            .map { Tally(it.key, it.value) }.sortedWith(compareByDescending<Tally> { it.count }.thenBy { it.label }).take(limit)
        private val csvDate = DateTimeFormatter.ofPattern("dd MMM yyyy HH:mm:ss XXX").withZone(zone)
        fun csvCell(value: Any?): String {
            var text = value?.toString().orEmpty()
            if (value is String && (text.trimStart().firstOrNull() in listOf('=', '+', '-', '@') || text.startsWith('\t') || text.startsWith('\r'))) text = "'$text"
            return "\"${text.replace("\"", "\"\"") }\""
        }
    }

    private data class Selection(val rows: List<LogEntry>, val health: List<LogHealth>, val oldest: Long?, val newest: Long?, val since: Long?)
    private fun select(params: Map<String, String>): Selection {
        val snapshot = history.snapshot(all = true)
        val all = snapshot.entries.map { LogEntry(it, it.closestDistanceKm <= 2.0,
            it.militaryStatus in setOf("military", "possible"), nightDate(it.closestAt)) }
        fun param(key: String) = params[key]?.trim()?.takeIf { it.isNotEmpty() }
        fun choice(key: String, values: Set<String>, default: String): String = (param(key) ?: default).also {
            require(it in values) { "Invalid $key" }
        }
        fun instant(key: String, end: Boolean = false) = param(key)?.let {
            val local = LocalDateTime.parse(it)
            val zoned = local.atZone(zone)
            require(zoned.toLocalDateTime() == local) { "$key falls in a Dublin clock-change gap" }
            (if (end) zoned.withLaterOffsetAtOverlap() else zoned.withEarlierOffsetAtOverlap()).toInstant().toEpochMilli()
        }
        val night = param("night")?.let { nightBounds(it) }
        val period = choice("period", setOf("all", "hour", "3hours", "today", "last-night", "7days", "14days"), "all")
        val now = clock()
        val today = Instant.ofEpochMilli(now).atZone(zone).toLocalDate()
        val relative = when (period) {
            "hour" -> now - 3_600_000L to now
            "3hours" -> now - 3 * 3_600_000L to now
            "today" -> today.atStartOfDay(zone).toInstant().toEpochMilli() to now
            "last-night" -> nightBounds(today.minusDays(1).toString())
            "7days" -> now - 7 * 86_400_000L to now
            "14days" -> now - 14 * 86_400_000L to now
            else -> null
        }
        val from = relative?.first ?: instant("from"); val to = relative?.second ?: instant("to", true)
        require(from == null || to == null || from < to) { "From must be before To" }
        val scope = choice("scope", setOf("all", "home", "military"), "all")
        val status = choice("route", setOf("all", "found", "unknown", "arrival", "departure"), "all")
        val kind = choice("military", setOf("all", "military", "possible", "unknown"), "all")
        val nightOnly = choice("nightOnly", setOf("true", "false"), "false") == "true"
        val fields = mapOf("flight" to { p: Passage -> listOf(p.flight, p.reportedFlightNumber) },
            "hex" to { p: Passage -> listOf(p.hex) }, "airline" to { p: Passage -> listOf(p.airlineName) },
            "origin" to { p: Passage -> listOf(p.origin) }, "destination" to { p: Passage -> listOf(p.destination) },
            "squawk" to { p: Passage -> listOf(p.squawk) },
            "category" to { p: Passage -> listOf(p.category) }, "emergency" to { p: Passage -> listOf(p.emergency) },
            "basis" to { p: Passage -> listOf(p.militaryBasis) }, "source" to { p: Passage -> listOf(p.routeSource) })
        val exact = choice("exact", setOf("true", "false"), "false") == "true"
        val textFilters = fields.mapNotNull { (key, field) -> param(key)?.let { Triple(key, it, field) } }
        val search = param("search")
        val numeric = mapOf("distance" to { p: Passage -> p.closestDistanceKm }, "altitude" to { p: Passage -> p.altitudeFeet?.toDouble() },
            "gs" to { p: Passage -> p.groundSpeedKnots }, "ias" to { p: Passage -> p.indicatedAirspeedKnots },
            "vertical" to { p: Passage -> p.verticalRate?.toDouble() }, "track" to { p: Passage -> p.track })
        val ranges = numeric.map { (name, field) ->
            fun bound(suffix: String): Double? = param(name + suffix)?.let { text ->
                text.toDoubleOrNull()?.takeIf { it.isFinite() && (name in setOf("altitude", "vertical") || it >= 0) }
                    ?: throw IllegalArgumentException("Invalid $name$suffix")
            }
            val min = bound("Min"); val max = bound("Max")
            require(min == null || max == null || min <= max) { "Invalid $name range" }
            Triple(field, min, max)
        }
        val rows = all.filter { row ->
            val p = row.passage
            (from == null || p.closestAt >= from) && (to == null || p.closestAt < to) &&
            (night == null || (p.closestAt >= night.first && p.closestAt < night.second)) &&
            (!nightOnly || row.nightDate != null) &&
            (scope == "all" || (scope == "home" && row.nearHome) || (scope == "military" && row.militaryNearby)) &&
            (kind == "all" || p.militaryStatus == kind) &&
            when (status) { "found" -> p.routeStatus == "found"; "unknown" -> p.routeStatus != "found"
                "arrival" -> p.routeStatus == "found" && dublin(p.destination)
                "departure" -> p.routeStatus == "found" && dublin(p.origin); else -> true } &&
            textFilters.all { (key, text, field) ->
                if (key in setOf("airline", "origin", "destination") && text == "__unknown__") field(p).all { it.isNullOrBlank() }
                else field(p).any { if (exact && key != "flight") it?.equals(text, ignoreCase = true) == true else it?.contains(text, ignoreCase = true) == true }
            } &&
            (search == null || listOf(p.flight, p.hex, p.airlineName, p.origin, p.destination, p.reportedFlightNumber, p.squawk, p.routeSource)
                .any { it?.contains(search, ignoreCase = true) == true }) &&
            ranges.all { (field, min, max) -> (min == null && max == null) || field(p)?.let { v ->
                v.isFinite() && (min == null || v >= min) && (max == null || v <= max) } == true }
        }
        val sort = choice("sort", setOf("time", "distance", "altitude", "gs", "ias", "flight", "airline"), "time")
        val desc = choice("order", setOf("asc", "desc"), "desc") == "desc"
        fun value(p: Passage): Comparable<*>? = when (sort) {
            "distance" -> p.closestDistanceKm; "altitude" -> p.altitudeFeet; "gs" -> p.groundSpeedKnots
            "ias" -> p.indicatedAirspeedKnots; "flight" -> p.flight; "airline" -> p.airlineName; else -> p.closestAt
        }
        @Suppress("UNCHECKED_CAST")
        val sorted = rows.sortedWith { x, y ->
            val xv = value(x.passage); val yv = value(y.passage)
            val result = when { xv == null && yv == null -> 0; xv == null -> 1; yv == null -> -1
                else -> (xv as Comparable<Any>).compareTo(yv) * if (desc) -1 else 1 }
            if (result != 0) result else compareValues(x.passage.hex, y.passage.hex)
        }
        val health = listOf(LogHealth("Flight log · 10 km", snapshot.entries.size, snapshot.storageStatus,
            snapshot.receiverStatus, snapshot.lastSampleAt, snapshot.enabled, snapshot.capacityLimited))
        return Selection(sorted, health, all.minOfOrNull { it.passage.closestAt }, all.maxOfOrNull { it.passage.closestAt }, snapshot.allAircraftSince)
    }

    private fun stats(rows: List<LogEntry>): LogStats {
        val p = rows.map { it.passage }
        val found = p.filter { it.routeStatus == "found" }
        val peak = p.groupingBy { Instant.ofEpochMilli(it.closestAt).atZone(zone).truncatedTo(java.time.temporal.ChronoUnit.HOURS).toInstant().toEpochMilli() }
            .eachCount().maxWithOrNull(compareBy<Map.Entry<Long, Int>> { it.value }.thenBy { it.key })
        return LogStats(p.size, p.map { it.hex }.distinct().size, rows.count { it.nightDate != null },
            found.count { dublin(it.destination) }, found.count { dublin(it.origin) }, found.size,
            p.count { it.militaryStatus == "military" }, p.count { it.militaryStatus == "possible" },
            measure(p.map { it.closestDistanceKm }), measure(p.mapNotNull { it.altitudeFeet?.toDouble() }),
            measure(p.mapNotNull { it.groundSpeedKnots }), measure(p.mapNotNull { it.indicatedAirspeedKnots }),
            tallies(p.map { it.airlineName ?: "Unrecorded airline" }),
            tallies(found.map { "${it.origin ?: "Unknown"} → ${it.destination ?: "Unknown"}" }),
            tallies(rows.mapNotNull { it.nightDate }, 31).sortedByDescending { it.label },
            tallies(p.map { Instant.ofEpochMilli(it.closestAt).atZone(zone).toLocalDate().toString() }, 32).sortedBy { it.label },
            peak?.let { BusiestHour(it.key, it.value) })
    }

    fun facets(): LogFacets {
        val rows = history.snapshot(all = true).entries
        fun values(field: (Passage) -> String?) = rows.mapNotNull(field).filter { it.isNotBlank() }.distinct().sortedWith(String.CASE_INSENSITIVE_ORDER)
        return LogFacets(values { it.airlineName }, values { it.origin }, values { it.destination })
    }

    fun query(params: Map<String, String>): LogResponse {
        val selection = select(params)
        fun integer(key: String, default: Int, max: Int) = params[key]?.let {
            it.toIntOrNull()?.takeIf { n -> n in 1..max } ?: throw IllegalArgumentException("Invalid $key")
        } ?: default
        val size = integer("pageSize", 50, 200)
        val page = integer("page", 1, 100_000).coerceAtMost(maxOf(1, (selection.rows.size + size - 1) / size))
        return LogResponse(selection.rows.drop((page - 1) * size).take(size), selection.rows.size, page, size,
            stats(selection.rows), selection.health, oldestAt = selection.oldest, newestAt = selection.newest, allAircraftSince = selection.since)
    }

    fun csv(params: Map<String, String>): String {
        val header = listOf("Closest approach (Dublin, UTC offset)", "Night starting", "Callsign", "Hex", "Aircraft type", "Aircraft type code", "Aircraft type source", "Airline", "Origin", "Destination", "Route status", "Route source", "Flight number", "Near home (2 km)", "Military nearby (10 km)", "Military status", "Military basis", "Closest km", "Altitude ft", "GS kt", "GS km/h", "IAS kt", "IAS km/h", "Vertical rate ft/min", "Track degrees", "Squawk", "Emergency", "Category", "First seen (Dublin)", "Last seen (Dublin)")
        return buildString {
            append('\uFEFF'); append(header.joinToString(",", transform = ::csvCell)); append("\r\n")
            for (row in select(params).rows) {
                val p = row.passage
                val values = listOf(csvDate.format(Instant.ofEpochMilli(p.closestAt)), row.nightDate, p.flight, p.hex, p.aircraftType, p.aircraftTypeCode, p.aircraftTypeSource, p.airlineName,
                    p.origin, p.destination, p.routeStatus, p.routeSource, p.reportedFlightNumber, row.nearHome, row.militaryNearby,
                    p.militaryStatus, p.militaryBasis, p.closestDistanceKm, p.altitudeFeet, p.groundSpeedKnots, p.groundSpeedKnots?.times(1.852),
                    p.indicatedAirspeedKnots, p.indicatedAirspeedKnots?.times(1.852), p.verticalRate, p.track, p.squawk, p.emergency, p.category,
                    csvDate.format(Instant.ofEpochMilli(p.firstSeenAt)), csvDate.format(Instant.ofEpochMilli(p.lastSeenAt)))
                append(values.joinToString(",", transform = ::csvCell)); append("\r\n")
            }
        }
    }
}
