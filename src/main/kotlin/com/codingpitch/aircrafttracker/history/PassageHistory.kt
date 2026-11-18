package com.codingpitch.aircrafttracker.history

import com.codingpitch.aircrafttracker.model.Aircraft
import java.io.Closeable
import java.nio.ByteBuffer
import java.nio.channels.FileChannel
import java.nio.file.StandardOpenOption
import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.StandardCopyOption
import kotlinx.serialization.Serializable
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import org.slf4j.LoggerFactory

@Serializable
data class Passage(
    val hex: String,
    val flight: String?,
    val firstSeenAt: Long,
    val lastSeenAt: Long,
    val closestAt: Long,
    val closestDistanceKm: Double,
    val altitudeFeet: Int?,
    val airlineName: String? = null,
    val origin: String? = null,
    val destination: String? = null,
    val routeStatus: String = "not-found",
    val militaryStatus: String = "unknown",
    val militaryBasis: String? = null,
    val groundSpeedKnots: Double? = null,
    val indicatedAirspeedKnots: Double? = null,
    val verticalRate: Int? = null,
    val track: Double? = null,
    val squawk: String? = null,
    val emergency: String? = null,
    val category: String? = null,
    val routeSource: String? = null,
    val reportedFlightNumber: String? = null,
    val aircraftType: String? = null,
    val aircraftTypeCode: String? = null,
    val aircraftTypeSource: String? = null
)

@Serializable
data class HistoryResponse(
    val entries: List<Passage>,
    val radiusKm: Double,
    val retentionDays: Int,
    val maxEntries: Int,
    val storageStatus: String,
    val receiverStatus: String,
    val lastSampleAt: Long?,
    val enabled: Boolean,
    val capacityLimited: Boolean = false,
    val allAircraftSince: Long? = null
)

