package com.codingpitch.aircrafttracker.aircraft

/** Database flags identify aircraft; callsigns alone are only a possible match. */
object MilitaryIdentity {
    private val hexes: Set<String> by lazy {
        requireNotNull(javaClass.getResourceAsStream("/aircraft/military-hex.txt"))
            .bufferedReader().useLines { lines -> lines.filter { it.matches(Regex("[0-9A-F]{6}")) }.toSet() }
    }
    data class Match(val status: String, val basis: String?)
    fun classify(hex: String, flight: String?, dbFlags: Int? = null): Match = when {
        dbFlags != null && dbFlags and 1 != 0 -> Match("military", "Receiver military database flag")
        hex.uppercase() in hexes -> Match("military", "tar1090 aircraft database military flag")
        flight?.trim()?.uppercase()?.matches(Regex("WOLF\\s*[0-9]{1,4}")) == true ->
            Match("possible", "WOLF callsign hint only; aircraft identity unverified")
        else -> Match("unknown", null)
    }
}
