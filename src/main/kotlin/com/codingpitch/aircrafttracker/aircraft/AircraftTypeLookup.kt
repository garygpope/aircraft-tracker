package com.codingpitch.aircrafttracker.aircraft

import io.ktor.client.HttpClient
import io.ktor.client.request.get
import io.ktor.client.statement.bodyAsText
import java.io.Closeable
import kotlinx.coroutines.*
import kotlinx.coroutines.sync.Semaphore
import kotlinx.coroutines.sync.withPermit
import kotlinx.serialization.Serializable
import kotlinx.serialization.SerialName
import kotlinx.serialization.json.Json

/** Registry identity by aircraft hex, independent of the route/callsign. */
data class AircraftTypeResult(val status: String, val label: String? = null, val code: String? = null, val source: String? = null)
@Serializable private data class TypeEnvelope(val response: TypeResponse)
@Serializable private data class TypeResponse(val aircraft: TypeRecord? = null)
@Serializable private data class TypeRecord(val mode_s: String = "", val type: String = "", val manufacturer: String = "", val icao_type: String = "")
@Serializable private data class HexTypeRecord(
    @SerialName("ModeS") val hex: String = "", @SerialName("Type") val type: String = "",
    @SerialName("Manufacturer") val manufacturer: String = "", @SerialName("ICAOTypeCode") val code: String = "")

class AircraftTypeLookup(
    private val client: HttpClient,
    private val baseUrl: String = "https://api.adsbdb.com/v0",
    private val fallbackUrl: String = "https://hexdb.io/api/v1/aircraft",
    private val clock: () -> Long = System::currentTimeMillis
) : Closeable {
    private data class Entry(val result: AircraftTypeResult, val expires: Long)
    private val cache = LinkedHashMap<String, Entry>()
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private val permits = Semaphore(2)
    private val json = Json { ignoreUnknownKeys = true }

    @Synchronized fun typeFor(rawHex: String): AircraftTypeResult {
        val hex = rawHex.uppercase()
        if (!hex.matches(Regex("[A-F0-9]{6}"))) return AircraftTypeResult("not-found")
        cache[hex]?.takeIf { it.expires > clock() }?.let { return it.result }
        if (cache.size >= 512 && hex !in cache) {
            val candidate = cache.entries.firstOrNull { it.value.result.status != "pending" }
                ?: return AircraftTypeResult("unavailable")
            cache.remove(candidate.key)
        }
        val pending = AircraftTypeResult("pending")
        cache[hex] = Entry(pending, Long.MAX_VALUE)
        scope.launch {
            permits.withPermit {
                fun result(id: String, model: String, maker: String, code: String, source: String): AircraftTypeResult? {
                    if (!id.equals(hex, true)) return null
                    val cleanCode = code.takeIf { it.matches(Regex("[A-Z0-9]{2,4}")) }
                    val cleanModel = model.trim().takeIf { it.isNotBlank() && !it.equals("unknown", true) }?.take(120)
                    val cleanMaker = maker.trim().takeIf { it.isNotBlank() && !it.equals("unknown", true) }?.take(80)
                    val label = if (cleanModel == null) cleanCode else if (cleanMaker == null || cleanModel.startsWith(cleanMaker, true))
                        cleanModel else "$cleanMaker $cleanModel"
                    return label?.let { AircraftTypeResult("found", it.take(120), cleanCode, source) }
                }
                var failed = false
                val primary = try {
                    val response = client.get("${baseUrl.trimEnd('/')}/aircraft/$hex")
                    if (response.status.value == 200) {
                        val a = json.decodeFromString<TypeEnvelope>(response.bodyAsText()).response.aircraft
                        a?.let { result(it.mode_s, it.type, it.manufacturer, it.icao_type, "adsbdb") }
                    } else { failed = response.status.value != 404; null }
                } catch (e: CancellationException) { throw e } catch (_: Exception) { failed = true; null }
                val fallback = if (primary != null) null else try {
                    val response = client.get("${fallbackUrl.trimEnd('/')}/$hex")
                    if (response.status.value == 200) {
                        val a = json.decodeFromString<HexTypeRecord>(response.bodyAsText())
                        result(a.hex, a.type, a.manufacturer, a.code, "HexDB")
                    } else { failed = failed || response.status.value != 404; null }
                } catch (e: CancellationException) { throw e } catch (_: Exception) { failed = true; null }
                val value = primary ?: fallback ?: AircraftTypeResult(if (failed) "unavailable" else "not-found")
                val ttl = when (value.status) { "found" -> 24 * 60 * 60_000L; "not-found" -> 6 * 60 * 60_000L; else -> 5 * 60_000L }
                synchronized(this@AircraftTypeLookup) { cache[hex] = Entry(value, clock() + ttl) }
            }
        }
        return pending
    }
    override fun close() { scope.cancel(); client.close() }
}