/** One compact summary per passage, with hard age, count and file-size limits. */
class PassageHistory(
    private val file: Path?,
    val radiusKm: Double = 2.0,
    private val clock: () -> Long = System::currentTimeMillis,
    private val retentionDays: Int = 30,
    private val maxEntries: Int = 50_000,
    private val maxFileBytes: Int = 32 * 1024 * 1024,
    private val enabled: Boolean = true
) : Closeable {
    private val log = LoggerFactory.getLogger(PassageHistory::class.java)
    private val json = Json { ignoreUnknownKeys = true }
    private val passages = mutableListOf<Passage>()
    private var dirty = true
    private var lastFlushAttempt: Long? = null
    private var storageStatus = if (file == null) "memory" else "pending"
    private var receiverStatus = "starting"
    private var lastSampleAt: Long? = null
    private var closed = false
    private var corruptFile = false
    @Serializable private data class Metadata(val allAircraftSince: Long, val capacityLimitedAt: Long? = null)
    private val metadataFile = file?.resolveSibling(file.fileName.toString() + ".metadata.json")
    private val metadata = runCatching {
        if (metadataFile != null && Files.exists(metadataFile)) json.decodeFromString<Metadata>(Files.readString(metadataFile))
        else Metadata(clock())
    }.getOrElse { Metadata(clock()) }
    private var capacityLimitedAt = metadata.capacityLimitedAt

    init {
        require(radiusKm.isFinite() && radiusKm > 0 && radiusKm <= 10)
        require(retentionDays in 1..30 && maxEntries in 1..50_000 && maxFileBytes > 0)
        if (enabled && file != null && Files.exists(file)) {
            try {
                require(Files.size(file) <= maxFileBytes)
                passages.addAll(json.decodeFromString<List<Passage>>(Files.readString(file)))
                passages.removeAll { !valid(it) }
                storageStatus = "saved"
                prune(clock())
            } catch (e: Exception) {
                // Preserve an unreadable existing file for recovery instead of overwriting it.
                corruptFile = true
                storageStatus = "unavailable"
                log.warn("Passage history could not be read; preserving existing file", e)
            }
        }
    }

    private fun valid(p: Passage) = p.hex.isNotBlank() && p.hex.length <= 32 &&
        p.firstSeenAt <= p.closestAt && p.closestAt <= p.lastSeenAt &&
        p.lastSeenAt <= clock() && p.closestDistanceKm.isFinite() &&
        p.closestDistanceKm >= 0 && p.closestDistanceKm <= radiusKm &&
        listOf(p.flight, p.airlineName, p.origin, p.destination, p.routeStatus, p.militaryBasis, p.aircraftType, p.aircraftTypeCode, p.aircraftTypeSource).all { it == null || it.length <= 120 }

    private fun prune(now: Long) {
        if (passages.removeAll { it.closestAt < now - retentionDays * 86_400_000L }) dirty = true
        passages.sortBy { it.lastSeenAt }
        if (passages.size > maxEntries) {
            capacityLimitedAt = now
            passages.subList(0, passages.size - maxEntries).clear()
            dirty = true
        }
    }

    /** One-time migration; original files are never modified or removed. */
    @Synchronized
    fun importLegacy(files: List<Path>) {
        if (!enabled || file == null || Files.exists(file) || corruptFile) return
        try {
            val loaded = files.filter { Files.exists(it) }.flatMap { source ->
                require(Files.size(source) <= maxFileBytes) { "Legacy log exceeds import limit" }
                json.decodeFromString<List<Passage>>(Files.readString(source))
            }.filter { valid(it) }.map {
                if (it.flight?.startsWith("EAI") == true && it.airlineName == "Elite Air")
                    it.copy(airlineName = "Aer Lingus Regional (Emerald Airlines)") else it
            }.sortedBy { it.firstSeenAt }
            val merged = mutableListOf<Passage>()
            val lastByHex = mutableMapOf<String, Int>()
            for (p in loaded) {
                val index = lastByHex[p.hex]
                val q = index?.let { merged[it] }
                if (q != null && p.firstSeenAt <= q.lastSeenAt && q.firstSeenAt <= p.lastSeenAt) {
                    val closest = if (p.closestDistanceKm < q.closestDistanceKm) p else q
                    val tagged = if (rank(p.militaryStatus) > rank(q.militaryStatus)) p else q
                    merged[index] = closest.copy(firstSeenAt = minOf(p.firstSeenAt, q.firstSeenAt),
                        lastSeenAt = maxOf(p.lastSeenAt, q.lastSeenAt), militaryStatus = tagged.militaryStatus,
                        militaryBasis = tagged.militaryBasis)
                } else { lastByHex[p.hex] = merged.size; merged.add(p) }
            }
            passages.addAll(merged)
            prune(clock())
            dirty = true
            flush(force = true)
        } catch (e: Exception) {
            // Never create a partial migration over an unreadable archive.
            corruptFile = true
            storageStatus = "unavailable"
            log.warn("Flight log migration could not complete; originals retained", e)
        }
    }

    private fun rank(status: String) = when (status) { "military" -> 2; "possible" -> 1; else -> 0 }

    @Synchronized
    fun observe(aircraft: List<Aircraft>) {
        if (!enabled || closed) return
        val now = clock()
        receiverStatus = "live"
        lastSampleAt = now
        prune(now)
        for (p in aircraft.distinctBy { it.hex }) {
            if (!p.distanceKm.isFinite() || p.distanceKm < 0 || p.distanceKm > radiusKm) continue
            val hex = p.hex.take(32)
            if (hex.isBlank()) continue
            val index = passages.indexOfLast { it.hex == hex && now - it.lastSeenAt < PASSAGE_GAP_MS }
            val previous = passages.getOrNull(index)
            val closer = previous == null || p.distanceKm < previous.closestDistanceKm
            val keepRoute = previous?.routeStatus == "found" && previous.flight == p.flight &&
                p.routeStatus in setOf("pending", "unavailable", "not-requested")
            val entry = Passage(
                hex = hex,
                flight = p.flight?.take(120) ?: previous?.flight,
                firstSeenAt = previous?.firstSeenAt ?: now,
                lastSeenAt = now,
                closestAt = if (closer) now else previous!!.closestAt,
                closestDistanceKm = if (closer) p.distanceKm else previous!!.closestDistanceKm,
                altitudeFeet = if (closer) p.altitudeFeet else previous!!.altitudeFeet,
                airlineName = p.airlineName?.take(120) ?: previous?.airlineName,
                origin = if (keepRoute) previous?.origin else if (p.routeStatus == "found") p.origin?.take(120) else null,
                destination = if (keepRoute) previous?.destination else if (p.routeStatus == "found") p.destination?.take(120) else null,
                routeStatus = if (keepRoute) "found" else p.routeStatus.take(120),
                militaryStatus = if (previous != null && rank(previous.militaryStatus) > rank(p.militaryStatus)) previous.militaryStatus else p.militaryStatus,
                militaryBasis = if (previous != null && rank(previous.militaryStatus) > rank(p.militaryStatus)) previous.militaryBasis else p.militaryBasis?.take(120),
                groundSpeedKnots = if (closer) p.groundSpeedKnots else previous!!.groundSpeedKnots,
                indicatedAirspeedKnots = if (closer) p.indicatedAirspeedKnots else previous!!.indicatedAirspeedKnots,
                verticalRate = if (closer) p.verticalRate else previous!!.verticalRate,
                track = if (closer) p.track else previous!!.track,
                squawk = if (closer) p.squawk?.take(120) else previous!!.squawk,
                emergency = if (closer) p.emergency?.take(120) else previous!!.emergency,
                category = if (closer) p.category?.take(120) else previous!!.category,
                routeSource = if (keepRoute) previous?.routeSource else if (p.routeStatus == "found") p.routeSource?.take(120) else null,
                reportedFlightNumber = if (keepRoute) previous?.reportedFlightNumber else if (p.routeStatus == "found") p.reportedFlightNumber?.take(120) else null,
                aircraftType = p.aircraftType?.take(120) ?: previous?.aircraftType,
                aircraftTypeCode = p.aircraftTypeCode?.take(120) ?: previous?.aircraftTypeCode,
                aircraftTypeSource = p.aircraftTypeSource?.take(120) ?: previous?.aircraftTypeSource
            )
            if (index < 0) passages.add(entry) else passages[index] = entry
            dirty = true
        }
        prune(now)
    }

    @Synchronized
    fun feedUnavailable() { if (!closed) receiverStatus = "offline" }

    @Synchronized
    fun snapshot(limit: Int = 20, all: Boolean = false): HistoryResponse {
        prune(clock())
        return HistoryResponse(
            passages.sortedWith(compareByDescending<Passage> { it.closestAt }.thenByDescending { it.lastSeenAt }.thenBy { it.hex }).take(if (all) maxEntries else limit.coerceIn(1, 100)).map { passage ->
                // Old snapshots lack ICAO codes. Hide unrelated historical matches
                // in responses while retaining the saved sightings unchanged.
                if (passage.routeStatus == "found" &&
                    passage.origin?.endsWith(" (DUB)") != true &&
                    passage.origin?.endsWith(" (EIDW)") != true &&
                    passage.destination?.endsWith(" (DUB)") != true &&
                    passage.destination?.endsWith(" (EIDW)") != true)
                    passage.copy(origin = null, destination = null, routeStatus = "uncertain")
                else passage
            }, radiusKm, retentionDays,
            maxEntries, if (!enabled) "disabled" else storageStatus,
            receiverStatus, lastSampleAt, enabled, capacityLimitedAt?.let { clock() - it < retentionDays * 86_400_000L } == true, metadata.allAircraftSince
        )
    }

    /** Merge metadata only; never change the recorded movement measurements or create records. */
    @Synchronized
    fun enrich(original: Passage, route: com.codingpitch.aircrafttracker.routes.RouteResult, usable: Boolean): Boolean {
        if (closed || !enabled) return false
        val index = passages.indexOfFirst { it.hex == original.hex && it.firstSeenAt == original.firstSeenAt && it.flight == original.flight }
        if (index < 0) return false
        val current = passages[index]
        val hasRoute = current.routeStatus == "found" &&
            listOf(current.origin, current.destination).any { it?.endsWith(" (DUB)") == true || it?.endsWith(" (EIDW)") == true }
        val fillRoute = !hasRoute && usable && route.hasDublinRoute()
        val updated = current.copy(
            airlineName = current.airlineName?.takeIf { it.isNotBlank() } ?: route.airline?.name?.takeIf { it.isNotBlank() }?.take(120),
            origin = if (fillRoute) route.origin?.label()?.take(120) else current.origin,
            destination = if (fillRoute) route.destination?.label()?.take(120) else current.destination,
            routeStatus = if (fillRoute) "found" else current.routeStatus,
            routeSource = if (fillRoute) "${route.source} · refreshed database match".take(120) else current.routeSource,
            reportedFlightNumber = current.reportedFlightNumber ?: if (fillRoute) route.reportedFlightNumber?.take(120) else null
        )
        if (updated == current) return false
        passages[index] = updated
        dirty = true
        return true
    }

    @Synchronized
    fun enrichType(hex: String, type: com.codingpitch.aircrafttracker.aircraft.AircraftTypeResult): Boolean {
        if (closed || !enabled || type.status != "found" || type.label.isNullOrBlank()) return false
        var changed = false
        for (index in passages.indices) {
            val p = passages[index]
            if (p.hex.equals(hex, true) && p.aircraftType.isNullOrBlank()) {
                passages[index] = p.copy(aircraftType = type.label.take(120), aircraftTypeCode = type.code?.take(120), aircraftTypeSource = type.source?.take(120))
                changed = true
            }
        }
        if (changed) dirty = true
        return changed
    }

    /** Coalesce samples into at most one disk write every five minutes, plus a shutdown flush. */
    @Synchronized
    fun flush(force: Boolean = false) {
        if (!enabled || file == null || corruptFile) return
        prune(clock())
        if (!dirty || (!force && lastFlushAttempt?.let { clock() - it < 5 * 60_000 } == true)) return
        lastFlushAttempt = clock()
        try {
            // Compute the byte budget once instead of repeatedly serializing a large archive.
            val sizes = passages.map { json.encodeToString(it).toByteArray(Charsets.UTF_8).size }
            var byteCount = 2L + sizes.sumOf { it.toLong() } + (sizes.size - 1).coerceAtLeast(0)
            var removeCount = 0
            while (byteCount > maxFileBytes && removeCount < sizes.size) {
                byteCount -= sizes[removeCount] + if (removeCount < sizes.lastIndex) 1 else 0
                removeCount++
            }
            if (removeCount > 0) { capacityLimitedAt = clock(); passages.subList(0, removeCount).clear() }
            val bytes = json.encodeToString(passages.toList()).toByteArray(Charsets.UTF_8)
            require(bytes.size <= maxFileBytes)
            val target = file.toAbsolutePath()
            Files.createDirectories(target.parent)
            val temporary = target.resolveSibling(target.fileName.toString() + ".tmp")
            FileChannel.open(temporary, StandardOpenOption.CREATE, StandardOpenOption.TRUNCATE_EXISTING, StandardOpenOption.WRITE).use { channel ->
                val buffer = ByteBuffer.wrap(bytes)
                while (buffer.hasRemaining()) channel.write(buffer)
                channel.force(true)
            }
            // An interrupted write cannot truncate the last successfully saved history.
            Files.move(temporary, target, StandardCopyOption.REPLACE_EXISTING, StandardCopyOption.ATOMIC_MOVE)
            if (metadataFile != null) {
                val metaTemporary = metadataFile.resolveSibling(metadataFile.fileName.toString() + ".tmp")
                Files.writeString(metaTemporary, json.encodeToString(Metadata(metadata.allAircraftSince, capacityLimitedAt)))
                Files.move(metaTemporary, metadataFile, StandardCopyOption.REPLACE_EXISTING, StandardCopyOption.ATOMIC_MOVE)
            }
            dirty = false
            storageStatus = "saved"
        } catch (e: Exception) {
            if (storageStatus != "unavailable") log.warn("Passage history cannot be saved; live display is unaffected", e)
            storageStatus = "unavailable"
        }
    }

    @Synchronized
    override fun close() { closed = true; flush(force = true) }

    companion object {
        private const val PASSAGE_GAP_MS = 10 * 60_000L
    }
}
